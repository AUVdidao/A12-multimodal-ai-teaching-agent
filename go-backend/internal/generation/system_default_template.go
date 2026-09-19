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

	_ "embed"

	"lessonforge.local/backend/internal/model"
)

// systemDefaultTemplateAsset is a PowerPoint-authored-compatible OOXML base
// package. The previous hand-built package was structurally sufficient for
// the internal ZIP validator but PowerPoint rejected it because it omitted
// standard presentation parts. Keep the source asset in the repository so
// the worker ships the exact same valid base package on every retry.
//
//go:embed assets/system-default-template.pptx
var systemDefaultTemplateAsset []byte

const (
	systemDefaultTemplateKey    = "system/default-lessonforge-template.pptx"
	systemDefaultTemplateID     = "lessonforge-system-default"
	systemDefaultTemplateVer    = 1
	systemDefaultShapeCount     = 3
	systemDefaultComponentCount = 26
)

type systemDefaultTemplate struct {
	binding map[string]any
}

type systemDefaultLayoutVariant struct {
	pageType    string
	sourceSlide int
	roles       []string
}

func systemDefaultLayoutVariants() []systemDefaultLayoutVariant {
	return []systemDefaultLayoutVariant{
		{pageType: "TITLE_IMPORT", sourceSlide: 1, roles: []string{"TITLE", "BULLETS"}},
		{pageType: "CONCEPT", sourceSlide: 2, roles: []string{"TITLE", "BODY", "BULLETS"}},
		{pageType: "PROCESS", sourceSlide: 3, roles: []string{"TITLE", "BODY", "BULLETS", "BULLETS", "BULLETS", "BULLETS"}},
		{pageType: "COMPARISON", sourceSlide: 4, roles: []string{"TITLE", "BODY", "BULLETS", "BULLETS", "BULLETS", "BULLETS"}},
		{pageType: "CARDS", sourceSlide: 5, roles: []string{"TITLE", "BULLETS", "BULLETS", "BULLETS", "BULLETS"}},
		{pageType: "SUMMARY", sourceSlide: 6, roles: []string{"TITLE", "BULLETS", "BULLETS", "BULLETS"}},
	}
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
	payload, err = clearSystemDefaultPlaceholders(payload)
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
	variants := systemDefaultLayoutVariants()
	pages := make([]any, 0, len(variants))
	components := make([]any, 0)
	for variantIndex, variant := range variants {
		objectIDs := make([]string, 0, len(variant.roles))
		for shapeIndex := range variant.roles {
			objectIDs = append(objectIDs, strconv.Itoa(shapeIndex+2))
		}
		pages = append(pages, map[string]any{
			"pageReferenceId":    fmt.Sprintf("system-default-page-%s-%02d", variant.pageType, variantIndex+1),
			"sourceSlide":        variant.sourceSlide,
			"semanticRole":       variant.pageType,
			"objectIds":          objectIDs,
			"semanticRoleSource": "COMPILER_OWNED_PAGE_VARIANT",
		})
		counts := map[string]int{}
		for _, role := range variant.roles {
			counts[role]++
		}
		ordinals := map[string]int{}
		for shapeIndex, contentType := range variant.roles {
			ordinals[contentType]++
			left, top, slotWidth, slotHeight := systemDefaultBoundsForRole(contentType, ordinals[contentType], counts[contentType])
			componentID := fmt.Sprintf("system-default-%s-%02d-%s-%02d", strings.ToLower(variant.pageType), variantIndex+1, strings.ToLower(contentType), ordinals[contentType])
			components = append(components, map[string]any{
				"componentId":  componentID,
				"name":         "系统默认" + variant.pageType + "可编辑组件",
				"semanticRole": variant.pageType,
				"sourceSlide":  variant.sourceSlide,
				"shapeRefs": []any{map[string]any{
					"objectType": "SHAPE",
					"objectId":   strconv.Itoa(shapeIndex + 2),
				}},
				"childComponentIds": []any{},
				"slots": []any{map[string]any{
					"slotId":               componentID + "-slot",
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

func systemDefaultBoundsForRole(role string, ordinal, count int) (int, int, int, int) {
	switch role {
	case "TITLE":
		return 650000, 400000, 10800000, 850000
	case "BODY":
		return 750000, 1550000, 4900000, 4000000
	case "BULLETS":
		if count <= 1 {
			return 6000000, 1550000, 5350000, 4400000
		}
		gap := 180000
		width := (10800000 - gap*(count-1)) / count
		return 650000 + (ordinal-1)*(width+gap), 2600000, width, 2900000
	default:
		return 650000, 1550000, 10800000, 4400000
	}
}

func systemDefaultBounds(index int) (int, int, int, int) {
	// The source asset and profile share these editable native text regions.
	// Execute may resize them per semantic page variant because the fallback
	// profile is compiler-owned and declares RESPONSIVE transforms.
	switch index {
	case 1: // TITLE
		return 650000, 400000, 10800000, 850000
	case 2: // BODY / focus
		return 750000, 1550000, 4900000, 4400000
	case 3: // BULLETS / supporting content
		return 6000000, 1550000, 5350000, 4400000
	default:
		return 650000, 400000, 10800000, 850000
	}
}

func systemDefaultPPTX() ([]byte, error) {
	if len(systemDefaultTemplateAsset) == 0 {
		return nil, errors.New("system default template asset is empty")
	}
	return append([]byte(nil), systemDefaultTemplateAsset...), nil
}

func clearSystemDefaultPlaceholders(payload []byte) ([]byte, error) {
	archive, err := zip.NewReader(bytes.NewReader(payload), int64(len(payload)))
	if err != nil {
		return nil, err
	}
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	placeholder := []byte("<a:t>系统默认内容</a:t>")
	emptyText := []byte("<a:t></a:t>")
	for _, entry := range archive.File {
		reader, err := entry.Open()
		if err != nil {
			return nil, err
		}
		data, readErr := io.ReadAll(reader)
		closeErr := reader.Close()
		if readErr != nil {
			return nil, readErr
		}
		if closeErr != nil {
			return nil, closeErr
		}
		if strings.HasPrefix(entry.Name, "ppt/slides/") && strings.HasSuffix(entry.Name, ".xml") {
			data = bytes.ReplaceAll(data, placeholder, emptyText)
		}
		header := entry.FileHeader
		header.Method = zip.Deflate
		header.SetModTime(time.Unix(0, 0).UTC())
		fileWriter, err := writer.CreateHeader(&header)
		if err != nil {
			return nil, err
		}
		if _, err := fileWriter.Write(data); err != nil {
			return nil, err
		}
	}
	if err := writer.Close(); err != nil {
		return nil, err
	}
	return buffer.Bytes(), nil
}

// systemDefaultPPTXGenerated retains the old deterministic package builder
// for comparison/debugging. Runtime generation uses the embedded package
// above, because Microsoft PowerPoint requires the standard OOXML parts it
// does not contain.
func systemDefaultPPTXGenerated() ([]byte, error) {
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
		fontSize := 1800
		if index == 1 {
			fontSize = 2800
		}
		style := `<a:noFill/><a:ln><a:noFill/></a:ln>`
		if index > 1 {
			style = `<a:solidFill><a:srgbClr val="F7F9FC"/></a:solidFill><a:ln><a:solidFill><a:srgbClr val="CBD5E1"/></a:solidFill></a:ln>`
		}
		shapes.WriteString(fmt.Sprintf(`<p:sp><p:nvSpPr><p:cNvPr id="%d" name="系统默认文字框%d"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="%d" y="%d"/><a:ext cx="%d" cy="%d"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>%s</p:spPr><p:txBody><a:bodyPr wrap="square"><a:normAutofit/></a:bodyPr><a:lstStyle/><a:p><a:r><a:rPr lang="zh-CN" sz="%d"/><a:t>系统默认内容</a:t></a:r><a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>`, id, id, left, top, width, height, style, fontSize))
	}
	return `<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld name="系统默认页面"><p:spTree>` + shapes.String() + `</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>`
}

func systemDefaultSlideRels() string {
	return `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/></Relationships>`
}

func systemDefaultTheme() string {
	return `<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="系统默认主题"><a:themeElements><a:clrScheme name="系统默认颜色"><a:dk1><a:srgbClr val="1F2937"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="334155"/></a:dk2><a:lt2><a:srgbClr val="F8FAFC"/></a:lt2><a:accent1><a:srgbClr val="2563EB"/></a:accent1><a:accent2><a:srgbClr val="7C3AED"/></a:accent2><a:accent3><a:srgbClr val="0F766E"/></a:accent3><a:accent4><a:srgbClr val="EA580C"/></a:accent4><a:accent5><a:srgbClr val="CA8A04"/></a:accent5><a:accent6><a:srgbClr val="DB2777"/></a:accent6><a:hlink><a:srgbClr val="2563EB"/></a:hlink><a:folHlink><a:srgbClr val="7C3AED"/></a:folHlink></a:clrScheme><a:fontScheme name="系统默认字体"><a:majorFont><a:latin typeface="Aptos Display"/><a:ea typeface="微软雅黑"/><a:cs typeface="Arial"/></a:majorFont><a:minorFont><a:latin typeface="Aptos"/><a:ea typeface="微软雅黑"/><a:cs typeface="Arial"/></a:minorFont></a:fontScheme><a:fmtScheme name="系统默认格式"><a:fillStyleLst/><a:lnStyleLst/><a:effectStyleLst/><a:bgFillStyleLst/></a:fmtScheme></a:themeElements></a:theme>`
}
