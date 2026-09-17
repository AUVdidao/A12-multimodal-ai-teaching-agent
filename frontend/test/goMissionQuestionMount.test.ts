import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer, type ViteDevServer } from 'vite';
import { compileScript, parse } from '@vue/compiler-sfc';
import AxiosMockAdapter from 'axios-mock-adapter';

const frontendRoot = resolve(process.cwd());
const teacher = { id: 7, username: 'question-teacher', displayName: 'Question Teacher', roles: ['TEACHER'], activeRole: 'TEACHER' };
const mission = { id: 7, ownerTeacherId: 7, source: 'SELF_CREATED', title: '光的干涉', description: '', status: 'IN_PROGRESS', selectedModelConnectionId: null, createdAt: '2026-09-05T00:00:00Z', updatedAt: '2026-09-05T00:00:00Z' };
const baseDetail = { mission, messages: [], files: [], currentDraft: null, generationJobs: [], artifacts: [] };
const planningDraft = { id: 'draft-1', missionId: 7, ownerUserId: 7, version: 1, markdown: '围绕 TCP 三次握手组织课堂内容。', structuredPlan: { subject: '计算机网络', slideCount: 12, slides: ['连接建立', '三次握手', '抓包练习'] }, outputStage: 'PLAN_DRAFT', createdAt: '2026-09-05T00:05:00Z' };
const lockedSpecification = { id: 'spec-1', missionId: 7, sourceDraftId: 'draft-1', version: 1, specification: { slides: ['连接建立', '三次握手', '抓包练习'] }, templateBinding: { templateOriginalName: '教师模板.pptx' }, contentHash: '1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef', createdAt: '2026-09-05T00:06:00Z' };
const readyTemplateBinding = { bindingKind: 'LESSONFORGE_UPSTREAM_TEMPLATE_BINDING', missionId: 7, missionFileId: 11, fileObjectId: 12, ownerUserId: 7, templateStorageKey: 'templates/teacher.pptx', templateFileSha256: '1234567890123456789012345678901234567890123456789012345678901234', templateOriginalName: '教师模板.pptx', executionReady: true, engineNativeProfilePresent: true };
const readyTemplateFile = { id: 11, missionId: 7, file: { id: 12, originalName: '教师模板.pptx', mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', size: 128, sha256: readyTemplateBinding.templateFileSha256 }, role: 'TEMPLATE', provenance: 'TEACHER', parseStatus: 'READY', createdAt: '2026-09-05T00:04:00Z' };
const unanswered = { id: 'question-1', missionId: 7, ownerUserId: 7, agentRunId: 'run-question-1', outputStage: 'QUESTION', referenceType: 'QUESTION', referenceId: 'question-1', text: '请选择年级', type: 'SINGLE_CHOICE', options: ['初中', '高中'], latestAnswer: null, createdAt: '2026-09-05T00:01:00Z' };
const answered = { ...unanswered, latestAnswer: { id: 'answer-1', selectedValues: ['高中'], textAnswer: '', answeredAt: '2026-09-05T00:02:00Z' } };

let dom: JSDOM;
let viteServer: ViteDevServer;
let mount: typeof import('@vue/test-utils')['mount'];
let flushPromises: typeof import('@vue/test-utils')['flushPromises'];
let createPinia: typeof import('pinia')['createPinia'];
let setActivePinia: typeof import('pinia')['setActivePinia'];
let createRouter: typeof import('vue-router')['createRouter'];
let createMemoryHistory: typeof import('vue-router')['createMemoryHistory'];
let h: typeof import('vue')['h'];
let GoMissionWorkspaceView: any;
let GoQuestionCard: any;
let useAuthStore: typeof import('../src/stores/auth')['useAuthStore'];
let goHttp: any;
let httpMock: AxiosMockAdapter;

function setGlobal(name: string, value: unknown) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

class FakeEventSource {
  onmessage: ((event: Event) => void) | null = null;
  onerror: (() => void) | null = null;
  readonly url: string;
  constructor(url: string) { this.url = url; }
  addEventListener() {}
  close() {}
}

function installDom() {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/lessonforge/missions/7' });
  for (const [name, value] of Object.entries({
    window: dom.window,
    history: dom.window.history,
    location: dom.window.location,
    document: dom.window.document,
    navigator: dom.window.navigator,
    localStorage: dom.window.localStorage,
    HTMLElement: dom.window.HTMLElement,
    SVGElement: dom.window.SVGElement,
    Element: dom.window.Element,
    Node: dom.window.Node,
    Event: dom.window.Event,
    CustomEvent: dom.window.CustomEvent,
    MutationObserver: dom.window.MutationObserver,
    EventSource: FakeEventSource,
  })) setGlobal(name, value);
}

function stubs() {
  return {
    LessonForgeFrame: { props: { workspace: Boolean }, setup(_props: unknown, context: { slots: Record<string, () => unknown> }) { return () => h('div', context.slots.default?.()); } },
    LessonForgeComposer: { props: ['modelValue', 'files', 'connections', 'selectedConnection', 'working', 'placeholder'], emits: ['send', 'files-selected', 'select-connection', 'manage-connections'], template: '<div data-test="composer-stub" />' },
    ModelConnectionDrawer: { props: ['modelValue', 'selectedConnection', 'selectedConnectionId'], emits: ['update:connection', 'connections-loaded'], template: '<div data-test="drawer-stub" />' },
    GoQuestionCard: { props: ['question', 'missionId', 'userId'], emits: ['submitted'], template: '<section data-test="go-active-question"><h2>{{ question.text }}</h2><button data-test="go-question-submit" type="button" @click="$emit(\'submitted\')">submit</button></section>' },
    GoQuestionHistoryCard: { props: ['questions'], template: '<section data-test="go-question-history"><span v-for="question in questions" :key="question.id">已回答 {{ question.text }} {{ question.latestAnswer && question.latestAnswer.selectedValues.join(\' \') }} {{ question.latestAnswer && question.latestAnswer.textAnswer }}</span></section>' },
    GoPlanDraftCard: { props: ['draft', 'locked', 'facts', 'approving', 'approvalBlocker'], emits: ['approve'], template: '<section data-test="go-plan-draft"><button v-if="!locked" data-test="approve-plan" type="button" :disabled="approving || Boolean(approvalBlocker)" @click="$emit(\'approve\')">Approve 方案</button><p v-if="approvalBlocker" data-test="approval-prerequisite">{{ approvalBlocker }}</p></section>' },
  };
}

async function settle() {
  await flushPromises();
  await flushPromises();
}

before(async () => {
  installDom();
  const source = await readFile(resolve(frontendRoot, 'src/views/GoMissionWorkspaceView.vue'), 'utf8');
  const cardSource = await readFile(resolve(frontendRoot, 'src/components/GoQuestionCard.vue'), 'utf8');
  const historySource = await readFile(resolve(frontendRoot, 'src/components/GoQuestionHistoryCard.vue'), 'utf8');
  const planSource = await readFile(resolve(frontendRoot, 'src/components/GoPlanDraftCard.vue'), 'utf8');
  const parsed = parse(source, { filename: 'src/views/GoMissionWorkspaceView.vue' });
  const cardParsed = parse(cardSource, { filename: 'src/components/GoQuestionCard.vue' });
  const historyParsed = parse(historySource, { filename: 'src/components/GoQuestionHistoryCard.vue' });
  const planParsed = parse(planSource, { filename: 'src/components/GoPlanDraftCard.vue' });
  const compiled = compileScript(parsed.descriptor, { id: 'data-v-go-question-mount', inlineTemplate: true });
  const compiledCard = compileScript(cardParsed.descriptor, { id: 'data-v-go-question-card-mount', inlineTemplate: true });
  const compiledHistory = compileScript(historyParsed.descriptor, { id: 'data-v-go-question-history-mount', inlineTemplate: true });
  const compiledPlan = compileScript(planParsed.descriptor, { id: 'data-v-go-plan-draft-mount', inlineTemplate: true });
  viteServer = await createServer({
    root: frontendRoot,
    configFile: resolve(frontendRoot, 'vite.config.ts'),
    server: { middlewareMode: true },
    logLevel: 'error',
    plugins: [{
      name: 'a12-go-question-mount-entry',
      resolveId(id) {
        if (id === '/@a12-go-question-mount-entry.ts' || id === '/@a12-go-question-card.ts' || id === '/@a12-go-question-history.ts' || id === '/@a12-go-plan-draft.ts') return id;
        if (id.split('?')[0].endsWith('/src/components/GoQuestionCard.vue')) return '/@a12-go-question-card.ts';
        if (id.split('?')[0].endsWith('/src/components/GoQuestionHistoryCard.vue')) return '/@a12-go-question-history.ts';
        if (id.split('?')[0].endsWith('/src/components/GoPlanDraftCard.vue')) return '/@a12-go-plan-draft.ts';
        return undefined;
      },
      load(id) {
        if (id === '/@a12-go-question-mount-entry.ts') return compiled.content;
        if (id === '/@a12-go-question-card.ts') return compiledCard.content;
        if (id === '/@a12-go-question-history.ts') return compiledHistory.content;
        if (id === '/@a12-go-plan-draft.ts') return compiledPlan.content;
        return undefined;
      },
    }],
  });
  const [vueTestUtils, vue, pinia, router, viewModule, authModule, goModule] = await Promise.all([
    import('@vue/test-utils'),
    import('vue'),
    import('pinia'),
    import('vue-router'),
    viteServer.ssrLoadModule('/@a12-go-question-mount-entry.ts'),
    viteServer.ssrLoadModule('/src/stores/auth.ts'),
    viteServer.ssrLoadModule('/src/api/go.ts'),
  ]);
  mount = vueTestUtils.mount;
  flushPromises = vueTestUtils.flushPromises;
  createPinia = pinia.createPinia;
  setActivePinia = pinia.setActivePinia;
  createRouter = router.createRouter;
  createMemoryHistory = router.createMemoryHistory;
  h = vue.h;
  GoMissionWorkspaceView = viewModule.default;
  GoQuestionCard = (await viteServer.ssrLoadModule('/@a12-go-question-card.ts')).default;
  useAuthStore = authModule.useAuthStore;
  goHttp = goModule.goHttp;
  httpMock = new AxiosMockAdapter(goHttp);
});

after(async () => {
  httpMock?.restore();
  await viteServer?.close();
  dom?.window.close();
});

test('mounted Go Mission workspace refreshes questions and folds the accepted answer into history', async () => {
  let answeredOnServer = false;
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(200, baseDetail);
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(() => [200, answeredOnServer ? [answered] : [unanswered]]);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, []);

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-test-session', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  assert.equal(wrapper.find('[data-test="go-active-question"]').exists(), true);
  assert.equal(wrapper.find('[data-test="composer-stub"]').exists(), false, 'the general chat composer is hidden while one clarification question is active');
  const submit = wrapper.get('[data-test="go-question-submit"]');
  await submit.trigger('click');
  answeredOnServer = true;
  await submit.trigger('click');
  await settle();
  assert.equal(wrapper.find('[data-test="go-active-question"]').exists(), false, '202 is followed by server refresh and history folding');
  assert.equal(wrapper.find('[data-test="go-question-history"]').exists(), true);
  assert.equal(wrapper.find('[data-test="composer-stub"]').exists(), true, 'the chat composer returns after the clarification is answered');
  assert.match(wrapper.get('[data-test="go-question-history"]').text(), /高中/);
  await wrapper.unmount();

  const refreshed = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();
  assert.equal(refreshed.find('[data-test="go-active-question"]').exists(), false);
  assert.match(refreshed.get('[data-test="go-question-history"]').text(), /已回答/);
  await refreshed.unmount();
});

test('Mission workspace keeps messages, questions, and plan drafts in one chronological chat timeline', async () => {
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(200, {
    ...baseDetail,
    files: [readyTemplateFile],
    currentDraft: planningDraft,
    messages: [
      { id: 1, missionId: 7, ownerUserId: 7, role: 'USER', content: '最初需求', messageType: 'MESSAGE', createdAt: '2026-09-05T00:00:00Z' },
      { id: 2, missionId: 7, ownerUserId: 7, role: 'ASSISTANT', content: '已收到课程要求', messageType: 'MESSAGE', createdAt: '2026-09-05T00:03:00Z' },
      { id: 3, missionId: 7, ownerUserId: 7, role: 'USER', content: '请增加课堂实验', messageType: 'MESSAGE', createdAt: '2026-09-05T00:06:00Z' },
    ],
  });
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, [answered]);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, []);

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-chat-timeline', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  const children = Array.from(wrapper.get('.lf-conversation-scroll').element.children);
  const indexOfText = (text: string) => children.findIndex((element) => element.textContent?.includes(text));
  const questionIndex = children.findIndex((element) => element.matches('[data-test="go-question-history"]'));
  const planIndex = children.findIndex((element) => element.matches('[data-test="go-plan-draft"]'));
  assert.ok(indexOfText('最初需求') < questionIndex, 'the first teacher turn stays before the later clarification');
  assert.ok(questionIndex < indexOfText('已收到课程要求'), 'the clarification is placed by its server timestamp');
  assert.ok(indexOfText('已收到课程要求') < planIndex, 'the plan follows the assistant turn that preceded it');
  assert.ok(planIndex < indexOfText('请增加课堂实验'), 'a later teacher turn is appended after the plan instead of jumping above it');
  await wrapper.unmount();
});

test('route stage does not manufacture an Agent conversation when the server has no context', async () => {
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(200, baseDetail);
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, []);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, []);

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-question-preview', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7?stage=question');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  assert.equal(wrapper.findAll('.lf-message').length, 0);
  assert.equal(wrapper.find('[data-test="go-active-question"]').exists(), false);
  assert.equal(wrapper.find('[data-test="go-initial-conversation"]').exists(), true);
  assert.doesNotMatch(wrapper.text(), /这节课最希望学生掌握哪一部分/);
  assert.doesNotMatch(wrapper.text(), /LessonForge 正在确认课程重点/);
  assert.equal(wrapper.findAll('.lf-activity').length, 0);
  assert.equal(httpMock.history.post.length, 0, 'empty server context stays empty and does not create a fake Agent state');
  await wrapper.unmount();
});

test('plan stage does not manufacture a draft, conversation, or activity when the server has no context', async () => {
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(200, baseDetail);
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, []);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, []);

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-plan-preview', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7?stage=plan');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  assert.equal(wrapper.find('[data-test="go-plan-draft"]').exists(), false);
  assert.equal(wrapper.find('[data-test="go-active-question"]').exists(), false);
  assert.equal(wrapper.findAll('.lf-message').length, 0);
  assert.equal(wrapper.find('[data-test="go-initial-conversation"]').exists(), true);
  assert.equal(wrapper.findAll('.lf-activity').length, 0);
  assert.doesNotMatch(wrapper.text(), /TCP 三次握手/);
  assert.doesNotMatch(wrapper.text(), /已生成计划草稿 v1/);
  assert.equal(httpMock.history.post.length, 0, 'empty server context stays empty and does not create a fake plan');
  await wrapper.unmount();
});

test('Mission workspace renders the persisted AgentRun state instead of inferring a fake success', async () => {
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(200, baseDetail);
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, []);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, [{ id: 'run-waiting', missionId: 7, ownerUserId: 7, status: 'WAITING_INPUTS', modelConnectionId: null, createdAt: '2026-09-05T00:03:00Z' }]);

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-run-status', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  assert.match(wrapper.get('.lf-conversation-progress__state').text(), /等待补充/);
  assert.match(wrapper.get('.lf-conversation-progress__phase').text(), /等待补充/);
  assert.match(wrapper.get('.lf-mission-facts').text(), /AgentRun等待补充/);
  await wrapper.unmount();
});

test('Mission workspace uses the newest AgentRun returned by the server', async () => {
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(200, baseDetail);
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, []);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, [
    { id: 'run-new', missionId: 7, ownerUserId: 7, status: 'QUEUED', modelConnectionId: null, createdAt: '2026-09-05T00:04:00Z' },
    { id: 'run-old', missionId: 7, ownerUserId: 7, status: 'WAITING_INPUTS', modelConnectionId: null, createdAt: '2026-09-05T00:03:00Z' },
  ]);

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-newest-run', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  assert.match(wrapper.get('[data-test="go-agent-run-status"]').text(), /排队中/);
  assert.doesNotMatch(wrapper.get('[data-test="go-agent-run-status"]').text(), /等待补充/);
  await wrapper.unmount();
});

test('Plan draft approval calls the real endpoint and renders the persisted Locked Specification card', async () => {
  let lockedOnServer = false;
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(() => [200, { ...baseDetail, files: [readyTemplateFile], currentDraft: planningDraft, lockedSpecification: lockedOnServer ? lockedSpecification : null }]);
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, []);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, []);
  httpMock.onPost('/api/planning/draft-1/approve').reply(() => { lockedOnServer = true; return [201, { lockedSpecification }]; });

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-approve-test', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  assert.equal(wrapper.find('[data-test="approve-plan"]').exists(), true);
  await wrapper.get('[data-test="approve-plan"]').trigger('click');
  await settle();
  assert.equal(httpMock.history.post.filter((request: AnyRecord) => request.url === '/api/planning/draft-1/approve').length, 1);
  assert.equal(wrapper.find('[data-test="go-locked-specification"]').exists(), true);
  assert.match(wrapper.get('[data-test="go-locked-specification"]').text(), /已锁定课件规格/);
  assert.equal(wrapper.find('[data-test="go-generation-waiting"]').exists(), true);
  assert.match(wrapper.get('[data-test="go-generation-waiting"]').text(), /等待生成/);
  assert.equal(wrapper.find('[data-test="approve-plan"]').exists(), false);
  await wrapper.unmount();
});

test('Plan draft approval is blocked with an actionable reason when no PPTX template is bound', async () => {
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(200, { ...baseDetail, currentDraft: planningDraft });
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, []);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, []);

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-approve-prerequisite', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  const button = wrapper.get('[data-test="approve-plan"]');
  assert.equal(button.attributes('disabled'), '');
  assert.match(wrapper.get('[data-test="approval-prerequisite"]').text(), /批准前需要先上传一份 PPTX 模板/);
  await button.trigger('click');
  assert.equal(httpMock.history.post.filter((request: AnyRecord) => request.url === '/api/planning/draft-1/approve').length, 0);
  await wrapper.unmount();
});

test('identity-only template binding is handed to the server fallback policy', async () => {
  const notReadySpec = { ...lockedSpecification, templateBinding: { ...readyTemplateBinding, executionReady: false, engineNativeProfilePresent: false } };
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(200, { ...baseDetail, files: [readyTemplateFile], lockedSpecification: notReadySpec });
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, []);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, []);
  httpMock.onPost('/api/missions/7/generation-jobs').reply((config) => {
    assert.deepEqual(JSON.parse(String(config.data)), { specificationId: 'spec-1', specificationVersion: 1, fallbackPolicy: 'AUTO' });
    return [201, { generationJob: { id: 'job-fallback', missionId: 7, specificationId: 'spec-1', specificationVersion: 1, status: 'SUCCEEDED', generationMode: 'SYSTEM_DEFAULT_TEMPLATE' }, created: true }];
  });

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-generation-profile-gate', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  const button = wrapper.get('[data-test="request-generation"]');
  await button.trigger('click');
  await settle();
  assert.equal(httpMock.history.post.filter((request: AnyRecord) => request.url === '/api/missions/7/generation-jobs').length, 1);
  await wrapper.unmount();
});

test('locked specification starts real generation and polls the persisted job to an artifact', async () => {
  let reads = 0;
  let requested = false;
  const readySpec = { ...lockedSpecification, templateBinding: readyTemplateBinding };
  const queuedJob = { id: 'job-1', missionId: 7, specificationId: 'spec-1', specificationVersion: 1, status: 'QUEUED', currentSlide: 0, totalSlides: 3, createdAt: '2026-09-05T00:07:00Z' };
  const artifact = { id: 'artifact-1', missionId: 7, generationJobId: 'job-1', file: { id: 13, originalName: 'lesson.pptx', mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', size: 2048, sha256: readyTemplateBinding.templateFileSha256 }, version: 1, contentType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', sha256: readyTemplateBinding.templateFileSha256, size: 2048, status: 'READY', createdAt: '2026-09-05T00:08:00Z' };
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(() => {
    reads += 1;
    const terminal = requested && reads >= 3;
    return [200, { ...baseDetail, files: [readyTemplateFile], lockedSpecification: readySpec, generationJobs: terminal ? [{ ...queuedJob, status: 'SUCCEEDED', artifactId: artifact.id }] : requested ? [queuedJob] : [], artifacts: terminal ? [artifact] : [] }];
  });
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, []);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, []);
  httpMock.onPost('/api/missions/7/generation-jobs').reply((config) => {
    requested = true;
    assert.deepEqual(JSON.parse(String(config.data)), { specificationId: 'spec-1', specificationVersion: 1, fallbackPolicy: 'AUTO' });
    return [201, { generationJob: queuedJob, created: true }];
  });

  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-generation-request', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();
  assert.equal(wrapper.find('[data-test="request-generation"]').exists(), true);
  await wrapper.get('[data-test="request-generation"]').trigger('click');
  await settle();
  assert.equal(wrapper.find('[data-test="generation-job-job-1"]').exists(), true);
  assert.equal(wrapper.get('[data-test="request-generation"]').attributes('disabled'), '', 'an active generation job disables a duplicate request');
  await new Promise((resolve) => setTimeout(resolve, 1700));
  await settle();
  assert.equal(wrapper.find('[data-test="request-generation"]').exists(), true, 'a terminal job with an existing Artifact still exposes regeneration');
  assert.match(wrapper.get('[data-test="request-generation"]').text(), /再次生成 PPT/);
  assert.equal(wrapper.find('.go-artifact-card').exists(), true);
  assert.equal(wrapper.find('a[href$="/api/artifacts/artifact-1/download"]').exists(), true);
  await wrapper.unmount();
});

test('Mission workspace exposes draggable left and right rails and keeps their widths per teacher', async () => {
  httpMock.reset();
  httpMock.onGet('/api/missions/7').reply(200, baseDetail);
  httpMock.onGet('/api/model-connections').reply(200, []);
  httpMock.onGet('/api/missions/7/questions').reply(200, []);
  httpMock.onGet('/api/missions/7/agent-runs').reply(200, []);

  dom.window.localStorage.clear();
  const pinia = createPinia();
  setActivePinia(pinia);
  useAuthStore(pinia).applySession('go-resize-test', teacher as any);
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/lessonforge/missions/:missionId', component: GoMissionWorkspaceView }] });
  await router.push('/lessonforge/missions/7');
  await router.isReady();
  const wrapper = mount(GoMissionWorkspaceView, { global: { plugins: [pinia, router], stubs: stubs() } });
  await settle();

  const workspace = wrapper.get('[data-test="go-mission-workspace"]');
  assert.equal(workspace.classes().includes('lf-workspace--resizable'), true);
  assert.equal(workspace.get('[data-test="resize-left-rail"]').attributes('aria-label'), '调整左侧栏宽度');
  assert.equal(workspace.get('[data-test="resize-right-board"]').attributes('aria-label'), '调整右侧看板宽度');

  await workspace.get('[data-test="resize-left-rail"]').trigger('pointerdown', { button: 0, clientX: 172 });
  assert.equal(dom.window.document.body.classList.contains('lf-is-resizing'), true);
  const leftMove = new dom.window.Event('pointermove');
  Object.defineProperty(leftMove, 'clientX', { value: 232 });
  dom.window.dispatchEvent(leftMove);
  await settle();
  assert.match(workspace.attributes('style') || '', /--lf-mission-index-width:\s*232px/);
  dom.window.dispatchEvent(new dom.window.Event('pointerup'));
  assert.equal(dom.window.document.body.classList.contains('lf-is-resizing'), false);

  await workspace.get('[data-test="resize-right-board"]').trigger('pointerdown', { button: 0, clientX: 700 });
  const rightMove = new dom.window.Event('pointermove');
  Object.defineProperty(rightMove, 'clientX', { value: 650 });
  dom.window.dispatchEvent(rightMove);
  await settle();
  assert.match(workspace.attributes('style') || '', /--lf-mission-board-width:\s*342px/);
  const tooSmallMove = new dom.window.Event('pointermove');
  Object.defineProperty(tooSmallMove, 'clientX', { value: 1000 });
  dom.window.dispatchEvent(tooSmallMove);
  await settle();
  assert.match(workspace.attributes('style') || '', /--lf-mission-board-width:\s*260px/);
  dom.window.dispatchEvent(new dom.window.Event('pointerup'));
  assert.deepEqual(JSON.parse(dom.window.localStorage.getItem('lessonforge:workspace-layout:7') || '{}'), { rail: 232, board: 260 });

  await wrapper.unmount();
});

test('mounted GoQuestionCard blocks empty answers and sends one real payload for a 202 response', async () => {
  let answerRequests = 0;
  let capturedPayload: unknown;
  httpMock.reset();
  httpMock.onPost('/api/questions/question-1/answers').reply((config) => {
    answerRequests += 1;
    capturedPayload = JSON.parse(config.data as string);
    return [202, { answerId: 'answer-1', agentRunId: 'run-answer-1' }];
  });
  const wrapper = mount(GoQuestionCard, { props: { question: unanswered, missionId: 7, userId: 7 } });
  await settle();
  assert.match(wrapper.text(), /A/);
  assert.match(wrapper.text(), /B/);
  assert.equal(wrapper.find('[data-test="go-question-text"]').exists(), false, 'choice clarification must not render a free-text box');
  const submit = wrapper.get('[data-test="go-question-submit"]');
  await submit.trigger('click');
  assert.equal(answerRequests, 0, 'empty choice must not POST');
  await wrapper.get('input[type="radio"][value="高中"]').setValue(true);
  await Promise.all([submit.trigger('click'), submit.trigger('click')]);
  await settle();
  assert.equal(answerRequests, 1, 'fast double click must issue one POST');
  assert.deepEqual(capturedPayload, { selectedValues: ['高中'], textAnswer: '' });
  await wrapper.unmount();
});

test('delayed answer failure after Mission and teacher switch does not write old error state', async () => {
  let finish: ((value: [number, unknown]) => void) | undefined;
  httpMock.reset();
  httpMock.onPost('/api/questions/question-1/answers').reply(() => new Promise((resolve) => {
    finish = resolve as (value: [number, unknown]) => void;
  }));
  const wrapper = mount(GoQuestionCard, { props: { question: unanswered, missionId: 7, userId: 7 } });
  await settle();
  await wrapper.get('input[type="radio"][value="高中"]').setValue(true);
  const request = wrapper.get('[data-test="go-question-submit"]').trigger('click');
  await new Promise((resolve) => setTimeout(resolve, 0));
  await wrapper.setProps({ missionId: 8, userId: 8 });
  finish?.([500, { error: { code: 'OLD_CONTEXT' } }]);
  await request;
  await settle();
  assert.equal(wrapper.find('[data-test="go-question-error"]').exists(), false);
  await wrapper.unmount();
});
