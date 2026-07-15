import type { ApiResponse } from './health';
import { http } from './http';

export type ApprovalStatus = 'SUBMITTED' | 'APPROVED' | 'REVISION_REQUIRED' | 'CANCELLED';
export type ApprovalReviewStatus = Extract<ApprovalStatus, 'APPROVED' | 'REVISION_REQUIRED'>;

export interface ApprovalRequest {
  id: number;
  artifactVersionId: number;
  artifactVersionNumber?: number | null;
  projectId: number;
  projectName?: string | null;
  submittedBy: number;
  submittedByName?: string | null;
  reviewerId: number;
  reviewerName?: string | null;
  status: ApprovalStatus;
  reviewNote?: string | null;
  submittedAt?: string | null;
  reviewedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

export interface SubmitApprovalRequestPayload {
  projectId: number;
  artifactVersionId: number;
  reviewerId: number;
}

export interface ReviewApprovalRequestPayload {
  status: ApprovalReviewStatus;
  note: string;
}

const approvalRequestsPath = '/api/v1/approval-requests';

export async function submitApprovalRequest(payload: SubmitApprovalRequestPayload) {
  const response = await http.post<ApiResponse<ApprovalRequest>>(approvalRequestsPath, payload);
  return response.data.data;
}

export async function listApprovalRequests(status?: ApprovalStatus) {
  const response = await http.get<ApiResponse<ApprovalRequest[]>>(approvalRequestsPath, {
    params: status ? { status } : undefined,
  });
  return response.data.data;
}

export async function getApprovalRequest(approvalRequestId: number | string) {
  const response = await http.get<ApiResponse<ApprovalRequest>>(
    `${approvalRequestsPath}/${approvalRequestId}`,
  );
  return response.data.data;
}

export async function reviewApprovalRequest(
  approvalRequestId: number | string,
  payload: ReviewApprovalRequestPayload,
) {
  const response = await http.put<ApiResponse<ApprovalRequest>>(
    `${approvalRequestsPath}/${approvalRequestId}/review`,
    payload,
  );
  return response.data.data;
}

export async function cancelApprovalRequest(approvalRequestId: number | string) {
  const response = await http.post<ApiResponse<ApprovalRequest>>(
    `${approvalRequestsPath}/${approvalRequestId}/cancel`,
  );
  return response.data.data;
}
