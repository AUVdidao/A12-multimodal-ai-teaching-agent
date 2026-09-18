package specification

import (
	"errors"
	"testing"
)

func validPlan() map[string]any {
	return map[string]any{"slides": []any{map[string]any{
		"title":   "课程目标",
		"regions": []any{map[string]any{"position": "LEFT", "content": []any{"重点"}, "sourceRefs": []any{map[string]any{"type": "TEACHER", "label": "教师要求"}}}},
	}}}
}

func TestCompileRejectsEngineAndGeometryFields(t *testing.T) {
	plan := validPlan()
	plan["slides"].([]any)[0].(map[string]any)["trustedEngineRequest"] = map[string]any{"url": "bad"}
	if _, _, err := Compile(plan, map[string]any{"templateFileVersion": "file", "templateProfileVersion": "profile"}); !errors.Is(err, ErrForbiddenField) {
		t.Fatalf("expected forbidden field, got %v", err)
	}
}

func TestCompileAllowsMissingTemplateBindingAndPreservesSemanticPlan(t *testing.T) {
	compiled, hash, err := Compile(validPlan(), nil)
	if err != nil || hash == "" {
		t.Fatalf("compile without template failed: %v", err)
	}
	if compiled["templateBinding"] != nil {
		t.Fatalf("missing template binding = %#v, want nil", compiled["templateBinding"])
	}
	if _, ok := compiled["plan"]; !ok {
		t.Fatal("compiled contract lost semantic plan")
	}

	compiled, hash, err = Compile(validPlan(), map[string]any{"templateFileVersion": "file", "templateProfileVersion": "profile"})
	if err != nil || hash == "" {
		t.Fatalf("compile failed: %v", err)
	}
	if _, ok := compiled["plan"]; !ok {
		t.Fatal("compiled contract lost semantic plan")
	}
}

func TestCompileInvokesSourceValidatorForNestedRefs(t *testing.T) {
	seen := 0
	_, _, err := CompileWithSourceValidator(validPlan(), map[string]any{"templateId": "t", "templateFileVersion": "f", "templateProfileVersion": "p"}, func(ref map[string]any) error {
		seen++
		if ref["type"] != "TEACHER" {
			t.Fatalf("source ref = %#v", ref)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if seen != 1 {
		t.Fatalf("validator calls = %d", seen)
	}
}
