import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { CACHE_DIR, REPORTS_DIR } from './registry.mjs';
import { extractHtml } from './extract.mjs';

const hash = (value) => createHash('sha256').update(value).digest('hex');

export function buildCandidate(source, record, raw) {
  if (!/^[a-z0-9][a-z0-9-]*$/.test(source.id ?? '')) throw new Error('INVALID_SOURCE_ID');
  if (!['new', 'updated', 'unchanged'].includes(record?.status)) throw new Error('NO_SUCCESSFUL_FETCH');
  if (hash(raw) !== record.sha256) throw new Error('SNAPSHOT_HASH_MISMATCH');
  const extraction = extractHtml(new TextDecoder('utf-8', {fatal:true}).decode(raw));
  if (!extraction.text || !extraction.text.split('\n').some((line) => line.trim() && !line.startsWith('#'))) {
    throw new Error('NO_BODY_CONTENT');
  }
  return {
    schemaVersion: 1, sourceId: source.id, reviewStatus: 'PENDING',
    title: source.name, body: extraction.text,
    extraction: { version: extraction.extractionVersion, region: extraction.region,
      warnings: extraction.warnings, headings: extraction.headings, contentSha256: hash(extraction.text) },
    metadata: { schemaVersion: 1, language: 'und', contentKind: 'source_extract',
      sources: [{sourceId: source.id, publisher: source.publisher, url: source.url,
        fetchedAt: record.fetchedAt, rawSha256: record.sha256}],
      topics: [], population: [], exclusions: [], prerequisites: [], evidenceUses: [],
      permissionStatus: 'pending', permissionProof: '' },
    reviewRequired: ['language', 'topics', 'population', 'exclusions', 'prerequisites',
      'evidenceUses', 'permissionProof', 'medicalFidelity'],
    registryLicenseNote: source.licenseNote ?? '',
  };
}

export function writeCandidates(sources, records) {
  const output = join(REPORTS_DIR, 'intake');
  mkdirSync(output, {recursive:true});
  const results = [];
  for (const source of sources) {
    try {
      if (!/^[a-z0-9][a-z0-9-]*$/.test(source.id ?? '')) throw new Error('INVALID_SOURCE_ID');
      const path = join(CACHE_DIR, source.id, 'snapshot.html');
      if (!existsSync(path)) throw new Error('NO_SNAPSHOT');
      const candidate = buildCandidate(source, records[source.id], readFileSync(path));
      writeFileSync(join(output, `${source.id}.json`), JSON.stringify(candidate, null, 2) + '\n', 'utf8');
      results.push({id:source.id, status:'PENDING', warnings:candidate.extraction.warnings});
    } catch (error) {
      results.push({id:source.id, status:'BLOCKED', reason:error.message});
    }
  }
  writeFileSync(join(output, 'manifest.json'), JSON.stringify({generatedAt:new Date().toISOString(), results}, null, 2) + '\n');
  return results;
}
