import test from 'node:test';
import assert from 'node:assert/strict';
import {reportFileName} from '../lib.mjs';

test('runs and datasets do not overwrite earlier evaluation reports', () => {
  assert.notEqual(reportFileName('seed-v1', 'run-1'), reportFileName('published-v1', 'run-1'));
  assert.notEqual(reportFileName('seed-v1', 'run-1'), reportFileName('seed-v1', 'run-2'));
});
test('labels cannot escape the local report directory', () => {
  assert.match(reportFileName('../../other', 'run-1'), /^topic-[0-9a-f]+-run-1\.json$/);
  assert.throws(() => reportFileName('label', '../escape'));
  assert.throws(() => reportFileName('x'.repeat(257), 'run-1'));
});
