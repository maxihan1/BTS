// TODOS.md 의 「비개발자가 읽을 수 있는가」 계약을 강제하는 판별식
//
// ## 왜 이 파일이 있나
//
// 대시보드 `📝 기술 부채` 페이지는 항목을 **상태별로만** 묶고, 펼치면 기술 본문이
// 그대로 나온다. 비개발자는 ① 어느 영역의 빚인지 ② 무슨 뜻인지 ③ 안 고치면 뭐가
// 생기는지를 알 수 없다. 그래서 항목마다 **「쉬운 말」·「방치하면」 두 줄**을 쓰기로 했는데,
// 규율을 문서에만 두면 다음 사람이 빠뜨리고 화면에는 **빈칸이 조용히** 뜬다.
//
// ## 이 판별식이 보는 것 (셋)
//
// ① **영역 접두 ↔ 마스터 §전수 매핑 영역 열** 차집합 0.
//    같은 값이 두 파일에 있고 아무도 대조하지 않았다 — 2026-08-18 실측에서 **4건**이
//    어긋나 있었다(항목 1·20·28·36). `[[two-lists-never-check-each-other]]` 그대로다.
// ② **두 줄 존재 + 내용 길이**. 존재만 보면 빈 문자열로 통과한다
//    (`[[invariant-satisfied-by-helptext-not-logic]]`).
// ③ **카테고리 매핑 ↔ 실제 영역 집합** 양방향 차집합 0. 새 영역이 조용히 「기타」로
//    떨어지는 대신 red 가 난다.
//
// ## ★파서를 새로 적지 않는다
//
// `TODOS.md` 구조 파서는 이미 두 벌이다 — `build-dashboard.mjs` 의 `parseTodos` 와
// `todos-resolved-section-purity.test.ts` 의 `parseSections`. 여기서 세 번째를 적으면
// 그 셋이 서로 갈라진다. **`parseTodos` 를 import 해서 쓴다.**

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// @ts-ignore — .mjs 는 타입 선언이 없다. 런타임 export 는 실재한다.
import { parseTodos, AREA_CATEGORIES, CONTRACTED_STATUSES } from '../build-dashboard.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const LEDGER = path.join(REPO_ROOT, 'TODOS.md');
const MASTER = path.join(REPO_ROOT, 'docs/plans/2026-08-12-debt24-master.md');

/** 두 줄의 마커. 서식을 바꾸려면 `TODOS.md` 머리의 등재 서식과 **함께** 고쳐야 한다. */
const PLAIN_MARKERS = ['**쉬운 말.**', '**방치하면.**'] as const;

/** 마커 뒤 내용의 최소 길이(공백 제외). 존재만 보면 빈 문자열이 통과한다. */
const MIN_CONTENT_LEN = 10;

interface Todo {
  status: string;
  title: string;
  body: string;
}

/** 계약 대상 항목만 추린다. */
function contractedTodos(): Todo[] {
  const todos = parseTodos(fs.readFileSync(LEDGER, 'utf8')) as Todo[];
  return todos.filter((t) => (CONTRACTED_STATUSES as readonly string[]).includes(t.status));
}

/**
 * 제목에서 영역 접두를 뽑는다 — `<영역> — <한 줄 증상>` 의 앞부분.
 *
 * `—`(em dash)가 없으면 `null` 을 낸다. 조용히 「기타」로 떨어지지 않게 호출부가 red 를 낸다.
 */
function areaOf(title: string): string | null {
  const idx = title.indexOf('—');
  if (idx < 0) return null;
  const area = title.slice(0, idx).trim();
  return area.length > 0 ? area : null;
}

/** 마스터 §전수 매핑에서 미해소 행의 `제목 → 영역` 을 뽑는다. */
function masterAreas(): Map<string, string> {
  const src = fs.readFileSync(MASTER, 'utf8');
  const map = new Map<string, string>();
  const row = /^\|\s*\*{0,2}(\d+)\*{0,2}\s*\|\s*⬜\s*\|\s*(.*?)\s*\|\s*\*{0,2}(?:#\d+|미배정|보류)\*{0,2}\s*\|\s*(.*?)\s*\|\s*$/;
  for (const line of src.split('\n')) {
    const m = line.match(row);
    if (m) map.set(m[2] as string, m[3] as string);
  }
  return map;
}

/** 항목 제목에서 끝 괄호 그룹 하나를 뗀다 — 마스터 항목 열과 맞추는 조인 키. */
function normalizeKey(title: string): string {
  return title.replace(/\s*\([^()]*\)\s*$/, '');
}

describe('TODOS.md — 비개발자 계약', () => {
  test('계약 대상 항목을 실제로 찾는다 (비-공허 짝)', () => {
    const todos = contractedTodos();
    // 하한이 없으면 파싱이 0건을 내도 아래 단언들이 전부 공허하게 통과한다.
    // ★`보류`(📌)는 현재 0건이라 그 축만으로는 비어 있다 — 미착수 축으로 비-공허를 증명한다.
    assert.ok(
      todos.length >= 10,
      `계약 대상이 ${todos.length}건뿐이다 — 파서가 고장났거나 상태 이름이 바뀌었다.`,
    );
    assert.ok(
      todos.every((t) => t.title.length > 0),
      '제목이 빈 항목이 있다 — 파서가 헤딩만 긁었다.',
    );
  });

  test('모든 계약 항목이 영역 접두를 갖는다', () => {
    const missing = contractedTodos()
      .filter((t) => areaOf(t.title) === null)
      .map((t) => t.title);

    assert.deepEqual(
      missing,
      [],
      `영역 접두(\`<영역> — \`)가 없는 항목:\n  ${missing.join('\n  ')}\n` +
        '접두가 없으면 대시보드에서 어느 카테고리에도 안 들어간다.',
    );
  });

  test('영역 접두가 마스터 §전수 매핑의 영역 열과 일치한다', () => {
    const master = masterAreas();
    const mismatched: string[] = [];

    for (const todo of contractedTodos()) {
      const key = normalizeKey(todo.title);
      const want = master.get(key);
      if (want === undefined) continue; // 마스터에 없는 항목은 장부 판별식 소관
      const got = areaOf(todo.title);
      if (got !== want) mismatched.push(`${key}\n      TODOS 「${got}」 ≠ 마스터 「${want}」`);
    }

    assert.deepEqual(
      mismatched,
      [],
      `영역이 두 파일에서 다른 항목:\n  ${mismatched.join('\n  ')}\n` +
        '같은 값이 두 곳에 있고 아무도 대조하지 않으면 갈라진다. 마스터 값을 정본으로 맞춰라.',
    );
  });

  test('모든 계약 항목이 「쉬운 말」·「방치하면」 두 줄을 갖는다', () => {
    const missing: string[] = [];

    for (const todo of contractedTodos()) {
      const absent = PLAIN_MARKERS.filter((m) => !todo.body.includes(m));
      if (absent.length > 0) missing.push(`${todo.title}\n      빠진 것: ${absent.join(' · ')}`);
    }

    assert.deepEqual(
      missing,
      [],
      `두 줄이 없는 항목:\n  ${missing.join('\n  ')}\n` +
        '서식은 `TODOS.md` 머리의 등재 서식을 볼 것. 비개발자가 읽는 유일한 줄이다.',
    );
  });

  test('카테고리 매핑이 실제 영역 집합과 양방향으로 같다', () => {
    // 한쪽만 늘면 새 영역이 조용히 「기타」로 떨어지거나(매핑 부족),
    // 아무도 안 쓰는 카테고리가 화면에 빈 묶음으로 남는다(매핑 과잉).
    const actual = new Set(
      contractedTodos()
        .map((t) => areaOf(t.title))
        .filter((a): a is string => a !== null),
    );
    const mapped = new Set(Object.keys(AREA_CATEGORIES as Record<string, unknown>));

    const unmapped = [...actual].filter((a) => !mapped.has(a)).sort();
    const unused = [...mapped].filter((a) => !actual.has(a)).sort();

    assert.deepEqual(
      { unmapped, unused },
      { unmapped: [], unused: [] },
      `매핑에 없는 영역: ${unmapped.join(', ') || '없음'}\n` +
        `쓰이지 않는 매핑: ${unused.join(', ') || '없음'}\n` +
        'AREA_CATEGORIES 는 화면 분류의 정본이다. 새 영역이 생기면 여기부터 red 가 난다.',
    );
  });

  test('모든 카테고리가 이름과 한 줄 설명을 갖는다', () => {
    const thin = Object.entries(AREA_CATEGORIES as Record<string, { name?: string; desc?: string }>)
      .filter(([, v]) => !v?.name?.trim() || (v?.desc ?? '').replace(/\s/g, '').length < MIN_CONTENT_LEN)
      .map(([k]) => k);

    assert.deepEqual(
      thin,
      [],
      `이름 또는 설명이 빈 카테고리: ${thin.join(', ')}\n` +
        '설명 줄이 없으면 비개발자는 그 묶음이 무엇인지 알 수 없다 — 분류만 있고 뜻이 없다.',
    );
  });

  test('`TODOS.md` 머리에 등재 서식이 적혀 있다', () => {
    // 규율을 판별식에만 두면 다음 사람은 **왜 red 인지** 모른 채 마커만 채운다.
    // 서식은 파일 머리 한 곳에만 둔다 — 사본을 만들면 그 둘이 갈라진다.
    // 머리 = **첫 항목 헤딩 앞**. 두 가지를 조심한다.
    //   ① `## ` 로 자르면 머리 안의 소제목(`## 등재 서식`)에서 잘려 서식을 못 본다.
    //   ② 서식 예시가 **코드펜스 안에** `## ⬜ <영역> — …` 를 보여 주므로, 펜스를 모르면
    //      그 예시를 첫 항목으로 읽어 머리가 통째로 사라진다(2026-08-18 실측 — 이 테스트가
    //      바로 그렇게 틀렸다. 같은 fence-맹목이 하루에 세 번 나왔다).
    const src = fs.readFileSync(LEDGER, 'utf8');
    const lines = src.split('\n');
    let inFence = false;
    let firstItem = -1;
    for (const [i, line] of lines.entries()) {
      if (line.startsWith('```')) { inFence = !inFence; continue; }
      if (!inFence && /^## [⬜📌✅] /.test(line)) { firstItem = i; break; }
    }
    const head = firstItem > 0 ? lines.slice(0, firstItem).join('\n') : src;
    const absent = PLAIN_MARKERS.filter((m) => !head.includes(m));

    assert.deepEqual(
      absent,
      [],
      `머리 설명에 없는 마커: ${absent.join(' · ')}\n` +
        '새로 등재하는 사람이 서식을 볼 곳이 여기뿐이다.',
    );
  });

  test('두 줄의 내용이 비어 있지 않다', () => {
    const thin: string[] = [];

    for (const todo of contractedTodos()) {
      for (const marker of PLAIN_MARKERS) {
        const at = todo.body.indexOf(marker);
        if (at < 0) continue; // 부재는 위 단언 소관
        const rest = todo.body.slice(at + marker.length).split('\n\n')[0] ?? '';
        if (rest.replace(/\s/g, '').length < MIN_CONTENT_LEN) {
          thin.push(`${todo.title} — ${marker} 내용이 너무 짧다`);
        }
      }
    }

    assert.deepEqual(
      thin,
      [],
      `내용이 비었거나 너무 짧은 줄:\n  ${thin.join('\n  ')}\n` +
        '마커만 두고 내용을 비우면 화면에 빈칸이 뜬다 — 존재 단언만으로는 그것이 통과한다.',
    );
  });
});
