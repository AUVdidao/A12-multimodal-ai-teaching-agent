package agent

import (
	_ "embed"
	"strings"
)

//go:embed skills/lessonforge-teaching-design/SKILL.md
var teachingDesignSkill string

//go:embed skills/lessonforge-presentation-planning/SKILL.md
var presentationPlanningSkill string

//go:embed skills/lessonforge-interaction-design/SKILL.md
var interactionDesignSkill string

func installedRoleSkill(role SubagentRole) string {
	var raw string
	switch role {
	case SubagentInstructionalDesigner:
		raw = teachingDesignSkill
	case SubagentPresentationArchitect:
		raw = presentationPlanningSkill
	case SubagentInteractionDesigner:
		raw = interactionDesignSkill
	default:
		return ""
	}
	return stripSkillFrontmatter(raw)
}

func stripSkillFrontmatter(raw string) string {
	raw = strings.TrimSpace(raw)
	if !strings.HasPrefix(raw, "---\n") {
		return raw
	}
	end := strings.Index(raw[4:], "\n---\n")
	if end < 0 {
		return raw
	}
	return strings.TrimSpace(raw[4+end+5:])
}

func promptWithInstalledSkill(base string, role SubagentRole) string {
	skill := installedRoleSkill(role)
	if skill == "" {
		return base
	}
	return base + "\n\nInstalled role skill (mandatory):\n" + skill
}
