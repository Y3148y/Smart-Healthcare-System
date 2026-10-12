const { execFileSync } = require('node:child_process')
const fs = require('node:fs')
const path = require('node:path')

const repoRoot = path.resolve(__dirname, '..')
const binaryExtension = /\.(?:png|jpe?g|webp|gif|ico|pdf|zip|jar|woff2?)$/i
const rules = [
  ['provider-api-key', /\bsk-[A-Za-z0-9_-]{20,}\b/],
  ['github-token', /\bgh[pousr]_[A-Za-z0-9]{30,}\b/],
  ['aws-access-key-id', /\bAKIA[0-9A-Z]{16}\b/],
  ['private-key-block', /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/]
]

function inspectText(text, file) {
  const findings = []
  text.split(/\r?\n/).forEach((line, index) => {
    for (const [rule, pattern] of rules) {
      if (pattern.test(line)) findings.push({ file, line: index + 1, rule })
    }
  })
  return findings
}

function inspectTrackedFiles(root = repoRoot) {
  const files = execFileSync('git', ['-C', root, 'ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(Boolean)
  const findings = []
  for (const file of files) {
    if (binaryExtension.test(file)) continue
    const bytes = fs.readFileSync(path.join(root, file))
    if (bytes.includes(0)) continue
    findings.push(...inspectText(bytes.toString('utf8'), file))
  }
  return { files: files.length, findings }
}

if (require.main === module) {
  const { files, findings } = inspectTrackedFiles()
  for (const { file, line, rule } of findings) console.error(`${file}:${line}: ${rule}`)
  if (findings.length) process.exitCode = 1
  else console.log(`No listed credential patterns found in tracked text files (${files} tracked paths checked).`)
}

module.exports = { inspectText, inspectTrackedFiles }
