package rag

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net"
	"net/http"
	"net/textproto"
	"net/url"
	pathpkg "path"
	"strconv"
	"strings"
	"time"

	"lessonforge.local/backend/internal/model"
)

var ErrNotConfigured = errors.New("RAG adapter is not configured")
var ErrLegacyWorkflowPath = errors.New("RAG_LEGACY_WORKFLOW_PATH_BLOCKED")
var ErrReadMaterialDisabled = errors.New("RAG_READ_MATERIAL_DISABLED")
var ErrTimeout = errors.New("RAG_TIMEOUT")
var ErrRequirementSummaryRequired = errors.New("RAG_REQUIREMENT_SUMMARY_REQUIRED")

const legacyWorkflowPath = "/api/ai-workflow/knowledge-retrieval"

type actorUserIDContextKey struct{}

func withActorUserID(ctx context.Context, userID int64) context.Context {
	return context.WithValue(ctx, actorUserIDContextKey{}, userID)
}

func actorUserID(ctx context.Context) (int64, bool) {
	if ctx == nil {
		return 0, false
	}
	userID, ok := ctx.Value(actorUserIDContextKey{}).(int64)
	return userID, ok && userID > 0
}

type Client struct {
	baseURL, searchPath, readPath string
	httpClient                    *http.Client
	java                          bool
	maxResponseBytes              int64
	serviceBearerToken            string
	resources                     *MissionResourceBinder
}

// NewClient preserves the old explicit-path adapter for focused compatibility
// tests. Production uses NewJavaClient so no legacy workflow route is selected.
func NewClient(baseURL, searchPath, readPath string) *Client {
	return &Client{baseURL: strings.TrimRight(baseURL, "/"), searchPath: searchPath, readPath: readPath, httpClient: &http.Client{Timeout: 90 * time.Second}, maxResponseBytes: 4 * 1024 * 1024}
}

func NewJavaClient(baseURL string, timeout time.Duration, maxResponseBytes int64) *Client {
	if timeout <= 0 {
		timeout = 90 * time.Second
	}
	if maxResponseBytes <= 0 {
		maxResponseBytes = 4 * 1024 * 1024
	}
	return &Client{baseURL: strings.TrimRight(strings.TrimSpace(baseURL), "/"), httpClient: &http.Client{Timeout: timeout}, java: true, maxResponseBytes: maxResponseBytes}
}

// SetServiceBearerToken configures the explicit service credential used for
// protected Java RAG deployments. It is intentionally separate from teacher
// Model Connection credentials and is never exposed through the REST API.
func (c *Client) SetServiceBearerToken(token string) {
	if c == nil {
		return
	}
	c.serviceBearerToken = strings.TrimSpace(token)
}

func (c *Client) ConfigureResources(store ResourceStore, files ResourceStorage) {
	c.resources = &MissionResourceBinder{Client: c, Store: store, Storage: files}
}

type Snippet struct {
	Title   string  `json:"title"`
	Source  string  `json:"source"`
	Content string  `json:"content"`
	Score   float64 `json:"score"`
}

type JavaProject struct {
	ID           int64  `json:"id"`
	ProjectName  string `json:"projectName"`
	CourseName   string `json:"courseName"`
	ChapterTitle string `json:"chapterTitle"`
	Description  string `json:"description"`
}

type JavaMaterial struct {
	ID               int64  `json:"id"`
	ProjectID        int64  `json:"projectId"`
	OriginalFilename string `json:"originalFilename"`
	ContentType      string `json:"contentType"`
	FileSize         int64  `json:"fileSize"`
	Description      string `json:"description"`
}

// MaterialIdentity is the cross-service identity proof for every
// LessonForge parser/index/read operation after intake. The Java service
// token authenticates the Go service only; these fields still have to match
// the server-owned Go MissionFile and Java binding.
type MaterialIdentity struct {
	MissionID     int64  `json:"missionId"`
	MissionFileID int64  `json:"missionFileId"`
	OwnerUserID   int64  `json:"ownerUserId"`
	ActorUserID   int64  `json:"actorUserId"`
	SourceSHA256  string `json:"sourceSha256"`
	SourceSize    int64  `json:"sourceSize"`
}

type javaKnowledgeHit struct {
	ChunkID    int64    `json:"chunkId"`
	MaterialID int64    `json:"materialId"`
	Source     string   `json:"sourceFilename"`
	Title      string   `json:"title"`
	Content    string   `json:"content"`
	Score      float64  `json:"score"`
	Keywords   []string `json:"keywords"`
}

type javaSearchResponse struct {
	Hits []javaKnowledgeHit `json:"hits"`
}

type javaParseResult struct {
	ParseStatus              string   `json:"parseStatus"`
	Summary                  string   `json:"summary"`
	Keywords                 []string `json:"keywords"`
	ApplicableTeachingStages []string `json:"applicableTeachingStages"`
	FailureReason            string   `json:"failureReason"`
	ExtractedTextPreview     string   `json:"extractedTextPreview"`
	PageCount                *int     `json:"pageCount"`
	Sections                 any      `json:"sections"`
}

type javaEnvelope struct {
	Code    int             `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data"`
}

func (c *Client) Search(ctx context.Context, missionID int64, query string, fileIDs []int64) ([]Snippet, error) {
	if c.java {
		if c.resources == nil {
			return nil, ErrNotConfigured
		}
		return c.resources.Search(ctx, missionID, query, fileIDs)
	}
	if c.baseURL == "" || strings.TrimSpace(c.searchPath) == "" {
		return nil, ErrNotConfigured
	}
	if isLegacyWorkflowPath(c.searchPath) {
		return nil, ErrLegacyWorkflowPath
	}
	return c.post(ctx, c.searchPath, map[string]any{"missionId": missionID, "query": query, "fileIds": fileIDs})
}

func (c *Client) searchJava(ctx context.Context, ragProjectID, missionID int64, query string, allowedMaterialIDs map[int64]struct{}) ([]Snippet, error) {
	if c.baseURL == "" {
		return nil, ErrNotConfigured
	}
	var result javaSearchResponse
	materialIDs := make([]int64, 0, len(allowedMaterialIDs))
	for materialID := range allowedMaterialIDs {
		materialIDs = append(materialIDs, materialID)
	}
	if err := c.javaJSON(ctx, http.MethodPost, "/api/v1/internal/lessonforge/projects/"+strconv.FormatInt(ragProjectID, 10)+"/knowledge/search", map[string]any{
		"missionId":   missionID,
		"materialIds": materialIDs,
		"query":       query,
		"limit":       10,
	}, &result); err != nil {
		return nil, err
	}
	snippets := make([]Snippet, 0, len(result.Hits))
	for _, hit := range result.Hits {
		if _, ok := allowedMaterialIDs[hit.MaterialID]; !ok {
			continue
		}
		snippets = append(snippets, Snippet{Title: hit.Title, Source: hit.Source, Content: hit.Content, Score: hit.Score})
	}
	return snippets, nil
}

func (c *Client) Read(ctx context.Context, missionID int64, fileID int64, locator string) (string, error) {
	if c.java {
		if c.resources == nil {
			return "", ErrNotConfigured
		}
		return c.resources.Read(ctx, missionID, fileID, locator)
	}
	if c.baseURL == "" || strings.TrimSpace(c.readPath) == "" {
		return "", ErrNotConfigured
	}
	if isLegacyWorkflowPath(c.readPath) {
		return "", ErrLegacyWorkflowPath
	}
	var result struct {
		Content string `json:"content"`
	}
	if err := c.postInto(ctx, c.readPath, map[string]any{"missionId": missionID, "fileId": fileID, "locator": locator}, &result); err != nil {
		return "", err
	}
	return result.Content, nil
}

func (c *Client) readJava(ctx context.Context, projectID, missionID, materialID int64, locator string, identity MaterialIdentity) (string, error) {
	var result struct {
		Content string `json:"content"`
	}
	requestPath := "/api/v1/internal/lessonforge/projects/" + strconv.FormatInt(projectID, 10) + "/knowledge/missions/" + strconv.FormatInt(missionID, 10) + "/materials/" + strconv.FormatInt(materialID, 10) + "/read"
	values := url.Values{}
	values.Set("missionId", strconv.FormatInt(identity.MissionID, 10))
	values.Set("missionFileId", strconv.FormatInt(identity.MissionFileID, 10))
	values.Set("ownerUserId", strconv.FormatInt(identity.OwnerUserID, 10))
	values.Set("actorUserId", strconv.FormatInt(identity.ActorUserID, 10))
	values.Set("sourceSha256", strings.TrimSpace(identity.SourceSHA256))
	values.Set("sourceSize", strconv.FormatInt(identity.SourceSize, 10))
	if strings.TrimSpace(locator) != "" {
		values.Set("locator", strings.TrimSpace(locator))
	}
	requestPath += "?" + values.Encode()
	if err := c.javaJSON(ctx, http.MethodGet, requestPath, nil, &result); err != nil {
		return "", err
	}
	if strings.TrimSpace(result.Content) == "" {
		return "", errors.New("RAG_MATERIAL_CONTENT_EMPTY")
	}
	return result.Content, nil
}

func (c *Client) ListProjects(ctx context.Context) ([]JavaProject, error) {
	var result []JavaProject
	if err := c.javaJSON(ctx, http.MethodGet, "/api/projects", nil, &result); err != nil {
		return nil, err
	}
	return result, nil
}

func (c *Client) CreateProject(ctx context.Context, projectName, courseName, chapterTitle, description string) (JavaProject, error) {
	var result JavaProject
	err := c.javaJSON(ctx, http.MethodPost, "/api/projects", map[string]any{
		"projectName":    projectName,
		"courseName":     courseName,
		"chapterTitle":   chapterTitle,
		"description":    description,
		"targetStudents": "teacher-owned Mission",
		"lessonDuration": 45,
	}, &result)
	return result, err
}

func (c *Client) ListMaterials(ctx context.Context, ragProjectID int64) ([]JavaMaterial, error) {
	var result []JavaMaterial
	err := c.javaJSON(ctx, http.MethodGet, "/api/projects/"+strconv.FormatInt(ragProjectID, 10)+"/materials", nil, &result)
	return result, err
}

// UploadMaterial streams the file to Java instead of buffering a teacher file
// in memory. The caller owns and closes the reader.
func (c *Client) UploadMaterial(ctx context.Context, ragProjectID int64, originalName, mimeType, description string, size int64, content io.Reader) (result JavaMaterial, err error) {
	if c.baseURL == "" {
		return result, ErrNotConfigured
	}
	if ragProjectID <= 0 || originalName == "" || content == nil || size <= 0 {
		return result, errors.New("RAG_MATERIAL_REQUEST_INVALID")
	}
	reader, writer := io.Pipe()
	form := multipart.NewWriter(writer)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.javaURL("/api/projects/"+strconv.FormatInt(ragProjectID, 10)+"/materials"), reader)
	if err != nil {
		_ = reader.CloseWithError(errors.New("RAG_MATERIAL_ENDPOINT_INVALID"))
		return result, errors.New("RAG_MATERIAL_ENDPOINT_INVALID")
	}
	if mimeType != "" {
		// Multipart's per-part MIME is set below in a custom writer only when
		// providers require it; Java uses the filename/content bytes as source of
		// truth. The request itself must remain multipart/form-data.
		_ = mimeType
	}
	req.Header.Set("Content-Type", form.FormDataContentType())
	c.applyServiceAuthorization(req)
	applyActorAuthorization(ctx, req)
	go func() {
		part, writeErr := form.CreateFormFile("file", originalName)
		var written int64
		if writeErr == nil {
			written, writeErr = io.Copy(part, io.LimitReader(content, size+1))
			if writeErr == nil && written != size {
				writeErr = errors.New("RAG_MATERIAL_SIZE_MISMATCH")
			}
		}
		if writeErr == nil && description != "" {
			writeErr = form.WriteField("description", description)
		}
		if closeErr := form.Close(); writeErr == nil {
			writeErr = closeErr
		}
		if writeErr != nil {
			_ = writer.CloseWithError(writeErr)
		} else {
			_ = writer.Close()
		}
	}()
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return result, classifyRAGNetworkError(err)
	}
	data, readErr := c.readResponse(resp)
	if readErr != nil {
		return result, readErr
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return result, classifyJavaHTTPError(resp.StatusCode, data)
	}
	if err := decodeJavaEnvelope(data, &result); err != nil {
		return result, err
	}
	if result.ID <= 0 {
		return result, errors.New("RAG_MATERIAL_RESPONSE_INVALID")
	}
	return result, nil
}

// UploadLessonForgeMaterial uses the independent LessonForge intake contract.
// It must not be replaced with UploadMaterial: the latter is intentionally the
// legacy Java path and keeps its RequirementSummary gate.
func (c *Client) UploadLessonForgeMaterial(ctx context.Context, ragProjectID, missionID, missionFileID, ownerUserID, actorUserID int64, originalName, mimeType, description string, size int64, sourceSHA256 string, content io.Reader) (result JavaMaterial, err error) {
	if c.baseURL == "" {
		return result, ErrNotConfigured
	}
	if ragProjectID <= 0 || missionID <= 0 || missionFileID <= 0 || ownerUserID <= 0 || actorUserID <= 0 || originalName == "" || content == nil || size <= 0 || strings.TrimSpace(sourceSHA256) == "" {
		return result, errors.New("RAG_LESSONFORGE_MATERIAL_REQUEST_INVALID")
	}
	reader, writer := io.Pipe()
	form := multipart.NewWriter(writer)
	requestPath := "/api/v1/internal/lessonforge/projects/" + strconv.FormatInt(ragProjectID, 10) + "/materials"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.javaURL(requestPath), reader)
	if err != nil {
		_ = reader.CloseWithError(errors.New("RAG_LESSONFORGE_MATERIAL_ENDPOINT_INVALID"))
		return result, errors.New("RAG_LESSONFORGE_MATERIAL_ENDPOINT_INVALID")
	}
	req.Header.Set("Content-Type", form.FormDataContentType())
	c.applyServiceAuthorization(req)
	applyActorAuthorization(ctx, req)
	go func() {
		// multipart.CreateFormFile always declares application/octet-stream.
		// The LessonForge Java intake contract validates MIME against the file
		// extension, so preserve the source object's MIME on the file part.
		partHeader := make(textproto.MIMEHeader)
		partHeader.Set("Content-Disposition", mimeContentDisposition(originalName))
		partHeader.Set("Content-Type", strings.TrimSpace(mimeType))
		part, writeErr := form.CreatePart(partHeader)
		var written int64
		if writeErr == nil {
			written, writeErr = io.Copy(part, io.LimitReader(content, size+1))
			if writeErr == nil && written != size {
				writeErr = errors.New("RAG_MATERIAL_SIZE_MISMATCH")
			}
		}
		fields := map[string]string{
			"missionId":     strconv.FormatInt(missionID, 10),
			"missionFileId": strconv.FormatInt(missionFileID, 10),
			"ownerUserId":   strconv.FormatInt(ownerUserID, 10),
			"actorUserId":   strconv.FormatInt(actorUserID, 10),
			"sourceSha256":  strings.TrimSpace(sourceSHA256),
			"sourceSize":    strconv.FormatInt(size, 10),
		}
		if writeErr == nil && description != "" {
			fields["description"] = description
		}
		if writeErr == nil {
			for name, value := range fields {
				if writeErr = form.WriteField(name, value); writeErr != nil {
					break
				}
			}
		}
		if closeErr := form.Close(); writeErr == nil {
			writeErr = closeErr
		}
		if writeErr != nil {
			_ = writer.CloseWithError(writeErr)
		} else {
			_ = writer.Close()
		}
	}()
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return result, classifyRAGNetworkError(err)
	}
	data, readErr := c.readResponse(resp)
	if readErr != nil {
		return result, readErr
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return result, classifyJavaHTTPError(resp.StatusCode, data)
	}
	var binding struct {
		MaterialID   int64  `json:"materialId"`
		ProjectID    int64  `json:"projectId"`
		OriginalName string `json:"originalFilename"`
		FileSize     int64  `json:"sourceSize"`
		SourceSHA256 string `json:"sourceSha256"`
	}
	if err := decodeJavaEnvelope(data, &binding); err != nil {
		return result, err
	}
	result = JavaMaterial{ID: binding.MaterialID, ProjectID: binding.ProjectID, OriginalFilename: binding.OriginalName, FileSize: binding.FileSize, Description: description}
	if result.ID <= 0 || result.ProjectID != ragProjectID || !strings.EqualFold(binding.SourceSHA256, sourceSHA256) || result.FileSize != size {
		return JavaMaterial{}, errors.New("RAG_LESSONFORGE_MATERIAL_RESPONSE_INVALID")
	}
	return result, nil
}

func mimeContentDisposition(filename string) string {
	return `form-data; name="file"; filename="` + strings.ReplaceAll(strings.ReplaceAll(strings.ReplaceAll(filename, `\`, `_`), `"`, `_`), "\r", "") + `"`
}

func (c *Client) ParseMaterial(ctx context.Context, ragProjectID, materialID int64, identity MaterialIdentity) error {
	var result any
	return c.javaJSON(ctx, http.MethodPost, "/api/v1/internal/lessonforge/projects/"+strconv.FormatInt(ragProjectID, 10)+"/materials/"+strconv.FormatInt(materialID, 10)+"/parse", identity, &result)
}

// ParseMaterialResult invokes the independent LessonForge parser contract and
// returns the server-owned parse result. It deliberately does not call the
// legacy MaterialService or RequirementSummary-gated endpoint.
func (c *Client) ParseMaterialResult(ctx context.Context, ragProjectID, materialID int64, identity MaterialIdentity) (model.ParseResult, error) {
	var result javaParseResult
	if err := c.javaJSON(ctx, http.MethodPost, "/api/v1/internal/lessonforge/projects/"+strconv.FormatInt(ragProjectID, 10)+"/materials/"+strconv.FormatInt(materialID, 10)+"/parse", identity, &result); err != nil {
		return model.ParseResult{}, err
	}
	status := strings.ToUpper(strings.TrimSpace(result.ParseStatus))
	if status != "SUCCEEDED" {
		if status == "" {
			status = "UNKNOWN"
		}
		return model.ParseResult{}, fmt.Errorf("RAG_PARSE_STATUS_%s", status)
	}
	if strings.TrimSpace(result.Summary) == "" || result.Keywords == nil || result.ApplicableTeachingStages == nil {
		return model.ParseResult{}, errors.New("RAG_PARSE_RESULT_INVALID")
	}
	return model.ParseResult{
		Summary:        result.Summary,
		Keywords:       result.Keywords,
		TeachingStages: result.ApplicableTeachingStages,
		ExtractedText:  result.ExtractedTextPreview,
		PageCount:      result.PageCount,
		Sections:       result.Sections,
	}, nil
}

func (c *Client) IndexMaterial(ctx context.Context, ragProjectID, materialID int64, identity MaterialIdentity) error {
	var result any
	return c.javaJSON(ctx, http.MethodPost, "/api/v1/internal/lessonforge/projects/"+strconv.FormatInt(ragProjectID, 10)+"/materials/"+strconv.FormatInt(materialID, 10)+"/index", identity, &result)
}

func (c *Client) javaJSON(ctx context.Context, method, requestPath string, body any, out any) error {
	if c.baseURL == "" {
		return ErrNotConfigured
	}
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return errors.New("RAG_REQUEST_INVALID")
		}
		reader = bytes.NewReader(encoded)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.javaURL(requestPath), reader)
	if err != nil {
		return errors.New("RAG_ENDPOINT_INVALID")
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	c.applyServiceAuthorization(req)
	applyActorAuthorization(ctx, req)
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return classifyRAGNetworkError(err)
	}
	data, err := c.readResponse(resp)
	if err != nil {
		return err
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return classifyJavaHTTPError(resp.StatusCode, data)
	}
	return decodeJavaEnvelope(data, out)
}

func (c *Client) applyServiceAuthorization(req *http.Request) {
	if c == nil || req == nil || c.serviceBearerToken == "" {
		return
	}
	req.Header.Set("Authorization", "Bearer "+c.serviceBearerToken)
}

func applyActorAuthorization(ctx context.Context, req *http.Request) {
	if req == nil {
		return
	}
	if userID, ok := actorUserID(ctx); ok {
		req.Header.Set("X-LessonForge-Actor-User-Id", strconv.FormatInt(userID, 10))
	}
}

func (c *Client) readResponse(resp *http.Response) ([]byte, error) {
	defer resp.Body.Close()
	limit := c.maxResponseBytes
	if limit <= 0 {
		limit = 4 * 1024 * 1024
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, errors.New("RAG_RESPONSE_READ_FAILED")
	}
	if int64(len(data)) > limit {
		return nil, errors.New("RAG_RESPONSE_TOO_LARGE")
	}
	return data, nil
}

func classifyRAGNetworkError(err error) error {
	if errors.Is(err, context.DeadlineExceeded) {
		return ErrTimeout
	}
	var networkErr net.Error
	if errors.As(err, &networkErr) && networkErr.Timeout() {
		return ErrTimeout
	}
	return errors.New("RAG_NETWORK_ERROR")
}

func classifyJavaHTTPError(status int, data []byte) error {
	if status != http.StatusConflict {
		return fmt.Errorf("RAG_HTTP_%d", status)
	}
	var envelope javaEnvelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		return fmt.Errorf("RAG_HTTP_%d", status)
	}
	message := strings.ToLower(strings.TrimSpace(envelope.Message))
	if strings.Contains(message, "confirmed requirement summary is required") {
		return ErrRequirementSummaryRequired
	}
	return fmt.Errorf("RAG_HTTP_%d", status)
}

func decodeJavaEnvelope(data []byte, out any) error {
	var envelope javaEnvelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		return errors.New("RAG_RESPONSE_FORMAT")
	}
	if envelope.Code != 0 {
		if strings.TrimSpace(envelope.Message) == "" {
			return errors.New("RAG_REMOTE_ERROR")
		}
		return fmt.Errorf("RAG_REMOTE_CODE_%d", envelope.Code)
	}
	if out == nil || len(envelope.Data) == 0 || string(envelope.Data) == "null" {
		return nil
	}
	if err := json.Unmarshal(envelope.Data, out); err != nil {
		return errors.New("RAG_RESPONSE_FORMAT")
	}
	return nil
}

func (c *Client) javaURL(requestPath string) string {
	return c.baseURL + "/" + strings.TrimLeft(requestPath, "/")
}

func (c *Client) post(ctx context.Context, path string, body any) ([]Snippet, error) {
	var result struct {
		Snippets []Snippet `json:"snippets"`
	}
	if err := c.postInto(ctx, path, body, &result); err != nil {
		return nil, err
	}
	return result.Snippets, nil
}

func (c *Client) postInto(ctx context.Context, path string, body any, out any) error {
	encoded, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/"+strings.TrimLeft(path, "/"), bytes.NewReader(encoded))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return classifyRAGNetworkError(err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 4*1024*1024+1))
	if err != nil {
		return err
	}
	if len(data) > 4*1024*1024 {
		return errors.New("RAG_RESPONSE_TOO_LARGE")
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("RAG_HTTP_%d", resp.StatusCode)
	}
	if err := json.Unmarshal(data, out); err != nil {
		return errors.New("RAG_RESPONSE_FORMAT")
	}
	return nil
}

func isLegacyWorkflowPath(rawPath string) bool {
	rawPath = strings.TrimSpace(rawPath)
	if !strings.Contains(rawPath, "://") {
		rawPath = "/" + strings.TrimLeft(rawPath, "/")
	}
	parsed, err := url.Parse(rawPath)
	if err != nil {
		return false
	}
	normalizedPath := pathpkg.Clean("/" + strings.Trim(parsed.Path, "/"))
	return strings.EqualFold(normalizedPath, legacyWorkflowPath)
}
