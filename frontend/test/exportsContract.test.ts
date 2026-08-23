import assert from 'node:assert/strict';
import test from 'node:test';

import {
  filterExportOptions,
  SUPPORTED_EXPORT_FORMATS,
} from '../src/api/exportContract.ts';

test('frontend exposes only DOCX as a current downloadable export', () => {
  assert.deepEqual(SUPPORTED_EXPORT_FORMATS, ['DOCX']);
  assert.equal((SUPPORTED_EXPORT_FORMATS as readonly string[]).includes('PPTX'), false);
});

test('runtime export filter keeps only complete same-origin DOCX options', () => {
  const options = filterExportOptions([
    {
      format: 'DOCX', label: '教案', description: 'docx', mediaType: 'application/docx',
      extension: 'docx', artifactId: 1, filename: 'lesson.docx',
      downloadUrl: '/api/v1/projects/1/exports/docx',
    },
    {
      format: 'PPTX', label: 'PPT', description: 'pptx', mediaType: 'application/pptx',
      extension: 'pptx', artifactId: 2, filename: 'lesson.pptx',
      downloadUrl: '/api/v1/projects/1/exports/pptx',
    },
    {
      format: 'UNKNOWN', label: 'unknown', description: 'unknown', mediaType: 'application/octet-stream',
      extension: 'bin', artifactId: 3, filename: 'lesson.bin',
      downloadUrl: '/api/v1/projects/1/exports/unknown',
    },
    {
      format: 'DOCX', label: 'missing', description: 'missing', mediaType: 'application/docx',
      extension: 'docx', artifactId: 4, filename: 'lesson.docx',
    },
    {
      format: 'DOCX', label: 'unsafe', description: 'unsafe', mediaType: 'application/docx',
      extension: 'docx', artifactId: 5, filename: 'lesson.docx',
      downloadUrl: 'https://attacker.invalid/export.docx',
    },
  ]);

  assert.deepEqual(options.map((option) => option.format), ['DOCX']);
  assert.equal(options[0].downloadUrl, '/api/v1/projects/1/exports/docx');
});
