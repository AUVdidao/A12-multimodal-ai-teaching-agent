import type { ApiResponse } from './health';
import { http } from './http';

export type PptSpecificationStatus = 'DRAFT' | 'REVIEW' | 'LOCKED';
export type AiSupplementPolicy = 'DISABLED' | 'TEACHER_APPROVED_ONLY';

export interface SemanticRegion { regionId: string; semanticRole: string; preferredPosition: string; maxItems: number }
export interface SemanticLayout { primaryRole: string; regions: SemanticRegion[]; requestedTransform?: string | null }
export interface ContentBlock { blockId: string; type: string; content: string; sourceType: string; sourceReference: string; locked: boolean }
export interface AssetRequirement { assetId: string; assetType: string; source: string; approvalStatus: string; required: boolean; placementIntent: string }
export interface Provenance { sourceType: string; sourceReference: string }
export interface SpecificationSlide { slideId: string; pageNumber: number; title: string; teachingGoal: string; semanticLayout: SemanticLayout; contentBlocks: ContentBlock[]; assetRequirements: AssetRequirement[]; provenance: Provenance[]; notes?: string | null }
export interface SpecificationWritePayload {
  contractVersion: string;
  templateProfileId: string;
  templateProfileVersion: number;
  templateCapabilityViewVersion: number;
  templateCapabilityViewChecksum: string;
  targetSlideCount: number;
  slideCountTolerance: number;
  locale: string;
  provider: string;
  model: string;
  aiSupplementPolicy: AiSupplementPolicy;
  slides: SpecificationSlide[];
  expectedChecksum?: string;
  expectedEntityVersion?: number;
}
export interface PptSpecification {
  id: number; specificationId: string; projectId: number; version: number; status: PptSpecificationStatus;
  contractVersion: string; templateProfileId: string; templateProfileVersion: number; templateProfileChecksum?: string | null; templateCapabilityViewVersion: number; templateCapabilityViewChecksum: string;
  targetSlideCount: number; slideCountTolerance: number; locale: string; provider: string; model: string; aiSupplementPolicy: AiSupplementPolicy;
  createdBy?: number | null; updatedBy?: number | null; submittedBy?: number | null; submittedAt?: string | null; submittedChecksum?: string | null;
  lockedBy?: number | null; lockedAt?: string | null; checksum: string; returnedFromVersion?: number | null; returnReason?: string | null;
  teacherEditingAt?: string | null; entityVersion: number; createdAt: string; updatedAt: string; slides: SpecificationSlide[];
}
export interface PptSpecificationHistory { projectId: number; specificationId?: string | null; latest?: PptSpecification | null; versions: PptSpecification[] }

export async function getPptSpecificationHistory(projectId: number | string) {
  const response = await http.get<ApiResponse<PptSpecificationHistory>>(`/api/v1/projects/${projectId}/ppt-specifications`);
  return response.data.data;
}
export async function createPptSpecification(projectId: number | string, payload: SpecificationWritePayload) {
  const response = await http.post<ApiResponse<PptSpecification>>(`/api/v1/projects/${projectId}/ppt-specifications`, payload);
  return response.data.data;
}
export async function updatePptSpecification(projectId: number | string, versionId: number, payload: SpecificationWritePayload) {
  const response = await http.put<ApiResponse<PptSpecification>>(`/api/v1/projects/${projectId}/ppt-specifications/${versionId}`, payload);
  return response.data.data;
}
export async function submitPptSpecificationForReview(projectId: number | string, versionId: number, expectedChecksum: string) {
  const response = await http.post<ApiResponse<PptSpecification>>(`/api/v1/projects/${projectId}/ppt-specifications/${versionId}/submit-review`, { expectedChecksum });
  return response.data.data;
}
export async function returnPptSpecificationToDraft(projectId: number | string, versionId: number, expectedChecksum: string, reason: string) {
  const response = await http.post<ApiResponse<PptSpecification>>(`/api/v1/projects/${projectId}/ppt-specifications/${versionId}/return-to-draft`, { expectedChecksum, reason });
  return response.data.data;
}
export async function lockPptSpecification(projectId: number | string, versionId: number, expectedChecksum: string) {
  const response = await http.post<ApiResponse<PptSpecification>>(`/api/v1/projects/${projectId}/ppt-specifications/${versionId}/lock`, { expectedChecksum });
  return response.data.data;
}

