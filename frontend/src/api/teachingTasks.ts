import type { ApiResponse } from './health';
import { http } from './http';

export type TeachingTaskStatus =
  | 'DRAFT'
  | 'ASSIGNED'
  | 'IN_PROGRESS'
  | 'SUBMITTED'
  | 'REVISION_REQUIRED'
  | 'COMPLETED'
  | 'CANCELLED';

export type TeachingTaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';

export interface TeachingTask {
  id: number;
  taskName: string;
  courseId: number;
  classId?: number | null;
  className?: string | null;
  chapterTitle: string;
  assigneeId: number;
  requirements: string;
  priority: TeachingTaskPriority;
  dueAt: string;
  linkedProjectId?: number | null;
  taskStatus: TeachingTaskStatus;
  overdue: boolean;
  assigneeName: string;
  courseName: string;
  createdBy?: number;
  creatorName?: string;
  submissionNote?: string | null;
  reviewNote?: string | null;
  submittedAt?: string | null;
  completedAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface CreateTeachingTaskPayload {
  taskName: string;
  courseId: number;
  classId?: number;
  chapterTitle: string;
  assigneeId: number;
  requirements: string;
  priority: TeachingTaskPriority;
  dueAt: string;
  linkedProjectId?: number;
}

export interface UpdateTeachingTaskStatusPayload {
  status: TeachingTaskStatus;
  note?: string;
}

export interface SubmitTeachingTaskPayload {
  note: string;
  linkedProjectId?: number;
}

const taskPath = '/api/v1/teaching-tasks';

export async function listTeachingTasks(status?: TeachingTaskStatus) {
  const response = await http.get<ApiResponse<TeachingTask[]>>(taskPath, {
    params: status ? { status } : undefined,
  });
  return response.data.data;
}

export async function createTeachingTask(payload: CreateTeachingTaskPayload) {
  const response = await http.post<ApiResponse<TeachingTask>>(taskPath, payload);
  return response.data.data;
}

export async function getTeachingTask(taskId: number | string) {
  const response = await http.get<ApiResponse<TeachingTask>>(`${taskPath}/${taskId}`);
  return response.data.data;
}

export async function updateTeachingTask(taskId: number | string, payload: CreateTeachingTaskPayload) {
  const response = await http.put<ApiResponse<TeachingTask>>(`${taskPath}/${taskId}`, payload);
  return response.data.data;
}

export async function updateTeachingTaskStatus(
  taskId: number | string,
  payload: UpdateTeachingTaskStatusPayload,
) {
  const response = await http.put<ApiResponse<TeachingTask>>(`${taskPath}/${taskId}/status`, payload);
  return response.data.data;
}

export async function submitTeachingTask(taskId: number | string, payload: SubmitTeachingTaskPayload) {
  const response = await http.post<ApiResponse<TeachingTask>>(`${taskPath}/${taskId}/submit`, payload);
  return response.data.data;
}
