/**
 * Registry and state access. Both files are committed on purpose: the registry is the
 * auditable list of what we are allowed to read, and the state file is the hash ledger that
 * proves when a source last changed.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

export const HERE = dirname(fileURLToPath(import.meta.url));
export const TOOL_DIR = resolve(HERE, '..');
export const ROOT = resolve(TOOL_DIR, '..', '..');
export const REGISTRY_PATH = join(TOOL_DIR, 'sources.json');
export const STATE_PATH = join(TOOL_DIR, 'state.json');
export const CACHE_DIR = join(TOOL_DIR, 'cache');
export const REPORTS_DIR = join(TOOL_DIR, 'reports');
export const CORPUS_DIR = join(ROOT, 'backend', 'src', 'main', 'resources', 'knowledge');

export function loadRegistry() {
  return JSON.parse(readFileSync(REGISTRY_PATH, 'utf8'));
}

export function loadState() {
  return JSON.parse(readFileSync(STATE_PATH, 'utf8'));
}

export function saveState(state) {
  state.updatedAt = new Date().toISOString();
  writeFileSync(STATE_PATH, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
}

/** Sources eligible for automatic fetching, newest priority first. */
export function autoSources(registry, only = []) {
  return registry.sources
    .filter((source) => source.fetchMode === 'auto')
    .filter((source) => only.length === 0 || only.includes(source.id))
    .sort((a, b) => a.priority.localeCompare(b.priority) || a.id.localeCompare(b.id));
}