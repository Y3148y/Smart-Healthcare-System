const test = require('node:test')
const assert = require('node:assert/strict')
const { inspectTrackedFiles } = require('./check-public-files.cjs')

test('public source, technical docs, demo screenshots and corpus text assets are allowed', () => {
  assert.deepEqual(inspectTrackedFiles([
    'backend/src/main/java/App.java', 'docs/RAG.md',
    'docs/GETTING_STARTED.md', 'docs/INTERVIEW_GUIDE.md', 'docs/INTERVIEW_DRILL.md',
    'docs/images/patient-triage.png', 'docs/images/admin-dashboard.png',
    'knowledge/corpus/neurology/migraine/document.zh-CN.md',
    'knowledge/corpus/neurology/migraine/provenance.json',
    'knowledge/corpus/neurology/migraine/source.en.md',
    'knowledge/corpus/neurology/migraine/source.nlm.en.md'
  ]), [])
})

test('private local data and unreviewed attachments cannot enter public history', () => {
  const paths = ['backend/data/patients.db', 'reports/rag/run.json', 'knowledge/releases/receipt.json',
    'knowledge/corpus/neurology/migraine/intake-packet.json', 'docs/AGENT_BOARD.md', 'docs/interview-private-notes.md',
    'docs/images/private-session.png',
    '.codex-run/log.txt', 'frontend/dist/index.html', '.env.local', 'secret.pem']
  assert.deepEqual(inspectTrackedFiles(paths).map(item => item.path), paths)
})
