import { parse, parseFragment } from 'parse5';

const SKIP = new Set(['script', 'style', 'noscript', 'svg', 'iframe', 'form', 'nav', 'footer', 'aside', 'template']);
const BLOCK = new Set(['p', 'div', 'section', 'article', 'main', 'header', 'blockquote', 'ul', 'ol', 'dl', 'dt', 'dd']);
const attr = (node, name) => node.attrs?.find((item) => item.name === name)?.value;
const hidden = (node) => SKIP.has(node.tagName) || attr(node, 'hidden') !== undefined || attr(node, 'aria-hidden') === 'true';
const children = (node) => node.childNodes ?? [];
const normal = (text) => text.replace(/\s+/gu, ' ').trim();

function textOf(node) {
  if (hidden(node)) return '';
  if (node.nodeName === '#text') return node.value;
  return children(node).map(textOf).join(' ');
}
function find(node, predicate) {
  if (hidden(node)) return [];
  return [...(predicate(node) ? [node] : []), ...children(node).flatMap((child) => find(child, predicate))];
}
export function decodeEntities(text) {
  return children(parseFragment(String(text).replace(/</g, '&lt;'))).map(textOf).join('');
}

/** Structural extraction only; neither medical review nor a publication decision. */
export function extractHtml(html) {
  const document = parse(html);
  const candidates = find(document, (node) => node.tagName === 'main' || attr(node, 'role') === 'main');
  const articles = candidates.length ? candidates : find(document, (node) => node.tagName === 'article');
  const region = articles.sort((a, b) => textOf(b).length - textOf(a).length)[0]
    ?? find(document, (node) => node.tagName === 'body')[0] ?? document;
  const warnings = articles.length ? [] : ['BODY_FALLBACK_REQUIRES_REVIEW'];
  if (articles.length > 1) warnings.push('MULTIPLE_CONTENT_REGIONS_REQUIRES_REVIEW');
  const output = [];
  const headings = [];
  let pending = '';
  function flush() {
    const line = normal(pending);
    if (line) output.push(line);
    pending = '';
  }
  function visit(node) {
    if (hidden(node)) return;
    if (node.nodeName === '#text') { pending += node.value; return; }
    if (/^h[1-6]$/.test(node.tagName ?? '')) {
      flush();
      const title = normal(textOf(node));
      if (title) { headings.push(title); output.push(`${'#'.repeat(Number(node.tagName[1]))} ${title}`); }
      return;
    }
    if (node.tagName === 'table') {
      flush(); warnings.push('TABLE_REQUIRES_REVIEW');
      for (const row of find(node, (item) => item.tagName === 'tr')) {
        const cells = children(row).filter((item) => ['td', 'th'].includes(item.tagName)).map((item) => normal(textOf(item)));
        if (cells.length) output.push(cells.join(' | '));
      }
      return;
    }
    if (node.tagName === 'li') {
      flush();
      const item = normal(textOf(node));
      if (item) output.push(`- ${item}`);
      if (find(node, (child) => child.tagName === 'li').length > 1) warnings.push('NESTED_LIST_REQUIRES_REVIEW');
      return;
    }
    if (node.tagName === 'br' || node.tagName === 'hr') { flush(); return; }
    if (BLOCK.has(node.tagName)) flush();
    for (const child of children(node)) visit(child);
    if (BLOCK.has(node.tagName)) flush();
  }
  visit(region); flush();
  const text = output.join('\n\n');
  if (!text) warnings.push('EMPTY_EXTRACTION');
  return { text, headings, region: region.tagName ?? 'document', warnings: [...new Set(warnings)], extractionVersion: 'html-structural-v1', reviewStatus: 'PENDING' };
}
export function htmlToText(html) { return extractHtml(html).text; }
export function shorten(text, limit = 220) {
  if (text.length <= limit) return text;
  return `${text.slice(0, limit).replace(/\s+\S*$/, '')}…（长度 ${text.length}，仅示位置）`;
}
