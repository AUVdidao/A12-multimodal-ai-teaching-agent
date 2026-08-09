import type { ApiResponse } from './health';
import { apiRequestUrl, AUTH_TOKEN_STORAGE_KEY, http } from './http';
import {
  type PptHarnessJobStatus,
} from '@/utils/pptHarnessJob';

export interface PptHarnessArtifactRef {
  fileName: string;
  sizeBytes: number;
  sha256: string;
  downloadUrl?: string | null;
}

export interface PptHarnessQaSummary {
  passed: boolean;
  qaLevel: string;
  warnings: string[];
}

export interface PptHarnessError {
  code: string;
  message: string;
}

export interface PptHarnessJob {
  taskId: string;
  requestId: string;
  projectId: number;
  status: PptHarnessJobStatus;
  currentStep?: PptHarnessJobStatus | null;
  progressPercent: number;
  message?: string | null;
  statusUrl: string;
  eventsUrl: string;
  artifact?: PptHarnessArtifactRef | null;
  qa?: PptHarnessQaSummary | null;
  error?: PptHarnessError | null;
}

export interface PptHarnessJobEvent {
  id: number;
  status: PptHarnessJobStatus;
  message: string;
  progressPercent: number;
  createdAt: string;
}

export interface PptHarnessJobEventHandlers {
  onOpen?: () => void;
  onEvent?: (event: PptHarnessJobEvent) => void;
  onEnd?: (event: { taskId?: string; status?: PptHarnessJobStatus }) => void;
  onError?: (error: unknown) => void;
}

export interface PptHarnessEventSubscription {
  close: () => void;
}

export const PPT_HARNESS_JOBS_PATH = (projectId: number | string) => (
  `/api/projects/${projectId}/ppt-harness/jobs`
);

export function pptHarnessJobPath(projectId: number | string, taskId: string): string {
  return `${PPT_HARNESS_JOBS_PATH(projectId)}/${encodeURIComponent(taskId)}`;
}

export async function createPptHarnessJob(projectId: number | string): Promise<PptHarnessJob> {
  const response = await http.post<ApiResponse<PptHarnessJob>>(PPT_HARNESS_JOBS_PATH(projectId));
  return response.data.data;
}

export async function getPptHarnessJob(
  projectId: number | string,
  taskId: string,
): Promise<PptHarnessJob> {
  const response = await http.get<ApiResponse<PptHarnessJob>>(pptHarnessJobPath(projectId, taskId));
  return response.data.data;
}

export function subscribePptHarnessJobEvents(
  projectId: number | string,
  taskId: string,
  handlers: PptHarnessJobEventHandlers,
): PptHarnessEventSubscription {
  const controller = new AbortController();
  let closed = false;

  const onError = (error: unknown) => {
    if (!closed) handlers.onError?.(error);
  };

  void streamEvents(projectId, taskId, controller.signal, handlers)
    .catch(onError);

  return {
    close() {
      closed = true;
      controller.abort();
    },
  };
}

async function streamEvents(
  projectId: number | string,
  taskId: string,
  signal: AbortSignal,
  handlers: PptHarnessJobEventHandlers,
) {
  if (typeof fetch !== 'function' || typeof AbortController !== 'function') {
    throw new Error('SSE is not supported in this browser');
  }

  const token = typeof window !== 'undefined'
    ? window.localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)
    : null;
  const headers: Record<string, string> = { Accept: 'text/event-stream' };
  if (token) headers.Authorization = `Bearer ${token}`;

  const response = await fetch(`${apiRequestUrl(`${pptHarnessJobPath(projectId, taskId)}/events`)}`, {
    method: 'GET',
    headers,
    signal,
    cache: 'no-store',
  });
  if (!response.ok) throw new Error(`SSE connection failed with HTTP ${response.status}`);
  if (!response.body) throw new Error('SSE response did not provide a readable stream');

  handlers.onOpen?.();
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName = 'message';
  let dataLines: string[] = [];
  let terminalEndSeen = false;

  const dispatch = () => {
    if (!dataLines.length) return;
    const data = dataLines.join('\n');
    dataLines = [];
    try {
      const payload = JSON.parse(data) as PptHarnessJobEvent | { taskId?: string; status?: PptHarnessJobStatus };
      if (eventName === 'status' || eventName === 'message') {
        handlers.onEvent?.(payload as PptHarnessJobEvent);
      } else if (eventName === 'end') {
        const endEvent = payload as { taskId?: string; status?: PptHarnessJobStatus };
        terminalEndSeen = Boolean(endEvent.status && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(endEvent.status));
        handlers.onEnd?.(endEvent);
      }
    } finally {
      eventName = 'message';
    }
  };

  while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
    buffer += decoder.decode(chunk.value, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() || '';
    for (const line of lines) {
      if (!line) {
        dispatch();
      } else if (line.startsWith('event:')) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart());
      }
    }
  }
  buffer += decoder.decode();
  if (buffer) {
    for (const line of buffer.split(/\r?\n/)) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart());
    }
  }
  dispatch();
  if (!terminalEndSeen) throw new Error('SSE stream ended before the PPT job reached a terminal status');
}
