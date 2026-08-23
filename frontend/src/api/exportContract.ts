export const SUPPORTED_EXPORT_FORMATS = ['DOCX'] as const;

export type ExportFormat = typeof SUPPORTED_EXPORT_FORMATS[number];

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

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

function isSafeDocxDownloadUrl(value: unknown): value is string {
  return typeof value === 'string'
    && /^\/api\/v1\/projects\/[1-9]\d*\/exports\/docx\/?$/.test(value);
}

export function isExportOption(value: unknown): value is ExportOption {
  if (!value || typeof value !== 'object') return false;
  const option = value as Record<string, unknown>;
  return option.format === 'DOCX'
    && isNonBlankString(option.label)
    && isNonBlankString(option.description)
    && isNonBlankString(option.mediaType)
    && option.extension === 'docx'
    && Number.isInteger(option.artifactId)
    && Number(option.artifactId) > 0
    && isNonBlankString(option.filename)
    && isSafeDocxDownloadUrl(option.downloadUrl);
}

export function filterExportOptions(value: unknown): ExportOption[] {
  return Array.isArray(value) ? value.filter(isExportOption) : [];
}
