import assert from 'node:assert/strict';
import test, { after } from 'node:test';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { findSelectableConnection } from '../src/utils/conversationWorkspaceConnection.ts';
import { generationStatusFor, generationToneFor } from '../src/utils/generationPresentation.ts';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));

class MemoryStorage {
  private values = new Map<string, string>();

  getItem(key: string) {
    return this.values.get(key) || null;
  }

  setItem(key: string, value: string) {
    this.values.set(key, value);
  }

  removeItem(key: string) {
    this.values.delete(key);
  }
}

const storage = new MemoryStorage();
const location = { protocol: 'http:', host: 'localhost', pathname: '/', search: '', hash: '', assign() {}, replace() {} };
const history = {
  length: 1,
  state: null as unknown,
  replaceState(state: unknown) { this.state = state; },
  pushState(state: unknown) { this.state = state; },
  go() {},
};
const document = {
  visibilityState: 'visible',
  querySelector() { return null; },
  addEventListener() {},
  removeEventListener() {},
  createElement() { return {}; },
};
const windowStub = {
  localStorage: storage,
  location,
  history,
  document,
  addEventListener() {},
  removeEventListener() {},
};

Object.assign(globalThis, { window: windowStub, location, document });

let viteServer: { ssrLoadModule(path: string): Promise<Record<string, any>>; close(): Promise<void> } | undefined;

async function loadSource(path: string) {
  if (!viteServer) {
    const { createServer } = await import('vite');
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

test('real Vue Router resolves only the LessonForge app surface', async () => {
  const { createPinia, setActivePinia } = await import('pinia');
  setActivePinia(createPinia());
  const routerModule = await loadSource('/src/router/index.ts');
  const router = routerModule.default;

  assert.equal(routerModule.roleHome('TEACHER'), '/lessonforge/new');
  assert.equal(routerModule.roleHome('LEADER'), '/lessonforge/new');
  assert.equal(routerModule.roleHome('RESEARCHER'), '/reviewer/missions');
  assert.equal(routerModule.roleHome('STUDENT'), '/assistant');
  const assistant = router.resolve('/assistant');
  assert.equal(assistant.name, 'lessonforge-missions');
  assert.equal(assistant.meta.lessonForge, true);
  assert.deepEqual(assistant.meta.roles, ['TEACHER', 'LEADER', 'STUDENT']);
  assert.equal(router.resolve({ name: 'lessonforge-new' }).path, '/lessonforge/new');
  assert.equal(router.resolve({ name: 'lessonforge-mission', params: { missionId: 'mission-1' } }).path, '/lessonforge/missions/mission-1');
  assert.equal(router.resolve('/lessonforge/missions').matched[0]?.redirect?.name, 'lessonforge-missions');
  assert.equal(router.resolve('/projects/42/ppt').name, 'not-found');
  assert.equal(router.resolve('/login').name, 'lessonforge-login');
  assert.equal(router.resolve('/register').name, 'lessonforge-register');
});

test('real Pinia Workspace lifecycle isolates users, restores same-user state, and clears on auth logout', async () => {
  const { createPinia, setActivePinia } = await import('pinia');
  setActivePinia(createPinia());
  const conversationModule = await loadSource('/src/stores/conversationWorkspace.ts');
  const authModule = await loadSource('/src/stores/auth.ts');
  const workspace = conversationModule.useConversationWorkspaceStore();
  const auth = authModule.useAuthStore();

  auth.applySession('synthetic-token-101', { id: 101, username: 'teacher-101', displayName: 'Teacher 101', roles: ['TEACHER'], activeRole: 'TEACHER' });
  workspace.hydrate(auth.user?.id);
  workspace.touchSession({ id: 'session-101', projectId: 11, title: 'User 101 course', updatedAt: '2026-08-30T00:00:00.000Z', status: 'active', connectionId: 501 });
  assert.equal(workspace.selectedConnectionId, 501);
  assert.match(storage.getItem('a12-conversation-workspace-index:101') || '', /User 101 course/);

  setActivePinia(createPinia());
  const restoredModule = await loadSource('/src/stores/conversationWorkspace.ts');
  const restored = restoredModule.useConversationWorkspaceStore();
  restored.hydrate(101);
  assert.equal(restored.activeSessionId, 'session-101');
  assert.equal(restored.selectedConnectionId, 501);

  const switchedAuth = authModule.useAuthStore();
  switchedAuth.applySession('synthetic-token-202', { id: 202, username: 'teacher-202', displayName: 'Teacher 202', roles: ['TEACHER'], activeRole: 'TEACHER' });
  restored.hydrate(switchedAuth.user?.id);
  assert.deepEqual(restored.sessions, []);
  assert.equal(restored.selectedConnectionId, null);

  switchedAuth.clearSession();
  assert.equal(switchedAuth.token, '');
  assert.equal(restored.sessions.length, 0);
  assert.equal(storage.getItem('a12-conversation-workspace-index:202'), null);
  assert.equal(storage.getItem('a12-conversation-workspace-index:101') !== null, true);
});

test('Workspace connection/session state clears unsafe refreshes and follows selected session', async () => {
  const { createPinia, setActivePinia } = await import('pinia');
  setActivePinia(createPinia());
  const conversationModule = await loadSource('/src/stores/conversationWorkspace.ts');
  const workspace = conversationModule.useConversationWorkspaceStore();
  workspace.hydrate(303);
  workspace.touchSession({ id: 'session-a', projectId: 31, title: 'A', updatedAt: '2026-08-30T00:00:00.000Z', status: 'active', connectionId: 1 });
  workspace.touchSession({ id: 'session-b', projectId: 32, title: 'B', updatedAt: '2026-08-30T00:00:00.000Z', status: 'active', connectionId: null });

  assert.equal(findSelectableConnection([{ id: 1, enabled: true, verificationStatus: 'VERIFIED' }, { id: 2, enabled: false, verificationStatus: 'VERIFIED' }] as any, 1)?.id, 1);
  assert.equal(findSelectableConnection([{ id: 1, enabled: false, verificationStatus: 'VERIFIED' }, { id: 2, enabled: true, verificationStatus: 'VERIFIED' }] as any, 1), null);
  assert.equal(findSelectableConnection([], 1), null);

  workspace.selectSession('session-a');
  assert.equal(workspace.selectedConnectionId, 1);
  workspace.selectSession('session-b');
  assert.equal(workspace.selectedConnectionId, null);
  workspace.setConnection(findSelectableConnection([{ id: 1, enabled: false, verificationStatus: 'VERIFIED' }] as any, 1)?.id || null);
  assert.equal(workspace.selectedConnectionId, null);
});

test('generation source errors are presented as error state instead of pending', () => {
  const input = { contextLoading: false, sourceState: 'error' as const, hasWorkspace: false, hasArtifacts: false, hasPlan: false, planConfirmed: false };
  assert.equal(generationStatusFor(input), '读取失败');
  assert.equal(generationToneFor(input), 'error');
  assert.equal(generationStatusFor({ ...input, sourceState: 'loaded', hasWorkspace: true, hasPlan: true, planConfirmed: true }), '方案已确认');
  assert.equal(generationToneFor({ ...input, sourceState: 'loaded', hasWorkspace: true, hasArtifacts: true }), 'ready');
});
