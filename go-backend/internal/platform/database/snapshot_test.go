package database

import (
	"testing"

	"lessonforge.local/backend/internal/model"
)

func TestAgentRunSnapshotsFreezeContextVersions(t *testing.T) {
	waiting := waitingSnapshot()
	if waiting["promptVersion"] != model.PlanAgentPromptVersion || waiting["contextPolicyVersion"] != model.PlanAgentContextPolicyVersion {
		t.Fatalf("waiting snapshot versions = %#v", waiting)
	}
	runnable := runnableAgentSnapshot(modelIdentitySnapshot{ConnectionID: 9, Provider: "CUSTOM", Protocol: "OPENAI_COMPATIBLE", BaseURL: "https://provider.example.test/v1", ModelID: "deepseek-flash"})
	if runnable["promptVersion"] != model.PlanAgentPromptVersion || runnable["contextPolicyVersion"] != model.PlanAgentContextPolicyVersion {
		t.Fatalf("runnable snapshot versions = %#v", runnable)
	}
	if runnable["encryptedApiKey"] != "" {
		t.Fatal("test identity unexpectedly contained a credential")
	}
}
