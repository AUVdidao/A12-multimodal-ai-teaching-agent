import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer, type ViteDevServer } from 'vite';
import { compileScript, parse } from '@vue/compiler-sfc';
import { DEMO_TEACHER_USER_ID } from '../src/utils/missionFrontendAdapter.ts';

const frontendRoot = resolve(process.cwd());
const userOne = { id: DEMO_TEACHER_USER_ID, username: 'teacher-demo', displayName: 'Teacher One', roles: ['TEACHER'], activeRole: 'TEACHER' };
const userTwo = { id: 202, username: 'teacher-202', displayName: 'Teacher Two', roles: ['TEACHER'], activeRole: 'TEACHER' };

let dom: JSDOM;
let viteServer: ViteDevServer;
let mount: typeof import('@vue/test-utils')['mount'];
let flushPromises: typeof import('@vue/test-utils')['flushPromises'];
let createPinia: typeof import('pinia')['createPinia'];
let setActivePinia: typeof import('pinia')['setActivePinia'];
let MissionWorkspaceView: any;
let useAuthStore: typeof import('../src/stores/auth')['useAuthStore'];
let useConversationWorkspaceStore: typeof import('../src/stores/conversationWorkspace')['useConversationWorkspaceStore'];

function setGlobal(name: string, value: unknown) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

function installDom() {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/assistant' });
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
  })) setGlobal(name, value);
}

function componentStubs() {
  const SidebarStub = {
    name: 'MissionWorkspaceSidebar',
    props: { missions: { type: Array, default: () => [] }, currentMissionId: { type: String, default: null } },
    emits: ['select-mission'],
    template: `<aside aria-label="Mission List"><button v-for="mission in missions" :key="mission.id" class="mission-sidebar__item" @click="$emit('select-mission', mission.id)"><strong>{{ mission.title }}</strong><span>{{ mission.status }}</span><small>截止 {{ mission.deadline }}</small></button><div v-if="!missions.length" class="mission-sidebar__empty">当前教师没有可见 Mission。</div></aside>`,
  };
  const ConversationStub = {
    name: 'AssistantConversation',
    props: { modelValue: { type: String, default: '' }, messages: { type: Array, default: () => [] }, files: { type: Array, default: () => [] } },
    emits: ['update:modelValue', 'send', 'file-select', 'open-connection'],
    template: `<section class="assistant-conversation" data-test="assistant-conversation">
      <strong data-test="conversation-title">{{ $attrs['workspace-title'] }}</strong>
      <div data-test="message-count">{{ messages.length }}</div>
      <div data-test="message-content">{{ messages.map((message) => message.content).join('|') }}</div>
      <div data-test="file-count">{{ files.length }}</div>
      <div v-for="file in files" :key="file.id" data-test="file-item"><strong>{{ file.originalFilename }}</strong><span data-test="file-status">{{ file.displayStatus }}</span></div>
      <textarea data-test="composer" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />
       <button type="button" data-test="send" @click="$emit('send')">send</button>
       <button type="button" data-test="open-connection" @click="$emit('open-connection')">connection</button>
       <input data-test="context-file-input" type="file" @change="$emit('file-select', $event.target.files?.[0])" />
    </section>`,
  };
  const DrawerStub = {
    name: 'ModelConnectionDrawer',
    props: { modelValue: Boolean, selectedConnectionId: { type: Number, default: null } },
    emits: ['update:connection'],
    template: '<section data-test="connection-drawer"><button data-test="choose-connection" @click="$emit(\'update:connection\', { id: 77, name: \'Fixture connection\' })">choose</button><span data-test="selected-connection">{{ selectedConnectionId }}</span></section>',
  };
  return { MissionWorkspaceSidebar: SidebarStub, AssistantConversation: ConversationStub, ModelConnectionDrawer: DrawerStub };
}

async function settle() {
  await flushPromises();
  await flushPromises();
}

before(async () => {
  installDom();
  const source = await readFile(resolve(frontendRoot, 'src/views/MissionWorkspaceView.vue'), 'utf8');
  const parsed = parse(source, { filename: 'src/views/MissionWorkspaceView.vue' });
  const compiled = compileScript(parsed.descriptor, { id: 'data-v-mission-workspace-mount', inlineTemplate: true });
  viteServer = await createServer({
    root: frontendRoot,
    configFile: resolve(frontendRoot, 'vite.config.ts'),
    server: { middlewareMode: true },
    logLevel: 'error',
    plugins: [{
      name: 'a12-mission-workspace-mount-entry',
      resolveId(id) { return id === '/@a12-mission-workspace-mount-entry.ts' ? id : undefined; },
      load(id) { return id === '/@a12-mission-workspace-mount-entry.ts' ? compiled.content : undefined; },
    }],
  });
  const [vueTestUtils, pinia, viewModule, authModule, conversationModule] = await Promise.all([
    import('@vue/test-utils'),
    import('pinia'),
    viteServer.ssrLoadModule('/@a12-mission-workspace-mount-entry.ts'),
    viteServer.ssrLoadModule('/src/stores/auth.ts'),
    viteServer.ssrLoadModule('/src/stores/conversationWorkspace.ts'),
  ]);
  mount = vueTestUtils.mount;
  flushPromises = vueTestUtils.flushPromises;
  createPinia = pinia.createPinia;
  setActivePinia = pinia.setActivePinia;
  MissionWorkspaceView = viewModule.default;
  useAuthStore = authModule.useAuthStore;
  useConversationWorkspaceStore = conversationModule.useConversationWorkspaceStore;
});

after(async () => {
  await viteServer?.close();
  dom?.window.close();
});

test('mounted Mission workspace covers decision gates, mission-scoped sessions, connection isolation, refresh, and user switch', async () => {
  localStorage.clear();
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('test-token-demo-teacher', userOne as any);
  const workspace = useConversationWorkspaceStore(pinia);
  const wrapper = mount(MissionWorkspaceView, {
    global: { plugins: [pinia], stubs: componentStubs() },
  });
  await settle();

  assert.equal(wrapper.findAll('.mission-sidebar__item').length, 6);
  assert.match(wrapper.find('.mission-sidebar__item').text(), /截止 2026-09-06/);
  assert.doesNotMatch(wrapper.find('.mission-sidebar__item').text(), /围绕光的干涉现象/);

  await wrapper.get('[data-test="reject-mission"]').trigger('click');
  assert.equal(wrapper.get('[data-test="reject-error"]').text(), '请填写拒绝原因后再提交。');
  assert.equal(wrapper.find('[data-test="mission-assignment"]').exists(), true);

  await wrapper.get('[data-test="accept-mission"]').trigger('click');
  await settle();
  assert.equal(wrapper.find('[data-test="mission-conversation"]').exists(), true);
  assert.equal(wrapper.find('[data-test="waiting-first-message"]').exists(), true);
  assert.equal(workspace.activeSession?.missionId, 'mission-101-assigned');
  assert.equal(wrapper.text().includes('已读取当前 Task 上下文'), false);
  assert.equal(wrapper.text().includes('AI 将结合当前项目数据回答'), false);

  await wrapper.get('[data-test="composer"]').setValue('请记录我的 Mission 首句');
  await wrapper.get('[data-test="send"]').trigger('click');
  await settle();
  assert.equal(wrapper.get('[data-test="message-count"]').text(), '2');
  assert.match(wrapper.get('[data-test="message-content"]').text(), /未调用 AI 或 Planning/);

  await wrapper.findAll('.mission-sidebar__item')[1].trigger('click');
  await settle();
  assert.equal(workspace.activeSession?.missionId, 'mission-101-accepted');
  assert.equal(wrapper.get('[data-test="message-count"]').text(), '0');
  await wrapper.get('[data-test="open-connection"]').trigger('click');
  await wrapper.get('[data-test="choose-connection"]').trigger('click');
  assert.equal(workspace.selectedConnectionId, 77);
  await wrapper.get('[data-test="composer"]').setValue('记录已接受 Mission 的首句');
  await wrapper.get('[data-test="send"]').trigger('click');
  await settle();
  assert.equal(wrapper.get('[data-test="message-count"]').text(), '2');

  const contextFile = new File(['fixture'], 'lesson-notes.pdf', { type: 'application/pdf' });
  const contextFileInput = wrapper.get('[data-test="context-file-input"]').element as HTMLInputElement;
  Object.defineProperty(contextFileInput, 'files', { configurable: true, value: [contextFile] });
  await wrapper.get('[data-test="context-file-input"]').trigger('change');
  await settle();
  assert.equal(wrapper.get('[data-test="file-count"]').text(), '1');
  assert.match(wrapper.get('[data-test="file-item"]').text(), /lesson-notes\.pdf/);
  assert.match(wrapper.get('[data-test="file-status"]').text(), /仅本地占位 · 未上传/);
  assert.match(wrapper.text(), /仅本地占位 · 未上传/);

  await wrapper.findAll('.mission-sidebar__item')[0].trigger('click');
  await settle();
  assert.equal(workspace.activeSession?.missionId, 'mission-101-assigned');
  assert.equal(wrapper.get('[data-test="message-count"]').text(), '2');
  assert.equal(wrapper.get('[data-test="file-count"]').text(), '0');
  assert.equal(workspace.selectedConnectionId, null);
  await wrapper.findAll('.mission-sidebar__item')[1].trigger('click');
  await settle();
  assert.equal(wrapper.get('[data-test="file-count"]').text(), '1');
  assert.match(wrapper.get('[data-test="file-status"]').text(), /仅本地占位 · 未上传/);
  assert.equal(workspace.selectedConnectionId, 77);

  await wrapper.unmount();
  const refreshedPinia = createPinia();
  setActivePinia(refreshedPinia);
  const refreshedAuth = useAuthStore(refreshedPinia);
  refreshedAuth.applySession('test-token-demo-teacher', userOne as any);
  const refreshed = mount(MissionWorkspaceView, {
    global: { plugins: [refreshedPinia], stubs: componentStubs() },
  });
  await settle();
  assert.equal(refreshed.get('[data-test="message-count"]').text(), '2', localStorage.getItem(`a12-conversation-workspace-index:${DEMO_TEACHER_USER_ID}`) || 'no persisted sessions');
  assert.equal(refreshed.get('[data-test="file-count"]').text(), '1');
  assert.match(refreshed.get('[data-test="file-status"]').text(), /仅本地占位 · 未上传/);
  assert.equal(useConversationWorkspaceStore(refreshedPinia).activeSession?.missionId, 'mission-101-accepted');
  assert.equal(useConversationWorkspaceStore(refreshedPinia).selectedConnectionId, 77);

  refreshedAuth.applySession('test-token-202', userTwo as any);
  await settle();
  assert.equal(refreshed.find('.mission-sidebar__empty').exists(), true);
  assert.equal(refreshed.find('[data-test="file-status"]').exists(), false);
  assert.equal(useConversationWorkspaceStore(refreshedPinia).sessions.length, 0);
  await refreshed.unmount();
});

test('mounted Mission workspace rejects with a non-empty reason and renders the result', async () => {
  localStorage.clear();
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('test-token-demo-teacher-reject', userOne as any);
  const wrapper = mount(MissionWorkspaceView, {
    global: { plugins: [pinia], stubs: componentStubs() },
  });
  await settle();

  await wrapper.get('[data-test="reject-mission"]').trigger('click');
  assert.equal(wrapper.get('[data-test="reject-error"]').text(), '请填写拒绝原因后再提交。');
  await wrapper.get('[data-test="reject-reason"]').setValue('时间安排冲突');
  await wrapper.get('[data-test="reject-mission"]').trigger('click');
  await settle();

  assert.equal(wrapper.find('[data-test="mission-assignment"]').exists(), false);
  assert.equal(wrapper.get('[data-test="mission-rejected"]').exists(), true);
  assert.match(wrapper.get('[data-test="mission-rejected"]').text(), /已拒绝此 Mission/);
  assert.match(wrapper.get('[data-test="mission-rejected"]').text(), /时间安排冲突/);
  assert.equal(useConversationWorkspaceStore(pinia).activeSession?.missionId, 'mission-101-assigned');
  await wrapper.unmount();
});


test('Mission view reuses the existing sidebar component and session store instead of Project API state', async () => {
  const viewSource = await (await import('node:fs/promises')).readFile(resolve(frontendRoot, 'src/views/MissionWorkspaceView.vue'), 'utf8');
  assert.match(viewSource, /useConversationWorkspaceStore/);
  assert.match(viewSource, /setSessionMessages/);
  assert.match(viewSource, /setSessionFiles/);
  assert.match(viewSource, /setConnection/);
  assert.match(viewSource, /:show-toolbar-actions="false"/);
  assert.doesNotMatch(viewSource, new RegExp('/api/projects'));
  assert.match(viewSource, /MissionWorkspaceSidebar/);
});
