package parser

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"time"

	"lessonforge.local/backend/internal/model"
)

var ErrNotConfigured = errors.New("PARSER_NOT_CONFIGURED")

const defaultMaxResponseBytes int64 = 16 * 1024 * 1024

type Adapter interface {
	Configured() bool
	Parse(context.Context, model.MissionFile, io.Reader) (model.ParseResult, error)
}

type Client struct {
	endpoint         string
	client           *http.Client
	maxResponseBytes int64
}

func NewClient(endpoint string, timeout time.Duration) *Client {
	return NewClientWithMaxResponseBytes(endpoint, timeout, defaultMaxResponseBytes)
}

func NewClientWithMaxResponseBytes(endpoint string, timeout time.Duration, maxResponseBytes int64) *Client {
	if maxResponseBytes <= 0 {
		maxResponseBytes = defaultMaxResponseBytes
	}
	return &Client{endpoint: endpoint, client: &http.Client{Timeout: timeout}, maxResponseBytes: maxResponseBytes}
}

func (c *Client) Configured() bool { return c.endpoint != "" }

func (c *Client) Parse(ctx context.Context, file model.MissionFile, content io.Reader) (parsed model.ParseResult, err error) {
	if c.endpoint == "" {
		return model.ParseResult{}, ErrNotConfigured
	}
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	// The existing parser's stable contract accepts both the normalized file
	// type and the file itself as multipart fields. Mission identifiers and the
	// checksum remain server-owned metadata and are not part of that parser
	// contract.
	endpoint, err := url.Parse(c.endpoint)
	if err != nil || endpoint.Scheme == "" || endpoint.Host == "" {
		return model.ParseResult{}, errors.New("PARSER_ENDPOINT_INVALID")
	}
	if err := writer.WriteField("fileType", parserFileType(file)); err != nil {
		return model.ParseResult{}, errors.New("PARSER_REQUEST_INVALID")
	}
	part, err := writer.CreateFormFile("file", file.FileObject.OriginalName)
	if err != nil {
		return model.ParseResult{}, errors.New("PARSER_REQUEST_INVALID")
	}
	if _, err := io.Copy(part, content); err != nil {
		return model.ParseResult{}, errors.New("PARSER_REQUEST_BODY_FAILED")
	}
	if err := writer.Close(); err != nil {
		return model.ParseResult{}, errors.New("PARSER_REQUEST_INVALID")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint.String(), &body)
	if err != nil {
		return model.ParseResult{}, errors.New("PARSER_ENDPOINT_INVALID")
	}
	req.Header.Set("Content-Type", writer.FormDataContentType())
	resp, err := c.client.Do(req)
	if err != nil {
		if errors.Is(ctx.Err(), context.DeadlineExceeded) || errors.Is(err, context.DeadlineExceeded) {
			return model.ParseResult{}, errors.New("PARSER_TIMEOUT")
		}
		return model.ParseResult{}, errors.New("PARSER_NETWORK_ERROR")
	}
	defer func() {
		if closeErr := resp.Body.Close(); closeErr != nil {
			if err == nil {
				err = errors.New("PARSER_RESPONSE_CLOSE_FAILED")
			}
		}
	}()
	data, err := io.ReadAll(io.LimitReader(resp.Body, c.maxResponseBytes+1))
	if err != nil || int64(len(data)) > c.maxResponseBytes {
		return model.ParseResult{}, errors.New("PARSER_RESPONSE_INVALID")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return model.ParseResult{}, fmt.Errorf("PARSER_HTTP_%d", resp.StatusCode)
	}
	var result struct {
		Summary        string   `json:"summary"`
		Keywords       []string `json:"keywords"`
		TeachingStages []string `json:"teachingStages"`
		AnalysisText   string   `json:"analysisText"`
		ExtractedText  string   `json:"extractedText"`
		PageCount      *int     `json:"pageCount"`
		Sections       any      `json:"sections"`
	}
	if err := json.Unmarshal(data, &result); err != nil || strings.TrimSpace(result.Summary) == "" || result.Keywords == nil || result.TeachingStages == nil {
		return model.ParseResult{}, errors.New("PARSER_RESPONSE_INVALID")
	}
	return model.ParseResult{
		Summary:        result.Summary,
		Keywords:       result.Keywords,
		TeachingStages: result.TeachingStages,
		AnalysisText:   result.AnalysisText,
		ExtractedText:  result.ExtractedText,
		PageCount:      result.PageCount,
		Sections:       result.Sections,
	}, nil
}

func parserFileType(file model.MissionFile) string {
	extension := strings.ToUpper(strings.TrimPrefix(filepath.Ext(file.FileObject.OriginalName), "."))
	switch extension {
	case "TXT", "MD", "PDF", "DOCX", "PPTX", "PNG", "JPG", "JPEG", "MP4", "PPT", "XLSX":
		return extension
	case "DOC":
		return "WORD"
	}
	mimeType := strings.ToLower(strings.TrimSpace(file.FileObject.MimeType))
	switch mimeType {
	case "text/plain":
		return "TXT"
	case "text/markdown":
		return "MD"
	case "application/msword":
		return "WORD"
	case "application/pdf":
		return "PDF"
	case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
		return "DOCX"
	case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
		return "PPTX"
	case "image/png":
		return "PNG"
	case "image/jpeg":
		return "JPG"
	case "video/mp4":
		return "MP4"
	default:
		return "OTHER"
	}
}
