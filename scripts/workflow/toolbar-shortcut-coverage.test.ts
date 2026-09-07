// 툴바가 표기하는 단축키가 SHORTCUTS 단일 출처에서만 나오는지 대조한다 (Jira J22)
//
// ## 왜 있나
//
// 2026-09-07 실측. `RichTextToolbar.tsx` 는 버튼마다 `shortcut="⌘⇧-"` 같은 **리터럴을
// 인라인으로** 박고 있었다. 그런데 `@tiptap/extension-horizontal-rule` 과
// `@tiptap/extension-link` 에는 `addKeyboardShortcuts` 가 **없다** — 툴팁이 적어 둔 키를
// 눌러도 아무 일이 일어나지 않았다. 표기와 배선이 서로를 검사하지 않았다.
//
// 인라인 코드는 더 미묘했다. 툴바가 TipTap 기본인 `⌘E` 를 적었는데 Jira 실물은 `⌘⇧M` 이다.
// 「동작은 하지만 Jira 와 다른 키」라 사용자가 두 제품 사이에서 손을 다시 배워야 했다.
//
// ## 처방 — 차집합이 아니라 목록 합치기
//
// 흔한 처방은 「표기 목록 ↔ 키맵 목록」 차집합 판별식이다. 여기서는 **목록을 하나로 만들어**
// 그 결함을 원천 제거했다 — `rich-text-shortcuts.ts` 의 `SHORTCUTS` 가 표기(`display`)와
// 키(`key`)를 **같은 항목의 두 필드**로 갖는다. 하나만 고치는 것이 구조적으로 불가능하다.
//
// 그래서 이 판별식이 지키는 것은 **「합쳐진 상태가 유지되는가」**다.
//
// ① 툴바 소스에 단축키 리터럴이 **0개**다 — 하나라도 되살아나면 목록이 다시 둘로 갈린다.
// ② 툴바가 표기를 `SHORTCUTS` 에서 조회한다(그 함수가 살아 있다).
// ③ 키맵 확장이 `SHORTCUTS` 전량을 등록한다 — 일부만 등록하면 적힌 키가 죽는다.
//
// ## 강제하지 **않는** 것
//
// - **키가 실제로 그 명령을 하는지**는 못 본다. 그것은 실제 에디터에 키를 쏘는
//   `apps/web/src/components/editor/__tests__/rich-text-shortcuts.test.ts` 의 몫이다.
// - **Jira 실물과 같은지**도 못 본다. 근거는 plan 의 `## Jira 대조` J22 행이고 사람이 본다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

const TOOLBAR = path.join(REPO_ROOT, 'apps/web/src/components/editor/RichTextToolbar.tsx');
const SHORTCUTS_SRC = path.join(REPO_ROOT, 'apps/web/src/components/editor/rich-text-shortcuts.ts');

/**
 * 단축키 표기에 쓰이는 mac 수식자 기호.
 *
 * 툴바 **코드**에 이 기호가 나오면 표기를 하드코딩했다는 뜻이다. 주석은 예외다 —
 * 「종전에는 ⌘⇧9 를 적었다」 같은 경위 설명까지 막으면 이유를 남길 수 없다.
 */
const MODIFIER_GLYPHS = ['⌘', '⇧', '⌥'] as const;

/** 하한 — SHORTCUTS 가 통째로 비면 아래 단언들이 공허하게 통과한다. */
const MIN_SHORTCUTS = 8;

/** JSX/TS 소스에서 주석을 걷어낸다. 문자열 안의 `//` 는 이 판별식 대상에 없다. */
export function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

/** `SHORTCUTS` 배열의 `display` 값을 읽는다. */
export function parseDisplays(shortcutsSource: string): string[] {
  return [...shortcutsSource.matchAll(/display:\s*'([^']+)'/g)]
    .map((m) => m[1])
    .filter((v): v is string => v !== undefined);
}

/** `SHORTCUTS` 배열의 `key` 값을 읽는다. */
export function parseKeys(shortcutsSource: string): string[] {
  return [...shortcutsSource.matchAll(/key:\s*'([^']+)'/g)]
    .map((m) => m[1])
    .filter((v): v is string => v !== undefined);
}

/** 코드(주석 제외)에 남아 있는 수식자 기호를 찾는다. */
export function findHardcodedGlyphs(source: string): string[] {
  const code = stripComments(source);
  return MODIFIER_GLYPHS.filter((g) => code.includes(g));
}

describe('툴바 단축키 단일 출처', () => {
  const toolbar = fs.readFileSync(TOOLBAR, 'utf8');
  const shortcuts = fs.readFileSync(SHORTCUTS_SRC, 'utf8');

  test('SHORTCUTS 가 하한 이상을 담는다 (비-공허 짝)', () => {
    const displays = parseDisplays(shortcuts);
    const keys = parseKeys(shortcuts);
    assert.ok(
      displays.length >= MIN_SHORTCUTS,
      `SHORTCUTS 의 display 를 ${displays.length}개만 읽었다 — 배열 서식이 바뀌었거나 파서가 눈이 멀었다.`,
    );
    assert.equal(
      displays.length,
      keys.length,
      'display 와 key 의 개수가 다르다 — 항목 하나가 한쪽 필드를 빠뜨렸다.',
    );
  });

  test('툴바 코드에 단축키 리터럴이 없다 (목록이 둘로 갈리지 않는다)', () => {
    assert.deepEqual(
      findHardcodedGlyphs(toolbar),
      [],
      '툴바 코드에 단축키 표기가 하드코딩됐다 — SHORTCUTS 와 갈리는 순간 툴팁이 다시 거짓말을 한다. `shortcutFor(label)` 로 조회할 것.',
    );
  });

  test('툴바가 SHORTCUTS 에서 표기를 조회한다', () => {
    assert.ok(
      toolbar.includes("from './rich-text-shortcuts'"),
      '툴바가 단축키 모듈을 import 하지 않는다 — 표기의 출처가 사라졌다.',
    );
    assert.match(
      toolbar,
      /SHORTCUTS\.find\(/,
      '툴바가 SHORTCUTS 를 조회하지 않는다 — 표기를 어디선가 다시 만들고 있을 가능성이 높다.',
    );
  });

  test('키맵 확장이 SHORTCUTS 전량을 등록한다', () => {
    // 일부만 등록하면 툴팁에 적힌 키가 죽는다 — 이 PR 이 고친 결함 그 자체다.
    assert.match(
      shortcuts,
      /for \(const shortcut of SHORTCUTS\)/,
      '키맵 확장이 SHORTCUTS 를 전량 순회하지 않는다 — 일부만 등록되면 적힌 키가 동작하지 않는다.',
    );
    assert.match(
      shortcuts,
      /addKeyboardShortcuts\(\)/,
      '키맵 등록 지점이 사라졌다.',
    );
  });

  test('판정 함수가 하드코딩을 실제로 잡는다 (뮤테이션 짝)', () => {
    assert.deepEqual(
      findHardcodedGlyphs('const a = shortcutFor(label)'),
      [],
      '깨끗한 소스를 red 로 판정했다.',
    );
    assert.deepEqual(
      findHardcodedGlyphs('<Button shortcut="⌘⇧K" />'),
      ['⌘', '⇧'],
      '하드코딩된 표기를 못 잡았다 — 판정 로직이 죽어 있다.',
    );
    // 주석은 통과해야 한다. 막으면 경위를 코드에 남길 수 없다.
    assert.deepEqual(
      findHardcodedGlyphs('// 종전에는 ⌘⇧9 를 적었다\nconst a = 1'),
      [],
      '주석 안의 표기를 하드코딩으로 오판했다 — 이유를 적을 자리가 사라진다.',
    );
  });

  test('TaskList 기본 키맵을 회수한 배선이 살아 있다', () => {
    // Jira 의 ⌘⇧9 는 인용인데 TipTap TaskList 가 그 키를 기본으로 갖는다. 회수 코드가
    // 사라지면 「인용을 눌렀는데 체크박스가 나온다」가 조용히 되살아난다.
    const extensions = fs.readFileSync(
      path.join(REPO_ROOT, 'apps/web/src/components/editor/rich-text-extensions.ts'),
      'utf8',
    );
    assert.match(
      extensions,
      /TaskList\.extend\(\{\s*addKeyboardShortcuts:\s*\(\)\s*=>\s*\(\{\}\)\s*\}\)/,
      'TaskList 기본 키맵 회수가 사라졌다 — Mod-Shift-9 를 TaskList 가 다시 선점해 인용 단축키가 죽는다.',
    );
  });
});
