import { HarnessError, SlideSpec } from "./domain.js";

export function toRunnerOutline(spec: SlideSpec): Record<string, unknown> {
  return {
    title: spec.deckTitle,
    subtitle: "A12 教学智能体生成",
    deck_style: { visual_density: "medium", header_variant: "left-accent", style_seed: "a12-teaching-generic", footer_page_numbers: true },
    slides: spec.slides.map((slide, index) => adaptSlide(slide, index))
  };
}

function adaptSlide(slide: SlideSpec["slides"][number], index: number): Record<string, unknown> {
  const slots = slide.slots;
  if (index === 0) return { type: "title", title: slide.title, subtitle: String(slots.subtitle || "教学课件"), notes: `visualStrategy: ${slide.visualStrategy}` };
  const base = { type: "content", title: slide.title, variant: variantFor(slide.layoutId), notes: `visualStrategy: ${slide.visualStrategy}` };
  switch (slide.layoutId) {
    case "two_column":
      return { ...base, variant: "split", body: String(slots.left), bullets: [String(slots.left)], highlights: [String(slots.right)] };
    case "comparison":
      return { ...base, variant: "comparison-2col", left: { title: "要点 A", body: String(slots.left) }, right: { title: "要点 B", body: String(slots.right) }, verdict: String(slots.verdict) };
    case "process":
      return { ...base, variant: "timeline", milestones: array(slots.steps).map((step, position) => ({ label: `步骤 ${position + 1}`, title: step, body: "完成本步骤的学习任务" })) };
    case "summary":
    case "assignment":
      return {
        ...base,
        variant: slide.layoutId === "summary" ? "cards-3" : "cards-2",
        cards: groupCards(
          array(slide.layoutId === "summary" ? slots.takeaways : slots.tasks),
          slide.layoutId === "summary" ? 3 : 2
        )
      };
    default:
      return { ...base, body: bodyFor(slide), bullets: bulletsFor(slide) };
  }
}

function variantFor(layoutId: string): string {
  const variants: Record<string, string> = {
    cover: "standard", section: "standard", title_content: "standard", image_text: "image-sidebar",
    two_column: "split", comparison: "comparison-2col", process: "timeline", summary: "cards-3", assignment: "cards-2"
  };
  const variant = variants[layoutId];
  if (!variant) throw new HarnessError("INVALID_SLIDE_SPEC", `No runner mapping for layoutId: ${layoutId}`);
  return variant;
}

function bodyFor(slide: SlideSpec["slides"][number]): string {
  const slots = slide.slots;
  return [slots.body, slots.summary, slots.left, slots.right, slots.verdict].filter(value => typeof value === "string").join("\n") || slide.visualStrategy;
}

function bulletsFor(slide: SlideSpec["slides"][number]): string[] {
  const slots = slide.slots;
  const list = [slots.bullets, slots.steps, slots.takeaways, slots.tasks].find(Array.isArray);
  return Array.isArray(list) ? list.map(String) : [];
}

function array(value: unknown): string[] {
  const items = Array.isArray(value) ? value.map(String).filter(Boolean) : [];
  return items.length >= 2 ? items : ["核心要点", "课堂应用"];
}

function groupCards(items: string[], maximumCards: number): Array<{ title: string; body: string }> {
  const cardCount = Math.min(items.length, maximumCards);
  const baseGroupSize = Math.floor(items.length / cardCount);
  const largerGroupCount = items.length % cardCount;
  let itemIndex = 0;

  return Array.from({ length: cardCount }, (_, cardIndex) => {
    const groupSize = baseGroupSize + (cardIndex < largerGroupCount ? 1 : 0);
    const groupedItems = items.slice(itemIndex, itemIndex + groupSize);
    const firstItemNumber = itemIndex + 1;
    itemIndex += groupSize;
    const lastItemNumber = itemIndex;
    return {
      title: firstItemNumber === lastItemNumber ? `要点 ${firstItemNumber}` : `要点 ${firstItemNumber}-${lastItemNumber}`,
      body: groupedItems.join("\n")
    };
  });
}
