import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadState, saveState } from '../lib/registry.mjs';

test('a fresh installation starts without another machine\'s fetch history', () => {
  const directory = mkdtempSync(join(tmpdir(), 'knowledge-state-'));
  try {
    const path = join(directory, 'nested', 'state.json');
    const state = loadState(path);
    assert.deepEqual(state.sources, {});
    state.sources.fixture = { sha256: 'previous-source-hash' };
    saveState(state, path);
    assert.deepEqual(loadState(path).sources, state.sources);
    assert.ok(loadState(path).updatedAt);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test('corrupt local state fails visibly rather than losing source history', () => {
  const directory = mkdtempSync(join(tmpdir(), 'knowledge-state-'));
  try {
    const path = join(directory, 'state.json');
    writeFileSync(path, '{invalid');
    assert.throws(() => loadState(path), SyntaxError);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
