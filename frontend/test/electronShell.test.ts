import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { strict as assert } from 'node:assert';
import { resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');

describe('Electron desktop shell contract', () => {
  it('keeps renderer isolated and loads the built Vue application', () => {
    const source = readFileSync(resolve(root, 'electron/main.cjs'), 'utf8');
    assert.match(source, /contextIsolation:\s*true/);
    assert.match(source, /nodeIntegration:\s*false/);
    assert.match(source, /loadFile\(path\.join\(__dirname, '..', 'dist', 'index\.html'\)\)/);
  });

  it('prevents duplicate desktop launches and focuses the existing window', () => {
    const source = readFileSync(resolve(root, 'electron/main.cjs'), 'utf8');
    assert.match(source, /app\.setName\(['"]LessonForge['"]\)/);
    assert.match(source, /app\.setPath\(['"]userData['"],\s*path\.join\(app\.getPath\(['"]appData['"]\),\s*['"]LessonForge['"]\)\)/);
    assert.match(source, /requestSingleInstanceLock\(\)/);
    assert.match(source, /second-instance/);
    assert.match(source, /mainWindow\.focus\(\)/);
  });

  it('exposes only a non-secret desktop capability', () => {
    const source = readFileSync(resolve(root, 'electron/preload.cjs'), 'utf8');
    assert.match(source, /isDesktop:\s*true/);
    assert.doesNotMatch(source, /token|apiKey|Authorization|password/i);
  });
});
