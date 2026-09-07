// 서버 sanitize allowlist 가 허용하는 태그 전량이 리치 텍스트 CSS 스타일을 받는지 대조한다
//
// ## 왜 있나
//
// 2026-09-07 실측. `apps/web` 은 `@tailwindcss/typography` 를 **설치하지 않은 채**
// `prose prose-sm` 클래스를 세 곳에서 쓰고 있었다 — 빌드 산출 CSS 에 `.prose` 가 **0회**.
// 그런데 Tailwind v4 preflight 는 `ol, ul { list-style: none }` 로 마커를 지운다. 결과는
// **저장은 되는데 화면에 안 보이는 본문**이었다. Maxi 가 「말머리 숫자서식 동작 안함」으로
// 신고한 것이 그것이다.
//
// 이 결함의 양식은 저장소가 이미 이름 붙였다 — **두 목록이 서로를 검사하지 않는다.**
// 목록 A 는 서버가 허용하는 태그(`MarkdownRenderer.SANITIZE_POLICY`), 목록 B 는 CSS 가
// 스타일을 주는 태그. 둘은 서로를 몰랐고, 그래서 B 가 통째로 비어도 아무도 못 봤다.
//
// ## 무엇을 강제하나
//
// ① **차집합 0** — allowlist 태그 전량이 리치 텍스트 CSS 블록에 셀렉터를 갖는다.
// ② **비-공허 짝 2개** — 두 파서가 각각 하한 이상을 읽는다. 파서가 눈이 멀면 「0건 대 0건」이
//    되어 ① 이 조용히 통과한다.
// ③ **뮤테이션 짝** — 판정 함수를 픽스처로 직접 흔들어, 태그 하나가 빠진 CSS 가 실제로
//    red 를 내는지 본다. ① 은 실제 파일이 이미 초록이면 로직이 죽어도 초록을 유지한다.
//
// ## 강제하지 **않는** 것
//
// - **스타일의 내용**은 안 본다. `ol` 에 셀렉터가 있는지만 보지 `list-style` 값이 옳은지는
//   모른다. 그것은 눈확인과 시각 회귀 스냅샷의 몫이다.
// - **에디터가 그 태그를 만들 수 있는지**도 안 본다. 서버가 허용하면 CSV·import 경로로
//   들어올 수 있으므로 CSS 는 그것까지 덮는 편이 맞다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** 서버 allowlist 정본. 여기 말고 다른 곳에 태그 목록을 적지 않는다. */
const SANITIZER = path.join(
  REPO_ROOT,
  'backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/markdown/MarkdownRenderer.kt',
);

/** 리치 텍스트 CSS 가 사는 파일. */
const STYLESHEET = path.join(REPO_ROOT, 'apps/web/src/index.css');

/** CSS 블록의 경계 마커. 이 사이만 스캔한다 — 파일 전체를 보면 무관한 규칙이 섞인다. */
const BLOCK_START = '/* rich-text:start */';
const BLOCK_END = '/* rich-text:end */';

/** 리치 텍스트 컨테이너 클래스. CSS 셀렉터는 전부 이것으로 시작한다. */
const CONTAINER = '.rich-text';

/**
 * `span` 의 예외 — 이 태그만 컨테이너 셀렉터로 대조하지 않는다.
 *
 * 서버는 `span[class=mention]` **하나만** 허용하고(EC8 — `"mention evil"` 은 거부),
 * 그 스타일은 `index.css` 의 **전역 `.mention`** 규칙이 FR-MN-01 부터 이미 갖고 있다.
 * `.rich-text span` 을 요구하면 **없는 결함을 만들어** 거짓 red 가 된다.
 *
 * 그래서 `span` 은 「`.mention` 규칙이 존재하는가」로 판정한다. 규칙이 사라지면 멘션이
 * 평문으로 보이므로 이 대체 판정도 실질을 지킨다.
 */
const SPAN_RULE = '.mention';

/**
 * 비-공허 하한.
 *
 * 실측 시점 allowlist 는 30태그, CSS 도 같은 30태그다. 하한을 25 로 두어 파서가 눈이 멀거나
 * 파일 서식이 바뀌어 「몇 건만 읽히는」 상태를 잡되, 태그 몇 개를 정당하게 줄이는 변경까지
 * 막지는 않는다.
 */
const MIN_TAGS = 25;

/**
 * Kotlin sanitizer 에서 허용 태그 집합을 읽는다.
 *
 * `.allowElements("h1", "h2", …)` 호출이 **체인으로 여러 줄에 흩어져 있다.** 한 줄만 보면
 * 대부분을 놓치므로 파일 전체에서 모든 호출을 훑는다.
 */
export function parseAllowedTags(kotlinSource: string): Set<string> {
  const tags = new Set<string>();
  for (const call of kotlinSource.matchAll(/\.allowElements\(([^)]*)\)/g)) {
    const args = call[1];
    if (args === undefined) continue;
    for (const literal of args.matchAll(/"([a-zA-Z0-9]+)"/g)) {
      const tag = literal[1];
      if (tag !== undefined) tags.add(tag);
    }
  }
  return tags;
}

/**
 * CSS 에서 리치 텍스트 스타일 대상 태그 집합을 읽는다.
 *
 * 파서 계약(plan Task 1 의 계약표).
 * - **그룹 셀렉터**(`.rich-text ul, .rich-text ol`)를 콤마로 쪼개 각각을 본다.
 * - **결합자**(`.rich-text li > p`)에서도 컨테이너 **바로 다음 태그 토큰**을 뽑는다.
 * - 속성 선택자(`input[type="checkbox"]`)는 태그 이름만 남긴다.
 *
 * 이 계약이 없으면 파서가 헐거워 가짜 green 을, 빡빡해 가짜 red 를 낸다.
 */
export function parseStyledTags(cssBlock: string): Set<string> {
  const tags = new Set<string>();
  // 선언 블록을 버리고 셀렉터만 남긴다. 주석은 먼저 지운다 — 주석 안의 예시가 섞이면 안 된다.
  const withoutComments = cssBlock.replace(/\/\*[\s\S]*?\*\//g, '');
  for (const rule of withoutComments.matchAll(/([^{}]+)\{[^{}]*\}/g)) {
    const selectorList = rule[1];
    if (selectorList === undefined) continue;
    for (const selector of selectorList.split(',')) {
      const trimmed = selector.trim();
      if (!trimmed.startsWith(CONTAINER)) continue;
      // 컨테이너 뒤 첫 토큰. 결합자(`>`·`+`·`~`)와 공백을 건너뛰고 태그 이름을 잡는다.
      const rest = trimmed.slice(CONTAINER.length);
      const match = /^[\s>+~]+([a-zA-Z][a-zA-Z0-9]*)/.exec(rest);
      const tag = match?.[1];
      if (tag !== undefined) tags.add(tag);
    }
  }
  return tags;
}

/**
 * 커버리지 판정 — 스타일을 못 받는 허용 태그 목록을 돌려준다.
 *
 * 빈 배열이면 통과. `span` 은 위 [SPAN_RULE] 대체 판정을 쓴다.
 */
export function findUncoveredTags(
  allowed: Set<string>,
  styled: Set<string>,
  hasSpanRule: boolean,
): string[] {
  const missing: string[] = [];
  for (const tag of [...allowed].sort()) {
    if (tag === 'span') {
      if (!hasSpanRule) missing.push('span (전역 .mention 규칙 부재)');
      continue;
    }
    if (!styled.has(tag)) missing.push(tag);
  }
  return missing;
}

/** `index.css` 에서 마커 사이 블록을 잘라낸다. 마커가 없으면 null. */
function extractBlock(css: string): string | null {
  const start = css.indexOf(BLOCK_START);
  const end = css.indexOf(BLOCK_END);
  if (start === -1 || end === -1 || end < start) return null;
  return css.slice(start + BLOCK_START.length, end);
}

describe('리치 텍스트 CSS 커버리지', () => {
  const kotlinSource = fs.readFileSync(SANITIZER, 'utf8');
  const css = fs.readFileSync(STYLESHEET, 'utf8');

  test('allowlist 파서가 하한 이상을 읽는다 (비-공허 짝)', () => {
    const allowed = parseAllowedTags(kotlinSource);
    assert.ok(
      allowed.size >= MIN_TAGS,
      `sanitizer 에서 허용 태그를 ${allowed.size}개만 읽었다 — allowElements 호출 서식이 바뀌었거나 파서가 눈이 멀었다.`,
    );
    // 대표 표본 — 목록이 통째로 다른 것을 읽는 상황을 잡는다.
    for (const expected of ['ol', 'ul', 'pre', 'code', 'blockquote', 'table', 'span']) {
      assert.ok(allowed.has(expected), `허용 태그에 \`${expected}\` 가 없다 — 파서가 잘못 읽고 있다.`);
    }
  });

  test('CSS 파서가 하한 이상을 읽는다 (비-공허 짝)', () => {
    const block = extractBlock(css);
    assert.ok(block !== null, `\`${BLOCK_START}\` / \`${BLOCK_END}\` 마커를 찾지 못했다 — CSS 블록이 사라졌거나 마커가 바뀌었다.`);
    const styled = parseStyledTags(block);
    assert.ok(
      styled.size >= MIN_TAGS,
      `리치 텍스트 CSS 에서 스타일 대상을 ${styled.size}개만 읽었다 — 셀렉터 서식이 바뀌었거나 파서가 눈이 멀었다.`,
    );
  });

  test('서버 allowlist 전량이 리치 텍스트 CSS 스타일을 받는다', () => {
    const allowed = parseAllowedTags(kotlinSource);
    const block = extractBlock(css);
    assert.ok(block !== null, 'CSS 블록 마커 부재');
    const styled = parseStyledTags(block);
    const hasSpanRule = new RegExp(`\\${SPAN_RULE}\\s*\\{`).test(css);

    assert.deepEqual(
      findUncoveredTags(allowed, styled, hasSpanRule),
      [],
      'CSS 가 스타일을 주지 않는 허용 태그가 있다 — 저장은 되는데 화면에 안 보이는 서식이 생긴다.',
    );
  });

  test('판정 함수가 누락을 실제로 잡는다 (뮤테이션 짝)', () => {
    const allowed = new Set(['ol', 'ul', 'pre', 'span']);

    // 정상 — 전부 덮였다.
    assert.deepEqual(
      findUncoveredTags(allowed, new Set(['ol', 'ul', 'pre']), true),
      [],
      '전부 덮인 입력을 red 로 판정했다.',
    );
    // 한 태그가 빠졌다.
    assert.deepEqual(
      findUncoveredTags(allowed, new Set(['ol', 'ul']), true),
      ['pre'],
      '누락된 태그를 못 잡았다 — 판정 로직이 죽어 있다.',
    );
    // `.mention` 규칙이 사라졌다.
    assert.deepEqual(
      findUncoveredTags(allowed, new Set(['ol', 'ul', 'pre']), false),
      ['span (전역 .mention 규칙 부재)'],
      'span 대체 판정이 동작하지 않는다.',
    );
  });

  test('CSS 파서가 그룹 셀렉터와 결합자를 읽는다 (파서 계약)', () => {
    const fixture = `
      .rich-text ul, .rich-text ol { list-style-position: outside; }
      .rich-text li > p { margin: 0; }
      .rich-text input[type="checkbox"] { margin-right: 0.5em; }
      .some-other-class p { color: red; }
    `;
    const styled = parseStyledTags(fixture);
    assert.ok(styled.has('ul') && styled.has('ol'), '그룹 셀렉터를 콤마로 쪼개 읽지 못했다.');
    assert.ok(styled.has('li'), '결합자가 붙은 셀렉터에서 첫 태그를 못 읽었다.');
    assert.ok(styled.has('input'), '속성 선택자에서 태그 이름을 못 벗겨냈다.');
    assert.ok(!styled.has('p'), '컨테이너 밖 셀렉터(.some-other-class p)를 잘못 집계했다.');
  });

  test('allowlist 파서가 체인으로 흩어진 호출을 전부 읽는다 (파서 계약)', () => {
    const fixture = `
      HtmlPolicyBuilder()
        .allowElements("h1", "h2", "h3")
        .allowElements("strong", "em")
        .allowAttributes("type")
        .allowElements("input")
    `;
    const allowed = parseAllowedTags(fixture);
    assert.deepEqual(
      [...allowed].sort(),
      ['em', 'h1', 'h2', 'h3', 'input', 'strong'],
      '여러 줄에 흩어진 allowElements 호출을 전부 읽지 못했다.',
    );
  });
});
