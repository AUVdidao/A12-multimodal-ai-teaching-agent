import { http } from './http';
import type { ApiResponse } from './health';
import { isGoBackend } from '@/config/runtime';
import {
  createGoModelConnection,
  deleteGoModelConnection,
  listGoModelConnections,
  setGoModelConnectionEnabled,
  updateGoModelConnection,
  verifyGoModelConnection,
} from './go';

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

export type ModelConnectionProtocol = 'OPENAI_COMPATIBLE';
export type ModelConnectionVerificationStatus = 'UNVERIFIED' | 'VERIFIED' | 'INVALID';
export interface ModelConnection {
  id: number;
  name: string;
  protocol: ModelConnectionProtocol;
  baseUrl: string;
  modelId: string;
  keyHint: string;
  enabled: boolean;
  verificationStatus: ModelConnectionVerificationStatus;
  lastVerifiedAt?: string | null;
  lastUsedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}
export interface ModelConnectionPayload {
  name: string;
  protocol: ModelConnectionProtocol;
  baseUrl: string;
  apiKey?: string;
  modelId: string;
}
export interface ConnectionVerification {
  connectionId: number;
  status: ModelConnectionVerificationStatus;
  safeCode: string;
  httpStatus: number;
  baseUrlHost: string;
  modelId: string;
  verifiedAt: string;
}

export async function getModelConnections() {
  if (isGoBackend) return { code: 0, message: '', data: await listGoModelConnections() };
  const response = await http.get<ApiResponse<ModelConnection[]>>('/api/v1/ai-credentials/connections');
  return response.data;
}
export async function createModelConnection(payload: Required<ModelConnectionPayload>) {
  if (isGoBackend) return { code: 0, message: '', data: await createGoModelConnection(payload) };
  const response = await http.post<ApiResponse<ModelConnection>>('/api/v1/ai-credentials/connections', payload);
  return response.data;
}
export async function updateModelConnection(id: number, payload: ModelConnectionPayload) {
  if (isGoBackend) return { code: 0, message: '', data: await updateGoModelConnection(id, payload) };
  const response = await http.put<ApiResponse<ModelConnection>>(`/api/v1/ai-credentials/connections/${id}`, payload);
  return response.data;
}
export async function deleteModelConnection(id: number) {
  if (isGoBackend) {
    await deleteGoModelConnection(id);
    return { code: 0, message: '', data: undefined };
  }
  const response = await http.delete<ApiResponse<void>>(`/api/v1/ai-credentials/connections/${id}`);
  return response.data;
}
export async function setModelConnectionEnabled(id: number, value: boolean) {
  if (isGoBackend) return { code: 0, message: '', data: await setGoModelConnectionEnabled(id, value) };
  const response = await http.post<ApiResponse<ModelConnection>>(`/api/v1/ai-credentials/connections/${id}/enabled?value=${value}`, {});
  return response.data;
}
export async function verifyModelConnection(id: number) {
  if (isGoBackend) return { code: 0, message: '', data: await verifyGoModelConnection(id) };
  const response = await http.post<ApiResponse<ConnectionVerification>>(`/api/v1/ai-credentials/connections/${id}/verify`, {});
  return response.data;
}
