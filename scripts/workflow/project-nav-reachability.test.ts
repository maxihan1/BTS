// 사이드바 트리에서 지울 16링크의 도달성 회귀 판별식 — 「유일한 진입로」를 걷어내도 그 16화면이 닿는가
//
// LEGACY_16 은 이 PR 직전 커밋 `26e9a6d07` 의
// `apps/web/src/components/layout/ProjectTree.tsx` 의 `REPORT_LINKS`(4) + `SETTINGS_LINKS`(12) 다.
// 🛑 소스에서 읽지 않는다 — 그 두 상수가 이 PR 로 삭제된다. 읽게 두면 삭제되는 순간 배열이
// 비고 차집합이 **공허하게 통과**한다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** `LEGACY_16` 을 전수 추출한 커밋. 이 SHA 의 `ProjectTree.tsx` 가 유일한 출처다. */
const LEGACY_SOURCE_SHA = '26e9a6d07';

/**
 * 사이드바 트리가 **유일한 진입로**로 들고 있던 16경로.
 *
 * 리포트 4 + 프로젝트 설정 12. 직접 링크 2(백로그·타임라인)는 이미 정본 탭이 덮고 있어
 * 트리가 유일한 진입로가 아니었으므로 대상이 아니다.
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
