// These historical probes can write synthetic records or consume model quota.
// Validate before loading browser libraries or making any network request.
function localTestTarget(value, args = process.argv.slice(2)) {
  if (!args.includes('--apply')) throw new Error('Live probe disabled. Use --apply only on an isolated demo/test instance; synthetic records and model usage may remain.')
  if (!value) throw new Error('Set an explicit local test base URL; no default daily-service target is selected.')
  const url = new URL(value)
  if (url.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
      || url.username || url.password || url.search || url.hash || url.pathname !== '/') {
    throw new Error('Test target must be a loopback HTTP origin without credentials, path, query or fragment.')
  }
  return url.origin
}
module.exports = { localTestTarget }
