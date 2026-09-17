package agent

import (
	"strings"
	"testing"

	"lessonforge.local/backend/internal/model"
)

func TestSubagentToolSetKeepsRoleBoundaries(t *testing.T) {
	research := subagentToolSet("search_materials", "read_material")
	if len(research) != 2 {
		t.Fatalf("research tools = %d, want 2", len(research))
	}
	for _, definition := range research {
		if definition.Function.Name != "search_materials" && definition.Function.Name != "read_material" {
			t.Fatalf("unexpected research tool %q", definition.Function.Name)
		}
	}
	template := subagentToolSet("get_template_capability")
	if len(template) != 1 || template[0].Function.Name != "get_template_capability" {
		t.Fatalf("template tools = %#v", template)
	}
	if len(subagentToolSet("get_current_plan")) != 1 {
		t.Fatal("tool filter unexpectedly changed unrelated tool")
	}
}

func TestDecodeSubagentJSONAcceptsProviderFence(t *testing.T) {
	var output subagentClarifierOutput
	if err := decodeSubagentJSON("```json\n{\"type\":\"READY\",\"requirements\":{}}\n```", &output); err != nil {
		t.Fatal(err)
	}
	if output.Type != "READY" {
		t.Fatalf("type = %q", output.Type)
	}
}

func TestDecodeSubagentJSONExtractsOneObjectFromProviderProse(t *testing.T) {
	var output subagentEvidenceOutput
	content := "Here is the result:\n```json\n{\"type\":\"SKIPPED\",\"reason\":\"NO_RELEVANT_EVIDENCE\"}\n```\n"
	if err := decodeSubagentJSON(content, &output); err != nil {
		t.Fatal(err)
	}
	if output.Type != "SKIPPED" || output.Reason != "NO_RELEVANT_EVIDENCE" {
		t.Fatalf("decoded output = %#v", output)
	}
}

func TestValidateSubagentEvidenceOutputKeepsStrictContract(t *testing.T) {
	valid := `{"type":"EVIDENCE","summary":"RAG section evidence","sources":[{"fileId":21,"locator":"chunk:3","claim":"The section defines hybrid retrieval."}]}`
	if err := validateSubagentEvidenceOutput(valid); err != nil {
		t.Fatalf("valid evidence rejected: %v", err)
	}
	if err := validateSubagentEvidenceOutput(`{"type":"EVIDENCE","summary":"free text"}`); err == nil {
		t.Fatal("evidence without sources was accepted")
	}
	if err := validateSubagentEvidenceOutput(`{"type":"EVIDENCE","summary":"evidence","sources":[{"fileId":21,"locator":"chunk:3","claim":"claim"}],"extra":"ignored"}`); err == nil {
		t.Fatal("unknown evidence field was accepted")
	}
}

func TestValidateMaterialResearchOutputRequiresSearchAndRead(t *testing.T) {
	valid := `{"type":"EVIDENCE","summary":"RAG section evidence","sources":[{"fileId":21,"locator":"chunk:3","claim":"The section defines hybrid retrieval."}]}`
	if err := validateMaterialResearchOutput(valid, map[string]bool{"search_materials": true}); err == nil {
		t.Fatal("material evidence was accepted without read_material")
	}
	if err := validateMaterialResearchOutput(valid, map[string]bool{"search_materials": true, "read_material": true}); err != nil {
		t.Fatalf("material evidence rejected after both tools: %v", err)
	}
}

func TestSubagentMaterialAndTemplateRouting(t *testing.T) {
	material := model.MissionFile{Role: "MATERIAL", FileObject: model.FileObject{OriginalName: "lesson.docx"}}
	template := model.MissionFile{Role: "TEMPLATE", FileObject: model.FileObject{OriginalName: "theme.pptx"}}
	pptxMaterial := model.MissionFile{Role: "MATERIAL", FileObject: model.FileObject{OriginalName: "slides.pptx"}}
	if !hasResearchMaterial([]model.MissionFile{material}) {
		t.Fatal("material file was not routed to researcher")
	}
	if !hasTemplateFile([]model.MissionFile{template}) {
		t.Fatal("template file was not routed to analyzer")
	}
	if hasResearchMaterial([]model.MissionFile{template}) {
		t.Fatal("template file was incorrectly routed to researcher")
	}
	if hasTemplateFile([]model.MissionFile{pptxMaterial}) {
		t.Fatal("ordinary pptx material was incorrectly routed to template analyzer")
	}
}

func TestSubagentEvidenceReachesClarifierContext(t *testing.T) {
	context := subagentEvidenceContext("mission", `{"type":"EVIDENCE","summary":"TCP source"}`, `{"type":"TEMPLATE_CANDIDATE","summary":"title and body"}`)
	for _, required := range []string{"Material researcher handoff", "TCP source", "Template capability handoff", "title and body"} {
		if !strings.Contains(context, required) {
			t.Fatalf("clarifier context missing %q: %s", required, context)
		}
	}
}

func TestValidateSubagentClarifierOutput(t *testing.T) {
	if err := validateSubagentClarifierOutput(subagentClarifierOutput{Type: "QUESTION", Question: " ", QuestionType: "SINGLE_CHOICE", Options: []string{"A", "B"}}); err == nil {
		t.Fatal("blank question was accepted")
	}
	if err := validateSubagentClarifierOutput(subagentClarifierOutput{Type: "QUESTION", Question: "Choose", QuestionType: "SINGLE_CHOICE", Options: []string{"A", "A"}}); err == nil {
		t.Fatal("duplicate options were accepted")
	}
	if err := validateSubagentClarifierOutput(subagentClarifierOutput{Type: "READY"}); err == nil {
		t.Fatal("READY without requirements was accepted")
	}
	if err := validateSubagentClarifierOutput(subagentClarifierOutput{Type: "READY", Requirements: map[string]any{}}); err != nil {
		t.Fatalf("valid READY was rejected: %v", err)
	}
}

func TestSubagentPromptsKeepClarificationNarrow(t *testing.T) {
	prompt := subagentSystemPrompt(SubagentRequirementClarifier)
	for _, required := range []string{"genuinely block", "Ask at most one question", "SINGLE_CHOICE"} {
		if !strings.Contains(prompt, required) {
			t.Fatalf("clarifier prompt missing %q", required)
		}
	}
}
