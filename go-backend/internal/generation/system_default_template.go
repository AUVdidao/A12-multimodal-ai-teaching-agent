package generation

import (
	"archive/zip"
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"lessonforge.local/backend/internal/model"
)

const (
	systemDefaultTemplateKey = "system/default-lessonforge-template.pptx"
	systemDefaultTemplateID  = "lessonforge-system-default"
	systemDefaultTemplateVer = 1
	systemDefaultShapeCount  = 56
	systemDefaultSlotCount   = 8
)

type systemDefaultTemplate struct {
	binding map[string]any
}

// prepareSystemDefaultTemplate creates the system-owned, low-fidelity PPTX
// used only when a teacher template is not executable. It is deliberately a
// real OOXML package, not a fake artifact: the normal Engine profile, source
// identity, compose plan, same-package executor and artifact validation still
// run unchanged.
func prepareSystemDefaultTemplate(spec model.LockedSpecification, job model.GenerationJob, owner int64, sharedRoot string) (systemDefaultTemplate, error) {
	if strings.TrimSpace(sharedRoot) == "" {
		return systemDefaultTemplate{}, errors.New("PPT_ENGINE_SYSTEM_DEFAULT_STORAGE_NOT_CONFIGURED")
	}
	path, err := sharedPath(sharedRoot, systemDefaultTemplateKey)
	if err != nil {
		return systemDefaultTemplate{}, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return systemDefaultTemplate{}, errors.New("PPT_ENGINE_SYSTEM_DEFAULT_STORAGE_NOT_CONFIGURED")
	}
	payload, err := systemDefaultPPTX()
	if err != nil {
		return systemDefaultTemplate{}, fmt.Errorf("PPT_ENGINE_SYSTEM_DEFAULT_TEMPLATE_BUILD: %w", err)
	}
	if err := os.WriteFile(path, payload, 0o644); err != nil {
		return systemDefaultTemplate{}, errors.New("PPT_ENGINE_SYSTEM_DEFAULT_STORAGE_NOT_CONFIGURED")
	}
	// A fixed timestamp makes the source identity reproducible across worker
	// retries and prevents filesystem metadata drift from invalidating the
	// Engine request. It is not used for teacher-owned files.
	fixedTime := time.Date(2000, time.January, 1, 0, 0, 0, 0, time.UTC)
	if err := os.Chtimes(path, fixedTime, fixedTime); err != nil {
		return systemDefaultTemplate{}, errors.New("PPT_ENGINE_SYSTEM_DEFAULT_STORAGE_NOT_CONFIGURED")
	}
	info, err := os.Stat(path)
	if err != nil || !info.Mode().IsRegular() {
		return systemDefaultTemplate{}, errors.New("PPT_ENGINE_SYSTEM_DEFAULT_STORAGE_NOT_CONFIGURED")
	}
	digest := sha256.Sum256(payload)
	sha := hex.EncodeToString(digest[:])
	profileID, profileVersion := fallbackProfileIdentity(spec)
	profile := systemDefaultProfile(spec, job.MissionID, owner, profileID, profileVersion, sha)
	lastModified := info.ModTime().UTC().Format(time.RFC3339Nano)
	binding := make(map[string]any, len(profile)+12)
	for key, value := range profile {
		binding[key] = value
	}
	binding["missionId"] = job.MissionID
	binding["templateFileVersion"] = "system-default-pptx-v1"
	binding["templateProfileVersion"] = "system-default-profile-v1"
	binding["templateFileSha256"] = sha
	binding["sourceSha256"] = sha
	binding["templateStorageKey"] = systemDefaultTemplateKey
	binding["templateFileSize"] = int64(len(payload))
	binding["templateLastModifiedUtc"] = lastModified
	profileChecksum, err := engineProfileChecksum(binding, profile)
	if err != nil {
		return systemDefaultTemplate{}, err
	}
	binding["engineNativeProfileChecksum"] = profileChecksum
	return systemDefaultTemplate{binding: binding}, nil
}

func fallbackProfileIdentity(spec model.LockedSpecification) (string, int) {
	id, version := profileIdentityFromValue(spec.Specification)
	if id == "" {
		if binding, ok := spec.TemplateBinding.(map[string]any); ok {
			id = text(binding["profileId"])
			version = intValue(binding["profileVersion"])
		}
	}
	if id == "" {
		id = "lessonforge-system-default-profile"
	}
	if version <= 0 {
		version = 1
	}
	return id, version
}

func profileIdentityFromValue(value any) (string, int) {
	var walk func(any) (string, int)
	walk = func(current any) (string, int) {
		object, ok := current.(map[string]any)
		if !ok {
			if list, ok := current.([]any); ok {
				for _, child := range list {
					if id, version := walk(child); id != "" {
						return id, version
					}
				}
			}
			return "", 0
		}
		if id := text(object["templateProfileId"]); id != "" {
			if _, hasSlides := object["slides"]; hasSlides {
				return id, intValue(object["templateProfileVersion"])
			}
		}
		for _, child := range object {
			if id, version := walk(child); id != "" {
				return id, version
			}
		}
		return "", 0
	}
	return walk(value)
}

func systemDefaultProfile(spec model.LockedSpecification, missionID, owner int64, profileID string, profileVersion int, sourceSHA string) map[string]any {
	const width = 12192000
	const height = 6858000
	objectIDs := make([]string, 0, systemDefaultShapeCount)
	for index := 0; index < systemDefaultShapeCount; index++ {
		objectIDs = append(objectIDs, strconv.Itoa(index+2))
	}
	roles := semanticRoles(spec.Specification)
	pages := make([]any, 0, len(roles))
	for index, role := range roles {
		pages = append(pages, map[string]any{
			"pageReferenceId":    fmt.Sprintf("system-default-page-%02d", index+1),
			"sourceSlide":        1,
			"semanticRole":       role,
			"objectIds":          objectIDs,
			"semanticRoleSource": "ANALYZER_PROFILE_CONTENT_REMAINDER",
		})
	}
	types := []string{"TITLE", "BODY", "BULLETS", "QUOTE", "TABLE", "CHART", "TEXT"}
	components := make([]any, 0, len(types)*systemDefaultSlotCount)
	shapeIndex := 0
	for _, contentType := range types {
		for slotIndex := 0; slotIndex < systemDefaultSlotCount; slotIndex++ {
			shapeIndex++
			left, top, slotWidth, slotHeight := systemDefaultBounds(shapeIndex)
			components = append(components, map[string]any{
				"componentId":  fmt.Sprintf("system-default-component-%03d", shapeIndex),
				"name":         "系统默认文字组件",
				"semanticRole": "SYSTEM_DEFAULT",
				"sourceSlide":  1,
				"shapeRefs": []any{map[string]any{
					"objectType": "SHAPE",
					"objectId":   strconv.Itoa(shapeIndex + 1),
				}},
				"childComponentIds": []any{},
				"slots": []any{map[string]any{
					"slotId":               fmt.Sprintf("system-default-slot-%03d", shapeIndex),
					"semanticRole":         contentType,
					"acceptedContentTypes": []any{contentType},
					"bounds":               map[string]any{"leftEmu": left, "topEmu": top, "widthEmu": slotWidth, "heightEmu": slotHeight},
					"required":             true,
					"capacityConstraint":   map[string]any{"maxCharacters": 4000, "maxItems": 1},
				}},
				"transformConstraint": "RESPONSIVE",
				"fixedStyle": map[string]any{
					"styleId":       "system-default-style",
					"fontToken":     "system-default-font",
					"colorToken":    "system-default-color",
					"preserveTheme": true,
				},
				"reusable":             true,
				"confidence":           1.0,
				"teacherConfirmed":     false,
				"executionEligibility": "EXECUTION_READY",
			})
		}
	}
	parserChecksum := sha256.Sum256([]byte("lessonforge-system-default-profile-v1"))
	return map[string]any{
		"contractVersion": "1.0.0",
		"profileId":       profileID,
		"templateId":      systemDefaultTemplateID,
		"projectId":       strconv.FormatInt(missionID, 10),
		"ownerUserId":     strconv.FormatInt(owner, 10),
		"templateVersion": systemDefaultTemplateVer,
		"profileVersion":  profileVersion,
		"status":          "READY",
		"pageSize":        map[string]any{"widthEmu": width, "heightEmu": height},
		"spatialProfile": map[string]any{
			"safeMarginLeftEmu": 300000, "safeMarginTopEmu": 300000,
			"safeMarginRightEmu": 300000, "safeMarginBottomEmu": 300000,
		},
		"templatePageReferences": pages,
		"components":             components,
		"textFitPolicy": map[string]any{
			"policyVersion": "1.0.0", "mode": "NO_ADJUSTMENT_PROFILE_V1", "fontEnvironmentVersion": "UNBOUND",
			"minimumFontSizePt": 10, "defaultFontSizePt": 18, "maximumFontSizePt": 36, "fontSizeStepPt": 1,
			"minimumLineSpacingPct": 80, "defaultLineSpacingPct": 100, "maximumLineSpacingPct": 160, "lineSpacingStepPct": 5,
			"maxTextBoxGrowthWidthEmu": 0, "maxTextBoxGrowthHeightEmu": 0,
			"minimumParagraphSpacingPt": 0, "defaultParagraphSpacingPt": 4, "maximumParagraphSpacingPt": 24, "paragraphSpacingStepPt": 1,
			"adjustmentOrder": []any{},
		},
		"executionStatus":        "EXECUTION_READY",
		"sourceVersionId":        1,
		"sourceSha256":           sourceSHA,
		"parserSnapshotChecksum": hex.EncodeToString(parserChecksum[:]),
	}
}

func semanticRoles(value any) []string {
	roles := []string{"BODY"}
	seen := map[string]bool{"BODY": true}
	var visit func(any)
	visit = func(current any) {
		switch item := current.(type) {
		case map[string]any:
			if layout, ok := item["semanticLayout"].(map[string]any); ok {
				role := strings.TrimSpace(fmt.Sprint(layout["primaryRole"]))
				if role != "" && !seen[role] {
					seen[role] = true
					roles = append(roles, role)
				}
			}
			for _, child := range item {
				visit(child)
			}
		case []any:
			for _, child := range item {
				visit(child)
			}
		}
	}
	visit(value)
	if len(roles) > 200 {
		return roles[:200]
	}
	return roles
}

func systemDefaultBounds(index int) (int, int, int, int) {
	// Callers use one-based component/shape indexes. Normalize them before
	// laying out the 56 slots so the first row starts at column zero and the
	// final row remains inside the 16:9 page bounds.
	zeroBased := index - 1
	column := zeroBased % 7
	row := zeroBased / 7
	return 350000 + column*1700000, 350000 + row*750000, 1450000, 600000
}

func systemDefaultPPTX() ([]byte, error) {
	entries := []struct {
		name string
		data string
	}{
		{"[Content_Types].xml", systemDefaultContentTypes()},
		{"_rels/.rels", systemDefaultRootRels()},
		{"ppt/presentation.xml", systemDefaultPresentation()},
		{"ppt/_rels/presentation.xml.rels", systemDefaultPresentationRels()},
		{"ppt/slideMasters/slideMaster1.xml", systemDefaultSlideMaster()},
		{"ppt/slideMasters/_rels/slideMaster1.xml.rels", systemDefaultSlideMasterRels()},
		{"ppt/slideLayouts/slideLayout1.xml", systemDefaultSlideLayout()},
		{"ppt/slideLayouts/_rels/slideLayout1.xml.rels", systemDefaultSlideLayoutRels()},
		{"ppt/slides/slide1.xml", systemDefaultSlide()},
		{"ppt/slides/_rels/slide1.xml.rels", systemDefaultSlideRels()},
		{"ppt/theme/theme1.xml", systemDefaultTheme()},
	}
	var buffer bytes.Buffer
	archive := zip.NewWriter(&buffer)
	for _, entry := range entries {
		header := &zip.FileHeader{Name: entry.name, Method: zip.Deflate}
		header.SetModTime(time.Unix(0, 0).UTC())
		writer, err := archive.CreateHeader(header)
		if err != nil {
			return nil, err
		}
		if _, err := io.WriteString(writer, entry.data); err != nil {
			return nil, err
		}
	}
	if err := archive.Close(); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}

func systemDefaultContentTypes() string {
	return `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/><Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/><Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/><Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/><Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>`
}

func systemDefaultRootRels() string {
	return `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/></Relationships>`
}

func systemDefaultPresentation() string {
	return `<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>`
}

func systemDefaultPresentationRels() string {
	return `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>`
}

func systemDefaultSlideMaster() string {
	return `<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld name="系统默认模板"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="1" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>`
}

func systemDefaultSlideMasterRels() string {
	return `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/></Relationships>`
}

func systemDefaultSlideLayout() string {
	return `<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank" preserve="1"><p:cSld name="系统默认版式"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>`
}

func systemDefaultSlideLayoutRels() string {
	return `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>`
}

func systemDefaultSlide() string {
	var shapes strings.Builder
	shapes.WriteString(`<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="12192000" cy="6858000"/><a:chOff x="0" y="0"/><a:chExt cx="12192000" cy="6858000"/></a:xfrm></p:grpSpPr>`)
	for index := 0; index < systemDefaultShapeCount; index++ {
		id := index + 2
		left, top, width, height := systemDefaultBounds(index + 1)
		shapes.WriteString(fmt.Sprintf(`<p:sp><p:nvSpPr><p:cNvPr id="%d" name="系统默认文字框%d"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="%d" y="%d"/><a:ext cx="%d" cy="%d"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="F7F9FC"/></a:solidFill><a:ln><a:solidFill><a:srgbClr val="CBD5E1"/></a:solidFill></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" sz="1600"/><a:t>系统默认内容</a:t></a:r><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>`, id, id, left, top, width, height))
	}
	return `<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld name="系统默认页面"><p:spTree>` + shapes.String() + `</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>`
}

func systemDefaultSlideRels() string {
	return `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>`
}

func systemDefaultTheme() string {
	return `<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="系统默认主题"><a:themeElements><a:clrScheme name="系统默认颜色"><a:dk1><a:srgbClr val="1F2937"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="334155"/></a:dk2><a:lt2><a:srgbClr val="F8FAFC"/></a:lt2><a:accent1><a:srgbClr val="2563EB"/></a:accent1><a:accent2><a:srgbClr val="7C3AED"/></a:accent2><a:accent3><a:srgbClr val="0F766E"/></a:accent3><a:accent4><a:srgbClr val="EA580C"/></a:accent4><a:accent5><a:srgbClr val="CA8A04"/></a:accent5><a:accent6><a:srgbClr val="DB2777"/></a:accent6><a:hlink><a:srgbClr val="2563EB"/></a:hlink><a:folHlink><a:srgbClr val="7C3AED"/></a:folHlink></a:clrScheme><a:fontScheme name="系统默认字体"><a:majorFont><a:latin typeface="Aptos Display"/><a:ea typeface="微软雅黑"/><a:cs typeface="Arial"/></a:majorFont><a:minorFont><a:latin typeface="Aptos"/><a:ea typeface="微软雅黑"/><a:cs typeface="Arial"/></a:minorFont></a:fontScheme><a:fmtScheme name="系统默认格式"><a:fillStyleLst/><a:lnStyleLst/><a:effectStyleLst/><a:bgFillStyleLst/></a:fmtScheme></a:themeElements></a:theme>`
}
