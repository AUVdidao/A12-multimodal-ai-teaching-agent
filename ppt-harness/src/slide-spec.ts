import { HarnessError, SLIDE_SPEC_SCHEMA_VERSION, SlideSpec, TemplateSpec } from "./domain.js";
import { TeachingContractContext, validateTeachingContract } from "./pedagogical-contract.js";

const BANNED = [/\bTODO\b/i, /\bTBD\b/i, /占位/, /lorem ipsum/i];

export function validateSlideSpec(spec: SlideSpec, template: TemplateSpec, targetSlides: number, context?: TeachingContractContext): void {
  if (spec.schemaVersion !== SLIDE_SPEC_SCHEMA_VERSION) throw new HarnessError("SLIDE_SCHEMA_VERSION_UNSUPPORTED", "SlideSpec schemaVersion must be 2");
  if (spec.templateId !== template.templateId || spec.templateVersion !== template.version) throw new HarnessError("INVALID_SLIDE_SPEC", "SlideSpec template reference does not match the selected template");
  if (!Array.isArray(spec.slides) || spec.slides.length !== targetSlides) throw new HarnessError("INVALID_SLIDE_SPEC", "SlideSpec page count does not match requested target");
  const layouts = new Map(template.layouts.map(layout => [layout.layoutId, layout]));
  for (const slide of spec.slides) {
    const layout = layouts.get(slide.layoutId);
    if (!layout) throw new HarnessError("INVALID_SLIDE_SPEC", `Unsupported layoutId: ${slide.layoutId}`);
    if (!slide.title?.trim() || !slide.visualStrategy?.trim()) throw new HarnessError("INVALID_SLIDE_SPEC", "Each slide requires title and visualStrategy");
    if (!slide.slots || typeof slide.slots !== "object" || Array.isArray(slide.slots)) throw new HarnessError("INVALID_SLIDE_SPEC", `slideId=${slide.slideId || "<missing>"}; slots must be an object`);
    for (const slot of layout.slots) if (slide.slots[slot] === undefined && slot !== "title") throw new HarnessError("INVALID_SLIDE_SPEC", `Required slot missing: ${slot}`);
    const text = JSON.stringify(slide);
    if (BANNED.some(pattern => pattern.test(text))) throw new HarnessError("INVALID_SLIDE_SPEC", "SlideSpec contains placeholder content");
    for (const [slot, capacity] of Object.entries(layout.capacity)) {
      const value = slot === "title" ? slide.title : slide.slots[slot];
      // Image capacity is cardinality, not a text-character limit. Phase 1
      // renders image_text as native shapes, so a concise visual description is
      // valid and never becomes a filesystem or network asset reference.
      if (slot === "image" && typeof value === "string") {
        if (!value.trim()) throw new HarnessError("INVALID_SLIDE_SPEC", "Image slot requires a non-empty visual description");
        continue;
      }
      if (typeof value === "string" && value.length > capacity) throw new HarnessError("INVALID_SLIDE_SPEC", `Slot ${slot} exceeds template capacity`);
      if (Array.isArray(value) && value.length > capacity) throw new HarnessError("INVALID_SLIDE_SPEC", `Slot ${slot} exceeds template capacity`);
    }
  }
  validateTeachingContract(spec, context ?? { objectiveCatalog: [], evidenceCatalog: [], outlineCatalog: [], outlineSectionCount: 0 }, targetSlides);
}
