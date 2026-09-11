package database

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"lessonforge.local/backend/internal/model"
)

type generationRequestFixture struct {
	store         *Store
	ctx           context.Context
	owner         model.User
	other         model.User
	missionID     int64
	templateID    int64
	missionFile   int64
	specID        string
	otherSpecID   string
	invalidSpecID string
}

func newGenerationRequestFixture(t *testing.T) generationRequestFixture {
	t.Helper()
	store, ctx := integrationStore(t)
	suffix := uuid.NewString()
	owner, err := store.CreateUser(ctx, "Generation owner", "generation-owner-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	other, err := store.CreateUser(ctx, "Generation other", "generation-other-"+suffix+"@example.test", "test-password-hash", model.RoleTeacher)
	if err != nil {
		t.Fatal(err)
	}
	var missionID int64
	if err := store.DB.QueryRow(ctx, `INSERT INTO missions(owner_teacher_id,source,title) VALUES($1,'SELF_CREATED','Generation request fixture') RETURNING id`, owner.ID).Scan(&missionID); err != nil {
		t.Fatal(err)
	}
	var messageID int64
	if err := store.DB.QueryRow(ctx, `INSERT INTO mission_messages(mission_id,role,content) VALUES($1,'USER','generation fixture') RETURNING id`, missionID).Scan(&messageID); err != nil {
		t.Fatal(err)
	}
	runID := uuid.NewString()
	if _, err := store.DB.Exec(ctx, `INSERT INTO agent_runs(id,mission_id,triggering_message_id,model_identity_snapshot,status) VALUES($1,$2,$3,'{"fixture":true}'::jsonb,'COMPLETED')`, runID, missionID, messageID); err != nil {
		t.Fatal(err)
	}
	var fileObjectID, missionFileID int64
	if err := store.DB.QueryRow(ctx, `INSERT INTO file_objects(owner_user_id,storage_key,original_name,mime_type,size_bytes,sha256) VALUES($1,$2,'fixture-template.pptx','application/vnd.openxmlformats-officedocument.presentationml.presentation',147487399,$3) RETURNING id`, owner.ID, "generation-template/"+suffix+".pptx", strings.Repeat("a", 64)).Scan(&fileObjectID); err != nil {
		t.Fatal(err)
	}
	if err := store.DB.QueryRow(ctx, `INSERT INTO mission_files(mission_id,file_object_id,role,provenance,parse_status,uploaded_by) VALUES($1,$2,'TEMPLATE','TEACHER','READY',$3) RETURNING id`, missionID, fileObjectID, owner.ID).Scan(&missionFileID); err != nil {
		t.Fatal(err)
	}
	binding := map[string]any{
		"bindingKind": "LESSONFORGE_UPSTREAM_TEMPLATE_BINDING", "contractVersion": "lessonforge-upstream-template-v1", "missionId": missionID,
		"missionFileId": missionFileID, "fileObjectId": fileObjectID, "ownerUserId": owner.ID,
		"templateStorageKey": "generation-template/" + suffix + ".pptx", "templateFileSha256": strings.Repeat("a", 64),
		"templateOriginalName": "fixture-template.pptx", "templateMimeType": "application/vnd.openxmlformats-officedocument.presentationml.presentation", "templateFileSize": 147487399,
	}
	bindingJSON, err := json.Marshal(binding)
	if err != nil {
		t.Fatal(err)
	}
	newSpec := func(version int, binding []byte) string {
		specID := uuid.NewString()
		draftID := uuid.NewString()
		if _, err := store.DB.Exec(ctx, `INSERT INTO planning_drafts(id,mission_id,version,markdown,structured_plan_json,created_by_agent_run_id) VALUES($1,$2,$3,'fixture','{"slides":[{"title":"fixture"}]}'::jsonb,$4)`, draftID, missionID, version, runID); err != nil {
			t.Fatal(err)
		}
		if _, err := store.DB.Exec(ctx, `INSERT INTO locked_specifications(id,mission_id,source_draft_id,version,specification_json,template_binding_json,content_hash) VALUES($1,$2,$3,$4,'{"slides":[{"title":"fixture"}]}'::jsonb,$5,$6)`, specID, missionID, draftID, version, binding, fmt.Sprintf("%064x", version)); err != nil {
			t.Fatal(err)
		}
		return specID
	}
	specID := newSpec(1, bindingJSON)
	otherSpecID := newSpec(2, bindingJSON)
	invalidSpecID := newSpec(3, []byte(`{}`))
	t.Cleanup(func() {
		cleanupCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		triggerDisabled := false
		if _, err := store.DB.Exec(cleanupCtx, `ALTER TABLE locked_specifications DISABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
			t.Errorf("disable immutable trigger for generation cleanup: %v", err)
		} else {
			triggerDisabled = true
		}
		defer func() {
			if triggerDisabled {
				if _, err := store.DB.Exec(cleanupCtx, `ALTER TABLE locked_specifications ENABLE TRIGGER locked_specifications_immutable_trg`); err != nil {
					t.Errorf("restore immutable trigger after generation cleanup: %v", err)
				}
			}
		}()
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM missions WHERE id=$1`, missionID); err != nil {
			t.Errorf("cleanup generation mission: %v", err)
		}
		if _, err := store.DB.Exec(cleanupCtx, `DELETE FROM users WHERE id=$1 OR id=$2`, owner.ID, other.ID); err != nil {
			t.Errorf("cleanup generation users: %v", err)
		}
	})
	return generationRequestFixture{store: store, ctx: ctx, owner: owner, other: other, missionID: missionID, templateID: fileObjectID, missionFile: missionFileID, specID: specID, otherSpecID: otherSpecID, invalidSpecID: invalidSpecID}
}

func TestCreateGenerationJobValidatesBoundaryAndAllowsTerminalRetry(t *testing.T) {
	f := newGenerationRequestFixture(t)
	first, err := f.store.CreateGenerationJob(f.ctx, f.owner.ID, f.missionID, f.specID, 1)
	if err != nil {
		t.Fatalf("create generation job: %v", err)
	}
	if !first.Created || first.Job.Status != "QUEUED" || first.Job.SpecificationID != f.specID || first.Job.SpecificationVersion != 1 {
		t.Fatalf("created generation job = %+v", first)
	}
	duplicate, err := f.store.CreateGenerationJob(f.ctx, f.owner.ID, f.missionID, f.specID, 1)
	if err != nil {
		t.Fatalf("duplicate generation request: %v", err)
	}
	if duplicate.Created || duplicate.Job.ID != first.Job.ID {
		t.Fatalf("duplicate result = %+v, want existing job", duplicate)
	}
	if _, err := f.store.CreateGenerationJob(f.ctx, f.owner.ID, f.missionID, f.specID, 2); err != ErrGenerationVersionMismatch {
		t.Fatalf("version mismatch = %v, want %v", err, ErrGenerationVersionMismatch)
	}
	if _, err := f.store.CreateGenerationJob(f.ctx, f.other.ID, f.missionID, f.specID, 1); err != ErrGenerationMissionNotFound {
		t.Fatalf("cross-owner result = %v, want %v", err, ErrGenerationMissionNotFound)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE generation_jobs SET status='FAILED',finished_at=now() WHERE id=$1`, first.Job.ID); err != nil {
		t.Fatal(err)
	}
	retry, err := f.store.CreateGenerationJob(f.ctx, f.owner.ID, f.missionID, f.specID, 1)
	if err != nil {
		t.Fatalf("terminal retry: %v", err)
	}
	if !retry.Created || retry.Job.ID == first.Job.ID || retry.Job.Status != "QUEUED" {
		t.Fatalf("terminal retry = %+v", retry)
	}
	if _, err := f.store.CreateGenerationJob(f.ctx, f.owner.ID, f.missionID, f.otherSpecID, 2); err != ErrGenerationActiveConflict {
		t.Fatalf("active conflict = %v, want %v", err, ErrGenerationActiveConflict)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE mission_files SET parse_status='PENDING' WHERE id=$1`, f.missionFile); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.CreateGenerationJob(f.ctx, f.owner.ID, f.missionID, f.specID, 1); err != ErrGenerationMaterialsNotReady {
		t.Fatalf("material readiness = %v, want %v", err, ErrGenerationMaterialsNotReady)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE mission_files SET parse_status='READY' WHERE id=$1`, f.missionFile); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.DB.Exec(f.ctx, `UPDATE generation_jobs SET status='FAILED',finished_at=now() WHERE id=$1`, retry.Job.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := f.store.CreateGenerationJob(f.ctx, f.owner.ID, f.missionID, f.invalidSpecID, 3); err != ErrGenerationTemplateInvalid {
		t.Fatalf("template validation = %v, want %v", err, ErrGenerationTemplateInvalid)
	}
}
