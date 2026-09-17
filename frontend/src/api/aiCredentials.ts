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
  deleteGoModelConnectionBinding,
  listGoModelConnectionBindings,
  setGoModelConnectionBinding,
  type GoModelConnectionBinding,
  type GoModelRole,
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
export type CapabilityVerificationStatus = 'DECLARED' | 'VERIFIED' | 'UNSUPPORTED';
export interface ModelCapabilities {
  supportsChat: boolean;
  supportsTools: boolean;
  supportsJSONMode: boolean;
  supportsVision: boolean;
  supportsEmbeddings: boolean;
  embeddingDimension?: number;
  supportsStreaming: boolean;
}
export interface ModelCapabilityVerification {
  supportsChat: CapabilityVerificationStatus;
  supportsTools: CapabilityVerificationStatus;
  supportsJSONMode: CapabilityVerificationStatus;
  supportsVision: CapabilityVerificationStatus;
  supportsEmbeddings: CapabilityVerificationStatus;
  supportsStreaming: CapabilityVerificationStatus;
}
export interface ModelConnection {
  id: number;
  name: string;
  protocol: ModelConnectionProtocol;
  baseUrl: string;
  modelId: string;
  provider?: string;
  capabilities?: ModelCapabilities;
  capabilityVerification?: ModelCapabilityVerification;
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
  capabilities?: ModelCapabilities;
}
export interface ConnectionVerification {
  connectionId: number;
  status: ModelConnectionVerificationStatus;
  safeCode: string;
  httpStatus: number;
  baseUrlHost: string;
  modelId: string;
  capabilities?: {
    normalChat?: boolean;
    toolCalling?: boolean;
    jsonMode?: boolean;
    vision?: boolean;
    visionProbed?: boolean;
    embeddings?: boolean;
    embeddingsProbed?: boolean;
    embeddingDimension?: number;
  };
  verifiedAt: string;
}

export async function getModelConnections() {
  if (isGoBackend) return { code: 0, message: '', data: await listGoModelConnections() };
  const response = await http.get<ApiResponse<ModelConnection[]>>('/api/v1/ai-credentials/connections');
  return response.data;
}
export async function createModelConnection(payload: ModelConnectionPayload & { apiKey: string }) {
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

export type ModelRole = GoModelRole;
export type ModelConnectionBinding = GoModelConnectionBinding;

export async function getModelConnectionBindings() {
  if (!isGoBackend) return [] as ModelConnectionBinding[];
  return listGoModelConnectionBindings();
}

export async function setModelConnectionBinding(role: ModelRole, connectionId: number) {
  if (!isGoBackend) throw new Error('MODEL_CONNECTION_BINDING_UNSUPPORTED');
  return setGoModelConnectionBinding(role, connectionId);
}

export async function clearModelConnectionBinding(role: ModelRole) {
  if (!isGoBackend) throw new Error('MODEL_CONNECTION_BINDING_UNSUPPORTED');
  await deleteGoModelConnectionBinding(role);
}
