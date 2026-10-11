import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, readdirSync} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {validateCases, validateExpectedTitles} from '../lib.mjs';

test('public topic smoke labels match the checked-in corpus, not legacy titles', () => {
  const dataset = validateCases(JSON.parse(readFileSync(new URL('../cases-public-corpus-development.json', import.meta.url), 'utf8')));
  const root = fileURLToPath(new URL('../../../knowledge/corpus/', import.meta.url));
  const documents = [];
  for (const category of readdirSync(root, {withFileTypes:true}).filter(entry => entry.isDirectory()))
    for (const topic of readdirSync(join(root, category.name), {withFileTypes:true}).filter(entry => entry.isDirectory())) {
      const text = readFileSync(join(root, category.name, topic.name, 'document.zh-CN.md'), 'utf8');
      const frontMatter = /^---\r?\n([\s\S]*?)\r?\n---\r?\n/.exec(text);
      assert.ok(frontMatter, 'public document requires JSON front matter');
      const metadata = JSON.parse(frontMatter[1]);
      documents.push({title:metadata.title});
    }
  assert.equal(documents.length, 11);
  assert.equal(dataset.cases.length, 12);
  assert.equal(dataset.split, 'DEVELOPMENT_ONLY');
  assert.equal(dataset.labelAuthor, 'IMPLEMENTATION_AUTHOR');
  assert.doesNotThrow(() => validateExpectedTitles(dataset, documents));
  const targetTitles = new Set(dataset.cases.map(item => item.expectedTitle));
  assert.equal(targetTitles.size, 11);
});

test('stale targets and an empty approved corpus fail before scoring', () => {
  const dataset = validateCases({schemaVersion:1,labelVersion:'test',reviewStatus:'NOT_CLINICALLY_REVIEWED',
    cases:[{id:'one',query:'query',expectedTitle:'old title'}]});
  assert.throws(() => validateExpectedTitles(dataset, []), /No approved corpus/);
  assert.throws(() => validateExpectedTitles(dataset, [{title:'new title'}]), /do not match/);
  assert.doesNotThrow(() => validateExpectedTitles(dataset, [{title:'old title'}]));
});
