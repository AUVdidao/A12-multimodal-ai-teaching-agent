import { defineStore } from 'pinia';
import {
  acceptLessonForgeMission, createLessonForgeMission, getLessonForgeMission, listLessonForgeMissions,
  rejectLessonForgeMission, selectLessonForgeConnection, sendLessonForgeMessage, submitLessonForgePptx,
  uploadLessonForgeContextFile, type LessonForgeActivityApi, type LessonForgeMissionApi,
} from '../api/lessonForge';
import type { LessonForgeActivity, LessonForgeGeneration, LessonForgeMission, LessonForgeMissionStatus, LessonForgeOutput, LessonForgePhase, LessonForgePlan, LessonForgeSurfaceGate } from '../types/lessonForge';

function displayStatus(status: string): LessonForgeMissionStatus {
  switch (status) {
    case 'ASSIGNED': return 'ASSIGNED';
    case 'ACCEPTED': return 'IN_PROGRESS';
    case 'REJECTED': return 'FEEDBACK';
    case 'SUBMITTED': return 'SUBMITTED';
    default: throw new Error(`UNSUPPORTED_MISSION_STATUS:${status}`);
  }
}
function sizeLabel(size: number) { if (!size) return undefined; if (size < 1024 * 1024) return `${Math.ceil(size / 1024)} KB`; return `${(size / 1024 / 1024).toFixed(1)} MB`; }
function clock(value?: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '—'; }
function isRecord(value: unknown): value is Record<string, unknown> { return typeof value === 'object' && value !== null; }
function isText(value: unknown): value is string { return typeof value === 'string' && value.trim().length > 0; }
function mapPlan(value: unknown): LessonForgePlan | undefined {
  if (!isRecord(value) || !isText(value.version) || typeof value.pages !== 'number' || !Number.isInteger(value.pages) || value.pages < 0 || (value.status !== 'DRAFT' && value.status !== 'LOCKED') || !isText(value.summary) || !Array.isArray(value.sections) || !value.sections.every(isText)) return undefined;
  return { version: value.version, pages: value.pages, status: value.status, summary: value.summary, sections: value.sections };
}
function mapQuestion(value: unknown): LessonForgeMission['question'] {
  if (!isRecord(value) || !Number.isInteger(value.current) || !Number.isInteger(value.total) || isText(value.prompt) === false || !Array.isArray(value.options) || !value.options.every(isText)) return undefined;
  return { current: value.current as number, total: value.total as number, prompt: value.prompt as string, options: value.options as string[] };
}
function mapOutput(value: unknown): LessonForgeOutput | undefined {
  if (!isRecord(value) || !isText(value.name) || typeof value.pages !== 'number' || !Number.isInteger(value.pages) || value.pages < 0 || !isText(value.version) || !isText(value.sizeLabel)) return undefined;
  return { name: value.name, pages: value.pages, version: value.version, sizeLabel: value.sizeLabel };
}
function mapGeneration(value: unknown): LessonForgeGeneration | undefined {
  if (!isRecord(value) || (value.status !== 'GENERATING' && value.status !== 'SUCCEEDED')) return undefined;
  return { status: value.status, detail: isText(value.detail) ? value.detail : undefined };
}
function isActivity(value: unknown): value is LessonForgeActivityApi {
  return isRecord(value) && isText(value.label) && isText(value.detail) && isText(value.time) && (value.tone === undefined || value.tone === 'default' || value.tone === 'success' || value.tone === 'warning');
}
function mapActivities(value: unknown): LessonForgeActivity[] {
  if (!Array.isArray(value) || !value.every(isActivity)) return [];
  return value.map(activity => ({ label: activity.label, detail: activity.detail, time: activity.time, tone: activity.tone }));
}
type PhaseResolution = Pick<LessonForgeMission, 'currentPhase' | 'phaseMessage' | 'question' | 'plan' | 'output' | 'generation'>;
export function resolveLessonForgeSurfaceGate(phase: LessonForgePhase, hasValidOutput: boolean, hasFeedback: boolean): LessonForgeSurfaceGate {
  const completed = phase === 'SUCCEEDED' && hasValidOutput;
  return { submission: completed, feedback: completed && hasFeedback };
}
function resolvePhase(input: { question?: LessonForgeMission['question']; plan?: LessonForgePlan; output?: LessonForgeOutput; generation?: LessonForgeGeneration; invalidFields: string[] }): PhaseResolution {
  const { question, plan, output, generation, invalidFields } = input;
  if (invalidFields.length) return { currentPhase: 'CONFLICT', phaseMessage: `阶段数据不可用：${invalidFields.join('、')}字段格式无效。` };
  if (question) {
    if (plan || generation || output) return { currentPhase: 'CONFLICT', phaseMessage: '阶段数据冲突：QUESTION 不得与 Plan、Generation 或 Output 并存。' };
    return { currentPhase: 'QUESTION', question };
  }
  if (generation?.status === 'GENERATING') {
    if (plan || output) return { currentPhase: 'CONFLICT', phaseMessage: '阶段数据冲突：GENERATING 不得与 Plan 或 Output 并存。' };
    return { currentPhase: 'GENERATING', generation };
  }
  if (plan) {
    if (generation || output) return { currentPhase: 'CONFLICT', phaseMessage: '阶段数据冲突：Plan 不得与 Generation 或 Output 并存。' };
    return plan.status === 'LOCKED' ? { currentPhase: 'LOCKED', plan } : { currentPhase: 'DRAFT', plan };
  }
  if (output) {
    return { currentPhase: 'SUCCEEDED', output };
  }
  if (generation?.status === 'SUCCEEDED') return { currentPhase: 'CONFLICT', phaseMessage: '阶段数据不可用：SUCCEEDED 缺少 PPT Output。' };
  return { currentPhase: 'UNAVAILABLE', phaseMessage: '等待服务端阶段数据。' };
}
export function mapLessonForgeMission(item: LessonForgeMissionApi): LessonForgeMission {
  const submissions = item.submissions.map(submission => ({ id: String(submission.id), fileName: submission.fileName, reviewerId: String(submission.reviewerId), reviewerName: item.leaderName || '负责人', status: submission.status === 'REVIEWED' ? 'REVIEWED' as const : 'SUBMISSION_PENDING' as const, submittedAt: clock(submission.submittedAt), source: 'SERVER' as const, size: submission.size, rating: submission.rating, reviewNote: submission.reviewNote, reviewedAt: submission.reviewedAt, downloadUrl: submission.downloadUrl }));
  const plan = mapPlan(item.plan);
  const output = mapOutput(item.output);
  const question = mapQuestion(item.question);
  const generation = mapGeneration(item.generation);
  const invalidFields = [
    item.question != null && !question ? 'Question' : '',
    item.plan != null && !plan ? 'Plan' : '',
    item.generation != null && !generation ? 'Generation' : '',
    item.output != null && !output ? 'Output' : '',
  ].filter(Boolean);
  const phase = resolvePhase({ question, plan, output, generation, invalidFields });
  const feedback = submissions.find(s => s.reviewNote)?.reviewNote || undefined;
  const surfaceGate = resolveLessonForgeSurfaceGate(phase.currentPhase, Boolean(phase.output), Boolean(feedback));
  const messages = item.messages.map(message => ({ id: String(message.id), role: message.role === 'TEACHER' ? 'teacher' as const : 'assistant' as const, body: message.content, time: clock(message.createdAt) }));
  const activities = mapActivities(item.activities);
  if (!activities.length) activities.push({ label: '展示回退', detail: '暂无服务端 Activity（非真实业务事件）', time: '—', tone: 'default' });
  return { id: String(item.id), teacherId: item.assignedTeacherId, title: item.title, description: item.description, status: displayStatus(item.status), selectedConnectionId: item.selectedModelConnectionId, ...phase, surfaceGate, deadline: item.deadline ? item.deadline.slice(0, 10) : undefined, recentActivity: clock(item.submissions[0]?.submittedAt), sources: item.contextFiles.map(file => ({ id: String(file.id), name: file.name, kind: file.kind, sizeLabel: sizeLabel(file.size) })), messages, activities, feedback, submissions, submission: submissions[0], review: submissions[0]?.rating ? { reviewerName: item.leaderName || '负责人', rating: submissions[0].rating, comment: submissions[0].reviewNote || '', submittedFileName: submissions[0].fileName, reviewedAt: clock(submissions[0].reviewedAt) } : undefined };
}
export const useLessonForgeStore = defineStore('lessonForge', {
  state: () => ({ teacherId: null as number | null, missions: [] as LessonForgeMission[], currentMissionId: '', loading: false, error: '' }),
  getters: {
    currentMission: state => state.missions.find(mission => mission.id === state.currentMissionId),
    missionsForViewer: state => (userId: number | null, role?: string) => {
      if (!userId || state.teacherId !== userId) return [];
      return role === 'TEACHER' ? state.missions.filter(mission => mission.teacherId === userId) : state.missions;
    },
    recentMissionsForViewer: state => (userId: number | null, role?: string) => {
      if (!userId || state.teacherId !== userId) return [];
      const missions = role === 'TEACHER' ? state.missions.filter(mission => mission.teacherId === userId) : state.missions;
      return missions.filter(mission => mission.status === 'IN_PROGRESS' || mission.status === 'FEEDBACK').slice(0, 5);
    },
  },
  actions: {
    async load(userId: number) { this.teacherId = userId; this.loading = true; this.error = ''; try { const missions = (await listLessonForgeMissions()).map(mapLessonForgeMission); if (this.teacherId !== userId) return; this.missions = missions; if (!this.currentMissionId || !this.missions.some(m => m.id === this.currentMissionId)) this.currentMissionId = this.missions[0]?.id || ''; } catch (error) { if (this.teacherId !== userId) return; this.error = error instanceof Error ? error.message : 'LessonForge API unavailable'; this.missions = []; } finally { if (this.teacherId === userId) this.loading = false; } },
    async loadMission(id: string) { const mission = mapLessonForgeMission(await getLessonForgeMission(Number(id))); this.replace(mission); return mission; },
    selectMission(id: string) { if (this.missions.some(m => m.id === id)) this.currentMissionId = id; },
    async createMission(title: string, description: string, assignedTeacherId: number) { const mission = mapLessonForgeMission(await createLessonForgeMission({ title, description, assignedTeacherId })); this.replace(mission); return mission; },
    async accept(id: string) { const mission = mapLessonForgeMission(await acceptLessonForgeMission(Number(id))); this.replace(mission); return mission; },
    async reject(id: string, reason: string) { const mission = mapLessonForgeMission(await rejectLessonForgeMission(Number(id), reason)); this.replace(mission); return mission; },
    async selectConnection(id: string, connectionId: number) { const mission = mapLessonForgeMission(await selectLessonForgeConnection(Number(id), connectionId)); this.replace(mission); return mission; },
    async sendMessage(id: string, text: string, files: File[] = []) { const mission = this.missions.find(item => item.id === id); if (mission?.status === 'ASSIGNED') return mission; for (const file of files) await uploadLessonForgeContextFile(Number(id), file); await sendLessonForgeMessage(Number(id), text); return this.loadMission(id); },
    async submitFinalDeck(id: string, file: File) { await submitLessonForgePptx(Number(id), file); return this.loadMission(id); },
    reset() { this.teacherId = null; this.missions = []; this.currentMissionId = ''; this.loading = false; this.error = ''; },
    replace(mission: LessonForgeMission) { const index = this.missions.findIndex(item => item.id === mission.id); if (index >= 0) this.missions[index] = mission; else this.missions.unshift(mission); this.currentMissionId = mission.id; },
  },
});
