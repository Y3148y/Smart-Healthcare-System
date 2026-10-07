import test from 'node:test';
import assert from 'node:assert/strict';
import {extractHtml} from '../lib/extract.mjs';
test('health-topic intake selects only the designated summary, not third-party links or page text',()=>{
  const r=extractHtml('<main><h1>Title</h1><div id="topic-summary"><h3>Question</h3><p>Government fixture.</p><ul><li>Condition.</li></ul></div><h2>Patient handouts</h2><p>Licensed fixture.</p></main>','health_topic_summary');
  assert.ok(r.text.includes('Government fixture.'));assert.ok(r.text.includes('Condition.'));
  assert.ok(!r.text.includes('Licensed fixture.'));assert.equal(r.region,'topic-summary');
});
test('missing or duplicate summary regions fail instead of importing the whole page',()=>{
  for(const html of ['<main>Body</main>','<div id="topic-summary">A</div><div id="topic-summary">B</div>'])
    assert.throws(()=>extractHtml(html,'health_topic_summary'),/SUMMARY_REGION_REQUIRED/);
});
