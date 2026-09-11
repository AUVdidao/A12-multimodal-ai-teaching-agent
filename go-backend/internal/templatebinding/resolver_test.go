package templatebinding

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestBuildVerifiesRealPPTXAndProducesStableUpstreamBinding(t *testing.T) {
	root := t.TempDir()
	key := "uploads/template-9.pptx"
	path := filepath.Join(root, filepath.FromSlash(key))
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		t.Fatal(err)
	}
	data := minimalPPTX(t)
	if err := os.WriteFile(path, data, 0o640); err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(data)
	input := Input{MissionID: 7, MissionFileID: 9, FileObjectID: 13, OwnerUserID: 22,
		OriginalName: "template.pptx", MimeType: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
		StorageKey: key, SHA256: hex.EncodeToString(digest[:]), Size: int64(len(data)), ParseStatus: "READY", LastModified: time.Unix(1, 0)}
	binding, err := Build(input, root)
	if err != nil {
		t.Fatal(err)
	}
	if binding["bindingKind"] != "LESSONFORGE_UPSTREAM_TEMPLATE_BINDING" || binding["templateFileSha256"] != input.SHA256 || binding["executionReady"] != false {
		t.Fatalf("unexpected binding: %#v", binding)
	}
	second, err := Build(input, root)
	if err != nil {
		t.Fatal(err)
	}
	if second["profileId"] != binding["profileId"] || second["templateProfileVersion"] != binding["templateProfileVersion"] {
		t.Fatalf("binding is not stable: first=%#v second=%#v", binding, second)
	}
}

func TestBuildRejectsInvalidTemplateInputs(t *testing.T) {
	input := Input{MissionID: 7, MissionFileID: 9, FileObjectID: 13, OwnerUserID: 22,
		OriginalName: "template.pptx", MimeType: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
		StorageKey: "uploads/template.pptx", SHA256: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Size: 1, ParseStatus: "READY"}
	if _, err := Build(input, ""); err != nil {
		t.Fatal("metadata-only binding should be valid without a configured storage root: ", err)
	}
	input.OriginalName = "template.pdf"
	if _, err := Build(input, ""); err == nil {
		t.Fatal("non-PPTX unexpectedly accepted")
	}
	input.OriginalName = "template.pptx"
	input.ParseStatus = "PROCESSING"
	if _, err := Build(input, ""); err == nil {
		t.Fatal("non-ready template unexpectedly accepted")
	}
}

func minimalPPTX(t *testing.T) []byte {
	t.Helper()
	var out bytes.Buffer
	archive := zip.NewWriter(&out)
	for _, name := range []string{"[Content_Types].xml", "ppt/presentation.xml"} {
		entry, err := archive.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		if _, err := entry.Write([]byte("<x/>")); err != nil {
			t.Fatal(err)
		}
	}
	if err := archive.Close(); err != nil {
		t.Fatal(err)
	}
	return out.Bytes()
}
