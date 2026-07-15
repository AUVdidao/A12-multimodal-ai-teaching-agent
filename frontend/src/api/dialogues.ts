import { http } from './http';
import type { ApiResponse } from './health';

export type DialogueSender = 'TEACHER' | 'AI' | 'ASSISTANT' | 'SYSTEM';

export interface DialogueMessage {
  id: number;
  projectId: number;
  sessionId: string;
  sender: DialogueSender;
  content: string;
  roundNo: number;
  createdAt: string;
}

export interface DialogueMessagePayload {
  sessionId: string;
  sender: DialogueSender;
  content: string;
  roundNo: number;
}

export interface DialogueClearResult {
  projectId: number;
  deletedCount: number;
}

export async function saveDialogueMessage(
  projectId: number | string,
  payload: DialogueMessagePayload,
) {
  const response = await http.post<ApiResponse<DialogueMessage>>(
    `/api/projects/${projectId}/dialogues`,
    payload,
  );
  return response.data.data;
}

export async function listProjectDialogues(projectId: number | string) {
  const response = await http.get<ApiResponse<DialogueMessage[]>>(
    `/api/projects/${projectId}/dialogues`,
  );
  return response.data.data;
}

export async function listSessionDialogues(sessionId: string) {
  const response = await http.get<ApiResponse<DialogueMessage[]>>(`/api/dialogues/${sessionId}`);
  return response.data.data;
}

export async function clearProjectDialogues(projectId: number | string) {
  const response = await http.delete<ApiResponse<DialogueClearResult>>(
    `/api/projects/${projectId}/dialogues`,
  );
  return response.data.data;
}
