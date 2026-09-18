package generation

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"testing"

	"lessonforge.local/backend/internal/model"
)

func TestPrepareSystemDefaultTemplateBuildsTrustedFallbackPackage(t *testing.T) {
	root := t.TempDir()
	spec := model.LockedSpecification{
		ID:        "spec-1",
		MissionID: 7,
		Version:   1,
		Specification: map[string]any{
			"templateProfileId":      "teacher-profile-1",
			"templateProfileVersion": 3,
			"slides": []any{map[string]any{
				"semanticLayout": map[string]any{"primaryRole": "BODY"},
			}},
		},
	}
	job := model.GenerationJob{ID: "job-1", MissionID: 7, GenerationMode: "SYSTEM_DEFAULT_TEMPLATE"}
	fallback, err := prepareSystemDefaultTemplate(spec, job, 101, root)
	if err != nil {
		t.Fatal(err)
	}
	profile, err := engineProfile(fallback.binding, job.MissionID)
	if err != nil {
		t.Fatal(err)
	}
	if profile["profileId"] != "teacher-profile-1" || intValue(profile["profileVersion"]) != 3 {
		t.Fatalf("fallback did not preserve locked profile identity: %#v", profile)
	}
	if len(profile["components"].([]any)) != systemDefaultShapeCount {
		t.Fatalf("component count = %d, want %d", len(profile["components"].([]any)), systemDefaultShapeCount)
	}
	component := profile["components"].([]any)[0].(map[string]any)
	if component["transformConstraint"] != "FIXED" {
		t.Fatalf("system default transform constraint = %#v, want FIXED", component["transformConstraint"])
	}

	path := filepath.Join(root, filepath.FromSlash(systemDefaultTemplateKey))
	archive, err := zip.OpenReader(path)
	if err != nil {
		t.Fatal(err)
	}
	defer archive.Close()
	wanted := map[string]bool{
		"[Content_Types].xml":              false,
		"ppt/presentation.xml":             false,
		"ppt/slides/slide1.xml":            false,
		"ppt/slides/_rels/slide1.xml.rels": false,
	}
	for _, entry := range archive.File {
		if _, ok := wanted[entry.Name]; ok {
			wanted[entry.Name] = true
		}
		if filepath.ToSlash(filepath.Dir(entry.Name)) == "ppt/slides" && filepath.Ext(entry.Name) == ".xml" {
			reader, err := entry.Open()
			if err != nil {
				t.Fatal(err)
			}
			data, err := io.ReadAll(reader)
			if closeErr := reader.Close(); err == nil {
				err = closeErr
			}
			if err != nil {
				t.Fatal(err)
			}
			if bytes.Contains(data, []byte("系统默认内容")) {
				t.Fatalf("fallback PPTX retained a visible placeholder in %s", entry.Name)
			}
		}
	}
	for name, found := range wanted {
		if !found {
			t.Fatalf("fallback PPTX missing %s", name)
		}
	}
	if _, err := json.Marshal(fallback.binding); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal(err)
	}
}

func TestBuildEngineV2PackageUsesSystemDefaultTemplate(t *testing.T) {
	root := t.TempDir()
	spec := model.LockedSpecification{
		ID: "spec-1", MissionID: 7, Version: 1,
		Specification: map[string]any{
			"contractVersion": "1.0.0", "specificationId": "spec-1", "projectId": "7", "version": 1, "status": "LOCKED",
			"templateProfileId": "teacher-profile-1", "templateProfileVersion": 3, "targetSlideCount": 1, "slideCountTolerance": 0,
			"locale": "zh-CN", "provider": "OPENAI_COMPATIBLE", "model": "teacher-model", "aiSupplementPolicy": "DISABLED",
			"lockedBy": "101", "lockedAt": "2026-09-03T00:00:00Z", "slides": []any{},
		},
	}
	job := model.GenerationJob{ID: "job-1", MissionID: 7, GenerationMode: "SYSTEM_DEFAULT_TEMPLATE"}
	pkg, err := buildEngineV2Package(spec, job, 101, root)
	if err != nil {
		t.Fatal(err)
	}
	var compose map[string]any
	if err := json.Unmarshal(pkg.compose, &compose); err != nil {
		t.Fatal(err)
	}
	profile, ok := compose["templateProfile"].(map[string]any)
	if !ok || len(profile["components"].([]any)) != systemDefaultShapeCount {
		t.Fatalf("fallback profile = %#v", compose["templateProfile"])
	}
	execute, err := pkg.execute(json.RawMessage(`{"planVersion":"2.0.0","slides":[]}`))
	if err != nil {
		t.Fatal(err)
	}
	var request map[string]any
	if err := json.Unmarshal(execute, &request); err != nil {
		t.Fatal(err)
	}
	source, ok := request["templateSource"].(map[string]any)
	if !ok || source["storageKey"] != systemDefaultTemplateKey || source["size"].(float64) <= 0 {
		t.Fatalf("fallback template source = %#v", request["templateSource"])
	}
}
