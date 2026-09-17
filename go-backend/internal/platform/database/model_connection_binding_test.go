package database

import (
	"testing"

	"lessonforge.local/backend/internal/model"
)

func TestConnectionSupportsRoleRequiresVerifiedMatchingCapability(t *testing.T) {
	capabilities := model.ModelCapabilities{
		SupportsChat:       true,
		SupportsTools:      true,
		SupportsJSONMode:   true,
		SupportsVision:     true,
		SupportsEmbeddings: true,
		EmbeddingDimension: 1024,
	}
	verification := model.CapabilityVerification{
		SupportsChat:       model.CapabilityVerified,
		SupportsVision:     model.CapabilityVerified,
		SupportsEmbeddings: model.CapabilityVerified,
	}
	for _, role := range []string{model.ModelRolePlanning, model.ModelRoleTemplateVision, model.ModelRoleEmbedding} {
		if !connectionSupportsRole(role, capabilities, verification) {
			t.Fatalf("role %s unexpectedly rejected", role)
		}
	}
	legacyVerification := model.DefaultCapabilityVerification()
	if !connectionSupportsRole(model.ModelRolePlanning, capabilities, legacyVerification) {
		t.Fatal("legacy overall-verified planning connection with declared capabilities unexpectedly rejected")
	}
	verification.SupportsVision = model.CapabilityDeclared
	if connectionSupportsRole(model.ModelRoleTemplateVision, capabilities, verification) {
		t.Fatal("declared-only vision capability unexpectedly accepted")
	}
	capabilities.EmbeddingDimension = 0
	if connectionSupportsRole(model.ModelRoleEmbedding, capabilities, verification) {
		t.Fatal("embedding model without a verified vector dimension unexpectedly accepted")
	}
	if connectionSupportsRole("UNKNOWN", capabilities, verification) {
		t.Fatal("unknown role unexpectedly accepted")
	}
}
