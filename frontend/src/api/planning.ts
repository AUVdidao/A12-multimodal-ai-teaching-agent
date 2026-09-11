import { http } from './http';
import type { ApiResponse } from './health';

export type PlanningOperation = 'INITIAL_PROPOSAL' | 'PATCH' | 'NEW_DRAFT_VERSION';

export interface CapabilityView {
  profileVersionId: number;
  profileVersion: number;
  checksum: string;
  displayName: string;
  pageRoles: string[];
  semanticLayouts: Array<{ name: string; description?: string; minCapacity?: number; maxCapacity?: number }>;
  imageCapability?: { supported: boolean; minCount?: number; maxCount?: number; notes?: string };
  tableCapability?: { supported: boolean; minCount?: number; maxCount?: number; notes?: string };
  chartCapability?: { supported: boolean; minCount?: number; maxCount?: number; notes?: string };
  fixedBrandAreas: string[];
  limitations: string[];
}

export interface ConfirmedPageOutlineItem {
  pageNumber: number;
  title: string;
  semanticRole: string;
  sourceType: string;
  sourceReference: string;
}

export interface ConfirmedContextReference {
  revision: string;
  checksum: string;
  confirmedPageCount?: number | null;
  confirmedPageOutline: ConfirmedPageOutlineItem[];
}

export interface PlanningRequestPayload {
  modelConnectionId: number;
  templateId: number;
  templateProfileVersionId: number;
  operation: PlanningOperation;
  baseSpecificationVersion?: number | null;
  baseSpecificationChecksum?: string | null;
  confirmedContextVersion: string;
  targetSlideCount: number;
  slideCountTolerance: number;
  locale: string;
  teachingContext: {
    courseName: string;
    topic: string;
    teachingObjectives: string[];
    outline: string[];
    lessonPlan: string[];
    teacherConfirmed: boolean;
  };
  evidence: Array<{ sourceId: string; sourceType: string; excerpt: string }>;
  teacherInstruction?: string;
  explicitTeacherTrigger: boolean;
}

export interface PlanningResponse {
  runId: string;
  traceId: string;
  executionStatus: string;
  requestedProvider: string;
  usedProvider: string;
  usedModel: string;
  rejectionReason?: string | null;
  capabilityView: CapabilityView;
  specification: { id: number; version: number; status: string; checksum: string; slides: unknown[] };
  proposal: { targetSlideCount: number; slides: unknown[] };
  tracedAt: string;
}

export async function getPlanningCapabilityView(projectId: number | string, templateId: number | string, profileVersionId: number | string) {
  const response = await http.get<ApiResponse<CapabilityView>>(
    `/api/v1/projects/${projectId}/planning/capability-views/${templateId}/${profileVersionId}`,
  );
  return response.data.data;
}

export async function getPlanningConfirmedContext(projectId: number | string) {
  const response = await http.get<ApiResponse<ConfirmedContextReference>>(
    `/api/v1/projects/${projectId}/planning/confirmed-context`,
  );
  return response.data.data;
}

export async function createPlanningProposal(projectId: number | string, payload: PlanningRequestPayload) {
  const response = await http.post<ApiResponse<PlanningResponse>>(
    `/api/v1/projects/${projectId}/planning/proposals`, payload,
  );
  return response.data.data;
}
