import test from 'node:test';
import assert from 'node:assert/strict';
import { extractHtml, decodeEntities } from '../lib/extract.mjs';
test('short main excludes navigation and preserves headings and paragraphs', () => {
  const result = extractHtml('<nav>Noise</nav><main><h1>Symptoms</h1><p>First <b>condition</b>.</p><p>Second condition.</p><ul><li>Call for help</li></ul></main><footer>Noise</footer>');
  assert.equal(result.text, '# Symptoms\n\nFirst condition.\n\nSecond condition.\n\n- Call for help');
  assert.deepEqual(result.headings, ['Symptoms']);
  assert.equal(result.reviewStatus, 'PENDING');
});
test('nested role main is not truncated at first closing element', () => {
  assert.equal(extractHtml('<div role="main"><div><p>One</p></div><p>Two</p></div><p>Outside</p>').text, 'One\n\nTwo');
});
test('table relationships and review dates survive with warning', () => {
  const result = extractHtml('<main><table><tr><th>Symptom</th><th>Action</th></tr><tr><td>A</td><td>B</td></tr></table><p>Page last reviewed: 2026-01-01</p></main>');
  assert.match(result.text, /Symptom \| Action\n\nA \| B/);
  assert.match(result.text, /Page last reviewed/);
  assert.deepEqual(result.warnings, ['TABLE_REQUIRES_REVIEW']);
});
test('hidden content removed; fallback and empty extraction explicit', () => {
  const result = extractHtml('<p>Public</p><div hidden>Hidden</div><script>secret</script>');
  assert.equal(result.text, 'Public');
  assert.deepEqual(result.warnings, ['BODY_FALLBACK_REQUIRES_REVIEW']);
  assert.ok(extractHtml('<main></main>').warnings.includes('EMPTY_EXTRACTION'));
});
test('invalid numeric entities do not crash', () => {
  assert.equal(decodeEntities('&#x1F600; &amp; &#99999999;'), '😀 & �');
});
