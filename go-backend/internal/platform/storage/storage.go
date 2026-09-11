package storage

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"mime"
	"mime/multipart"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type File struct {
	StorageKey   string
	OriginalName string
	MimeType     string
	Size         int64
	SHA256       string
}

type Service struct {
	root     string
	maxBytes int64
}

func New(root string, maxBytes int64) (*Service, error) {
	if filepath.IsAbs(root) == false {
		absolute, err := filepath.Abs(root)
		if err != nil {
			return nil, err
		}
		root = absolute
	}
	if err := os.MkdirAll(root, 0o750); err != nil {
		return nil, err
	}
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return nil, err
	}
	return &Service{root: resolvedRoot, maxBytes: maxBytes}, nil
}

func (s *Service) SaveMultipart(ctx context.Context, header *multipart.FileHeader) (result File, err error) {
	if header == nil {
		return File{}, errors.New("file is required")
	}
	if header.Size > s.maxBytes {
		return File{}, fmt.Errorf("file exceeds %d bytes", s.maxBytes)
	}
	name := filepath.Base(header.Filename)
	if name == "." || name == "" || strings.ContainsAny(name, "\x00\r\n") {
		return File{}, errors.New("invalid file name")
	}
	suffix := fmt.Sprintf("%d-%s", time.Now().UnixNano(), name)
	key := filepath.Join("uploads", suffix)
	target, err := s.safePath(key)
	if err != nil {
		return File{}, err
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o750); err != nil {
		return File{}, err
	}
	src, err := header.Open()
	if err != nil {
		return File{}, err
	}
	dst, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o640)
	if err != nil {
		if closeErr := src.Close(); closeErr != nil {
			return File{}, errors.Join(err, fmt.Errorf("close multipart source: %w", closeErr))
		}
		return File{}, err
	}
	defer func() {
		if closeErr := dst.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close multipart destination: %w", closeErr))
		}
		if closeErr := src.Close(); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close multipart source: %w", closeErr))
		}
	}()
	hash := sha256.New()
	reader := io.LimitReader(io.TeeReader(src, hash), s.maxBytes+1)
	size, err := io.Copy(dst, reader)
	if err != nil {
		return File{}, err
	}
	if size > s.maxBytes {
		if cleanupErr := removePath(target); cleanupErr != nil {
			return File{}, fmt.Errorf("file exceeds %d bytes; cleanup: %w", s.maxBytes, cleanupErr)
		}
		return File{}, fmt.Errorf("file exceeds %d bytes", s.maxBytes)
	}
	select {
	case <-ctx.Done():
		if cleanupErr := removePath(target); cleanupErr != nil {
			return File{}, fmt.Errorf("save cancelled: %w; cleanup: %v", ctx.Err(), cleanupErr)
		}
		return File{}, ctx.Err()
	default:
	}
	mimeType := normalizedMultipartMime(name, header.Header.Get("Content-Type"))
	return File{StorageKey: filepath.ToSlash(key), OriginalName: name, MimeType: mimeType, Size: size, SHA256: hex.EncodeToString(hash.Sum(nil))}, nil
}

// normalizedMultipartMime keeps browser clients that send the generic
// application/octet-stream value from losing the type required by the
// downstream LessonForge intake contract. A non-generic client declaration is
// preserved and remains subject to downstream extension/MIME validation.
func normalizedMultipartMime(name, provided string) string {
	mimeType := strings.TrimSpace(strings.ToLower(provided))
	if mimeType != "" && mimeType != "application/octet-stream" {
		return mimeType
	}
	known := map[string]string{
		".doc":  "application/msword",
		".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
		".jpg":  "image/jpeg",
		".jpeg": "image/jpeg",
		".md":   "text/markdown",
		".mp4":  "video/mp4",
		".pdf":  "application/pdf",
		".png":  "image/png",
		".ppt":  "application/vnd.ms-powerpoint",
		".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
		".txt":  "text/plain",
		".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
	}
	if detected := known[strings.ToLower(filepath.Ext(name))]; detected != "" {
		return detected
	}
	if detected := mime.TypeByExtension(strings.ToLower(filepath.Ext(name))); detected != "" {
		return detected
	}
	return "application/octet-stream"
}

func (s *Service) SaveBytes(ctx context.Context, originalName, mimeType string, data []byte) (File, error) {
	if len(data) == 0 || int64(len(data)) > s.maxBytes {
		return File{}, errors.New("generated file size is invalid")
	}
	name := filepath.Base(originalName)
	if name == "." || name == "" || strings.ContainsAny(name, "\x00\r\n") {
		return File{}, errors.New("invalid file name")
	}
	key := filepath.ToSlash(filepath.Join("generated", fmt.Sprintf("%d-%s", time.Now().UnixNano(), name)))
	return s.SaveBytesAtKey(ctx, key, name, mimeType, data)
}

// SaveBytesAtKey writes a generated file to a caller-selected stable key. If
// the key already contains identical bytes, it returns the existing file so a
// retried job remains storage-idempotent.
func (s *Service) SaveBytesAtKey(ctx context.Context, key, originalName, mimeType string, data []byte) (File, error) {
	if len(data) == 0 || int64(len(data)) > s.maxBytes {
		return File{}, errors.New("generated file size is invalid")
	}
	name := filepath.Base(originalName)
	if name == "." || name == "" || strings.ContainsAny(name, "\x00\r\n") {
		return File{}, errors.New("invalid file name")
	}
	key = filepath.ToSlash(key)
	target, err := s.safePath(key)
	if err != nil {
		return File{}, err
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o750); err != nil {
		return File{}, err
	}
	select {
	case <-ctx.Done():
		return File{}, ctx.Err()
	default:
	}
	hash := sha256.Sum256(data)
	if existing, err := os.ReadFile(target); err == nil {
		if len(existing) == len(data) && sha256.Sum256(existing) == hash {
			if mimeType == "" {
				mimeType = "application/octet-stream"
			}
			return File{StorageKey: key, OriginalName: name, MimeType: mimeType, Size: int64(len(existing)), SHA256: hex.EncodeToString(hash[:])}, nil
		}
		return File{}, errors.New("storage key already contains different bytes")
	} else if !os.IsNotExist(err) {
		return File{}, err
	}
	file, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o640)
	if err != nil {
		if os.IsExist(err) {
			return s.SaveBytesAtKey(ctx, key, name, mimeType, data)
		}
		return File{}, err
	}
	if _, err := file.Write(data); err != nil {
		closeErr := file.Close()
		cleanupErr := removePath(target)
		if cleanupErr != nil {
			return File{}, fmt.Errorf("write generated file: %w; cleanup: %v", err, cleanupErr)
		}
		if closeErr != nil {
			return File{}, fmt.Errorf("write generated file: %w; close: %v", err, closeErr)
		}
		return File{}, err
	}
	if err := file.Close(); err != nil {
		if cleanupErr := removePath(target); cleanupErr != nil {
			return File{}, fmt.Errorf("close generated file: %w; cleanup: %v", err, cleanupErr)
		}
		return File{}, err
	}
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}
	return File{StorageKey: key, OriginalName: name, MimeType: mimeType, Size: int64(len(data)), SHA256: hex.EncodeToString(hash[:])}, nil
}

func (s *Service) Open(ctx context.Context, key string) (*os.File, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	default:
	}
	path, err := s.safePath(key)
	if err != nil {
		return nil, err
	}
	return os.Open(path)
}

// OpenVerified opens one stable file handle and verifies the bytes that will
// be served from that same handle. This prevents a successful verification of
// one inode from being followed by a read from a replaced or truncated path.
func (s *Service) OpenVerified(ctx context.Context, file File) (*os.File, error) {
	if file.Size <= 0 || file.Size > s.maxBytes || file.SHA256 == "" {
		return nil, errors.New("stored file metadata is invalid")
	}
	path, err := s.safePath(file.StorageKey)
	if err != nil {
		return nil, err
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	hash := sha256.New()
	count, err := io.CopyN(hash, f, s.maxBytes+1)
	if err != nil && !errors.Is(err, io.EOF) {
		if closeErr := f.Close(); closeErr != nil {
			return nil, fmt.Errorf("stored file read: %w; close: %v", err, closeErr)
		}
		return nil, err
	}
	if count != file.Size {
		if closeErr := f.Close(); closeErr != nil {
			return nil, fmt.Errorf("stored file size mismatch; close: %v", closeErr)
		}
		return nil, errors.New("stored file size mismatch")
	}
	if !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), file.SHA256) {
		if closeErr := f.Close(); closeErr != nil {
			return nil, fmt.Errorf("stored file checksum mismatch; close: %v", closeErr)
		}
		return nil, errors.New("stored file checksum mismatch")
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		if closeErr := f.Close(); closeErr != nil {
			return nil, fmt.Errorf("stored file seek: %w; close: %v", err, closeErr)
		}
		return nil, err
	}
	select {
	case <-ctx.Done():
		if closeErr := f.Close(); closeErr != nil {
			return nil, fmt.Errorf("open stored file cancelled: %w; close: %v", ctx.Err(), closeErr)
		}
		return nil, ctx.Err()
	default:
		return f, nil
	}
}

func removePath(path string) error {
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

func (s *Service) Remove(key string) error {
	path, err := s.safePath(key)
	if err != nil {
		return err
	}
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// Verify checks the physical file against the metadata produced by SaveBytesAtKey.
// It is used after database reconciliation so a visible artifact cannot be
// accepted merely because its database rows exist.
func (s *Service) Verify(ctx context.Context, file File) error {
	f, err := s.OpenVerified(ctx, file)
	if err != nil {
		return err
	}
	return f.Close()
}

func (s *Service) safePath(key string) (string, error) {
	if key == "" || filepath.IsAbs(key) {
		return "", errors.New("invalid storage key")
	}
	clean := filepath.Clean(filepath.FromSlash(key))
	if clean == "." || strings.HasPrefix(clean, ".."+string(os.PathSeparator)) || clean == ".." {
		return "", errors.New("invalid storage key")
	}
	path := filepath.Join(s.root, clean)
	rootAbs, err := filepath.Abs(s.root)
	if err != nil {
		return "", fmt.Errorf("resolve storage root: %w", err)
	}
	pathAbs, err := filepath.Abs(path)
	if err != nil {
		return "", fmt.Errorf("resolve storage path: %w", err)
	}
	if pathAbs != rootAbs && !strings.HasPrefix(pathAbs, rootAbs+string(os.PathSeparator)) {
		return "", errors.New("storage key escapes root")
	}
	probe := pathAbs
	missing := make([]string, 0, 4)
	for {
		resolved, err := filepath.EvalSymlinks(probe)
		if err == nil {
			resolvedAbs, absErr := filepath.Abs(resolved)
			if absErr != nil {
				return "", fmt.Errorf("resolve storage symlink: %w", absErr)
			}
			if resolvedAbs != rootAbs && !strings.HasPrefix(resolvedAbs, rootAbs+string(os.PathSeparator)) {
				return "", errors.New("storage path escapes root")
			}
			for i := len(missing) - 1; i >= 0; i-- {
				resolvedAbs = filepath.Join(resolvedAbs, missing[i])
			}
			if resolvedAbs != rootAbs && !strings.HasPrefix(resolvedAbs, rootAbs+string(os.PathSeparator)) {
				return "", errors.New("storage path escapes root")
			}
			return resolvedAbs, nil
		}
		if !os.IsNotExist(err) {
			return "", err
		}
		parent := filepath.Dir(probe)
		if parent == probe {
			return "", errors.New("storage path cannot be resolved")
		}
		missing = append(missing, filepath.Base(probe))
		probe = parent
	}
}
