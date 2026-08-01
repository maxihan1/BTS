// 일회성 마이그레이션 — 기존 MEMORY.md 의 손 큐레이션(★·hook·그룹)을 각 메모리 frontmatter 로 이주
// 실행. node scripts/doc-index/backfill.mjs [--dry]
//
// ★ build-doc-index.mjs 보다 **먼저** 돌아야 한다. 생성기가 MEMORY.md 를 덮어쓰면
//   승계 원천이 사라지기 때문이다 (메모리는 git 이 추적하지 않아 되돌릴 수 없다).

import fs from 'node:fs';
import path from 'node:path';
import { MEMORY_DIR, AUTOGEN_HEADER } from './config.mjs';
import { parseLegacyIndex } from './parse-memory.mjs';
import { classify, splitFrHistory } from './classify.mjs';

const DRY = process.argv.includes('--dry');
const memoryMd = path.join(MEMORY_DIR, 'MEMORY.md');
const raw = fs.readFileSync(memoryMd, 'utf8');

if (raw.startsWith(AUTOGEN_HEADER)) {
  console.error('FAIL. MEMORY.md 가 이미 자동 생성본이다 — 승계할 손 큐레이션이 없다.');
  console.error('  → 백업에서 원본 MEMORY.md 를 복원한 뒤 다시 실행할 것.');
  process.exit(1);
}

const legacy = parseLegacyIndex(raw);
const files = fs.readdirSync(MEMORY_DIR).filter((f) => f.endsWith('.md') && f !== 'MEMORY.md');
const slugs = files.map((f) => f.replace(/\.md$/, ''));
const { frHistory } = splitFrHistory(slugs, legacy);
const frSet = new Set(frHistory.map((f) => f.slug));

let changed = 0;
let skipped = 0;
for (const f of files) {
  const slug = f.replace(/\.md$/, '');
  const p = path.join(MEMORY_DIR, f);
  const src = fs.readFileSync(p, 'utf8');
  const m = src.match(/^---\n([\s\S]*?)\n---/);
  if (!m) {
    console.warn('SKIP (frontmatter 없음).', slug);
    skipped++;
    continue;
  }
  if (/^\s*(hook|priority|category):/m.test(m[1])) continue; // 이미 이주됨 — 멱등

  const leg = legacy.get(slug);
  const category = frSet.has(slug) ? 'fr-history' : classify(slug, legacy);
  const priority = leg?.star ? 'critical' : 'normal';
  const hook = (leg?.hook || '').replace(/\n/g, ' ').trim();

  // metadata: 블록 끝에 덧붙인다. 들여쓰기 2칸은 기존 형식과 같다.
  const add = [
    hook ? `  hook: ${JSON.stringify(hook)}` : null,
    `  priority: ${priority}`,
    `  category: ${category}`,
  ]
    .filter(Boolean)
    .join('\n');
  const out = src.replace(/^---\n([\s\S]*?)\n---/, `---\n$1\n${add}\n---`);

  if (DRY) {
    console.log(`${slug} → category=${category} priority=${priority} hook=${hook ? 'Y' : '-'}`);
  } else {
    fs.writeFileSync(p, out);
  }
  changed++;
}
console.log(`${DRY ? '[dry] ' : ''}${changed}건 처리. (frontmatter 없어 건너뜀 ${skipped}건)`);
