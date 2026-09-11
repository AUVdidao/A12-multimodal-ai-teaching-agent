import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  composerFileIdentity,
  LESSONFORGE_COMPOSER_MAX_HEIGHT,
  mergeComposerFiles,
  resizeComposerTextarea,
} from '../src/utils/lessonForgeComposer.ts';
import { isSelectableConnection } from '../src/utils/conversationWorkspaceConnection.ts';

test('Composer textarea keeps a fixed height and scrolls long content internally', () => {
  const textarea = { style: { height: '', overflowY: '' }, scrollHeight: 96 } as unknown as HTMLTextAreaElement;
  resizeComposerTextarea(textarea);
  assert.equal(textarea.style.height, `${LESSONFORGE_COMPOSER_MAX_HEIGHT}px`);
  assert.equal(textarea.style.overflowY, 'auto');

  Object.defineProperty(textarea, 'scrollHeight', { configurable: true, value: LESSONFORGE_COMPOSER_MAX_HEIGHT + 120 });
  resizeComposerTextarea(textarea);
  assert.equal(textarea.style.height, `${LESSONFORGE_COMPOSER_MAX_HEIGHT}px`);
  assert.equal(textarea.style.overflowY, 'auto');
});

test('Composer file selections accumulate, stably deduplicate, and retain identity for single removal', () => {
  const first = new File(['one'], 'lesson.pdf', { type: 'application/pdf', lastModified: 1 });
  const second = new File(['two'], 'slides.pptx', { type: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', lastModified: 2 });
  const sameAsFirst = new File(['one'], 'lesson.pdf', { type: 'application/pdf', lastModified: 1 });
  const merged = mergeComposerFiles([{ name: first.name, file: first }], [{ name: second.name, file: second }, { name: sameAsFirst.name, file: sameAsFirst }]);

  assert.deepEqual(merged.map((item) => item.name), ['lesson.pdf', 'slides.pptx']);
  assert.equal(composerFileIdentity(merged[0]), composerFileIdentity({ name: sameAsFirst.name, file: sameAsFirst }));
  assert.notEqual(composerFileIdentity(merged[0]), composerFileIdentity(merged[1]));
});

test('strict connection gate rejects legacy status-only connections', () => {
  const legacyStatusOnly = { id: 7, name: 'legacy', enabled: true, status: 'VERIFIED' } as any;
  assert.equal(isSelectableConnection(legacyStatusOnly), false);
});

test('production Composer exposes Codex-style drag resizing, cumulative file selection, and the shared connection gate', async () => {
  const [source, styles] = await Promise.all([
    readFile(resolve(process.cwd(), 'src/components/lessonForge/LessonForgeComposer.vue'), 'utf8'),
    readFile(resolve(process.cwd(), 'src/styles/lessonForge.css'), 'utf8'),
  ]);
  assert.match(source, /ref="textarea"/);
  assert.match(source, /@input="onTextInput"/);
  assert.match(source, /class="lf-composer-resize-handle"/);
  assert.match(source, /@pointerdown="startResize"/);
  assert.match(source, /composerStyle/);
  assert.match(source, /COMPOSER_MAX_HEIGHT = 360/);
  assert.doesNotMatch(source, /resizeComposerTextarea/);
  assert.match(source, /mergeComposerFiles\(props\.files, incoming\)/);
  assert.match(source, /isSelectableConnection/);
  assert.match(source, /disabled\?: boolean/);
  assert.match(source, /!props\.disabled/);
  assert.doesNotMatch(source, /verificationStatus\s*\|\|\s*connection\.status/);
  assert.match(styles, /\.lf-composer textarea\s*\{[^}]*resize: none/s);
  assert.match(styles, /\.lf-composer textarea\s*\{[^}]*height: var\(--lf-composer-textarea-height\)/s);
  assert.match(styles, /\.lf-composer textarea\s*\{[^}]*overflow-y: auto/s);
  assert.match(styles, /\.lf-composer\s*\{[^}]*height: var\(--lf-composer-height\)/s);
  assert.match(styles, /\.lf-composer-resize-handle\s*\{/);
  assert.match(styles, /\.lf-is-resizing-composer/);
});
