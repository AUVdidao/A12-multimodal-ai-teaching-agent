import { http } from './http';
import type { ApiResponse } from './health';

export type MaterialFileType =
  | 'PDF'
  | 'DOCX'
  | 'PPT'
  | 'PPTX'
  | 'XLSX'
  | 'TXT'
  | 'MD'
  | 'PNG'
  | 'JPG'
  | 'JPEG'
  | 'MP4';
export type MaterialParseStatus = 'NOT_STARTED' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED';
export type MaterialUsageType =
  | 'TEXTBOOK_BASIS'
  | 'CASE_MATERIAL'
  | 'EXERCISE_SOURCE'
  | 'KNOWLEDGE_SUPPLEMENT'
  | 'IMAGE_ASSET';

export interface MaterialRecord {
  id: number;
  projectId: number;
  originalFilename: string;
  fileExtension: string;
  fileType: MaterialFileType;
  contentType: string;
  fileSize: number;
  description?: string;
  uploadStatus: string;
  parseStatus: MaterialParseStatus;
  usageTypes: MaterialUsageType[];
  usageNote?: string;
  createdAt: string;
  updatedAt: string;
  downloadPath: string;
}

export interface MaterialUsage {
  materialId: number;
  projectId: number;
  usageTypes: MaterialUsageType[];
  note?: string;
  updatedAt?: string;
}

export interface MaterialParseResult {
  id?: number;
  materialId: number;
  parseStatus: MaterialParseStatus;
  summary?: string;
  keywords: string[];
  applicableTeachingStages: string[];
  failureReason?: string;
  parsedAt?: string;
  prototype: boolean;
  extractedTextPreview?: string;
  pageCount?: number;
  sections?: string[];
  chunkCount?: number;
  parseDurationMs?: number;
}

export async function uploadMaterial(
  projectId: number,
  file: File,
  description: string,
  onProgress?: (percentage: number) => void,
) {
  const form = new FormData();
  form.append('file', file);
  if (description.trim()) form.append('description', description.trim());
  const response = await http.post<ApiResponse<MaterialRecord>>(`/api/projects/${projectId}/materials`, form, {
    onUploadProgress: (event) => {
      if (event.total && onProgress) onProgress(Math.round((event.loaded / event.total) * 100));
    },
  });
  return response.data.data;
}

export async function listMaterials(projectId: number) {
  const response = await http.get<ApiResponse<MaterialRecord[]>>(`/api/projects/${projectId}/materials`);
  return response.data.data;
}

export async function updateMaterialUsages(
  projectId: number,
  materialId: number,
  usageTypes: MaterialUsageType[],
  note: string,
) {
  const response = await http.put<ApiResponse<MaterialUsage>>(
    `/api/projects/${projectId}/materials/${materialId}/usages`,
    { usageTypes, note },
  );
  return response.data.data;
}

export async function getMaterialParseResult(projectId: number, materialId: number) {
  const response = await http.get<ApiResponse<MaterialParseResult>>(
    `/api/projects/${projectId}/materials/${materialId}/parse-result`,
  );
  return response.data.data;
}

export async function startMaterialParse(projectId: number, materialId: number) {
  const response = await http.post<ApiResponse<MaterialParseResult>>(
    `/api/projects/${projectId}/materials/${materialId}/parse`,
  );
  return response.data.data;
}

export async function retryMaterialParse(projectId: number, materialId: number) {
  const response = await http.post<ApiResponse<MaterialParseResult>>(
    `/api/projects/${projectId}/materials/${materialId}/parse/retry`,
  );
  return response.data.data;
}

export async function indexMaterial(projectId: number, materialId: number) {
  const response = await http.post<ApiResponse<unknown[]>>(
    `/api/projects/${projectId}/materials/${materialId}/index`,
  );
  return response.data.data;
}

export async function downloadMaterial(projectId: number, material: MaterialRecord) {
  const response = await http.get(`/api/projects/${projectId}/materials/${material.id}/download`, {
    responseType: 'blob',
  });
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = material.originalFilename;
  anchor.click();
  URL.revokeObjectURL(url);
}

export async function downloadMaterialById(projectId: number, materialId: number, filename: string) {
  const response = await http.get(`/api/projects/${projectId}/materials/${materialId}/download`, {
    responseType: 'blob',
  });
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}
