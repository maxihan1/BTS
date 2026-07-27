// BC 키워드 목록이 실제 FR 제목을 얼마나 맞히는지 고정하는 커버리지 회귀 테스트
//
// 왜 이 테스트가 있나. `BC_KEYWORDS` 는 하드코딩 목록이고, 그 목록이 실제 도메인 어휘를
// 얼마나 덮는지 **아무도 보고 있지 않았다**. 그래서 '댓글' 같은 흔한 명사가 빠진 것을
// 3개월 뒤 PR 에서야 발견했다(TODOS §BC_KEYWORDS 항목).
//
// 판별식. 하드코딩 목록끼리 눈으로 대조하지 않는다 —
// `docs/plan/product/*.md` 의 **FR 제목 전수**를 classify() 에 통과시켜 소속 BC 와 대조한다.
// 계획 문서가 늘면 판별식도 자동으로 늘어난다.
//
// ★오라클의 한계를 명시한다. "FR 이 어느 파일에 있나" 는 **계획 문서의 편제**이지
// 라우팅 정답이 아니다. 예를 들어 FR-TT-01 `Worklog` 는 agile-planning.md 에 있지만
// 코드는 issue-tracking 모듈에 산다. 그래서 이 테스트는 "전부 맞혀라" 가 아니라
// **"지금보다 나빠지지 마라"** 를 강제한다 — 현재 불일치를 baseline 으로 동결하고
// 신규 증가만 차단한다. baseline 은 줄여가는 것이 목표이며, 줄면 이 테스트가
// "baseline 을 낮춰라" 라고 알려준다(느슨해진 채 방치되는 것을 막는다).

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { classify } from './classify-task.ts';
import type { BoundedContext } from './types.ts';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const PRODUCT_DIR = path.join(REPO_ROOT, 'docs/plan/product');

/**
 * 계획 문서 파일명 → BoundedContext.
 * 대부분 동명이나 두 곳이 다르다 —
 *  · `notification-dashboard.md` 의 BC 값은 `notification` (모듈명 기준)
 *  · `personalization.md` 는 논리 BC 로, 물리적으로는 identity-access 모듈에 산다
 */
const FILE_TO_BC: Record<string, BoundedContext> = {
  'identity-access': 'identity-access',
  'issue-tracking': 'issue-tracking',
  'project-workflow': 'project-workflow',
  'agile-planning': 'agile-planning',
  'automation': 'automation',
  'notification-dashboard': 'notification',
  'slack-integration': 'slack-integration',
  'search-export-import': 'search-export-import',
  'personalization': 'personalization',
};

interface FrTitle {
  fr: string;
  title: string;
  expected: BoundedContext;
}

/** `### §x.y FR-XX-NN — 제목` 형태의 헤더에서 FR ID 와 제목을 뽑는다 */
function collectFrTitles(): FrTitle[] {
  const rows: FrTitle[] = [];
  for (const file of fs.readdirSync(PRODUCT_DIR).filter((f) => f.endsWith('.md'))) {
    const bc = FILE_TO_BC[path.basename(file, '.md')];
    if (bc === undefined) continue;
    const text = fs.readFileSync(path.join(PRODUCT_DIR, file), 'utf8');
    for (const m of text.matchAll(/^#{2,4} .*?(FR-[A-Z]+-\d+)\s*[—–-]\s*(.+?)\s*$/gm)) {
      rows.push({
        fr: m[1] as string,
        title: (m[2] as string).replace(/[`*]/g, '').trim(),
        expected: bc,
      });
    }
  }
  return rows;
}

/**
 * 동결된 불일치 상한. **줄이는 방향으로만 갱신한다.**
 *
 * 2026-07-27 실측 — 착수 시점 **80/131(61.1%)** → 키워드 정비 후 **49/131(37.4%)**.
 * 정비 내역. 한글 접두사 경계 규칙 도입 · `aql`/`검색`/`search` 를 automation 에서
 * search-export-import 로 이관 · `search-export-import`·`personalization` BC 신설 ·
 * 누락 도메인 명사 보강(댓글·타임라인·대시보드·워크로그 등).
 *
 * ★남은 49건을 0 으로 만들지 않은 이유. 상당수가 **오라클의 모호성**이지 키워드 결함이 아니다.
 * 예 — FR-IS-01 "이슈 CRUD, 상태 변경 시 워크플로우 검증 + 알림" 은 제목 하나에
 * issue-tracking·project-workflow·notification 어휘가 동시에 들어 있다. 계획 문서 편제에
 * 맞추려고 키워드를 더 밀어넣으면 **실사용 입력의 라우팅이 오히려 나빠진다**(과적합).
 * 이 테스트의 목적은 정확도 100% 가 아니라 **커버리지 회귀 차단**이다.
 */
const MAX_MISMATCHES = 49;

/** 판별식이 살아 있는지 확인하는 하한 — 제목 추출이 고장나면 0건이 되고 모든 단언이 공허해진다 */
const MIN_TITLES = 120;

describe('BC 키워드 커버리지', () => {
  test('FR 제목을 충분히 수집한다 (판별식 비-공허 확인)', () => {
    const rows = collectFrTitles();
    assert.ok(
      rows.length >= MIN_TITLES,
      `FR 제목이 ${rows.length}건뿐이다 (하한 ${MIN_TITLES}). 헤더 정규식이 계획 문서 서식 변경을 못 따라갔을 수 있다 — 이 상태에서는 아래 커버리지 단언이 공허하게 통과한다.`,
    );
  });

  test('FR 제목의 BC 분류 불일치가 baseline 을 넘지 않는다', () => {
    const rows = collectFrTitles();
    const mismatches = rows.filter((r) => classify({ title: r.title }).primary_bc !== r.expected);

    const sample = mismatches
      .slice(0, 12)
      .map((r) => `  ${r.fr} "${r.title}" 기대=${r.expected} 실제=${classify({ title: r.title }).primary_bc}`)
      .join('\n');

    assert.ok(
      mismatches.length <= MAX_MISMATCHES,
      `BC 분류 불일치가 ${mismatches.length}건으로 baseline ${MAX_MISMATCHES} 를 넘었다.\n` +
        `BC_KEYWORDS 에 도메인 명사를 추가하거나, 새로 추가한 키워드가 다른 BC 를 침범하지 않는지 확인하라.\n${sample}`,
    );
  });

  test('baseline 이 실제보다 느슨해지면 알려준다 (동결값 갱신 유도)', () => {
    const rows = collectFrTitles();
    const mismatches = rows.filter((r) => classify({ title: r.title }).primary_bc !== r.expected);

    assert.ok(
      mismatches.length >= MAX_MISMATCHES - 4,
      `불일치가 ${mismatches.length}건으로 baseline ${MAX_MISMATCHES} 보다 충분히 낮아졌다. ` +
        `MAX_MISMATCHES 를 ${mismatches.length} 로 낮춰라 — 안 낮추면 이 테스트가 느슨해진 채 방치된다.`,
    );
  });
});

describe('한국어 키워드 경계', () => {
  /**
   * 한국어는 단어 사이에 경계 문자가 없어 단순 `includes` 가 **접미사 오탐**을 낸다.
   * 실측 사례 — "댓글 리액션 추가" 가 automation 으로 갔다. '리**액션**' 이 automation
   * 키워드 '액션' 에 걸렸기 때문이다. null(미정의)보다 나쁜 **조용한 오라우팅**이다.
   *
   * 판별식 — 한국어 키워드는 **앞에 한글 음절이 붙어 있으면 매치로 치지 않는다.**
   * 조사는 뒤에 붙으므로('액션을') 뒤는 허용하고 앞만 막는다.
   */
  test('접미사 오탐 — "리액션" 은 automation 액션으로 매치되지 않는다', () => {
    assert.notEqual(classify({ title: '댓글 리액션 추가' }).primary_bc, 'automation');
  });

  test('정상 매치는 유지된다 — "자동화 액션 추가"', () => {
    assert.equal(classify({ title: '자동화 액션 추가' }).primary_bc, 'automation');
  });

  test('조사가 붙어도 매치된다 — "액션을 추가한다"', () => {
    assert.equal(classify({ title: '자동화 규칙의 액션을 추가한다' }).primary_bc, 'automation');
  });
});

describe('BC 키워드 배치', () => {
  test('검색/AQL 은 search-export-import 로 간다 (automation 아님)', () => {
    assert.equal(classify({ title: 'AQL 파서 성능 개선' }).primary_bc, 'search-export-import');
    assert.equal(classify({ title: '이슈 검색 필터 저장' }).primary_bc, 'search-export-import');
  });

  test('댓글은 issue-tracking 으로 간다', () => {
    assert.equal(classify({ title: '댓글 수정 버그 고쳐줘' }).primary_bc, 'issue-tracking');
  });
});
