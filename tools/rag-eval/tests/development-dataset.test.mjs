import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {validateCases} from '../lib.mjs';

test('specific-question development dataset remains explicit and non-clinical', () => {
  const dataset = validateCases(JSON.parse(readFileSync(new URL('../cases-author-specific-60-v3.json', import.meta.url), 'utf8')));
  assert.equal(dataset.cases.length, 60);
  assert.equal(dataset.split, 'DEVELOPMENT_ONLY');
  assert.equal(dataset.labelAuthor, 'IMPLEMENTATION_AUTHOR');
  assert.equal(dataset.labelReviewStatus, 'AUTHOR_DRAFT_NOT_INDEPENDENTLY_REVIEWED');
  assert.equal(dataset.cases.filter(item => item.expectedTitle === null).length, 5);
  const groups = new Map();
  for (const item of dataset.cases.filter(item => item.expectedTitle !== null))
    groups.set(item.groupId, (groups.get(item.groupId) ?? 0) + 1);
  assert.equal(groups.size, 11);
  assert.ok([...groups.values()].every(count => count === 5));
});
