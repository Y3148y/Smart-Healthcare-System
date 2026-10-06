import test from 'node:test';
import assert from 'node:assert/strict';
import { parseRobots, isDisallowed, robotsAllows, fetchSource } from '../lib/http.mjs';

const blocked = (text, path) => isDisallowed(parseRobots(text), path);
test('restricted sources are blocked before any robots or network request', async () => {
  const result = await fetchSource({id:'restricted',url:'not-even-a-url',permissionStatus:'restricted'});
  assert.equal(result.status, 'blocked-by-permission');
  assert.equal(result.httpStatus, null);
});

test('wildcard disallow and consecutive user agents survive parsing', () => {
  assert.equal(blocked('User-agent: other\nUser-agent: *\nDisallow: /private', '/private/a'), true);
  assert.equal(blocked('User-agent: *\nDisallow: /private', '/public'), false);
});
test('longest rule wins irrespective of order; allow wins equal length', () => {
  for (const rules of ['Allow: /a\nDisallow: /a/private', 'Disallow: /a/private\nAllow: /a']) {
    assert.equal(blocked(`User-agent: *\n${rules}`, '/a/private'), true);
  }
  assert.equal(blocked('User-agent: *\nDisallow: /a\nAllow: /a', '/a'), false);
});
test('specific agent groups replace wildcard and matching groups merge', () => {
  const rules = 'User-agent: *\nDisallow: /\nUser-agent: ai-hospital-knowledge-sync\nAllow: /public\nUser-agent: ai-hospital-knowledge-sync\nDisallow: /secret';
  assert.equal(blocked(rules, '/public'), false);
  assert.equal(blocked(rules, '/secret'), true);
});
test('wildcard and end anchor; empty disallow', () => {
  assert.equal(blocked('User-agent: *\nDisallow: /*.pdf$', '/a.pdf'), true);
  assert.equal(blocked('User-agent: *\nDisallow: /*.pdf$', '/a.pdf.html'), false);
  assert.equal(blocked('User-agent: *\nDisallow:', '/a'), false);
});
test('unreadable robots fails closed, without accepting old disk cache', async () => {
  await assert.rejects(robotsAllows('https://robots-test.invalid/a', async () => ({ok:false,status:404})), /ROBOTS_UNAVAILABLE/);
  await assert.rejects(robotsAllows('https://robots-network.invalid/a', async () => { throw new Error('offline'); }), /ROBOTS_UNAVAILABLE/);
});
