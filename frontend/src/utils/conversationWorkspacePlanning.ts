import type { ModelConnection } from '@/api/aiCredentials';
import type { TeachingProject } from '@/api/projects';
import type { ConfirmedContextReference, PlanningRequestPayload } from '@/api/planning';
import type { TeachingRequirement } from '@/api/requirements';
import type { TeachingIntent } from '@/api/teachingIntents';
import type { TemplateDetail } from '@/api/templates';

export function findExecutableConnection(connections: ModelConnection[], id?: number | null) {
  return connections.find((connection) => connection.id === id
    && connection.enabled
    && connection.verificationStatus === 'VERIFIED') || null;
}

export interface ConversationPlanningRequestSnapshot {
  userId: number | null;
  sessionId: string;
  projectId: number;
  modelConnectionId: number;
}

export interface ConversationPlanningRequestState {
  userId: number | null;
  sessionId: string;
  projectId?: number;
  selectedConnectionId: number | null;
  connections: ModelConnection[];
}

export function isConversationPlanningRequestCurrent(
  snapshot: ConversationPlanningRequestSnapshot,
  current: ConversationPlanningRequestState,
) {
  return current.userId === snapshot.userId
    && current.sessionId === snapshot.sessionId
    && current.projectId === snapshot.projectId
    && current.selectedConnectionId === snapshot.modelConnectionId
    && findExecutableConnection(current.connections, current.selectedConnectionId)?.id === snapshot.modelConnectionId;
}

export function findConfirmedTemplate(templateDetails: TemplateDetail[]) {
  return templateDetails.find((detail) => detail.profiles.some((profile) => profile.status === 'CONFIRMED')) || null;
}

export function buildConversationPlanningRequest(input: {
  project: TeachingProject | null;
  requirement: TeachingRequirement | null;
  intent: TeachingIntent | null;
  confirmedContext: ConfirmedContextReference | null;
  template: TemplateDetail | null;
  modelConnectionId: number | null;
  teacherInstruction: string;
}): PlanningRequestPayload | null {
  const { project, requirement, intent, confirmedContext, template, modelConnectionId } = input;
  const profile = template?.profiles.find((item) => item.status === 'CONFIRMED');
  const objectives = intent?.generationGoals?.map((item) => item.trim()).filter(Boolean) ?? [];
  const outline = confirmedContext?.confirmedPageOutline?.map((item) => item.title.trim()).filter(Boolean) ?? [];
  const lessonPlan = intent?.teachingApproach?.trim() ? [intent.teachingApproach.trim()] : [];
  const topic = requirement?.topic?.trim() || '';
  const confirmedPageCount = confirmedContext?.confirmedPageCount;

  if (!project?.id || !project.courseName?.trim() || !topic || intent?.status !== 'CONFIRMED'
    || !intent.id || objectives.length === 0 || !confirmedContext?.revision
    || !confirmedContext.checksum || !confirmedPageCount || confirmedPageCount < 1
    || !template?.template.id || !profile || !modelConnectionId) {
    return null;
  }

  return {
    modelConnectionId,
    templateId: template.template.id,
    templateProfileVersionId: profile.id,
    operation: 'INITIAL_PROPOSAL',
    baseSpecificationVersion: null,
    baseSpecificationChecksum: null,
    confirmedContextVersion: confirmedContext.revision,
    targetSlideCount: confirmedPageCount,
    slideCountTolerance: 0,
    locale: 'zh-CN',
    teachingContext: {
      courseName: project.courseName.trim(),
      topic,
      teachingObjectives: objectives,
      outline,
      lessonPlan,
      teacherConfirmed: true,
    },
    evidence: (intent.evidenceItems ?? []).map((item) => ({
      sourceId: `material:${item.materialId ?? 'none'}:chunk:${item.knowledgeChunkId ?? 'none'}`,
      sourceType: 'CONFIRMED_TEACHING_INTENT',
      excerpt: item.contentExcerpt?.trim() || 'confirmed-evidence',
    })),
    teacherInstruction: input.teacherInstruction.trim() || undefined,
    explicitTeacherTrigger: true,
  };
}
