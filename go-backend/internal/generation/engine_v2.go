package generation

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/pptengine"
)

const (
	engineContractVersion = "2.0.0"
	engineSpecVersion     = "1.0.0"
	manifestVersion       = "1.0.0"
)

type EngineV2Planner interface {
	ComposePlan(context.Context, json.RawMessage) (pptengine.ComposePlanResult, error)
}

// engineV2Package keeps Engine-owned plan data out of Go. The execute request
// is assembled only after compose-plan returns a non-empty plan.
type engineV2Package struct {
	compose json.RawMessage
	execute func(json.RawMessage) (json.RawMessage, error)
}

func buildEngineV2Package(spec model.LockedSpecification, job model.GenerationJob, owner int64, sharedRoot string) (engineV2Package, error) {
	profile, err := engineProfile(spec.TemplateBinding, job.MissionID)
	if err != nil {
		return engineV2Package{}, err
	}
	if text(profile["projectId"]) != strconv.FormatInt(job.MissionID, 10) || text(profile["ownerUserId"]) != strconv.FormatInt(owner, 10) {
		return engineV2Package{}, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_BINDING_MISMATCH")
	}
	engineSpec, err := engineSpecification(spec.Specification, spec.ID, spec.Version, job.MissionID, profile)
	if err != nil {
		return engineV2Package{}, err
	}
	specificationChecksum, err := canonicalChecksumWithout(engineSpec, "checksum")
	if err != nil {
		return engineV2Package{}, err
	}
	engineSpec["checksum"] = specificationChecksum
	manifest, err := approvedManifest(engineSpec, job, owner)
	if err != nil {
		return engineV2Package{}, err
	}
	// The Java capability service is authoritative for the canonical native
	// profile checksum. Recomputing it after the profile crossed the JSON/JSONB
	// boundary in Go is unsafe: Jackson preserves typed decimal values such as
	// 1.0 while Go's JSON decoder normalizes them to 1. The profile payload is
	// still validated and projected above; only the checksum is carried across
	// this adapter boundary from the same server-owned native profile.
	profileChecksum, checksumErr := bridgeProfileChecksum(spec.TemplateBinding)
	if checksumErr != nil {
		return engineV2Package{}, checksumErr
	}
	manifestChecksum, err := canonicalChecksumWithout(manifest, "manifestChecksum")
	if err != nil {
		return engineV2Package{}, err
	}
	manifest["manifestChecksum"] = manifestChecksum

	generationJob, err := engineGenerationJob(spec, job, owner, specificationChecksum, profile, profileChecksum, manifest, manifestChecksum)
	if err != nil {
		return engineV2Package{}, err
	}
	templateSource, err := templateSource(spec.TemplateBinding, sharedRoot)
	if err != nil {
		return engineV2Package{}, err
	}

	compose := map[string]any{
		"contractVersion":       engineContractVersion,
		"requestId":             generationJob["requestId"],
		"generationJob":         generationJob["generationJob"],
		"specification":         engineSpec,
		"templateProfile":       profile,
		"approvedAssetManifest": manifest,
	}
	composeJSON, err := json.Marshal(compose)
	if err != nil {
		return engineV2Package{}, fmt.Errorf("encode Engine compose request: %w", err)
	}

	return engineV2Package{
		compose: composeJSON,
		execute: func(plan json.RawMessage) (json.RawMessage, error) {
			if len(strings.TrimSpace(string(plan))) == 0 || string(plan) == "null" {
				return nil, errors.New("PPT_ENGINE_COMPOSE_PLAN_MISSING")
			}
			executeRequest := map[string]any{}
			for key, value := range compose {
				executeRequest[key] = value
			}
			executeRequest["plan"] = json.RawMessage(append([]byte(nil), plan...))
			executeRequest["templateSource"] = templateSource
			executeRequest["approvedAssetFiles"] = []any{}
			encoded, encodeErr := json.Marshal(executeRequest)
			if encodeErr != nil {
				return nil, fmt.Errorf("encode Engine execute request: %w", encodeErr)
			}
			return encoded, nil
		},
	}, nil
}

func engineProfile(raw any, missionID int64) (map[string]any, error) {
	profile, ok := raw.(map[string]any)
	if !ok {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_INCOMPLETE")
	}
	encoded, err := json.Marshal(profile)
	if err != nil {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_INCOMPLETE")
	}
	var copy map[string]any
	if err := json.Unmarshal(encoded, &copy); err != nil {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_INCOMPLETE")
	}
	if text(copy["missionId"]) != strconv.FormatInt(missionID, 10) {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_MISSION_MISMATCH")
	}
	// The Java bridge resolves a teacher-owned Java Project ID, while the
	// Engine contract is project-scoped to the LessonForge Mission. Keep the
	// Java identity at the adapter boundary and emit only the local Mission ID
	// in the Engine-native profile projection.
	copy["projectId"] = strconv.FormatInt(missionID, 10)
	delete(copy, "missionId")
	// These are Go's storage binding facts, not fields in the Engine profile
	// schema. They are consumed by templateSource below.
	for _, key := range []string{
		"templateFileSha256", "templateStorageKey", "templateFileSize", "templateLastModifiedUtc", "capability",
		// These fields belong to the Java bridge envelope or its persistence
		// projection, not to the canonical Engine Profile schema.
		"engineNativeProfile", "engineNativeProfileChecksum", "missionId", "templateFileVersion", "templateProfileVersion",
	} {
		delete(copy, key)
	}
	for _, key := range []string{"contractVersion", "profileId", "templateId", "projectId", "ownerUserId", "templateVersion", "profileVersion", "status", "pageSize", "spatialProfile", "templatePageReferences", "components", "textFitPolicy", "executionStatus", "sourceVersionId", "sourceSha256", "parserSnapshotChecksum"} {
		if _, exists := copy[key]; !exists && key != "preservedNativeObjects" {
			return nil, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_INCOMPLETE")
		}
	}
	// Jackson serializes the record with NON_EMPTY for this optional list. Keep
	// Go's canonical profile checksum byte-identical when the DB stores an empty
	// array, while preserving the field whenever native objects are present.
	if preserved, ok := copy["preservedNativeObjects"].([]any); ok && len(preserved) == 0 {
		delete(copy, "preservedNativeObjects")
	}
	return copy, nil
}

func engineSpecification(raw any, specID string, specVersion int, missionID int64, profile map[string]any) (map[string]any, error) {
	root, ok := raw.(map[string]any)
	if !ok {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
	}
	var candidate map[string]any
	if nested, ok := root["engineSpecification"].(map[string]any); ok {
		candidate = nested
	} else if _, hasSlides := root["slides"]; hasSlides {
		candidate = root
	} else if compiledPlan, ok := root["plan"].(map[string]any); ok {
		if nested, ok := compiledPlan["engineSpecification"].(map[string]any); ok {
			candidate = nested
		} else if _, hasSlides := compiledPlan["slides"]; hasSlides {
			candidate = compiledPlan
		} else {
			return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
		}
	} else {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
	}
	encoded, err := json.Marshal(candidate)
	if err != nil {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
	}
	copy := map[string]any{}
	if err := json.Unmarshal(encoded, &copy); err != nil {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
	}
	if text(copy["specificationId"]) == "" {
		copy["specificationId"] = specID
	}
	if text(copy["projectId"]) != strconv.FormatInt(missionID, 10) {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_PROJECT_MISMATCH")
	}
	if text(copy["specificationId"]) != specID || intValue(copy["version"]) != specVersion || text(copy["status"]) != "LOCKED" {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_BINDING_MISMATCH")
	}
	if text(copy["templateProfileId"]) != text(profile["profileId"]) || intValue(copy["templateProfileVersion"]) != intValue(profile["profileVersion"]) {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_PROFILE_MISMATCH")
	}
	for _, key := range []string{"contractVersion", "version", "templateProfileId", "templateProfileVersion", "targetSlideCount", "slideCountTolerance", "locale", "provider", "model", "aiSupplementPolicy", "lockedBy", "lockedAt", "slides"} {
		if _, exists := copy[key]; !exists {
			return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
		}
	}
	if text(copy["contractVersion"]) != engineSpecVersion {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_VERSION_INVALID")
	}
	return copy, nil
}

func approvedManifest(spec map[string]any, job model.GenerationJob, owner int64) (map[string]any, error) {
	entries := make([]any, 0)
	slides, _ := spec["slides"].([]any)
	for _, rawSlide := range slides {
		slide, _ := rawSlide.(map[string]any)
		requirements, _ := slide["assetRequirements"].([]any)
		for _, rawRequirement := range requirements {
			requirement, _ := rawRequirement.(map[string]any)
			assetID := text(requirement["assetId"])
			if assetID == "" {
				return nil, errors.New("PPT_ENGINE_ASSET_REQUIREMENT_INVALID")
			}
			if boolValue(requirement["required"]) {
				return nil, errors.New("PPT_ENGINE_REQUIRED_ASSET_UNRESOLVED")
			}
			entries = append(entries, map[string]any{"assetRequirementId": assetID, "resolution": "APPROVED_OMISSION"})
		}
	}
	return map[string]any{
		"contractVersion":       manifestVersion,
		"manifestId":            "manifest-" + job.ID,
		"projectId":             strconv.FormatInt(job.MissionID, 10),
		"ownerUserId":           strconv.FormatInt(owner, 10),
		"manifestVersion":       1,
		"status":                "APPROVED",
		"specificationId":       text(spec["specificationId"]),
		"specificationVersion":  intValue(spec["version"]),
		"specificationChecksum": text(spec["checksum"]),
		"manifestChecksum":      strings.Repeat("0", 64),
		"entries":               entries,
	}, nil
}

func engineGenerationJob(spec model.LockedSpecification, job model.GenerationJob, owner int64, specChecksum string, profile map[string]any, profileChecksum string, manifest map[string]any, manifestChecksum string) (map[string]any, error) {
	requestID := "generation-" + uuid.NewString()
	jobObject := map[string]any{
		"generationJobId":              job.ID,
		"executionAttemptId":           uuid.NewString(),
		"projectId":                    strconv.FormatInt(job.MissionID, 10),
		"ownerUserId":                  strconv.FormatInt(owner, 10),
		"jobBindingChecksum":           strings.Repeat("0", 64),
		"specificationBinding":         map[string]any{"inputId": spec.ID, "inputVersion": spec.Version, "inputChecksum": specChecksum},
		"templateProfileBinding":       map[string]any{"inputId": text(profile["profileId"]), "inputVersion": intValue(profile["profileVersion"]), "inputChecksum": profileChecksum},
		"approvedAssetManifestBinding": map[string]any{"inputId": text(manifest["manifestId"]), "inputVersion": intValue(manifest["manifestVersion"]), "inputChecksum": manifestChecksum},
		"composeContractVersion":       engineContractVersion,
		"planContractVersion":          engineContractVersion,
		"engineBuildVersion":           "external-engine",
		"executorAdapterVersion":       "lessonforge-go-v1",
		"fontEnvironmentVersion":       "UNBOUND",
		"requestedBy":                  strconv.FormatInt(owner, 10),
		"requestedAt":                  time.Now().UTC().Format(time.RFC3339Nano),
		"idempotencyKey":               "generation:" + job.ID,
	}
	checksum, err := canonicalChecksumWithout(jobObject, "executionAttemptId", "jobBindingChecksum")
	if err != nil {
		return nil, err
	}
	jobObject["jobBindingChecksum"] = checksum
	return map[string]any{"requestId": requestID, "generationJob": jobObject}, nil
}

func templateSource(raw any, sharedRoot string) (map[string]any, error) {
	binding, ok := raw.(map[string]any)
	if !ok {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_SOURCE_NOT_CONFIGURED")
	}
	key := text(binding["templateStorageKey"])
	digest := text(binding["templateFileSha256"])
	if key == "" || digest == "" || intValue(binding["templateFileSize"]) <= 0 || text(binding["templateLastModifiedUtc"]) == "" || strings.TrimSpace(sharedRoot) == "" {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_SOURCE_NOT_CONFIGURED")
	}
	_, err := sharedPath(sharedRoot, key)
	if err != nil {
		return nil, err
	}
	// The Go and Engine processes may mount the same shared volume at different
	// absolute paths.  The Engine contract deliberately accepts only a key
	// relative to its controlled input root, so never leak the Go container's
	// absolute mount point across the service boundary.
	cleanKey := filepath.Clean(filepath.FromSlash(key))
	engineKey := filepath.ToSlash(cleanKey)
	result := map[string]any{
		"storageKey":      engineKey,
		"sha256":          digest,
		"size":            intValue(binding["templateFileSize"]),
		"lastModifiedUtc": text(binding["templateLastModifiedUtc"]),
	}
	if id := text(binding["templateId"]); id != "" {
		result["templateId"] = id
	}
	if version := intValue(binding["templateVersion"]); version > 0 {
		result["templateVersion"] = version
	}
	if sourceVersion := intValue(binding["sourceVersionId"]); sourceVersion > 0 {
		result["sourceVersionId"] = sourceVersion
	}
	return result, nil
}

func sharedPath(root, key string) (string, error) {
	if filepath.IsAbs(key) || strings.ContainsAny(key, "\\\x00") {
		return "", errors.New("PPT_ENGINE_TEMPLATE_SOURCE_KEY_INVALID")
	}
	clean := filepath.Clean(filepath.FromSlash(key))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", errors.New("PPT_ENGINE_TEMPLATE_SOURCE_KEY_INVALID")
	}
	rootAbs, err := filepath.Abs(root)
	if err != nil {
		return "", errors.New("PPT_ENGINE_TEMPLATE_SOURCE_NOT_CONFIGURED")
	}
	pathAbs, err := filepath.Abs(filepath.Join(rootAbs, clean))
	if err != nil || (pathAbs != rootAbs && !strings.HasPrefix(pathAbs, rootAbs+string(filepath.Separator))) {
		return "", errors.New("PPT_ENGINE_TEMPLATE_SOURCE_KEY_INVALID")
	}
	return pathAbs, nil
}

func canonicalChecksum(value map[string]any) (string, error) {
	return canonicalChecksumWithout(value)
}

func canonicalChecksumWithout(value map[string]any, excluded ...string) (string, error) {
	copy := make(map[string]any, len(value))
	for key, item := range value {
		copy[key] = item
	}
	for _, key := range excluded {
		delete(copy, key)
	}
	encoded, err := json.Marshal(copy)
	if err != nil {
		return "", fmt.Errorf("canonical Engine checksum: %w", err)
	}
	hash := sha256.Sum256(encoded)
	return hex.EncodeToString(hash[:]), nil
}

func bridgeProfileChecksum(raw any) (string, error) {
	binding, ok := raw.(map[string]any)
	if !ok {
		return "", errors.New("PPT_ENGINE_TEMPLATE_PROFILE_CHECKSUM_UNAVAILABLE")
	}
	checksum := strings.ToLower(strings.TrimSpace(text(binding["engineNativeProfileChecksum"])))
	decoded, err := hex.DecodeString(checksum)
	if err != nil || len(decoded) != sha256.Size {
		return "", errors.New("PPT_ENGINE_TEMPLATE_PROFILE_CHECKSUM_UNAVAILABLE")
	}
	return checksum, nil
}

func text(value any) string {
	if value == nil {
		return ""
	}
	return strings.TrimSpace(fmt.Sprint(value))
}

func intValue(value any) int {
	switch number := value.(type) {
	case int:
		return number
	case int64:
		return int(number)
	case float64:
		return int(number)
	case json.Number:
		parsed, _ := number.Int64()
		return int(parsed)
	case string:
		parsed, _ := strconv.Atoi(number)
		return parsed
	default:
		return 0
	}
}

func boolValue(value any) bool {
	valueBool, _ := value.(bool)
	return valueBool
}
