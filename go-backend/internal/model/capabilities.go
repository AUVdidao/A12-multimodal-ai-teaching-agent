package model

const (
	CapabilityTools     = "supportsTools"
	CapabilityJSONMode  = "supportsJSONMode"
	CapabilityVision    = "supportsVision"
	CapabilityChat      = "supportsChat"
	CapabilityEmbedding = "supportsEmbeddings"
	CapabilityStreaming = "supportsStreaming"

	CapabilityDeclared    = "DECLARED"
	CapabilityVerified    = "VERIFIED"
	CapabilityUnsupported = "UNSUPPORTED"
)

// ModelCapabilities describes the features that the selected connection is
// allowed to use. It is stored with the connection and frozen into each
// AgentRun so a later connection edit cannot change an already-created run.
type ModelCapabilities struct {
	SupportsChat       bool `json:"supportsChat"`
	SupportsTools      bool `json:"supportsTools"`
	SupportsJSONMode   bool `json:"supportsJSONMode"`
	SupportsVision     bool `json:"supportsVision"`
	SupportsEmbeddings bool `json:"supportsEmbeddings"`
	EmbeddingDimension int  `json:"embeddingDimension,omitempty"`
	SupportsStreaming  bool `json:"supportsStreaming"`
}

// CapabilityVerification records whether a capability is only teacher-declared
// or was observed by the connection verification handshake.
type CapabilityVerification struct {
	SupportsChat       string `json:"supportsChat"`
	SupportsTools      string `json:"supportsTools"`
	SupportsJSONMode   string `json:"supportsJSONMode"`
	SupportsVision     string `json:"supportsVision"`
	SupportsEmbeddings string `json:"supportsEmbeddings"`
	SupportsStreaming  string `json:"supportsStreaming"`
}

func DefaultModelCapabilities() ModelCapabilities {
	return ModelCapabilities{
		SupportsChat:       true,
		SupportsTools:      true,
		SupportsJSONMode:   true,
		SupportsVision:     false,
		SupportsEmbeddings: false,
		SupportsStreaming:  false,
	}
}

func DefaultCapabilityVerification() CapabilityVerification {
	return CapabilityVerification{
		SupportsChat:       CapabilityDeclared,
		SupportsTools:      CapabilityDeclared,
		SupportsJSONMode:   CapabilityDeclared,
		SupportsVision:     CapabilityDeclared,
		SupportsEmbeddings: CapabilityDeclared,
		SupportsStreaming:  CapabilityDeclared,
	}
}

// NormalizeCapabilities keeps connections created by older callers compatible
// with the pre-capability runtime, while the HTTP API can explicitly persist
// an all-false capability set via CapabilitiesSet on ModelConnection.
func NormalizeCapabilities(capabilities ModelCapabilities, capabilitiesSet bool) ModelCapabilities {
	if !capabilitiesSet && capabilities == (ModelCapabilities{}) {
		return DefaultModelCapabilities()
	}
	return capabilities
}

func NormalizeCapabilityVerification(value CapabilityVerification) CapabilityVerification {
	defaults := DefaultCapabilityVerification()
	if value.SupportsChat == "" {
		value.SupportsChat = defaults.SupportsChat
	}
	if value.SupportsTools == "" {
		value.SupportsTools = defaults.SupportsTools
	}
	if value.SupportsJSONMode == "" {
		value.SupportsJSONMode = defaults.SupportsJSONMode
	}
	if value.SupportsVision == "" {
		value.SupportsVision = defaults.SupportsVision
	}
	if value.SupportsEmbeddings == "" {
		value.SupportsEmbeddings = defaults.SupportsEmbeddings
	}
	if value.SupportsStreaming == "" {
		value.SupportsStreaming = defaults.SupportsStreaming
	}
	return value
}
