import type { ApiResponse } from './health';
import { http } from './http';
import {
  filterExportOptions,
  isExportOption,
  type ExportFormat,
  type ExportOption,
} from './exportContract';

export { SUPPORTED_EXPORT_FORMATS } from './exportContract';
export { filterExportOptions } from './exportContract';
export type { ExportFormat, ExportOption } from './exportContract';

export interface ExportCatalog {
  projectId: number;
  projectName: string;
  formats: ExportOption[];
}

interface RawExportCatalog {
  projectId?: number;
  projectName?: string;
  formats?: unknown;
}

export async function getProjectExportCatalog(projectId: number | string) {
  const response = await http.get<ApiResponse<RawExportCatalog>>(
    `/api/v1/projects/${projectId}/exports`,
  );
  const catalog = response.data.data;
  return {
    projectId: catalog?.projectId ?? Number(projectId),
    projectName: catalog?.projectName ?? '',
    formats: filterExportOptions(catalog?.formats),
  } satisfies ExportCatalog;
}

export async function downloadProjectExport(projectId: number | string, option: ExportOption) {
  if (!isExportOption(option)) {
    throw new Error('Export option is not a validated current-format option');
  }
  const response = await http.get<Blob>(
    option.downloadUrl,
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
