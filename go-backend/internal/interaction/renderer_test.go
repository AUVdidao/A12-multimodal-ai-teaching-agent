package interaction

import (
	"strings"
	"testing"
)

func testPlan(pageType string) map[string]any {
	return map[string]any{
		"plan": map[string]any{
			"slides": []any{
				map[string]any{
					"title":    "材料解析",
					"pageType": pageType,
					"points":   []any{"材料中的关键结论"},
					"sourceRefs": []any{map[string]any{
						"fileId":  int64(42),
						"locator": "第 3 页",
						"claim":   "原文依据",
					}},
				},
				map[string]any{
					"title":    "案例对比",
					"pageType": pageType,
					"points":   []any{"材料中的对比结论"},
					"sourceRefs": []any{map[string]any{
						"fileId":  int64(42),
						"locator": "第 4 页",
					}},
				},
			},
		},
	}
}

func TestBuildFromLockedSpecificationUsesMaterialSources(t *testing.T) {
	spec, err := BuildFromLockedSpecification(testPlan("COMPARISON"), "spec-1", 3)
	if err != nil {
		t.Fatalf("BuildFromLockedSpecification() error = %v", err)
	}
	if spec.GameType != GameTypeSingleChoice {
		t.Fatalf("game type = %q, want %q", spec.GameType, GameTypeSingleChoice)
	}
	if len(spec.Questions) != 2 {
		t.Fatalf("question count = %d, want 2", len(spec.Questions))
	}
	if spec.Questions[0].Source.FileID != 42 || spec.Questions[0].Source.Locator != "第 3 页" {
		t.Fatalf("question source = %+v", spec.Questions[0].Source)
	}
	if spec.SourceSpecificationID != "spec-1" || spec.SourceSpecificationVersion != 3 {
		t.Fatalf("specification provenance = %s v%d", spec.SourceSpecificationID, spec.SourceSpecificationVersion)
	}
}

func TestBuildFromLockedSpecificationRequiresSources(t *testing.T) {
	plan := testPlan("PROCESS")
	slides := plan["plan"].(map[string]any)["slides"].([]any)
	for _, raw := range slides {
		delete(raw.(map[string]any), "sourceRefs")
	}
	_, err := BuildFromLockedSpecification(plan, "spec-1", 1)
	if err == nil || err.Error() != "GAME_MATERIAL_SOURCE_REQUIRED" {
		t.Fatalf("error = %v, want GAME_MATERIAL_SOURCE_REQUIRED", err)
	}
}

func TestBuildFromLockedSpecificationMapsChunkSourceToMaterialFile(t *testing.T) {
	plan := map[string]any{
		"plan": map[string]any{
			"slides": []any{map[string]any{
				"title":    "检索流程",
				"pageType": "PROCESS",
				"points":   []any{"先检索，再生成"},
				"sources":  []any{"chunk:176"},
			}},
		},
	}
	spec, err := BuildFromLockedSpecification(plan, "spec-1", 1, 48)
	if err != nil {
		t.Fatalf("BuildFromLockedSpecification() error = %v", err)
	}
	if got := spec.Questions[0].Source; got.FileID != 48 || got.Locator != "chunk:176" {
		t.Fatalf("mapped source = %+v", got)
	}
}

func TestBuildAndRenderRunnerUsesMaterialGroundedCheckpoints(t *testing.T) {
	spec, err := BuildFromLockedSpecificationWithType(testPlan("TRUE_FALSE"), "spec-runner", 2, GameTypeRunner)
	if err != nil {
		t.Fatalf("BuildFromLockedSpecificationWithType() error = %v", err)
	}
	if spec.GameType != GameTypeRunner {
		t.Fatalf("game type = %q, want %q", spec.GameType, GameTypeRunner)
	}
	if len(spec.Questions) == 0 || spec.Questions[0].Type != GameTypeTrueFalse {
		t.Fatalf("runner checkpoint type = %+v, want material true/false checkpoint", spec.Questions)
	}
	if len(spec.Questions) < 2 || spec.Questions[1].Type != GameTypeSingleChoice {
		t.Fatalf("runner second checkpoint type = %+v, want material single choice checkpoint", spec.Questions)
	}
	html, err := Render(spec)
	if err != nil {
		t.Fatalf("Render() error = %v", err)
	}
	output := string(html)
	for _, fragment := range []string{"runner-arena", "requestAnimationFrame", "RUNNER", "材料来源", "runner-dino-tail", "runner-dino-head", "runner-dino-leg--one", "runner-player-a", "runner-player-b", "runner-obstacles-a", "runner-obstacles-b", "学生甲", "学生乙", "答题时两条赛道都会暂停"} {
		if !strings.Contains(output, fragment) {
			t.Fatalf("runner HTML does not contain %q", fragment)
		}
	}
	if strings.Contains(output, "<script src=") {
		t.Fatal("runner HTML must not load external scripts")
	}
}

func TestBuildFromLockedSpecificationRejectsUnknownGameType(t *testing.T) {
	_, err := BuildFromLockedSpecificationWithType(testPlan("TRUE_FALSE"), "spec-1", 1, "UNKNOWN")
	if err == nil || err.Error() != "GAME_TYPE_UNSUPPORTED" {
		t.Fatalf("error = %v, want GAME_TYPE_UNSUPPORTED", err)
	}
}

func TestRenderProducesSelfContainedEscapedHTML(t *testing.T) {
	spec, err := BuildFromLockedSpecification(testPlan("TRUE_FALSE"), "spec-1", 1)
	if err != nil {
		t.Fatalf("BuildFromLockedSpecification() error = %v", err)
	}
	spec.Title = "课堂 <互动>"
	html, err := Render(spec)
	if err != nil {
		t.Fatalf("Render() error = %v", err)
	}
	output := string(html)
	for _, fragment := range []string{
		"<!doctype html>",
		`type="application/json"`,
		"JSON.parse",
		"材料来源",
		"课堂 &lt;互动&gt;",
	} {
		if !strings.Contains(output, fragment) {
			t.Fatalf("rendered HTML does not contain %q", fragment)
		}
	}
	if strings.Contains(output, "<互动>") {
		t.Fatal("unescaped title markup found in rendered HTML")
	}
	if strings.Contains(output, "https://") || strings.Contains(output, "http://") {
		t.Fatal("rendered game must not load external resources")
	}
}
