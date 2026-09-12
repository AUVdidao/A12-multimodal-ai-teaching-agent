package agent

import (
	"context"
	"errors"
	"log"
	"time"

	"github.com/jackc/pgx/v5"
	"lessonforge.local/backend/internal/model"
	"lessonforge.local/backend/internal/platform/database"
)

func StartWorker(ctx context.Context, store *database.Store, runtime *Runtime, timeout time.Duration) {
	if err := store.RecoverAgentRuns(ctx); err != nil {
		log.Printf("agent worker error operation=recover_interrupted error=%v", err)
	}
	go func() {
		ticker := time.NewTicker(500 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := store.QueueReadyRuns(ctx); err != nil {
					log.Printf("agent worker error operation=queue_ready_runs error=%v", err)
				}
				if err := RunOnce(ctx, store, runtime, timeout); err != nil {
					log.Printf("agent worker error operation=run_once error=%v", err)
				}
			}
		}
	}()
}

func StartMaintenance(ctx context.Context, store *database.Store, files interface{ Remove(string) error }) {
	go func() {
		ticker := time.NewTicker(10 * time.Minute)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := runMaintenanceOnce(ctx, store, files); err != nil {
					// A failed pass is visible and the ticker continues, so one bad
					// object neither hides the failure nor leaks the maintenance goroutine.
					log.Printf("agent maintenance error operation=cleanup_expired_uploads error=%v", err)
				}
				if err := store.CleanupExpiredModelExecutionLeases(ctx); err != nil {
					log.Printf("agent maintenance error operation=cleanup_model_execution_leases error=%v", err)
				}
			}
		}
	}()
}

type expiredUploadCleaner interface {
	CleanupExpiredUploads(context.Context) ([]string, error)
	FinalizeExpiredUpload(context.Context, string) error
}

func runMaintenanceOnce(ctx context.Context, store expiredUploadCleaner, files interface{ Remove(string) error }) error {
	keys, err := store.CleanupExpiredUploads(ctx)
	if err != nil {
		return err
	}
	for _, key := range keys {
		if err := files.Remove(key); err != nil {
			return errors.Join(errors.New("remove expired upload "+key), err)
		}
		if err := store.FinalizeExpiredUpload(ctx, key); err != nil {
			return errors.Join(errors.New("finalize expired upload "+key), err)
		}
	}
	return nil
}

// RunOnce performs one production queue/claim/runtime pass. StartWorker only
// schedules this same entry point, which keeps bounded integration execution
// on the real worker path.
func RunOnce(ctx context.Context, store *database.Store, runtime *Runtime, timeout time.Duration) error {
	run, err := store.ClaimAgent(ctx)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		return err
	}
	return runClaimed(ctx, store, runtime, timeout, run)
}

func runClaimed(ctx context.Context, store *database.Store, runtime *Runtime, timeout time.Duration, run model.AgentRun) error {
	runCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	stopWatch := make(chan struct{})
	defer close(stopWatch)
	go watchCancellation(runCtx, store, run.ID, cancel, stopWatch)
	go renewLease(runCtx, store, run.ID, run.LeaseToken, cancel, stopWatch)
	var err error
	err = runtime.Run(runCtx, run)
	cancelled, checkErr := store.AgentCancelled(ctx, run.ID)
	if checkErr != nil {
		return checkErr
	}
	if cancelled {
		return store.FinishAgentWithActivity(ctx, run.ID, run.LeaseToken, "CANCELLED", "AGENT_CANCELLED", "", "AGENT_RUN_CANCELLED", "Agent run cancelled", "AGENT_RUN", run.ID)
	}
	if errors.Is(err, ErrWaitingForInput) {
		return store.FinishAgentWithActivity(ctx, run.ID, run.LeaseToken, "WAITING_INPUTS", "", "", "AGENT_RUN_WAITING_INPUTS", "Agent run is waiting for teacher input", "AGENT_RUN", run.ID)
	}
	if err != nil {
		// Output commit ambiguity is reconciled by the stage-aware Store method.
		// A runtime-level error has no safe stage identity, so never infer
		// completion from another output stage here.
		if errors.Is(err, database.ErrCommitAmbiguous) {
			return err
		}
		code := "AGENT_FAILED"
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, model.ErrTimeout) {
			code = "AGENT_TIMEOUT"
		}
		return errors.Join(err, store.FinishAgentWithActivity(ctx, run.ID, run.LeaseToken, "FAILED", code, "Agent run failed", "AGENT_RUN_FAILED", "Agent run failed", "AGENT_RUN", run.ID))
	}
	return store.FinishAgentWithActivity(ctx, run.ID, run.LeaseToken, "COMPLETED", "", "", "AGENT_RUN_COMPLETED", "Agent run completed", "AGENT_RUN", run.ID)
}

func renewLease(ctx context.Context, store *database.Store, runID, leaseToken string, cancel context.CancelFunc, stop <-chan struct{}) {
	ticker := time.NewTicker(store.LeaseHeartbeatInterval())
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-stop:
			return
		case <-ticker.C:
			if err := store.RenewAgentLease(ctx, runID, leaseToken); err != nil {
				cancel()
				return
			}
		}
	}
}
func watchCancellation(ctx context.Context, store *database.Store, runID string, cancel context.CancelFunc, stop <-chan struct{}) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-stop:
			return
		case <-ticker.C:
			cancelled, err := store.AgentCancelled(ctx, runID)
			if err != nil {
				log.Printf("agent worker error operation=watch_cancellation run_id=%s error=%v", runID, err)
				cancel()
				return
			}
			if cancelled {
				cancel()
				return
			}
		}
	}
}
