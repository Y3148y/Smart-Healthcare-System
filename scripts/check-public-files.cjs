const { execFileSync } = require('node:child_process')

const allowedCorpusName = /^(document\.zh-CN\.md|provenance\.json|source(?:\.nlm)?\.en\.md)$/
const allowedDocs = new Set(['docs/AGENT.md', 'docs/ARCHITECTURE.md', 'docs/RAG.md'])

function prohibitedReason(path) {
  const normalized = path.replaceAll('\\', '/')
  const name = normalized.split('/').at(-1)
  if (normalized.startsWith('docs/') && !allowedDocs.has(normalized)) return 'private working document'
  if (normalized.startsWith('knowledge/corpus/') && !allowedCorpusName.test(name)) return 'unapproved corpus attachment'
  if (/^(knowledge\/(sources|reference|releases|history)\/|reports\/|scripts\/local\/)/.test(normalized))
    return 'local source, report or release data'
  if (normalized.split('/').some(part => /^\.codex(?:-|$)|^\.idea$|^node_modules$|^target$|^dist$|^data$/.test(part)))
    return 'local or generated directory'
  if (/^(\.env(?:\.|$)|AGENTS\.md$)/.test(normalized) || /\.(?:log|key|pem)$/i.test(name))
    return 'private configuration or credential-like file'
  return null
}

function inspectTrackedFiles(paths) {
  return paths.flatMap(path => {
    const reason = prohibitedReason(path)
    return reason ? [{ path, reason }] : []
  })
}

if (require.main === module) {
  const paths = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(Boolean)
  const violations = inspectTrackedFiles(paths)
  if (violations.length) {
    for (const {path, reason} of violations) console.error(`${path}: ${reason}`)
    process.exitCode = 1
  } else console.log(`Public tracked-file path policy passed (${paths.length} files).`)
}

module.exports = { inspectTrackedFiles }
