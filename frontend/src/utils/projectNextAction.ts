import type { ProjectStatus } from '@/api/projects';

export interface ProjectNextAction {
  label: string;
  path: string;
}

export function getProjectListNextAction(projectId: number, status: ProjectStatus | string): ProjectNextAction {
  const overview = { label: '查看项目概览', path: `/projects/${projectId}` };
  const actions: Record<ProjectStatus, ProjectNextAction> = {
    CREATED: { label: '继续项目设置', path: `/projects/${projectId}/mode` },
    REQUIREMENT_CONFIRMED: { label: '上传参考资料', path: `/projects/${projectId}/materials` },
    MATERIAL_READY: { label: '查看知识库', path: `/projects/${projectId}/knowledge` },
    INTENT_CONFIRMED: { label: '生成教学内容', path: `/projects/${projectId}/plan` },
    GENERATED: { label: '预览生成成果', path: `/projects/${projectId}/preview` },
    FINALIZED: overview,
  };
  return actions[status as ProjectStatus] || overview;
}
