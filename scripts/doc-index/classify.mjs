// 메모리를 FR 완료 이력과 카테고리로 가르고, 카테고리는 4단계 우선순위로 판정한다
import { CLASSIFY_PATTERNS, FR_ID_RE, MANUAL_FR_OVERRIDE, CATEGORIES } from './config.mjs';

const VALID = new Set(CATEGORIES.map((c) => c.key));

/**
 * 카테고리 판정. 승계 → 패턴 → uncategorized.
 * 승계 값이 fr-done 이면 카테고리가 아니므로 패턴으로 내려간다.
 */
export function classify(slug, legacyIndex) {
  const legacy = legacyIndex.get(slug);
  if (legacy && VALID.has(legacy.category)) return legacy.category;
  for (const [re, cat] of CLASSIFY_PATTERNS) if (re.test(slug)) return cat;
  return 'uncategorized';
}

/** slug 에서 FR ID 를 뽑는다. 수동 오버라이드 우선. 못 뽑으면 null. */
export function frIdOf(slug) {
  if (MANUAL_FR_OVERRIDE[slug]) return MANUAL_FR_OVERRIDE[slug];
  const m = slug.match(FR_ID_RE);
  return m ? `FR-${m[1].toUpperCase()}-${m[2]}` : null;
}

/**
 * 전체 엔트리를 FR 완료 이력과 카테고리 대상으로 가른다.
 *
 * ★ frontmatter 의 category 가 **정본**이다. backfill 이 끝나면 승계 원천(MEMORY.md)은
 *   자동 생성본으로 바뀌어 legacy 가 빈 Map 이 된다. 그때도 분류가 흔들리지 않아야
 *   재생성 결과가 매번 같다(룰 I). frontmatter 가 없는 신규 메모리만 승계+패턴으로 판정한다.
 */
export function partition(entries, legacyIndex) {
  const frHistory = [];
  const categorized = [];
  const unknown = [];
  for (const e of entries) {
    if (!e.category) {
      unknown.push(e.slug);
    } else if (e.category === 'fr-history') {
      frHistory.push({ slug: e.slug, frId: frIdOf(e.slug) });
    } else {
      categorized.push(e.slug);
    }
  }
  const fallback = splitFrHistory(unknown, legacyIndex);
  return {
    frHistory: [...frHistory, ...fallback.frHistory],
    categorized: [...categorized, ...fallback.categorized],
  };
}

/**
 * FR 완료 이력(→ docs/INDEX-fr.md)과 카테고리 대상(→ memory/index/*.md)으로 가른다.
 * 사람이 fr-done 이 아닌 그룹에 명시 등록한 것은 규칙/허브 메모리이므로 카테고리에 남긴다.
 * frontmatter 가 아직 없는 메모리에만 쓴다 — 정본은 partition() 이다.
 */
export function splitFrHistory(slugs, legacyIndex) {
  const frHistory = [];
  const categorized = [];
  for (const slug of slugs) {
    const override = MANUAL_FR_OVERRIDE[slug];
    const m = slug.match(FR_ID_RE);
    const legacy = legacyIndex.get(slug);
    const legacyClaimsCategory = legacy && VALID.has(legacy.category);
    if (!legacyClaimsCategory && (override || m)) {
      frHistory.push({
        slug,
        frId: override || `FR-${m[1].toUpperCase()}-${m[2]}`,
      });
    } else {
      categorized.push(slug);
    }
  }
  return { frHistory, categorized };
}
