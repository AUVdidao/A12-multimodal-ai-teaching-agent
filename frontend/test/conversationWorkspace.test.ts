import assert from 'node:assert/strict';
import test, { after } from 'node:test';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

import {
  acquireConnectionOperation,
  buildModelConnectionPayload,
  connectionVerificationOutcome,
  findSelectableConnection,
  modelConnectionVerificationLabel,
  releaseConnectionOperation,
  safeConnectionErrorMessage,
  shouldSyncConnectionSelection,
} from '../src/utils/conversationWorkspaceConnection.ts';
import {
  buildConversationPlanningRequest,
  findExecutableConnection,
  isConversationPlanningRequestCurrent,
} from '../src/utils/conversationWorkspacePlanning.ts';
import {
  clearConversationSessions,
  conversationWorkspaceStorageKey,
  persistConversationSessions,
  readConversationSessions,
  type ConversationWorkspaceStorage,
  type ConversationSessionSummary,
} from '../src/stores/conversationWorkspace.ts';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (relativePath: string) => readFileSync(resolve(root, relativePath), 'utf8');

let viteServer: { ssrLoadModule(path: string): Promise<Record<string, any>>; close(): Promise<void> } | undefined;
let planningFlow: Promise<Record<string, any>> | undefined;

async function loadPlanningFlow() {
  if (!planningFlow) {
    planningFlow = (async () => {
      viteServer = await createServer({
        root,
        configFile: false,
        resolve: { alias: { '@': resolve(root, 'src') } },
        optimizeDeps: { noDiscovery: true },
        server: { middlewareMode: true },
        appType: 'custom',
        logLevel: 'error',
      });
      return viteServer.ssrLoadModule('/src/utils/conversationWorkspacePlanningFlow.ts');
    })();
  }
  return planningFlow;
}

after(async () => {
  await viteServer?.close();
});

class MemoryStorage implements ConversationWorkspaceStorage {
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

const session = (id: string, title: string, connectionId: number | null = null): ConversationSessionSummary => ({
  id,
  title,
  updatedAt: '2026-08-30T00:00:00.000Z',
  status: 'active',
  connectionId,
});

test('connection selection only accepts enabled verified connections and clears unsafe ids', () => {
  const connections = [
    { id: 1, name: 'ready', enabled: true, verificationStatus: 'VERIFIED' },
    { id: 2, name: 'disabled', enabled: false, verificationStatus: 'VERIFIED' },
  ] as any;
  assert.equal(findSelectableConnection(connections, 1)?.id, 1);
  assert.equal(findSelectableConnection(connections, 2), null);
  assert.equal(findSelectableConnection(connections, 99), null);
  assert.equal(findSelectableConnection(connections, null), null);
});

test('connection form payload requires a new key but preserves an existing key on blank edit', () => {
  const values = { name: '学校连接', baseUrl: 'https://model.example/v1', modelId: 'lesson-model', apiKey: '' };
  assert.equal(buildModelConnectionPayload(values, 'create'), null);

  const blankEdit = buildModelConnectionPayload(values, 'edit');
  assert.ok(blankEdit);
  assert.equal('apiKey' in blankEdit, false);

  const replacement = buildModelConnectionPayload({ ...values, apiKey: 'synthetic-replacement-key' }, 'edit');
  assert.equal(replacement?.apiKey, 'synthetic-replacement-key');
  assert.equal(buildModelConnectionPayload({ ...values, apiKey: 'synthetic-new-key' }, 'create')?.protocol, 'OPENAI_COMPATIBLE');
});

test('connection verification maps server outcomes without turning pending or invalid into success', () => {
  assert.deepEqual(connectionVerificationOutcome({ status: 'VERIFIED', safeCode: 'OK' }), {
    kind: 'verified',
    message: '测试连接返回 VERIFIED（服务端记录）。',
  });
  assert.deepEqual(connectionVerificationOutcome({ status: 'INVALID', safeCode: 'HTTP_401' }), {
    kind: 'invalid',
    message: '测试连接未通过：HTTP_401',
  });
  assert.equal(connectionVerificationOutcome({ status: 'UNVERIFIED', safeCode: '' }).kind, 'pending');
});

test('connection selection sync waits for a successful list load before fail-closed cleanup', () => {
  assert.equal(shouldSyncConnectionSelection('idle'), false);
  assert.equal(shouldSyncConnectionSelection('loading'), false);
  assert.equal(shouldSyncConnectionSelection('error'), false);
  assert.equal(shouldSyncConnectionSelection('loaded'), true);
  assert.equal(findSelectableConnection([{ id: 7, enabled: true, verificationStatus: 'VERIFIED' }] as any, 7)?.id, 7);
  assert.equal(findSelectableConnection([{ id: 7, enabled: false, verificationStatus: 'VERIFIED' }] as any, 7), null);
  assert.equal(findSelectableConnection([{ id: 7, enabled: true, verificationStatus: 'UNVERIFIED' }] as any, 7), null);
  assert.equal(findSelectableConnection([{ id: 7, enabled: true, verificationStatus: 'INVALID' }] as any, 7), null);
});

test('connection operation lock blocks same and cross connection mutations until its owner releases', () => {
  const first = acquireConnectionOperation(null, 'toggle', 7);
  assert.deepEqual(first, { operation: 'toggle', connectionId: 7 });
  assert.equal(acquireConnectionOperation(first, 'delete', 7), null);
  assert.equal(acquireConnectionOperation(first, 'verify', 9), null);
  assert.deepEqual(releaseConnectionOperation(first, 'toggle', 9), first);
  assert.equal(releaseConnectionOperation(first, 'toggle', 7), null);
  const second = acquireConnectionOperation(null, 'verify', 9);
  assert.equal(releaseConnectionOperation(second, 'toggle', 9), second);
  assert.equal(releaseConnectionOperation(second, 'verify', 9), null);
});

test('connection verification labels distinguish invalid from pending in every summary', () => {
  assert.equal(modelConnectionVerificationLabel('VERIFIED'), '已验证（服务端记录）');
  assert.equal(modelConnectionVerificationLabel('INVALID'), '验证未通过（服务端记录）');
  assert.equal(modelConnectionVerificationLabel('UNVERIFIED'), 'LIVE_VERIFICATION_PENDING');
});

test('planning execution requires an enabled verified connection', () => {
  const connections = [
    { id: 1, enabled: true, verificationStatus: 'UNVERIFIED' },
    { id: 2, enabled: false, verificationStatus: 'VERIFIED' },
    { id: 3, enabled: true, verificationStatus: 'VERIFIED' },
  ] as any;
  assert.equal(findExecutableConnection(connections, 1), null);
  assert.equal(findExecutableConnection(connections, 2), null);
  assert.equal(findExecutableConnection(connections, 3)?.id, 3);
});

test('actual Planning orchestration drops stale template results before POST and commit', async () => {
  const { executeConversationPlanning } = await loadPlanningFlow();
  let resolveTemplateDetails!: (value: unknown[]) => void;
  let templateDetailsStarted!: () => void;
  const templateDetails = new Promise<unknown[]>((resolve) => {
    resolveTemplateDetails = resolve;
  });
  const templateStarted = new Promise<void>((resolve) => {
    templateDetailsStarted = resolve;
  });
  const snapshot = {
    userId: 101,
    sessionId: 'session-old',
    projectId: 9,
    modelConnectionId: 33,
  };
  const current = {
    userId: 101,
    sessionId: 'session-old',
    projectId: 9,
    selectedConnectionId: 33,
    connections: [{ id: 33, enabled: true, verificationStatus: 'VERIFIED' }] as any,
  };
  let postCalls = 0;
  const staleMessages: Array<{ sessionId: string; content: string }> = [];
  const oldOperation = executeConversationPlanning({
    project: { id: 9, courseName: '人工智能基础' } as any,
    requirement: { topic: '机器学习入门' } as any,
    requestSnapshot: snapshot,
    teacherInstruction: '生成课程方案',
    currentState: () => current,
    getPlanningConfirmedContext: async () => ({ revision: 'intent:7:checksum', checksum: 'checksum', confirmedPageCount: 2, confirmedPageOutline: [] }),
    getLatestTeachingIntent: async () => ({ id: 7, status: 'CONFIRMED', generationGoals: ['理解监督学习'] } as any),
    listTemplates: async () => [{ id: 4 } as any],
    getTemplate: async () => {
      templateDetailsStarted();
      await templateDetails;
      return { template: { id: 4 }, profiles: [{ id: 8, status: 'CONFIRMED' }] } as any;
    },
    createPlanningProposal: async () => {
      postCalls += 1;
      return {} as any;
    },
    onBlocked: (content) => staleMessages.push({ sessionId: current.sessionId, content }),
    onStale: (content) => staleMessages.push({ sessionId: current.sessionId, content }),
  });

  await templateStarted;
  current.sessionId = 'session-new';
  current.projectId = 10;
  current.selectedConnectionId = 44;
  resolveTemplateDetails([]);
  await oldOperation;

  assert.equal(postCalls, 0);
  assert.equal(staleMessages.length, 1);
  assert.equal(staleMessages[0]?.sessionId, 'session-new');
  assert.match(staleMessages[0]?.content || '', /已丢弃过期 Planning 请求/);
  assert.equal(current.sessionId, 'session-new');
  assert.equal(isConversationPlanningRequestCurrent(snapshot, current), false);
});

test('actual Planning orchestration submits selected modelConnectionId through the API boundary', async () => {
  const { executeConversationPlanning } = await loadPlanningFlow();
  const payloads: any[] = [];
  const requestSnapshot = { userId: 101, sessionId: 'session-valid', projectId: 9, modelConnectionId: 33 };
  const current = {
    userId: 101,
    sessionId: 'session-valid',
    projectId: 9,
    selectedConnectionId: 33,
    connections: [{ id: 33, enabled: true, verificationStatus: 'VERIFIED' }] as any,
  };
  const response = await executeConversationPlanning({
    project: { id: 9, courseName: '人工智能基础' } as any,
    requirement: { topic: '机器学习入门' } as any,
    requestSnapshot,
    teacherInstruction: '生成课程方案',
    currentState: () => current,
    getPlanningConfirmedContext: async () => ({ revision: 'intent:7:checksum', checksum: 'checksum', confirmedPageCount: 2, confirmedPageOutline: [{ pageNumber: 2, title: '监督学习', semanticRole: 'CONTENT', sourceType: 'CONFIRMED', sourceReference: 'intent:7' }] }),
    getLatestTeachingIntent: async () => ({ id: 7, status: 'CONFIRMED', generationGoals: ['理解监督学习'], teachingApproach: '案例讨论', evidenceItems: [] } as any),
    listTemplates: async () => [{ id: 4 } as any],
    getTemplate: async () => ({ template: { id: 4 }, profiles: [{ id: 8, status: 'CONFIRMED' }] } as any),
    createPlanningProposal: async (_projectId, payload) => {
      payloads.push(payload);
      return { executionStatus: 'PENDING', usedProvider: 'OPENAI_COMPATIBLE' } as any;
    },
    onBlocked: () => assert.fail('valid orchestration was blocked'),
    onStale: () => assert.fail('valid orchestration was stale'),
  });

  assert.equal(response?.executionStatus, 'PENDING');
  assert.equal(payloads.length, 1);
  assert.equal(payloads[0]?.modelConnectionId, 33);
});

test('conversation Planning payload carries the selected connection and server-owned context binding', () => {
  const payload = buildConversationPlanningRequest({
    project: { id: 9, courseName: '人工智能基础' } as any,
    requirement: { topic: '机器学习入门' } as any,
    intent: {
      id: 7,
      status: 'CONFIRMED',
      generationGoals: ['理解监督学习'],
      teachingApproach: '案例讨论',
      evidenceItems: [{ materialId: 2, knowledgeChunkId: 5, contentExcerpt: 'confirmed excerpt' }],
    } as any,
    confirmedContext: {
      revision: 'intent:7:checksum',
      checksum: 'checksum',
      confirmedPageCount: 2,
      confirmedPageOutline: [{ pageNumber: 2, title: '监督学习', semanticRole: 'CONTENT', sourceType: 'CONFIRMED', sourceReference: 'intent:7' }],
    },
    template: { template: { id: 4 }, profiles: [{ id: 8, status: 'CONFIRMED' }] } as any,
    modelConnectionId: 33,
    teacherInstruction: '生成课程方案',
  });

  assert.equal(payload?.modelConnectionId, 33);
  assert.equal(payload?.templateId, 4);
  assert.equal(payload?.templateProfileVersionId, 8);
  assert.equal(payload?.confirmedContextVersion, 'intent:7:checksum');
  assert.equal(payload?.explicitTeacherTrigger, true);
  assert.deepEqual(payload?.evidence, [{
    sourceId: 'material:2:chunk:5',
    sourceType: 'CONFIRMED_TEACHING_INTENT',
    excerpt: 'confirmed excerpt',
  }]);
});

test('conversation Planning payload fails closed when confirmed context or profile is unavailable', () => {
  const input = {
    project: { id: 9, courseName: '人工智能基础' } as any,
    requirement: { topic: '机器学习入门' } as any,
    intent: { id: 7, status: 'DRAFT', generationGoals: ['理解监督学习'] } as any,
    confirmedContext: null,
    template: null,
    modelConnectionId: 33,
    teacherInstruction: '生成课程方案',
  };
  assert.equal(buildConversationPlanningRequest(input), null);
});

test('connection errors use a safe fallback when sensitive material appears', () => {
  const fallback = '连接操作失败。';
  assert.equal(safeConnectionErrorMessage(new Error('authorization bearer secret-value'), fallback), fallback);
  assert.equal(safeConnectionErrorMessage(new Error('https://user:password@example.com/v1'), fallback), fallback);
  assert.equal(safeConnectionErrorMessage(new Error('Request failed with status code 503'), fallback), 'Request failed with status code 503');
});

test('connection mutation source uses the existing CRUD/verify API and no legacy provider path', () => {
  const drawer = read('src/components/assistant/ModelConnectionDrawer.vue');
  const assistant = read('src/views/AiAssistantView.vue');
  const sidePanel = read('src/components/assistant/AssistantSidePanel.vue');
  assert.match(drawer, /createModelConnection/);
  assert.match(drawer, /updateModelConnection/);
  assert.match(drawer, /deleteModelConnection/);
  assert.match(drawer, /setModelConnectionEnabled/);
  assert.match(drawer, /verifyModelConnection/);
  assert.match(drawer, /shouldSyncConnectionSelection/);
  assert.match(drawer, /operationLock/);
  assert.match(assistant, /modelConnectionVerificationLabel/);
  assert.match(sidePanel, /modelConnectionVerificationLabel/);
  assert.match(assistant, /createPlanningProposal/);
  assert.match(assistant, /modelConnectionId/);
  assert.match(assistant, /findExecutableConnection/);
  assert.match(assistant, /executionStatus !== 'COMPLETED'/);
  assert.doesNotMatch(drawer, /runKimiAssistantChat|ai-workflow|saveAiCredentials|getAiCredentials/);
});

test('LessonForge workspace uses only the new app routes', () => {
  const view = read('src/views/LessonForgeMissionWorkspaceView.vue');
  const router = read('src/router/index.ts');
  assert.doesNotMatch(view, /project-(?:plan|preview)/);
  assert.match(router, /name: 'lessonforge-missions'/);
  assert.match(router, /name: 'lessonforge-mission'/);
  assert.doesNotMatch(router, /project-(?:lesson-plan|ppt|planning-agent|templates)/);
});

test('conversation summaries are isolated per user across switch, logout, and restore', () => {
  const storage = new MemoryStorage();
  const userOne = session('u1-session', '教师一的课程', 11);
  const userTwo = session('u2-session', '教师二的课程', 22);

  persistConversationSessions(storage, 101, [userOne]);
  assert.deepEqual(readConversationSessions(storage, 101), [userOne]);
  assert.deepEqual(readConversationSessions(storage, 202), []);
  assert.notEqual(conversationWorkspaceStorageKey(101), conversationWorkspaceStorageKey(202));

  persistConversationSessions(storage, 202, [userTwo]);
  assert.deepEqual(readConversationSessions(storage, 101), [userOne]);
  assert.deepEqual(readConversationSessions(storage, 202), [userTwo]);
  assert.deepEqual(readConversationSessions(storage, undefined), []);

  clearConversationSessions(storage, 101);
  assert.deepEqual(readConversationSessions(storage, 101), []);
  assert.deepEqual(readConversationSessions(storage, 202), [userTwo]);
});
