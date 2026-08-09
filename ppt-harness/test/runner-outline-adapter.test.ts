import assert from "node:assert/strict";
import test from "node:test";
import { Slide, SlideSpec } from "../src/domain.js";
import { toRunnerOutline } from "../src/runner-outline-adapter.js";

function makeSlide(index: number, overrides: Partial<Slide> = {}): Slide {
  return {
    slideId: `slide-${index + 1}`,
    pedagogicalRole: "CONCEPT",
    learningObjectiveIds: [],
    teachingPurpose: `Purpose ${index + 1}`,
    title: `Slide ${index + 1}`,
    content: { body: `Body ${index + 1}` },
    evidenceRefs: [],
    sourceNotes: [],
    visualIntent: { type: "TEXT", description: "text" },
    assetRequests: [],
    layoutIntent: "title_content",
    interaction: { type: "NONE" },
    teacherNotes: "",
    density: "MEDIUM",
    importance: "CORE",
    outlineSectionIndex: null,
    layoutId: index === 0 ? "cover" : "title_content",
    visualStrategy: "native text",
    slots: { body: `Body ${index + 1}` },
    ...overrides,
  };
}

function makeSpec(...overrides: Partial<Slide>[]): SlideSpec {
  return {
    schemaVersion: 2,
    deckTitle: "Adapter test deck",
    locale: "en-US",
    templateId: "a12-teaching-generic",
    templateVersion: "1.0.0",
    slides: overrides.map((override, index) => makeSlide(index, override)),
  };
}

test("FLOW preserves exact steps as Runner timeline milestones", () => {
  const steps = ["Demand confirmation", "Material analysis", "Knowledge retrieval", "Slide generation"];
  const outline = toRunnerOutline(makeSpec({}, {
    visualIntent: { type: "FLOW", description: "real process" },
    content: { steps },
    slots: {},
  }));
  const slide = (outline.slides as Array<Record<string, unknown>>)[1];
  assert.equal(slide.variant, "timeline");
  assert.deepEqual((slide.milestones as Array<{ title: string }>).map(item => item.title), steps);
  assert.equal(JSON.stringify(slide).includes("learning task"), false);
});

test("COMPARISON preserves real titles and points without generated A/B labels", () => {
  const outline = toRunnerOutline(makeSpec({}, {
    visualIntent: { type: "COMPARISON", description: "compare retrieval methods" },
    content: {
      leftTitle: "Sparse Retrieval",
      leftPoints: ["Exact term match"],
      rightTitle: "Dense Retrieval",
      rightPoints: ["Semantic similarity"],
      verdict: "Choose based on query behavior",
    },
    slots: {},
  }));
  const slide = (outline.slides as Array<Record<string, unknown>>)[1];
  assert.equal(slide.variant, "comparison-2col");
  assert.equal((slide.left as { title: string }).title, "Sparse Retrieval");
  assert.deepEqual((slide.right as { bullets: string[] }).bullets, ["Semantic similarity"]);
  assert.equal(JSON.stringify(slide).includes("要点 A"), false);
  assert.equal(JSON.stringify(slide).includes("要点 B"), false);
});

test("TABLE and CHART use native structures only when their data is valid", () => {
  const tableOutline = toRunnerOutline(makeSpec({}, {
    visualIntent: { type: "TABLE", description: "matrix" },
    content: { headers: ["Metric", "Value", "Note"], rows: [["Hit@5", 0.8, "validated"]] },
    slots: {},
  }));
  const tableSlide = (tableOutline.slides as Array<Record<string, unknown>>)[1];
  assert.equal(tableSlide.variant, "table");
  assert.deepEqual((tableSlide.table as { headers: string[] }).headers, ["Metric", "Value", "Note"]);

  const malformedOutline = toRunnerOutline(makeSpec({}, {
    visualIntent: { type: "TABLE", description: "malformed matrix" },
    content: { headers: ["Metric", "Value"], rows: [["Hit@5"]] },
    slots: {},
  }));
  const malformedSlide = (malformedOutline.slides as Array<Record<string, unknown>>)[1];
  assert.equal(malformedSlide.variant, "standard");
  assert.equal((malformedOutline.compliance as { adapter_diagnostics: Array<{ reason: string }> }).adapter_diagnostics[0].reason, "no valid headers/rows table structure");

  const chartOutline = toRunnerOutline(makeSpec({}, {
    visualIntent: { type: "CHART", description: "trend" },
    content: { categories: ["A", "B", "C"], series: [{ name: "Hit@5", values: [0.5, 0.7, 0.8] }] },
    slots: {},
  }));
  const chartSlide = (chartOutline.slides as Array<Record<string, unknown>>)[1];
  assert.equal(chartSlide.variant, "chart");
  assert.deepEqual((chartSlide.chart as { series: Array<{ values: number[] }> }).series[0].values, [0.5, 0.7, 0.8]);

  const invalidChartOutline = toRunnerOutline(makeSpec({}, {
    visualIntent: { type: "CHART", description: "missing numeric data" },
    content: { categories: ["A", "B"], series: [{ name: "Hit@5", values: ["0.5", "0.7"] }] },
    slots: {},
  }));
  const invalidChartSlide = (invalidChartOutline.slides as Array<Record<string, unknown>>)[1];
  assert.equal(invalidChartSlide.variant, "standard");
  assert.equal("chart" in invalidChartSlide, false);
});

test("STATS, IMAGE fallback, and MIXED priority are deterministic", () => {
  const statsOutline = toRunnerOutline(makeSpec({}, {
    visualIntent: { type: "STATS", description: "key figures" },
    content: { stats: [{ label: "Chunks", value: "601" }, { label: "Max length", value: "1000" }, { label: "Hit@5", value: 0.8 }] },
    slots: {},
  }));
  const statsSlide = (statsOutline.slides as Array<Record<string, unknown>>)[1];
  assert.equal(statsSlide.variant, "stats");
  assert.equal((statsSlide.facts as Array<{ label: string }>)[0].label, "Chunks");

  const imageOutline = toRunnerOutline(makeSpec({}, {
    visualIntent: { type: "IMAGE", description: "requested visual" },
    content: { description: "a diagram request", assetRequests: [{ type: "IMAGE" }] },
    slots: {},
  }));
  const imageSlide = (imageOutline.slides as Array<Record<string, unknown>>)[1];
  assert.equal(imageSlide.variant, "image-sidebar");
  assert.equal("image" in imageSlide, false);
  assert.equal(JSON.stringify(imageOutline).includes("http"), false);

  const mixed = makeSpec({}, {
    visualIntent: { type: "MIXED", description: "chart wins" },
    content: { categories: ["A", "B"], series: [{ name: "Score", values: [1, 2] }], headers: ["A"], rows: [["table"]] },
    slots: {},
  });
  const mixedOutline = toRunnerOutline(mixed);
  assert.equal((mixedOutline.slides as Array<Record<string, unknown>>)[1].variant, "chart");

  const deterministicAgain = toRunnerOutline(mixed);
  assert.deepEqual(deterministicAgain, mixedOutline);
});

test("speaker notes retain teaching guidance and derive source trace from evidence snapshot", () => {
  const spec = makeSpec({}, {
    teacherNotes: "Emphasize the difference between dense and sparse retrieval.",
    interaction: { type: "DISCUSSION", prompt: "Why are equal dimensions not directly comparable?", expectedResponse: "Discuss representation semantics.", durationMinutes: 5 },
    sourceNotes: ["fake.pdf"],
    evidenceRefs: [{ materialId: 8, chunkId: 123 }],
    slots: {},
  });
  const outline = toRunnerOutline(spec, [{ materialId: 8, chunkId: 123, sourceName: "教材.pdf", text: "secret evidence text" }]);
  const notes = String((outline.slides as Array<Record<string, unknown>>)[1].notes);
  assert.match(notes, /dense and sparse retrieval/);
  assert.match(notes, /Why are equal dimensions/);
  assert.match(notes, /sourceName=教材\.pdf/);
  assert.doesNotMatch(notes, /fake\.pdf/);
  assert.doesNotMatch(notes, /secret evidence text/);
});

test("pedagogical roles influence layout when content supports the native structure", () => {
  const outline = toRunnerOutline(makeSpec(
    { pedagogicalRole: "HOOK", visualIntent: { type: "TEXT", description: "opening" }, content: {}, slots: {} },
    { pedagogicalRole: "OBJECTIVE", content: {}, slots: {} },
    { pedagogicalRole: "COMPARISON", visualIntent: { type: "COMPARISON", description: "compare" }, content: { leftTitle: "Left", leftPoints: ["L"], rightTitle: "Right", rightPoints: ["R"] }, slots: {} },
    { pedagogicalRole: "PRACTICE", content: { steps: ["Try", "Check"] }, slots: {} },
    { pedagogicalRole: "FORMATIVE_ASSESSMENT", content: { cards: [{ title: "Prompt", body: "Answer" }, { title: "Check", body: "Explain" }] }, slots: {} },
    { pedagogicalRole: "SUMMARY", content: { takeaways: ["One", "Two", "Three"] }, slots: {} },
    { pedagogicalRole: "ASSIGNMENT", content: { tasks: ["Task one", "Task two"] }, slots: {} },
  ));
  const slides = outline.slides as Array<Record<string, unknown>>;
  assert.equal(slides[0].type, "title");
  assert.equal(slides[1].type, "section");
  assert.equal(slides[2].variant, "comparison-2col");
  assert.equal(slides[3].variant, "timeline");
  assert.equal(slides[4].variant, "cards-2");
  assert.equal(slides[5].variant, "cards-3");
  assert.equal(slides[6].variant, "cards-2");
});
