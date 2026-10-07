import { readdir, readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { auditBundledDocument } from './lib/corpus-audit.mjs'

const root = new URL('../../backend/src/main/resources/knowledge/', import.meta.url)
const names = (await readdir(root).catch(error => {
  if (error.code === 'ENOENT') return []
  throw error
})).filter(name => name.endsWith('.md')).sort()
const documents = await Promise.all(names.map(async name => auditBundledDocument(name,
  await readFile(new URL(name, root), 'utf8'))))
console.log(JSON.stringify({ purpose: 'engineering-inventory-not-clinical-approval',
  directory: fileURLToPath(root), documentCount: documents.length,
  status: names.length ? 'BUNDLED_DOCUMENTS_REQUIRE_REVIEW' : 'NO_BUNDLED_MEDICAL_CORPUS', documents }, null, 2))
