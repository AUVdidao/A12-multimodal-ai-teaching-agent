import type { A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';

const tones = ['purple', 'green', 'orange', 'blue', 'red'] as const;
const icons: A12AssetIconName[] = ['folder', 'book', 'lightbulb', 'document', 'sparkle'];

export type PresentationTone = (typeof tones)[number];

export function projectTone(projectId: number): PresentationTone {
  return tones[Math.abs(projectId) % tones.length];
}

export function projectIcon(projectId: number): A12AssetIconName {
  return icons[Math.abs(projectId) % icons.length];
}

export function formatDateTime(value?: string) {
  if (!value) return '暂无';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}

export function formatFullDateTime(value?: string) {
  if (!value) return '暂无';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
}

export function formatRelativeTime(value?: string) {
  if (!value) return '暂无';
  const timestamp = new Date(value).getTime();
  if (Number.isNaN(timestamp)) return value;
  const seconds = Math.max(0, Math.round((Date.now() - timestamp) / 1000));
  if (seconds < 60) return '刚刚';
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
  if (seconds < 604800) return `${Math.floor(seconds / 86400)} 天前`;
  return formatDateTime(value);
}

export function stageTone(stage?: string): 'purple' | 'green' | 'orange' | 'blue' | 'red' {
  if (stage === 'FINALIZED' || stage === 'INTENT_CONFIRMED') return 'green';
  if (stage === 'MATERIAL_ANALYZING' || stage === 'KNOWLEDGE_INDEXED') return 'blue';
  if (stage === 'CONTENT_GENERATED') return 'purple';
  if (stage === 'FAILED') return 'red';
  return 'orange';
}

export function formatBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** index).toFixed(index === 0 ? 0 : 1)} ${units[index]}`;
}
