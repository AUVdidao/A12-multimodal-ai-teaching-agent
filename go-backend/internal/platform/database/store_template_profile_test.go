package database

import (
	"strings"
	"testing"

	"lessonforge.local/backend/internal/templatebinding"
)

func TestMergeTemplateProfilePersistsOnlyCompleteOwnerBoundProfile(t *testing.T) {
	digest := strings.Repeat("a", 64)
	input := templatebinding.Input{MissionID: 11, OwnerUserID: 22, SHA256: digest}
	binding := map[string]any{
		"executionReady":             false,
		"engineNativeProfilePresent": false,
		"profileSource":              "GO_FILE_IDENTITY",
	}
	profile := map[string]any{
		"missionId":                   int64(11),
		"ownerUserId":                 int64(22),
		"templateFileSha256":          digest,
		"templateFileVersion":         "3",
		"templateProfileVersion":      "2",
		"templateId":                  "31",
		"profileId":                   "41",
		"projectId":                   "99",
		"templateVersion":             3,
		"profileVersion":              2,
		"sourceVersionId":             9,
		"sourceSha256":                digest,
		"parserSnapshotChecksum":      strings.Repeat("b", 64),
		"contractVersion":             "1.0.0",
		"status":                      "READY",
		"executionStatus":             "EXECUTION_READY",
		"pageSize":                    map[string]any{},
		"spatialProfile":              map[string]any{},
		"textFitPolicy":               map[string]any{},
		"templatePageReferences":      []any{},
		"components":                  []any{},
		"preservedNativeObjects":      []any{},
		"engineNativeProfileChecksum": strings.Repeat("c", 64),
		"engineNativeProfile": map[string]any{
			"contractVersion":        "1.0.0",
			"profileId":              "41",
			"templateId":             "31",
			"projectId":              "99",
			"ownerUserId":            "22",
			"templateVersion":        3,
			"profileVersion":         2,
			"status":                 "READY",
			"executionStatus":        "EXECUTION_READY",
			"sourceVersionId":        9,
			"sourceSha256":           digest,
			"parserSnapshotChecksum": strings.Repeat("b", 64),
			"pageSize":               map[string]any{},
			"spatialProfile":         map[string]any{},
			"templatePageReferences": []any{},
			"components":             []any{},
			"textFitPolicy":          map[string]any{},
		},
	}

	if !mergeTemplateProfile(binding, profile, input) {
		t.Fatal("complete profile was not accepted")
	}
	if binding["executionReady"] != true || binding["engineNativeProfilePresent"] != true || binding["profileSource"] != "JAVA_ENGINE_NATIVE_PROFILE" {
		t.Fatalf("readiness binding = %#v", binding)
	}
	if binding["profileId"] != "41" || binding["templateProfileVersion"] != "2" {
		t.Fatalf("native identity was not persisted = %#v", binding)
	}
}

func TestMergeTemplateProfileFailsClosedOnDigestMismatch(t *testing.T) {
	digest := strings.Repeat("a", 64)
	input := templatebinding.Input{MissionID: 11, OwnerUserID: 22, SHA256: digest}
	binding := map[string]any{"executionReady": false, "engineNativeProfilePresent": false}
	profile := map[string]any{
		"missionId":          int64(11),
		"ownerUserId":        int64(22),
		"templateFileSha256": strings.Repeat("d", 64),
	}
	if mergeTemplateProfile(binding, profile, input) {
		t.Fatal("digest-mismatched profile was accepted")
	}
	if binding["executionReady"] != false || binding["engineNativeProfilePresent"] != false {
		t.Fatalf("mismatched profile changed readiness = %#v", binding)
	}
}
