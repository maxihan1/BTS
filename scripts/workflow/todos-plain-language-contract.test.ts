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
import {
  parseTodos,
  renderTodos,
  areaOfTitle,
  AREA_CATEGORIES,
  CATEGORIES,
  CONTRACTED_STATUSES,
  PLAIN_LINE_RE,
  UNCLASSIFIED,
} from '../build-dashboard.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const LEDGER = path.join(REPO_ROOT, 'TODOS.md');
const MASTER = path.join(REPO_ROOT, 'docs/plans/2026-08-12-debt24-master.md');

/**
 * 두 줄의 라벨. 마커 **서식**은 여기 적지 않는다 — `PLAIN_LINE_RE` 가 정본이고 이 배열은
 * 「어느 라벨이 있어야 하는가」만 센다. 서식을 두 번 적으면 렌더러와 갈라진다.
 */
const PLAIN_LABELS = ['쉬운 말', '방치하면'] as const;

/**
 * 마커 뒤 내용의 최소 길이(공백 제외).
 *
 * 실측(2026-08-18) — 현행 56줄의 길이 최소 24 · 중앙값 36 · 최대 64. 하한을 10 으로 두면
 * 실측 최소의 절반 이하라 「빈칸」만 막고 「무의미」는 못 막는다. 20 으로 올려도 현행 전량 통과다.
 */
const MIN_CONTENT_LEN = 20;

/**
 * 카테고리 설명의 최소 길이(공백 제외).
 *
 * 두 줄과 성격이 다르다 — 항목 설명이 아니라 **묶음 이름의 부연**이라 짧은 것이 정상이다.
 * 실측 — 현행 5종의 길이 13 · 17 · 24 · 26 · 28. 하한을 두 줄과 같은 20 으로 두면
 * 멀쩡한 설명 2종이 red 가 된다. 임계를 나눈 이유가 그것이다.
 */
const MIN_DESC_LEN = 10;

/** 본문에서 라벨별 내용을 **렌더러와 같은 규칙으로** 뽑는다. */
function plainLinesOf(body: string): Map<string, string> {
  const found = new Map<string, string>();
  let inFence = false;
  for (const line of body.split('\n')) {
    if (line.startsWith('```')) { inFence = !inFence; continue; }
    if (inFence) continue;
    const m = line.match(PLAIN_LINE_RE as RegExp);
    if (m) found.set(m[1] as string, (m[2] as string).trim());
  }
  return found;
}

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
      .filter((t) => areaOfTitle(t.title) === null)
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
      const got = areaOfTitle(todo.title);
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
      const found = plainLinesOf(todo.body);
      const absent = PLAIN_LABELS.filter((l) => !found.has(l));
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
        .map((t) => areaOfTitle(t.title))
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
      .filter(([, v]) => !v?.name?.trim() || (v?.desc ?? '').replace(/\s/g, '').length < MIN_DESC_LEN)
      .map(([k]) => k);

    assert.deepEqual(
      thin,
      [],
      `이름 또는 설명이 빈 카테고리: ${thin.join(', ')}\n` +
        '설명 줄이 없으면 비개발자는 그 묶음이 무엇인지 알 수 없다 — 분류만 있고 뜻이 없다.',
    );
  });

  test('영역 → 카테고리 배정이 기대와 정확히 일치한다', () => {
    // ★기대값을 **검사 대상 상수에서 읽으면 동어반복**이다. 매핑에서 파생한 첫 시도가 정확히
    //   그랬고, 값을 바꿔도 초록이었다(2026-08-18 뮤테이션 ⑦ 실측). 배정은 사람 판단이라
    //   기계 오라클이 없으므로, 이 저장소의 처방대로 **두 번째 목록**을 여기 두고 양방향 대조한다.
    //   표를 의도적으로 바꾸려면 이 목록도 같은 커밋에서 고쳐라.
    const EXPECTED: Record<string, string> = {
      'apps/web': CATEGORIES.screen.name,
      'issue-tracking': CATEGORIES.feature.name,
      'search-export-import': CATEGORIES.feature.name,
      '도구': CATEGORIES.guard.name,
      '워크플로우': CATEGORIES.guard.name,
      '인프라': CATEGORIES.infra.name,
      '문서': CATEGORIES.docs.name,
    };
    const actual = Object.fromEntries(
      Object.entries(AREA_CATEGORIES as Record<string, { name: string }>).map(([k, v]) => [k, v.name]),
    );
    assert.deepEqual(
      actual,
      EXPECTED,
      '영역 → 카테고리 배정이 기대와 다르다 — 「개발자 말 → 사람 말」 번역이 이 기능의 본체다.',
    );
  });

  test('실파일 렌더에서 영역이 **배정된 카테고리 이름 아래** 나온다', () => {
    // 위 단언이 「배정이 맞는가」를, 이것이 「화면에 그대로 반영되는가」를 본다.
    const html = renderTodos(parseTodos(fs.readFileSync(LEDGER, 'utf8'))) as string;
    const catOf = new Map<string, string>();
    for (const todo of contractedTodos()) {
      const area = areaOfTitle(todo.title);
      if (area) catOf.set(area, (AREA_CATEGORIES as Record<string, { name: string }>)[area]?.name);
    }

    const wrong: string[] = [];
    for (const [area, catName] of catOf) {
      if (!catName) { wrong.push(`${area} — 매핑 없음`); continue; }
      const catStart = html.indexOf(`>${catName} <`);
      if (catStart < 0) { wrong.push(`${area} → 「${catName}」 묶음이 화면에 없다`); continue; }
      const nextCat = html.indexOf('<h3 class="todo-cat">', catStart + 1);
      const block = nextCat > 0 ? html.slice(catStart, nextCat) : html.slice(catStart);
      if (!block.includes(`${area} —`)) wrong.push(`${area} 가 「${catName}」 묶음 안에 없다`);
    }

    assert.deepEqual(
      wrong,
      [],
      `영역이 배정된 카테고리 아래 없다:\n  ${wrong.join('\n  ')}\n` +
        'AREA_CATEGORIES 의 값을 바꿔도 초록이면 그 번역은 장식이다.',
    );
  });

  test('카테고리 표시 순서가 선언 순서와 같다', () => {
    // 생성기 주석이 「선언 순서가 곧 화면 표시 순서」라 못 박았는데 그것을 지키는 단언이 없었다.
    const html = renderTodos(parseTodos(fs.readFileSync(LEDGER, 'utf8'))) as string;
    const shown = [...html.matchAll(/<h3 class="todo-cat">([^<]+?) </g)].map((m) => (m[1] as string).trim());
    const declared = Object.values(CATEGORIES as Record<string, { name: string }>).map((c) => c.name);
    assert.deepEqual(
      shown,
      declared.filter((n) => shown.includes(n)),
      '카테고리가 선언 순서와 다르게 나온다 — 「사용자가 체감하는 것을 먼저」가 무근거가 된다.',
    );
  });

  test('실파일 렌더에 `분류 없음` 묶음이 없다', () => {
    // `UNCLASSIFIED` 는 소실 방지 폴백이고 실데이터에서는 **비어 있어야** 한다.
    // 그것을 강제하는 단언이 0건이라 생성기 주석의 「판별식이 강제한다」가 거짓이었다.
    const html = renderTodos(parseTodos(fs.readFileSync(LEDGER, 'utf8'))) as string;
    assert.ok(
      !html.includes((UNCLASSIFIED as { name: string }).name),
      '`분류 없음` 묶음이 떴다 — 영역 접두가 매핑에 없는 항목이 있다.',
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
    // 항목을 하나도 못 찾으면 head 가 파일 전체가 되어 **항목 본문의 마커만으로 항상 통과**한다.
    // 판정이 사라져도 초록인 상태이므로 실패로 둔다.
    assert.ok(firstItem > 0, '첫 항목 헤딩을 찾지 못했다 — 이 단언이 공허해진다.');
    const head = lines.slice(0, firstItem).join('\n');
    const absent = PLAIN_LABELS.filter((l) => !head.includes(`**${l}.**`));

    assert.deepEqual(
      absent,
      [],
      `머리 설명에 없는 라벨: ${absent.join(' · ')}\n` +
        '새로 등재하는 사람이 서식을 볼 곳이 여기뿐이다.',
    );
  });

  test('두 줄의 내용이 비어 있지 않다', () => {
    const thin: string[] = [];

    for (const todo of contractedTodos()) {
      for (const [label, content] of plainLinesOf(todo.body)) {
        if (content.replace(/\s/g, '').length < MIN_CONTENT_LEN) {
          thin.push(`${todo.title} — 「${label}」 내용이 ${MIN_CONTENT_LEN}자 미만이다`);
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
