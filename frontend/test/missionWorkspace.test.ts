import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer } from 'vite';

import { DEMO_TEACHER_USER_ID, getFrontendMission, listFrontendMissions } from '../src/utils/missionFrontendAdapter.ts';

const root = resolve(process.cwd());
let viteServer: { ssrLoadModule(path: string): Promise<Record<string, any>>; close(): Promise<void> } | undefined;

async function loadSource(path: string) {
  if (!viteServer) {
    viteServer = await createServer({
      root,
      configFile: false,
      resolve: { alias: { '@': resolve(root, 'src') } },
      optimizeDeps: { noDiscovery: true },
      server: { middlewareMode: true },
      appType: 'custom',
      logLevel: 'error',
    });
  }
  return viteServer.ssrLoadModule(path);
}

after(async () => {
  await viteServer?.close();
});

test('Mission fixture is explicitly frontend-only and isolated by teacher id', () => {
  const otherTeacherUserId = 202;
  assert.equal(DEMO_TEACHER_USER_ID, 2);
  const ownMissions = listFrontendMissions(DEMO_TEACHER_USER_ID);
  const otherMissions = listFrontendMissions(otherTeacherUserId);

  assert.equal(ownMissions.length, 6);
  assert.deepEqual(new Set(ownMissions.map((mission) => mission.status)), new Set(['ASSIGNED', 'ACCEPTED', 'IN_PROGRESS', 'SUBMITTED', 'RETURNED', 'COMPLETED']));
  assert.equal(otherMissions.length, 0);
  assert.equal(getFrontendMission('mission-101-assigned', otherTeacherUserId), null);
  assert.equal(listFrontendMissions(101).length, 0);
  assert.equal(ownMissions.every((mission) => mission.source === 'FRONTEND_ONLY' && mission.limitation === 'BACKEND_NOT_CONNECTED'), true);
  assert.equal(ownMissions.some((mission) => Object.prototype.hasOwnProperty.call(mission, 'projectId')), false);
});

test('Mission store accepts in place, rejects only with a reason, and switches user scope', async () => {
  const { createPinia, setActivePinia } = await import('pinia');
  setActivePinia(createPinia());
  const { useMissionWorkspaceStore } = await loadSource('/src/stores/missionWorkspace.ts');
  const store = useMissionWorkspaceStore();

  await store.load(DEMO_TEACHER_USER_ID);
  assert.equal(store.currentMission?.status, 'ASSIGNED');
  assert.equal(store.rejectCurrentMission('   '), false);
  assert.equal(store.currentMission?.decision, 'PENDING');

  assert.equal(store.acceptCurrentMission(), true);
  assert.equal(store.currentMission?.status, 'ACCEPTED');
  assert.equal(store.currentMission?.decision, 'ACCEPTED');
  assert.equal(store.acceptCurrentMission(), false);

  await store.load(DEMO_TEACHER_USER_ID);
  assert.equal(store.currentMission?.status, 'ASSIGNED');
  assert.equal(store.rejectCurrentMission('时间安排冲突'), true);
  assert.equal(store.currentMission?.decision, 'REJECTED');
  assert.equal(store.currentMission?.decisionReason, '时间安排冲突');
  store.selectMission('mission-101-accepted');
  assert.equal(store.rejectCurrentMission('不应允许'), false);

  await store.load(202);
  assert.deepEqual(store.missions, []);
  assert.equal(store.currentMission, null);
});

test('LessonForge Mission workspace is the route target and does not expose Project API', async () => {
  const routerSource = await readFile(resolve(root, 'src/router/index.ts'), 'utf8');
  const viewSource = await readFile(resolve(root, 'src/views/LessonForgeMissionWorkspaceView.vue'), 'utf8');
  const railSource = await readFile(resolve(root, 'src/components/lessonForge/LessonForgeMissionRail.vue'), 'utf8');
  const boardSource = await readFile(resolve(root, 'src/components/lessonForge/LessonForgeBoard.vue'), 'utf8');

  assert.match(routerSource, /component:\s*\(\)\s*=>\s*import\('@\/views\/LessonForgeMissionWorkspaceView\.vue'\)/);
  assert.match(viewSource, /LessonForgeMissionRail/);
  assert.match(viewSource, /LessonForgeComposer/);
  assert.match(boardSource, /mission\.deadline/);
  assert.doesNotMatch(railSource, /mission\.description/);
  assert.doesNotMatch(routerSource, /project-(?:templates|planning-agent|ppt-specification|ppt)/);
  assert.doesNotMatch(viewSource, /\/api\/projects/);
  assert.doesNotMatch(viewSource, /projectId/);
});
