import { http } from './http';
import type { ApiResponse } from './health';

export type MissionStatus = 'ASSIGNED' | 'ACCEPTED' | 'REJECTED' | 'SUBMITTED';
export interface LessonForgeMessageApi { id: number; role: string; content: string; createdAt: string; }
export interface LessonForgeFileApi { id: number; name: string; contentType: string; extension: string; size: number; sha256: string; kind: 'MATERIAL' | 'TEMPLATE' | 'TEACHER_ASSET'; uploadedAt: string; downloadUrl: string; }
export interface LessonForgeSubmissionApi { id: number; fileName: string; contentType: string; size: number; sha256: string; status: 'SUBMITTED' | 'REVIEWED'; reviewerId: number; rating?: number | null; reviewNote?: string | null; submittedAt: string; reviewedAt?: string | null; downloadUrl: string; }
export interface LessonForgePlanApi { version: string; pages: number; status: 'DRAFT' | 'LOCKED'; summary: string; sections: string[]; }
export interface LessonForgeOutputApi { name: string; pages: number; version: string; sizeLabel: string; }
export interface LessonForgeQuestionApi { current: number; total: number; prompt: string; options: string[]; }
export interface LessonForgeGenerationApi { status: 'GENERATING' | 'SUCCEEDED'; detail?: string; }
export interface LessonForgeActivityApi { label: string; detail: string; time: string; tone?: 'default' | 'success' | 'warning'; }
export interface LessonForgeMissionApi { id: number; title: string; description: string; assignedTeacherId: number; createdByLeaderId: number; deadline?: string | null; status: MissionStatus; rejectionReason?: string | null; selectedModelConnectionId?: number | null; conversationId?: number | null; messages: LessonForgeMessageApi[]; contextFiles: LessonForgeFileApi[]; submissions: LessonForgeSubmissionApi[]; teacherName?: string | null; leaderName?: string | null; question?: LessonForgeQuestionApi | null; plan?: LessonForgePlanApi | null; output?: LessonForgeOutputApi | null; generation?: LessonForgeGenerationApi | null; activities?: LessonForgeActivityApi[] | null; }
export interface TeacherApi { id: number; username: string; displayName: string; }
const base = '/api/v1/lessonforge';
async function unwrap<T>(request: Promise<{ data: ApiResponse<T> }>) { const response = (await request).data; if (response.code !== 0) throw new Error(response.message); return response.data; }
export const listLessonForgeMissions = () => unwrap(http.get<ApiResponse<LessonForgeMissionApi[]>>(`${base}/missions`));
export const getLessonForgeMission = (id: number | string) => unwrap(http.get<ApiResponse<LessonForgeMissionApi>>(`${base}/missions/${id}`));
export const listLessonForgeTeachers = () => unwrap(http.get<ApiResponse<TeacherApi[]>>(`${base}/teachers`));
export const createLessonForgeMission = (payload: { title: string; description: string; assignedTeacherId: number; deadline?: string }) => unwrap(http.post<ApiResponse<LessonForgeMissionApi>>(`${base}/missions`, payload));
export const acceptLessonForgeMission = (id: number | string) => unwrap(http.post<ApiResponse<LessonForgeMissionApi>>(`${base}/missions/${id}/accept`, {}));
export const rejectLessonForgeMission = (id: number | string, reason: string) => unwrap(http.post<ApiResponse<LessonForgeMissionApi>>(`${base}/missions/${id}/reject`, { reason }));
export const selectLessonForgeConnection = (id: number | string, connectionId: number) => unwrap(http.put<ApiResponse<LessonForgeMissionApi>>(`${base}/missions/${id}/model-connection`, { connectionId }));
export const sendLessonForgeMessage = (id: number | string, content: string) => unwrap(http.post<ApiResponse<LessonForgeMessageApi>>(`${base}/missions/${id}/conversation/messages`, { content }));
export async function uploadLessonForgeContextFile(id: number | string, file: File, kind?: string) { const form = new FormData(); form.append('file', file); if (kind) form.append('kind', kind); return unwrap(http.post<ApiResponse<LessonForgeFileApi>>(`${base}/missions/${id}/context-files`, form)); }
export const deleteLessonForgeContextFile = (id: number | string, fileId: number) => unwrap(http.delete<ApiResponse<void>>(`${base}/missions/${id}/context-files/${fileId}`));
export async function submitLessonForgePptx(id: number | string, file: File) { const form = new FormData(); form.append('file', file); return unwrap(http.post<ApiResponse<LessonForgeSubmissionApi>>(`${base}/missions/${id}/submissions`, form)); }
export const reviewLessonForgeSubmission = (id: number | string, submissionId: number, rating: number, note?: string) => unwrap(http.post<ApiResponse<LessonForgeSubmissionApi>>(`${base}/missions/${id}/submissions/${submissionId}/review`, { rating, note }));
