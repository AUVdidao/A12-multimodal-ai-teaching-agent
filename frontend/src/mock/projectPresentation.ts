import type { A12AssetIconName } from '@/components/ui/A12AssetIcon.vue';

export type ProjectTone = 'purple' | 'green' | 'orange' | 'blue' | 'red';

export interface ProjectPresentation {
  icon: A12AssetIconName;
  tone: ProjectTone;
  subtitle: string;
  updatedLabel: string;
}

export const projectPresentation: Record<number, ProjectPresentation> = {
  1: { icon: 'atom', tone: 'purple', subtitle: '大一 · 人工智能', updatedLabel: '1 小时前' },
  2: { icon: 'sprout', tone: 'green', subtitle: '初一 · 生物', updatedLabel: '3 小时前' },
  3: { icon: 'math', tone: 'orange', subtitle: '高一 · 数学', updatedLabel: '昨天' },
  4: { icon: 'flask', tone: 'blue', subtitle: '初三 · 化学', updatedLabel: '2 天前' },
  5: { icon: 'letter-a', tone: 'red', subtitle: '高二 · 语文', updatedLabel: '3 天前' },
};
