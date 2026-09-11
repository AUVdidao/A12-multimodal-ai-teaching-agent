import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

test('final deck submission is server-scoped and does not invent a reviewer', async () => {
  const api = await readFile(resolve(process.cwd(), 'src/api/lessonForge.ts'), 'utf8');
  const store = await readFile(resolve(process.cwd(), 'src/stores/lessonForge.ts'), 'utf8');
  const card = await readFile(resolve(process.cwd(), 'src/components/lessonForge/LessonForgeSubmissionCard.vue'), 'utf8');
  assert.match(api, /submitLessonForgePptx/);
  assert.match(api, /reviewLessonForgeSubmission/);
  assert.match(store, /submitLessonForgePptx/);
  assert.doesNotMatch(card, /reviewer-wang|reviewer-li|选择一名教研人员/);
  assert.match(card, /点击提交后上传/);
});

test('submission and feedback remain conversation cards instead of new technical pages', async () => {
  const workspace = await readFile(resolve(process.cwd(), 'src/views/LessonForgeMissionWorkspaceView.vue'), 'utf8');
  const rail = await readFile(resolve(process.cwd(), 'src/components/lessonForge/LessonForgeMissionRail.vue'), 'utf8');
  const card = await readFile(resolve(process.cwd(), 'src/components/lessonForge/LessonForgeSubmissionCard.vue'), 'utf8');
  assert.match(workspace, /LessonForgeSubmissionCard/);
  assert.match(workspace, /data-test="review-card"/);
  assert.match(workspace, /feedbackOpen/);
  assert.match(workspace, /watch\(\(\) => route\.params\.missionId/);
  assert.match(workspace, /mission\.surfaceGate\.feedback/);
  assert.match(workspace, /mission\.surfaceGate\.submission/);
  assert.match(rail, /mission\.surfaceGate\.submission/);
  assert.doesNotMatch(workspace, /mission\.feedback/);
  assert.doesNotMatch(workspace, /mission\.output \|\| mission\.submission \|\| mission\.status === 'SUBMITTED'/);
  assert.doesNotMatch(rail, /v-if="mission\.submission"/);
  assert.match(card, /仅本地选择 · 点击提交后上传/);
  assert.doesNotMatch(card, /选择一名教研人员/);
  assert.equal(workspace.includes('/api/submissions'), false);
});

test('Leader has a LessonForge entry and a submission-only review page', async () => {
  const router = await readFile(resolve(process.cwd(), 'src/router/index.ts'), 'utf8');
  const missions = await readFile(resolve(process.cwd(), 'src/views/LessonForgeMissionsView.vue'), 'utf8');
  const create = await readFile(resolve(process.cwd(), 'src/views/LessonForgeNewMissionView.vue'), 'utf8');
  const workspace = await readFile(resolve(process.cwd(), 'src/views/LessonForgeMissionWorkspaceView.vue'), 'utf8');
  assert.match(router, /title: 'LessonForge', roles: \['TEACHER', 'LEADER', 'STUDENT'\], lessonForge: true/);
  assert.match(router, /title: 'New Mission', roles: \['TEACHER', 'LEADER'\], lessonForge: true/);
  assert.match(router, /title: 'Mission Workspace', roles: \['TEACHER', 'LEADER'\], lessonForge: true/);
  assert.match(missions, /auth\.activeRole === 'LEADER'/);
  assert.match(create, /show-files="false"/);
  assert.match(create, /show-connection="false"/);
  assert.match(workspace, /data-test="leader-submission-review"/);
  assert.match(workspace, /reviewLessonForgeSubmission/);
  assert.match(workspace, /isLeader/);
  assert.match(workspace, /教师私聊、Context Files 与 Model Connection 不在此页面提供/);
});
