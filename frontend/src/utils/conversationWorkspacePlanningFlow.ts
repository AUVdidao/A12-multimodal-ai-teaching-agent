import type { ConfirmedContextReference, PlanningRequestPayload, PlanningResponse } from '@/api/planning';
import type { TeachingProject } from '@/api/projects';
import type { TeachingRequirement } from '@/api/requirements';
import type { TeachingIntent } from '@/api/teachingIntents';
import type { TemplateDetail, TemplateSummary } from '@/api/templates';
import {
  buildConversationPlanningRequest,
  findConfirmedTemplate,
  isConversationPlanningRequestCurrent,
  type ConversationPlanningRequestSnapshot,
  type ConversationPlanningRequestState,
} from './conversationWorkspacePlanning';

export interface ConversationPlanningFlowInput {
  project: TeachingProject;
  requirement: TeachingRequirement | null;
  requestSnapshot: ConversationPlanningRequestSnapshot;
  teacherInstruction: string;
  currentState: () => ConversationPlanningRequestState;
  getPlanningConfirmedContext: (projectId: number) => Promise<ConfirmedContextReference>;
  getLatestTeachingIntent: (projectId: number) => Promise<TeachingIntent | null>;
  listTemplates: (projectId: number) => Promise<TemplateSummary[]>;
  getTemplate: (projectId: number, templateId: number) => Promise<TemplateDetail>;
  createPlanningProposal: (projectId: number, payload: PlanningRequestPayload) => Promise<PlanningResponse>;
  onBlocked: (message: string) => void;
  onStale: (message: string) => void;
}

export async function executeConversationPlanning(input: ConversationPlanningFlowInput) {
  const isCurrentRequest = () => isConversationPlanningRequestCurrent(input.requestSnapshot, input.currentState());

  try {
    const [confirmedContext, intent, templateSummaries] = await Promise.all([
      input.getPlanningConfirmedContext(input.requestSnapshot.projectId),
      input.getLatestTeachingIntent(input.requestSnapshot.projectId),
      input.listTemplates(input.requestSnapshot.projectId),
    ]);
    if (!isCurrentRequest()) {
      input.onStale('当前会话或用户已切换，已阻断过期 Planning 请求。');
      return null;
    }

    const templateDetails = await Promise.all(templateSummaries.map((summary) => input.getTemplate(input.requestSnapshot.projectId, summary.id)));
    if (!isCurrentRequest()) {
      input.onStale('模板读取期间当前会话或连接已切换，已丢弃过期 Planning 请求。');
      return null;
    }

    const payload = buildConversationPlanningRequest({
      project: input.project,
      requirement: input.requirement,
      intent,
      confirmedContext,
      template: findConfirmedTemplate(templateDetails),
      modelConnectionId: input.requestSnapshot.modelConnectionId,
      teacherInstruction: input.teacherInstruction,
    });
    if (!payload) {
      input.onBlocked('当前项目缺少服务端已确认上下文或 CONFIRMED Template Profile，未发送 Planning 请求。');
      return null;
    }

    const response = await input.createPlanningProposal(input.requestSnapshot.projectId, payload);
    if (!isCurrentRequest()) {
      input.onStale('Planning 返回时当前会话或用户已切换，已丢弃旧响应。');
      return null;
    }
    return response;
  } catch (error) {
    if (!isCurrentRequest()) {
      input.onStale('Planning 返回错误时当前会话或用户已切换，已丢弃旧错误。');
      return null;
    }
    throw error;
  }
}
