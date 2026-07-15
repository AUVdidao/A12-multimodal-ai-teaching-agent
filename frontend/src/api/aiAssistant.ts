import type { ApiResponse } from './health';
import { http } from './http';

export type GenerationMode = 'STANDARD' | 'QUALITY' | 'HIGH_QUALITY' | 'ECONOMY' | 'MOCK';

export interface AiGatewayStatus {
  requestedProvider: string;
  activeProvider: string;
  mockEnabled: boolean;
  difyConfigured: boolean;
  fallbackToMock: boolean;
  message: string;
}

export interface ClarificationRequest {
  projectId: number;
  rawRequirement: string;
  knownFields: string[];
  generationMode?: GenerationMode;
  requestedMissingFields?: string[];
}

export interface ClarificationResponse {
  workflow: string;
  missingFields: string[];
  questions: string[];
  suggestedFields: Record<string, string>;
  nextAction: string;
}

export interface GenerationPlanRequest {
  projectId: number;
  courseName: string;
  chapterTopic: string;
  targetAudience?: string;
  outputTypes?: string[];
  generationMode?: GenerationMode;
}

export interface PlanSection {
  title: string;
  points: string[];
  materialReference: string;
}

export interface GenerationPlanResponse {
  workflow: string;
  planId: string;
  pptOutline: PlanSection[];
  docOutline: PlanSection[];
  interactionPlan: string[];
  estimatedDuration: string;
  nextAction: string;
}

const workflowPath = '/api/ai-workflow';

export async function getAiGatewayStatus() {
  const response = await http.get<ApiResponse<AiGatewayStatus>>(`${workflowPath}/status`);
  return response.data.data;
}

export async function runClarification(payload: ClarificationRequest) {
  const response = await http.post<ApiResponse<ClarificationResponse>>(
    `${workflowPath}/clarification`,
    payload,
  );
  return response.data.data;
}

export async function runGenerationPlan(payload: GenerationPlanRequest) {
  const response = await http.post<ApiResponse<GenerationPlanResponse>>(
    `${workflowPath}/generation-plan`,
    payload,
  );
  return response.data.data;
}
