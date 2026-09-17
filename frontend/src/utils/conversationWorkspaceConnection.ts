import type { ConnectionVerification, ModelCapabilities, ModelConnection, ModelConnectionPayload, ModelConnectionVerificationStatus } from '@/api/aiCredentials';

export interface ConnectionSelectionCandidate {
  enabled?: boolean;
  verificationStatus?: ModelConnectionVerificationStatus;
  capabilities?: Partial<ModelCapabilities>;
}

export function isSelectableConnection(connection: ConnectionSelectionCandidate | null | undefined) {
  return connection?.enabled === true
    && connection.verificationStatus === 'VERIFIED'
    && connection.capabilities?.supportsChat !== false;
}

export function findSelectableConnection(connections: ModelConnection[], id?: number | null) {
  return connections.find((connection) => connection.id === id && isSelectableConnection(connection)) || null;
}

export type ConnectionListState = 'idle' | 'loading' | 'loaded' | 'error';
export type ConnectionOperation = 'save' | 'delete' | 'verify' | 'toggle';
export interface ConnectionOperationLock {
  operation: ConnectionOperation;
  connectionId: number | null;
}

export function shouldSyncConnectionSelection(state: ConnectionListState) {
  return state === 'loaded';
}

export function acquireConnectionOperation(current: ConnectionOperationLock | null, operation: ConnectionOperation, connectionId: number | null = null) {
  return current ? null : { operation, connectionId };
}

export function releaseConnectionOperation(current: ConnectionOperationLock | null, operation: ConnectionOperation, connectionId: number | null = null) {
  if (!current || current.operation !== operation || current.connectionId !== connectionId) return current;
  return null;
}

export function modelConnectionVerificationLabel(status: ModelConnection['verificationStatus']) {
  if (status === 'VERIFIED') return '已验证（服务端记录）';
  if (status === 'INVALID') return '验证未通过（服务端记录）';
  return 'LIVE_VERIFICATION_PENDING';
}

export function safeConnectionErrorMessage(reason: unknown, fallback: string) {
  const message = reason instanceof Error ? reason.message.trim() : '';
  if (!message || /api[-_ ]?key|authorization|bearer|password|secret|token|stack trace|jdbc|https?:\/\/[^\s/@]+:[^\s/@]+@/i.test(message)) return fallback;
  return message.slice(0, 180);
}

export interface ModelConnectionFormValues {
  name: string;
  baseUrl: string;
  modelId: string;
  apiKey: string;
  capabilities?: ModelCapabilities;
}

export function defaultModelCapabilities(): ModelCapabilities {
  return {
    supportsChat: true,
    supportsTools: true,
    supportsJSONMode: true,
    supportsVision: false,
    supportsEmbeddings: false,
    supportsStreaming: false,
  };
}

export function buildModelConnectionPayload(values: ModelConnectionFormValues, mode: 'create' | 'edit'): ModelConnectionPayload | null {
  if (!values.name.trim() || !values.baseUrl.trim() || !values.modelId.trim() || (mode === 'create' && !values.apiKey.trim())) return null;
  const payload: ModelConnectionPayload = {
    name: values.name.trim(),
    protocol: 'OPENAI_COMPATIBLE',
    baseUrl: values.baseUrl.trim(),
    modelId: values.modelId.trim(),
    capabilities: values.capabilities || defaultModelCapabilities(),
  };
  if (values.apiKey.trim()) payload.apiKey = values.apiKey;
  return payload;
}

export function connectionVerificationOutcome(verification: Pick<ConnectionVerification, 'status' | 'safeCode'>) {
  if (verification.status === 'VERIFIED') return { kind: 'verified' as const, message: '测试连接返回 VERIFIED（服务端记录）。' };
  if (verification.status === 'INVALID') return { kind: 'invalid' as const, message: `测试连接未通过：${verification.safeCode || 'INVALID'}` };
  return { kind: 'pending' as const, message: '测试连接返回未验证状态；仍保持 LIVE_VERIFICATION_PENDING。' };
}
