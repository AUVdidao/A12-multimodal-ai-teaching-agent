package agent

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/platform/crypto"
	"lessonforge.local/backend/internal/platform/database"
	"lessonforge.local/backend/internal/rag"
)

func TestWorkerRuntimeAmbiguityIsExactForEveryOutputStage(t *testing.T) {
	stages := []struct {
		name     string
		response string
		activity string
	}{
		{name: "MESSAGE", response: `{"type":"MESSAGE","content":"runtime message"}`, activity: database.AgentOutputStageMessage},
		{name: "QUESTION", response: `{"type":"QUESTION","question":"Choose one","questionType":"SINGLE_CHOICE","options":["A","B"]}`, activity: database.AgentOutputStageQuestion},
		{name: "PLAN_DRAFT", response: `{"type":"PLAN_DRAFT","markdown":"# Draft","structuredPlan":{"slides":[{"title":"Intro"}]}}`, activity: database.AgentOutputStagePlan},
	}
	for _, test := range stages {
		t.Run(test.name, func(t *testing.T) {
			f := newAgentWorkerFixture(t, test.response)
			f.store.ConfigureWorker("agent-connection-loss-a", 500*time.Millisecond)
			interrupted := false
			f.store.SetBeforeAgentOutputCommitHookForTest(func(ctx context.Context, tx pgx.Tx) error {
				if interrupted {
					return nil
				}
				var backendPID int
				if err := tx.QueryRow(ctx, `SELECT pg_backend_pid()`).Scan(&backendPID); err != nil {
					return err
				}
				var terminated bool
				if err := f.store.DB.QueryRow(ctx, `SELECT pg_terminate_backend($1)`, backendPID).Scan(&terminated); err != nil {
					return err
				}
				if !terminated {
					return errors.New("test PostgreSQL backend was not terminated")
				}
				interrupted = true
				return nil
			})
			if err := RunOnce(f.ctx, f.store, f.runtime, time.Second); err == nil {
				t.Fatal("real PostgreSQL connection interruption did not reach the worker")
			}
			f.store.SetBeforeAgentOutputCommitHookForTest(nil)
			if !interrupted {
				t.Fatal("real output transaction connection interruption was not exercised")
			}
			// No test SQL changes the run state: a fresh production RunOnce reclaims
			// the expired lease after the terminated connection, then persists the
			// same run/stage exactly once.
			time.Sleep(650 * time.Millisecond)

			// A new worker pass receives the same run/stage response and must
			// reconcile the committed output without creating another row.
			if err := RunOnce(f.ctx, f.store, f.runtime, time.Second); err != nil {
				t.Fatalf("retry through production RunOnce: %v", err)
			}
			for _, stage := range []string{database.AgentOutputStageMessage, database.AgentOutputStageQuestion, database.AgentOutputStagePlan} {
				found, err := f.store.ReconcileAgentOutput(f.ctx, f.missionID, f.runID, stage)
				if err != nil {
					t.Fatal(err)
				}
				if found != (stage == test.activity) {
					t.Fatalf("stage %s reconciliation = %t, want %t", stage, found, stage == test.activity)
				}
			}
			var messages, questionMessages, questions, drafts, activities int
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='MESSAGE'`, f.missionID, f.runID).Scan(&messages); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION_MESSAGE'`, f.missionID, f.runID).Scan(&questionMessages); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM questions WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION'`, f.missionID, f.runID).Scan(&questions); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage='PLAN_DRAFT'`, f.missionID, f.runID).Scan(&drafts); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND idempotency_key LIKE $2`, f.missionID, "agent-run:"+f.runID+":%").Scan(&activities); err != nil {
				t.Fatal(err)
			}
			wantMessages, wantQuestionMessages, wantQuestions, wantDrafts, wantActivities := 0, 0, 0, 0, 2
			switch test.activity {
			case database.AgentOutputStageMessage:
				wantMessages = 1
			case database.AgentOutputStageQuestion:
				wantQuestionMessages, wantQuestions, wantActivities = 1, 1, 3
			case database.AgentOutputStagePlan:
				wantDrafts = 1
			}
			if messages != wantMessages || questionMessages != wantQuestionMessages || questions != wantQuestions || drafts != wantDrafts || activities != wantActivities {
				t.Fatalf("stage %s exact rows = message %d question-message %d question %d draft %d activity %d; want %d/%d/%d/%d/%d", test.activity, messages, questionMessages, questions, drafts, activities, wantMessages, wantQuestionMessages, wantQuestions, wantDrafts, wantActivities)
			}
			var status string
			if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM agent_runs WHERE id=$1`, f.runID).Scan(&status); err != nil {
				t.Fatal(err)
			}
			wantStatus := "COMPLETED"
			if test.activity == database.AgentOutputStageQuestion {
				wantStatus = "WAITING_INPUTS"
			}
			if status != wantStatus {
				t.Fatalf("run status = %s, want %s", status, wantStatus)
			}
		})
	}
}

func TestRuntimeToolCallingReplaysOneWireTurnThroughProviderClient(t *testing.T) {
	f := newAgentWorkerFixture(t, `{"type":"MESSAGE","content":"unused"}`)
	draftID := uuid.NewString()
	if _, err := f.store.DB.Exec(f.ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id) VALUES($1,$2,1,'# Current plan','{"slides":[{"title":"TCP"}]}'::jsonb,$3)`, draftID, f.missionID, f.runID); err != nil {
		t.Fatal(err)
	}
	draft, err := f.store.CurrentDraft(f.ctx, f.ownerID, f.missionID)
	if err != nil {
		t.Fatal(err)
	}
	expectedPlan := marshal(draft)
	provider := &recordingProviderTransport{responses: []string{
		`{"choices":[{"message":{"content":"","reasoning_content":"opaque reasoning","tool_calls":[{"id":"call-1","type":"function","function":{"name":"get_current_plan","arguments":"{\"include\":\"latest\"}"}},{"id":"call-2","type":"function","function":{"name":"get_template_capability","arguments":"{\"detail\":\"semantic\"}"}}]}}]}`,
		`{"choices":[{"message":{"content":"{\"type\":\"QUESTION\",\"question\":\"Which grade level?\",\"questionType\":\"TEXT\",\"options\":[]}"}}]}`,
	}}
	f.runtime.chat = nil
	f.runtime.Models = model.NewClientWithTransport(time.Second, 1<<20, provider)
	f.runtime.MaxToolCalls = 2
	if err := RunOnce(f.ctx, f.store, f.runtime, time.Second); err != nil {
		t.Fatalf("Runtime tool loop: %v", err)
	}
	if len(provider.requests) != 2 {
		t.Fatalf("provider request count = %d, want 2", len(provider.requests))
	}
	var replay struct {
		Messages []struct {
			Role             string           `json:"role"`
			Content          string           `json:"content"`
			ReasoningContent string           `json:"reasoning_content"`
			ToolCallID       string           `json:"tool_call_id"`
			ToolCalls        []model.ToolCall `json:"tool_calls"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(provider.requests[1], &replay); err != nil {
		t.Fatal(err)
	}
	if len(replay.Messages) < 5 {
		t.Fatalf("replayed message count = %d, want system/user/files plus assistant and two tools", len(replay.Messages))
	}
	assistantIndex := len(replay.Messages) - 3
	assistant := replay.Messages[assistantIndex]
	if assistant.Role != "assistant" || assistant.ReasoningContent != "opaque reasoning" || len(assistant.ToolCalls) != 2 {
		t.Fatalf("replayed assistant turn = %#v", assistant)
	}
	assistantCount := 0
	for _, message := range replay.Messages {
		if message.Role == "assistant" {
			assistantCount++
		}
	}
	if assistantCount != 1 {
		t.Fatalf("assistant message count = %d, want 1", assistantCount)
	}
	if assistant.ToolCalls[0].ID != "call-1" || assistant.ToolCalls[0].Function.Name != "get_current_plan" || assistant.ToolCalls[0].Function.Arguments != `{"include":"latest"}` || assistant.ToolCalls[1].ID != "call-2" || assistant.ToolCalls[1].Function.Name != "get_template_capability" || assistant.ToolCalls[1].Function.Arguments != `{"detail":"semantic"}` {
		t.Fatalf("replayed tool calls = %#v", assistant.ToolCalls)
	}
	if replay.Messages[assistantIndex+1].Role != "tool" || replay.Messages[assistantIndex+1].ToolCallID != "call-1" || replay.Messages[assistantIndex+1].Content != expectedPlan {
		t.Fatalf("first replayed tool result = %#v", replay.Messages[assistantIndex+1])
	}
	if replay.Messages[assistantIndex+2].Role != "tool" || replay.Messages[assistantIndex+2].ToolCallID != "call-2" || replay.Messages[assistantIndex+2].Content != `{"error":"TEMPLATE_CAPABILITY_NOT_CONFIGURED"}` {
		t.Fatalf("second replayed tool result = %#v", replay.Messages[assistantIndex+2])
	}
}

func TestRuntimeRebuildsMissionQuestionsAnswersAndDraftFromPostgreSQL(t *testing.T) {
	f := newAgentWorkerFixture(t, `{"type":"MESSAGE","content":"unused"}`)
	questionID := uuid.NewString()
	if _, err := f.store.DB.Exec(f.ctx, `INSERT INTO questions(id,mission_id,agent_run_id,output_stage,text,question_type,options_json) VALUES($1,$2,$3,'QUESTION','Which grade should this lesson target?','SINGLE_CHOICE','["高中","大学"]'::jsonb)`, questionID, f.missionID, f.runID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.DB.Exec(f.ctx, `INSERT INTO question_answers(id,question_id,selected_values_json,text_answer) VALUES($1,$2,'["大学"]'::jsonb,'')`, uuid.NewString(), questionID); err != nil {
		t.Fatal(err)
	}
	draftID := uuid.NewString()
	if _, err := f.store.DB.Exec(f.ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id,output_stage) VALUES($1,$2,1,'# Existing draft','{"slides":[{"title":"Existing lesson"}]}'::jsonb,$3,'PLAN_DRAFT')`, draftID, f.missionID, f.runID); err != nil {
		t.Fatal(err)
	}

	var request model.ChatRequest
	f.runtime.chat = func(_ context.Context, _ model.ResolvedConnection, got model.ChatRequest) (model.ChatResponse, error) {
		request = got
		return model.ChatResponse{Content: `{"type":"MESSAGE","content":"context rebuilt"}`, RawStatus: 200}, nil
	}
	if err := RunOnce(f.ctx, f.store, f.runtime, time.Second); err != nil {
		t.Fatalf("runtime with durable context: %v", err)
	}
	joined := ""
	for _, message := range request.Messages {
		joined += message.Role + "\n" + message.Content + "\n"
	}
	for _, required := range []string{"Current Mission (server-owned):", "Agent runtime", "Persisted Mission questions and latest answers:", "Which grade should this lesson target?", "大学", "Current Planning Draft (server-owned):", "# Existing draft", "Existing lesson"} {
		if !strings.Contains(joined, required) {
			t.Fatalf("provider context missing %q: %s", required, joined)
		}
	}
}

func TestQuestionAnswerToDraftApprovalStopsAtLockedSpecification(t *testing.T) {
	dsn := os.Getenv("LESSONFORGE_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("LESSONFORGE_TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	pool, err := database.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if err := database.Migrate(ctx, pool, filepath.Join("..", "..", "migrations")); err != nil {
		t.Fatal(err)
	}
	unlock, err := database.AcquireIntegrationTestLock(ctx, pool)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(unlock)
	store := database.NewStore(pool)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Question lifecycle", "question-lifecycle-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	crypt, err := crypto.New([]byte("01234567890123456789012345678901"))
	if err != nil {
		t.Fatal(err)
	}
	encrypted, err := crypt.Encrypt("synthetic-provider-key")
	if err != nil {
		t.Fatal(err)
	}
	connection, err := store.CreateConnection(ctx, owner.ID, model.ModelConnection{Name: "Question lifecycle " + suffix, Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://provider.invalid", ModelID: "lifecycle-model", KeyHint: "test"}, encrypted)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE model_connections SET enabled=true,verification_status='VERIFIED' WHERE id=$1`, connection.ID); err != nil {
		t.Fatal(err)
	}
	var missionID, messageID int64
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title,description,selected_model_connection_id) VALUES($1,'SELF_CREATED','Lifecycle mission','Build a lesson for university students',$2) RETURNING id`, owner.ID, connection.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','Start planning this lesson') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	snapshot, err := json.Marshal(map[string]any{"connectionId": connection.ID, "protocol": connection.Protocol, "baseUrl": connection.BaseURL, "modelId": connection.ModelID, "encryptedApiKey": encrypted})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status) VALUES($1,$2,$3,$4,$5,'QUEUED')`, runID, missionID, messageID, connection.ID, snapshot); err != nil {
		t.Fatal(err)
	}

	responses := []string{
		`{"type":"QUESTION","question":"Which learner level should the lesson target?","questionType":"SINGLE_CHOICE","options":["高中","大学"]}`,
		`{"type":"PLAN_DRAFT","markdown":"# University lesson draft","structuredPlan":{"slides":[{"title":"Learning goals"},{"title":"Core concept"}]}}`,
	}
	var calls int
	var requests []model.ChatRequest
	runtime := &Runtime{Store: store, Crypto: crypt, Models: model.NewClient(time.Second, 1<<20), MaxToolCalls: 1}
	runtime.SetChatForTest(func(_ context.Context, _ model.ResolvedConnection, request model.ChatRequest) (model.ChatResponse, error) {
		requests = append(requests, request)
		if calls >= len(responses) {
			return model.ChatResponse{}, errors.New("unexpected lifecycle model call")
		}
		response := model.ChatResponse{Content: responses[calls], RawStatus: 200}
		calls++
		return response, nil
	})
	if err := RunOnce(ctx, store, runtime, time.Second); err != nil {
		t.Fatalf("question AgentRun: %v", err)
	}
	var firstStatus string
	if err := pool.QueryRow(ctx, `SELECT status FROM agent_runs WHERE id=$1`, runID).Scan(&firstStatus); err != nil {
		t.Fatal(err)
	}
	if firstStatus != "WAITING_INPUTS" {
		t.Fatalf("question run status = %s, want WAITING_INPUTS", firstStatus)
	}
	questions, err := store.Questions(ctx, owner.ID, missionID)
	if err != nil {
		t.Fatal(err)
	}
	if len(questions) != 1 || questions[0].LatestAnswer != nil {
		t.Fatalf("persisted question before answer = %#v", questions)
	}
	answerID, nextRunID, err := store.AnswerQuestion(ctx, owner.ID, questions[0].ID, "", []string{"大学"})
	if err != nil {
		t.Fatalf("answer question: %v", err)
	}
	if answerID == "" || nextRunID == "" || nextRunID == runID {
		t.Fatalf("answer identity = answer %q next run %q first run %q", answerID, nextRunID, runID)
	}
	if err := RunOnce(ctx, store, runtime, time.Second); err != nil {
		t.Fatalf("draft AgentRun: %v", err)
	}
	if calls != 2 || len(requests) != 2 {
		t.Fatalf("lifecycle model calls = %d requests = %d, want 2/2", calls, len(requests))
	}
	secondContext := ""
	for _, message := range requests[1].Messages {
		secondContext += message.Content + "\n"
	}
	for _, required := range []string{"Which learner level should the lesson target?", "大学", "Build a lesson for university students"} {
		if !strings.Contains(secondContext, required) {
			t.Fatalf("answer-run context missing %q: %s", required, secondContext)
		}
	}
	draft, err := store.CurrentDraft(ctx, owner.ID, missionID)
	if err != nil {
		t.Fatalf("current draft: %v", err)
	}
	if draft.Version != 1 || draft.Markdown != "# University lesson draft" {
		t.Fatalf("draft = %#v", draft)
	}

	digest := strings.Repeat("a", 64)
	var templateFileID int64
	if err := pool.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,'lesson-template.pptx','application/vnd.openxmlformats-officedocument.presentationml.presentation',12,$3) RETURNING id`, owner.ID, "templates/"+suffix+".pptx", digest).Scan(&templateFileID); err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,provenance,parse_status,uploaded_by) VALUES($1,$2,'TEMPLATE','TEACHER','READY',$3)`, missionID, templateFileID, owner.ID); err != nil {
		t.Fatal(err)
	}
	locked, err := store.ApproveDraft(ctx, owner.ID, draft.ID)
	if err != nil {
		t.Fatalf("approve draft: %v", err)
	}
	if locked.ID == "" || locked.SourceDraftID != draft.ID || locked.Version != 1 || locked.ContentHash == "" {
		t.Fatalf("locked specification = %#v", locked)
	}
	var lockedCount, generationJobs int
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM locked_specifications WHERE mission_id=$1`, missionID).Scan(&lockedCount); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `SELECT count(*) FROM generation_jobs WHERE mission_id=$1`, missionID).Scan(&generationJobs); err != nil {
		t.Fatal(err)
	}
	if lockedCount != 1 || generationJobs != 0 {
		t.Fatalf("approval boundary rows = locked %d generation_jobs %d", lockedCount, generationJobs)
	}
	if _, err := pool.Exec(ctx, `UPDATE locked_specifications SET content_hash=$1 WHERE id=$2`, strings.Repeat("b", 64), locked.ID); err == nil {
		t.Fatal("locked specification direct update unexpectedly succeeded")
	}

	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		if _, err := pool.Exec(cleanupCtx, `ALTER TABLE locked_specifications DISABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
			t.Errorf("disable immutable trigger for cleanup: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, missionID); err != nil {
			t.Errorf("cleanup mission: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM execution_audits WHERE actor_user_id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup audits: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM file_objects WHERE owner_user_id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup file objects: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM model_connections WHERE owner_user_id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup connections: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, owner.ID); err != nil {
			t.Errorf("cleanup user: %v", err)
		}
		if _, err := pool.Exec(cleanupCtx, `ALTER TABLE locked_specifications ENABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
			t.Errorf("enable immutable trigger after cleanup: %v", err)
		}
	})
}

func TestRuntimeReadMaterialRejectsAnotherTeacherBeforeRAG(t *testing.T) {
	f := newAgentWorkerFixture(t, `{"type":"MESSAGE","content":"unused"}`)
	other, err := f.store.CreateUser(f.ctx, "Other teacher", "other-"+uuid.NewString()+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if _, err := f.store.DB.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1`, other.ID); err != nil {
			t.Errorf("cleanup other teacher: %v", err)
		}
	})
	// A non-nil client makes this prove the authorization guard, rather than
	// the unrelated RAG_NOT_CONFIGURED branch. The URL must never be called.
	f.runtime.RAG = rag.NewClient("http://127.0.0.1:1", "/search", "/read")
	result := f.runtime.tool(f.ctx, f.missionID, other.ID, model.ToolCall{Type: "function", Function: model.ToolCallFunction{
		Name:      "read_material",
		Arguments: `{"fileId":1,"locator":"chunk:1"}`,
	}})
	if result != `{"error":"RAG_FILE_NOT_AUTHORIZED"}` {
		t.Fatalf("cross-owner read result = %s", result)
	}
	var activityCount int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM activity_events WHERE mission_id=$1 AND event_type='AGENT_TOOL_CALLED'`, f.missionID).Scan(&activityCount); err != nil {
		t.Fatal(err)
	}
	if activityCount != 0 {
		t.Fatalf("cross-owner read created %d activity events, want 0", activityCount)
	}
}

func TestWorkerRetriesOneInvalidPlanThenPersistsValidDraft(t *testing.T) {
	f := newAgentWorkerFixture(t, `{"type":"PLAN_DRAFT","markdown":"","structuredPlan":{"slides":[]}}`)
	responses := []string{
		`{"type":"PLAN_DRAFT","markdown":"","structuredPlan":{"slides":[]}}`,
		`{"type":"PLAN_DRAFT","markdown":"# Repaired draft","structuredPlan":{"slides":[{"title":"课程目标"}]}}`,
	}
	var calls int
	var requests []model.ChatRequest
	f.runtime.chat = func(_ context.Context, _ model.ResolvedConnection, request model.ChatRequest) (model.ChatResponse, error) {
		requests = append(requests, request)
		if calls >= len(responses) {
			return model.ChatResponse{}, errors.New("unexpected repair request")
		}
		response := model.ChatResponse{Content: responses[calls], RawStatus: 200}
		calls++
		return response, nil
	}
	if err := RunOnce(f.ctx, f.store, f.runtime, time.Second); err != nil {
		t.Fatalf("bounded output repair: %v", err)
	}
	if calls != 2 || len(requests) != 2 {
		t.Fatalf("provider calls = %d requests = %d, want 2/2", calls, len(requests))
	}
	if len(requests[1].Messages) == 0 || requests[1].Messages[len(requests[1].Messages)-1].Role != "user" || !strings.Contains(requests[1].Messages[len(requests[1].Messages)-1].Content, "AGENT_INVALID_PLAN") {
		t.Fatalf("repair request tail = %#v", requests[1].Messages)
	}
	draft, err := f.store.CurrentDraft(f.ctx, f.ownerID, f.missionID)
	if err != nil {
		t.Fatalf("current draft: %v", err)
	}
	if draft.Version != 1 || draft.Markdown != "# Repaired draft" {
		t.Fatalf("draft = %#v", draft)
	}
	var drafts int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2`, f.missionID, f.runID).Scan(&drafts); err != nil {
		t.Fatal(err)
	}
	if drafts != 1 {
		t.Fatalf("draft rows = %d, want 1", drafts)
	}
}

type recordingProviderTransport struct {
	responses []string
	requests  [][]byte
}

func (p *recordingProviderTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	body, err := io.ReadAll(request.Body)
	if err != nil {
		return nil, err
	}
	p.requests = append(p.requests, body)
	index := len(p.requests) - 1
	if index >= len(p.responses) {
		return nil, errors.New("unexpected provider request")
	}
	return &http.Response{StatusCode: http.StatusOK, Body: io.NopCloser(strings.NewReader(p.responses[index])), Header: make(http.Header)}, nil
}

func TestWorkerRuntimeStageMismatchUsesExactRunAndStageIdentity(t *testing.T) {
	cases := []struct {
		name         string
		seedStage    string
		seedOutput   string
		targetStage  string
		targetOutput string
	}{
		{name: "MESSAGE-to-QUESTION", seedStage: database.AgentOutputStageMessage, seedOutput: `{"type":"MESSAGE","content":"seed message"}`, targetStage: database.AgentOutputStageQuestion, targetOutput: `{"type":"QUESTION","question":"target question","questionType":"TEXT","options":[]}`},
		{name: "MESSAGE-to-PLAN_DRAFT", seedStage: database.AgentOutputStageMessage, seedOutput: `{"type":"MESSAGE","content":"seed message"}`, targetStage: database.AgentOutputStagePlan, targetOutput: `{"type":"PLAN_DRAFT","markdown":"# target draft","structuredPlan":{"slides":[{"title":"Target"}]}}`},
		{name: "QUESTION-to-MESSAGE", seedStage: database.AgentOutputStageQuestion, seedOutput: `{"type":"QUESTION","question":"seed question","questionType":"TEXT","options":[]}`, targetStage: database.AgentOutputStageMessage, targetOutput: `{"type":"MESSAGE","content":"target message"}`},
		{name: "QUESTION-to-PLAN_DRAFT", seedStage: database.AgentOutputStageQuestion, seedOutput: `{"type":"QUESTION","question":"seed question","questionType":"TEXT","options":[]}`, targetStage: database.AgentOutputStagePlan, targetOutput: `{"type":"PLAN_DRAFT","markdown":"# target draft","structuredPlan":{"slides":[{"title":"Target"}]}}`},
		{name: "PLAN_DRAFT-to-MESSAGE", seedStage: database.AgentOutputStagePlan, seedOutput: `{"type":"PLAN_DRAFT","markdown":"# seed draft","structuredPlan":{"slides":[{"title":"Seed"}]}}`, targetStage: database.AgentOutputStageMessage, targetOutput: `{"type":"MESSAGE","content":"target message"}`},
		{name: "PLAN_DRAFT-to-QUESTION", seedStage: database.AgentOutputStagePlan, seedOutput: `{"type":"PLAN_DRAFT","markdown":"# seed draft","structuredPlan":{"slides":[{"title":"Seed"}]}}`, targetStage: database.AgentOutputStageQuestion, targetOutput: `{"type":"QUESTION","question":"target question","questionType":"TEXT","options":[]}`},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			f := newAgentWorkerFixture(t, test.seedOutput)
			if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET status='QUEUED',lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$1`, f.runID); err != nil {
				t.Fatal(err)
			}
			RunOnce(f.ctx, f.store, f.runtime, time.Second)
			if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET status='RUNNING',lease_token=$1,lease_expires_at=clock_timestamp()-interval '1 second',finished_at=NULL WHERE id=$2`, uuid.NewString(), f.runID); err != nil {
				t.Fatal(err)
			}
			bPool, err := database.Open(f.ctx, os.Getenv("LESSONFORGE_TEST_DATABASE_URL"))
			if err != nil {
				t.Fatalf("open independent stage worker pool: %v", err)
			}
			t.Cleanup(bPool.Close)
			storeB := database.NewStore(bPool)
			storeB.ConfigureWorker("agent-stage-worker-b", 10*time.Second)
			runtimeB := &Runtime{Store: storeB, Crypto: f.runtime.Crypto, Models: model.NewClient(time.Second, 1<<20), MaxToolCalls: 1, chat: func(context.Context, model.ResolvedConnection, model.ChatRequest) (model.ChatResponse, error) {
				return model.ChatResponse{Content: test.targetOutput, RawStatus: 200}, nil
			}}
			RunOnce(f.ctx, storeB, runtimeB, time.Second)

			var messageCount, questionMessageCount, questionCount, draftCount int
			queries := []struct {
				query string
				args  []any
				out   *int
			}{
				{`SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='MESSAGE'`, []any{f.missionID, f.runID}, &messageCount},
				{`SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION_MESSAGE'`, []any{f.missionID, f.runID}, &questionMessageCount},
				{`SELECT count(*) FROM questions WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage='QUESTION'`, []any{f.missionID, f.runID}, &questionCount},
				{`SELECT count(*) FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage='PLAN_DRAFT'`, []any{f.missionID, f.runID}, &draftCount},
			}
			for _, q := range queries {
				if err := f.store.DB.QueryRow(f.ctx, q.query, q.args...).Scan(q.out); err != nil {
					t.Fatal(err)
				}
			}
			if messageCount != countStage(test.seedStage, database.AgentOutputStageMessage)+countStage(test.targetStage, database.AgentOutputStageMessage) ||
				questionMessageCount != countStage(test.seedStage, database.AgentOutputStageQuestion)+countStage(test.targetStage, database.AgentOutputStageQuestion) ||
				questionCount != countStage(test.seedStage, database.AgentOutputStageQuestion)+countStage(test.targetStage, database.AgentOutputStageQuestion) ||
				draftCount != countStage(test.seedStage, database.AgentOutputStagePlan)+countStage(test.targetStage, database.AgentOutputStagePlan) {
				t.Fatalf("stage mismatch rows = message %d question-message %d question %d draft %d", messageCount, questionMessageCount, questionCount, draftCount)
			}
			for _, stage := range []string{database.AgentOutputStageMessage, database.AgentOutputStageQuestion, database.AgentOutputStagePlan} {
				found, err := f.store.ReconcileAgentOutput(f.ctx, f.missionID, f.runID, stage)
				if err != nil {
					t.Fatal(err)
				}
				want := stage == test.seedStage || stage == test.targetStage
				if found != want {
					t.Fatalf("reconcile stage %s = %t, want %t", stage, found, want)
				}
			}
			var status string
			if err := f.store.DB.QueryRow(f.ctx, `SELECT status FROM agent_runs WHERE id=$1`, f.runID).Scan(&status); err != nil {
				t.Fatal(err)
			}
			wantStatus := "COMPLETED"
			if test.targetStage == database.AgentOutputStageQuestion {
				wantStatus = "WAITING_INPUTS"
			}
			if status != wantStatus {
				t.Fatalf("stage mismatch run status = %s, want %s", status, wantStatus)
			}
		})
	}
}

func countStage(actual, expected string) int {
	if actual == expected {
		return 1
	}
	return 0
}

func TestRuntimeCancellationRejectsRetryForEveryOutputStage(t *testing.T) {
	stages := []struct {
		name     string
		response string
	}{
		{name: "MESSAGE", response: `{"type":"MESSAGE","content":"cancelled message"}`},
		{name: "QUESTION", response: `{"type":"QUESTION","question":"cancelled question","questionType":"TEXT","options":[]}`},
		{name: "PLAN_DRAFT", response: `{"type":"PLAN_DRAFT","markdown":"# cancelled","structuredPlan":{"slides":[{"title":"Cancelled"}]}}`},
	}
	for _, test := range stages {
		t.Run(test.name, func(t *testing.T) {
			f := newAgentWorkerFixture(t, test.response)
			if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET status='QUEUED',lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$1`, f.runID); err != nil {
				t.Fatal(err)
			}
			if _, err := f.store.CancelAgent(f.ctx, f.ownerID, f.runID); err != nil {
				t.Fatal(err)
			}
			RunOnce(f.ctx, f.store, f.runtime, time.Second)
			var messages, questions, drafts int
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage IS NOT NULL`, f.missionID, f.runID).Scan(&messages); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM questions WHERE mission_id=$1 AND agent_run_id=$2`, f.missionID, f.runID).Scan(&questions); err != nil {
				t.Fatal(err)
			}
			if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage IS NOT NULL`, f.missionID, f.runID).Scan(&drafts); err != nil {
				t.Fatal(err)
			}
			if messages != 0 || questions != 0 || drafts != 0 {
				t.Fatalf("cancelled stage %s produced outputs: messages %d questions %d drafts %d", test.name, messages, questions, drafts)
			}
		})
	}
}

func TestUnknownRuntimeOutputAndStageFailClosed(t *testing.T) {
	f := newAgentWorkerFixture(t, `{"type":"UNKNOWN"}`)
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE agent_runs SET status='QUEUED',lease_owner=NULL,lease_token=NULL,lease_expires_at=NULL,heartbeat_at=NULL WHERE id=$1`, f.runID); err != nil {
		t.Fatal(err)
	}
	RunOnce(f.ctx, f.store, f.runtime, time.Second)
	if found, err := f.store.ReconcileAgentOutput(f.ctx, f.missionID, f.runID, "UNKNOWN"); err == nil || found {
		t.Fatalf("unknown store stage reconciliation = %t/%v", found, err)
	}
	var messages, questions, drafts int
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM mission_messages WHERE mission_id=$1 AND agent_run_id=$2 AND output_stage IS NOT NULL`, f.missionID, f.runID).Scan(&messages); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM questions WHERE mission_id=$1 AND agent_run_id=$2`, f.missionID, f.runID).Scan(&questions); err != nil {
		t.Fatal(err)
	}
	if err := f.store.DB.QueryRow(f.ctx, `SELECT count(*) FROM planning_drafts WHERE mission_id=$1 AND created_by_agent_run_id=$2 AND output_stage IS NOT NULL`, f.missionID, f.runID).Scan(&drafts); err != nil {
		t.Fatal(err)
	}
	if messages != 0 || questions != 0 || drafts != 0 {
		t.Fatalf("unknown stage side effects = messages %d questions %d drafts %d", messages, questions, drafts)
	}
}

func TestWorkerClassifiesProviderTimeoutAsAgentTimeout(t *testing.T) {
	f := newAgentWorkerFixture(t, `{"type":"MESSAGE","content":"unused"}`)
	f.runtime.chat = func(context.Context, model.ResolvedConnection, model.ChatRequest) (model.ChatResponse, error) {
		return model.ChatResponse{}, model.ErrTimeout
	}

	err := RunOnce(f.ctx, f.store, f.runtime, time.Second)
	if err == nil || !errors.Is(err, model.ErrTimeout) {
		t.Fatalf("provider timeout RunOnce error = %v, want model.ErrTimeout", err)
	}

	var status, errorCode string
	if err := f.store.DB.QueryRow(f.ctx, `SELECT status,error_code FROM agent_runs WHERE id=$1`, f.runID).Scan(&status, &errorCode); err != nil {
		t.Fatal(err)
	}
	if status != "FAILED" || errorCode != "AGENT_TIMEOUT" {
		t.Fatalf("provider timeout persisted state = %s/%s, want FAILED/AGENT_TIMEOUT", status, errorCode)
	}
}

type agentWorkerFixture struct {
	store     *database.Store
	ctx       context.Context
	runtime   *Runtime
	ownerID   int64
	missionID int64
	runID     string
	run       model.AgentRun
}

func newAgentWorkerFixture(t *testing.T, response string) agentWorkerFixture {
	t.Helper()
	dsn := os.Getenv("LESSONFORGE_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("LESSONFORGE_TEST_DATABASE_URL is not configured")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	t.Cleanup(cancel)
	pool, err := database.Open(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(pool.Close)
	if err := database.Migrate(ctx, pool, filepath.Join("..", "..", "migrations")); err != nil {
		t.Fatal(err)
	}
	unlock, err := database.AcquireIntegrationTestLock(ctx, pool)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(unlock)
	store := database.NewStore(pool)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Agent runtime", "agent-runtime-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	connection, err := store.CreateConnection(ctx, owner.ID, model.ModelConnection{Name: "Agent runtime " + suffix, Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://8.8.8.8", ModelID: "test-model", KeyHint: "test"}, "")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := pool.Exec(ctx, `UPDATE model_connections SET enabled=true,verification_status='VERIFIED' WHERE id=$1`, connection.ID); err != nil {
		t.Fatal(err)
	}
	var missionID, messageID int64
	if err := pool.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title,selected_model_connection_id) VALUES($1,'SELF_CREATED','Agent runtime',$2) RETURNING id`, owner.ID, connection.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	if err := pool.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','start') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	crypt, err := crypto.New([]byte("01234567890123456789012345678901"))
	if err != nil {
		t.Fatal(err)
	}
	encrypted, err := crypt.Encrypt("synthetic-provider-key")
	if err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	snapshot, _ := json.Marshal(map[string]any{"connectionId": connection.ID, "protocol": connection.Protocol, "baseUrl": connection.BaseURL, "modelId": connection.ModelID, "encryptedApiKey": encrypted})
	leaseToken := uuid.NewString()
	if _, err := pool.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_connection_id,model_identity_snapshot,status) VALUES($1,$2,$3,$4,$5,'QUEUED')`, runID, missionID, messageID, connection.ID, snapshot); err != nil {
		t.Fatal(err)
	}
	runtime := &Runtime{Store: store, Crypto: crypt, Models: model.NewClient(time.Second, 1<<20), MaxToolCalls: 1, chat: func(context.Context, model.ResolvedConnection, model.ChatRequest) (model.ChatResponse, error) {
		return model.ChatResponse{Content: response, RawStatus: 200}, nil
	}}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cleanupCancel()
		for _, cleanup := range []struct {
			name  string
			query string
			arg   any
		}{
			{"execution audits", `DELETE FROM execution_audits WHERE actor_user_id=$1`, owner.ID},
			{"mission", `DELETE FROM missions WHERE id=$1`, missionID},
			{"file objects", `DELETE FROM file_objects WHERE owner_user_id=$1`, owner.ID},
			{"model connections", `DELETE FROM model_connections WHERE owner_user_id=$1`, owner.ID},
			{"user", `DELETE FROM users WHERE id=$1`, owner.ID},
		} {
			if _, err := pool.Exec(cleanupCtx, cleanup.query, cleanup.arg); err != nil {
				t.Errorf("cleanup %s: %v", cleanup.name, err)
			}
		}
		var remaining int
		if err := pool.QueryRow(cleanupCtx, `SELECT count(*) FROM users WHERE id=$1`, owner.ID).Scan(&remaining); err != nil || remaining != 0 {
			t.Errorf("cleanup residual user %d: %v", remaining, err)
		}
	})
	return agentWorkerFixture{store: store, ctx: ctx, runtime: runtime, ownerID: owner.ID, missionID: missionID, runID: runID, run: model.AgentRun{ID: runID, MissionID: missionID, LeaseToken: leaseToken, ModelIdentitySnapshot: map[string]any{"connectionId": connection.ID, "protocol": connection.Protocol, "baseUrl": connection.BaseURL, "modelId": connection.ModelID, "encryptedApiKey": encrypted}}}
}

func stageScope(stage string) string {
	switch stage {
	case database.AgentOutputStageMessage:
		return "message"
	case database.AgentOutputStageQuestion:
		return "question"
	default:
		return "draft"
	}
}
