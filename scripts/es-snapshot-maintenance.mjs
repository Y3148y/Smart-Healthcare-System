import { pathToFileURL } from 'node:url';

const SCHEMA = 'cjk-bm25-v1';
const DAY = 86400000;

export function options(args) {
  const result = { keep: [], remove: [], apply: false, stopped: false, hours: 24 };
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--apply') result.apply = true;
    else if (arg === '--apps-stopped') result.stopped = true;
    else if (['--url', '--prefix', '--keep', '--delete', '--min-age-hours'].includes(arg)) {
      const value = args[++i];
      if (!value || value.startsWith('--')) throw new Error('Missing option value');
      if (arg === '--keep') result.keep.push(value);
      else if (arg === '--delete') result.remove.push(value);
      else result[({ '--url': 'url', '--prefix': 'prefix', '--min-age-hours': 'hours' })[arg]] = value;
    } else throw new Error('Unknown option');
  }
  result.prefix ??= 'ai-hospital-lexical';
  if (!/^[a-z][a-z0-9-]{0,100}$/.test(result.prefix)) throw new Error('Invalid index prefix');
  const endpoint = new URL(result.url);
  if (!['http:', 'https:'].includes(endpoint.protocol)
      || !['127.0.0.1', 'localhost', '[::1]'].includes(endpoint.hostname)
      || endpoint.username || endpoint.password || endpoint.search || endpoint.hash || endpoint.pathname !== '/')
    throw new Error('Explicit loopback Elasticsearch origin required');
  result.url = endpoint.origin;
  result.hours = Number(result.hours);
  if (!Number.isInteger(result.hours) || result.hours < 24 || result.hours > 8760)
    throw new Error('Retention must be 24 to 8760 hours');
  const exact = new RegExp(`^${result.prefix}-[a-f0-9]{64}$`);
  if (!result.keep.length || [...result.keep, ...result.remove].some(name => !exact.test(name))
      || new Set(result.keep).size !== result.keep.length || new Set(result.remove).size !== result.remove.length
      || result.remove.some(name => result.keep.includes(name))) throw new Error('Exact distinct protected and deletion names required');
  if (result.apply && (!result.stopped || !result.remove.length))
    throw new Error('Deletion requires --apps-stopped and explicit --delete names');
  return result;
}

function identity(name, description, prefix) {
  if (!new RegExp(`^${prefix}-[a-f0-9]{64}$`).test(name)) throw new Error('Unexpected index name');
  const meta = description?.mappings?._meta;
  const settings = description?.settings?.index;
  const created = Number(settings?.creation_date);
  const uuid = settings?.uuid;
  if (meta?.schema !== SCHEMA || meta?.snapshot !== name.slice(prefix.length + 1)
      || typeof uuid !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(uuid)
      || !Number.isSafeInteger(created) || created <= 0
      || !description.aliases || Object.keys(description.aliases).length)
    throw new Error('Index ownership, age or alias metadata is unsafe');
  return { name, uuid, created };
}

export function plan(config, indices, now = Date.now()) {
  if (!indices || typeof indices !== 'object' || Array.isArray(indices)) throw new Error('Invalid inventory');
  const all = Object.entries(indices).map(([name, value]) => identity(name, value, config.prefix));
  for (const name of [...config.keep, ...config.remove]) {
    if (!all.some(item => item.name === name)) throw new Error('Explicit index is absent');
  }
  return all.map(item => ({ ...item, protected: config.keep.includes(item.name),
    eligible: !config.keep.includes(item.name) && now - item.created >= config.hours * DAY / 24,
    requested: config.remove.includes(item.name) })).sort((a, b) => a.name.localeCompare(b.name));
}

export async function maintain(config, request, now = Date.now()) {
  const inventory = await request('GET', `/${config.prefix}-*?expand_wildcards=open,closed&allow_no_indices=true`);
  const rows = plan(config, inventory, now);
  const selected = rows.filter(row => row.requested);
  if (selected.some(row => !row.eligible)) throw new Error('Requested index is protected or too recent');
  const deleted = [];
  if (config.apply) {
    // Check every identity before the first destructive call. This is not an ES lease:
    // the operator must stop all application readers/writers and other index admins.
    for (const row of rows.filter(item => item.protected || item.requested)) {
      const exact = await request('GET', `/${row.name}?expand_wildcards=none`);
      if (Object.keys(exact).length !== 1 || !exact[row.name]) throw new Error('Exact index lookup mismatch');
      const current = identity(row.name, exact[row.name], config.prefix);
      if (current.uuid !== row.uuid || current.created !== row.created) throw new Error('Index identity changed');
    }
    for (const row of selected) {
      const receipt = await request('DELETE', `/${row.name}?expand_wildcards=none`);
      if (receipt?.acknowledged !== true) throw new Error('Deletion outcome uncertain; inspect before retry');
      deleted.push(row.name);
    }
  }
  return { mode: config.apply ? 'APPLY' : 'DRY_RUN', indices: rows, deleted };
}

async function main() {
  const config = options(process.argv.slice(2));
  const key = process.env.AI_ELASTICSEARCH_API_KEY ?? '';
  if (/\s|[\x00-\x1f\x7f]/.test(key)) throw new Error('Invalid API key');
  const request = async (method, path) => {
    const response = await fetch(config.url + path, { method, redirect: 'error',
      signal: AbortSignal.timeout(10000), headers: key ? { Authorization: `ApiKey ${key}` } : {} });
    if (!response.ok) { await response.body?.cancel(); throw new Error('Elasticsearch request failed'); }
    const reader = response.body.getReader();
    const parts = []; let size = 0;
    try {
      for (;;) {
        const next = await reader.read(); if (next.done) break;
        size += next.value.length;
        if (size > 2097152) throw new Error('Inventory exceeds response budget');
        parts.push(Buffer.from(next.value));
      }
    } finally { await reader.cancel(); }
    return JSON.parse(Buffer.concat(parts).toString('utf8'));
  };
  console.log(JSON.stringify(await maintain(config, request), null, 2));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(() => { console.error('Snapshot maintenance failed; no automatic retry. Inspect index state before retrying.'); process.exitCode = 1; });
}
