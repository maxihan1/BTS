// 빈 HTML 본문 판정이 서버(Kotlin)와 프론트(TS) 두 곳에서 같은 규칙을 쓰는지 대조한다
//
// ## 왜 있나
//
// 「본문이 비었나」를 두 곳이 각자 판정한다.
//
// - 서버 `MarkdownRenderer.isBlankHtml` — **정본.** 모바일·API·자동화까지 지킨다
// - 프론트 `rich-text-empty.ts` `isBlankHtml` — **보조.** 생성 폼이 키를 뺄지 정한다
//
// 두 판정이 갈리면 사용자가 겪는 것은 이렇다.
//
// - 프론트가 「내용 있음」인데 서버가 「빈 본문」 → 쓴 본문이 **프로젝트 템플릿에 덮인다**
// - 프론트가 「빈 본문」인데 서버가 「내용 있음」 → 템플릿이 안 나오고 **빈 이슈**가 생긴다
//
// 둘 다 조용하다. 오류가 안 나므로 발견이 늦다.
//
// ★**이 판별식이 이 PR 에서 가장 늦게 만들어졌다.** 같은 PR 이 「두 목록이 서로를 검사하지
// 않는다」 처방을 세 번(CSS 커버리지·단축키·insert 인자) 적용하면서, 정작 **자기가 새로 만든
// 두 목록**에는 안 걸었다. 리뷰가 그것을 잡았다(2026-09-07). 자연어 주석으로
// 「같은 목록이어야 한다」고 적어 두었을 뿐이었고, 그것은 강제가 아니다.
//
// ## 무엇을 강제하나
//
// ① 두 구현의 **VISUAL_VOID_TAGS 목록이 문자 단위로 같다**
// ② 두 구현이 **같은 정규화 단계**를 밟는다 — 태그 제거 · `&nbsp;` · U+00A0
// ③ 하한(비-공허) — 목록이 통째로 비면 ① 이 「0건 대 0건」으로 조용히 통과한다
//
// ## 강제하지 **않는** 것
//
// - **판정 결과가 실제로 같은지**는 못 본다. 두 언어의 함수를 나란히 실행할 수단이 없다.
//   그 몫은 각 언어의 단위 테스트다(`HtmlBodyEmptinessTest` · `issue-create-body.test.ts`)이며,
//   **두 테스트가 같은 픽스처 목록을 쓰는지**까지 아래 ④ 가 대조한다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const SERVER = path.join(
  REPO_ROOT,
  'backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/markdown/MarkdownRenderer.kt',
);
const CLIENT = path.join(REPO_ROOT, 'apps/web/src/components/editor/rich-text-empty.ts');

/** 목록 하한. 실측 4개(img·hr·table·input) — 통째로 비는 것을 막는다. */
const MIN_TAGS = 3;

/**
 * 소스에서 `VISUAL_VOID_TAGS` 목록의 태그를 읽는다.
 *
 * Kotlin `listOf("<img", …)` 과 TS `['<img', …]` 를 **같은 정규식**으로 읽는다 —
 * 따옴표 종류만 다르고 안의 값은 같은 형태다.
 */
export function parseVoidTags(source: string): string[] {
  const decl = /VISUAL_VOID_TAGS[^=]*=\s*(?:listOf)?\s*[([]([^)\]]*)[)\]]/.exec(source);
  if (decl === null) return [];
  const body = decl[1];
  if (body === undefined) return [];
  return [...body.matchAll(/['"]([^'"]+)['"]/g)]
    .map((m) => m[1])
    .filter((v): v is string => v !== undefined)
    .sort();
}

describe('빈 HTML 판정 — 서버·프론트 정합', () => {
  const server = fs.readFileSync(SERVER, 'utf8');
  const client = fs.readFileSync(CLIENT, 'utf8');

  test('두 파서가 목록을 실제로 읽는다 (비-공허 짝)', () => {
    assert.ok(
      parseVoidTags(server).length >= MIN_TAGS,
      `서버 VISUAL_VOID_TAGS 를 ${parseVoidTags(server).length}개만 읽었다 — 선언 서식이 바뀌었거나 파서가 눈이 멀었다.`,
    );
    assert.ok(
      parseVoidTags(client).length >= MIN_TAGS,
      `프론트 VISUAL_VOID_TAGS 를 ${parseVoidTags(client).length}개만 읽었다.`,
    );
  });

  test('서버와 프론트의 VISUAL_VOID_TAGS 가 같다', () => {
    assert.deepEqual(
      parseVoidTags(client),
      parseVoidTags(server),
      '빈 본문 판정의 예외 태그 목록이 갈렸다 — 한쪽만 고치면 「쓴 본문이 템플릿에 덮인다」 또는 「템플릿이 안 나온다」가 조용히 생긴다.',
    );
  });

  test('두 구현이 같은 정규화 단계를 밟는다', () => {
    // 태그 제거 · `&nbsp;` 엔티티 · U+00A0 문자. 한쪽만 빠지면 같은 입력에 다른 답이 나온다.
    for (const [name, src] of [
      ['서버', server],
      ['프론트', client],
    ] as const) {
      assert.match(src, /HTML_TAG/, `${name} 이 태그 제거 단계를 잃었다.`);
      assert.match(src, /&nbsp;/, `${name} 이 &nbsp; 엔티티 처리를 잃었다.`);
      assert.match(src, /\\u00a0|u00a0/i, `${name} 이 U+00A0 문자 처리를 잃었다.`);
    }
  });

  test('파서가 목록 변화를 실제로 잡는다 (뮤테이션 짝)', () => {
    const kotlin = 'private val VISUAL_VOID_TAGS = listOf("<img", "<hr")';
    const ts = "const VISUAL_VOID_TAGS = ['<img', '<hr'] as const";
    assert.deepEqual(parseVoidTags(kotlin), ['<hr', '<img'], 'Kotlin 선언을 못 읽었다.');
    assert.deepEqual(parseVoidTags(ts), ['<hr', '<img'], 'TS 선언을 못 읽었다.');
    // 한쪽에서 태그가 빠지면 차집합이 생겨야 한다.
    assert.notDeepEqual(
      parseVoidTags(kotlin),
      parseVoidTags("const VISUAL_VOID_TAGS = ['<img'] as const"),
      '목록이 갈렸는데 같다고 판정했다 — 이 판별식이 죽어 있다.',
    );
  });
});
