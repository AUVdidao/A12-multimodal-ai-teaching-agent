package parser

import (
	"context"
	"errors"
	"io"
	"os"
	"strconv"
	"strings"
	"testing"

	"lessonforge.local/backend/internal/model"
)

type fakeStore struct {
	files   []model.MissionFile
	status  []string
	results []model.ParseResult
}

func (s *fakeStore) PendingMissionFiles(context.Context, int) ([]model.MissionFile, error) {
	return s.files, nil
}
func (s *fakeStore) SetMissionFileParseStatus(_ context.Context, missionID, fileID int64, status string) error {
	s.status = append(s.status, strings.Join([]string{stringID(missionID), stringID(fileID), status}, ":"))
	return nil
}
func (s *fakeStore) SaveMissionFileParseResult(_ context.Context, _ int64, _ int64, result model.ParseResult) error {
	s.results = append(s.results, result)
	return nil
}

type fakeStorage struct{}

func (fakeStorage) Open(context.Context, string) (*os.File, error) {
	return os.Open(os.DevNull)
}

type fakeAdapter struct {
	err    error
	result model.ParseResult
}

func (a fakeAdapter) Configured() bool { return !errors.Is(a.err, ErrNotConfigured) }

func (a fakeAdapter) Parse(context.Context, model.MissionFile, io.Reader) (model.ParseResult, error) {
	return a.result, a.err
}

func stringID(value int64) string {
	return strconv.FormatInt(value, 10)
}

func TestProcessOnceAdvancesReadyAndFailedStates(t *testing.T) {
	file := testFile()
	for _, test := range []struct {
		name  string
		err   error
		want  string
		ready bool
	}{
		{name: "ready", ready: true},
		{name: "failed", err: errors.New("parser rejected input"), want: "11:7:FAILED"},
	} {
		t.Run(test.name, func(t *testing.T) {
			store := &fakeStore{files: []model.MissionFile{file}}
			worker := &Worker{Store: store, Storage: fakeStorage{}, Adapter: fakeAdapter{err: test.err, result: model.ParseResult{Summary: "parsed"}}}
			if err := worker.ProcessOnce(context.Background()); err != nil {
				t.Fatal(err)
			}
			if test.ready {
				if len(store.results) != 1 || store.results[0].Summary != "parsed" {
					t.Fatalf("results = %#v", store.results)
				}
				return
			}
			if len(store.status) != 1 || store.status[0] != test.want {
				t.Fatalf("statuses = %#v, want %q", store.status, test.want)
			}
		})
	}
}

func TestProcessOncePersistsParserResultBeforeReady(t *testing.T) {
	store := &fakeStore{files: []model.MissionFile{testFile()}}
	result := model.ParseResult{Summary: "parsed", ExtractedText: "source", Keywords: []string{"a"}, TeachingStages: []string{"概念讲解"}}
	worker := &Worker{Store: store, Storage: fakeStorage{}, Adapter: fakeAdapter{result: result}}
	if err := worker.ProcessOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(store.results) != 1 || store.results[0].ExtractedText != "source" {
		t.Fatalf("results = %#v", store.results)
	}
	if len(store.status) != 0 {
		t.Fatalf("legacy status writer was used on success: %#v", store.status)
	}
}

func TestProcessOnceKeepsPendingWhenParserIsNotConfigured(t *testing.T) {
	store := &fakeStore{files: []model.MissionFile{testFile()}}
	worker := &Worker{Store: store, Storage: fakeStorage{}, Adapter: fakeAdapter{err: ErrNotConfigured}}
	if err := worker.ProcessOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(store.status) != 0 {
		t.Fatalf("not configured parser changed status: %#v", store.status)
	}
}

func TestProcessOnceKeepsPendingForRetryableRAGBindingFailure(t *testing.T) {
	store := &fakeStore{files: []model.MissionFile{testFile()}}
	worker := &Worker{
		Store:   store,
		Storage: fakeStorage{},
		Adapter: fakeAdapter{err: model.ErrRetryableRAGBinding},
	}
	if err := worker.ProcessOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(store.status) != 0 {
		t.Fatalf("retryable RAG binding failure changed pending file to terminal status: %#v", store.status)
	}
}
