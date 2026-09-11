import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { strict as assert } from 'node:assert';
import { resolve } from 'node:path';
import { normalizeGoMissionCollections } from '../src/utils/goMissionDetail.ts';

const source = readFileSync(resolve(import.meta.dirname, '../src/api/go.ts'), 'utf8');

describe('Go API client contract', () => {
  it('uses the Go auth, Mission, upload, and connection routes', () => {
    for (const path of ['/api/auth/login', '/api/auth/register', '/api/auth/me', '/api/auth/logout', '/api/model-connections', '/api/uploads', '/api/missions', '/api/missions/${id}/messages', '/api/missions/${id}/files', '/api/missions/${id}/events']) {
      assert.ok(source.includes(path), `missing ${path}`);
    }
  });

  it('keeps Go requests cookie-based and free of legacy token injection', () => {
    assert.match(source, /withCredentials:\s*true/);
    assert.doesNotMatch(source, /localStorage|Authorization\s*:/);
  });

  it('sends the selected connection and temporary upload IDs through Mission creation', () => {
    assert.match(source, /modelConnectionId\?:\s*number\s*\|\s*null/);
    assert.match(source, /uploadIds\?:\s*string\[\]/);
    assert.match(source, /modelConnectionId\s*:\s*number\s*\|\s*null/);
  });

  it('normalizes nullable empty collections returned by the Go Mission detail API', () => {
    const detail = normalizeGoMissionCollections({
      messages: null,
      files: null,
      generationJobs: null,
      artifacts: null,
    });
    assert.deepEqual(detail.messages, []);
    assert.deepEqual(detail.files, []);
    assert.deepEqual(detail.generationJobs, []);
    assert.deepEqual(detail.artifacts, []);
  });

  it('guards the Mission list client against a nullable empty response', () => {
    assert.match(source, /return Array\.isArray\(data\) \? data : \[\]/);
  });
});
