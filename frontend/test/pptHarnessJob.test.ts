import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  isPptHarnessActiveStatus,
  isPptHarnessTerminalStatus,
  pptHarnessStatusLabel,
  pptHarnessTaskStorageKey,
  safePptHarnessError,
} from '../src/utils/pptHarnessJob.ts';

const frontendRoot = join(import.meta.dirname, '..');

test('all Harness workflow statuses have explicit active or terminal semantics', () => {
  for (const status of [
    'QUEUED',
    'LOADING_REQUIREMENT',
    'LOADING_TEMPLATE',
    'BUILDING_TEMPLATE_CONTEXT',
    'GENERATING_SLIDE_SPEC',
    'VALIDATING_SLIDE_SPEC',
    'REPAIRING_SLIDE_SPEC',
    'RENDERING_PPTX',
    'RENDERING_PREVIEW',
    'RUNNING_DETERMINISTIC_QA',
    'VISUAL_REVIEW',
    'REVISING',
    'FINALIZING',
    'RETRY_PENDING',
  ]) {
    assert.equal(isPptHarnessActiveStatus(status), true, status);
    assert.match(pptHarnessStatusLabel(status), /.+/);
  }
  for (const status of ['SUCCEEDED', 'FAILED', 'CANCELLED']) {
    assert.equal(isPptHarnessTerminalStatus(status), true, status);
  }
  assert.equal(isPptHarnessActiveStatus('loading1'), false);
});

test('PPT failure messages redact internal implementation details', () => {
  assert.equal(safePptHarnessError('KIMI_TIMEOUT: generation timed out'), 'KIMI_TIMEOUT: generation timed out');
  assert.equal(safePptHarnessError('java.lang.IllegalStateException at C:\\secret\\Runner.java:42'), 'PPT 生成失败，请稍后重试。');
  assert.equal(safePptHarnessError('Bearer secret-token'), 'PPT 生成失败，请稍后重试。');
});

test('task storage is project-scoped and does not store credentials', () => {
  assert.equal(pptHarnessTaskStorageKey(42), 'a12-ppt-harness-task:42');
  assert.equal(/token|secret/i.test(pptHarnessTaskStorageKey(42)), false);
});

test('generation view uses Harness create job and does not call generic artifact generation', async () => {
  const source = await readFile(join(frontendRoot, 'src/views/GenerationPlanView.vue'), 'utf8');
  assert.match(source, /createPptHarnessJob\(projectId\.value\)/);
  assert.doesNotMatch(source, /generateArtifacts\(/);
  assert.match(source, /onError:.*startPptStatusPolling|onError:/s);
  assert.match(source, /PPT_STATUS_POLL_INTERVAL_MS = 2500/);
  assert.match(source, /getArtifacts\(projectId\.value\)[\s\S]*listArtifactVersions\(projectId\.value\)/);
  assert.match(source, /:disabled="!canGenerateContent \|\| generating \|\| pptJobActive"/);
});

test('Harness API paths are explicit and separate from generic artifact generation', async () => {
  const source = await readFile(join(frontendRoot, 'src/api/pptHarness.ts'), 'utf8');
  assert.match(source, /ppt-harness\/jobs/);
  assert.match(source, /\/events/);
  assert.doesNotMatch(source, /artifacts\/generate/);
});

test('result preview names the HTML view as content structure preview', async () => {
  const source = await readFile(join(frontendRoot, 'src/components/generation/PptArtifactPreview.vue'), 'utf8');
  assert.match(source, /内容结构预览/);
  assert.match(source, /真实 PNG 预览尚未启用/);
});
