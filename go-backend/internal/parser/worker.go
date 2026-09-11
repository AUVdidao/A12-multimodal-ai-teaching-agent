package parser

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"time"

	"lessonforge.local/backend/internal/model"
)

type Store interface {
	PendingMissionFiles(context.Context, int) ([]model.MissionFile, error)
	SetMissionFileParseStatus(context.Context, int64, int64, string) error
	SaveMissionFileParseResult(context.Context, int64, int64, model.ParseResult) error
}

type Storage interface {
	Open(context.Context, string) (*os.File, error)
}

type Worker struct {
	Store   Store
	Storage Storage
	Adapter Adapter
}

func (w *Worker) ProcessOnce(ctx context.Context) error {
	files, err := w.Store.PendingMissionFiles(ctx, 20)
	if err != nil {
		return err
	}
	for _, file := range files {
		if err := w.processFile(ctx, file); err != nil {
			log.Printf("parser worker file failed owner_user_id=%d mission_id=%d mission_file_id=%d error=%v", file.OwnerUserID, file.MissionID, file.ID, err)
			if errors.Is(err, ErrNotConfigured) {
				continue
			}
			if errors.Is(err, model.ErrCommitAmbiguous) {
				// The result transaction may already be durable. Do not
				// overwrite a possibly committed READY state with FAILED.
				return err
			}
			if errors.Is(err, model.ErrRetryableRAGBinding) {
				// Java may already have durably accepted the material while the
				// Go-side identity binding failed. Keep the file PENDING so the
				// next worker pass can retry the same idempotent identity.
				continue
			}
			if statusErr := w.Store.SetMissionFileParseStatus(ctx, file.OwnerUserID, file.ID, "FAILED"); statusErr != nil {
				return errors.Join(err, statusErr)
			}
		}
	}
	return nil
}

func (w *Worker) processFile(ctx context.Context, file model.MissionFile) (err error) {
	if w.Adapter == nil || !w.Adapter.Configured() {
		return ErrNotConfigured
	}
	reader, err := w.Storage.Open(ctx, file.FileObject.StorageKey)
	if err != nil {
		if statusErr := w.Store.SetMissionFileParseStatus(ctx, file.OwnerUserID, file.ID, "FAILED"); statusErr != nil {
			return errors.Join(err, statusErr)
		}
		return err
	}
	defer func() {
		if closeErr := reader.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close parser input: %w", closeErr))
		}
	}()
	result, err := w.Adapter.Parse(ctx, file, reader)
	if err != nil {
		return err
	}
	return w.Store.SaveMissionFileParseResult(ctx, file.OwnerUserID, file.ID, result)
}

func StartWorker(ctx context.Context, worker *Worker, interval time.Duration) {
	if interval <= 0 {
		interval = 5 * time.Second
	}
	go func() {
		if err := worker.ProcessOnce(ctx); err != nil {
			log.Printf("parser worker: %v", err)
		}
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				if err := worker.ProcessOnce(ctx); err != nil {
					log.Printf("parser worker: %v", err)
				}
			}
		}
	}()
}
