import axios, { type AxiosError, type AxiosInstance } from 'axios';
import { goApiBaseUrl } from '@/config/runtime';
import { normalizeGoMissionCollections } from '@/utils/goMissionDetail';

export { goApiBaseUrl } from '@/config/runtime';

export interface GoUser {
  id: number;
  name: string;
  email: string;
  role: 'TEACHER' | 'RESEARCHER';
}

export interface GoAuthResponse {
  user: GoUser;
  expiresAt?: string;
  token?: string;
}

export interface GoModelConnection {
  id: number;
  name: string;
  protocol: 'OPENAI_COMPATIBLE';
  baseUrl: string;
  modelId: string;
  provider?: string;
  capabilities: GoModelCapabilities;
  capabilityVerification?: GoCapabilityVerification;
  keyHint: string;
  enabled: boolean;
  verificationStatus: 'UNVERIFIED' | 'VERIFIED' | 'INVALID';
  lastVerifiedAt?: string | null;
  lastUsedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface GoModelCapabilities {
  supportsChat: boolean;
  supportsTools: boolean;
  supportsJSONMode: boolean;
  supportsVision: boolean;
  supportsEmbeddings: boolean;
  embeddingDimension?: number;
  supportsStreaming: boolean;
}

export interface GoCapabilityVerification {
  supportsChat: 'DECLARED' | 'VERIFIED' | 'UNSUPPORTED';
  supportsTools: 'DECLARED' | 'VERIFIED' | 'UNSUPPORTED';
  supportsJSONMode: 'DECLARED' | 'VERIFIED' | 'UNSUPPORTED';
  supportsVision: 'DECLARED' | 'VERIFIED' | 'UNSUPPORTED';
  supportsEmbeddings: 'DECLARED' | 'VERIFIED' | 'UNSUPPORTED';
  supportsStreaming: 'DECLARED' | 'VERIFIED' | 'UNSUPPORTED';
}

export type GoModelRole = 'PLANNING' | 'TEMPLATE_VISION' | 'EMBEDDING';
export interface GoModelConnectionBinding {
  role: GoModelRole;
  modelConnectionId: number;
  updatedAt: string;
}

export interface GoConnectionVerification {
  connectionId: number;
  status: 'UNVERIFIED' | 'VERIFIED' | 'INVALID';
  safeCode: string;
  httpStatus: number;
  baseUrlHost: string;
  modelId: string;
  capabilities?: {
    normalChat: boolean;
    toolCalling: boolean;
    jsonMode: boolean;
    vision: boolean;
    visionProbed: boolean;
    embeddings: boolean;
    embeddingsProbed: boolean;
    embeddingDimension: number;
  };
  verifiedAt: string;
}

export interface GoMission {
  id: number;
  ownerTeacherId: number;
  source: string;
  title: string;
  description: string;
  deadline?: string | null;
  status: string;
  selectedModelConnectionId?: number | null;
  createdAt: string;
  updatedAt: string;
}

export type GoFeedbackSeverity = 'INFO' | 'IMPORTANT' | 'BLOCKING';

export interface GoMissionFeedbackItem {
  slideNumber?: number;
  slideTitle: string;
  severity: GoFeedbackSeverity;
  comment: string;
}

export interface GoMissionFeedback {
  id: string;
  missionId: number;
  reviewerName: string;
  submissionVersion: number;
  rating: number;
  summary: string;
  items: GoMissionFeedbackItem[];
  createdAt: string;
}

export interface GoMessage {
  id: number;
  missionId: number;
  ownerUserId: number;
  agentRunId?: string;
  outputStage?: string;
  referenceType?: string;
  referenceId?: string;
  role: string;
  content: string;
  messageType: string;
  structuredPayload?: unknown;
  createdAt: string;
}

export interface GoFileObject {
  id: number;
  originalName: string;
  mimeType: string;
  size: number;
  sha256: string;
}

export interface GoMissionFile {
  id: number;
  missionId: number;
  file: GoFileObject;
  role: string;
  provenance: string;
  parseStatus: string;
  createdAt: string;
}

export interface GoPlanningDraft {
  id: string;
  missionId: number;
  version: number;
  markdown: string;
  structuredPlan: unknown;
  outputStage: string;
  createdAt: string;
}

export interface GoLockedSpecification {
  id: string;
  missionId: number;
  sourceDraftId: string;
  version: number;
  specification: unknown;
  templateBinding: unknown;
  contentHash: string;
  createdAt: string;
}

export interface GoGenerationJob {
  id: string;
  missionId: number;
  specificationId: string;
  specificationVersion: number;
  generationMode?: 'TEACHER_TEMPLATE' | 'SYSTEM_DEFAULT_TEMPLATE' | string;
  fallbackReasons?: string[];
  status: string;
  currentSlide: number;
  totalSlides: number;
  artifactId?: string | null;
  generationFeedback?: unknown;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
}

export interface GoArtifact {
  id: string;
  missionId: number;
  generationJobId: string;
  file: GoFileObject;
  version: number;
  contentType: string;
  sha256: string;
  size: number;
  status?: string;
  createdAt: string;
}

export interface GoActivityEvent {
  id: number;
  missionId: number;
  eventType: string;
  summary: string;
  referenceType?: string;
  referenceId?: string;
  createdAt: string;
}

export type GoQuestionType = 'TEXT' | 'SINGLE_CHOICE' | 'MULTI_CHOICE';

export interface GoQuestionAnswer {
  id: string;
  selectedValues: string[];
  textAnswer: string;
  answeredAt: string;
}

export interface GoQuestion {
  id: string;
  missionId: number;
  ownerUserId: number;
  agentRunId: string;
  outputStage: string;
  referenceType: string;
  referenceId: string;
  text: string;
  type: GoQuestionType;
  options?: string[] | null;
  latestAnswer: GoQuestionAnswer | null;
  createdAt: string;
}

export interface GoMissionDetail {
  mission: GoMission;
  messages: GoMessage[];
  files: GoMissionFile[];
  currentDraft?: GoPlanningDraft | null;
  lockedSpecification?: GoLockedSpecification | null;
  generationJobs: GoGenerationJob[];
  artifacts: GoArtifact[];
  feedback: GoMissionFeedback[];
}

export interface GoGenerationJobResponse {
  generationJob: GoGenerationJob;
  created: boolean;
}

export interface GoReviewMissionDetail {
  mission: GoMission;
  currentDraft?: GoPlanningDraft | null;
  feedback: GoMissionFeedback[];
}

export interface GoMissionCreateResponse {
  missionId: number;
  messageId: number;
  agentRunId: string;
  status: string;
}

export interface GoUploadResponse {
  uploadId: string;
  file: { name: string; mimeType: string; size: number; sha256: string };
}

export interface GoMessageResponse {
  messageId: number;
  agentRunId: string;
  status: string;
}

export interface GoAgentRun {
  id: string;
  missionId: number;
  ownerUserId: number;
  status: 'QUEUED' | 'WAITING_INPUTS' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED' | string;
  modelConnectionId?: number | null;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  createdAt: string;
}

export interface GoErrorBody {
  error?: { code?: string };
}

export const goHttp: AxiosInstance = axios.create({
  baseURL: goApiBaseUrl,
  timeout: 30000,
  withCredentials: true,
  headers: { Accept: 'application/json' },
});

export function goErrorCode(error: unknown) {
  const response = (error as AxiosError<GoErrorBody> | undefined)?.response;
  return response?.data?.error?.code || (error as AxiosError | undefined)?.code || 'GO_REQUEST_FAILED';
}

export function goErrorMessage(error: unknown, fallback = '暂时无法连接 LessonForge 服务。') {
  const response = (error as AxiosError<GoErrorBody> | undefined)?.response;
  const code = response?.data?.error?.code;
  if (code === 'TEMPLATE_BINDING_REQUIRED') return '当前模板绑定不可用；如未上传模板，批准后将使用系统默认版式。';
  if (code === 'SPECIFICATION_PLAN_INVALID') return '课件方案结构无效，请重新生成方案草稿。';
  if (code === 'SPECIFICATION_FORBIDDEN_FIELD') return '课件方案包含不允许的执行字段，请重新生成方案草稿。';
  if (code) return `请求未完成（${code}）。`;
  return fallback;
}

export async function goLogin(payload: { email: string; password: string }) {
  const { data } = await goHttp.post<GoAuthResponse>('/api/auth/login', payload);
  return data;
}

export async function goRegister(payload: { name: string; email: string; password: string; role: 'TEACHER' | 'RESEARCHER' }) {
  const { data } = await goHttp.post<GoAuthResponse>('/api/auth/register', payload);
  return data;
}

export async function goMe() {
  const { data } = await goHttp.get<{ user: GoUser }>('/api/auth/me');
  return data.user;
}

export async function goLogout() {
  await goHttp.post('/api/auth/logout');
}

export async function listGoModelConnections() {
  const { data } = await goHttp.get<GoModelConnection[]>('/api/model-connections');
  return data;
}

export async function createGoModelConnection(payload: { name: string; protocol: 'OPENAI_COMPATIBLE'; baseUrl: string; modelId: string; apiKey: string; capabilities?: GoModelCapabilities }) {
  const { data } = await goHttp.post<GoModelConnection>('/api/model-connections', payload);
  return data;
}

export async function updateGoModelConnection(id: number, payload: { name: string; protocol: 'OPENAI_COMPATIBLE'; baseUrl: string; modelId: string; apiKey?: string; capabilities?: GoModelCapabilities }) {
  const { data } = await goHttp.put<GoModelConnection>(`/api/model-connections/${id}`, payload);
  return data;
}

export async function deleteGoModelConnection(id: number) {
  await goHttp.delete(`/api/model-connections/${id}`);
}

export async function setGoModelConnectionEnabled(id: number, enabled: boolean) {
  const { data } = await goHttp.post<GoModelConnection>(`/api/model-connections/${id}/enabled?enabled=${enabled}`, {});
  return data;
}

export async function verifyGoModelConnection(id: number) {
  const { data } = await goHttp.post<GoConnectionVerification>(`/api/model-connections/${id}/verify`);
  return data;
}

export async function listGoModelConnectionBindings() {
  const { data } = await goHttp.get<GoModelConnectionBinding[]>('/api/model-connection-bindings');
  return Array.isArray(data) ? data : [];
}

export async function setGoModelConnectionBinding(role: GoModelRole, connectionId: number) {
  const { data } = await goHttp.put<GoModelConnectionBinding>(`/api/model-connection-bindings/${role}`, { connectionId });
  return data;
}

export async function deleteGoModelConnectionBinding(role: GoModelRole) {
  await goHttp.delete(`/api/model-connection-bindings/${role}`);
}

export async function listGoMissions() {
  const { data } = await goHttp.get<GoMission[]>('/api/missions');
  return Array.isArray(data) ? data : [];
}

export async function getGoMission(id: number) {
  const { data } = await goHttp.get<GoMissionDetail>(`/api/missions/${id}`);
  return normalizeGoMissionDetail(data);
}

export async function listGoResearcherMissions() {
  const { data } = await goHttp.get<GoMission[]>('/api/researcher/missions');
  return Array.isArray(data) ? data : [];
}

export async function getGoResearcherMission(id: number) {
  const { data } = await goHttp.get<GoReviewMissionDetail>(`/api/researcher/missions/${id}`);
  return data;
}

export async function createGoMissionFeedback(id: number, payload: { submissionVersion: number; rating: number; summary: string; items: GoMissionFeedbackItem[] }) {
  const { data } = await goHttp.post<GoMissionFeedback>(`/api/researcher/missions/${id}/feedback`, payload);
  return data;
}

export async function listGoMissionQuestions(id: number) {
  const { data } = await goHttp.get<GoQuestion[] | null>(`/api/missions/${id}/questions`);
  return Array.isArray(data) ? data : [];
}

export async function listGoMissionAgentRuns(id: number) {
  const { data } = await goHttp.get<GoAgentRun[] | null>(`/api/missions/${id}/agent-runs`);
  return Array.isArray(data) ? data : [];
}

export async function answerGoQuestion(questionId: string, payload: { selectedValues: string[]; textAnswer: string }) {
  const { data } = await goHttp.post<{ answerId: string; agentRunId: string }>(`/api/questions/${questionId}/answers`, payload);
  return data;
}

export function normalizeGoMissionDetail(data: GoMissionDetail): GoMissionDetail {
  return normalizeGoMissionCollections(data) as GoMissionDetail;
}

export async function createGoMission(payload: { title?: string; description?: string; message: string; uploadIds?: string[]; modelConnectionId?: number | null }) {
  const { data } = await goHttp.post<GoMissionCreateResponse>('/api/missions', payload);
  return data;
}

export async function sendGoMissionMessage(id: number, content: string) {
  const { data } = await goHttp.post<GoMessageResponse>(`/api/missions/${id}/messages`, { content });
  return data;
}

export async function approveGoPlanningDraft(draftId: string) {
  const { data } = await goHttp.post<{ lockedSpecification: GoLockedSpecification }>(`/api/planning/${draftId}/approve`, {});
  return data.lockedSpecification;
}

export async function createGoGenerationJob(id: number, specificationId: string, specificationVersion: number, fallbackPolicy = 'AUTO') {
  const { data } = await goHttp.post<GoGenerationJobResponse>(`/api/missions/${id}/generation-jobs`, { specificationId, specificationVersion, fallbackPolicy });
  return data;
}

export async function selectGoMissionConnection(id: number, connectionId: number | null) {
  const { data } = await goHttp.put<{ missionId: number; modelConnectionId: number | null; agentRunId?: string | null }>(`/api/missions/${id}/model-connection`, { connectionId });
  return data;
}

export async function uploadGoTemporary(file: File) {
  const form = new FormData();
  form.append('file', file, file.name);
  const { data } = await goHttp.post<GoUploadResponse>('/api/uploads', form, { headers: { 'Content-Type': 'multipart/form-data' } });
  return data;
}

export async function uploadGoMissionFile(id: number, file: File) {
  const form = new FormData();
  form.append('file', file, file.name);
  const { data } = await goHttp.post<{ fileId: number; name: string; sha256: string }>(`/api/missions/${id}/files`, form, { headers: { 'Content-Type': 'multipart/form-data' } });
  return data;
}

export function goMissionEventsUrl(id: number, after = 0) {
  return `${goApiBaseUrl}/api/missions/${id}/events?after=${after}`;
}
