package templatebinding

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"lessonforge.local/backend/internal/model"
)

var ErrInvalidTemplate = errors.New("LESSONFORGE_TEMPLATE_BINDING_INVALID")

// Input is the server-owned identity of a Mission TEMPLATE file. It is
// deliberately independent of Java's TemplateProfileVersion: upstream
// LessonForge only needs a durable file binding before the downstream Engine
// profile exists.
type Input struct {
	MissionID     int64
	MissionFileID int64
	FileObjectID  int64
	OwnerUserID   int64
	OriginalName  string
	MimeType      string
	StorageKey    string
	SHA256        string
	Size          int64
	ParseStatus   string
	LastModified  time.Time
}

// Build validates a real stored PPTX when storageRoot is configured and
// returns a deterministic, Go-owned upstream template binding. It does not
// claim that the file has an Engine-native profile or is generation-ready.
func Build(input Input, storageRoot string) (map[string]any, error) {
	if input.MissionID <= 0 || input.MissionFileID <= 0 || input.FileObjectID <= 0 || input.OwnerUserID <= 0 ||
		input.Size <= 0 || strings.TrimSpace(input.StorageKey) == "" || strings.ToUpper(strings.TrimSpace(input.ParseStatus)) != "READY" {
		return nil, ErrInvalidTemplate
	}
	digest := strings.ToLower(strings.TrimSpace(input.SHA256))
	if !isSHA256(digest) || !isPPTXName(input.OriginalName) || !isPPTXMIME(input.MimeType) {
		return nil, ErrInvalidTemplate
	}
	lastModified := input.LastModified.UTC()
	if strings.TrimSpace(storageRoot) != "" {
		path, err := safeStoragePath(storageRoot, input.StorageKey)
		if err != nil {
			return nil, ErrInvalidTemplate
		}
		info, err := os.Stat(path)
		if err != nil || !info.Mode().IsRegular() || info.Size() != input.Size {
			return nil, ErrInvalidTemplate
		}
		if err := verifyPPTX(path, input.Size, digest); err != nil {
			return nil, ErrInvalidTemplate
		}
		lastModified = info.ModTime().UTC()
	}
	return map[string]any{
		"bindingKind":                "LESSONFORGE_UPSTREAM_TEMPLATE_BINDING",
		"contractVersion":            "lessonforge-upstream-template-v1",
		"missionId":                  input.MissionID,
		"missionFileId":              input.MissionFileID,
		"fileObjectId":               input.FileObjectID,
		"ownerUserId":                input.OwnerUserID,
		"templateId":                 "mission-file-" + strconv.FormatInt(input.MissionFileID, 10),
		"profileId":                  "lessonforge-profile-" + strconv.FormatInt(input.MissionFileID, 10) + "-" + digest[:16],
		"templateVersion":            1,
		"profileVersion":             1,
		"templateFileVersion":        "sha256-" + digest,
		"templateProfileVersion":     "lessonforge-template-profile-v1",
		"templateFileSha256":         digest,
		"sourceSha256":               digest,
		"templateStorageKey":         input.StorageKey,
		"templateFileSize":           input.Size,
		"templateOriginalName":       input.OriginalName,
		"templateMimeType":           input.MimeType,
		"templateLastModifiedUtc":    lastModified.Format(time.RFC3339Nano),
		"profileSource":              "GO_FILE_IDENTITY",
		"executionReady":             false,
		"engineNativeProfilePresent": false,
	}, nil
}

// Resolver supplies the same Go-owned binding to the Agent's optional
// get_template_capability tool. It intentionally does not call Java's old
// Template Profile/Capability endpoint.
type Resolver struct {
	Files       FileSource
	StorageRoot string
}

type FileSource interface {
	MissionFiles(context.Context, int64, int64) ([]model.MissionFile, error)
}

func (r Resolver) GetForOwner(ctx context.Context, ownerID, missionID int64) (any, error) {
	if r.Files == nil || ownerID <= 0 || missionID <= 0 {
		return nil, ErrInvalidTemplate
	}
	files, err := r.Files.MissionFiles(ctx, ownerID, missionID)
	if err != nil {
		return nil, ErrInvalidTemplate
	}
	for _, file := range files {
		if file.Role != "TEMPLATE" || file.OwnerUserID != ownerID {
			continue
		}
		binding, buildErr := Build(Input{
			MissionID: missionID, MissionFileID: file.ID, FileObjectID: file.FileObject.ID,
			OwnerUserID: ownerID, OriginalName: file.FileObject.OriginalName, MimeType: file.FileObject.MimeType,
			StorageKey: file.FileObject.StorageKey, SHA256: file.FileObject.SHA256, Size: file.FileObject.Size,
			ParseStatus: file.ParseStatus, LastModified: file.FileObject.CreatedAt,
		}, r.StorageRoot)
		if buildErr == nil {
			return binding, nil
		}
	}
	return nil, ErrInvalidTemplate
}

func isPPTXName(name string) bool {
	return strings.EqualFold(filepath.Ext(strings.TrimSpace(name)), ".pptx")
}

func isPPTXMIME(mime string) bool {
	return strings.EqualFold(strings.TrimSpace(mime), "application/vnd.openxmlformats-officedocument.presentationml.presentation")
}

func isSHA256(value string) bool {
	if len(value) != 64 {
		return false
	}
	for _, r := range value {
		if !((r >= '0' && r <= '9') || (r >= 'a' && r <= 'f')) {
			return false
		}
	}
	return true
}

func safeStoragePath(root, key string) (string, error) {
	if strings.TrimSpace(root) == "" || strings.TrimSpace(key) == "" || filepath.IsAbs(key) || strings.ContainsAny(key, "\\\x00") {
		return "", ErrInvalidTemplate
	}
	clean := filepath.Clean(filepath.FromSlash(key))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", ErrInvalidTemplate
	}
	return filepath.Join(root, clean), nil
}

func verifyPPTX(path string, size int64, expected string) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, f); err != nil {
		return err
	}
	if !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), expected) {
		return ErrInvalidTemplate
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return err
	}
	archive, err := zip.NewReader(f, size)
	if err != nil {
		return err
	}
	var contentTypes, presentation bool
	for _, entry := range archive.File {
		switch entry.Name {
		case "[Content_Types].xml":
			contentTypes = true
		case "ppt/presentation.xml":
			presentation = true
		}
	}
	if !contentTypes || !presentation {
		return fmt.Errorf("%w: pptx entries missing", ErrInvalidTemplate)
	}
	return nil
}
