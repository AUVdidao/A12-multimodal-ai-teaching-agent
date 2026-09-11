import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const read = (relativePath: string) => readFileSync(resolve(root, relativePath), 'utf8');

test('legacy planning UI is no longer part of the LessonForge app route surface', () => {
  const view = read('src/views/PlanningAgentView.vue');
  const api = read('src/api/planning.ts');
  const router = read('src/router/index.ts');
  assert.match(view, /confirmedContextVersion/);
  assert.match(view, /teacherConfirmed/);
  assert.match(view, /NOT_RUN|不可用/);
  assert.match(api, /confirmedContextVersion/);
  assert.match(api, /teacherInstruction/);
  assert.doesNotMatch(router, /project-planning-agent/);
  assert.match(router, /lessonforge-missions/);
});

test('planning client exposes semantic proposal only and does not expose executor fields', () => {
  const api = read('src/api/planning.ts');
  const view = read('src/views/PlanningAgentView.vue');
  assert.match(api, /createPlanningProposal/);
  assert.doesNotMatch(api, /pptxgenjs|PowerPointExecutor|nativeObjectRef/);
  assert.doesNotMatch(view, /componentId|shapeId|oxml|nativeObjectRef/);
});
