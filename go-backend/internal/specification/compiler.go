package specification

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

var (
	ErrPlanInvalid      = errors.New("SPECIFICATION_PLAN_INVALID")
	ErrForbiddenField   = errors.New("SPECIFICATION_FORBIDDEN_FIELD")
	ErrTemplateRequired = errors.New("TEMPLATE_BINDING_REQUIRED")
)

type SourceRefValidator func(map[string]any) error

// Compile turns the agent's semantic plan into the immutable, service-owned
// execution contract. No model-provided engine request is accepted.
func Compile(plan any, binding map[string]any) (map[string]any, string, error) {
	return CompileWithSourceValidator(plan, binding, nil)
}

func CompileWithSourceValidator(plan any, binding map[string]any, validator SourceRefValidator) (map[string]any, string, error) {
	if err := ValidateSemanticPlanWithSourceValidator(plan, validator); err != nil {
		return nil, "", err
	}
	if binding == nil || strings.TrimSpace(fmt.Sprint(binding["templateFileVersion"])) == "" || strings.TrimSpace(fmt.Sprint(binding["templateProfileVersion"])) == "" {
		return nil, "", ErrTemplateRequired
	}
	compiled := map[string]any{
		"compiler":        "lessonforge-semantic-v1",
		"plan":            plan,
		"templateBinding": binding,
	}
	encoded, err := json.Marshal(compiled)
	if err != nil {
		return nil, "", fmt.Errorf("compile specification: %w", err)
	}
	hash := sha256.Sum256(encoded)
	return compiled, hex.EncodeToString(hash[:]), nil
}

func ValidateSemanticPlan(plan any) error {
	return ValidateSemanticPlanWithSourceValidator(plan, nil)
}

func ValidateSemanticPlanWithSourceValidator(plan any, validator SourceRefValidator) error {
	root, ok := plan.(map[string]any)
	if !ok {
		return ErrPlanInvalid
	}
	slides, ok := root["slides"].([]any)
	if !ok || len(slides) == 0 {
		return ErrPlanInvalid
	}
	if err := rejectForbidden(root); err != nil {
		return err
	}
	if err := validateNestedSourceRefs(root, validator); err != nil {
		return err
	}
	for _, raw := range slides {
		slide, ok := raw.(map[string]any)
		if !ok || strings.TrimSpace(fmt.Sprint(slide["title"])) == "" {
			return ErrPlanInvalid
		}
		if regions, exists := slide["regions"]; exists {
			items, ok := regions.([]any)
			if !ok {
				return ErrPlanInvalid
			}
			for _, item := range items {
				region, ok := item.(map[string]any)
				if !ok || strings.TrimSpace(fmt.Sprint(region["position"])) == "" || region["content"] == nil {
					return ErrPlanInvalid
				}
			}
		}
	}
	return nil
}

func validateNestedSourceRefs(value any, validator SourceRefValidator) error {
	switch current := value.(type) {
	case map[string]any:
		if refs, ok := current["sourceRefs"]; ok {
			if err := validateSourceRefs(refs, validator); err != nil {
				return err
			}
		}
		for _, child := range current {
			if err := validateNestedSourceRefs(child, validator); err != nil {
				return err
			}
		}
	case []any:
		for _, child := range current {
			if err := validateNestedSourceRefs(child, validator); err != nil {
				return err
			}
		}
	}
	return nil
}

func validateSourceRefs(value any, validator SourceRefValidator) error {
	if value == nil {
		return nil
	}
	refs, ok := value.([]any)
	if !ok {
		return ErrPlanInvalid
	}
	for _, raw := range refs {
		ref, ok := raw.(map[string]any)
		if !ok {
			return ErrPlanInvalid
		}
		sourceType := strings.ToUpper(strings.TrimSpace(fmt.Sprint(ref["type"])))
		if sourceType != "MATERIAL" && sourceType != "TEACHER" && sourceType != "AI_EXAMPLE" && sourceType != "AI_IMAGE" {
			return ErrPlanInvalid
		}
		if strings.TrimSpace(fmt.Sprint(ref["id"])) == "" && strings.TrimSpace(fmt.Sprint(ref["label"])) == "" {
			return ErrPlanInvalid
		}
		if validator != nil && (sourceType == "MATERIAL" || sourceType == "TEACHER") {
			if err := validator(ref); err != nil {
				return err
			}
		}
	}
	return nil
}

func rejectForbidden(value any) error {
	for key, child := range objectChildren(value) {
		if forbiddenField(key) {
			return ErrForbiddenField
		}
		if err := rejectForbidden(child); err != nil {
			return err
		}
	}
	if items, ok := value.([]any); ok {
		for _, child := range items {
			if err := rejectForbidden(child); err != nil {
				return err
			}
		}
	}
	return nil
}

func objectChildren(value any) map[string]any {
	if object, ok := value.(map[string]any); ok {
		return object
	}
	return nil
}

func forbiddenField(key string) bool {
	switch strings.ToLower(strings.ReplaceAll(strings.ReplaceAll(key, "_", ""), "-", "")) {
	case "shapeid", "componentid", "groupid", "x", "y", "width", "height", "ooxml", "xml", "renderer", "renderercode", "trustedenginerequest", "enginerequest", "enginepayload", "authorization", "apikey", "credential":
		return true
	default:
		return false
	}
}
