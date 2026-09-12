package database

import (
	"strings"
	"testing"

	"lessonforge.local/backend/internal/templatebinding"
)

func TestMergeTemplateProfilePreservesUpstreamBindingContract(t *testing.T) {
	sha := strings.Repeat("a", 64)
	binding := map[string]any{
		"bindingKind":     "LESSONFORGE_UPSTREAM_TEMPLATE_BINDING",
		"contractVersion": "lessonforge-upstream-template-v1",
		"missionId":       int64(71),
		"ownerUserId":     int64(58),
	}
	input := templatebinding.Input{MissionID: 71, OwnerUserID: 58, SHA256: sha}
	profile := map[string]any{
		"missionId":                   float64(71),
		"ownerUserId":                 float64(58),
		"templateFileSha256":          sha,
		"engineNativeProfile":         map[string]any{"status": "READY"},
		"engineNativeProfileChecksum": strings.Repeat("b", 64),
		"contractVersion":             "1.0.0",
		"profileId":                   "4",
		"templateId":                  "3",
		"projectId":                   "13",
		"templateVersion":             1,
		"profileVersion":              2,
		"templateFileVersion":         "1",
		"templateProfileVersion":      "2",
		"status":                      "READY",
		"pageSize":                    map[string]any{},
		"spatialProfile":              map[string]any{},
		"templatePageReferences":      []any{},
		"components":                  []any{"component-1"},
		"textFitPolicy":               map[string]any{},
		"executionStatus":             "EXECUTION_READY",
		"sourceVersionId":             3,
		"sourceSha256":                sha,
		"parserSnapshotChecksum":      strings.Repeat("c", 64),
	}

	if !mergeTemplateProfile(binding, profile, input) {
		t.Fatal("mergeTemplateProfile returned false")
	}
	if got := binding["contractVersion"]; got != "lessonforge-upstream-template-v1" {
		t.Fatalf("upstream contract version was overwritten: %v", got)
	}
	if binding["executionReady"] != true || binding["engineNativeProfilePresent"] != true {
		t.Fatalf("profile readiness was not projected: %#v", binding)
	}
}
