import type { MaterialParseStatus, MaterialUsageType } from '@/api/materials';

export const usageOptions: Array<{ value: MaterialUsageType; label: string; description: string }> = [
  { value: 'TEXTBOOK_BASIS', label: '教材依据', description: '用于概念、定义与章节组织' },
  { value: 'CASE_MATERIAL', label: '案例素材', description: '用于课堂导入和案例分析' },
  { value: 'EXERCISE_SOURCE', label: '习题来源', description: '用于课堂练习和反馈' },
  { value: 'KNOWLEDGE_SUPPLEMENT', label: '知识补充', description: '用于拓展阅读和延伸探究' },
  { value: 'IMAGE_ASSET', label: '图片素材', description: '用于观察、说明和视觉表达' },
];

export const usageLabels = Object.fromEntries(usageOptions.map((item) => [item.value, item.label])) as Record<MaterialUsageType, string>;

export const parseStatusLabels: Record<MaterialParseStatus, string> = {
  NOT_STARTED: '等待解析',
  PROCESSING: '解析中',
  SUCCEEDED: '解析完成',
  FAILED: '解析失败',
};

export function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function formatDateTime(value?: string) {
  if (!value) return '-';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value));
}
