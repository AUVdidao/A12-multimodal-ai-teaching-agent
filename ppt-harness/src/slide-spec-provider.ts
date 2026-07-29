import { HarnessConfig } from "./config.js";
import { HarnessError, PresentationJob, Slide, SlideSpec, TemplateSpec } from "./domain.js";

export interface SlideSpecProvider {
  create(job: PresentationJob, template: TemplateSpec): Promise<SlideSpec>;
  repair?(job: PresentationJob, template: TemplateSpec, invalidSpec: unknown, reason: string): Promise<SlideSpec>;
}

export class FixtureSlideSpecProvider implements SlideSpecProvider {
  async create(job: PresentationJob, template: TemplateSpec): Promise<SlideSpec> {
    const snapshot = job.requirementSnapshot;
    const course = text(snapshot.courseName) || text(snapshot.subject) || "教学主题";
    const topic = text(snapshot.chapterTopic) || text(snapshot.topic) || course;
    const title = `${course}：${topic}`;
    const definitions: Array<[string, string, Record<string, unknown>]> = [
      ["cover", title, { subtitle: "教学课件" }],
      ["section", "学习目标", { summary: "理解概念，能够解释并应用。" }],
      ["title_content", "核心知识", { body: "围绕课程核心概念建立清晰理解。", bullets: ["识别关键概念", "解释形成过程", "联系真实情境"] }],
      ["image_text", "观察与探究", { body: "基于现象或素材开展观察、推理与表达。", image: { type: "native-shape", description: "过程示意图" } }],
      ["two_column", "课堂活动", { left: "小组讨论问题", right: "分享与互评要点" }],
      ["comparison", "易混概念辨析", { left: "概念 A 的特征", right: "概念 B 的特征", verdict: "从适用条件和关键证据进行区分。" }],
      ["process", "学习过程", { steps: ["导入问题", "概念建构", "应用练习", "交流反馈"] }],
      ["summary", "课堂小结", { takeaways: ["回顾核心概念", "连接实际情境", "形成可迁移方法"] }],
      ["assignment", "课后任务", { tasks: ["完成基础练习", "记录一个应用案例", "准备下节课问题"] }]
    ];
    const slides: Slide[] = [];
    for (let index = 0; index < job.targetSlideCount; index += 1) {
      const [layoutId, slideTitle, slots] = definitions[Math.min(index, definitions.length - 1)];
      slides.push({ slideId: `slide-${index + 1}`, layoutId, title: index === 0 ? title : slideTitle, visualStrategy: visualStrategy(layoutId), slots });
    }
    return { deckTitle: title, locale: job.locale, templateId: template.templateId, templateVersion: template.version, slides };
  }
}

export class KimiSlideSpecProvider implements SlideSpecProvider {
  constructor(private readonly config: HarnessConfig) {}
  async create(job: PresentationJob, template: TemplateSpec): Promise<SlideSpec> {
    return this.request(job, template, "GENERATE", undefined, undefined);
  }

  async repair(job: PresentationJob, template: TemplateSpec, invalidSpec: unknown, reason: string): Promise<SlideSpec> {
    return this.request(job, template, "REPAIR", invalidSpec, reason);
  }

  private async request(
    job: PresentationJob,
    template: TemplateSpec,
    mode: "GENERATE" | "REPAIR",
    invalidSpec?: unknown,
    reason?: string,
  ): Promise<SlideSpec> {
    if (!this.config.kimiApiKey) {
      throw new HarnessError("KIMI_NOT_CONFIGURED", "Kimi SlideSpec generation requires a server-side MOONSHOT_API_KEY", 503);
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.config.kimiTimeoutMs);
    try {
      const response = await fetch(`${this.config.kimiBaseUrl}/chat/completions`, {
        method: "POST",
        signal: controller.signal,
        headers: {
          "Content-Type": "application/json",
          "Authorization": `Bearer ${this.config.kimiApiKey}`,
        },
        body: JSON.stringify(kimiRequest(this.config, job, template, mode, invalidSpec, reason)),
      });
      if (!response.ok) {
        throw new HarnessError("KIMI_REQUEST_FAILED", `Kimi SlideSpec request failed with HTTP ${response.status}: ${providerError(await response.text())}`, 502);
      }
      const payload = await response.json() as { choices?: Array<{ message?: { content?: string } }> };
      const content = payload.choices?.[0]?.message?.content;
      if (!content || typeof content !== "string") throw new HarnessError("KIMI_INVALID_RESPONSE", "Kimi did not return a SlideSpec JSON response", 502);
      try {
        return normalizeSlideSpec(JSON.parse(content), job, template);
      } catch {
        throw new HarnessError("KIMI_INVALID_JSON", "Kimi response was not valid SlideSpec JSON", 502);
      }
    } catch (error) {
      if (error instanceof HarnessError) throw error;
      if (error instanceof Error && error.name === "AbortError") throw new HarnessError("KIMI_TIMEOUT", "Kimi SlideSpec request timed out", 504);
      throw new HarnessError("KIMI_REQUEST_FAILED", "Kimi SlideSpec request could not be completed", 502);
    } finally {
      clearTimeout(timeout);
    }
  }
}

function kimiRequest(
  config: HarnessConfig,
  job: PresentationJob,
  template: TemplateSpec,
  mode: "GENERATE" | "REPAIR",
  invalidSpec?: unknown,
  reason?: string,
): Record<string, unknown> {
  const request: Record<string, unknown> = {
    model: config.kimiModel,
    // json_object is documented for Kimi Chat Completions and the response is
    // still validated locally against the stricter per-template SlideSpec rules.
    response_format: { type: "json_object" },
    messages: [
      { role: "system", content: systemInstruction(template, job.targetSlideCount) },
      { role: "user", content: JSON.stringify({
        operation: mode,
        requirementSnapshot: job.requirementSnapshot,
        template: compactTemplate(template),
        targetSlideCount: job.targetSlideCount,
        locale: job.locale,
        invalidSlideSpec: invalidSpec,
        validationFailure: reason,
      }) },
    ],
  };

  // K2.6 rejects the K2 preview-only thinking extension and custom
  // temperature settings. Keep its request body to documented chat fields.
  // K3 keeps its provider-specific settings isolated here.
  if (config.kimiModel.toLowerCase().startsWith("kimi-k3")) {
    request.temperature = 1;
    request.reasoning_effort = "low";
  }
  return request;
}

function providerError(body: string): string {
  const fallback = "no provider error detail";
  try {
    const payload = JSON.parse(body) as { error?: { message?: unknown } };
    const message = payload.error?.message;
    if (typeof message === "string" && message.trim()) return redact(message).slice(0, 360);
  } catch {
    // Keep the failure safe and concise if the provider response is not JSON.
  }
  return fallback;
}

function redact(value: string): string {
  return value
    .replace(/Bearer\s+[A-Za-z0-9._-]+/gi, "Bearer [REDACTED]")
    .replace(/(api[_-]?key\s*[=:]\s*)[^\s,;]+/gi, "$1[REDACTED]");
}

export function selectSlideSpecProvider(config: HarnessConfig): SlideSpecProvider {
  return config.generationSource === "FIXTURE" ? new FixtureSlideSpecProvider() : new KimiSlideSpecProvider(config);
}

function text(value: unknown): string | undefined { return typeof value === "string" && value.trim() ? value.trim().slice(0, 80) : undefined; }
function visualStrategy(layout: string): string {
  return ({ cover: "高对比标题与原生几何图形", image_text: "原生形状示意图与说明并置", comparison: "双栏对照", process: "步骤流程图" } as Record<string, string>)[layout] || "简洁信息层级与原生形状";
}

function compactTemplate(template: TemplateSpec) {
  return {
    templateId: template.templateId,
    version: template.version,
    name: template.name,
    layouts: template.layouts.map(layout => ({ layoutId: layout.layoutId, requiredSlots: layout.slots, capacity: layout.capacity })),
  };
}

function systemInstruction(template: TemplateSpec, targetSlideCount: number): string {
  const layoutContract = template.layouts.map(layout =>
    `${layout.layoutId}: required slots [${layout.slots.filter(slot => slot !== "title").join(", ") || "none"}]`,
  ).join("; ");
  return [
    "You generate structured SlideSpec JSON for an editable Chinese teaching PPTX.",
    "Return exactly one JSON object. Do not use Markdown fences or any explanatory text.",
    `Generate exactly ${targetSlideCount} slides using only selected template ${template.templateId}@${template.version}.`,
    `Root keys must be deckTitle, locale, templateId, templateVersion, slides. Set locale=${template.locale}, templateId=${template.templateId}, templateVersion=${template.version}.`,
    "Every slide must use exactly these keys: slideId, layoutId, title, visualStrategy, slots.",
    "Use the exact key layoutId. Never use layout, variant, type, layout_id, templateRef, body, bullets, subtitle, left, right, steps, takeaways, tasks, verdict, or image at slide root.",
    `Allowed layoutId values and required slots: ${layoutContract}. Put every required value inside the slots object.`,
    "Example: {\"slideId\":\"slide-1\",\"layoutId\":\"cover\",\"title\":\"...\",\"visualStrategy\":\"native shapes with a clear teaching focus\",\"slots\":{\"subtitle\":\"...\"}}.",
    "Use Chinese unless the requirement explicitly requests another language.",
    "Cover introduction, objectives, core knowledge, activity, assessment, summary, and assignment as appropriate.",
    "Every slide needs a concrete visualStrategy. Avoid repeating the same bullet layout on consecutive slides.",
    "Never output revision notes, workflow explanations, prompts, internal logs, or placeholder text.",
  ].join(" ");
}

/**
 * Kimi may return an otherwise useful deck draft using common presentation
 * aliases (layout, subtitle, bullets) despite JSON-object mode. Normalize only
 * known aliases into the strict local contract; unknown layouts still fail
 * validation in the workflow.
 */
function normalizeSlideSpec(value: unknown, job: PresentationJob, template: TemplateSpec): SlideSpec {
  if (!isRecord(value)) return value as SlideSpec;
  const sourceSlides = Array.isArray(value.slides) ? value.slides : [];
  const layouts = new Map(template.layouts.map(layout => [layout.layoutId, layout]));
  const slides = sourceSlides.map((source, index) => {
    const raw = isRecord(source) ? source : {};
    const layoutId = stringValue(raw.layoutId) || stringValue(raw.layout) || "";
    const layout = layouts.get(layoutId);
    const suppliedSlots = isRecord(raw.slots) ? raw.slots : {};
    const slots: Record<string, unknown> = {};
    for (const slot of layout?.slots ?? []) {
      if (slot === "title") continue;
      const candidate = suppliedSlots[slot] ?? raw[slot];
      if (candidate !== undefined) slots[slot] = candidate;
    }
    return {
      slideId: stringValue(raw.slideId) || `slide-${index + 1}`,
      layoutId,
      title: stringValue(raw.title) || "",
      visualStrategy: visualStrategyText(raw.visualStrategy),
      slots,
    };
  });
  return {
    deckTitle: stringValue(value.deckTitle) || slides[0]?.title || text(job.requirementSnapshot.courseName) || "教学课件",
    locale: stringValue(value.locale) || job.locale,
    templateId: stringValue(value.templateId) || template.templateId,
    templateVersion: stringValue(value.templateVersion) || template.version,
    slides,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stringValue(value: unknown): string | undefined {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (typeof value === "number" && Number.isFinite(value)) return String(value);
  return undefined;
}

function visualStrategyText(value: unknown): string {
  if (typeof value === "string" && value.trim()) return value.trim();
  if (isRecord(value)) {
    const description = stringValue(value.description);
    const type = stringValue(value.type);
    const placement = stringValue(value.placement);
    return [type, description, placement].filter((item): item is string => Boolean(item)).join("；");
  }
  return "";
}

function slideSpecSchema(template: TemplateSpec, targetSlideCount: number, locale: string): Record<string, unknown> {
  const layouts = template.layouts.map(layout => layout.layoutId);
  return {
    type: "object",
    additionalProperties: false,
    required: ["deckTitle", "locale", "templateId", "templateVersion", "slides"],
    properties: {
      deckTitle: { type: "string", minLength: 1, maxLength: 120 },
      locale: { type: "string", const: locale },
      templateId: { type: "string", const: template.templateId },
      templateVersion: { type: "string", const: template.version },
      slides: {
        type: "array", minItems: targetSlideCount, maxItems: targetSlideCount,
        items: {
          type: "object", additionalProperties: false,
          required: ["slideId", "layoutId", "title", "visualStrategy", "slots"],
          properties: {
            slideId: { type: "string", minLength: 1, maxLength: 64 },
            layoutId: { type: "string", enum: layouts },
            title: { type: "string", minLength: 1, maxLength: 100 },
            visualStrategy: { type: "string", minLength: 1, maxLength: 240 },
            slots: { type: "object", additionalProperties: true },
          },
        },
      },
    },
  };
}
