import type { ApiResponse } from './health';
import { http } from './http';

export type ExportFormat = 'PPTX' | 'DOCX';

export interface ExportOption {
  format: ExportFormat;
  label: string;
  description: string;
  mediaType: string;
  extension: string;
  artifactId: number;
  versionId?: number | null;
  versionNumber?: number | null;
  filename: string;
  downloadUrl: string;
}

export interface ExportCatalog {
  projectId: number;
  projectName: string;
  formats: ExportOption[];
}

export async function getProjectExportCatalog(projectId: number | string) {
  const response = await http.get<ApiResponse<ExportCatalog>>(`/api/v1/projects/${projectId}/exports`);
  return response.data.data;
}

export async function downloadProjectExport(projectId: number | string, option: ExportOption) {
  const response = await http.get<Blob>(
    option.downloadUrl || `/api/v1/projects/${projectId}/exports/${option.format.toLowerCase()}`,
    { responseType: 'blob' },
  );
  const filename = responseFilename(response.headers['content-disposition']) || option.filename;
  const url = URL.createObjectURL(response.data);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function responseFilename(disposition?: string) {
  if (!disposition) return '';
  const encoded = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  if (encoded) {
    try {
      return decodeURIComponent(encoded.replace(/^"|"$/g, ''));
    } catch {
      return '';
    }
  }
  return disposition.match(/filename="([^"]+)"/i)?.[1]
    || disposition.match(/filename=([^;]+)/i)?.[1]?.trim()
    || '';
}
