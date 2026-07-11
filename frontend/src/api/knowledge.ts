import { http } from './http';
import type { ApiResponse } from './health';
import type { MaterialUsageType } from './materials';

export interface KnowledgeChunk {
  chunkId: number;
  projectId: number;
  materialId: number;
  chunkNo: number;
  sourceFilename: string;
  title: string;
  content: string;
  keywords: string[];
  usageTypes: MaterialUsageType[];
  createdAt: string;
}

export interface KnowledgeOverview {
  indexedMaterialCount: number;
  chunkCount: number;
  chunks: KnowledgeChunk[];
  prototype: boolean;
}

export interface KnowledgeHit {
  chunkId: number;
  materialId: number;
  sourceFilename: string;
  title: string;
  content: string;
  score: number;
  hitReason: string;
  usageTypes: MaterialUsageType[];
  keywords: string[];
}

export interface KnowledgeSearchResult {
  query: string;
  hits: KnowledgeHit[];
  prototype: boolean;
  algorithm: string;
}

export async function getKnowledgeOverview(projectId: number) {
  const response = await http.get<ApiResponse<KnowledgeOverview>>(`/api/projects/${projectId}/knowledge/overview`);
  return response.data.data;
}

export async function searchKnowledge(projectId: number, query: string, limit = 10) {
  const response = await http.post<ApiResponse<KnowledgeSearchResult>>(
    `/api/projects/${projectId}/knowledge/search`,
    { query, limit },
  );
  return response.data.data;
}
