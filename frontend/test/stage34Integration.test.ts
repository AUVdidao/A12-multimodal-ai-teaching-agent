import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (relativePath: string) => readFileSync(resolve(root, relativePath), 'utf8');

test('legacy specification workflow is retained only as source history, not an app route', () => {
  const view = read('src/views/PptSpecificationView.vue');
  const api = read('src/api/pptSpecifications.ts');
  assert.match(view, /保存草稿/);
  assert.match(view, /并发修改/);
  assert.match(view, /刷新后重试/);
  assert.match(view, /expectedChecksum/);
  assert.match(api, /templateProfileChecksum/);
  assert.match(api, /returnPptSpecificationToDraft/);
  assert.doesNotMatch(read('src/router/index.ts'), /project-ppt-specification/);
});

test('legacy template profile workflow is retained only as source history, not an app route', () => {
  const view = read('src/views/ProjectTemplateProfileView.vue');
  const api = read('src/api/templates.ts');
  const router = read('src/router/index.ts');
  assert.match(view, /NOT_READY/);
  assert.match(view, /未就绪/);
  assert.match(view, /checksum/);
  assert.match(api, /NOT_READY/);
  assert.match(api, /ANALYZER_CANDIDATE/);
  assert.doesNotMatch(router, /project-templates/);
  assert.doesNotMatch(router, /project-ppt-specification/);
});
