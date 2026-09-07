// 리치 텍스트를 그리는 자리가 전부 `.rich-text` 를 쓰고, 죽은 `prose` 가 남지 않았는지 본다
//
// ## 왜 있나
//
// 2026-09-07 실측. `apps/web` 에는 `@tailwindcss/typography` 가 **없는데** 세 컴포넌트가
// `prose prose-sm` 을 쓰고 있었다 — 빌드 산출 CSS 에 `.prose` 가 0회였다. 클래스는
// 이름일 뿐이라 **없는 클래스를 써도 아무 오류가 나지 않는다.** 그래서 몇 달을 살았다.
//
// 이 PR 이 `.rich-text` 로 갈아탄 뒤에도 같은 함정이 그대로다. 셋 중 하나만 안 바꾸면
// 그 화면만 조용히 무스타일로 남고, 그것이 정확히 지금까지의 상태다.
//
// ## 무엇을 강제하나
//
// ① `apps/web/src` 어디에도 `prose` 클래스가 **남아 있지 않다**.
// ② 리치 텍스트를 그리는 세 자리가 **전부** `rich-text` 를 쓴다.
// ③ 클래스 이름이 CSS 블록과 일치한다 — 한쪽만 바꾸면 다시 이름만 남는다.
//
// ## 강제하지 **않는** 것
//
// - **새 소비처가 생겼을 때 그것이 클래스를 쓰는지**는 못 본다. 아래 파일 목록은 실측
//   3곳이고, 네 번째가 생기면 사람이 여기 추가해야 한다. 목록이 자라는 종류의 판별식이라
//   비-공허 하한으로 「목록이 통째로 비는」 실패만 막는다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const WEB_SRC = path.join(REPO_ROOT, 'apps/web/src');
const STYLESHEET = path.join(WEB_SRC, 'index.css');

/** 리치 텍스트 컨테이너 클래스. `index.css` 의 셀렉터와 같아야 한다. */
const CLASS = 'rich-text';

/**
 * 리치 텍스트를 그리는 자리.
 *
 * 실측 3곳 — 에디터 입력창 · 본문 읽기 · 댓글 읽기. 세 곳이 **같은 클래스**를 써야
 * 「본문에서는 보이는데 댓글에서는 안 보이는」 차이가 생기지 않는다.
 */
const CONSUMERS: readonly string[] = [
  'components/editor/RichTextEditor.tsx',
  'components/issue/IssueDescription.tsx',
  'components/issue/CommentSection.tsx',
];

/** `.tsx`/`.ts` 파일을 전부 훑는다. */
function walk(dir: string, out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (/\.(tsx?|css)$/.test(entry.name)) out.push(full);
  }
  return out;
}

/**
 * 소스에서 죽은 `prose` 클래스 사용을 찾는다.
 *
 * `prosemirror` 같은 다른 낱말에 걸리지 않게 **클래스 토큰 경계**로 잰다.
 */
export function findProseUsage(source: string): boolean {
  return /(^|["'\s`])prose(-[a-z]+)?(["'\s`]|$)/m.test(source);
}

describe('리치 텍스트 클래스 사용', () => {
  const files = walk(WEB_SRC);

  test('소스 파서가 파일을 실제로 읽는다 (비-공허 짝)', () => {
    assert.ok(files.length >= 200, `apps/web/src 에서 ${files.length}개 파일만 읽었다 — 탐색이 눈이 멀었다.`);
  });

  test('죽은 `prose` 클래스가 남아 있지 않다', () => {
    const offenders = files
      .filter((f) => findProseUsage(fs.readFileSync(f, 'utf8')))
      .map((f) => path.relative(REPO_ROOT, f));
    assert.deepEqual(
      offenders,
      [],
      '`prose` 클래스가 남았다 — @tailwindcss/typography 가 없으므로 그 화면은 서식 없이 그려진다.',
    );
  });

  test('리치 텍스트 소비처 전량이 rich-text 를 쓴다', () => {
    const missing = CONSUMERS.filter(
      (rel) => !fs.readFileSync(path.join(WEB_SRC, rel), 'utf8').includes(CLASS),
    );
    assert.deepEqual(
      missing,
      [],
      `리치 텍스트를 그리는데 \`${CLASS}\` 를 안 쓰는 파일이 있다 — 그 화면만 무스타일로 남는다.`,
    );
  });

  test('클래스 이름이 CSS 블록과 일치한다', () => {
    const css = fs.readFileSync(STYLESHEET, 'utf8');
    assert.ok(
      css.includes(`.${CLASS} `) || css.includes(`.${CLASS} {`),
      `index.css 에 \`.${CLASS}\` 셀렉터가 없다 — 컴포넌트가 존재하지 않는 클래스를 쓰고 있다(이번 결함의 원형).`,
    );
  });

  test('판정 함수가 prose 를 실제로 잡는다 (뮤테이션 짝)', () => {
    assert.equal(findProseUsage('className="rich-text max-w-none"'), false, '깨끗한 소스를 red 로 판정했다.');
    assert.equal(findProseUsage('className="prose prose-sm"'), true, '`prose` 를 못 잡았다.');
    assert.equal(findProseUsage("className={`prose ${x}`}"), true, '템플릿 리터럴 안의 `prose` 를 못 잡았다.');
    // 다른 낱말에 걸리면 안 된다 — `prosemirror` 는 무관하다.
    assert.equal(findProseUsage("import 'prosemirror-state'"), false, '`prosemirror` 를 오탐했다.');
  });
});
