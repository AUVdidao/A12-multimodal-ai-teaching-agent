import type { ApiResponse } from './health';
import { http } from './http';

export type QuestionStatus = 'OPEN' | 'ANSWERED' | 'CLOSED';

export interface QuestionAnswer {
  id: number;
  questionId: number;
  teacherId: number;
  teacherName: string;
  content: string;
  createdAt: string;
}

export interface Question {
  id: number;
  publicationId: number;
  projectId: number;
  studentId: number;
  studentName: string;
  title: string;
  content: string;
  status: QuestionStatus;
  answeredAt?: string | null;
  closedAt?: string | null;
  createdAt: string;
  updatedAt: string;
  answers: QuestionAnswer[];
}

export interface CreateQuestionPayload {
  publicationId: number;
  title: string;
  content: string;
}

export interface CreateQuestionAnswerPayload {
  content: string;
}

export interface UpdateQuestionStatusPayload {
  status: QuestionStatus;
}

export interface ListQuestionsParams {
  publicationId?: number;
  status?: QuestionStatus;
}

const questionsPath = '/api/v1/questions';

export async function listQuestions(params: ListQuestionsParams = {}) {
  const response = await http.get<ApiResponse<Question[]>>(questionsPath, { params });
  return response.data.data;
}

export async function getQuestion(questionId: number | string) {
  const response = await http.get<ApiResponse<Question>>(`${questionsPath}/${questionId}`);
  return response.data.data;
}

export async function createQuestion(payload: CreateQuestionPayload) {
  const response = await http.post<ApiResponse<Question>>(questionsPath, payload);
  return response.data.data;
}

export async function createQuestionAnswer(
  questionId: number | string,
  payload: CreateQuestionAnswerPayload,
) {
  const response = await http.post<ApiResponse<Question>>(`${questionsPath}/${questionId}/answers`, payload);
  return response.data.data;
}

export async function updateQuestionStatus(
  questionId: number | string,
  payload: UpdateQuestionStatusPayload,
) {
  const response = await http.put<ApiResponse<Question>>(`${questionsPath}/${questionId}/status`, payload);
  return response.data.data;
}
