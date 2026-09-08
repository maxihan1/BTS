// 사이드바 트리에서 지울 16링크의 도달성 회귀 판별식 — 「유일한 진입로」를 걷어내도 그 16화면이 닿는가
//
// ## ① 무엇을 지키나
//
// 사이드바 프로젝트 트리(`ProjectTree.tsx`)는 리포트 4 + 프로젝트 설정 12 링크를 들고 있었고,
// 그 컴포넌트 KDoc 이 스스로 그것을 이렇게 적어 두었다(2026-09-04 전수 grep · `8fe36c3b2`).
//
//   *"★한때 여기 있던 「보드」 **링크 한 줄**이 보드 목록으로 갈린 것이고, 나머지 14개 링크는
//     그 화면들의 **유일한 진입로**라 그대로 남는다."*
//
// 이 PR 이 그 트리를 설정 서브앱으로 재편하며 **그 16링크를 지운다.** 지우고 나서 「없어진
// 것이 없다」를 확인하면 이미 늦다 — 비교 대상이 함께 사라져 무엇을 잃었는지 잴 수 없다.
// 그래서 이 판별식이 **먼저** 선다.
//
// 대상이 18 이 아니라 16 인 이유. 직접 링크 2(백로그·타임라인)는 이미 정본 탭
// (`PROJECT_VIEW_TABS`)이 덮고 있어 트리가 유일한 진입로가 아니었다.
//
// ## ② 왜 `LEGACY_16` 을 하드코딩하는가
//
// 🛑 소스에서 읽으면 안 된다. 출처인 `ProjectTree.tsx` 의 `REPORT_LINKS`·`SETTINGS_LINKS` 가
// **이 PR 로 삭제되기 때문**이다. 읽게 두면 삭제되는 순간 배열이 비고, 빈 집합 ∖ 무엇이든
// 은 항상 비어 있으므로 차집합이 **공허하게 통과**한다. 잃어버린 링크를 못 잡는다.
//
// 출처는 이 브랜치의 base commit 한 곳뿐이다.
//   `git show 26e9a6d07:apps/web/src/components/layout/ProjectTree.tsx`
//
// ★출처가 **소스 상수**지 e2e 가 아니다 (리뷰 E-3). 실측으로 두 목록이 이미 갈려 있었다 —
// `e2e/project-tree.spec.ts` 의 `SETTINGS_LINK_CONTRACT` 는 **11**개인데 소스
// `SETTINGS_LINKS` 는 **12**개다. 「일반」 항목만 `{ to: PROJECT_SETTINGS_PATH, label: '일반' }`
// 로 **상수 참조**여서 리터럴만 grep 한 e2e 계약이 그 1건을 통째로 빠뜨렸다. e2e 를 출처로
// 잡았다면 처음부터 1건을 놓쳤을 것이다. 그 드리프트 자체가 이 판별식이 필요한 실물 증거이고,
// 같은 함정을 {@link parseToPaths} 가 개수 등식으로 막는다.
//
// ## ③ 이것은 «회귀 방지»지 «불변식»이 아니다 (★리뷰 E-5)
//
// `LEGACY_16` 은 이 PR 이후 **아무도 갱신하지 않는다.** 새 설정 화면이 생겨도 16 그대로다.
// 즉 이 판별식이 초록이라고 「모든 설정 화면이 닿는다」가 참인 것이 아니다. 참인 것은
// **「2026-09-08 이전에 트리가 유일한 진입로였던 그 16개가 여전히 닿는다」** 뿐이다.
//
// 진짜 불변식(「모든 설정 라우트가 어디선가 닿는다」)은 `router.ts` 의 라우트 트리를 읽어야
// 하고 **별건이다. 지금 범위를 넓히지 마라.** 이 성격을 적어 두지 않으면 다음 사람이 이것을
// 불변식으로 오해하고, 새 화면을 고아로 만든 채 초록을 보게 된다.
//
// ## ⚠️ 「도달」은 «두 홉 중 두 번째»만 잰다 — 실패 메시지를 그대로 믿지 마라
//
// 이 판별식은 `PROJECT_SETTINGS_NAV` 멤버십을 도달성으로 센다. 그런데 그 nav 는 **이미 설정
// 서브앱 «안»에 있을 때만** 렌더된다. 거기까지 가는 **첫 홉**의 유일한 입구는
// `components/layout/ProjectTree.tsx` 의 `PROJECT_SETTINGS_PATH`(스페이스 `⋯` 드롭다운)이고,
// 이 파일은 그것을 읽지 않는다.
//
// 오늘 깨질 조건은 없다 — 그 입구는 따로 잠겨 있다(`ProjectTree.test.tsx` 가 href 까지 단언 ·
// `e2e/project-settings-nav.spec.ts` S1 이 실제로 그 경로로 진입). 남는 위험은 **안내가 엉뚱한
// 곳을 가리키는 것**이다. 실제 파손이 `⋯` 항목 삭제라면 아래 실패 메시지는 「정본 3개 중
// 한 곳에 그 경로를 되살려라」라고 말하는데, 정본 3개는 멀쩡하고 고칠 자리는 `ProjectTree` 다.
// **red 를 만나면 첫 홉부터 의심해라.** (2026-09-08 코드리뷰 지적 ⑤)
//
// ## 도달 집합은 살아 있는 정본에서 «읽는다»
//
// 하드코딩하는 것은 `LEGACY_16` **뿐**이다. 도달 집합은 정본 3개를 텍스트로 파싱해 만든다 —
// 베껴 두면 정본이 줄어도 못 잡는 「두 목록이 서로를 검사하지 않는」 양식이 된다.
// 런타임 import 를 쓰지 않는 이유는 대상이 `lucide-react` 와 `@/` 별칭을 import 해
// `node --experimental-strip-types` 아래서 해석되지 않기 때문이다(다른 판별식들과 같은 선택).
//
// 텍스트 파싱의 대가는 **조용한 0건**이다. 두 빈 집합은 같아서 차집합이 통과한다
// (`partial-column-parser-lets-unread-column-rot`). 그래서 개수 하한 4개와 `to:` 개수 등식을
// 함께 건다 — 못 읽은 것은 통과가 아니라 **실패**다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** `LEGACY_16` 을 전수 추출한 커밋. 이 SHA 의 `ProjectTree.tsx` 가 유일한 출처다. */
const LEGACY_SOURCE_SHA = '26e9a6d07';

/**
 * 픽스처 원소 수 — 줄면 보호 대상이 조용히 빠진다. 이 상수와 배열이 **서로를 검사한다.**
 *
 * ### 왜 16인가
 * **리포트 4 + 프로젝트 설정 12** 다. 설정 12 중 「일반」(`settings/details`)은 소스에서
 * `{ to: PROJECT_SETTINGS_PATH, label: '일반' }` 로 **상수 참조 뒤에** 있었고, 그래서 리터럴만
 * grep 한 `e2e/project-tree.spec.ts` 의 `SETTINGS_LINK_CONTRACT` 는 **11** 만 셌다(리뷰 E-3).
 * 즉 **12 가 아니라 11 로 보이던 자리가 실재한다** — 그것이 이 상수를 못박는 이유다.
 *
 * 직접 링크 2(백로그·타임라인)는 이미 정본 탭이 덮고 있어 트리가 유일한 진입로가 아니었다.
 * 그래서 18 이 아니라 16 이다.
 *
 * 🛑 **줄이지 마라.** 원소를 하나 지워도 차집합 `LEGACY_16 ∖ reachable` 은 더 작아질 뿐
 * 여전히 비어 있어 판별식이 초록이다. 그 순간 그 경로가 보호 대상에서 조용히 빠진다.
 */
const LEGACY_COUNT = 16;

/**
 * 사이드바 트리가 **유일한 진입로**로 들고 있던 16경로.
 *
 * 구성과 그 근거는 {@link LEGACY_COUNT} 에 있다.
 *
 * 출처: `git show <LEGACY_SOURCE_SHA>:apps/web/src/components/layout/ProjectTree.tsx`
 * (`REPORT_LINKS` · `SETTINGS_LINKS`. 「일반」의 `to` 는 상수 `PROJECT_SETTINGS_PATH` =
 * `/projects/$projectKey/settings/details` 였다 — 리터럴만 grep 하면 놓치는 자리다).
 */
const LEGACY_16: readonly string[] = [
  '/projects/$projectKey/reports/velocity',
  '/projects/$projectKey/reports/cfd',
  '/projects/$projectKey/reports/cycle-time',
  '/projects/$projectKey/reports/worklog',
  '/projects/$projectKey/settings/details',
  '/projects/$projectKey/settings/workflow-scheme',
  '/projects/$projectKey/settings/members',
  '/projects/$projectKey/settings/components',
  '/projects/$projectKey/settings/versions',
  '/projects/$projectKey/settings/custom-fields',
  '/projects/$projectKey/settings/issue-templates',
  '/projects/$projectKey/settings/field-permissions',
  '/projects/$projectKey/settings/automation',
  '/projects/$projectKey/settings/slack-channels',
  '/projects/$projectKey/settings/project-lead',
  '/projects/$projectKey/settings/import',
];

/** 도달 집합의 정본 ① — 설정 서브앱 메뉴 4그룹 10항목. */
const SETTINGS_NAV_SOURCE = 'apps/web/src/components/project/project-shell-mode.ts';
/** 도달 집합의 정본 ② — 프로젝트 뷰 탭 10종. */
const VIEW_TABS_SOURCE = 'apps/web/src/components/project/project-view-tabs.ts';
/** 도달 집합의 정본 ③ — 리포트 링크 4종. */
const REPORT_LINKS_SOURCE = 'apps/web/src/components/project/project-report-links.ts';

/** 정본 ① 이 최소 몇 건을 내야 「파서가 읽었다」고 인정하는가. */
const MIN_SETTINGS_NAV_ITEMS = 10;
/** 정본 ② 의 하한. */
const MIN_VIEW_TABS = 10;
/** 정본 ③ 의 하한. */
const MIN_REPORT_LINKS = 4;
/** 합집합의 하한 — `LEGACY_16` 과 같은 수다. 이보다 적으면 차집합이 공허해진다. */
const MIN_REACHABLE = 16;

function read(relative: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, relative), 'utf8');
}

/** 문자열 모드별 닫는 따옴표 — {@link stripComments} 의 상태 기계가 쓴다. */
const QUOTE_OF = { sq: "'", dq: '"', tpl: '`' } as const;

/**
 * 주석을 지운다 — 문자열 리터럴은 그대로 둔다.
 *
 * ★왜 필요한가. 주석 처리된 `to: '/projects/...'` 한 줄을 도달 집합에 넣으면 「닿는다」는
 * 결론이 거짓이 되고 차집합이 0 이 되어 **false green** 이 난다.
 *
 * ⚠️ 정규식 리터럴은 추적하지 않는다. 대상 3파일에 정규식 리터럴이 없기 때문이고, 생겨서
 * 파서가 깨지면 아래 **개수 하한**이 즉시 잡는다(조용히 0건이 되지 않는다).
 *
 * @param source 원문.
 * @returns 주석이 제거된 소스. 개행은 보존한다.
 */
export function stripComments(source: string): string {
  let out = '';
  let mode: 'code' | 'line' | 'block' | 'sq' | 'dq' | 'tpl' = 'code';
  for (let i = 0; i < source.length; i += 1) {
    const ch = source[i] as string;
    const two = source.slice(i, i + 2);
    if (mode === 'code') {
      if (two === '//' || two === '/*') {
        mode = two === '//' ? 'line' : 'block';
        i += 1;
        continue;
      }
      if (ch === "'") mode = 'sq';
      else if (ch === '"') mode = 'dq';
      else if (ch === '`') mode = 'tpl';
      out += ch;
    } else if (mode === 'line') {
      if (ch === '\n') {
        mode = 'code';
        out += ch;
      }
    } else if (mode === 'block') {
      if (two === '*/') {
        mode = 'code';
        i += 1;
      } else if (ch === '\n') out += ch;
    } else if (ch === '\\') {
      out += ch + (source[i + 1] ?? '');
      i += 1;
    } else {
      if (ch === QUOTE_OF[mode]) mode = 'code';
      out += ch;
    }
  }
  return out;
}

/**
 * `const NAME ... = [ ... ]` 의 배열 본문을 대괄호 균형으로 잘라낸다.
 *
 * 파일 전체를 훑지 않고 **그 상수만** 보는 이유는, 같은 파일의 인터페이스 선언
 * (`readonly to: string`)이나 다른 배열이 도달 집합에 섞이는 것을 막기 위해서다.
 *
 * @param source 주석이 제거된 소스.
 * @param constName 상수 이름.
 * @returns 배열 리터럴의 본문(바깥 대괄호 제외).
 * @throws Error 상수를 못 찾거나 대괄호가 닫히지 않으면. **못 읽은 것을 빈 것으로 세지 않는다.**
 */
export function sliceArrayLiteral(source: string, constName: string): string {
  const anchor = new RegExp(`\\bconst\\s+${constName}\\b[^=]*=\\s*\\[`).exec(source);
  if (anchor === null) {
    throw new Error(
      `${constName} 선언을 못 찾았다 — 정본의 형태가 바뀌었다면 이 파서도 같이 고쳐라`,
    );
  }
  const start = anchor.index + anchor[0].length;
  let depth = 1;
  let i = start;
  for (; i < source.length && depth > 0; i += 1) {
    if (source[i] === '[') depth += 1;
    else if (source[i] === ']') depth -= 1;
  }
  if (depth !== 0) throw new Error(`${constName} 의 배열 대괄호가 닫히지 않았다`);
  return source.slice(start, i - 1);
}

/**
 * 상수 배열에서 `to:` 목적지를 전부 뽑는다.
 *
 * ### ★세는 것과 세려는 것이 같은지 못박는다 (리뷰 E-3 의 실물 전례)
 * 종전 `SETTINGS_LINKS` 12항목 중 「일반」만 `{ to: PROJECT_SETTINGS_PATH, ... }` 로 **상수
 * 참조**였고, 리터럴만 grep 한 `e2e/project-tree.spec.ts` 의 `SETTINGS_LINK_CONTRACT` 는
 * **정확히 11개**가 되어 1건이 조용히 무방비였다. 그래서 `to:` **출현 수**와 **리터럴로 읽힌
 * 수**가 다르면 통과시키지 않고 실패로 올린다 — 상수 참조가 다시 생기면 즉시 걸린다.
 *
 * ### ⚠️ 경로 리터럴은 **단따옴표만** 받는다 — 의도된 fail-closed 다
 * 정본 3파일이 언젠가 `to: "…"` 나 백틱으로 바뀌면 이 함수는 **조용히 0건을 내지 않고 throw**
 * 한다(위 개수 등식이 잡는다). 넓게 받는 것보다 이쪽이 낫다고 판단했다 — 백틱을 허용하는
 * 순간 `` to: `/projects/${key}` `` 같은 **정적 경로가 아닌 것**까지 도달 집합에 들어오는데,
 * 그것은 과소계상보다 나쁜 **거짓 도달**이다.
 * 🛑 따옴표를 바꾸다 영문 모를 red 를 만났다면 그것이 이 설계다. **정규식을 넓히지 말고**
 * 정본의 따옴표를 되돌리거나, 넓힐 값어치가 있다고 판단되면 별건으로 올려라.
 *
 * @param source 정본 파일 원문(주석 포함 가능).
 * @param constName 읽을 상수 이름.
 * @returns 선언 순서대로의 라우트 경로 목록.
 * @throws Error `to:` 중 리터럴로 읽지 못한 것이 있으면.
 */
export function parseToPaths(source: string, constName: string): string[] {
  const body = sliceArrayLiteral(stripComments(source), constName);
  const occurrences = body.match(/\bto:\s*/g)?.length ?? 0;
  const paths = [...body.matchAll(/\bto:\s*'(\/[^']*)'/g)].map((m) => m[1] as string);
  if (paths.length !== occurrences) {
    throw new Error(
      `${constName}: \`to:\` 가 ${occurrences}건인데 경로 리터럴은 ${paths.length}건만 읽었다 — ` +
        '상수 참조나 템플릿 리터럴로 숨은 목적지가 있다. 파서가 세는 것과 세려는 것이 다르다.',
    );
  }
  return paths;
}

/** 정본 3개에서 **읽어서** 만든 도달 집합. 하드코딩하면 목록이 줄어도 못 잡는다. */
interface ReachableSurvey {
  readonly settingsNav: readonly string[];
  readonly viewTabs: readonly string[];
  readonly reportLinks: readonly string[];
  readonly reachable: ReadonlySet<string>;
}

/**
 * 살아 있는 정본 3개를 읽어 도달 집합을 만든다.
 *
 * @returns 정본별 목록과 그 합집합.
 */
export function surveyReachable(): ReachableSurvey {
  const settingsNav = parseToPaths(read(SETTINGS_NAV_SOURCE), 'PROJECT_SETTINGS_NAV');
  const viewTabs = parseToPaths(read(VIEW_TABS_SOURCE), 'PROJECT_VIEW_TABS');
  const reportLinks = parseToPaths(read(REPORT_LINKS_SOURCE), 'PROJECT_REPORT_LINKS');
  return {
    settingsNav,
    viewTabs,
    reportLinks,
    reachable: new Set([...settingsNav, ...viewTabs, ...reportLinks]),
  };
}

/**
 * 판정 함수 — 도달 집합이 덮지 못한 레거시 경로.
 *
 * 순수 함수인 이유는 **픽스처로 직접 흔들기** 위해서다. 지금 초록인 판별식은 공허 통과와
 * 구분되지 않으므로, 같은 파일에서 이 함수를 가짜 집합으로 흔들어 red 를 보여야 한다.
 *
 * @param legacy 지켜야 할 레거시 경로 목록.
 * @param reachable 살아 있는 정본이 만든 도달 집합.
 * @returns 어디서도 닿지 않는 경로(정렬). 비어 있어야 정상이다.
 */
export function unreachablePaths(
  legacy: readonly string[],
  reachable: ReadonlySet<string>,
): string[] {
  return legacy.filter((p) => !reachable.has(p)).sort();
}

describe('프로젝트 내비 도달성 (A-2)', () => {
  // ★이 test 를 「정본 3개를 … 읽었다」 안에 합치지 않는다. 그쪽은 **파일에서 읽은 결과**를
  //   재는 자리고, 이쪽은 **이 파일 안의 픽스처 자신**을 재는 자리다. 층위가 다르다 —
  //   합치면 파일 파싱이 죽었을 때와 픽스처가 줄었을 때가 한 이름 아래 섞여, 실패 메시지만
  //   보고는 어느 쪽을 고쳐야 하는지 갈리지 않는다.
  //   순서상 **맨 앞**에 둔다. 아래 두 단언의 전제이기 때문이다.
  test('픽스처 LEGACY_16 자신이 16건 그대로다 (픽스처의 비-공허)', () => {
    // 🛑 여기가 없으면 픽스처만 무방비다. 아래 「16경로 전수」 test 도 이것을 못 막는다 —
    //    그 test 는 LEGACY_16 을 **순회**하므로 배열이 줄면 순회 횟수만 줄고 통과한다.
    assert.equal(
      LEGACY_16.length,
      LEGACY_COUNT,
      `LEGACY_16 이 ${LEGACY_16.length}건이다 — ${LEGACY_COUNT} 이어야 한다.\n` +
        '  줄었다면 그 경로가 보호 대상에서 조용히 빠진 것이다. 차집합은 더 작아질 뿐 여전히 비어\n' +
        '  있으므로 이 단언 말고는 아무것도 그 사실을 말해 주지 않는다.\n' +
        `  구성: 리포트 4 + 프로젝트 설정 12 (출처 git show ${LEGACY_SOURCE_SHA}:apps/web/src/components/layout/ProjectTree.tsx)`,
    );

    // 중복이 섞이면 길이는 16 그대로인데 **실제로 지키는 경로는 15**가 된다.
    // 길이만 재면 이 형태를 놓치므로 같은 자리에서 함께 잰다.
    assert.equal(
      new Set(LEGACY_16).size,
      LEGACY_COUNT,
      'LEGACY_16 에 중복이 있다 — 길이는 맞는데 실제 보호 대상이 그만큼 줄었다',
    );
  });

  test('정본 3개를 개수 하한 이상으로 읽었다 (공허 통과 방지)', () => {
    const survey = surveyReachable();

    assert.ok(
      survey.settingsNav.length >= MIN_SETTINGS_NAV_ITEMS,
      `PROJECT_SETTINGS_NAV 를 ${survey.settingsNav.length}건 읽었다 — ${MIN_SETTINGS_NAV_ITEMS} 미만이면 파서가 깨졌거나 목록이 줄었다`,
    );
    assert.ok(
      survey.viewTabs.length >= MIN_VIEW_TABS,
      `PROJECT_VIEW_TABS 를 ${survey.viewTabs.length}건 읽었다 — ${MIN_VIEW_TABS} 미만이면 파서가 깨졌거나 탭이 줄었다`,
    );
    assert.ok(
      survey.reportLinks.length >= MIN_REPORT_LINKS,
      `PROJECT_REPORT_LINKS 를 ${survey.reportLinks.length}건 읽었다 — ${MIN_REPORT_LINKS} 미만이면 파서가 깨졌거나 리포트가 줄었다`,
    );
    assert.ok(
      survey.reachable.size >= MIN_REACHABLE,
      `도달 집합이 ${survey.reachable.size}건이다 — ${MIN_REACHABLE} 미만이면 아래 차집합이 공허하게 통과한다`,
    );
  });

  test('★사이드바 트리가 유일한 진입로였던 16경로가 전부 닿는다', () => {
    const { reachable } = surveyReachable();
    const lost = unreachablePaths(LEGACY_16, reachable);

    assert.deepStrictEqual(
      lost,
      [],
      '사이드바 트리에서 지운 링크의 화면이 **어디서도 닿지 않는다**.\n' +
        `  잃어버린 진입로: ${lost.join(', ') || '없음'}\n` +
        `  → 정본 3개(${SETTINGS_NAV_SOURCE} · ${VIEW_TABS_SOURCE} · ${REPORT_LINKS_SOURCE}) 중 한 곳에 그 경로를 되살려라.\n` +
        `  레거시 목록의 출처: git show ${LEGACY_SOURCE_SHA}:apps/web/src/components/layout/ProjectTree.tsx`,
    );
  });
});

// ── 비-공허 짝 — 판정 함수를 픽스처로 직접 흔든다 ────────────────────────────────
//
// 위 두 단언은 **지금 초록인 것이 정상**이다. 그래서 그 초록만으로는 「지킨다」와
// 「아무것도 안 본다」를 구분할 수 없다. 아래가 그 구분을 세우는 유일한 증거다.
describe('프로젝트 내비 도달성 — 비-공허 짝', () => {
  test('★도달 집합에서 1건이 빠지면 반드시 잡는다 (16경로 전수)', () => {
    const { reachable } = surveyReachable();

    for (const lost of LEGACY_16) {
      const shrunk = new Set([...reachable].filter((p) => p !== lost));
      assert.deepStrictEqual(
        unreachablePaths(LEGACY_16, shrunk),
        [lost],
        `${lost} 를 도달 집합에서 빼도 판정이 조용히 통과한다 — 이 경로는 무방비다`,
      );
    }
  });

  test('★판정 함수는 파일 없이도 흔들린다 (순수 픽스처)', () => {
    const legacy = ['/a', '/b'];
    assert.deepStrictEqual(unreachablePaths(legacy, new Set(['/a', '/b'])), []);
    assert.deepStrictEqual(unreachablePaths(legacy, new Set(['/a'])), ['/b']);
    assert.deepStrictEqual(unreachablePaths(legacy, new Set()), ['/a', '/b']);
  });

  test('★상수 참조로 숨은 목적지를 통과시키지 않는다 (리뷰 E-3 재현)', () => {
    // 종전 `SETTINGS_LINKS` 의 「일반」이 정확히 이 모양이었고, 리터럴만 세는 파서는
    // 12항목을 11로 읽어 1건을 무방비로 남겼다.
    const fixture = [
      "const SETTINGS_LINKS = [",
      "  { to: PROJECT_SETTINGS_PATH, label: '일반' },",
      "  { to: '/projects/$projectKey/settings/members', label: '멤버' },",
      ']',
    ].join('\n');

    assert.throws(
      () => parseToPaths(fixture, 'SETTINGS_LINKS'),
      /경로 리터럴은 1건만 읽었다/,
      '상수 참조를 못 본 채 통과하면 그 화면이 조용히 무방비가 된다',
    );
  });

  test('★주석 처리된 목적지를 「닿는다」로 세지 않는다', () => {
    const fixture = [
      'const LINKS = [',
      "  { to: '/live' },",
      "  // { to: '/commented-out' },",
      "  /* { to: '/block-commented' }, */",
      ']',
    ].join('\n');

    assert.deepStrictEqual(
      parseToPaths(fixture, 'LINKS'),
      ['/live'],
      '주석 안의 목적지를 도달 집합에 넣으면 차집합이 0 이 되는 false green 이 난다',
    );
  });

  test('★상수를 못 찾으면 0건이 아니라 실패다', () => {
    // 이름이 바뀌거나 배열이 통째로 사라졌을 때 조용히 빈 집합을 내면 두 빈 집합이 같아져
    // 차집합이 공허하게 통과한다 (`partial-column-parser-lets-unread-column-rot`).
    assert.throws(
      () => parseToPaths('const OTHER = []\n', 'PROJECT_SETTINGS_NAV'),
      /PROJECT_SETTINGS_NAV 선언을 못 찾았다/,
    );
  });
});
