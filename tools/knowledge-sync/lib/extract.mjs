/**
 * Zero-dependency HTML → plain text.
 *
 * This exists only so a human (or an AI with judgement) can read what a page says and decide
 * whether it is worth rewriting into the repository's own short-paragraph style. The output is
 * never published and never copied into the corpus, so a crude extractor is acceptable here —
 * but it is crude, and `proposals` labels it as unverified reading material.
 */
const ENTITIES = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—',
  hellip: '…', md: '—', lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”', times: '×', deg: '°',
};

export function decodeEntities(text) {
  return text.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (match, body) => {
    if (body.startsWith('#x') || body.startsWith('#X')) {
      const code = Number.parseInt(body.slice(2), 16);
      return Number.isFinite(code) ? String.fromCodePoint(code) : match;
    }
    if (body.startsWith('#')) {
      const code = Number.parseInt(body.slice(1), 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : match;
    }
    const mapped = ENTITIES[body.toLowerCase()];
    return mapped ?? match;
  });
}

/** Prefer a single main/article block when the page exposes one; otherwise use the whole body. */
function pickMainRegion(html) {
  for (const tag of ['main', 'article']) {
    const match = new RegExp(`<${tag}\\b[^>]*>([\\s\\S]*?)</${tag}>`, 'i').exec(html);
    if (match && match[1].replace(/<[^>]+>/g, ' ').trim().length > 400) return match[1];
  }
  const roleMain = /<[a-z]+\b[^>]*role=["']main["'][^>]*>([\s\S]*?)<\/[a-z]+>/i.exec(html);
  if (roleMain && roleMain[1].replace(/<[^>]+>/g, ' ').trim().length > 400) return roleMain[1];
  return html;
}

const BOILERPLATE = [
  /^(skip to|cookie|privacy|cookie policy|terms and conditions|all rights reserved)/i,
  /^(share|print page|search this site|menu|navigation|home)$/i,
  /^(last reviewed|next review due|page last reviewed)/i,
];

export function htmlToText(html) {
  let text = html
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<(script|style|noscript|svg|iframe|form|nav|header|footer|aside|figure)\b[\s\S]*?<\/\1>/gi, ' ')
    .replace(/<(br|\/p|\/div|\/li|\/h[1-6]|\/tr|\/td|hr)\s*\/?>/gi, '\n');
  text = pickMainRegion(text);
  text = text
    .replace(/<[^>]+>/g, ' ')
    .replace(/[ \t ]+/g, ' ');
  text = decodeEntities(text);
  const lines = text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 1)
    .filter((line) => !BOILERPLATE.some((pattern) => pattern.test(line)));
  const paragraphs = [];
  for (const line of lines) {
    if (paragraphs.length > 0 && line.length < 60 && !/[。．.!?！？]$/.test(paragraphs.at(-1))) {
      paragraphs[paragraphs.length - 1] += ' ' + line;
    } else {
      paragraphs.push(line);
    }
  }
  return paragraphs.join('\n').replace(/\n{2,}/g, '\n').trim();
}

export function shorten(text, limit = 220) {
  if (text.length <= limit) return text;
  return `${text.slice(0, limit).replace(/\s+\S*$/, '')}…（长度 ${text.length}，仅示位置）`;
}