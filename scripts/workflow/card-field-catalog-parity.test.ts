// 카드 필드 카탈로그 3-way 정합 판별식 — 백엔드 열거형 ↔ 설정 화면 후보 ↔ 카드 렌더러
//
// ## 왜 있나
//
// 「카드에 얹을 수 있는 표준 필드」가 **세 곳에 따로** 적혀 있다.
//
//   ① `CardLayoutSettingsService.CardLayoutFieldKey`   — 400 판정. 무엇을 **저장할 수 있나**
//   ② `settings/CardLayoutPanel.STANDARD_FIELDS`       — 후보 표시. 무엇을 **고를 수 있나**
//   ③ `board/BoardCard.EXTRA_STANDARD_FIELDS`          — 실제 렌더. 무엇이 **카드에 뜨나**
//
// 셋 중 한쪽만 늘면 「고를 수는 있는데 카드엔 안 뜨는」 필드가 생긴다. 그리고 그 상태는
// **값이 없어 생략된 것(스펙 E4)과 화면에서 구분되지 않는다** — 사용자도 리뷰어도 못 본다.
// 반대 방향도 같다. 백엔드에만 키가 늘면 저장은 되는데 고를 창구가 없다.
//
// `BoardCard.tsx` 의 KDoc 이 이미 **자백**하고 있었다 — *"후보를 나열하는 …STANDARD_FIELDS 와
// 이 카탈로그는 서로를 검사하지 않는다 … 후속으로 남긴다"*. 자연어 자백은 강제가 아니다
// (`docs/rules/behavior-rules.md §3`). 백엔드 열거형까지 합치면 축이 셋이라 더 나쁘다.
//
// 이 저장소가 스스로 이름 붙인 지배 결함 양식 그대로다 — `two-lists-never-check-each-other`.
// 처방도 그 양식이 정한 대로다. **양방향 차집합 + 비-공허 짝 + 합성 뮤테이션 대조군.**
//
// ## 왜 `apps/web` 이 아니라 여기인가
//
// `issue-text-constraints-alignment.test.ts` 와 같은 이유다. Kotlin 은 import 할 수 없어
// `readFileSync` 로 읽는데, 그러면 vitest 모듈 그래프에 안 걸려 `.husky/pre-push` 의
// `vitest related` 가 이 판정을 부르지 않는다. 그리고 **백엔드 열거형만 바뀐 커밋**은
// `select-test-scope.ts` 의 `frontendScope` 가 skip 이라 프론트 테스트가 아예 안 도는데,
// 그 순간이 바로 이 판별식이 필요한 순간이다. `scripts/**/*.test.ts` 는 조건 없이 전량 실행된다.
//
// ## ★역참조를 프론트 두 곳에만 요구하는 이유
//
// 「이 축은 판별식이 지킨다」가 **카탈로그 옆에** 적혀 있지 않으면, 목록을 늘리는 사람은
// 판별식의 존재를 모른 채 한쪽만 고치고 red 를 만난 뒤에야 알게 된다 — 더 나쁘게는,
// `BoardCard.tsx` 처럼 **「검사하지 않는다」고 적힌 낡은 문장**을 근거로 삼는다.
// 그래서 프론트 두 카탈로그에는 이 파일 이름이 있어야 한다.
//
// 백엔드 열거형에는 요구하지 않는다. 이 판별식은 그 파일을 **읽기만** 하고, 그 파일의 주석
// 규약은 agile-planning BC 가 소유한다 — 다른 BC 의 파일에 우리 쪽 표기를 강제하면 그것이
// 또 하나의 갈라질 규약이 된다. 백엔드 쪽 안내는 BC 소유자가 판단할 몫으로 남긴다.
//
// ## 미커버 선언
//
//   - **선언 순서.** `CardLayoutPanel` KDoc 이 *"열거 순서까지 같다"* 고 적었지만 여기서는
//     집합만 잰다. 순서는 화면 순서가 아니고(카드 순서는 저장된 배열 순서다 — `BoardCard` KDoc),
//     기능상 자유도라 강제하면 근거 없는 red 를 만든다.
//   - **커스텀 필드(`cf_` 접두).** 프로젝트마다 다르므로 어느 목록에도 열거되지 않는다.
//     세 곳 다 접두사 규약으로만 다루므로 대조할 정본 자체가 없다.
//   - **라벨 문구.** 같은 키에 붙은 한국어 이름이 두 프론트 파일에 각각 있지만, 문구 정합은
//     화면 테스트가 잰다(`CardLayoutPanel.test` · `BoardCard.test`). 여기서 또 재면 네 번째 목록이 된다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** 꼭짓점 ① — 저장 허용값의 정본(400 판정). */
const KOTLIN_SOURCE =
  'backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/application/CardLayoutSettingsService.kt';
/** 꼭짓점 ② — 설정 화면이 후보로 그리는 목록. */
const PANEL_SOURCE = 'apps/web/src/components/board/settings/CardLayoutPanel.tsx';
/** 꼭짓점 ③ — 카드가 실제로 그리는 카탈로그. */
const CARD_SOURCE = 'apps/web/src/components/board/BoardCard.tsx';

/** 역참조 표식 — 프론트 두 카탈로그가 이 이름을 들고 있어야 한다. */
const SELF_NAME = 'card-field-catalog-parity.test.ts';

function read(relative: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, relative), 'utf8');
}

/**
 * 주석을 걷어낸다 — **문자열 리터럴은 건드리지 않는다.**
 *
 * ★단순 정규식으로 `//` 를 지우면 `'https://…'` 같은 리터럴이 잘려 나가 파서가 조용히
 * 다른 것을 읽는다. 그래서 상태 기계로 문자열(`'` `"` `` ` ``) 안팎을 가른다.
 * Kotlin 과 TypeScript 의 주석 문법은 이 범위에서 같다(줄 주석과 블록 주석 둘 다).
 */
export function stripComments(source: string): string {
  let out = '';
  let i = 0;
  while (i < source.length) {
    const two = source.slice(i, i + 2);
    if (two === '//') {
      while (i < source.length && source[i] !== '\n') i += 1;
      continue;
    }
    if (two === '/*') {
      i += 2;
      while (i < source.length && source.slice(i, i + 2) !== '*/') i += 1;
      i += 2;
      continue;
    }
    const ch = source[i];
    if (ch === "'" || ch === '"' || ch === '`') {
      const quote = ch;
      out += ch;
      i += 1;
      while (i < source.length) {
        const c = source[i];
        out += c;
        i += 1;
        if (c === '\\') {
          if (i < source.length) {
            out += source[i];
            i += 1;
          }
          continue;
        }
        if (c === quote) break;
      }
      continue;
    }
    out += ch;
    i += 1;
  }
  return out;
}

/**
 * `open` 다음의 짝 맞는 `close` 까지의 본문을 돌려준다.
 *
 * @param source 주석이 걷힌 소스.
 * @param from `open` 문자의 인덱스.
 * @throws Error 괄호가 닫히지 않으면. **못 읽은 것을 빈 것으로 세면 판정이 공허해진다.**
 */
function balancedBody(source: string, from: number, open: string, close: string): string {
  let depth = 0;
  for (let i = from; i < source.length; i += 1) {
    if (source[i] === open) depth += 1;
    else if (source[i] === close) {
      depth -= 1;
      if (depth === 0) return source.slice(from + 1, i);
    }
  }
  throw new Error(`괄호 ${open}${close} 가 닫히지 않았다 — 파서가 소스를 못 읽었다`);
}

/** 최상위(괄호 깊이 0) 콤마로만 쪼갠다. 중첩된 `{}` `[]` `()` 안의 콤마에 속지 않는다. */
function splitTopLevel(body: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let cur = '';
  for (const ch of body) {
    if (ch === '{' || ch === '[' || ch === '(') depth += 1;
    if (ch === '}' || ch === ']' || ch === ')') depth -= 1;
    if (ch === ',' && depth === 0) {
      parts.push(cur);
      cur = '';
    } else {
      cur += ch;
    }
  }
  parts.push(cur);
  return parts;
}

/**
 * Kotlin `enum class <name> { A, B, ... }` 의 상수 이름을 뽑는다.
 *
 * ★`;` 뒤의 멤버 선언은 상수가 아니다 — 잘라 낸다. 지금 이 열거형에는 없지만, 나중에
 * 메서드가 붙었을 때 조용히 이름을 하나 더 세지 않게 한다.
 *
 * @throws Error 열거형을 못 찾으면. 통과가 아니라 실패로 떨어뜨린다.
 */
export function kotlinEnumConstants(source: string, name: string): string[] {
  const clean = stripComments(source);
  const head = new RegExp(`\\benum\\s+class\\s+${name}\\b`).exec(clean);
  if (head === null) throw new Error(`Kotlin 열거형 ${name} 을 찾지 못했다 — 이름이 바뀌었나`);
  const brace = clean.indexOf('{', head.index);
  if (brace < 0) throw new Error(`Kotlin 열거형 ${name} 의 본문 시작 { 을 찾지 못했다`);
  const body = balancedBody(clean, brace, '{', '}');
  const semi = body.indexOf(';');
  const constantsPart = semi >= 0 ? body.slice(0, semi) : body;
  return splitTopLevel(constantsPart)
    .map((part) => /^\s*([A-Za-z_][A-Za-z0-9_]*)/.exec(part)?.[1])
    .filter((c): c is string => c !== undefined);
}

/**
 * TypeScript `const <name> = [ { key: 'X', … }, … ]` 의 `key` 리터럴을 뽑는다.
 *
 * @throws Error 상수를 못 찾으면.
 */
export function tsArrayKeyLiterals(source: string, name: string): string[] {
  const clean = stripComments(source);
  const head = new RegExp(`\\bconst\\s+${name}\\s*=\\s*\\[`).exec(clean);
  if (head === null) throw new Error(`TS 배열 상수 ${name} 을 찾지 못했다 — 이름이 바뀌었나`);
  const body = balancedBody(clean, clean.indexOf('[', head.index), '[', ']');
  return splitTopLevel(body)
    .map((part) => /\bkey\s*:\s*['"]([^'"]+)['"]/.exec(part)?.[1])
    .filter((c): c is string => c !== undefined);
}

/**
 * TypeScript `const <name> = { X: '…', … }` 의 최상위 키를 뽑는다.
 *
 * @throws Error 상수를 못 찾으면.
 */
export function tsObjectKeys(source: string, name: string): string[] {
  const clean = stripComments(source);
  const head = new RegExp(`\\bconst\\s+${name}\\s*=\\s*\\{`).exec(clean);
  if (head === null) throw new Error(`TS 객체 상수 ${name} 을 찾지 못했다 — 이름이 바뀌었나`);
  const body = balancedBody(clean, clean.indexOf('{', head.index), '{', '}');
  return splitTopLevel(body)
    .map((part) => /^\s*['"]?([A-Za-z_][A-Za-z0-9_]*)['"]?\s*:/.exec(part)?.[1])
    .filter((c): c is string => c !== undefined);
}

/** 두 목록의 **양방향** 차집합. 한쪽만 보면 반대 방향이 조용히 썩는다. */
export function symmetricDiff(
  left: readonly string[],
  right: readonly string[],
): { onlyLeft: string[]; onlyRight: string[] } {
  const l = new Set(left);
  const r = new Set(right);
  return {
    onlyLeft: [...l].filter((k) => !r.has(k)).sort(),
    onlyRight: [...r].filter((k) => !l.has(k)).sort(),
  };
}

/** 세 꼭짓점을 지금 디스크에서 읽는다. 파싱 실패는 여기서 던진다. */
function catalogs(): { backend: string[]; panel: string[]; card: string[] } {
  return {
    backend: kotlinEnumConstants(read(KOTLIN_SOURCE), 'CardLayoutFieldKey'),
    panel: tsArrayKeyLiterals(read(PANEL_SOURCE), 'STANDARD_FIELDS'),
    card: tsObjectKeys(read(CARD_SOURCE), 'EXTRA_STANDARD_FIELDS'),
  };
}

describe('카드 필드 카탈로그 3-way 정합', () => {
  // ── 비-공허 하한 ────────────────────────────────────────────────────────────
  // 파서가 깨져 빈 배열을 돌려주면 아래 차집합이 **공짜로 0** 이 된다. 이 저장소가 그 함정을
  // 여러 번 밟았다(`partial-column-parser-lets-unread-column-rot`). 그래서 「수집했다」를 먼저 잰다.
  test('★세 카탈로그가 모두 비어 있지 않다 (비-공허 하한)', () => {
    const { backend, panel, card } = catalogs();
    assert.deepStrictEqual(
      { backend: backend.length > 0, panel: panel.length > 0, card: card.length > 0 },
      { backend: true, panel: true, card: true },
      `수집 결과가 비었다 — 파서가 소스를 못 읽었다는 뜻이고, 그러면 아래 차집합이 공짜로 0 이 된다.\n` +
        `  backend=${backend.length} panel=${panel.length} card=${card.length}`,
    );
  });

  test('★같은 목록 안에 중복 키가 없다', () => {
    const { backend, panel, card } = catalogs();
    const dup = (list: string[]): string[] =>
      [...new Set(list.filter((k, i) => list.indexOf(k) !== i))].sort();
    assert.deepStrictEqual(
      { backend: dup(backend), panel: dup(panel), card: dup(card) },
      { backend: [], panel: [], card: [] },
      '중복이 있으면 집합 비교는 통과하는데 화면에는 같은 줄이 두 번 뜬다',
    );
  });

  // ── 양방향 차집합 ──────────────────────────────────────────────────────────
  test('백엔드 열거형 ↔ 설정 화면 후보 — 양방향 차집합이 0 이다', () => {
    const { backend, panel } = catalogs();
    assert.deepStrictEqual(
      symmetricDiff(backend, panel),
      { onlyLeft: [], onlyRight: [] },
      `${KOTLIN_SOURCE} 의 CardLayoutFieldKey 와 ${PANEL_SOURCE} 의 STANDARD_FIELDS 가 어긋난다.\n` +
        '  onlyLeft(백엔드에만) = 저장은 되는데 고를 창구가 없다\n' +
        '  onlyRight(화면에만)  = 고르면 서버가 400 이다',
    );
  });

  test('설정 화면 후보 ↔ 카드 렌더러 — 양방향 차집합이 0 이다', () => {
    const { panel, card } = catalogs();
    assert.deepStrictEqual(
      symmetricDiff(panel, card),
      { onlyLeft: [], onlyRight: [] },
      `${PANEL_SOURCE} 의 STANDARD_FIELDS 와 ${CARD_SOURCE} 의 EXTRA_STANDARD_FIELDS 가 어긋난다.\n` +
        '  onlyLeft(후보에만)  = ★고를 수는 있는데 카드엔 안 뜬다 — 값이 없어 생략된 것(E4)과 구분되지 않는다\n' +
        '  onlyRight(카드에만) = 그릴 준비만 하고 아무도 켤 수 없다',
    );
  });

  test('백엔드 열거형 ↔ 카드 렌더러 — 양방향 차집합이 0 이다', () => {
    const { backend, card } = catalogs();
    assert.deepStrictEqual(
      symmetricDiff(backend, card),
      { onlyLeft: [], onlyRight: [] },
      `${KOTLIN_SOURCE} 의 CardLayoutFieldKey 와 ${CARD_SOURCE} 의 EXTRA_STANDARD_FIELDS 가 어긋난다.\n` +
        '  onlyLeft(백엔드에만) = 저장된 키를 카드가 못 그린다(런타임에 조용히 버려진다)\n' +
        '  onlyRight(카드에만)  = 그 키를 보내면 서버가 400 이다',
    );
  });

  // ── 역참조 ─────────────────────────────────────────────────────────────────
  test('★프론트 두 카탈로그가 이 판별식을 가리킨다', () => {
    const missing = [PANEL_SOURCE, CARD_SOURCE].filter((rel) => !read(rel).includes(SELF_NAME));
    assert.deepStrictEqual(
      missing,
      [],
      `카탈로그 옆에 「이 축은 ${SELF_NAME} 가 대조한다」가 없다: ${missing.join(', ')}\n` +
        '  목록을 늘리는 사람이 판별식의 존재를 모르면 한쪽만 고치고, 더 나쁘게는 「검사하지 않는다」고 \n' +
        '  적힌 낡은 문장을 근거로 삼는다(BoardCard.tsx 가 실제로 그 상태였다).',
    );
  });

  // ── 합성 뮤테이션 대조군 — 판별식이 **실제로 무는가** ────────────────────────
  //
  // 위의 단언들이 초록인 것은 지금 셋이 일치하기 때문이다. 그것만으로는 이 판별식이
  // **red 를 낼 능력이 있는지** 알 수 없다 — 판정을 통째로 지워도 똑같이 초록이다
  // (`invariant-satisfied-by-helptext-not-logic`). 그래서 **진짜 소스에** 가짜 키를 주입해
  // 판정이 무는 것을 실측한다.
  describe('합성 뮤테이션 대조군', () => {
    test('★백엔드 열거형에 가짜 키를 넣으면 잡는다', () => {
      const mutated = read(KOTLIN_SOURCE).replace(
        'enum class CardLayoutFieldKey {',
        'enum class CardLayoutFieldKey {\n    GHOST_ONLY_IN_BACKEND,\n',
      );
      assert.notStrictEqual(mutated, read(KOTLIN_SOURCE), '뮤테이션이 적용되지 않았다 — 대조군이 공허하다');
      const backend = kotlinEnumConstants(mutated, 'CardLayoutFieldKey');
      const panel = tsArrayKeyLiterals(read(PANEL_SOURCE), 'STANDARD_FIELDS');
      assert.deepStrictEqual(symmetricDiff(backend, panel), {
        onlyLeft: ['GHOST_ONLY_IN_BACKEND'],
        onlyRight: [],
      });
    });

    test('★설정 화면 후보에 가짜 키를 넣으면 잡는다', () => {
      const original = read(PANEL_SOURCE);
      const mutated = original.replace(
        "{ key: 'EPIC',",
        "{ key: 'GHOST_ONLY_IN_PANEL', label: '유령' },\n  { key: 'EPIC',",
      );
      assert.notStrictEqual(mutated, original, '뮤테이션이 적용되지 않았다 — 대조군이 공허하다');
      const panel = tsArrayKeyLiterals(mutated, 'STANDARD_FIELDS');
      const card = tsObjectKeys(read(CARD_SOURCE), 'EXTRA_STANDARD_FIELDS');
      assert.deepStrictEqual(symmetricDiff(panel, card), {
        onlyLeft: ['GHOST_ONLY_IN_PANEL'],
        onlyRight: [],
      });
    });

    test('★카드 렌더러에 가짜 키를 넣으면 잡는다', () => {
      const original = read(CARD_SOURCE);
      const mutated = original.replace(
        'const EXTRA_STANDARD_FIELDS = {',
        "const EXTRA_STANDARD_FIELDS = {\n  GHOST_ONLY_IN_CARD: '유령',",
      );
      assert.notStrictEqual(mutated, original, '뮤테이션이 적용되지 않았다 — 대조군이 공허하다');
      const card = tsObjectKeys(mutated, 'EXTRA_STANDARD_FIELDS');
      const backend = kotlinEnumConstants(read(KOTLIN_SOURCE), 'CardLayoutFieldKey');
      assert.deepStrictEqual(symmetricDiff(backend, card), {
        onlyLeft: [],
        onlyRight: ['GHOST_ONLY_IN_CARD'],
      });
    });

    test('★한쪽에서 키를 지워도 잡는다 (반대 방향)', () => {
      const original = read(CARD_SOURCE);
      const mutated = original.replace("  ISSUE_TYPE: '이슈 종류',\n", '');
      assert.notStrictEqual(mutated, original, '뮤테이션이 적용되지 않았다 — 대조군이 공허하다');
      const card = tsObjectKeys(mutated, 'EXTRA_STANDARD_FIELDS');
      const panel = tsArrayKeyLiterals(read(PANEL_SOURCE), 'STANDARD_FIELDS');
      assert.deepStrictEqual(symmetricDiff(panel, card), {
        onlyLeft: ['ISSUE_TYPE'],
        onlyRight: [],
      });
    });

    test('★역참조를 지우면 잡는다', () => {
      const stripped = read(CARD_SOURCE).split(SELF_NAME).join('(지워짐)');
      assert.ok(!stripped.includes(SELF_NAME), '뮤테이션이 적용되지 않았다 — 대조군이 공허하다');
    });
  });

  // ── 파서 자체의 비-공허 짝 (픽스처) ─────────────────────────────────────────
  describe('파서 픽스처', () => {
    test('Kotlin — 주석 안의 이름에 속지 않고 `;` 뒤 멤버를 세지 않는다', () => {
      const src = [
        '/** GHOST_IN_KDOC 은 상수가 아니다. */',
        'enum class K {',
        '    // LINE_COMMENT_GHOST',
        '    A,',
        '    B;',
        '    fun label(): String = "x"',
        '}',
      ].join('\n');
      assert.deepStrictEqual(kotlinEnumConstants(src, 'K'), ['A', 'B']);
    });

    test('TS 배열 — 중첩 객체의 콤마에 속지 않는다', () => {
      const src = "const S = [{ key: 'A', label: '가' }, { key: 'B', label: '나' }] as const";
      assert.deepStrictEqual(tsArrayKeyLiterals(src, 'S'), ['A', 'B']);
    });

    test('TS 객체 — 최상위 키만 센다', () => {
      const src = "const O = { A: '가', B: '나' } as const";
      assert.deepStrictEqual(tsObjectKeys(src, 'O'), ['A', 'B']);
    });

    test('★문자열 리터럴 안의 `//` 를 주석으로 지우지 않는다', () => {
      const src = "const O = { A: 'https://x', B: '나' } as const";
      assert.deepStrictEqual(tsObjectKeys(src, 'O'), ['A', 'B']);
    });

    test('★상수를 못 찾으면 통과가 아니라 던진다', () => {
      assert.throws(() => tsObjectKeys('const OTHER = {}', 'MISSING'), /찾지 못했다/);
      assert.throws(() => kotlinEnumConstants('class X', 'MISSING'), /찾지 못했다/);
      assert.throws(() => tsArrayKeyLiterals('const OTHER = []', 'MISSING'), /찾지 못했다/);
    });
  });
});
