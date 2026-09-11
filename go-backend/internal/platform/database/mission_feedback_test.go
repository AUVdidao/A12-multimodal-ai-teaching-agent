package database

import (
	"strings"
	"testing"

	"lessonforge.local/backend/internal/model"
)

func TestValidateMissionFeedback(t *testing.T) {
	valid := []model.MissionFeedbackItem{{SlideNumber: 2, SlideTitle: "牛顿第二定律", Severity: "IMPORTANT", Comment: "补充受力图与单位说明。"}}
	if err := validateMissionFeedback(1, 4, "建议增加一个课堂练习。", valid); err != nil {
		t.Fatalf("valid feedback rejected: %v", err)
	}

	tests := []struct {
		name string
		call func() error
		want string
	}{
		{name: "version", call: func() error { return validateMissionFeedback(0, 4, "总体意见", nil) }, want: "FEEDBACK_INPUT_INVALID"},
		{name: "rating", call: func() error { return validateMissionFeedback(1, 6, "总体意见", nil) }, want: "FEEDBACK_INPUT_INVALID"},
		{name: "summary", call: func() error { return validateMissionFeedback(1, 4, "  ", nil) }, want: "FEEDBACK_SUMMARY_INVALID"},
		{name: "item", call: func() error {
			return validateMissionFeedback(1, 4, "总体意见", []model.MissionFeedbackItem{{SlideNumber: 1, SlideTitle: "标题", Severity: "INFO"}})
		}, want: "FEEDBACK_ITEM_INVALID"},
		{name: "severity", call: func() error {
			return validateMissionFeedback(1, 4, "总体意见", []model.MissionFeedbackItem{{SlideNumber: 1, SlideTitle: "标题", Severity: "LOW", Comment: "修改"}})
		}, want: "FEEDBACK_ITEM_INVALID"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if err := tt.call(); err == nil || !strings.HasPrefix(err.Error(), tt.want) {
				t.Fatalf("validateMissionFeedback() error = %v, want prefix %q", err, tt.want)
			}
		})
	}
}
