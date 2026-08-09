import {
  ASSET_REQUEST_TYPES,
  DENSITIES,
  EvidenceRef,
  HarnessError,
  IMPORTANCES,
  INTERACTION_TYPES,
  Importance,
  InteractionType,
  PEDAGOGICAL_ROLES,
  PedagogicalRole,
  PresentationJob,
  PresentationJobSnapshot,
  SlideSpec,
  TeachingObjective,
  VISUAL_INTENT_TYPES,
  VisualIntentType,
} from "./domain.js";

export interface EvidenceCatalogItem {
  ref: EvidenceRef;
  key: string;
  snapshotIndex: number;
  chunkId?: string | number;
  materialId?: string | number;
  sourceName?: string;
  title?: string;
}

export interface OutlineCatalogItem {
  outlineSectionIndex: number;
  title: string;
  description: string;
  points: string[];
}

export interface TeachingContractContext {
  objectiveCatalog: TeachingObjective[];
  evidenceCatalog: EvidenceCatalogItem[];
  outlineCatalog: OutlineCatalogItem[];
  outlineSectionCount: number;
}

export function buildTeachingContractContext(input: PresentationJob | PresentationJobSnapshot): TeachingContractContext {
  const snapshot = "jobSnapshot" in input ? input.jobSnapshot : input;
  return {
    objectiveCatalog: buildObjectiveCatalog(snapshot),
    evidenceCatalog: buildEvidenceCatalog(snapshot),
    outlineCatalog: buildOutlineCatalog(snapshot),
    outlineSectionCount: Array.isArray(snapshot.confirmedGenerationPlan.pptOutline)
      ? snapshot.confirmedGenerationPlan.pptOutline.length
      : 0,
  };
}

export function buildObjectiveCatalog(snapshot: PresentationJobSnapshot): TeachingObjective[] {
  const candidates = [
    ...goalTexts(snapshot.requirementSummary.teachingGoals),
    ...goalTexts(snapshot.confirmedTeachingIntent.generationGoals),
  ];
  const seen = new Set<string>();
  const catalog: TeachingObjective[] = [];
  for (const candidate of candidates) {
    const normalized = candidate.replace(/\s+/g, " ").trim().slice(0, 160);
    const key = normalized.toLocaleLowerCase();
    if (!normalized || seen.has(key)) continue;
    seen.add(key);
    catalog.push({ objectiveId: `OBJ-${catalog.length + 1}`, text: normalized });
  }
  return catalog;
}

export function buildEvidenceCatalog(snapshot: PresentationJobSnapshot): EvidenceCatalogItem[] {
  const evidence = Array.isArray(snapshot.materialEvidence) ? snapshot.materialEvidence : [];
  const catalog: EvidenceCatalogItem[] = [];
  evidence.forEach((item, snapshotIndex) => {
    const chunkId = identityValue(item.chunkId);
    const materialId = identityValue(item.materialId);
    const ref = chunkId !== undefined
      ? { chunkId }
      : materialId !== undefined
        ? { materialId, snapshotIndex }
        : undefined;
    if (!ref) return;
    catalog.push({
      ref,
      key: evidenceKey(ref),
      snapshotIndex,
      ...(chunkId === undefined ? {} : { chunkId }),
      ...(materialId === undefined ? {} : { materialId }),
      ...(stringValue(item.sourceName) ? { sourceName: stringValue(item.sourceName) } : {}),
      ...(stringValue(item.title) ? { title: stringValue(item.title) } : {}),
    });
  });
  return catalog;
}

export function buildOutlineCatalog(snapshot: PresentationJobSnapshot): OutlineCatalogItem[] {
  const outline = Array.isArray(snapshot.confirmedGenerationPlan.pptOutline)
    ? snapshot.confirmedGenerationPlan.pptOutline
    : [];
  return outline.flatMap((value, outlineSectionIndex) => {
    if (!isRecord(value)) return [];
    const title = stringValue(value.title) || "";
    const description = stringValue(value.description) || "";
    const points = Array.isArray(value.points)
      ? value.points.filter((point): point is string => typeof point === "string" && Boolean(point.trim())).map(point => point.trim().slice(0, 240))
      : [];
    if (!title && !description && points.length === 0) return [];
    return [{ outlineSectionIndex, title: title.slice(0, 160), description: description.slice(0, 240), points }];
  });
}

export function evidenceRefKey(ref: EvidenceRef): string | undefined {
  if (!isRecord(ref)) return undefined;
  const chunkId = identityValue(ref.chunkId);
  if (chunkId !== undefined) {
    if (ref.materialId !== undefined || ref.snapshotIndex !== undefined) return undefined;
    return `chunk:${String(chunkId)}`;
  }
  const materialId = identityValue(ref.materialId);
  const snapshotIndex = ref.snapshotIndex;
  if (materialId !== undefined && typeof snapshotIndex === "number" && Number.isSafeInteger(snapshotIndex) && snapshotIndex >= 0) {
    return `material:${String(materialId)}@${snapshotIndex}`;
  }
  return undefined;
}

export function evidenceRefForCatalog(item: EvidenceCatalogItem): EvidenceRef {
  return { ...item.ref };
}

export function evidenceKey(ref: EvidenceRef): string {
  const key = evidenceRefKey(ref);
  if (!key) throw new Error("Evidence catalog item has no stable identity");
  return key;
}

export function validateTeachingContract(spec: SlideSpec, context: TeachingContractContext, targetSlides: number): void {
  const objectiveIds = new Set(context.objectiveCatalog.map(objective => objective.objectiveId));
  const evidenceKeys = new Set(context.evidenceCatalog.map(item => item.key));
  const outlineIndexes = new Set(context.outlineCatalog.map(item => item.outlineSectionIndex));
  const coveredOutlineIndexes = new Set<number>();
  const roles: PedagogicalRole[] = [];

  if (spec.learningObjectives !== undefined) {
    if (!Array.isArray(spec.learningObjectives)) throw new HarnessError("SLIDE_OBJECTIVE_CATALOG_INVALID", "learningObjectives must be an array");
    for (const objective of spec.learningObjectives) {
      const supplied = context.objectiveCatalog.find(item => item.objectiveId === objective.objectiveId);
      if (!supplied || supplied.text !== objective.text) {
        throw new HarnessError("SLIDE_OBJECTIVE_CATALOG_INVALID", `invalid objective catalog entry=${String(objective?.objectiveId)}`);
      }
    }
  }

  for (const slide of spec.slides) {
    const slideId = typeof slide?.slideId === "string" && slide.slideId.trim() ? slide.slideId.trim() : "<missing>";
    if (!isPedagogicalRole(slide.pedagogicalRole)) fail("SLIDE_ROLE_INVALID", slideId, "pedagogicalRole is not an allowed enum value");
    roles.push(slide.pedagogicalRole);

    if (!Array.isArray(slide.learningObjectiveIds)) fail("SLIDE_OBJECTIVE_REF_INVALID", slideId, "learningObjectiveIds must be an array");
    for (const objectiveId of slide.learningObjectiveIds) {
      if (typeof objectiveId !== "string" || !objectiveIds.has(objectiveId)) {
        fail("SLIDE_OBJECTIVE_REF_INVALID", slideId, `invalid objectiveId=${String(objectiveId)}`);
      }
    }

    if (typeof slide.teachingPurpose !== "string" || !slide.teachingPurpose.trim() || slide.teachingPurpose.length > 160) {
      fail("SLIDE_TEACHING_PURPOSE_INVALID", slideId, "teachingPurpose must be nonblank and <=160 chars");
    }
    if (!isRecord(slide.content)) fail("SLIDE_CONTENT_INVALID", slideId, "content must be an object");
    if (!Array.isArray(slide.evidenceRefs)) fail("SLIDE_EVIDENCE_REF_INVALID", slideId, "evidenceRefs must be an array");
    for (const ref of slide.evidenceRefs) {
      const key = evidenceRefKey(ref);
      if (!key || !evidenceKeys.has(key)) fail("SLIDE_EVIDENCE_REF_INVALID", slideId, `invalid evidenceRef=${formatEvidenceRef(ref)}`);
    }
    if (!Array.isArray(slide.sourceNotes) || slide.sourceNotes.some(note => typeof note !== "string" || !note.trim() || note.length > 240)) {
      fail("SLIDE_SOURCE_NOTES_INVALID", slideId, "sourceNotes must contain concise nonblank notes");
    }

    if (!isRecord(slide.visualIntent) || !isVisualIntentType(slide.visualIntent.type) || typeof slide.visualIntent.description !== "string" || !slide.visualIntent.description.trim()) {
      fail("SLIDE_VISUAL_INTENT_INVALID", slideId, "visualIntent requires an allowed type and nonblank description");
    }
    if (isRecord(slide.visualIntent) && typeof slide.visualIntent.description === "string" && slide.visualIntent.description.length > 240) {
      fail("SLIDE_VISUAL_INTENT_INVALID", slideId, "visualIntent.description must be <=240 chars");
    }

    if (!Array.isArray(slide.assetRequests)) fail("SLIDE_ASSET_REQUEST_INVALID", slideId, "assetRequests must be an array");
    for (const asset of slide.assetRequests) {
      if (!isRecord(asset) || !isAssetRequestType(asset.type) || typeof asset.purpose !== "string" || !asset.purpose.trim() || asset.purpose.length > 160) {
        fail("SLIDE_ASSET_REQUEST_INVALID", slideId, "assetRequests require an allowed type and concise purpose");
      }
      if (isRecord(asset) && [asset.query, asset.description, asset.sourcePreference].some(value => value !== undefined && (typeof value !== "string" || value.length > 240))) {
        fail("SLIDE_ASSET_REQUEST_INVALID", slideId, "asset request text fields must be <=240 chars");
      }
    }
    if (typeof slide.layoutIntent !== "string" || !slide.layoutIntent.trim() || slide.layoutIntent.length > 240) {
      fail("SLIDE_LAYOUT_INTENT_INVALID", slideId, "layoutIntent must be nonblank and <=240 chars");
    }
    if (slide.interaction !== undefined) validateInteraction(slide.interaction, slideId);
    if (typeof slide.teacherNotes !== "string" || slide.teacherNotes.length > 2000) fail("SLIDE_TEACHER_NOTES_INVALID", slideId, "teacherNotes must be <=2000 chars");
    if (!isDensity(slide.density)) fail("SLIDE_DENSITY_INVALID", slideId, `invalid density=${String(slide.density)}`);
    if (!isImportance(slide.importance)) fail("SLIDE_IMPORTANCE_INVALID", slideId, `invalid importance=${String(slide.importance)}`);

    if (slide.outlineSectionIndex !== null && (!Number.isSafeInteger(slide.outlineSectionIndex) || slide.outlineSectionIndex < 0 || slide.outlineSectionIndex >= context.outlineSectionCount || !outlineIndexes.has(slide.outlineSectionIndex))) {
      fail("SLIDE_OUTLINE_REF_INVALID", slideId, `invalid outlineSectionIndex=${String(slide.outlineSectionIndex)}`);
    }
    if (slide.outlineSectionIndex !== null) coveredOutlineIndexes.add(slide.outlineSectionIndex);
  }

  for (const outlineSection of context.outlineCatalog) {
    if (!coveredOutlineIndexes.has(outlineSection.outlineSectionIndex)) {
      throw new HarnessError("SLIDE_OUTLINE_COVERAGE_MISSING", `outlineSectionIndex=${outlineSection.outlineSectionIndex} is not covered by any slide`);
    }
  }

  if (targetSlides >= 6) {
    const hasOpening = roles.some(role => role === "HOOK" || role === "OBJECTIVE");
    const hasKnowledge = roles.some(role => ["CONCEPT", "EXPLANATION", "EXAMPLE", "INQUIRY"].includes(role));
    const hasActivity = roles.some(role => ["PRACTICE", "FORMATIVE_ASSESSMENT", "DISCUSSION"].includes(role));
    const hasSummary = roles.includes("SUMMARY");
    if (!hasOpening || !hasKnowledge || !hasActivity || !hasSummary) {
      throw new HarnessError("SLIDE_SEQUENCE_INVALID", "teaching sequence must include opening, knowledge, activity, and SUMMARY roles");
    }
  }
}

function validateInteraction(value: unknown, slideId: string): asserts value is { type: InteractionType } {
  if (!isRecord(value) || !isInteractionType(value.type)) fail("SLIDE_INTERACTION_INVALID", slideId, "interaction.type is not an allowed enum value");
  for (const key of ["prompt", "expectedResponse"] as const) {
    if (value[key] !== undefined && (typeof value[key] !== "string" || value[key].length > 400)) {
      fail("SLIDE_INTERACTION_INVALID", slideId, `interaction.${key} must be <=400 chars`);
    }
  }
  if (value.durationMinutes !== undefined && (!Number.isSafeInteger(value.durationMinutes) || value.durationMinutes < 0 || value.durationMinutes > 120)) {
    fail("SLIDE_INTERACTION_INVALID", slideId, "interaction.durationMinutes must be an integer from 0 to 120");
  }
}

function goalTexts(value: unknown): string[] {
  if (typeof value === "string") return value.trim() ? [value] : [];
  if (!Array.isArray(value)) return [];
  return value.flatMap(item => {
    if (typeof item === "string") return item.trim() ? [item] : [];
    if (!isRecord(item)) return [];
    return [item.text, item.goal, item.description, item.title].find((candidate): candidate is string => typeof candidate === "string" && Boolean(candidate.trim())) || [];
  });
}

function identityValue(value: unknown): string | number | undefined {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value === "string" && value.trim()) return value.trim();
  return undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function isRecord(value: unknown): value is Record<string, any> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isPedagogicalRole(value: unknown): value is PedagogicalRole {
  return typeof value === "string" && (PEDAGOGICAL_ROLES as readonly string[]).includes(value);
}
function isVisualIntentType(value: unknown): value is VisualIntentType {
  return typeof value === "string" && (VISUAL_INTENT_TYPES as readonly string[]).includes(value);
}
function isAssetRequestType(value: unknown): boolean {
  return typeof value === "string" && (ASSET_REQUEST_TYPES as readonly string[]).includes(value);
}
function isInteractionType(value: unknown): value is InteractionType {
  return typeof value === "string" && (INTERACTION_TYPES as readonly string[]).includes(value);
}
function isDensity(value: unknown): boolean {
  return typeof value === "string" && (DENSITIES as readonly string[]).includes(value);
}
function isImportance(value: unknown): value is Importance {
  return typeof value === "string" && (IMPORTANCES as readonly string[]).includes(value);
}
function formatEvidenceRef(value: unknown): string {
  if (!isRecord(value)) return "<invalid>";
  if (value.chunkId !== undefined) return `chunkId=${String(value.chunkId)}`;
  if (value.materialId !== undefined) return `materialId=${String(value.materialId)},snapshotIndex=${String(value.snapshotIndex)}`;
  return "<missing identity>";
}
function fail(code: string, slideId: string, message: string): never {
  throw new HarnessError(code, `slideId=${slideId}; ${message}`);
}
