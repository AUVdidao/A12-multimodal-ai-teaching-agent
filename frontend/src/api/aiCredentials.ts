import { http } from './http';
import type { ApiResponse } from './health';

export interface AiCredentialView {
  slot: number;
  configured: boolean;
  active: boolean;
  maskedKey: string | null;
  updatedAt: string | null;
}

export interface AiCredentialsView {
  provider: string;
  credentials: AiCredentialView[];
}

export interface SaveAiCredentialsPayload {
  keys: string[];
  activeSlot: number;
}

export async function getAiCredentials() {
  const response = await http.get<ApiResponse<AiCredentialsView>>('/api/v1/ai-credentials');
  return response.data;
}

export async function saveAiCredentials(payload: SaveAiCredentialsPayload) {
  const response = await http.post<ApiResponse<AiCredentialsView>>('/api/v1/ai-credentials', payload);
  return response.data;
}
