import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer, type ViteDevServer } from 'vite';
import { compileScript, parse } from '@vue/compiler-sfc';
import axios from 'axios';
import AxiosMockAdapter from 'axios-mock-adapter';

const frontendRoot = resolve(process.cwd());
const teacher = { id: 2, username: 'teacher-one', displayName: 'Teacher One', roles: ['TEACHER'], activeRole: 'TEACHER' };
const connections = [
  { id: 1, name: 'Disabled verified', protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://disabled.example/v1', modelId: 'disabled-model', keyHint: '••••', enabled: false, verificationStatus: 'VERIFIED' },
  { id: 2, name: 'Invalid connection', protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://invalid.example/v1', modelId: 'invalid-model', keyHint: '••••', enabled: true, verificationStatus: 'INVALID' },
  { id: 3, name: 'Pending connection', protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://pending.example/v1', modelId: 'pending-model', keyHint: '••••', enabled: true, verificationStatus: 'UNVERIFIED' },
  { id: 4, name: 'Verified connection', protocol: 'OPENAI_COMPATIBLE', baseUrl: 'https://verified.example/v1', modelId: 'verified-model', keyHint: '••••', enabled: true, verificationStatus: 'VERIFIED' },
];

let dom: JSDOM;
let viteServer: ViteDevServer;
let mount: typeof import('@vue/test-utils')['mount'];
let flushPromises: typeof import('@vue/test-utils')['flushPromises'];
let createPinia: typeof import('pinia')['createPinia'];
let setActivePinia: typeof import('pinia')['setActivePinia'];
let createRouter: typeof import('vue-router')['createRouter'];
let createMemoryHistory: typeof import('vue-router')['createMemoryHistory'];
let LessonForgeNewMissionView: any;
let useAuthStore: typeof import('../src/stores/auth')['useAuthStore'];
let useLessonForgeStore: typeof import('../src/stores/lessonForge')['useLessonForgeStore'];
let httpMock: AxiosMockAdapter;

function setGlobal(name: string, value: unknown) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

function installDom() {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/lessonforge/new' });
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
    File: dom.window.File,
    FormData: dom.window.FormData,
  })) setGlobal(name, value);
}

function router() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/lessonforge/new', name: 'lessonforge-new', component: LessonForgeNewMissionView }],
  });
}

async function settle() {
  await flushPromises();
  await flushPromises();
}

async function mountTeacher() {
  localStorage.clear();
  httpMock.reset();
  httpMock.onGet('/api/v1/lessonforge/missions').reply(200, { code: 0, data: [] });
  httpMock.onGet('/api/v1/ai-credentials/connections').reply(200, { code: 0, data: connections });
  const pinia = createPinia();
  setActivePinia(pinia);
  const auth = useAuthStore(pinia);
  auth.applySession('teacher-token', teacher as any);
  const appRouter = router();
  await appRouter.push('/lessonforge/new');
  await appRouter.isReady();
  const wrapper = mount(LessonForgeNewMissionView, {
    global: {
      plugins: [pinia, appRouter],
      stubs: {
        LessonForgeFrame: { template: '<div><slot /></div>' },
        LessonForgeComposer: {
          props: ['modelValue', 'files', 'connections', 'selectedConnection'],
          template: `<div class="lf-composer">
            <div v-if="files.length" data-test="composer-context-files"><span v-for="file in files" :key="file.name">{{ file.name }} {{ fileType(file.name) }} · 等待上传</span></div>
            <textarea aria-label="消息输入" :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" />
            <input type="file" multiple @change="$emit('files-selected', Array.from($event.target.files || []).map(file => ({ name: file.name, file })))" />
            <button aria-label="选择模型连接" @click="pickerOpen = !pickerOpen">{{ selectedConnection?.name || 'Select model' }}</button>
            <div v-if="pickerOpen"><button v-for="connection in connections" :key="connection.id" :data-test="'connection-option-' + connection.id" :disabled="!usable(connection)" @click="pick(connection)">{{ connection.name }} {{ state(connection) }}</button><button class="lf-add-connection" @click="$emit('manage-connections'); pickerOpen = false">Manage connections</button></div>
            <button aria-label="发送" :disabled="!(modelValue.trim() || files.length)" @click="$emit('send', { text: modelValue.trim(), files })">↑</button>
          </div>`,
          data: () => ({ pickerOpen: false }),
          methods: {
            usable(connection: any) { return connection.enabled && connection.verificationStatus === 'VERIFIED'; },
            pick(connection: any) { if (this.usable(connection)) { this.$emit('select-connection', connection); this.pickerOpen = false; } },
            state(connection: any) { return connection.enabled === false ? 'Disabled' : connection.verificationStatus === 'INVALID' ? 'Invalid' : this.usable(connection) ? 'Verified' : 'pending'; },
            fileType(name: string) { return name.split('.').pop()?.toUpperCase() || 'FILE'; },
          },
        },
        ModelConnectionDrawer: { props: ['modelValue'], template: '<div v-if="modelValue" data-test="model-connection-drawer" />' },
      },
    },
  });
  await settle();
  return { wrapper, store: useLessonForgeStore(pinia) };
}

before(async () => {
  installDom();
  httpMock = new AxiosMockAdapter(axios);
  const source = await readFile(resolve(frontendRoot, 'src/views/LessonForgeNewMissionView.vue'), 'utf8');
  const parsed = parse(source, { filename: 'src/views/LessonForgeNewMissionView.vue' });
  const compiled = compileScript(parsed.descriptor, { id: 'data-v-lessonforge-new-workspace-mount', inlineTemplate: true });
  viteServer = await createServer({
    root: frontendRoot,
    configFile: resolve(frontendRoot, 'vite.config.ts'),
    server: { middlewareMode: true },
    logLevel: 'error',
    plugins: [{
      name: 'lessonforge-new-workspace-mount-entry',
      resolveId(id) { return id === '/@lessonforge-new-workspace-mount-entry.ts' ? id : undefined; },
      load(id) { return id === '/@lessonforge-new-workspace-mount-entry.ts' ? compiled.content : undefined; },
    }],
  });
  const [vueTestUtils, pinia, routerModule, viewModule, authModule, storeModule] = await Promise.all([
    import('@vue/test-utils'),
    import('pinia'),
    import('vue-router'),
    viteServer.ssrLoadModule('/@lessonforge-new-workspace-mount-entry.ts'),
    viteServer.ssrLoadModule('/src/stores/auth.ts'),
    viteServer.ssrLoadModule('/src/stores/lessonForge.ts'),
  ]);
  mount = vueTestUtils.mount;
  flushPromises = vueTestUtils.flushPromises;
  createPinia = pinia.createPinia;
  setActivePinia = pinia.setActivePinia;
  createRouter = routerModule.createRouter;
  createMemoryHistory = routerModule.createMemoryHistory;
  LessonForgeNewMissionView = viewModule.default;
  useAuthStore = authModule.useAuthStore;
  useLessonForgeStore = storeModule.useLessonForgeStore;
});

after(async () => {
  httpMock?.restore();
  await viteServer?.close();
  dom?.window.close();
});

test('teacher enters an empty Conversation Workspace with a fixed Composer', async () => {
  const { wrapper } = await mountTeacher();
  assert.equal(wrapper.find('[data-test="new-mission-workspace"]').exists(), true);
  assert.equal(wrapper.find('[data-test="empty-conversation"]').exists(), true);
  assert.equal(wrapper.find('[data-test="fixed-composer"]').exists(), true);
  assert.equal(wrapper.find('.lf-composer textarea').exists(), true);
  assert.equal(wrapper.find('[data-test="teacher-new-mission-boundary"]').exists(), false);
  assert.equal(wrapper.find('select[aria-label="选择教师"]').exists(), false);
  assert.equal((wrapper.get('[aria-label="发送"]').element as HTMLButtonElement).disabled, true);
  await wrapper.unmount();
});

test('text or a file enables Send, while file context remains frontend-only', async () => {
  const { wrapper, store } = await mountTeacher();
  const textarea = wrapper.get('textarea[aria-label="消息输入"]');
  await textarea.setValue('Make a short lesson deck about cells.');
  assert.equal((wrapper.get('[aria-label="发送"]').element as HTMLButtonElement).disabled, false);
  await wrapper.get('[aria-label="发送"]').trigger('click');
  assert.match(wrapper.get('[data-test="send-boundary"]').text(), /FRONTEND_ONLY/);
  assert.match(wrapper.get('[data-test="send-boundary"]').text(), /尚未创建真实 Mission\/会话/);
  assert.equal(store.missions.length, 0);

  await textarea.setValue('');
  const fileInput = wrapper.get('input[type="file"]');
  const file = new File(['pdf'], 'cells.pdf', { type: 'application/pdf' });
  Object.defineProperty(fileInput.element, 'files', { configurable: true, value: [file] });
  await fileInput.trigger('change');
  assert.equal((wrapper.get('[aria-label="发送"]').element as HTMLButtonElement).disabled, false);
  assert.match(wrapper.get('[data-test="composer-context-files"]').text(), /cells\.pdf/);
  assert.match(wrapper.get('[data-test="composer-context-files"]').text(), /PDF/);
  assert.match(wrapper.get('[data-test="composer-context-files"]').text(), /等待上传/);
  assert.doesNotMatch(wrapper.get('[data-test="composer-context-files"]').text(), /内部|storage|fileId/i);
  await wrapper.unmount();
});

test('only enabled verified connections are selectable and management reuses the existing entry', async () => {
  const { wrapper } = await mountTeacher();
  await wrapper.get('[aria-label="选择模型连接"]').trigger('click');
  assert.equal((wrapper.get('[data-test="connection-option-1"]').element as HTMLButtonElement).disabled, true);
  assert.equal((wrapper.get('[data-test="connection-option-2"]').element as HTMLButtonElement).disabled, true);
  assert.equal((wrapper.get('[data-test="connection-option-3"]').element as HTMLButtonElement).disabled, true);
  assert.equal((wrapper.get('[data-test="connection-option-4"]').element as HTMLButtonElement).disabled, false);
  assert.match(wrapper.get('[data-test="connection-option-2"]').text(), /Invalid/);
  assert.match(wrapper.get('[data-test="connection-option-3"]').text(), /pending/i);
  await wrapper.get('[data-test="connection-option-4"]').trigger('click');
  assert.match(wrapper.get('[aria-label="选择模型连接"]').text(), /Verified connection/);
  await wrapper.get('[aria-label="选择模型连接"]').trigger('click');
  await wrapper.get('.lf-add-connection').trigger('click');
  assert.equal(wrapper.find('[data-test="model-connection-drawer"]').exists(), true);
  await wrapper.unmount();
});

test('shared Composer keeps file status visible and blocks non-verified connection options', async () => {
  const source = await readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgeComposer.vue'), 'utf8');
  assert.match(source, /等待上传/);
  assert.match(source, /fileTypeLabel/);
  assert.match(source, /:disabled="!isUsable\(connection\)"/);
  assert.match(source, /verificationStatus === 'INVALID'/);
});
