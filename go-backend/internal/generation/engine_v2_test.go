package generation

import (
	"encoding/json"
	"path/filepath"
	"testing"
	"time"

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
	got, err := engineSpecification(raw, "spec-1", 1, 7, 101, time.Date(2026, 9, 3, 0, 0, 0, 0, time.UTC), map[string]any{"profileId": "profile-1", "profileVersion": 2})
	if err != nil {
		t.Fatal(err)
	}
	if got["projectId"] != "7" || got["specificationId"] != "spec-1" {
		t.Fatalf("compiled plan was not unwrapped and bound: %#v", got)
	}
}

func TestEngineSpecificationProjectsSemanticPlanAtGenerationBoundary(t *testing.T) {
	raw := map[string]any{
		"compiler": "lessonforge-semantic-v1",
		"plan": map[string]any{
			"lessonTitle": "受控生成验收",
			"slides": []any{map[string]any{
				"title":     "课程目标",
				"purpose":   "明确本页的教学目标",
				"keyPoints": []any{"第一项", "第二项"},
			}},
		},
	}
	profile := map[string]any{
		"profileId":      "profile-1",
		"profileVersion": 2,
		"templatePageReferences": []any{
			map[string]any{"semanticRole": "UNMAPPED"},
			map[string]any{"semanticRole": "CONTENT"},
		},
	}
	got, err := engineSpecification(raw, "spec-1", 4, 7, 101, time.Date(2026, 9, 3, 0, 0, 0, 0, time.UTC), profile)
	if err != nil {
		t.Fatal(err)
	}
	if got["projectId"] != "7" || got["specificationId"] != "spec-1" || got["version"] != 4 || got["status"] != "LOCKED" {
		t.Fatalf("server-owned bindings = %#v", got)
	}
	if got["templateProfileId"] != "profile-1" || got["templateProfileVersion"] != 2 {
		t.Fatalf("profile binding = %#v", got)
	}
	slides, ok := got["slides"].([]any)
	if !ok || len(slides) != 1 {
		t.Fatalf("projected slides = %#v", got["slides"])
	}
	slide := slides[0].(map[string]any)
	if slide["teachingGoal"] != "明确本页的教学目标" {
		t.Fatalf("teaching goal = %#v", slide["teachingGoal"])
	}
	layout := slide["semanticLayout"].(map[string]any)
	if layout["primaryRole"] != "CONTENT" {
		t.Fatalf("semantic role = %#v", layout["primaryRole"])
	}
	blocks := slide["contentBlocks"].([]any)
	if len(blocks) != 3 || blocks[2].(map[string]any)["type"] != "BULLETS" {
		t.Fatalf("projected content blocks = %#v", blocks)
	}
}

func TestEngineSpecificationProjectsLegacyPointsPlanWithoutInventingTeachingFacts(t *testing.T) {
	raw := map[string]any{
		"compiler": "lessonforge-semantic-v1",
		"plan": map[string]any{
			"slides": []any{map[string]any{
				"title":  "TCP 三次握手",
				"points": []any{"客户端发送 SYN", "服务端返回 SYN-ACK"},
			}},
		},
	}
	got, err := engineSpecification(raw, "spec-legacy", 1, 88, 58, time.Date(2026, 9, 18, 0, 0, 0, 0, time.UTC), map[string]any{
		"profileId": "lessonforge-system-default-profile", "profileVersion": 1,
		"templateId": systemDefaultTemplateID,
		"templatePageReferences": []any{map[string]any{"semanticRole": "BODY"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	slide := got["slides"].([]any)[0].(map[string]any)
	if slide["teachingGoal"] != "客户端发送 SYN" {
		t.Fatalf("legacy point was not used as the existing teaching fact: %#v", slide["teachingGoal"])
	}
	if slide["semanticLayout"].(map[string]any)["requestedTransform"] != "FIXED" {
		t.Fatalf("system default transform was not bound to the native fixed geometry: %#v", slide["semanticLayout"])
	}
	blocks := slide["contentBlocks"].([]any)
	if blocks[2].(map[string]any)["content"] != "客户端发送 SYN\n服务端返回 SYN-ACK" {
		t.Fatalf("legacy points were not projected as key points: %#v", blocks[2])
	}
}

func TestEngineSpecificationRejectsIncompleteSemanticSlide(t *testing.T) {
	raw := map[string]any{
		"compiler": "lessonforge-semantic-v1",
		"plan": map[string]any{
			"slides": []any{map[string]any{"title": "只有标题"}},
		},
	}
	_, err := engineSpecification(raw, "spec-1", 1, 7, 101, time.Date(2026, 9, 3, 0, 0, 0, 0, time.UTC), map[string]any{
		"profileId": "profile-1", "profileVersion": 1,
		"templatePageReferences": []any{map[string]any{"semanticRole": "CONTENT"}},
	})
	if err == nil || err.Error() != "PPT_ENGINE_SPECIFICATION_INCOMPLETE" {
		t.Fatalf("error = %v", err)
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

func TestEngineProfileChecksumProjectsJavaCanonicalIdentityWithoutReserializing(t *testing.T) {
	const nativeJSON = `{"ownerUserId":"58","projectId":"13"}`
	binding := map[string]any{
		"missionId":                   "71",
		"engineNativeProfile":         map[string]any{"ownerUserId": "58", "projectId": "13"},
		"engineNativeProfileJson":     nativeJSON,
		"engineNativeProfileChecksum": "6dff0ade080e02215850b2da0b0ab6b24dae82d187fa1aa84b098e25405fd286",
	}
	profile := map[string]any{"ownerUserId": "58", "projectId": "71"}
	got, err := engineProfileChecksum(binding, profile)
	if err != nil {
		t.Fatal(err)
	}
	want := "f9851e235489d3aa7c585d6088d3e04526ed84dbfe2e2b42a3bb3e9163e0230c"
	if got != want {
		t.Fatalf("projected checksum = %s, want %s", got, want)
	}
}
