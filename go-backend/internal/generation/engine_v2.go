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
	"lessonforge.local/backend/internal/platform/database"
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
	bindingForEngine := spec.TemplateBinding
	if job.GenerationMode == database.GenerationModeSystemDefault {
		fallback, fallbackErr := prepareSystemDefaultTemplate(spec, job, owner, sharedRoot)
		if fallbackErr != nil {
			return engineV2Package{}, fallbackErr
		}
		bindingForEngine = fallback.binding
	}
	profile, err := engineProfile(bindingForEngine, job.MissionID)
	if err != nil {
		return engineV2Package{}, err
	}
	if text(profile["projectId"]) != strconv.FormatInt(job.MissionID, 10) || text(profile["ownerUserId"]) != strconv.FormatInt(owner, 10) {
		return engineV2Package{}, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_BINDING_MISMATCH")
	}
	engineSpec, err := engineSpecification(spec.Specification, spec.ID, spec.Version, job.MissionID, owner, spec.CreatedAt, profile)
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
	// The Engine checks the checksum of the profile after Go projects the Java
	// bridge identity onto the LessonForge Mission. Teacher bindings therefore
	// retain Java's exact canonical JSON and only replace server-owned identity
	// fields; system-default bindings use the local compatibility normalizer.
	profileChecksum, checksumErr := engineProfileChecksum(bindingForEngine, profile)
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
	templateSource, err := templateSource(bindingForEngine, sharedRoot)
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
	if text(profile["missionId"]) != strconv.FormatInt(missionID, 10) {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_MISSION_MISMATCH")
	}
	// A teacher binding is an adapter envelope. When Java has persisted the
	// native profile, use that object as the Engine input; the envelope's
	// contractVersion is the upstream binding contract, not the Engine profile
	// contract. System-default bindings intentionally have no nested native
	// object and use their top-level server-owned profile instead.
	profileSource := profile
	if native, ok := profile["engineNativeProfile"].(map[string]any); ok && len(native) > 0 {
		profileSource = native
	}
	encoded, err := json.Marshal(profileSource)
	if err != nil {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_INCOMPLETE")
	}
	var copy map[string]any
	if err := json.Unmarshal(encoded, &copy); err != nil {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_INCOMPLETE")
	}
	// The Java bridge resolves a teacher-owned Java Project ID, while the
	// Engine contract is project-scoped to the LessonForge Mission. Keep the
	// Java identity at the adapter boundary and emit only the local Mission ID
	// in the Engine-native profile projection.
	copy["projectId"] = strconv.FormatInt(missionID, 10)
	ownerID := text(copy["ownerUserId"])
	if ownerID == "" {
		ownerID = text(profile["ownerUserId"])
	}
	if ownerID == "" {
		return nil, errors.New("PPT_ENGINE_TEMPLATE_PROFILE_INCOMPLETE")
	}
	copy["ownerUserId"] = ownerID
	// These are Go's storage binding facts, not fields in the Engine profile
	// schema. They are consumed by templateSource below.
	for _, key := range []string{
		"templateFileSha256", "templateStorageKey", "templateFileSize", "templateLastModifiedUtc", "capability",
		// These fields belong to the Java bridge envelope or its persistence
		// projection, not to the canonical Engine Profile schema.
		"bindingKind", "engineNativeProfile", "engineNativeProfileChecksum", "engineNativeProfilePresent", "executionReady", "fileObjectId", "missionFileId", "missionId", "profileSource", "templateFileSha256", "templateFileSize", "templateFileVersion", "templateLastModifiedUtc", "templateMimeType", "templateOriginalName", "templateProfileVersion", "templateStorageKey",
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

func engineSpecification(raw any, specID string, specVersion int, missionID, owner int64, lockedAt time.Time, profile map[string]any) (map[string]any, error) {
	root, ok := raw.(map[string]any)
	if !ok {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
	}
	var candidate map[string]any
	semanticPlan := false
	if compiler, ok := root["compiler"].(string); ok && compiler == "lessonforge-semantic-v1" {
		compiledPlan, ok := root["plan"].(map[string]any)
		if !ok {
			return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
		}
		candidate = compiledPlan
		semanticPlan = true
	} else if _, hasSlides := root["slides"]; hasSlides {
		candidate = root
	} else if compiledPlan, ok := root["plan"].(map[string]any); ok {
		if _, hasSlides := compiledPlan["slides"]; hasSlides {
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
	if semanticPlan {
		copy, err = projectSemanticPlan(copy, specID, specVersion, missionID, owner, lockedAt, profile)
		if err != nil {
			return nil, err
		}
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

// projectSemanticPlan is the only adapter from the teacher-facing semantic
// plan to the Engine V2 locked-specification contract. The model may describe
// teaching content, but it cannot provide project identity, lock metadata,
// template profile identity, or native layout instructions.
func projectSemanticPlan(plan map[string]any, specID string, specVersion int, missionID, owner int64, lockedAt time.Time, profile map[string]any) (map[string]any, error) {
	rawSlides, ok := plan["slides"].([]any)
	if !ok || len(rawSlides) == 0 || len(rawSlides) > 200 || missionID <= 0 || owner <= 0 {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
	}
	profileID := text(profile["profileId"])
	profileVersion := intValue(profile["profileVersion"])
	if profileID == "" || profileVersion <= 0 {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_PROFILE_MISMATCH")
	}
	semanticRole, ok := firstExecutableSemanticRole(profile)
	if !ok {
		return nil, errors.New("PPT_ENGINE_SEMANTIC_ROLE_UNAVAILABLE")
	}
	if lockedAt.IsZero() {
		lockedAt = time.Now().UTC()
	}

	slides := make([]any, 0, len(rawSlides))
	for index, rawSlide := range rawSlides {
		slide, ok := rawSlide.(map[string]any)
		if !ok {
			return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
		}
		title := text(slide["title"])
		purpose := text(slide["purpose"])
		if title == "" || purpose == "" {
			return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
		}
		keyPoints, err := semanticTextList(slide["keyPoints"])
		if err != nil || len(keyPoints) == 0 {
			return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
		}
		role := semanticRole
		if requested, ok := slide["semanticLayout"].(map[string]any); ok && text(requested["primaryRole"]) != "" {
			requestedRole := text(requested["primaryRole"])
			if !semanticRoleAvailable(profile, requestedRole) {
				return nil, errors.New("PPT_ENGINE_SEMANTIC_ROLE_UNAVAILABLE")
			}
			role = requestedRole
		}
		blocks := []any{
			semanticContentBlock(fmt.Sprintf("slide-%d-title", index+1), "TITLE", title, specID, index+1),
			semanticContentBlock(fmt.Sprintf("slide-%d-purpose", index+1), "BODY", purpose, specID, index+1),
			semanticContentBlock(fmt.Sprintf("slide-%d-key-points", index+1), "BULLETS", strings.Join(keyPoints, "\n"), specID, index+1),
		}
		slides = append(slides, map[string]any{
			"slideId":       fmt.Sprintf("slide-%d", index+1),
			"pageNumber":    index + 1,
			"title":         title,
			"teachingGoal":  purpose,
			"contentBlocks": blocks,
			"semanticLayout": map[string]any{
				"primaryRole": role,
				"regions": []any{map[string]any{
					"regionId":          fmt.Sprintf("slide-%d-content", index+1),
					"semanticRole":      role,
					"preferredPosition": "CENTER",
					"maxItems":          len(blocks),
				}},
				"requestedTransform": nil,
			},
			"assetRequirements": []any{},
			"provenance":        []any{},
			"notes":             text(slide["notes"]),
		})
	}

	return map[string]any{
		"contractVersion":        engineSpecVersion,
		"specificationId":        specID,
		"projectId":              strconv.FormatInt(missionID, 10),
		"version":                specVersion,
		"status":                 "LOCKED",
		"templateProfileId":      profileID,
		"templateProfileVersion": profileVersion,
		"targetSlideCount":       len(slides),
		"slideCountTolerance":    0,
		"locale":                 "zh-CN",
		"provider":               "LESSONFORGE",
		"model":                  "semantic-plan-v1",
		"aiSupplementPolicy":     "DISABLED",
		"lockedBy":               strconv.FormatInt(owner, 10),
		"lockedAt":               lockedAt.UTC().Format(time.RFC3339Nano),
		"slides":                 slides,
	}, nil
}

func semanticContentBlock(blockID, blockType, content, specID string, slideNumber int) map[string]any {
	return map[string]any{
		"blockId":         blockID,
		"type":            blockType,
		"content":         content,
		"sourceType":      "AI_EXAMPLE",
		"sourceReference": fmt.Sprintf("planning-draft:%s:slide-%d", specID, slideNumber),
		"locked":          true,
	}
}

func semanticTextList(value any) ([]string, error) {
	items, ok := value.([]any)
	if !ok {
		return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
	}
	result := make([]string, 0, len(items))
	for _, item := range items {
		value := text(item)
		if value == "" {
			return nil, errors.New("PPT_ENGINE_SPECIFICATION_INCOMPLETE")
		}
		result = append(result, value)
	}
	return result, nil
}

func firstExecutableSemanticRole(profile map[string]any) (string, bool) {
	refs, ok := profile["templatePageReferences"].([]any)
	if !ok {
		return "", false
	}
	for _, raw := range refs {
		ref, ok := raw.(map[string]any)
		if !ok {
			continue
		}
		role := text(ref["semanticRole"])
		if role != "" && !strings.EqualFold(role, "UNMAPPED") {
			return role, true
		}
	}
	return "", false
}

func semanticRoleAvailable(profile map[string]any, requested string) bool {
	refs, ok := profile["templatePageReferences"].([]any)
	if !ok {
		return false
	}
	for _, raw := range refs {
		ref, ok := raw.(map[string]any)
		if ok && text(ref["semanticRole"]) == requested && !strings.EqualFold(requested, "UNMAPPED") {
			return true
		}
	}
	return false
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

func engineProfileChecksum(rawBinding any, profile map[string]any) (string, error) {
	binding, ok := rawBinding.(map[string]any)
	if !ok {
		return "", errors.New("PPT_ENGINE_TEMPLATE_PROFILE_CHECKSUM_UNAVAILABLE")
	}
	if native, hasNative := binding["engineNativeProfile"].(map[string]any); hasNative && len(native) > 0 {
		// Re-encoding a Java profile through map[string]any changes both object
		// ordering and lexical forms such as 1.0. Keep the exact Java canonical
		// string and project only the two server-owned identity fields.
		raw, ok := binding["engineNativeProfileJson"].(string)
		if !ok || strings.TrimSpace(raw) == "" {
			return "", errors.New("PPT_ENGINE_TEMPLATE_PROFILE_CHECKSUM_UNAVAILABLE")
		}
		expected := strings.ToLower(strings.TrimSpace(text(binding["engineNativeProfileChecksum"])))
		if !validSHA256(expected) || sha256Text(raw) != expected {
			return "", errors.New("PPT_ENGINE_TEMPLATE_PROFILE_CHECKSUM_UNAVAILABLE")
		}
		projected := raw
		for _, field := range []string{"projectId", "ownerUserId"} {
			value := text(profile[field])
			if value == "" {
				return "", errors.New("PPT_ENGINE_TEMPLATE_PROFILE_CHECKSUM_UNAVAILABLE")
			}
			var err error
			projected, err = replaceCanonicalStringField(projected, field, value)
			if err != nil {
				return "", errors.New("PPT_ENGINE_TEMPLATE_PROFILE_CHECKSUM_UNAVAILABLE")
			}
		}
		return sha256Text(projected), nil
	}

	// System-default profiles are authored by Go. Their checksum uses the
	// same Java-compatible decimal normalization as the fallback profile.
	normalized := normalizeEngineProfileNumbers(profile, false)
	value, ok := normalized.(map[string]any)
	if !ok {
		return "", errors.New("PPT_ENGINE_TEMPLATE_PROFILE_CHECKSUM_UNAVAILABLE")
	}
	return canonicalChecksum(value)
}

func validSHA256(value string) bool {
	decoded, err := hex.DecodeString(value)
	return err == nil && len(decoded) == sha256.Size
}

func sha256Text(value string) string {
	digest := sha256.Sum256([]byte(value))
	return hex.EncodeToString(digest[:])
}

// replaceCanonicalStringField replaces exactly one top-level JSON object field
// while preserving every other byte of the canonical Java serialization.
func replaceCanonicalStringField(raw, field, desired string) (string, error) {
	data := []byte(raw)
	if len(data) == 0 || !json.Valid(data) {
		return "", errors.New("invalid canonical profile JSON")
	}
	type replacement struct {
		start   int
		end     int
		encoded []byte
	}
	replacements := make([]replacement, 0, 1)
	depth := 0
	for index := 0; index < len(data); {
		switch data[index] {
		case '"':
			start := index
			end, err := jsonStringEnd(data, start)
			if err != nil {
				return "", err
			}
			if depth == 1 {
				var key string
				if err := json.Unmarshal(data[start:end], &key); err != nil {
					return "", err
				}
				cursor := end
				for cursor < len(data) && isJSONWhitespace(data[cursor]) {
					cursor++
				}
				if key == field && cursor < len(data) && data[cursor] == ':' {
					cursor++
					for cursor < len(data) && isJSONWhitespace(data[cursor]) {
						cursor++
					}
					if cursor >= len(data) || data[cursor] != '"' {
						return "", errors.New("canonical profile identity is not a string")
					}
					valueEnd, err := jsonStringEnd(data, cursor)
					if err != nil {
						return "", err
					}
					encoded, err := json.Marshal(desired)
					if err != nil {
						return "", err
					}
					replacements = append(replacements, replacement{start: cursor, end: valueEnd, encoded: encoded})
					index = valueEnd
					continue
				}
			}
			index = end
		case '{', '[':
			depth++
			index++
		case '}', ']':
			depth--
			if depth < 0 {
				return "", errors.New("invalid canonical profile nesting")
			}
			index++
		default:
			index++
		}
	}
	if len(replacements) != 1 {
		return "", fmt.Errorf("canonical profile field %q count = %d", field, len(replacements))
	}
	result := append([]byte(nil), data...)
	for index := len(replacements) - 1; index >= 0; index-- {
		replacement := replacements[index]
		result = append(append(append([]byte(nil), result[:replacement.start]...), replacement.encoded...), result[replacement.end:]...)
	}
	return string(result), nil
}

func jsonStringEnd(data []byte, start int) (int, error) {
	for index := start + 1; index < len(data); index++ {
		if data[index] == '\\' {
			index++
			continue
		}
		if data[index] == '"' {
			return index + 1, nil
		}
	}
	return 0, errors.New("unterminated canonical profile string")
}

func isJSONWhitespace(value byte) bool {
	return value == ' ' || value == '\t' || value == '\r' || value == '\n'
}

func normalizeEngineProfileNumbers(value any, decimalContext bool) any {
	switch current := value.(type) {
	case map[string]any:
		for key, child := range current {
			if key == "confidence" || (decimalContext && (key == "x" || key == "y" || key == "width" || key == "height")) {
				if number, ok := javaDecimalNumber(child); ok {
					current[key] = number
					continue
				}
			}
			current[key] = normalizeEngineProfileNumbers(child, key == "originalBounds")
		}
	case []any:
		for index, child := range current {
			current[index] = normalizeEngineProfileNumbers(child, decimalContext)
		}
	}
	return value
}

func javaDecimalNumber(value any) (json.Number, bool) {
	var rendered string
	switch number := value.(type) {
	case float64:
		rendered = strconv.FormatFloat(number, 'f', -1, 64)
	case float32:
		rendered = strconv.FormatFloat(float64(number), 'f', -1, 32)
	case json.Number:
		rendered = number.String()
	default:
		return "", false
	}
	if !strings.ContainsAny(rendered, ".eE") {
		rendered += ".0"
	}
	return json.Number(rendered), true
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
