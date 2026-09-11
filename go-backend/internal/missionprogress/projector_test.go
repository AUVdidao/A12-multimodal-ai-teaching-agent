package missionprogress

import "testing"

func TestProjectPrefersLiveGenerationOverBusinessStatus(t *testing.T) {
	got := Project(Facts{MissionStatus: "IN_PROGRESS", HasSelectedModel: true, GenerationStatus: "RUNNING"})
	if got.Progress != 85 || got.ProgressStage != "GENERATING" || got.ProgressLabel != "课件生成" {
		t.Fatalf("generation projection = %#v", got)
	}
}

func TestProjectExposesWaitingQuestionAsMissionProgress(t *testing.T) {
	runID := "run-1"
	got := Project(Facts{MissionStatus: "IN_PROGRESS", HasSelectedModel: true, LatestAgentRunID: &runID, LatestAgentRunStatus: "WAITING_INPUTS", HasPendingQuestion: true})
	if got.Progress != 30 || got.ProgressStage != "WAITING_INPUTS" || got.ActiveAgentRunID == nil || *got.ActiveAgentRunID != runID {
		t.Fatalf("waiting projection = %#v", got)
	}
}
