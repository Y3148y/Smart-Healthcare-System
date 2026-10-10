/** Source definitions are versioned; fetch state is local to each installation. */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

export const HERE = dirname(fileURLToPath(import.meta.url));
export const TOOL_DIR = resolve(HERE, '..');
export const ROOT = resolve(TOOL_DIR, '..', '..');
export const REGISTRY_PATH = join(TOOL_DIR, 'sources.json');
export const CACHE_DIR = join(ROOT, 'knowledge', 'sources');
export const REPORTS_DIR = join(ROOT, 'reports', 'knowledge-sync');
export const STATE_PATH = join(REPORTS_DIR, 'state.json');

export function loadRegistry() {
  return JSON.parse(readFileSync(REGISTRY_PATH, 'utf8'));
}

export function loadState(path = STATE_PATH) {
  try {
    return JSON.parse(readFileSync(path, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return { version: 1, updatedAt: null, sources: {} };
    throw error;
  }
}

export function saveState(state, path = STATE_PATH) {
  state.updatedAt = new Date().toISOString();
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
}
