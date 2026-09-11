package model

const (
	CapabilityTools     = "supportsTools"
	CapabilityJSONMode  = "supportsJSONMode"
	CapabilityVision    = "supportsVision"
	CapabilityStreaming = "supportsStreaming"

	CapabilityDeclared    = "DECLARED"
	CapabilityVerified    = "VERIFIED"
	CapabilityUnsupported = "UNSUPPORTED"
)

// ModelCapabilities describes the features that the selected connection is
// allowed to use. It is stored with the connection and frozen into each
// AgentRun so a later connection edit cannot change an already-created run.
type ModelCapabilities struct {
	SupportsTools     bool `json:"supportsTools"`
	SupportsJSONMode  bool `json:"supportsJSONMode"`
	SupportsVision    bool `json:"supportsVision"`
	SupportsStreaming bool `json:"supportsStreaming"`
}

// CapabilityVerification records whether a capability is only teacher-declared
// or was observed by the connection verification handshake.
type CapabilityVerification struct {
	SupportsTools     string `json:"supportsTools"`
	SupportsJSONMode  string `json:"supportsJSONMode"`
	SupportsVision    string `json:"supportsVision"`
	SupportsStreaming string `json:"supportsStreaming"`
}

func DefaultModelCapabilities() ModelCapabilities {
	return ModelCapabilities{
		SupportsTools:     true,
		SupportsJSONMode:  true,
		SupportsVision:    false,
		SupportsStreaming: false,
	}
}

func DefaultCapabilityVerification() CapabilityVerification {
	return CapabilityVerification{
		SupportsTools:     CapabilityDeclared,
		SupportsJSONMode:  CapabilityDeclared,
		SupportsVision:    CapabilityDeclared,
		SupportsStreaming: CapabilityDeclared,
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
	if value.SupportsTools == "" {
		value.SupportsTools = defaults.SupportsTools
	}
	if value.SupportsJSONMode == "" {
		value.SupportsJSONMode = defaults.SupportsJSONMode
	}
	if value.SupportsVision == "" {
		value.SupportsVision = defaults.SupportsVision
	}
	if value.SupportsStreaming == "" {
		value.SupportsStreaming = defaults.SupportsStreaming
	}
	return value
}
