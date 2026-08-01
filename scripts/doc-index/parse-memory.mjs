// 메모리 파일의 frontmatter 를 읽고, 기존 MEMORY.md 에서 손 큐레이션(그룹·★·hook)을 승계 추출한다
import { LEGACY_GROUP_MAP } from './config.mjs';

const FM_KEYS = ['hook', 'priority', 'category', 'type'];

/** `---` 로 감싼 frontmatter 를 평평한 객체로 읽는다. 없으면 null. */
export function parseFrontmatter(content) {
  const m = content.match(/^---\n([\s\S]*?)\n---/);
  if (!m) return null;
  const out = {};
  for (const line of m[1].split('\n')) {
    const kv = line.match(/^\s*([a-zA-Z_]+):\s*(.*)$/);
    if (!kv) continue;
    const key = kv[1];
    let val = kv[2].trim();
    // description 은 따옴표로 감싸는 경우가 있다
    if (val.startsWith('"') && val.endsWith('"') && val.length > 1) val = val.slice(1, -1);
    if (key === 'name' || key === 'description' || FM_KEYS.includes(key)) {
      if (out[key] === undefined) out[key] = val;
    }
  }
  return out;
}

/**
 * 기존 MEMORY.md 를 파싱해 slug → {category, star, hook} 를 만든다.
 * 손 큐레이션을 버리지 않고 frontmatter 로 이주시키기 위한 seed 다.
 */
export function parseLegacyIndex(content) {
  const out = new Map();
  let cur = null;
  for (const rawLine of content.split('\n')) {
    const h = rawLine.match(/^##\s+(.*)$/);
    if (h) {
      cur = null;
      for (const [k, v] of Object.entries(LEGACY_GROUP_MAP)) {
        if (h[1].startsWith(k)) {
          cur = v;
          break;
        }
      }
      continue;
    }
    if (!cur) continue;
    // 줄 전체의 hook 은 마지막 `—` 뒤. 링크가 여러 개인 묶음 행에서는 hook 을 쓰지 않는다.
    const links = [...rawLine.matchAll(/\[([^\]]*)\]\(([a-z0-9-]+)\.md\)/g)];
    const dashIdx = rawLine.indexOf(' — ');
    const lineHook = links.length === 1 && dashIdx > -1 ? rawLine.slice(dashIdx + 3).trim() : '';
    for (let i = 0; i < links.length; i++) {
      const m = links[i];
      const label = m[1];
      const slug = m[2];
      if (out.has(slug)) continue;
      // ★ 는 줄 전체가 아니라 **직전 링크**에 귀속된다.
      //   `[A](a.md) ★설명 · [B](b.md)` 에서 ★ 는 A 의 것이지 B 의 것이 아니다.
      //   줄 전체로 판정하면 묶음 행 하나가 링크 5개를 전부 ★ 로 만들어 과다 계상된다.
      const tailStart = m.index + m[0].length;
      const tailEnd = i + 1 < links.length ? links[i + 1].index : rawLine.length;
      const ownedTail = rawLine.slice(tailStart, tailEnd);
      out.set(slug, {
        category: cur,
        star: label.includes('★') || ownedTail.includes('★'),
        hook: lineHook,
        label: label.replace(/\*\*/g, '').replace(/★/g, '').trim(),
      });
    }
  }
  return out;
}
