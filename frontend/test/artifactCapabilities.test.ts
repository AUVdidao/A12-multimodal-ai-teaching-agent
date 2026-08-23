import assert from 'node:assert/strict';
import test from 'node:test';

import { canReviseArtifact } from '../src/utils/artifactCapabilities.ts';

test('artifact revision capability is disabled for historical PPT only', () => {
  assert.equal(canReviseArtifact('PPT'), false);
  assert.equal(canReviseArtifact('DOCX'), true);
  assert.equal(canReviseArtifact('INTERACTION'), true);
  assert.equal(canReviseArtifact('UNKNOWN'), false);
});
