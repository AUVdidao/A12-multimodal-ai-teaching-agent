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

func TestPlanningRoleSkillsAreEmbeddedInMatchingPrompts(t *testing.T) {
	teaching := subagentSystemPrompt(SubagentInstructionalDesigner)
	for _, required := range []string{"Installed role skill", "objective to an explanation", "INSTRUCTIONAL_PROPOSAL"} {
		if !strings.Contains(teaching, required) {
			t.Fatalf("instructional prompt missing %q", required)
		}
	}
	if strings.Contains(teaching, "metadata:") {
		t.Fatal("skill frontmatter leaked into instructional prompt")
	}
	presentation := subagentSystemPrompt(SubagentPresentationArchitect)
	for _, required := range []string{"Installed role skill", "Do not choose card grids", "PRESENTATION_PROPOSAL"} {
		if !strings.Contains(presentation, required) {
			t.Fatalf("presentation prompt missing %q", required)
		}
	}
	if strings.Contains(subagentSystemPrompt(SubagentPlanComposer), "Installed role skill") {
		t.Fatal("planning skill was installed on the wrong role")
	}
	interactionPrompt := subagentSystemPrompt(SubagentInteractionDesigner)
	for _, required := range []string{"Installed role skill", "INTERACTION_PROPOSAL", "Do not copy a slide point"} {
		if !strings.Contains(interactionPrompt, required) {
			t.Fatalf("interaction prompt missing %q", required)
		}
	}
}

func TestValidateInstructionalProposal(t *testing.T) {
	valid := `{"type":"INSTRUCTIONAL_PROPOSAL","objectives":["理解智能体循环"],"units":[{"title":"循环结构","objective":"解释循环","learnerContent":["观察后再行动"],"teacherNotes":["用案例引入"],"evidenceRefs":[{"fileId":21,"locator":"chunk:3","claim":"材料给出循环定义"}]}],"evidenceGaps":[],"unresolvedDecisions":[]}`
	if err := validateInstructionalProposal(valid); err != nil {
		t.Fatalf("valid instructional proposal rejected: %v", err)
	}
	if err := validateInstructionalProposal(`{"type":"INSTRUCTIONAL_PROPOSAL","objectives":[],"units":[],"evidenceGaps":[],"unresolvedDecisions":[]}`); err == nil {
		t.Fatal("empty instructional proposal was accepted")
	}
}

func TestValidatePresentationProposal(t *testing.T) {
	valid := `{"type":"PRESENTATION_PROPOSAL","slides":[{"title":"智能体循环","purpose":"解释顺序关系","points":["感知","决策","行动"],"pageType":"PROCESS","visualFocus":"循环关系","informationHierarchy":["TITLE","FOCUS"],"contentDensity":"BALANCED","componentRequirements":["TEXT"],"sources":["file:21#chunk:3"],"notes":"逐步讲解"}],"capabilityGaps":[],"unresolvedDecisions":[]}`
	if err := validatePresentationProposal(valid); err != nil {
		t.Fatalf("valid presentation proposal rejected: %v", err)
	}
	invalid := strings.Replace(valid, `"pageType":"PROCESS"`, `"pageType":"FREEFORM"`, 1)
	if err := validatePresentationProposal(invalid); err == nil {
		t.Fatal("unsupported page type was accepted")
	}
	withGeometry := strings.Replace(valid, `"notes":"逐步讲解"`, `"notes":"逐步讲解","x":10`, 1)
	if err := validatePresentationProposal(withGeometry); err == nil {
		t.Fatal("presentation proposal with geometry was accepted")
	}
}
