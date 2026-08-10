import assert from "node:assert/strict";
import test from "node:test";
import { loadConfig, parseVisualReviewMode } from "../src/config.js";

test("visual review defaults to ADVISORY", () => {
  assert.equal(parseVisualReviewMode({}), "ADVISORY");
  assert.equal(loadConfig({}).visualReviewMode, "ADVISORY");
});

test("legacy false maps to DISABLED", () => {
  assert.equal(parseVisualReviewMode({ PPT_HARNESS_VISUAL_REVIEW_ENABLED: "false" }), "DISABLED");
});

test("legacy true maps to ADVISORY", () => {
  assert.equal(parseVisualReviewMode({ PPT_HARNESS_VISUAL_REVIEW_ENABLED: "true" }), "ADVISORY");
});

test("new mode wins over the legacy flag", () => {
  assert.equal(parseVisualReviewMode({ PPT_HARNESS_VISUAL_REVIEW_MODE: "ENFORCING", PPT_HARNESS_VISUAL_REVIEW_ENABLED: "false" }), "ENFORCING");
  assert.equal(parseVisualReviewMode({ PPT_HARNESS_VISUAL_REVIEW_MODE: "DISABLED", PPT_HARNESS_VISUAL_REVIEW_ENABLED: "true" }), "DISABLED");
});

test("invalid or blank mode never opts into enforcing and falls back safely", () => {
  assert.equal(parseVisualReviewMode({ PPT_HARNESS_VISUAL_REVIEW_MODE: "wat", PPT_HARNESS_VISUAL_REVIEW_ENABLED: "true" }), "ADVISORY");
  assert.equal(parseVisualReviewMode({ PPT_HARNESS_VISUAL_REVIEW_MODE: "", PPT_HARNESS_VISUAL_REVIEW_ENABLED: "false" }), "DISABLED");
});
