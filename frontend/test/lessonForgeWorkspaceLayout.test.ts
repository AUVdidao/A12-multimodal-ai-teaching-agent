import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { createServer, type ViteDevServer } from 'vite';
import { compileScript, parse } from '@vue/compiler-sfc';

const frontendRoot = resolve(process.cwd());
let dom: JSDOM;
let viteServer: ViteDevServer;
let mount: typeof import('@vue/test-utils')['mount'];
let LessonForgeBoard: any;
let LessonForgeMissionRail: any;

function setGlobal(name: string, value: unknown) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
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
  })) setGlobal(name, value);
}

const mission = {
  id: '7',
  teacherId: 2,
  title: '完善《牛顿运动定律》课堂课件',
  description: '面向高一学生，补充受力分析与课堂练习。',
  status: 'IN_PROGRESS',
  currentPhase: 'LOCKED',
  recentActivity: '今天 10:24',
  deadline: '2026-09-10',
  sources: [{ id: 'source-1', name: '教材第三章.pdf', kind: 'MATERIAL' }],
  messages: [],
  plan: { version: 'v3', pages: 24, status: 'LOCKED', summary: '从受力分析到课堂练习。', sections: ['受力分析', '课堂练习'] },
  output: { name: '牛顿运动定律.pptx', pages: 24, version: 'v3', sizeLabel: '2 MB' },
  activities: [{ label: 'PPT 生成完成', detail: '24 Slides', time: '10:24', tone: 'success' }],
  feedback: '第 18 页增加一道课堂练习。',
  submission: { id: 'submission-1', fileName: '牛顿运动定律_最终版.pptx', reviewerId: '9', reviewerName: '王老师', status: 'SUBMISSION_PENDING', submittedAt: '10:30', source: 'SERVER' },
  submissions: [],
  surfaceGate: { submission: false, feedback: false },
} as any;

before(async () => {
  installDom();
  const [boardSource, railSource] = await Promise.all([
    readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgeBoard.vue'), 'utf8'),
    readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgeMissionRail.vue'), 'utf8'),
  ]);
  const boardCompiled = compileScript(parse(boardSource, { filename: 'src/components/lessonForge/LessonForgeBoard.vue' }).descriptor, { id: 'data-v-lessonforge-board-layout', inlineTemplate: true });
  const railCompiled = compileScript(parse(railSource, { filename: 'src/components/lessonForge/LessonForgeMissionRail.vue' }).descriptor, { id: 'data-v-lessonforge-rail-layout', inlineTemplate: true });
  viteServer = await createServer({
    root: frontendRoot,
    configFile: resolve(frontendRoot, 'vite.config.ts'),
    server: { middlewareMode: true },
    logLevel: 'error',
    plugins: [{
      name: 'lessonforge-layout-entry',
      resolveId(id) {
        if (id === '/@lessonforge-board-layout.ts' || id === '/@lessonforge-rail-layout.ts') return id;
        return undefined;
      },
      load(id) {
        if (id === '/@lessonforge-board-layout.ts') return boardCompiled.content;
        if (id === '/@lessonforge-rail-layout.ts') return railCompiled.content;
        return undefined;
      },
    }],
  });
  const [vueTestUtils, boardModule, railModule] = await Promise.all([
    import('@vue/test-utils'),
    viteServer.ssrLoadModule('/@lessonforge-board-layout.ts'),
    viteServer.ssrLoadModule('/@lessonforge-rail-layout.ts'),
  ]);
  mount = vueTestUtils.mount;
  LessonForgeBoard = boardModule.default;
  LessonForgeMissionRail = railModule.default;
});

after(async () => {
  await viteServer?.close();
  dom?.window.close();
});

test('Board keeps real Courseware and Activity while removing duplicate Mission and fake tabs', async () => {
  const collapsed = mount(LessonForgeBoard, { props: { mission, planOpen: false } });
  assert.equal(collapsed.find('.lf-board-tabs').exists(), false);
  assert.equal(collapsed.text().includes(mission.title), false);
  assert.equal(collapsed.text().includes(mission.description), true);
  assert.equal(collapsed.find('[data-test="board-courseware"]').exists(), true);
  assert.equal(collapsed.find('[data-test="board-mission"]').exists(), true);
  assert.equal(collapsed.find('[data-test="board-activity"]').exists(), true);
  assert.match(collapsed.get('[data-test="board-activity"]').text(), /PPT 生成完成/);
  await collapsed.get('.lf-plan-row').trigger('click');
  assert.equal(collapsed.emitted('toggle-plan')?.length, 1);

  const expanded = mount(LessonForgeBoard, { props: { mission, planOpen: true } });
  assert.match(expanded.get('.lf-board-plan').text(), /受力分析/);
  await collapsed.unmount();
  await expanded.unmount();
});

test('Mission Rail is a compact resource index with only data-backed sections', async () => {
  const wrapper = mount(LessonForgeMissionRail, { props: { mission } });
  assert.equal(wrapper.find('.lf-rail-title').exists(), false);
  assert.equal(wrapper.find('.lf-rail-description').exists(), false);
  assert.equal(wrapper.find('.lf-rail-status').exists(), false);
  assert.equal(wrapper.text().includes(mission.title), false);
  assert.equal(wrapper.find('[data-test="rail-feedback"]').exists(), false);
  assert.equal(wrapper.find('[data-test="rail-deadline"]').exists(), false);
  for (const selector of ['rail-sources', 'rail-courseware']) {
    assert.equal(wrapper.find(`[data-test="${selector}"]`).exists(), true, selector);
  }
  assert.equal(wrapper.find('[data-test="rail-submission"]').exists(), false);
  assert.equal(wrapper.find('[data-test="rail-outputs"]').exists(), false);
  await wrapper.get('.lf-back-link').trigger('click');
  await wrapper.get('[data-test="rail-sources"] .lf-rail-file').trigger('click');
  await wrapper.get('[data-test="rail-courseware"] .lf-rail-file').trigger('click');
  assert.equal(wrapper.emitted('back')?.length, 1);
  assert.deepEqual(wrapper.emitted('focus')?.[0], ['教材第三章.pdf']);
  assert.equal(wrapper.emitted('plan')?.length, 1);
  await wrapper.unmount();

  const outputMission = { ...mission, currentPhase: 'SUCCEEDED', plan: undefined, surfaceGate: { submission: true, feedback: true } } as any;
  const outputWrapper = mount(LessonForgeMissionRail, { props: { mission: outputMission } });
  assert.equal(outputWrapper.find('[data-test="rail-courseware"]').exists(), false);
  assert.equal(outputWrapper.find('[data-test="rail-outputs"]').exists(), true);
  await outputWrapper.unmount();
});

test('Mission Workspace layout contract preserves one primary header and overflow-safe responsive columns', async () => {
  const workspace = await readFile(resolve(frontendRoot, 'src/views/LessonForgeMissionWorkspaceView.vue'), 'utf8');
  const frame = await readFile(resolve(frontendRoot, 'src/components/lessonForge/LessonForgeFrame.vue'), 'utf8');
  const css = await readFile(resolve(frontendRoot, 'src/styles/lessonForge.css'), 'utf8');
  assert.match(workspace, /<h1 data-test="mission-title">\{\{ mission\.title \}\}<\/h1>/);
  assert.match(workspace, /class="lf-status-chip" data-test="mission-status"/);
  assert.match(workspace, /<LessonForgeBoard :mission="mission"/);
  assert.doesNotMatch(workspace, /showBoard/);
  assert.match(frame, /v-if="!workspace" class="lf-topbar__context"/);
  assert.doesNotMatch(frame, /lf-window-menu/);
  assert.match(css, /--lf-mission-index-width: 172px/);
  assert.match(css, /--lf-mission-board-default-width: 292px/);
  assert.match(css, /--lf-mission-board-min-width: 260px/);
  assert.match(css, /--lf-mission-board-max-width: 420px/);
  assert.match(css, /--lf-mission-board-width: var\(--lf-mission-board-default-width\)/);
  assert.match(css, /--lf-composer-height: 100px/);
  assert.match(css, /--lf-scrollbar-track: #1f1f1f/);
  assert.match(css, /--lf-scrollbar-thumb: #3a3a3a/);
  assert.match(css, /--lf-scrollbar-thumb-hover: #4a4a4a/);
  assert.match(css, /--lf-mission-header-height: 68px/);
  assert.match(css, /grid-template-columns: var\(--lf-mission-index-width\) minmax\(0, 1fr\) var\(--lf-mission-board-width\)/);
  assert.match(css, /\.lf-app--workspace \.lf-frame-body \{ height: calc\(100vh - var\(--lf-topbar-height\)\); min-height: 0; \}/);
  assert.match(css, /\.lf-workspace \{[\s\S]*?height: 100%; min-height: 0; overflow: hidden;/);
  assert.match(css, /\.lf-conversation-header \{[\s\S]*?height: var\(--lf-mission-header-height\); min-height: var\(--lf-mission-header-height\);/);
  assert.doesNotMatch(css, /grid-template-columns: 184px minmax\(0, 1fr\) 276px/);
  assert.doesNotMatch(css, /\.lf-board--expanded|\.lf-board-tabs/);
  assert.doesNotMatch(css, /\.lf-board\s*\{[^}]*display:\s*none/);
  assert.doesNotMatch(css, /\.lf-mission-board[^}]*display:\s*none/);
  assert.match(css, /\.lf-board\s*\{[^}]*min-width: var\(--lf-mission-board-min-width\)/s);
  assert.match(css, /\.lf-board\s*\{[^}]*max-width: var\(--lf-mission-board-max-width\)/s);
  assert.match(css, /\.lf-board\s*\{[^}]*container-type: inline-size/s);
  assert.match(css, /\.lf-board-label > span\s*\{[^}]*text-overflow: ellipsis/s);
  assert.match(css, /@container \(max-width: 286px\)/);
  assert.match(css, /\.lf-composer\s*\{[^}]*grid-template-rows: var\(--lf-composer-textarea-height\) var\(--lf-composer-toolbar-height\)/s);
  assert.match(css, /\.lf-composer-files\s*\{[^}]*max-height: 44px[^}]*overflow-y: auto/s);
  assert.match(css, /\.lf-app \*::-webkit-scrollbar-thumb\s*\{[^}]*background: var\(--lf-scrollbar-thumb\)/s);
  assert.doesNotMatch(css, /scrollbar-(?:color|thumb)[^\n]*#d8ddef|#d7deeb/);
});
