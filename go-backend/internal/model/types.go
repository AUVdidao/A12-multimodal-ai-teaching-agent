package model

import "time"

type Role string

const (
	RoleTeacher    Role = "TEACHER"
	RoleResearcher Role = "RESEARCHER"
)

type User struct {
	ID    int64  `json:"id"`
	Name  string `json:"name"`
	Email string `json:"email"`
	Role  Role   `json:"role"`
}

type ModelConnection struct {
	ID                     int64                  `json:"id"`
	OwnerUserID            int64                  `json:"-"`
	Name                   string                 `json:"name"`
	Provider               string                 `json:"provider"`
	Protocol               string                 `json:"protocol"`
	BaseURL                string                 `json:"baseUrl"`
	ModelID                string                 `json:"modelId"`
	Capabilities           ModelCapabilities      `json:"capabilities"`
	CapabilityVerification CapabilityVerification `json:"capabilityVerification"`
	// CapabilitiesSet is an internal distinction between an omitted API field
	// and an explicitly supplied all-false capability set.
	CapabilitiesSet    bool       `json:"-"`
	KeyHint            string     `json:"keyHint"`
	Enabled            bool       `json:"enabled"`
	VerificationStatus string     `json:"verificationStatus"`
	LastVerifiedAt     *time.Time `json:"lastVerifiedAt,omitempty"`
	LastUsedAt         *time.Time `json:"lastUsedAt,omitempty"`
	CreatedAt          time.Time  `json:"createdAt"`
	UpdatedAt          time.Time  `json:"updatedAt"`
}

type Mission struct {
	ID                        int64      `json:"id"`
	OwnerTeacherID            int64      `json:"ownerTeacherId"`
	Source                    string     `json:"source"`
	Title                     string     `json:"title"`
	Description               string     `json:"description"`
	Deadline                  *time.Time `json:"deadline,omitempty"`
	Status                    string     `json:"status"`
	Progress                  int        `json:"progress"`
	ProgressLabel             string     `json:"progressLabel"`
	ProgressStage             string     `json:"progressStage"`
	ActiveAgentRunID          *string    `json:"activeAgentRunId,omitempty"`
	SelectedModelConnectionID *int64     `json:"selectedModelConnectionId,omitempty"`
	CreatedAt                 time.Time  `json:"createdAt"`
	UpdatedAt                 time.Time  `json:"updatedAt"`
}

type Message struct {
	ID                int64     `json:"id"`
	MissionID         int64     `json:"missionId"`
	OwnerUserID       int64     `json:"ownerUserId"`
	AgentRunID        *string   `json:"agentRunId,omitempty"`
	OutputStage       string    `json:"outputStage,omitempty"`
	ReferenceType     string    `json:"referenceType,omitempty"`
	ReferenceID       string    `json:"referenceId,omitempty"`
	Role              string    `json:"role"`
	Content           string    `json:"content"`
	MessageType       string    `json:"messageType"`
	StructuredPayload any       `json:"structuredPayload,omitempty"`
	CreatedAt         time.Time `json:"createdAt"`
}

type ConversationSummary struct {
	MissionID                  int64     `json:"missionId"`
	OwnerUserID                int64     `json:"-"`
	SummaryVersion             int       `json:"summaryVersion"`
	SummarizedThroughMessageID *int64    `json:"summarizedThroughMessageId,omitempty"`
	Summary                    string    `json:"summary"`
	SourceAgentRunID           *string   `json:"sourceAgentRunId,omitempty"`
	PromptVersion              string    `json:"promptVersion"`
	CreatedAt                  time.Time `json:"createdAt"`
	UpdatedAt                  time.Time `json:"updatedAt"`
}

type FileObject struct {
	ID           int64     `json:"id"`
	OriginalName string    `json:"originalName"`
	MimeType     string    `json:"mimeType"`
	Size         int64     `json:"size"`
	SHA256       string    `json:"sha256"`
	StorageKey   string    `json:"-"`
	CreatedAt    time.Time `json:"createdAt"`
}
type MissionFile struct {
	ID          int64      `json:"id"`
	MissionID   int64      `json:"missionId"`
	OwnerUserID int64      `json:"-"`
	FileObject  FileObject `json:"file"`
	Role        string     `json:"role"`
	Provenance  string     `json:"provenance"`
	ParseStatus string     `json:"parseStatus"`
	CreatedAt   time.Time  `json:"createdAt"`
}
type RAGMaterialBinding struct {
	MissionID     int64  `json:"missionId"`
	MissionFileID int64  `json:"missionFileId"`
	RAGProjectID  int64  `json:"-"`
	RAGMaterialID int64  `json:"-"`
	SourceSHA256  string `json:"-"`
}
type ParseResult struct {
	Summary        string   `json:"summary"`
	Keywords       []string `json:"keywords"`
	TeachingStages []string `json:"teachingStages"`
	AnalysisText   string   `json:"analysisText"`
	ExtractedText  string   `json:"extractedText"`
	PageCount      *int     `json:"pageCount,omitempty"`
	Sections       any      `json:"sections,omitempty"`
}
type AgentRun struct {
	ID                string `json:"id"`
	MissionID         int64  `json:"missionId"`
	OwnerUserID       int64  `json:"ownerUserId"`
	Status            string `json:"status"`
	ModelConnectionID *int64 `json:"modelConnectionId,omitempty"`
	// Internal execution credential snapshot; never expose it through REST/SSE.
	ModelIdentitySnapshot any        `json:"-"`
	ErrorCode             string     `json:"errorCode,omitempty"`
	ErrorMessage          string     `json:"errorMessage,omitempty"`
	StartedAt             *time.Time `json:"startedAt,omitempty"`
	FinishedAt            *time.Time `json:"finishedAt,omitempty"`
	CreatedAt             time.Time  `json:"createdAt"`
	LeaseToken            string     `json:"-"`
}
type Question struct {
	ID            string          `json:"id"`
	MissionID     int64           `json:"missionId"`
	OwnerUserID   int64           `json:"ownerUserId"`
	AgentRunID    string          `json:"agentRunId"`
	OutputStage   string          `json:"outputStage"`
	ReferenceType string          `json:"referenceType"`
	ReferenceID   string          `json:"referenceId"`
	Text          string          `json:"text"`
	Type          string          `json:"type"`
	Options       any             `json:"options,omitempty"`
	LatestAnswer  *QuestionAnswer `json:"latestAnswer"`
	CreatedAt     time.Time       `json:"createdAt"`
}
type QuestionAnswer struct {
	ID             string    `json:"id"`
	SelectedValues []string  `json:"selectedValues"`
	TextAnswer     string    `json:"textAnswer"`
	AnsweredAt     time.Time `json:"answeredAt"`
}
type PlanningDraft struct {
	ID                  string    `json:"id"`
	MissionID           int64     `json:"missionId"`
	OwnerUserID         int64     `json:"ownerUserId"`
	Version             int       `json:"version"`
	Markdown            string    `json:"markdown"`
	StructuredPlan      any       `json:"structuredPlan"`
	CreatedByAgentRunID string    `json:"createdByAgentRunId"`
	OutputStage         string    `json:"outputStage"`
	ReferenceType       string    `json:"referenceType"`
	ReferenceID         string    `json:"referenceId"`
	CreatedAt           time.Time `json:"createdAt"`
}
type LockedSpecification struct {
	ID              string    `json:"id"`
	MissionID       int64     `json:"missionId"`
	SourceDraftID   string    `json:"sourceDraftId"`
	Version         int       `json:"version"`
	Specification   any       `json:"specification"`
	TemplateBinding any       `json:"templateBinding"`
	ContentHash     string    `json:"contentHash"`
	CreatedAt       time.Time `json:"createdAt"`
}
type GenerationJob struct {
	ID                   string     `json:"id"`
	MissionID            int64      `json:"missionId"`
	SpecificationID      string     `json:"specificationId"`
	SpecificationVersion int        `json:"specificationVersion"`
	Status               string     `json:"status"`
	CurrentSlide         int        `json:"currentSlide"`
	TotalSlides          int        `json:"totalSlides"`
	ArtifactID           *string    `json:"artifactId,omitempty"`
	Feedback             any        `json:"generationFeedback,omitempty"`
	CreatedAt            time.Time  `json:"createdAt"`
	StartedAt            *time.Time `json:"startedAt,omitempty"`
	FinishedAt           *time.Time `json:"finishedAt,omitempty"`
	LeaseToken           string     `json:"-"`
}
type Artifact struct {
	ID              string     `json:"id"`
	MissionID       int64      `json:"missionId"`
	GenerationJobID string     `json:"generationJobId"`
	File            FileObject `json:"file"`
	Version         int        `json:"version"`
	ContentType     string     `json:"contentType"`
	SHA256          string     `json:"sha256"`
	Size            int64      `json:"size"`
	Status          string     `json:"status,omitempty"`
	CreatedAt       time.Time  `json:"createdAt"`
}
type ActivityEvent struct {
	ID            int64     `json:"id"`
	MissionID     int64     `json:"missionId"`
	EventType     string    `json:"eventType"`
	Summary       string    `json:"summary"`
	ReferenceType *string   `json:"referenceType,omitempty"`
	ReferenceID   *string   `json:"referenceId,omitempty"`
	CreatedAt     time.Time `json:"createdAt"`
}

type MissionFeedbackItem struct {
	SlideNumber int    `json:"slideNumber,omitempty"`
	SlideTitle  string `json:"slideTitle"`
	Severity    string `json:"severity"`
	Comment     string `json:"comment"`
}

type MissionFeedback struct {
	ID                string                `json:"id"`
	MissionID         int64                 `json:"missionId"`
	ReviewerUserID    int64                 `json:"-"`
	ReviewerName      string                `json:"reviewerName"`
	SubmissionVersion int                   `json:"submissionVersion"`
	Rating            int                   `json:"rating"`
	Summary           string                `json:"summary"`
	Items             []MissionFeedbackItem `json:"items"`
	CreatedAt         time.Time             `json:"createdAt"`
}
