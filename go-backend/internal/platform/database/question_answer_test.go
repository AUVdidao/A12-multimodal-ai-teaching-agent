package database

import (
	"errors"
	"testing"

	"lessonforge.local/backend/internal/model"
)

func TestValidateQuestionAnswerUsesPersistedQuestionContract(t *testing.T) {
	tests := []struct {
		name    string
		qtype   string
		options string
		text    string
		chosen  []string
		valid   bool
	}{
		{name: "text answer", qtype: "TEXT", options: `[]`, text: "大学", valid: true},
		{name: "text cannot receive choice", qtype: "TEXT", options: `[]`, chosen: []string{"大学"}},
		{name: "text rejects persisted options", qtype: "TEXT", options: `["选项"]`, text: "大学"},
		{name: "single choice", qtype: "SINGLE_CHOICE", options: `["初中","高中"]`, chosen: []string{"高中"}, valid: true},
		{name: "single rejects unknown option", qtype: "SINGLE_CHOICE", options: `["初中","高中"]`, chosen: []string{"大学"}},
		{name: "single rejects two options", qtype: "SINGLE_CHOICE", options: `["初中","高中"]`, chosen: []string{"初中", "高中"}},
		{name: "multi choice", qtype: "MULTI_CHOICE", options: `["案例","练习","讨论"]`, chosen: []string{"案例", "练习"}, valid: true},
		{name: "multi rejects duplicate", qtype: "MULTI_CHOICE", options: `["案例","练习"]`, chosen: []string{"案例", "案例"}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := validateQuestionAnswer(test.qtype, []byte(test.options), test.text, test.chosen)
			if test.valid && err != nil {
				t.Fatalf("valid answer rejected: %v", err)
			}
			if !test.valid && !errors.Is(err, model.ErrQuestionAnswerInvalid) {
				t.Fatalf("error = %v, want ErrQuestionAnswerInvalid", err)
			}
		})
	}
}
