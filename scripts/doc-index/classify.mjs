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

/**
 * FR 완료 이력(→ docs/INDEX-fr.md)과 카테고리 대상(→ memory/index/*.md)으로 가른다.
 * 사람이 fr-done 이 아닌 그룹에 명시 등록한 것은 규칙/허브 메모리이므로 카테고리에 남긴다.
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
