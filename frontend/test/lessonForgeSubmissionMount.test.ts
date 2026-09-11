import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer, type ViteDevServer } from 'vite';
import { compileScript, parse } from '@vue/compiler-sfc';

const frontendRoot = resolve(process.cwd());
const mission = {
  id: 'mission-newton',
  teacherId: 2,
  title: '完善《牛顿运动定律》课堂课件',
  description: '面向高一学生，补充受力分析与课堂练习。',
  status: 'IN_PROGRESS',
  recentActivity: '今天 10:24',
  sources: [],
  messages: [],
  activities: [],
  surfaceGate: { submission: true, feedback: false },
} as const;

let dom: JSDOM;
let viteServer: ViteDevServer;
let mount: typeof import('@vue/test-utils')['mount'];
let flushPromises: typeof import('@vue/test-utils')['flushPromises'];
let LessonForgeSubmissionCard: any;

function setGlobal(name: string, value: unknown) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

function installDom() {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/lessonforge/missions/mission-newton' });
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

async function settle() {
  await flushPromises();
  await flushPromises();
}

function fileNamed(input: HTMLInputElement, name: string) {
  Object.defineProperty(input, 'files', { configurable: true, value: [new File(['deck'], name)] });
}

function mountCard() {
  return mount(LessonForgeSubmissionCard, { props: { mission } });
}

before(async () => {
  installDom();
  const source = await readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgeSubmissionCard.vue'), 'utf8');
  const parsed = parse(source, { filename: 'src/components/lessonForge/LessonForgeSubmissionCard.vue' });
  const compiled = compileScript(parsed.descriptor, { id: 'data-v-lessonforge-submission-mount', inlineTemplate: true });
  viteServer = await createServer({
    root: frontendRoot,
    configFile: resolve(frontendRoot, 'vite.config.ts'),
    server: { middlewareMode: true },
    logLevel: 'error',
    plugins: [{
      name: 'lessonforge-submission-mount-entry',
      resolveId(id) { return id === '/@lessonforge-submission-mount-entry.ts' ? id : undefined; },
      load(id) { return id === '/@lessonforge-submission-mount-entry.ts' ? compiled.content : undefined; },
    }],
  });
  const [vueTestUtils, cardModule] = await Promise.all([
    import('@vue/test-utils'),
    viteServer.ssrLoadModule('/@lessonforge-submission-mount-entry.ts'),
  ]);
  mount = vueTestUtils.mount;
  flushPromises = vueTestUtils.flushPromises;
  LessonForgeSubmissionCard = cardModule.default;
});

after(async () => {
  await viteServer?.close();
  dom?.window.close();
});

test('blocks submission when the final deck file is missing', async () => {
  const wrapper = mountCard();
  const submit = wrapper.get('[data-test="submit-final-deck"]');

  assert.equal((submit.element as HTMLButtonElement).disabled, true);
  await submit.trigger('click');
  assert.deepEqual(wrapper.emitted('submit-file'), undefined);
  await wrapper.unmount();
});

test('allows submission once a PPTX file is selected without an invented reviewer', async () => {
  const wrapper = mountCard();
  const input = wrapper.get('[data-test="submission-file-input"]').element as HTMLInputElement;
  fileNamed(input, '牛顿运动定律_最终版.pptx');
  await wrapper.get('[data-test="submission-file-input"]').trigger('change');
  await settle();

  const submit = wrapper.get('[data-test="submit-final-deck"]');
  assert.equal((submit.element as HTMLButtonElement).disabled, false);
  assert.match(wrapper.get('[data-test="submission-file"]').text(), /牛顿运动定律_最终版\.pptx/);
  await submit.trigger('click');
  assert.equal(wrapper.emitted('submit-file')?.length, 1);
  await wrapper.unmount();
});

test('rejects an invalid file type and keeps submission blocked', async () => {
  const wrapper = mountCard();
  const input = wrapper.get('[data-test="submission-file-input"]').element as HTMLInputElement;
  fileNamed(input, '牛顿运动定律_最终版.pdf');
  await wrapper.get('[data-test="submission-file-input"]').trigger('change');
  await settle();

  assert.match(wrapper.get('[data-test="submission-error"]').text(), /\.pptx/);
  assert.equal(wrapper.find('[data-test="submission-local-only"]').exists(), false);
  assert.equal((wrapper.get('[data-test="submit-final-deck"]').element as HTMLButtonElement).disabled, true);
  await wrapper.get('[data-test="submit-final-deck"]').trigger('click');
  assert.deepEqual(wrapper.emitted('submit-file'), undefined);
  await wrapper.unmount();
});

test('accepts a PPTX, shows the upload boundary and emits the selected file', async () => {
  const wrapper = mountCard();
  const input = wrapper.get('[data-test="submission-file-input"]').element as HTMLInputElement;
  fileNamed(input, '牛顿运动定律_最终版.PPTX');
  await wrapper.get('[data-test="submission-file-input"]').trigger('change');
  await settle();

  assert.match(wrapper.get('[data-test="submission-file"]').text(), /牛顿运动定律_最终版\.PPTX/);
  assert.equal(wrapper.get('[data-test="submission-local-only"]').text(), '仅本地选择 · 点击提交后上传');
  assert.equal((wrapper.get('[data-test="submit-final-deck"]').element as HTMLButtonElement).disabled, false);
  await wrapper.get('[data-test="submit-final-deck"]').trigger('click');
  const emitted = wrapper.emitted('submit-file');
  assert.equal(emitted?.length, 1);
  assert.equal((emitted?.[0]?.[0] as File).name, '牛顿运动定律_最终版.PPTX');
  await wrapper.unmount();
});
