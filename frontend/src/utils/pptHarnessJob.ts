export const PPT_HARNESS_ACTIVE_STATUSES = [
  'QUEUED',
  'LOADING_REQUIREMENT',
  'LOADING_TEMPLATE',
  'BUILDING_TEMPLATE_CONTEXT',
  'GENERATING_SLIDE_SPEC',
  'VALIDATING_SLIDE_SPEC',
  'REPAIRING_SLIDE_SPEC',
  'RENDERING_PPTX',
  'RENDERING_PREVIEW',
  'RUNNING_DETERMINISTIC_QA',
  'VISUAL_REVIEW',
  'REVISING',
  'FINALIZING',
  'RETRY_PENDING',
] as const;

export const PPT_HARNESS_TERMINAL_STATUSES = ['SUCCEEDED', 'FAILED', 'CANCELLED'] as const;

export type PptHarnessJobStatus =
  | (typeof PPT_HARNESS_ACTIVE_STATUSES)[number]
  | (typeof PPT_HARNESS_TERMINAL_STATUSES)[number];

export function isPptHarnessActiveStatus(status?: string | null): boolean {
  return Boolean(status && (PPT_HARNESS_ACTIVE_STATUSES as readonly string[]).includes(status));
}

export function isPptHarnessTerminalStatus(status?: string | null): boolean {
  return Boolean(status && (PPT_HARNESS_TERMINAL_STATUSES as readonly string[]).includes(status));
}

export function pptHarnessStatusLabel(status?: string | null): string {
  const labels: Record<string, string> = {
    QUEUED: '正在排队',
    LOADING_REQUIREMENT: '正在读取教学上下文',
    LOADING_TEMPLATE: '正在读取课件模板',
    BUILDING_TEMPLATE_CONTEXT: '正在准备课件上下文',
    GENERATING_SLIDE_SPEC: '正在生成课件结构',
    VALIDATING_SLIDE_SPEC: '正在校验课件结构',
    REPAIRING_SLIDE_SPEC: '正在修复课件结构',
    RENDERING_PPTX: '正在渲染 PPTX',
    RENDERING_PREVIEW: '正在准备预览交接信息',
    RUNNING_DETERMINISTIC_QA: '正在质量检查',
    VISUAL_REVIEW: '正在进行视觉检查',
    REVISING: '正在修订课件',
    FINALIZING: '正在保存成果',
    RETRY_PENDING: '正在恢复生成任务',
    SUCCEEDED: 'PPT 已生成',
    FAILED: 'PPT 生成失败',
    CANCELLED: 'PPT 生成已取消',
  };
  return (status && labels[status]) || '正在生成 PPT';
}

export function safePptHarnessError(message: unknown, fallback = 'PPT 生成失败，请稍后重试。'): string {
  if (typeof message !== 'string') return fallback;
  const value = message.trim();
  if (!value || /(Exception|stack trace|node:|java\.|at\s+.+\(|[A-Za-z]:\\|Bearer\s+|token|api[_-]?key)/i.test(value)) {
    return fallback;
  }
  return value.slice(0, 240);
}

export function pptHarnessTaskStorageKey(projectId: number | string): string {
  return `a12-ppt-harness-task:${projectId}`;
}
