import assert from 'node:assert/strict';
import { after, before, beforeEach, test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { JSDOM } from 'jsdom';
import { createServer, type ViteDevServer } from 'vite';
import { compileScript, parse } from '@vue/compiler-sfc';

type AnyRecord = Record<string, any>;

const frontendRoot = resolve(process.cwd());
const api = (data: unknown) => ({ code: 200, message: 'ok', data });
const userOne = { id: 101, username: 'teacher-101', displayName: 'Teacher One', roles: ['TEACHER'], activeRole: 'TEACHER' };
const userTwo = { id: 202, username: 'teacher-202', displayName: 'Teacher Two', roles: ['TEACHER'], activeRole: 'TEACHER' };
const project = {
  id: 9,
  projectName: '物理光学教学设计',
  courseName: '高中物理',
  chapterTitle: '光的干涉',
  targetStudents: '高二',
  lessonDuration: 45,
  modelMode: 'QUALITY',
  status: 'INTENT_CONFIRMED',
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
};
const alternateProject = { ...project, id: 10, projectName: '备用教学任务' };
const verifiedConnection = {
  id: 33,
  name: 'Verified Test Connection',
  protocol: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://provider.invalid/v1',
  modelId: 'test-model',
  keyHint: '***test',
  enabled: true,
  verificationStatus: 'VERIFIED',
};
const pendingConnection = { ...verifiedConnection, id: 34, name: 'Pending Test Connection', verificationStatus: 'UNVERIFIED' };
const invalidConnection = { ...verifiedConnection, id: 35, name: 'Invalid Test Connection', verificationStatus: 'INVALID' };
const requirement = {
  id: 91,
  projectId: 9,
  topic: '光的干涉',
  outputTypes: ['PPT'],
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
};
const confirmedContext = {
  revision: 'ctx-r1',
  checksum: 'ctx-checksum',
  confirmedPageCount: 8,
  confirmedPageOutline: [{ pageNumber: 1, title: '光的干涉', semanticRole: 'title', sourceType: 'teacher', sourceReference: 'teacher' }],
};
const confirmedIntent = {
  id: 71,
  projectId: 9,
  requirementSummaryId: 91,
  status: 'CONFIRMED',
  generationGoal: '解释干涉现象',
  generationGoals: ['解释干涉现象'],
  contentBasis: '教材',
  teachingApproach: '演示与探究',
  interactionMode: '讨论',
  outputTypes: ['PPT'],
  evidenceItems: [],
  prototype: false,
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
};
const templateSummary = {
  id: 4,
  projectId: 9,
  name: '课堂模板',
  createdAt: '2026-08-30T00:00:00Z',
  updatedAt: '2026-08-30T00:00:00Z',
};
const templateDetail = {
  template: templateSummary,
  deduplicated: false,
  sourceVersions: [],
  profiles: [{ id: 44, version: 1, sourceVersionId: 1, status: 'CONFIRMED', checksum: 'profile-checksum', capabilityViewChecksum: 'capability-checksum', createdAt: '2026-08-30T00:00:00Z' }],
};

let dom: JSDOM;
let viteServer: ViteDevServer;
let mount: typeof import('@vue/test-utils')['mount'];
let flushPromises: typeof import('@vue/test-utils')['flushPromises'];
let defineComponent: typeof import('vue')['defineComponent'];
let onMounted: typeof import('vue')['onMounted'];
let createPinia: typeof import('pinia')['createPinia'];
let setActivePinia: typeof import('pinia')['setActivePinia'];
let useAuthStore: typeof import('../src/stores/auth')['useAuthStore'];
let useConversationWorkspaceStore: typeof import('../src/stores/conversationWorkspace')['useConversationWorkspaceStore'];
let createRouter: typeof import('vue-router')['createRouter'];
let createMemoryHistory: typeof import('vue-router')['createMemoryHistory'];
let ElementPlus: any;
let AxiosMockAdapter: any;
let http: AnyRecord;
let AssistantView: any;
let fixtureConnections: AnyRecord[] = [verifiedConnection];

function setGlobal(name: string, value: unknown) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

function installDom() {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/assistant' });
  setGlobal('window', dom.window);
  setGlobal('history', dom.window.history);
  setGlobal('location', dom.window.location);
  setGlobal('document', dom.window.document);
  setGlobal('navigator', dom.window.navigator);
  setGlobal('localStorage', dom.window.localStorage);
  setGlobal('HTMLElement', dom.window.HTMLElement);
  setGlobal('SVGElement', dom.window.SVGElement);
  setGlobal('Element', dom.window.Element);
  setGlobal('Node', dom.window.Node);
  setGlobal('Event', dom.window.Event);
  setGlobal('CustomEvent', dom.window.CustomEvent);
  setGlobal('MutationObserver', dom.window.MutationObserver);
  setGlobal('ShadowRoot', dom.window.ShadowRoot);
  setGlobal('getComputedStyle', dom.window.getComputedStyle.bind(dom.window));
  setGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => setTimeout(callback, 0));
  setGlobal('cancelAnimationFrame', (id: number) => clearTimeout(id));
  setGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  setGlobal('IntersectionObserver', class { observe() {} unobserve() {} disconnect() {} });
  dom.window.scrollTo = () => {};
  dom.window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {}, dispatchEvent() { return false; } }) as MediaQueryList;
}

function resetBrowserState() {
  document.body.innerHTML = '<div id="app"></div>';
  window.localStorage.clear();
  fixtureConnections = [verifiedConnection];
}

function responseForProject(projectValue: AnyRecord) {
  const workspace = { latestPlan: null, artifacts: [], teachingIntent: confirmedIntent };
  const dialoguePath = `/api/projects/${projectValue.id}/dialogues`;
  const projectPath = `/api/projects/${projectValue.id}`;
  return { projectValue, workspace, dialoguePath, projectPath };
}

function registerContextMocks(mock: any, projectValue = project, options: { confirmedContextValue?: AnyRecord | null; profileStatus?: string } = {}) {
  const context = responseForProject(projectValue);
  mock.onGet('/api/projects').reply(200, api([projectValue, ...(projectValue.id === project.id ? [alternateProject] : [])]));
  mock.onGet('/api/projects/recent').reply(200, api([]));
  mock.onGet('/api/ai-workflow/status').reply(200, api({ requestedProvider: 'TEST', activeProvider: 'TEST', mockEnabled: true, providerConfigured: false, fallbackToMock: true, message: 'test adapter only' }));
  mock.onPost('/api/v1/auth/logout').reply(200, api(null));
  mock.onGet(`${context.projectPath}/requirements/latest`).reply(200, api(projectValue.id === project.id ? requirement : null));
  mock.onGet(`${context.projectPath}/materials`).reply(200, api([]));
  mock.onGet(`${context.projectPath}/knowledge/overview`).reply(200, api({ indexedMaterialCount: 0, chunkCount: 0, chunks: [], prototype: false }));
  mock.onGet(`${context.projectPath}/generation/workspace`).reply(200, api(context.workspace));
  mock.onGet('/api/v1/questions').reply(200, api([]));
  mock.onGet(context.dialoguePath).reply(200, api([]));
  mock.onGet(`/api/v1/projects/${projectValue.id}/planning/confirmed-context`).reply(200, api(options.confirmedContextValue === undefined ? confirmedContext : options.confirmedContextValue));
  mock.onGet(`${context.projectPath}/teaching-intents/latest`).reply(200, api(projectValue.id === project.id ? confirmedIntent : null));
  mock.onGet(`${context.projectPath}/templates/${templateSummary.id}`).reply(200, api({
    ...templateDetail,
    profiles: templateDetail.profiles.map((profile) => ({ ...profile, status: options.profileStatus || profile.status })),
  }));
  mock.onGet(`${context.projectPath}/templates`).reply(200, api(projectValue.id === project.id ? [templateSummary] : []));
  mock.onPost(context.dialoguePath).reply((config: AnyRecord) => [200, api({ id: 800 + mock.history.post.length, projectId: projectValue.id, sessionId: JSON.parse(config.data).sessionId, sender: JSON.parse(config.data).sender, content: JSON.parse(config.data).content, roundNo: 1, createdAt: '2026-08-30T00:00:00Z' })]);
}

function componentStubs() {
  const ButtonStub = defineComponent({ name: 'ElButton', emits: ['click'], template: '<button @click="$emit(\'click\', $event)" :disabled="$attrs.disabled"><slot /></button>' });
  const IconStub = defineComponent({ name: 'ElIcon', template: '<span><slot /></span>' });
  const StatePanelStub = defineComponent({ name: 'StatePanel', template: '<section data-test="state-panel"><slot name="action" /></section>' });
  const SidebarStub = defineComponent({
    name: 'ConversationWorkspaceSidebar',
    props: { sessions: { type: Array, default: () => [] } },
    emits: ['new-session', 'select-session'],
    template: '<aside data-test="workspace-sidebar"><button data-test="new-session" @click="$emit(\'new-session\')">new</button><button data-test="select-session" v-if="sessions.length > 1" @click="$emit(\'select-session\', sessions[1].id)">switch</button></aside>',
  });
  const ProjectContextStub = defineComponent({ name: 'AssistantProjectContext', props: { projectName: String }, emits: ['select-project', 'create-project', 'view-projects', 'open-switch', 'overview'], template: '<section data-test="project-context"><span data-test="project-name">{{ projectName }}</span><button data-test="select-project" @click="$emit(\'select-project\', 10)">project</button></section>' });
  const ConversationStub = defineComponent({
    name: 'AssistantConversation',
    props: { messages: { type: Array, default: () => [] }, loading: Boolean },
    emits: ['update:modelValue', 'send', 'quick-prompt', 'action', 'new-dialogue', 'history', 'create-project', 'view-projects', 'file-select'],
    template: '<section data-test="assistant-conversation"><div data-test="message-count">{{ messages.length }}</div><div data-test="message-content">{{ messages.map((message) => message.content).join(\'|\') }}</div><button data-test="start-generation" @click="$emit(\'action\', { id: \'start-generation\', actionType: \'START_WORKFLOW\' })">generate</button><button data-test="start-progress" @click="$emit(\'quick-prompt\', \'progress\')">progress</button></section>',
  });
  const SidePanelStub = defineComponent({ name: 'AssistantSidePanel', emits: ['navigate', 'show-service-detail', 'open-connections', 'open-artifact'], template: '<aside data-test="assistant-side-panel" />' });
  const DrawerStub = defineComponent({
    name: 'ModelConnectionDrawer',
    props: { modelValue: Boolean },
    emits: ['update:modelValue', 'connections-loaded', 'update:connection'],
    setup(_props, { emit }) {
      onMounted(() => emit('connections-loaded', fixtureConnections));
      return { choose() { emit('update:connection', fixtureConnections[0] || null); } };
    },
    template: '<section data-test="connection-drawer"><button data-test="choose-connection" @click="choose">choose</button></section>',
  });
  return {
    ElButton: ButtonStub,
    ElIcon: IconStub,
    StatePanel: StatePanelStub,
    ConversationWorkspaceSidebar: SidebarStub,
    AssistantProjectContext: ProjectContextStub,
    AssistantConversation: ConversationStub,
    AssistantSidePanel: SidePanelStub,
    ModelConnectionDrawer: DrawerStub,
  };
}

async function mountAssistant(mock: any, user = userOne) {
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession(`test-token-${user.id}`, user as any);
  const workspace = useConversationWorkspaceStore(pinia);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/assistant', component: AssistantView }] });
  await router.push('/assistant');
  await router.isReady();
  const wrapper = mount(AssistantView, {
    global: { plugins: [pinia, router, ElementPlus], stubs: componentStubs() },
  });
  await flushPromises();
  await flushPromises();
  return { wrapper, auth, workspace, router };
}

async function waitForPost(mock: any, url: string) {
  for (let attempt = 0; attempt < 40; attempt += 1) {
    if (mock.history.post.some((request: AnyRecord) => request.url === url)) return;
    await flushPromises();
    await new Promise((resolvePromise) => setTimeout(resolvePromise, 0));
  }
  assert.fail(`timed out waiting for POST ${url}`);
}

before(async () => {
  installDom();
  const source = await readFile(resolve(frontendRoot, 'src/views/AiAssistantView.vue'), 'utf8');
  const parsed = parse(source, { filename: 'src/views/AiAssistantView.vue' });
  const script = compileScript(parsed.descriptor, { id: 'data-v-c73acba9', inlineTemplate: true });
  viteServer = await createServer({
    configFile: resolve(frontendRoot, 'vite.config.ts'),
    server: { middlewareMode: true },
    logLevel: 'error',
    plugins: [{
      name: 'a12-assistant-mount-entry',
      resolveId(id) { return id === '/@a12-assistant-mount-entry.ts' ? id : undefined; },
      load(id) { return id === '/@a12-assistant-mount-entry.ts' ? script.content : undefined; },
    }],
  });
  const [vueTestUtils, vue, pinia, router, axiosMock, elementPlus] = await Promise.all([
    import('@vue/test-utils'),
    import('vue'),
    import('pinia'),
    import('vue-router'),
    import('axios-mock-adapter'),
    import('element-plus'),
  ]);
  const httpModule = await viteServer.ssrLoadModule('/src/api/http.ts');
  const viewModule = await viteServer.ssrLoadModule('/@a12-assistant-mount-entry.ts');
  const authModule = await viteServer.ssrLoadModule('/src/stores/auth.ts');
  const workspaceModule = await viteServer.ssrLoadModule('/src/stores/conversationWorkspace.ts');
  mount = vueTestUtils.mount;
  flushPromises = vueTestUtils.flushPromises;
  defineComponent = vue.defineComponent;
  onMounted = vue.onMounted;
  createPinia = pinia.createPinia;
  setActivePinia = pinia.setActivePinia;
  createRouter = router.createRouter;
  createMemoryHistory = router.createMemoryHistory;
  AxiosMockAdapter = axiosMock.default;
  http = httpModule.http;
  AssistantView = viewModule.default;
  useAuthStore = authModule.useAuthStore;
  useConversationWorkspaceStore = workspaceModule.useConversationWorkspaceStore;
  ElementPlus = elementPlus.default;
});

beforeEach(() => {
  resetBrowserState();
});

after(async () => {
  if (viteServer) await viteServer.close();
  dom?.window.close();
});

test('real AiAssistantView mount runs onMounted and wires current connection event', async () => {
  const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
  registerContextMocks(mock);
  const mounted = await mountAssistant(mock);
  assert.equal(mock.history.get.filter((request: AnyRecord) => request.url === '/api/projects').length, 1);
  assert.equal(mounted.wrapper.find('[data-test="assistant-conversation"]').exists(), true);
  await mounted.wrapper.get('[data-test="choose-connection"]').trigger('click');
  assert.equal(mounted.workspace.selectedConnectionId, 33);
  await mounted.wrapper.unmount();
  mock.restore();
});

test('project context event uses the production project-selection handler and reloads the selected project', async () => {
  const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
  registerContextMocks(mock);
  mock.onGet('/api/projects/10/requirements/latest').reply(200, api(null));
  mock.onGet('/api/projects/10/materials').reply(200, api([]));
  mock.onGet('/api/projects/10/knowledge/overview').reply(200, api({ indexedMaterialCount: 0, chunkCount: 0, chunks: [], prototype: false }));
  mock.onGet('/api/projects/10/generation/workspace').reply(200, api({ latestPlan: null, artifacts: [], teachingIntent: null }));
  mock.onGet('/api/projects/10/dialogues').reply(200, api([]));
  const mounted = await mountAssistant(mock);
  await mounted.wrapper.get('[data-test="select-project"]').trigger('click');
  await flushPromises();
  await flushPromises();
  assert.equal(mounted.wrapper.get('[data-test="project-name"]').text(), '备用教学任务');
  assert.equal(mock.history.get.filter((request: AnyRecord) => request.url === '/api/projects/10/materials').length, 1);
  await mounted.wrapper.unmount();
  mock.restore();
});

test('production action blocks unselected or unverified connections before Planning POST', async () => {
  const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
  registerContextMocks(mock);
  const mounted = await mountAssistant(mock);
  await mounted.wrapper.get('[data-test="start-generation"]').trigger('click');
  await flushPromises();
  assert.equal(mock.history.post.filter((request: AnyRecord) => request.url === '/api/v1/projects/9/planning/proposals').length, 0);
  for (const connection of [pendingConnection, invalidConnection, { ...verifiedConnection, id: 36, name: 'Disabled Test Connection', enabled: false }]) {
    fixtureConnections = [connection];
    await mounted.wrapper.get('[data-test="choose-connection"]').trigger('click');
    await mounted.wrapper.get('[data-test="start-generation"]').trigger('click');
    await flushPromises();
    assert.equal(mock.history.post.filter((request: AnyRecord) => request.url === '/api/v1/projects/9/planning/proposals').length, 0);
  }
  await mounted.wrapper.unmount();
  mock.restore();
});

test('verified connection sends the selected modelConnectionId through the mounted production path', async () => {
  const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
  registerContextMocks(mock);
  mock.onPost('/api/v1/projects/9/planning/proposals').reply(200, api({ executionStatus: 'PENDING', usedProvider: 'MOCK', runId: 'run-1', traceId: 'trace-1' }));
  const mounted = await mountAssistant(mock);
  await mounted.wrapper.get('[data-test="choose-connection"]').trigger('click');
  await mounted.wrapper.get('[data-test="start-generation"]').trigger('click');
  await flushPromises();
  const planningRequests = mock.history.post.filter((request: AnyRecord) => request.url === '/api/v1/projects/9/planning/proposals');
  assert.equal(planningRequests.length, 1, `GET history: ${mock.history.get.map((request: AnyRecord) => request.url).join(' | ')} POST history: ${mock.history.post.map((request: AnyRecord) => `${request.url} ${request.data || ''}`).join(' | ')}`);
  assert.equal(JSON.parse(planningRequests[0].data).modelConnectionId, 33);
  assert.notEqual(mounted.wrapper.text(), '');
  await mounted.wrapper.unmount();
  mock.restore();
});

test('mounted generation action fails closed when confirmed context or profile is unavailable', async () => {
  for (const options of [{ confirmedContextValue: null }, { profileStatus: 'CANDIDATE' }]) {
    const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
    registerContextMocks(mock, project, options);
    const mounted = await mountAssistant(mock);
    await mounted.wrapper.get('[data-test="choose-connection"]').trigger('click');
    await mounted.wrapper.get('[data-test="start-generation"]').trigger('click');
    await flushPromises();
    assert.equal(mock.history.post.filter((request: AnyRecord) => request.url === '/api/v1/projects/9/planning/proposals').length, 0);
    assert.equal(mounted.wrapper.text().includes('未发送 Planning 请求'), true, mounted.wrapper.text());
    await mounted.wrapper.unmount();
    mock.restore();
  }
});

test('prefetch completion after new dialogue does not POST stale Planning or write old result', async () => {
  const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
  registerContextMocks(mock);
  let resolveContext!: (value: unknown) => void;
  const deferred = new Promise<unknown>((resolvePromise) => {
    resolveContext = resolvePromise;
    mock.onGet('/api/v1/projects/9/planning/confirmed-context').reply(() => deferred.then(() => [200, api(confirmedContext)]));
  });
  const mounted = await mountAssistant(mock);
  await mounted.wrapper.get('[data-test="choose-connection"]').trigger('click');
  await mounted.wrapper.get('[data-test="start-generation"]').trigger('click');
  await mounted.wrapper.get('[data-test="new-session"]').trigger('click');
  resolveContext({});
  await flushPromises();
  assert.equal(mock.history.post.filter((request: AnyRecord) => request.url === '/api/v1/projects/9/planning/proposals').length, 0);
  assert.equal(mounted.workspace.activeSessionId.length > 0, true);
  await mounted.wrapper.unmount();
  mock.restore();
});

test('Planning response after logout and user switch is discarded without a second POST', async () => {
  const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
  registerContextMocks(mock);
  let resolvePlanning!: (value: unknown) => void;
  const planningResponse = new Promise<unknown>((resolvePromise) => { resolvePlanning = resolvePromise; });
  mock.onPost('/api/v1/projects/9/planning/proposals').reply(() => planningResponse.then(() => [200, api({ executionStatus: 'COMPLETED', usedProvider: 'REAL', runId: 'run-2', traceId: 'trace-2' })]));
  const mounted = await mountAssistant(mock);
  await mounted.wrapper.get('[data-test="choose-connection"]').trigger('click');
  await mounted.wrapper.get('[data-test="start-generation"]').trigger('click');
  await waitForPost(mock, '/api/v1/projects/9/planning/proposals');
  await mounted.auth.logout();
  mounted.auth.applySession('test-token-202', userTwo as any);
  mounted.workspace.hydrate(userTwo.id);
  resolvePlanning({});
  await flushPromises();
  assert.equal(mock.history.post.filter((request: AnyRecord) => request.url === '/api/v1/projects/9/planning/proposals').length, 1, `POST history: ${mock.history.post.map((request: AnyRecord) => `${request.url} ${request.data || ''}`).join(' | ')}`);
  assert.equal(mounted.workspace.userId, 202);
  await mounted.wrapper.unmount();
  mock.restore();
});

test('Planning error after a session switch is discarded without an old error toast', async () => {
  const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
  registerContextMocks(mock);
  let rejectPlanning!: (reason?: unknown) => void;
  const planningResponse = new Promise<unknown>((_resolvePromise, rejectPromise) => { rejectPlanning = rejectPromise; });
  mock.onPost('/api/v1/projects/9/planning/proposals').reply(() => planningResponse);
  const mounted = await mountAssistant(mock);
  await mounted.wrapper.get('[data-test="choose-connection"]').trigger('click');
  await mounted.wrapper.get('[data-test="start-generation"]').trigger('click');
  await waitForPost(mock, '/api/v1/projects/9/planning/proposals');
  await mounted.wrapper.get('[data-test="new-session"]').trigger('click');
  rejectPlanning(new Error('old-provider-error'));
  await flushPromises();
  assert.equal(mock.history.post.filter((request: AnyRecord) => request.url === '/api/v1/projects/9/planning/proposals').length, 1);
  assert.equal(document.body.textContent?.includes('old-provider-error'), false);
  assert.equal(mounted.wrapper.text().includes('已丢弃旧错误'), true, mounted.wrapper.text());
  await mounted.wrapper.unmount();
  mock.restore();
});

test('dialogue save failure after user switch does not change the new session message list', async () => {
  const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
  registerContextMocks(mock);
  let rejectDialogue!: (reason?: unknown) => void;
  const dialogueResponse = new Promise<unknown>((_resolvePromise, rejectPromise) => { rejectDialogue = rejectPromise; });
  mock.onPost('/api/projects/9/dialogues').reply(() => dialogueResponse.then(() => [200, api({})]));
  const mounted = await mountAssistant(mock);
  await mounted.wrapper.get('[data-test="choose-connection"]').trigger('click');
  await mounted.wrapper.get('[data-test="start-generation"]').trigger('click');
  await mounted.auth.logout();
  mounted.auth.applySession('test-token-202', userTwo as any);
  mounted.workspace.hydrate(userTwo.id);
  rejectDialogue(new Error('old-session-save-failed'));
  await flushPromises();
  assert.equal(mounted.workspace.userId, 202);
  assert.equal(mounted.wrapper.text().includes('old-session-save-failed'), false);
  await mounted.wrapper.unmount();
  mock.restore();
});

test('dialogue save success after a new dialogue does not append the old message to the new UI', async () => {
  const mock = new AxiosMockAdapter(http, { onNoMatch: 'throwException' });
  registerContextMocks(mock);
  let resolveDialogue!: (value: unknown) => void;
  const dialogueResponse = new Promise<unknown>((resolvePromise) => { resolveDialogue = resolvePromise; });
  mock.onPost('/api/projects/9/dialogues').reply(() => dialogueResponse.then(() => [200, api({ id: 999, projectId: 9, sessionId: 'old-session', sender: 'TEACHER', content: 'old message', roundNo: 1, createdAt: '2026-08-30T00:00:00Z' })]));
  const mounted = await mountAssistant(mock);
  await mounted.wrapper.get('[data-test="start-progress"]').trigger('click');
  await mounted.wrapper.get('[data-test="new-session"]').trigger('click');
  resolveDialogue({});
  await flushPromises();
  assert.equal(mounted.wrapper.get('[data-test="message-count"]').text(), '1');
  assert.equal(mounted.wrapper.text().includes('old message'), false);
  await mounted.wrapper.unmount();
  mock.restore();
});
