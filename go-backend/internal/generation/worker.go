package generation

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/platform/database"
	"lessonforge.local/backend/internal/platform/storage"
	"lessonforge.local/backend/internal/pptengine"
)

type Worker struct {
	Store                   *database.Store
	Engine                  *pptengine.Client
	Composer                EngineV2Planner
	Storage                 *storage.Service
	OwnerResolver           func(context.Context, int64) (int64, error)
	EngineSharedStorageRoot string
	// commitArtifact is a same-package test seam. Production leaves it nil,
	// which routes through the real Store transaction below.
	commitArtifact func(context.Context, int64, int64, string, string, model.FileObject, string, any) (model.Artifact, error)
	// afterArtifactCommitHook is test-only and lets integration tests exercise
	// the real commit-to-verify crash/corruption window.
	afterArtifactCommitHook func(storage.File) error
	// afterArtifactFinalizeHook is test-only and controls the narrow interval
	// after READY is committed and before the second physical verification.
	afterArtifactFinalizeHook func(storage.File) error
	skipLeaseRenewal          bool
}

func (w *Worker) Start(ctx context.Context) {
	go func() {
		ticker := time.NewTicker(750 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := w.RunOnce(ctx); err != nil {
					// The scheduler has no caller to return to; retain the error in
					// the worker log while the next tick remains available for retry.
					log.Printf("generation worker error operation=run_once error=%v", err)
				}
			}
		}
	}()
}

// RunOnce performs one production claim-and-generation pass. It is also the
// bounded runtime entry point used by integration tests; Start only schedules
// this same method.
func (w *Worker) RunOnce(ctx context.Context) error {
	if err := w.recoverStaged(ctx); err != nil {
		return err
	}
	job, err := w.Store.ClaimGeneration(ctx)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		return err
	}
	owner, err := w.OwnerResolver(ctx, job.MissionID)
	if err != nil {
		return w.Store.FinishGeneration(ctx, job.ID, job.LeaseToken, "FAILED", map[string]string{"code": "MISSION_OWNER_UNAVAILABLE"}, nil)
	}
	spec, err := w.Store.LockedSpecificationForMission(ctx, job.MissionID, job.SpecificationID)
	if err != nil {
		return w.Store.FinishGeneration(ctx, job.ID, job.LeaseToken, "FAILED", map[string]string{"code": "LOCKED_SPECIFICATION_UNAVAILABLE"}, nil)
	}
	cancelled, err := w.Store.GenerationCancelRequested(ctx, job.ID)
	if err != nil {
		return err
	}
	if cancelled {
		return w.Store.FinishGeneration(ctx, job.ID, job.LeaseToken, "CANCELLED", map[string]string{"code": "GENERATION_CANCELLED"}, nil)
	}
	runCtx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	leaseStop := make(chan struct{})
	defer close(leaseStop)
	if !w.skipLeaseRenewal {
		go renewLease(runCtx, w.Store, job.ID, job.LeaseToken, cancel, leaseStop)
	}
	var result pptengine.ExecuteResult
	if w.Composer != nil {
		pkg, packageErr := buildEngineV2Package(spec, job, owner, w.EngineSharedStorageRoot)
		if packageErr != nil {
			return w.Store.FinishGenerationWithActivity(ctx, job.ID, job.LeaseToken, "FAILED", map[string]string{"code": safeCode(packageErr)}, "GENERATION_FAILED", "Generation requires a trusted Engine V2 package", "GENERATION_JOB", job.ID)
		}
		composeResult, composeErr := w.Composer.ComposePlan(runCtx, pkg.compose)
		if composeErr != nil {
			return w.Store.FinishGenerationWithActivity(ctx, job.ID, job.LeaseToken, "FAILED", composeFailureFeedback(composeResult, composeErr), "GENERATION_FAILED", "PPT Engine compose-plan failed", "GENERATION_JOB", job.ID)
		}
		executePayload, executeBuildErr := pkg.execute(composeResult.Plan)
		if executeBuildErr != nil {
			return w.Store.FinishGenerationWithActivity(ctx, job.ID, job.LeaseToken, "FAILED", map[string]string{"code": safeCode(executeBuildErr)}, "GENERATION_FAILED", "Generation requires a trusted Engine execute package", "GENERATION_JOB", job.ID)
		}
		result, err = w.Engine.ExecuteV2(runCtx, executePayload)
	} else {
		payload := enginePayload(spec)
		if len(payload) == 0 {
			return w.Store.FinishGenerationWithActivity(ctx, job.ID, job.LeaseToken, "FAILED", map[string]string{"code": "PPT_ENGINE_PAYLOAD_UNAVAILABLE"}, "GENERATION_FAILED", "Generation requires a trusted Engine execution package", "GENERATION_JOB", job.ID)
		}
		result, err = w.Engine.Execute(runCtx, payload)
	}
	if err != nil {
		return w.Store.FinishGenerationWithActivity(ctx, job.ID, job.LeaseToken, "FAILED", executeFailureFeedback(result, err), "GENERATION_FAILED", "PPT Engine generation failed", "GENERATION_JOB", job.ID)
	}
	cancelled, err = w.Store.GenerationCancelRequested(ctx, job.ID)
	if err != nil {
		return err
	}
	if cancelled {
		return w.Store.FinishGeneration(ctx, job.ID, job.LeaseToken, "CANCELLED", map[string]string{"code": "GENERATION_CANCELLED"}, nil)
	}
	if result.Status != "SUCCEEDED" && result.Status != "SUCCEEDED_WITH_FEEDBACK" {
		// The Engine executor contract calls its structured diagnostic list
		// `feedback`; `diagnostics` is the older client-side name. Preserve the
		// actual safe diagnostic payload so PARTIAL/FAILED jobs explain which
		// binding was incomplete instead of recording null.
		diagnostics := result.Diagnostics
		if len(diagnostics) == 0 {
			diagnostics = result.Feedback
		}
		return w.Store.FinishGeneration(ctx, job.ID, job.LeaseToken, "FAILED", map[string]any{"code": "PPT_ENGINE_FAILED", "status": result.Status, "diagnostics": diagnostics}, nil)
	}
	if len(result.Artifacts) == 0 {
		return w.Store.FinishGeneration(ctx, job.ID, job.LeaseToken, "FAILED", map[string]string{"code": "PPT_ENGINE_ARTIFACT_MISSING"}, nil)
	}
	receipt := result.Artifacts[0]
	data, err := w.Engine.ReadArtifact(runCtx, receipt, 200*1024*1024)
	if err != nil {
		return w.Store.FinishGenerationWithActivity(ctx, job.ID, job.LeaseToken, "FAILED", map[string]string{"code": artifactBridgeCode(err)}, "GENERATION_FAILED", "PPT Engine artifact bridge failed", "GENERATION_JOB", job.ID)
	}
	mimeType := receipt.ContentType
	if mimeType == "" {
		mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
	}
	// Each lease owns a distinct physical object.  A job id alone is not an
	// ownership boundary: after an expired lease is taken over, an old worker's
	// compensation can otherwise race a new worker that is writing the same
	// path before its file_objects row commits.  Keeping the lease token in the
	// key makes deletion and reconciliation attempt-local.
	storageKey := generationStorageKey(job.ID, job.LeaseToken)
	file, err := w.saveGeneratedFile(runCtx, owner, storageKey, "lessonforge-"+job.ID+".pptx", mimeType, data)
	if err != nil {
		return w.Store.FinishGeneration(ctx, job.ID, job.LeaseToken, "FAILED", map[string]string{"code": "ARTIFACT_STORAGE_FAILED"}, nil)
	}
	if err := w.Storage.Verify(runCtx, file); err != nil {
		return w.Store.FinishGeneration(ctx, job.ID, job.LeaseToken, "FAILED", map[string]string{"code": "ARTIFACT_STORAGE_INTEGRITY_FAILED"}, nil)
	}
	commit := w.commitArtifact
	if commit == nil {
		commit = w.Store.CommitGenerationArtifact
	}
	feedback := result.Diagnostics
	if len(feedback) == 0 {
		feedback = result.Feedback
	}
	artifact, err := commit(ctx, owner, job.MissionID, job.ID, job.LeaseToken, modelFile(file), mimeType, map[string]any{"engineArtifactId": receipt.ArtifactID, "diagnostics": feedback})
	if err != nil {
		reconciled, found, reconcileErr := w.Store.ReconcileGenerationArtifact(ctx, owner, job.MissionID, job.ID, job.LeaseToken, file.StorageKey)
		if reconcileErr == nil && found {
			return w.completeArtifact(ctx, owner, job.MissionID, job.ID, reconciled)
		}
		if reconcileErr == nil && !found {
			if referenced, referenceErr := w.Store.StorageKeyReferenced(ctx, file.StorageKey); referenceErr != nil {
				return errors.Join(err, referenceErr)
			} else if !referenced {
				if removeErr := w.Storage.Remove(file.StorageKey); removeErr != nil {
					return errors.Join(err, removeErr)
				}
			}
			// The stale attempt was compensated, but its fencing/commit error is
			// still observable by the scheduler. Returning nil here would disguise
			// a failed old token as a successful generation.
			return err
		}
		return errors.Join(err, reconcileErr)
	}
	if w.afterArtifactCommitHook != nil {
		if err := w.afterArtifactCommitHook(file); err != nil {
			return err
		}
	}
	return w.completeArtifact(ctx, owner, job.MissionID, job.ID, artifact)
}

func (w *Worker) saveGeneratedFile(ctx context.Context, owner int64, key, originalName, mimeType string, data []byte) (storage.File, error) {
	file, err := w.Storage.SaveBytesAtKey(ctx, key, originalName, mimeType, data)
	if err == nil || !strings.Contains(err.Error(), "storage key already contains different bytes") {
		return file, err
	}
	// A repeat of the *same* lease can encounter an unreferenced attempt key.
	// Different lease holders never share a key, so this retry cleanup cannot
	// remove bytes currently being written by a takeover worker.
	referenced, referenceErr := w.Store.StorageKeyReferenced(ctx, key)
	if referenceErr != nil {
		return storage.File{}, referenceErr
	}
	if referenced {
		return storage.File{}, err
	}
	if removeErr := w.Storage.Remove(key); removeErr != nil {
		return storage.File{}, errors.Join(err, removeErr)
	}
	return w.Storage.SaveBytesAtKey(ctx, key, originalName, mimeType, data)
}

func generationStorageKey(jobID, leaseToken string) string {
	return "generated/lessonforge-" + jobID + "-" + leaseToken + ".pptx"
}

func (w *Worker) recoverStaged(ctx context.Context) error {
	owner, artifact, found, err := w.Store.StagedGenerationArtifact(ctx)
	if err != nil || !found {
		return err
	}
	return w.completeArtifact(ctx, owner, artifact.MissionID, artifact.GenerationJobID, artifact)
}

func (w *Worker) completeArtifact(ctx context.Context, owner, missionID int64, jobID string, artifact model.Artifact) error {
	verified := true
	if err := w.Storage.Verify(ctx, storage.File{StorageKey: artifact.File.StorageKey, Size: artifact.File.Size, SHA256: artifact.File.SHA256}); err != nil {
		verified = false
		if invalidateErr := w.Store.InvalidateGenerationArtifact(ctx, owner, missionID, jobID, artifact.ID, map[string]string{"code": "ARTIFACT_STORAGE_INTEGRITY_FAILED"}); invalidateErr == nil {
			if referenced, referenceErr := w.Store.StorageKeyReferenced(ctx, artifact.File.StorageKey); referenceErr != nil {
				return referenceErr
			} else if !referenced {
				return w.Storage.Remove(artifact.File.StorageKey)
			}
		} else {
			return invalidateErr
		}
	}
	if !verified {
		return nil
	}
	if err := w.Store.FinalizeGenerationArtifact(ctx, owner, missionID, jobID, artifact.ID); err != nil {
		// An uncertain finalize result is reconciled by the next worker pass;
		// the staged file and row are retained until the outcome is known.
		return err
	}
	if w.afterArtifactFinalizeHook != nil {
		if err := w.afterArtifactFinalizeHook(storage.File{StorageKey: artifact.File.StorageKey, Size: artifact.File.Size, SHA256: artifact.File.SHA256}); err != nil {
			return err
		}
	}
	// Verify once more after the READY transition. If an external actor
	// replaced the file in that narrow window, remove the visible reference
	// through the same compensating path instead of returning success.
	if err := w.Storage.Verify(ctx, storage.File{StorageKey: artifact.File.StorageKey, Size: artifact.File.Size, SHA256: artifact.File.SHA256}); err != nil {
		if invalidateErr := w.Store.InvalidateGenerationArtifact(ctx, owner, missionID, jobID, artifact.ID, map[string]string{"code": "ARTIFACT_STORAGE_INTEGRITY_FAILED"}); invalidateErr == nil {
			if referenced, referenceErr := w.Store.StorageKeyReferenced(ctx, artifact.File.StorageKey); referenceErr != nil {
				return referenceErr
			} else if !referenced {
				return w.Storage.Remove(artifact.File.StorageKey)
			}
		} else {
			return invalidateErr
		}
		return nil
	}
	return nil
}

func renewLease(ctx context.Context, store *database.Store, jobID, leaseToken string, cancel context.CancelFunc, stop <-chan struct{}) {
	ticker := time.NewTicker(store.LeaseHeartbeatInterval())
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-stop:
			return
		case <-ticker.C:
			if err := store.RenewGenerationLease(ctx, jobID, leaseToken); err != nil {
				cancel()
				return
			}
		}
	}
}
func enginePayload(spec model.LockedSpecification) json.RawMessage {
	// This request is assembled by the backend from the immutable semantic
	// contract. There is no passthrough field for model-supplied engine JSON.
	encoded, err := json.Marshal(map[string]any{
		"compiler":        "lessonforge-semantic-v1",
		"specification":   spec.Specification,
		"templateBinding": spec.TemplateBinding,
	})
	if err != nil {
		return nil
	}
	return encoded
}
func safeCode(err error) string {
	if err == nil {
		return ""
	}
	if errors.Is(err, pptengine.ErrNotConfigured) {
		return "PPT_ENGINE_NOT_CONFIGURED"
	}
	message := strings.TrimSpace(err.Error())
	if strings.HasPrefix(message, "PPT_ENGINE_") {
		if index := strings.IndexByte(message, ':'); index > 0 {
			return message[:index]
		}
		return message
	}
	return "PPT_ENGINE_REQUEST_FAILED"
}

func composeFailureFeedback(result pptengine.ComposePlanResult, err error) map[string]any {
	feedback := map[string]any{"code": safeCode(err)}
	if len(result.Raw) == 0 {
		return feedback
	}
	feedback["engineResponseBytes"] = len(result.Raw)
	var envelope map[string]any
	if json.Unmarshal(result.Raw, &envelope) != nil {
		return feedback
	}
	// Preserve only the Engine's structured failure feedback. It contains
	// contract/diagnostic facts, never credentials or request headers, and is
	// needed to distinguish schema rejection from a resolver-level block.
	if raw, ok := envelope["feedback"].(map[string]any); ok {
		selected := map[string]any{}
		for _, key := range []string{"status", "diagnostics", "generationJobId", "executionAttemptId", "specificationId", "specificationVersion", "templateProfileId", "templateProfileVersion"} {
			if value, exists := raw[key]; exists {
				selected[key] = value
			}
		}
		if diagnostics, ok := raw["diagnostics"].([]any); ok {
			codes := make([]string, 0, len(diagnostics))
			for _, item := range diagnostics {
				if diagnostic, ok := item.(map[string]any); ok {
					if code, ok := diagnostic["code"].(string); ok && strings.TrimSpace(code) != "" {
						codes = append(codes, code)
					}
				}
			}
			if len(codes) > 0 {
				selected["diagnosticCodes"] = codes
			}
		}
		if len(selected) > 0 {
			feedback["engineFeedback"] = selected
		}
	}
	return feedback
}

func executeFailureFeedback(result pptengine.ExecuteResult, err error) map[string]any {
	feedback := map[string]any{"code": safeCode(err)}
	if len(result.Raw) == 0 {
		return feedback
	}
	feedback["engineResponseBytes"] = len(result.Raw)
	var envelope map[string]any
	if json.Unmarshal(result.Raw, &envelope) != nil {
		return feedback
	}
	selected := map[string]any{}
	for _, key := range []string{"status", "executionId", "specificationChecksum", "templateProfileChecksum", "assetManifestChecksum"} {
		if value, exists := envelope[key]; exists {
			selected[key] = value
		}
	}
	if raw, ok := envelope["feedback"].([]any); ok {
		diagnostics := make([]any, 0, len(raw))
		for _, item := range raw {
			diagnostic, ok := item.(map[string]any)
			if !ok {
				continue
			}
			safe := map[string]any{}
			for _, key := range []string{"code", "severity", "impact", "slideId", "pageNumber", "assetId", "componentId", "slotId", "messageKey", "safeDetails"} {
				if value, exists := diagnostic[key]; exists {
					safe[key] = value
				}
			}
			if len(safe) > 0 {
				diagnostics = append(diagnostics, safe)
			}
		}
		if len(diagnostics) > 0 {
			selected["diagnostics"] = diagnostics
		}
	}
	if len(selected) > 0 {
		feedback["engineFeedback"] = selected
	}
	return feedback
}

func artifactBridgeCode(err error) string {
	if errors.Is(err, pptengine.ErrArtifactBridgeNotConfigured) {
		return "PPT_ENGINE_ARTIFACT_BRIDGE_NOT_CONFIGURED"
	}
	return "PPT_ENGINE_ARTIFACT_BRIDGE_FAILED"
}
func modelFile(file storage.File) model.FileObject {
	return model.FileObject{OriginalName: file.OriginalName, MimeType: file.MimeType, Size: file.Size, SHA256: file.SHA256, StorageKey: file.StorageKey}
}
