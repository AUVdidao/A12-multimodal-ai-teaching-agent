import { EvidenceRef, SlideSpec } from "./domain.js";

type UnknownRecord = Record<string, unknown>;
type EvidenceItem = Record<string, unknown>;

interface AdapterDiagnostic {
  slideId: string;
  requestedVisualIntent: string;
  actualRunnerType: string;
  reason: string;
}

interface AdapterContext {
  evidence: EvidenceItem[];
  diagnostics: AdapterDiagnostic[];
}

interface RenderPlan {
  fields: UnknownRecord;
  reason?: string;
}

const MAX_BODY_CHARS = 3000;
const MAX_NOTE_CHARS = 3000;
const MAX_TABLE_ROWS = 20;
const MAX_TABLE_COLUMNS = 12;
const MAX_TIMELINE_ITEMS = 8;
const MAX_STATS = 8;
const MAX_TEXT_LIST = 20;
const MAX_TEXT_ITEM_CHARS = 500;
const SAFE_ASSET_EXTENSIONS = /\.(?:png|jpe?g|svg|webp|gif|bmp|mermaid)$/i;

/**
 * Compile V2 teaching semantics into the phase-1 Runner outline contract.
 *
 * This function deliberately accepts the authoritative evidence snapshot as a
 * separate argument. SlideSpec contains references, not source identity, so a
 * free-form sourceNotes value is never promoted to a source trace.
 */
export function toRunnerOutline(spec: SlideSpec, materialEvidence: EvidenceItem[] = []): Record<string, unknown> {
  const context: AdapterContext = { evidence: materialEvidence, diagnostics: [] };
  const slides = spec.slides.map((slide, index) => adaptSlide(slide, index, context));
  const subtitle = stringValue(spec.slides[0]?.slots?.subtitle);

  return {
    title: spec.deckTitle,
    ...(subtitle ? { subtitle } : {}),
    deck_style: {
      visual_density: deckDensity(spec),
      header_variant: "left-accent",
      style_seed: "a12-teaching-generic",
      footer_page_numbers: true,
    },
    // Disable optional attribution-slide insertion. The adapter has no image
    // search/generation step and must keep the requested slide count stable.
    compliance: {
      auto_image_sources: false,
      adapter_diagnostics: context.diagnostics,
    },
    slides,
  };
}

function adaptSlide(slide: SlideSpec["slides"][number], index: number, context: AdapterContext): UnknownRecord {
  const content = isRecord(slide.content) ? slide.content : {};
  const plan = choosePlan(slide, index, content);
  const actualRunnerType = runnerType(plan.fields);
  if (plan.reason) {
    context.diagnostics.push({
      slideId: slide.slideId,
      requestedVisualIntent: slide.visualIntent.type,
      actualRunnerType,
      reason: plan.reason,
    });
  }

  return {
    ...plan.fields,
    notes: buildNotes(slide, context.evidence),
  };
}

function choosePlan(slide: SlideSpec["slides"][number], index: number, content: UnknownRecord): RenderPlan {
  const intent = slide.visualIntent.type;

  switch (intent) {
    case "FLOW": {
      const steps = extractSteps(content, slide.slots);
      if (steps) return withTextFields({ type: "content", title: slide.title, variant: "timeline", milestones: steps }, content, slide.slots);
      const diagram = trustedAsset(content, slide.slots, ["diagram", "mermaid_source"]);
      if (diagram) return withTextFields({ type: "content", title: slide.title, variant: "flow", diagram }, content, slide.slots);
      return fallbackPlan(slide, index, "no valid content.steps[] or trusted diagram asset");
    }
    case "COMPARISON": {
      const comparison = extractComparison(content, slide.slots);
      if (comparison) return withTextFields({ type: "content", title: slide.title, variant: "comparison-2col", ...comparison }, content, slide.slots);
      const split = extractSplit(content, slide.slots);
      if (split) return withTextFields({ type: "content", title: slide.title, ...split }, content, slide.slots, "comparison requires explicit column titles; preserved content as split");
      return fallbackPlan(slide, index, "no valid left/right comparison structure");
    }
    case "TABLE": {
      const table = extractTable(content, slide.slots);
      if (table) return withTextFields({ type: "content", title: slide.title, variant: "table", table }, content, slide.slots);
      return fallbackPlan(slide, index, "no valid headers/rows table structure");
    }
    case "CHART": {
      const chart = extractChart(content, slide.slots);
      if (chart) return withTextFields({ type: "content", title: slide.title, variant: "chart", chart }, content, slide.slots);
      return fallbackPlan(slide, index, "no structured numeric chart data");
    }
    case "STATS": {
      const facts = extractStats(content, slide.slots);
      if (facts) return withTextFields({ type: "content", title: slide.title, variant: "stats", facts }, content, slide.slots);
      return fallbackPlan(slide, index, "no valid label/value stats items");
    }
    case "TIMELINE": {
      const milestones = extractSteps(content, slide.slots);
      if (milestones) return withTextFields({ type: "content", title: slide.title, variant: "timeline", milestones }, content, slide.slots);
      return fallbackPlan(slide, index, "no valid timeline items; deterministic text fallback");
    }
    case "IMAGE": {
      const image = trustedAsset(content, slide.slots, ["hero_image", "image"]);
      if (image) return withTextFields({ type: "content", title: slide.title, variant: "image-sidebar", image, image_side: "left" }, content, slide.slots);
      // image-sidebar is Runner-native even without an image: its renderer
      // becomes a deterministic text treatment instead of receiving a fake URL.
      return withTextFields({ type: "content", title: slide.title, variant: "image-sidebar", image_side: "left" }, content, slide.slots, "no trusted image asset; image-sidebar text fallback");
    }
    case "DIAGRAM":
    case "CONCEPT_MAP": {
      const diagram = trustedAsset(content, slide.slots, ["diagram", "mermaid_source"]);
      if (diagram) return withTextFields({ type: "content", title: slide.title, variant: "flow", diagram }, content, slide.slots);
      return fallbackPlan(slide, index, "no trusted diagram/mermaid asset; native flow requires a real asset");
    }
    case "MIXED":
      return mixedPlan(slide, index, content);
    case "TEXT":
    default:
      return fallbackLayoutPlan(slide, index, content);
  }
}

function mixedPlan(slide: SlideSpec["slides"][number], index: number, content: UnknownRecord): RenderPlan {
  const chart = extractChart(content, slide.slots);
  if (chart) return withTextFields({ type: "content", title: slide.title, variant: "chart", chart }, content, slide.slots);
  const table = extractTable(content, slide.slots);
  if (table) return withTextFields({ type: "content", title: slide.title, variant: "table", table }, content, slide.slots);
  const steps = extractSteps(content, slide.slots);
  if (steps) return withTextFields({ type: "content", title: slide.title, variant: "timeline", milestones: steps }, content, slide.slots);
  const comparison = extractComparison(content, slide.slots);
  if (comparison) return withTextFields({ type: "content", title: slide.title, variant: "comparison-2col", ...comparison }, content, slide.slots);
  const split = extractSplit(content, slide.slots);
  if (split) return withTextFields({ type: "content", title: slide.title, ...split }, content, slide.slots);
  return fallbackLayoutPlan(slide, index, content);
}

function fallbackLayoutPlan(slide: SlideSpec["slides"][number], index: number, content: UnknownRecord): RenderPlan {
  if (slide.pedagogicalRole === "COMPARISON") {
    const comparison = extractComparison(content, slide.slots);
    if (comparison) return withTextFields({ type: "content", title: slide.title, variant: "comparison-2col", ...comparison }, content, slide.slots);
  }

  if (slide.pedagogicalRole === "PRACTICE" || slide.pedagogicalRole === "FORMATIVE_ASSESSMENT") {
    const steps = extractSteps(content, slide.slots);
    if (steps) return withTextFields({ type: "content", title: slide.title, variant: "timeline", milestones: steps }, content, slide.slots);
  }

  const cards = extractCards(content, slide.slots);
  if (cards && (slide.pedagogicalRole === "PRACTICE" || slide.pedagogicalRole === "FORMATIVE_ASSESSMENT")) {
    return withTextFields({ type: "content", title: slide.title, variant: cards.length === 2 ? "cards-2" : "cards-3", cards }, content, slide.slots);
  }

  // Summary/assignment slots remain an explicit compatibility override for
  // legacy renderer fixtures that mutate those slots after V2 generation.
  // They are only consulted for these two legacy card layouts; all native V2
  // structures above read their semantic content first.
  const summaryItems = slide.layoutId === "summary"
    ? stringList(slide.slots.takeaways) ?? stringList(content.takeaways)
    : stringList(content.takeaways);
  if ((slide.layoutId === "summary" || slide.pedagogicalRole === "SUMMARY") && summaryItems && summaryItems.length >= 2) {
    const grouped = groupCards(summaryItems, 3);
    if (grouped) return withTextFields({ type: "content", title: slide.title, variant: "cards-3", cards: grouped }, content, slide.slots);
  }

  const assignmentItems = slide.layoutId === "assignment"
    ? stringList(slide.slots.tasks) ?? stringList(content.tasks)
    : stringList(content.tasks);
  if ((slide.layoutId === "assignment" || slide.pedagogicalRole === "ASSIGNMENT") && assignmentItems && assignmentItems.length >= 2) {
    const grouped = groupCards(assignmentItems, 2);
    if (grouped) return withTextFields({ type: "content", title: slide.title, variant: "cards-2", cards: grouped }, content, slide.slots);
  }

  const layoutHint = slide.layoutIntent.toLowerCase();
  if (/(two[- ]?column|two[- ]?pane|双栏|对照)/i.test(layoutHint)) {
    const split = extractSplit(content, slide.slots);
    if (split) return withTextFields({ type: "content", title: slide.title, ...split }, content, slide.slots);
  }
  if (/(timeline|process|流程|时间线)/i.test(layoutHint)) {
    const steps = extractSteps(content, slide.slots);
    if (steps) return withTextFields({ type: "content", title: slide.title, variant: "timeline", milestones: steps }, content, slide.slots);
  }

  if (slide.layoutId === "two_column") {
    const split = extractSplit(content, slide.slots);
    if (split) return withTextFields({ type: "content", title: slide.title, ...split }, content, slide.slots);
  }
  if (slide.layoutId === "comparison") {
    const comparison = extractComparison(content, slide.slots);
    if (comparison) return withTextFields({ type: "content", title: slide.title, variant: "comparison-2col", ...comparison }, content, slide.slots);
    const split = extractSplit(content, slide.slots);
    if (split) return withTextFields({ type: "content", title: slide.title, ...split }, content, slide.slots, "comparison layout lacked explicit titles; preserved content as split");
  }
  if (slide.layoutId === "process") {
    const steps = extractSteps(content, slide.slots);
    if (steps) return withTextFields({ type: "content", title: slide.title, variant: "timeline", milestones: steps }, content, slide.slots);
  }
  if (slide.layoutId === "image_text") {
    const image = trustedAsset(content, slide.slots, ["hero_image", "image"]);
    return withTextFields({ type: "content", title: slide.title, variant: "image-sidebar", ...(image ? { image } : {}), image_side: "left" }, content, slide.slots, image ? undefined : "image_text has no trusted asset; image-sidebar text fallback");
  }

  if (index === 0 && slide.pedagogicalRole === "HOOK") {
    return titlePlan(slide);
  }
  if (slide.pedagogicalRole === "OBJECTIVE" && !bodyFor(content, slide.slots) && !bulletsFor(content, slide.slots)) {
    return sectionPlan(slide);
  }
  if (slide.pedagogicalRole === "HOOK" && index === 0) return titlePlan(slide);
  return withTextFields({ type: "content", title: slide.title, variant: "standard" }, content, slide.slots);
}

function fallbackPlan(slide: SlideSpec["slides"][number], index: number, reason: string): RenderPlan {
  const content = isRecord(slide.content) ? slide.content : {};
  if ((slide.visualIntent.type === "DIAGRAM" || slide.visualIntent.type === "CONCEPT_MAP") && (slide.layoutId === "summary" || slide.layoutId === "assignment")) {
    const legacyItems = slide.layoutId === "summary"
      ? stringList(slide.slots.takeaways) ?? stringList(content.takeaways)
      : stringList(slide.slots.tasks) ?? stringList(content.tasks);
    if (legacyItems && legacyItems.length >= 2) {
      const legacyCards = groupCards(legacyItems, slide.layoutId === "summary" ? 3 : 2);
      if (legacyCards) {
        return withTextFields({ type: "content", title: slide.title, variant: slide.layoutId === "summary" ? "cards-3" : "cards-2", cards: legacyCards }, content, slide.slots, reason);
      }
    }
    const legacyCards = fallbackLayoutPlan(slide, index, content);
    if (legacyCards.fields.variant === "cards-2" || legacyCards.fields.variant === "cards-3") return { ...legacyCards, reason };
  }
  const fallback = withTextFields({ type: "content", title: slide.title, variant: "standard" }, content, slide.slots);
  return { ...fallback, reason };
}

function titlePlan(slide: SlideSpec["slides"][number]): RenderPlan {
  const subtitle = stringValue(firstDefined(slide.content.subtitle, slide.slots.subtitle));
  return { fields: { type: "title", title: slide.title, ...(subtitle ? { subtitle } : {}) } };
}

function sectionPlan(slide: SlideSpec["slides"][number]): RenderPlan {
  const subtitle = stringValue(firstDefined(slide.content.subtitle, slide.slots.subtitle, slide.teachingPurpose));
  return { fields: { type: "section", title: slide.title, ...(subtitle ? { subtitle } : {}) } };
}

function withTextFields(base: UnknownRecord, content: UnknownRecord, slots: UnknownRecord, reason?: string): RenderPlan {
  const fields: UnknownRecord = { ...base };
  const body = bodyFor(content, slots);
  if (body) fields.body = body;
  const bullets = bulletsFor(content, slots);
  if (bullets) fields.bullets = bullets;
  const paragraphs = validStringList(firstDefined(content.paragraphs, slots.paragraphs));
  if (paragraphs) fields.paragraphs = paragraphs;
  const caption = boundedString(firstDefined(content.caption, slots.caption), 500);
  if (caption) fields.caption = caption;
  const verdict = boundedString(firstDefined(content.verdict, slots.verdict), 500);
  if (verdict && fields.verdict === undefined) fields.verdict = verdict;
  return { fields, ...(reason ? { reason } : {}) };
}

function extractSplit(content: UnknownRecord, slots: UnknownRecord): UnknownRecord | undefined {
  const left = textItems(firstDefined(content.leftPoints, content.left, slots.left));
  const right = textItems(firstDefined(content.rightPoints, content.right, slots.right));
  if (!left || !right || left.length === 0 || right.length === 0) return undefined;
  return { variant: "split", bullets: left, highlights: right };
}

function extractComparison(content: UnknownRecord, slots: UnknownRecord): UnknownRecord | undefined {
  const left = column(firstDefined(content.left, slots.left), stringValue(content.leftTitle), textItems(content.leftPoints));
  const right = column(firstDefined(content.right, slots.right), stringValue(content.rightTitle), textItems(content.rightPoints));
  if (!left || !right) return undefined;
  return {
    left,
    right,
    ...(boundedString(firstDefined(content.verdict, slots.verdict), 500) ? { verdict: boundedString(firstDefined(content.verdict, slots.verdict), 500) } : {}),
  };
}

function column(value: unknown, titleHint?: string, pointsHint?: string[]): UnknownRecord | undefined {
  const record = isRecord(value) ? value : undefined;
  const title = boundedString(firstDefined(record?.title, titleHint), 240);
  if (!title) return undefined;
  const bullets = validStringList(firstDefined(record?.bullets, record?.points, pointsHint));
  const body = boundedString(firstDefined(record?.body, record?.description, record?.text, typeof value === "string" ? value : undefined), MAX_BODY_CHARS);
  if (!body && !bullets) return undefined;
  return { title, ...(body ? { body } : {}), ...(bullets ? { bullets } : {}) };
}

function extractSteps(content: UnknownRecord, slots: UnknownRecord): Array<{ label?: string; title: string; body: string }> | undefined {
  const source = firstDefined(content.steps, content.milestones, content.items, slots.steps);
  if (!Array.isArray(source) || source.length < 2 || source.length > MAX_TIMELINE_ITEMS) return undefined;
  const milestones: Array<{ label?: string; title: string; body: string }> = [];
  for (const item of source) {
    if (typeof item === "string") {
      const text = boundedString(item, 240);
      if (!text) return undefined;
      milestones.push({ title: text, body: boundedString(item, MAX_BODY_CHARS) ?? text });
      continue;
    }
    if (!isRecord(item)) return undefined;
    const title = boundedString(firstDefined(item.title, item.name, item.step), 240);
    const body = boundedString(firstDefined(item.body, item.description, item.detail, title), MAX_BODY_CHARS);
    if (!title || !body) return undefined;
    const label = boundedString(item.label, 80);
    milestones.push({ title, body, ...(label ? { label } : {}) });
  }
  return milestones;
}

function extractTable(content: UnknownRecord, slots: UnknownRecord): { headers: string[]; rows: Array<Array<string | number>> } | undefined {
  const table = isRecord(content.table) ? content.table : undefined;
  const headersValue = firstDefined(table?.headers, content.headers, content.columns, slots.headers, slots.columns);
  const rowsValue = firstDefined(table?.rows, content.rows, slots.rows);
  const headers = validStringList(headersValue, MAX_TABLE_COLUMNS);
  if (!headers || headers.length === 0 || !Array.isArray(rowsValue) || rowsValue.length < 1 || rowsValue.length > MAX_TABLE_ROWS) return undefined;
  const rows: Array<Array<string | number>> = [];
  for (const row of rowsValue) {
    if (!Array.isArray(row) || row.length !== headers.length || row.length > MAX_TABLE_COLUMNS) return undefined;
    const normalized: Array<string | number> = [];
    for (const cell of row) {
      if (typeof cell === "string") {
        if (cell.length > MAX_TEXT_ITEM_CHARS) return undefined;
        normalized.push(cell);
      } else if (typeof cell === "number" && Number.isFinite(cell)) {
        normalized.push(cell);
      } else {
        return undefined;
      }
    }
    rows.push(normalized);
  }
  return { headers, rows };
}

function extractChart(content: UnknownRecord, slots: UnknownRecord): UnknownRecord | undefined {
  const chart = isRecord(content.chart) ? content.chart : isRecord(slots.chart) ? slots.chart : content;
  const categories = validStringList(firstDefined(chart.categories, chart.labels, content.categories, content.labels, slots.categories, slots.labels), 30);
  const seriesValue = firstDefined(chart.series, chart.datasets, content.series, content.datasets, slots.series, slots.datasets);
  if (!categories || !Array.isArray(seriesValue) || seriesValue.length < 1 || seriesValue.length > 6) return undefined;

  const series: Array<{ name: string; labels: string[]; values: number[] }> = [];
  for (const item of seriesValue) {
    if (!isRecord(item)) return undefined;
    const name = boundedString(firstDefined(item.name, item.label), 240);
    const values = numericList(item.values ?? item.data, 30);
    const labels = validStringList(firstDefined(item.labels, categories), 30);
    if (!name || !values || !labels || values.length !== categories.length || labels.length !== values.length) return undefined;
    series.push({ name, labels, values });
  }
  const typeValue = stringValue(chart.type) ?? "bar";
  if (!(["bar", "line", "pie", "doughnut"] as string[]).includes(typeValue)) return undefined;
  return { type: typeValue, series, ...(isRecord(chart.options) ? { options: chart.options } : {}) };
}

function extractStats(content: UnknownRecord, slots: UnknownRecord): Array<UnknownRecord> | undefined {
  const source = firstDefined(content.stats, content.facts, content.items, slots.stats, slots.facts);
  if (!Array.isArray(source) || source.length < 2 || source.length > MAX_STATS) return undefined;
  const facts: Array<UnknownRecord> = [];
  for (const item of source) {
    if (!isRecord(item)) return undefined;
    const label = boundedString(item.label, 240);
    const value = item.value;
    if (!label || !(typeof value === "string" ? value.length <= 80 : typeof value === "number" && Number.isFinite(value))) return undefined;
    const fact: UnknownRecord = { label, value };
    for (const key of ["detail", "caption", "accent"] as const) {
      const field = boundedString(item[key], key === "detail" ? 500 : key === "caption" ? 300 : 40);
      if (field) fact[key] = field;
    }
    facts.push(fact);
  }
  return facts;
}

function extractCards(content: UnknownRecord, slots: UnknownRecord): Array<{ title: string; body: string; accent?: string }> | undefined {
  const source = firstDefined(content.cards, slots.cards);
  if (!Array.isArray(source) || source.length < 2 || source.length > 3) return undefined;
  const cards: Array<{ title: string; body: string; accent?: string }> = [];
  for (const item of source) {
    if (!isRecord(item)) return undefined;
    const title = boundedString(item.title, 240);
    const body = boundedString(item.body, MAX_BODY_CHARS);
    if (!title || !body) return undefined;
    const accent = boundedString(item.accent, 40);
    cards.push({ title, body, ...(accent ? { accent } : {}) });
  }
  return cards;
}

function groupCards(items: string[], maximumCards: number): Array<{ title: string; body: string }> | undefined {
  if (items.length < 2) return undefined;
  const cardCount = Math.min(items.length, maximumCards);
  const baseGroupSize = Math.floor(items.length / cardCount);
  const largerGroupCount = items.length % cardCount;
  let itemIndex = 0;
  const cards = Array.from({ length: cardCount }, (_, cardIndex) => {
    const groupSize = baseGroupSize + (cardIndex < largerGroupCount ? 1 : 0);
    const groupedItems = items.slice(itemIndex, itemIndex + groupSize);
    itemIndex += groupSize;
    return { title: `Group ${cardIndex + 1}`, body: groupedItems.join("\n") };
  });
  return cards.every(card => card.body.length <= MAX_BODY_CHARS) ? cards : undefined;
}

function bodyFor(content: UnknownRecord, slots: UnknownRecord): string | undefined {
  const values = [content.body, content.summary, slots.body, slots.summary, slots.left, slots.right, content.verdict, slots.verdict]
    .map(stringValue)
    .filter((value): value is string => Boolean(value));
  const body = values.join("\n");
  return body.length > 0 && body.length <= MAX_BODY_CHARS ? body : undefined;
}

function bulletsFor(content: UnknownRecord, slots: UnknownRecord): string[] | undefined {
  const candidates = [content.bullets, content.items, content.takeaways, content.tasks, slots.bullets, slots.takeaways, slots.tasks, content.steps, slots.steps];
  for (const candidate of candidates) {
    const list = validStringList(candidate);
    if (list) return list;
  }
  return undefined;
}

function textItems(value: unknown): string[] | undefined {
  if (typeof value === "string") return boundedString(value, MAX_TEXT_ITEM_CHARS) ? [value] : undefined;
  return validStringList(value);
}

function trustedAsset(content: UnknownRecord, slots: UnknownRecord, keys: string[]): string | undefined {
  const candidates: unknown[] = [];
  for (const key of keys) {
    candidates.push(content[key], slots[key]);
    const assets = isRecord(content.assets) ? content.assets : undefined;
    candidates.push(assets?.[key]);
  }
  candidates.push(content.assetPath, content.imageAsset, slots.assetPath);
  return candidates.map(stringValue).find(isTrustedAsset);
}

function isTrustedAsset(value: string | undefined): value is string {
  if (!value || value.length > 300 || !SAFE_ASSET_EXTENSIONS.test(value)) return false;
  if (/^(?:[A-Za-z]:|[/\\]|file:|https?:)/i.test(value)) return false;
  if (/(?:^|[/\\])\.\.(?:[/\\]|$)/.test(value)) return false;
  return true;
}

function buildNotes(slide: SlideSpec["slides"][number], evidence: EvidenceItem[]): string {
  const lines: string[] = [];
  if (slide.teacherNotes.trim()) lines.push(`Teacher notes: ${slide.teacherNotes.trim()}`);
  if (slide.teachingPurpose.trim()) lines.push(`Teaching purpose: ${slide.teachingPurpose.trim()}`);
  if (slide.interaction && slide.interaction.type !== "NONE") {
    const interaction = slide.interaction;
    const parts = [`type=${interaction.type}`];
    if (interaction.prompt) parts.push(`prompt=${interaction.prompt}`);
    if (interaction.expectedResponse) parts.push(`expectedResponse=${interaction.expectedResponse}`);
    if (interaction.durationMinutes !== undefined) parts.push(`durationMinutes=${interaction.durationMinutes}`);
    lines.push(`Interaction: ${parts.join("; ")}`);
  }
  lines.push(`Pedagogical role: ${slide.pedagogicalRole}; density: ${slide.density}; importance: ${slide.importance}`);
  lines.push(`Visual intent: ${slide.visualIntent.type}${slide.visualIntent.description ? ` - ${slide.visualIntent.description}` : ""}`);
  if (slide.layoutIntent.trim()) lines.push(`Layout intent: ${slide.layoutIntent.trim()}`);
  if (slide.visualStrategy.trim()) lines.push(`Renderer hint: ${slide.visualStrategy.trim()}`);

  const sourceTrace = sourceTraceFor(slide.evidenceRefs, evidence);
  if (sourceTrace.length > 0) lines.push(`Source trace: ${sourceTrace.join(" | ")}`);
  return lines.join("\n").slice(0, MAX_NOTE_CHARS);
}

function sourceTraceFor(refs: EvidenceRef[], evidence: EvidenceItem[]): string[] {
  const traces: string[] = [];
  for (const ref of refs) {
    const item = evidence.find((candidate, index) => evidenceMatches(candidate, index, ref));
    if (!item) continue;
    const sourceName = stringValue(item.sourceName);
    const materialId = scalarValue(item.materialId);
    const chunkId = scalarValue(item.chunkId);
    const identity = [sourceName ? `sourceName=${sourceName}` : "", materialId !== undefined ? `materialId=${materialId}` : "", chunkId !== undefined ? `chunkId=${chunkId}` : ""]
      .filter(Boolean)
      .join(", ");
    if (identity && !traces.includes(identity)) traces.push(identity);
  }
  return traces;
}

function evidenceMatches(item: EvidenceItem, index: number, ref: EvidenceRef): boolean {
  if (ref.chunkId !== undefined && scalarValue(item.chunkId) !== ref.chunkId) return false;
  if (ref.materialId !== undefined && scalarValue(item.materialId) !== ref.materialId) return false;
  if (ref.snapshotIndex !== undefined && ref.snapshotIndex !== index && scalarValue(item.snapshotIndex) !== ref.snapshotIndex) return false;
  return ref.chunkId !== undefined || ref.materialId !== undefined || ref.snapshotIndex !== undefined;
}

function deckDensity(spec: SlideSpec): "low" | "medium" | "high" {
  const counts = { LOW: 0, MEDIUM: 0, HIGH: 0 };
  for (const slide of spec.slides) counts[slide.density] += 1;
  if (counts.HIGH > counts.MEDIUM && counts.HIGH >= counts.LOW) return "high";
  if (counts.LOW > counts.MEDIUM && counts.LOW > counts.HIGH) return "low";
  return "medium";
}

function runnerType(fields: UnknownRecord): string {
  return `${String(fields.type)}${fields.variant ? `/${String(fields.variant)}` : ""}`;
}

function firstDefined(...values: unknown[]): unknown {
  return values.find(value => value !== undefined && value !== null);
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function boundedString(value: unknown, maxLength: number): string | undefined {
  const result = stringValue(value);
  return result && result.length <= maxLength ? result : undefined;
}

function validStringList(value: unknown, maxItems = MAX_TEXT_LIST): string[] | undefined {
  if (!Array.isArray(value) || value.length === 0 || value.length > maxItems) return undefined;
  const result: string[] = [];
  for (const item of value) {
    const text = boundedString(item, MAX_TEXT_ITEM_CHARS);
    if (!text) return undefined;
    result.push(text);
  }
  return result;
}

function stringList(value: unknown): string[] | undefined {
  return validStringList(value);
}

function numericList(value: unknown, maxItems: number): number[] | undefined {
  if (!Array.isArray(value) || value.length === 0 || value.length > maxItems) return undefined;
  return value.every(item => typeof item === "number" && Number.isFinite(item)) ? [...value] as number[] : undefined;
}

function scalarValue(value: unknown): string | number | undefined {
  return typeof value === "string" || (typeof value === "number" && Number.isFinite(value)) ? value : undefined;
}

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
