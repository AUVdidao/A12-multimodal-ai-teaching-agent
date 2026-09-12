package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"lessonforge.local/backend/internal/agent"
	"lessonforge.local/backend/internal/capability"
	"lessonforge.local/backend/internal/generation"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/parser"
	"lessonforge.local/backend/internal/platform/config"
	"lessonforge.local/backend/internal/platform/crypto"
	"lessonforge.local/backend/internal/platform/database"
	"lessonforge.local/backend/internal/platform/httpapi"
	"lessonforge.local/backend/internal/platform/storage"
	"lessonforge.local/backend/internal/pptengine"
	"lessonforge.local/backend/internal/rag"
	"lessonforge.local/backend/internal/templatebinding"
)

func main() {
	// main 是服务进程的组合根：这里只负责读取配置、创建基础设施、
	// 注入各个子系统的依赖并启动 Worker/HTTP 服务；业务规则仍由各自
	// internal 包负责，避免把 Mission、Agent 或 PPT Engine 逻辑堆在入口文件中。
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	// 配置失败时不启动半可用服务。Config 同时提供数据库、文件存储、
	// 外部服务地址、超时和 Generation 开关等运行边界。
	cfg, err := config.Load()
	if err != nil {
		logger.Error("configuration failed", "error", err)
		os.Exit(2)
	}

	// 统一的进程生命周期上下文：收到 Ctrl+C 或 SIGTERM 后，所有后台
	// Worker 都会收到取消信号，HTTP 服务随后进入优雅关闭流程。
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// PostgreSQL 是业务状态的权威来源。服务启动必须先连接数据库并完成
	// migration，之后才允许构造 Store、启动 Worker 和暴露 HTTP API。
	pool, err := database.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		logger.Error("database unavailable", "error", err)
		os.Exit(2)
	}
	defer pool.Close()
	migrationDir := os.Getenv("LESSONFORGE_MIGRATIONS_DIR")
	if migrationDir == "" {
		migrationDir = "./migrations"
	}
	if err := database.Migrate(ctx, pool, migrationDir); err != nil {
		logger.Error("migration failed", "error", err)
		os.Exit(2)
	}

	// crypto.Service 负责保护教师 Model Connection 中的敏感凭据；
	// storage.Service 负责文件内容，数据库只保存文件元数据、哈希和 key。
	crypt, err := crypto.New(cfg.EncryptionKey)
	if err != nil {
		logger.Error("crypto configuration failed", "error", err)
		os.Exit(2)
	}
	files, err := storage.New(cfg.StorageRoot, cfg.MaxUploadBytes)
	if err != nil {
		logger.Error("storage unavailable", "error", err)
		os.Exit(2)
	}

	// Store 是数据库业务状态层：Mission、AgentRun、GenerationJob、
	// lease/fencing、Draft、LockedSpecification 和 Artifact 的读写都应
	// 经过 Store，而不是由 HTTP handler 或 Worker 直接拼接业务 SQL。
	store := database.NewStore(pool)
	store.ConfigureStorageRoot(cfg.StorageRoot)
	store.ConfigureWorker("lessonforge-server", cfg.JobLeaseDuration)
	// The Java bridge is an optional enrichment source for immutable template
	// bindings. Its absence never prevents approval; an incomplete or missing
	// profile simply leaves the binding identity-only until Generate is requested.
	store.TemplateProfile = capability.NewAuthenticatedClient(
		cfg.TemplateCapabilityURL,
		cfg.TemplateCapabilityBearerToken,
		cfg.ModelRequestTimeout,
		cfg.MaxModelResponseBytes,
	)

	// 外部能力统一通过适配器注入：ModelClient 负责模型调用，JavaClient
	// 负责 RAG/解析服务调用。这样主业务只依赖 LessonForge 的接口和契约，
	// 不依赖外部 Java 服务的内部实现。
	models := model.NewClient(cfg.ModelRequestTimeout, cfg.MaxModelResponseBytes)
	ragClient := rag.NewJavaClient(cfg.RAGBaseURL, cfg.ModelRequestTimeout, cfg.MaxModelResponseBytes)
	ragClient.SetServiceBearerToken(cfg.RAGBearerToken)
	ragClient.ConfigureResources(store, files)
	var parserAdapter parser.Adapter
	if cfg.RAGBaseURL != "" {
		// 配置了 LessonForge Java RAG 时，Parser 优先走该独立契约。
		// 这里不是“调用失败后再静默切换”的 fallback；真实失败必须暴露。
		// LessonForge's independent Java intake/parser/index contract has
		// precedence whenever RAG is configured. This is not a fallback to the
		// old Parser URL and never invokes the RequirementSummary-gated route.
		parserAdapter = rag.NewLessonForgeParserAdapter(ragClient, store)
	} else {
		// 未配置 Java RAG 时才使用兼容 Parser 适配器；该模式不会把未配置
		// 或失败伪装成解析成功，文件状态仍由 Parser Worker 按结果推进。
		// Explicit compatibility mode for deployments that have not configured
		// Java LessonForge RAG yet. A RAG error never silently falls through here.
		parserAdapter = parser.NewClientWithMaxResponseBytes(cfg.ParserURL, cfg.ParserRequestTimeout, cfg.MaxParserResponseBytes)
	}

	// Parser Worker 异步处理文件解析，把文件从 PENDING 推进到 READY/FAILED，
	// 使上传请求不必同步等待外部解析服务。
	parser.StartWorker(ctx, &parser.Worker{Store: store, Storage: files, Adapter: parserAdapter}, cfg.ParserInterval)

	// Runtime 是 Agent 的依赖集合：Agent 通过它访问 Store、模型、RAG、
	// 模板能力和加密服务。Agent Worker 再从数据库领取 AgentRun 执行，
	// 因此 HTTP 请求本身不直接承担长时间模型调用。
	runtime := &agent.Runtime{Store: store, Crypto: crypt, Models: models, RAG: ragClient, Capability: templatebinding.Resolver{Files: store, StorageRoot: cfg.StorageRoot}, MaxToolCalls: 8, ToolTimeout: cfg.ToolRequestTimeout}
	agent.StartWorker(ctx, store, runtime, cfg.AgentRunTimeout)
	agent.StartMaintenance(ctx, store, files)

	// Generation 是 LockedSpecification 之后的下游阶段，默认关闭。
	// 显式启用后，Worker 才会通过 PPT Engine adapter 调用 compose-plan/execute，
	// 再把共享目录中的 Engine artifact 校验后导入 LessonForge Storage。
	if cfg.EnableGenerationWorker {
		engine := pptengine.NewClient(cfg.PPTEngineBaseURL, cfg.PPTEngineArtifactRoot, cfg.PPTEngineRequestTimeout)
		generationWorker := &generation.Worker{Store: store, Engine: engine, Composer: engine, Storage: files, OwnerResolver: store.MissionOwner, EngineSharedStorageRoot: cfg.PPTEngineSharedStorageRoot}
		generationWorker.Start(ctx)
	}

	// HTTP API 是对外入口，Router 内部负责认证、owner 隔离、Mission/文件/
	// Agent/Planning/Generation/Artifact 路由分发；handler 通过 Store 和 Runtime
	// 完成实际业务操作。
	server := httpapi.NewServer(httpapi.Config{
		Addr:                      cfg.Addr,
		SessionCookie:             cfg.SessionCookie,
		SessionTTL:                cfg.SessionTTL,
		MaxUploadBytes:            cfg.MaxUploadBytes,
		ModelRequestTimeout:       cfg.ModelRequestTimeout,
		ModelExecutionBearerToken: cfg.ModelExecutionBearerToken,
		CORSOrigins:               cfg.CORSOrigins,
	}, pool, store, crypt, files, models, runtime)
	httpServer := &http.Server{Addr: cfg.Addr, Handler: server.Router(), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 2 * time.Minute, WriteTimeout: 2 * time.Minute, IdleTimeout: 2 * time.Minute}
	go func() { logger.Info("LessonForge Go backend listening", "addr", cfg.Addr) }()
	go func() {
		if err := httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("http server failed", "error", err)
			stop()
		}
	}()

	// 主 goroutine 等待进程取消；收到信号后停止接收新请求，并给正在处理的
	// HTTP 请求最多 10 秒完成收尾。后台 Worker 使用同一个 ctx 退出。
	<-ctx.Done()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		logger.Error("http server shutdown failed", "error", err)
	}
	logger.Info("LessonForge Go backend stopped")
}
