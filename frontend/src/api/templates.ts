import { http } from './http';
import type { ApiResponse } from './health';

export type ProcessingStatus = 'NOT_STARTED' | 'PROCESSING' | 'NOT_READY' | 'SUCCEEDED' | 'FAILED' | 'NOT_IMPLEMENTED';
export type ProfileStatus = 'CANDIDATE' | 'REVIEW' | 'CONFIRMED';

export interface TemplateSummary {
  id: number;
  projectId: number;
  name: string;
  activeSourceVersionId?: number;
  createdAt: string;
  updatedAt: string;
}

export interface SourceVersion {
  id: number;
  templateId: number;
  version: number;
  originalFilename: string;
  contentType: string;
  fileSize: number;
  sha256: string;
  parseStatus: ProcessingStatus;
  renderStatus: ProcessingStatus;
  analysisStatus: ProcessingStatus;
  createdAt: string;
  downloadPath: string;
  processingRuns?: ProcessingRun[];
  structuralSnapshot?: { id: number; slideCount: number; checksum: string; snapshot: Record<string, unknown>; createdAt: string };
  renderedSlideSet?: { id: number; status: ProcessingStatus; slideCount?: number; previewReference?: string; statusMessage?: string; createdAt: string };
  /** Template detail returns a summary; GET source-version returns verified full details. */
  detailsLoaded: boolean;
}

export interface ProcessingRun {
  id: number;
  operation: 'PARSER' | 'RENDERER' | 'ANALYZER';
  status: ProcessingStatus;
  attempt: number;
  adapter?: string;
  outputReference?: string;
  failureReason?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface ProfileSummary {
  id: number;
  version: number;
  sourceVersionId: number;
  status: ProfileStatus;
  origin?: 'MANUAL_DRAFT' | 'ANALYZER_CANDIDATE';
  checksum: string;
  capabilityViewChecksum: string;
  createdAt: string;
  confirmedAt?: string;
}

export interface TemplateDetail {
  template: TemplateSummary;
  deduplicated: boolean;
  sourceVersions: SourceVersion[];
  profiles: ProfileSummary[];
}

export interface ProfileResponse extends ProfileSummary {
  templateId: number;
  projectId: number;
  parentProfileVersionId?: number;
  origin?: 'MANUAL_DRAFT' | 'ANALYZER_CANDIDATE';
  parserSnapshotChecksum?: string;
  rendererStatus?: ProcessingStatus;
  analyzerStatus?: ProcessingStatus;
  profile: Record<string, unknown>;
  capabilityView: Record<string, unknown>;
  ownedByTeacherId?: number;
  teacherEditedAt?: string;
  confirmedChecksum?: string;
  reviews: Array<{ id: number; action: string; checksumAtAction: string; note?: string; createdAt: string }>;
}

export const defaultProfile = (name: string) => ({
  displayName: `${name} Candidate`,
  pageRoles: ['title', 'content', 'summary'],
  semanticLayouts: [{ name: '左文右图', description: '标题与正文在左侧，图片在右侧', minCapacity: 1, maxCapacity: 4 }],
  imageCapability: { supported: true, minCount: 0, maxCount: 1, notes: '需教师审核素材' },
  tableCapability: { supported: false, minCount: 0, maxCount: 0, notes: '' },
  chartCapability: { supported: false, minCount: 0, maxCount: 0, notes: '' },
  fixedBrandAreas: ['页眉品牌区域'],
  limitations: ['Analyzer 未运行，语义角色需教师审核'],
});

export async function listTemplates(projectId: number) {
  const response = await http.get<ApiResponse<TemplateSummary[]>>(`/api/projects/${projectId}/templates`);
  return response.data.data;
}

export async function getTemplate(projectId: number, templateId: number) {
  const response = await http.get<ApiResponse<TemplateDetail>>(`/api/projects/${projectId}/templates/${templateId}`);
  return response.data.data;
}

export async function uploadTemplate(projectId: number, name: string, file: File) {
  const form = new FormData();
  form.append('name', name.trim());
  form.append('file', file);
  const response = await http.post<ApiResponse<TemplateDetail>>(`/api/projects/${projectId}/templates`, form);
  return response.data.data;
}

export async function getSourceVersion(projectId: number, templateId: number, sourceVersionId: number) {
  const response = await http.get<ApiResponse<SourceVersion>>(`/api/projects/${projectId}/templates/${templateId}/source-versions/${sourceVersionId}`);
  return response.data.data;
}

export async function processTemplate(projectId: number, templateId: number, sourceVersionId: number, operation: 'parse' | 'render' | 'analyze') {
  const response = await http.post<ApiResponse<{ status: ProcessingStatus; failureReason?: string }>>(
    `/api/projects/${projectId}/templates/${templateId}/source-versions/${sourceVersionId}/${operation}`,
  );
  return response.data.data;
}

export async function createCandidate(projectId: number, templateId: number, sourceVersionId: number, profile: Record<string, unknown>) {
  const response = await http.post<ApiResponse<ProfileResponse>>(`/api/projects/${projectId}/templates/${templateId}/profiles`, { sourceVersionId, profile });
  return response.data.data;
}

export async function getProfile(projectId: number, templateId: number, profileId: number) {
  const response = await http.get<ApiResponse<ProfileResponse>>(`/api/projects/${projectId}/templates/${templateId}/profiles/${profileId}`);
  return response.data.data;
}

export async function editCandidate(projectId: number, templateId: number, profileId: number, profile: Record<string, unknown>) {
  const response = await http.put<ApiResponse<ProfileResponse>>(`/api/projects/${projectId}/templates/${templateId}/profiles/${profileId}`, profile);
  return response.data.data;
}

export async function submitProfileReview(projectId: number, templateId: number, profileId: number, note: string) {
  const response = await http.post<ApiResponse<ProfileResponse>>(`/api/projects/${projectId}/templates/${templateId}/profiles/${profileId}/review`, null, { params: { note } });
  return response.data.data;
}

export async function confirmProfile(projectId: number, templateId: number, profileId: number, checksum: string) {
  const response = await http.post<ApiResponse<ProfileResponse>>(`/api/projects/${projectId}/templates/${templateId}/profiles/${profileId}/confirm`, { checksum });
  return response.data.data;
}
