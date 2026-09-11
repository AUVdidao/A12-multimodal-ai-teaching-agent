package generation

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"lessonforge.local/backend/internal/model"
)

func TestBuildEngineV2PackagePreservesEnginePlanBoundary(t *testing.T) {
	job := model.GenerationJob{ID: "job-1", MissionID: 7, SpecificationID: "spec-1"}
	spec := model.LockedSpecification{
		ID: "spec-1", MissionID: 7, Version: 1,
		Specification: map[string]any{
			"contractVersion": "1.0.0", "specificationId": "spec-1", "projectId": "7", "version": 1, "status": "LOCKED",
			"templateProfileId": "profile-1", "templateProfileVersion": 2, "targetSlideCount": 1, "slideCountTolerance": 0,
			"locale": "zh-CN", "provider": "OPENAI_COMPATIBLE", "model": "teacher-model", "aiSupplementPolicy": "DISABLED",
			"lockedBy": "101", "lockedAt": "2026-09-03T00:00:00Z", "slides": []any{},
		},
		TemplateBinding: map[string]any{
			"contractVersion": "1.0.0", "profileId": "profile-1", "templateId": "template-1", "projectId": "7", "missionId": "7", "ownerUserId": "101", "engineNativeProfileChecksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"templateVersion": 3, "profileVersion": 2, "status": "READY", "pageSize": map[string]any{"width": 10, "height": 5.625},
			"spatialProfile": map[string]any{}, "templatePageReferences": []any{}, "components": []any{}, "preservedNativeObjects": []any{}, "textFitPolicy": map[string]any{},
			"executionStatus": "EXECUTION_READY", "sourceVersionId": 12, "sourceSha256": "template-sha", "parserSnapshotChecksum": "parser-sha",
			"templateStorageKey": "templates/template-1.pptx", "templateFileSha256": "template-sha", "templateFileSize": 1234,
			"templateLastModifiedUtc": "2026-09-03T00:00:00Z",
		},
	}
	pkg, err := buildEngineV2Package(spec, job, 101, filepath.Join(t.TempDir(), "shared"))
	if err != nil {
		t.Fatal(err)
	}
	var compose map[string]any
	if err := json.Unmarshal(pkg.compose, &compose); err != nil {
		t.Fatal(err)
	}
	if compose["contractVersion"] != engineContractVersion || compose["requestId"] == nil {
		t.Fatalf("compose envelope = %#v", compose)
	}
	if profile, ok := compose["templateProfile"].(map[string]any); !ok || profile["preservedNativeObjects"] != nil {
		t.Fatalf("empty optional preserved objects were not normalized: %#v", compose["templateProfile"])
	}
	if _, ok := compose["plan"]; ok {
		t.Fatal("compose request contains an Engine-owned plan")
	}
	execute, err := pkg.execute(json.RawMessage(`{"planVersion":"2.0.0","slides":[]}`))
	if err != nil {
		t.Fatal(err)
	}
	var request map[string]any
	if err := json.Unmarshal(execute, &request); err != nil {
		t.Fatal(err)
	}
	if request["plan"].(map[string]any)["planVersion"] != "2.0.0" {
		t.Fatalf("plan was not passed through: %#v", request["plan"])
	}
	templateSource, ok := request["templateSource"].(map[string]any)
	if !ok {
		t.Fatalf("template source = %#v", request["templateSource"])
	}
	if got := templateSource["storageKey"]; got != "templates/template-1.pptx" {
		t.Fatalf("template source storage key = %#v, want relative Engine key", got)
	}
	if files, ok := request["approvedAssetFiles"].([]any); !ok || len(files) != 0 {
		t.Fatalf("approved asset files = %#v", request["approvedAssetFiles"])
	}
}

func TestBuildEngineV2PackageRejectsRequiredAssetWithoutRealFile(t *testing.T) {
	spec := model.LockedSpecification{
		ID: "spec-1", MissionID: 7, Version: 1,
		Specification: map[string]any{
			"contractVersion": "1.0.0", "specificationId": "spec-1", "projectId": "7", "version": 1, "status": "LOCKED",
			"templateProfileId": "profile-1", "templateProfileVersion": 1, "targetSlideCount": 1, "slideCountTolerance": 0,
			"locale": "zh-CN", "provider": "OPENAI_COMPATIBLE", "model": "teacher-model", "aiSupplementPolicy": "DISABLED",
			"lockedBy": "101", "lockedAt": "2026-09-03T00:00:00Z", "slides": []any{
				map[string]any{"assetRequirements": []any{map[string]any{"assetId": "asset-1", "required": true}}},
			},
		},
		TemplateBinding: map[string]any{
			"contractVersion": "1.0.0", "profileId": "profile-1", "templateId": "template-1", "projectId": "7", "missionId": "7", "ownerUserId": "101",
			"templateVersion": 1, "profileVersion": 1, "status": "READY", "pageSize": map[string]any{}, "spatialProfile": map[string]any{},
			"templatePageReferences": []any{}, "components": []any{}, "textFitPolicy": map[string]any{}, "executionStatus": "EXECUTION_READY",
			"sourceVersionId": 12, "sourceSha256": "template-sha", "parserSnapshotChecksum": "parser-sha", "templateStorageKey": "template.pptx",
			"templateFileSha256": "template-sha", "templateFileSize": 1234, "templateLastModifiedUtc": "2026-09-03T00:00:00Z",
		},
	}
	_, err := buildEngineV2Package(spec, model.GenerationJob{ID: "job-1", MissionID: 7}, 101, t.TempDir())
	if err == nil || err.Error() != "PPT_ENGINE_REQUIRED_ASSET_UNRESOLVED" {
		t.Fatalf("error = %v", err)
	}
}

func TestEngineSpecificationUnwrapsCompiledPlan(t *testing.T) {
	raw := map[string]any{
		"compiler": "lessonforge-semantic-v1",
		"plan": map[string]any{
			"contractVersion":        "1.0.0",
			"projectId":              "7",
			"version":                1,
			"status":                 "LOCKED",
			"templateProfileId":      "profile-1",
			"templateProfileVersion": 2,
			"targetSlideCount":       1,
			"slideCountTolerance":    0,
			"locale":                 "zh-CN",
			"provider":               "OPENAI_COMPATIBLE",
			"model":                  "teacher-model",
			"aiSupplementPolicy":     "DISABLED",
			"lockedBy":               "101",
			"lockedAt":               "2026-09-03T00:00:00Z",
			"slides":                 []any{},
		},
	}
	got, err := engineSpecification(raw, "spec-1", 1, 7, map[string]any{"profileId": "profile-1", "profileVersion": 2})
	if err != nil {
		t.Fatal(err)
	}
	if got["projectId"] != "7" || got["specificationId"] != "spec-1" {
		t.Fatalf("compiled plan was not unwrapped and bound: %#v", got)
	}
}

func TestEngineProfileStripsBridgeEnvelopeFields(t *testing.T) {
	raw := map[string]any{
		"missionId": "7", "engineNativeProfile": map[string]any{}, "engineNativeProfileChecksum": "bridge-checksum",
		"templateFileVersion": "1", "templateProfileVersion": "1",
		"contractVersion": "1.0.0", "profileId": "2", "templateId": "2", "projectId": "7", "ownerUserId": "2",
		"templateVersion": 1, "profileVersion": 1, "status": "READY", "pageSize": map[string]any{},
		"spatialProfile": map[string]any{}, "templatePageReferences": []any{}, "components": []any{},
		"textFitPolicy": map[string]any{}, "executionStatus": "EXECUTION_READY", "sourceVersionId": 2,
		"sourceSha256": "source-sha", "parserSnapshotChecksum": "parser-sha",
	}
	got, err := engineProfile(raw, 7)
	if err != nil {
		t.Fatal(err)
	}
	for _, key := range []string{"engineNativeProfile", "engineNativeProfileChecksum", "missionId", "templateFileVersion", "templateProfileVersion"} {
		if _, exists := got[key]; exists {
			t.Fatalf("bridge-only field %q leaked into Engine profile: %#v", key, got)
		}
	}
}
