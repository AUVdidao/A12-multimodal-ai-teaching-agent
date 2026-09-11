package missionprogress

// Facts is the durable state needed to project a teacher-facing Mission
// progress value. It deliberately keeps business MissionStatus separate from
// the derived execution progress shown by the list/detail APIs.
type Facts struct {
	MissionStatus        string
	HasSelectedModel     bool
	LatestAgentRunID     *string
	LatestAgentRunStatus string
	HasPendingQuestion   bool
	HasPlanningDraft     bool
	HasLockedSpec        bool
	GenerationStatus     string
	HasReadyArtifact     bool
}

type Result struct {
	Progress         int
	ProgressLabel    string
	ProgressStage    string
	ActiveAgentRunID *string
}

func Project(f Facts) Result {
	result := Result{Progress: 0, ProgressLabel: "待开始", ProgressStage: "NOT_STARTED"}

	if f.MissionStatus == "COMPLETED" {
		return Result{Progress: 100, ProgressLabel: "已完成", ProgressStage: "COMPLETED"}
	}
	if f.MissionStatus == "SUBMITTED" {
		return Result{Progress: 100, ProgressLabel: "已提交", ProgressStage: "SUBMITTED"}
	}
	if f.MissionStatus == "FEEDBACK" {
		return Result{Progress: 100, ProgressLabel: "待反馈", ProgressStage: "FEEDBACK"}
	}

	switch f.GenerationStatus {
	case "RUNNING", "VERIFYING":
		return Result{Progress: 85, ProgressLabel: "课件生成", ProgressStage: "GENERATING"}
	case "QUEUED":
		return Result{Progress: 70, ProgressLabel: "等待生成", ProgressStage: "GENERATION_QUEUED"}
	case "SUCCEEDED":
		return Result{Progress: 100, ProgressLabel: "已完成", ProgressStage: "COMPLETED"}
	case "FAILED":
		return Result{Progress: 70, ProgressLabel: "生成失败", ProgressStage: "GENERATION_FAILED"}
	case "CANCELLED":
		return Result{Progress: 65, ProgressLabel: "生成已取消", ProgressStage: "GENERATION_CANCELLED"}
	}
	if f.HasReadyArtifact {
		return Result{Progress: 100, ProgressLabel: "已完成", ProgressStage: "COMPLETED"}
	}
	if f.HasLockedSpec {
		return Result{Progress: 65, ProgressLabel: "方案已锁定", ProgressStage: "SPECIFICATION_LOCKED"}
	}
	if f.HasPlanningDraft {
		return Result{Progress: 55, ProgressLabel: "方案已生成", ProgressStage: "PLAN_DRAFT"}
	}

	result.ActiveAgentRunID = f.LatestAgentRunID
	switch f.LatestAgentRunStatus {
	case "RUNNING":
		result.Progress = 35
		result.ProgressLabel = "分析中"
		result.ProgressStage = "AGENT_RUNNING"
	case "QUEUED":
		result.Progress = 20
		result.ProgressLabel = "等待执行"
		result.ProgressStage = "AGENT_QUEUED"
	case "WAITING_INPUTS":
		result.Progress = 30
		result.ProgressLabel = "等待补充"
		result.ProgressStage = "WAITING_INPUTS"
	case "FAILED":
		result.Progress = 0
		result.ProgressLabel = "执行失败"
		result.ProgressStage = "AGENT_FAILED"
	}
	if f.HasPendingQuestion {
		result.Progress = 30
		result.ProgressLabel = "等待补充"
		result.ProgressStage = "WAITING_INPUTS"
	}
	if !f.HasSelectedModel && result.ProgressStage == "NOT_STARTED" {
		result.ProgressLabel = "待配置模型"
		result.ProgressStage = "WAITING_MODEL"
	}
	if result.ProgressStage != "AGENT_RUNNING" && result.ProgressStage != "AGENT_QUEUED" && result.ProgressStage != "WAITING_INPUTS" {
		result.ActiveAgentRunID = nil
	}
	return result
}
