// classify-task 가 낼 수 있는 TaskType 집합과 스킬 분기 표의 행 집합이 어긋나지 않는지 강제한다
//
// 왜 이 테스트가 있나. `bts-review-plan/SKILL.md` Step 2 표에 `backend` 행이 없어
// **분기가 미정의**인 상태로 지냈다(TODOS §bts-review-plan 분기 표 항목).
// 하드코딩 목록 두 개(타입 유니온 ↔ 스킬 표)의 정합을 아무도 보고 있지 않았기 때문이다.
// 개별 타입을 하나 추가하는 것으로 끝내면 재발한다 — 판별식이 필요하다.
//
// 판별식. `types.ts` 의 TaskType 유니온을 파싱하고, 스킬 문서의 분기 표에서 언급된 타입을
// 추출해 **차집합이 0** 인지 단언한다. 새 타입을 추가하면 이 테스트가 먼저 깨진다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const TYPES_FILE = path.join(REPO_ROOT, 'scripts/workflow/types.ts');
const REVIEW_PLAN_SKILL = path.join(REPO_ROOT, '.claude/skills/bts-review-plan/SKILL.md');

/**
 * `types.ts` 의 `export type TaskType = | 'a' | 'b' ...` 에서 값을 뽑는다.
 *
 * 소스를 파싱하는 이유 — 타입을 import 해도 **런타임에는 값이 없다**(TS 타입은 지워진다).
 * 별도 배열 상수를 두면 그 배열과 유니온이 또 어긋날 수 있어 원본을 읽는다.
 */
function parseTaskTypes(): string[] {
  const src = fs.readFileSync(TYPES_FILE, 'utf8');
  const m = src.match(/export type TaskType =([\s\S]*?);/);
  assert.ok(m, 'types.ts 에서 TaskType 유니온을 찾지 못했다 — 선언 서식이 바뀌었다.');
  return [...(m[1] as string).matchAll(/'([a-z-]+)'/g)].map((x) => x[1] as string);
}

/** 스킬 문서의 분기 표에서 언급된 타입을 뽑는다 (`TYPE == "x"` / `TYPE ∈ {a, b}` 두 서식) */
function parseSkillTableTypes(file: string): Set<string> {
  const src = fs.readFileSync(file, 'utf8');
  const found = new Set<string>();
  for (const m of src.matchAll(/TYPE\s*==\s*"([a-z-]+)"/g)) found.add(m[1] as string);
  for (const m of src.matchAll(/TYPE\s*∈\s*\{([^}]+)\}/g)) {
    for (const t of (m[1] as string).split(',')) found.add(t.trim());
  }
  return found;
}

/** 표에 없는 타입을 받아내는 fallback 행이 있는가 */
function hasFallbackRow(file: string): boolean {
  return /그 외 \(표에 없는 타입\)/.test(fs.readFileSync(file, 'utf8'));
}

/** 분기 표가 사는 절의 제목. 스캔 구역을 여기로 한정한다 */
const STEP2_HEADING = '## Step 2. 타입별 리뷰 렌즈 분기';

/** 진입 티어를 선언하는 문장. 표에서 티어를 걷어낸 뒤 이 한 줄이 그 사실의 유일한 자리다 */
const ENTRY_TIER_DECLARATION = '**T2/T3 만 진입한다**';

/**
 * fallback 행을 가리키는 가짜 타입.
 *
 * 이 행에는 `TYPE ==` 토큰이 없어 행 파서가 그냥 지나쳤고, 그래서 렌즈·주장 단언의 **사각**이었다
 * (「`—(이 표 미진입) · skip`」 으로 바꿔도 전부 초록임이 실측됐다). 실도달 경로는 없지만
 * 방어심도를 남기려고 센티넬로 편입한다.
 */
const FALLBACK_SENTINEL = '__fallback__';

/**
 * 행별 기대 렌즈 집합 — 이 표와 `SKILL.md` 분기 표가 **서로를 검사**하는 두 목록이다.
 *
 * 왜 「최소 1종」으로 부족한가. 개수 하한만 재면 `ui` 를 2종에서 1종으로 줄이거나
 * `auth` 에서 `/plan-ceo-review` 를 빼도, 심지어 `/plan-engg-review` 로 오타를 내도 초록이다
 * (셋 다 실측 GREEN 이었다 · PR #388 리뷰). 집합을 양방향으로 대조해야 그 셋이 함께 닫힌다.
 *
 * 조건부 렌즈(`backend` 의 design · `ui` 의 eng)도 **표에 글자로 있으므로** 여기 포함한다.
 */
const EXPECTED_LENSES: Readonly<Record<string, readonly string[]>> = {
  auth: ['/plan-eng-review', '/plan-ceo-review'],
  migration: ['/plan-eng-review', '/plan-ceo-review'],
  ui: ['/plan-design-review', '/plan-eng-review'],
  api: ['/plan-eng-review'],
  design: ['/plan-design-review'],
  backend: ['/plan-eng-review', '/plan-design-review'],
  feature: ['/plan-eng-review'],
  bugfix: ['/plan-eng-review'],
  chore: ['/plan-eng-review'],
  qa: ['/plan-eng-review'],
  [FALLBACK_SENTINEL]: ['/plan-eng-review'],
};

/**
 * Step 2 절만 잘라 낸다 — 코드펜스와 다른 절의 표를 스캔에서 뺀다.
 *
 * 파일 전체를 훑으면 「이렇게 쓰지 마라」 반례를 이 문서에 적는 것만으로 red 가 나고,
 * 다른 절이 정당하게 티어를 적어도 red 가 난다(둘 다 실측 · PR #388 리뷰).
 */
function readStep2Section(file: string): string {
  const src = fs.readFileSync(file, 'utf8');
  const start = src.indexOf(STEP2_HEADING);
  assert.ok(start >= 0, `Step 2 절을 찾지 못했다 — 제목이 바뀌었다: ${STEP2_HEADING}`);
  const rest = src.slice(start + STEP2_HEADING.length);
  const end = rest.search(/\n## /);
  const section = end >= 0 ? rest.slice(0, end) : rest;
  return section.replace(/```[\s\S]*?```/g, '');
}

/** 분기 표 한 행 — 그 행이 맡는 타입들과, 그 행이 실제로 지시하는 리뷰 렌즈들 */
interface SkillTableRow {
  types: string[];
  lenses: string[];
  /** 행 원문 — 렌즈 밖의 서술(티어 조건 등)을 보는 단언이 쓴다 */
  line: string;
}

/**
 * 분기 표를 **행 단위**로 쪼갠다 (위 `parseSkillTableTypes` 는 문서 전체에서 타입 토큰만 긁는다).
 *
 * 행 단위가 따로 필요한 이유 — 「어떤 타입이 표에 **언급**되는가」와 「그 타입이 **어느 렌즈로
 * 가는가」는 다른 질문이다. 종전 판별식은 앞의 것만 봐서, 행이 `skip`·「이 표 미진입」 처럼
 * **렌즈를 하나도 지시하지 않는 값**으로 있어도 초록이었다(`[[partial-column-parser-lets-unread-column-rot]]`).
 */
function parseSkillTableRows(file: string): SkillTableRow[] {
  const rows: SkillTableRow[] = [];
  for (const line of readStep2Section(file).split('\n')) {
    if (!line.trimStart().startsWith('|')) continue;
    const types: string[] = [];
    for (const m of line.matchAll(/TYPE\s*==\s*"([a-z-]+)"/g)) types.push(m[1] as string);
    for (const m of line.matchAll(/TYPE\s*∈\s*\{([^}]+)\}/g)) {
      for (const t of (m[1] as string).split(',')) types.push(t.trim());
    }
    if (types.length === 0 && /그 외 \(표에 없는 타입\)/.test(line)) types.push(FALLBACK_SENTINEL);
    if (types.length === 0) continue;
    const lenses = [...line.matchAll(/\/plan-[a-z]+-review/g)].map((m) => m[0]);
    rows.push({ types, lenses, line });
  }
  return rows;
}

describe('스킬 분기 표 ↔ TaskType 정합', () => {
  test('TaskType 유니온을 파싱한다 (판별식 비-공허 확인)', () => {
    const types = parseTaskTypes();
    // 하한을 두지 않으면 파싱이 0건을 내도 아래 차집합이 공허하게 통과한다.
    assert.ok(types.length >= 8, `TaskType 이 ${types.length}종뿐이다 — 파싱이 고장났을 수 있다.`);
    assert.ok(types.includes('backend'), 'backend 가 TaskType 에 없다 — 이 테스트의 전제가 바뀌었다.');
  });

  test('bts-review-plan 분기 표가 모든 TaskType 을 다룬다', () => {
    const types = parseTaskTypes();
    const covered = parseSkillTableTypes(REVIEW_PLAN_SKILL);

    const missing = types.filter((t) => !covered.has(t));

    assert.deepEqual(
      missing,
      [],
      `bts-review-plan 분기 표에 행이 없는 타입: ${missing.join(', ')}\n` +
        `표에 행을 추가하거나, 의도적으로 fallback 에 맡길 것이면 여기 예외로 명시하라.`,
    );
  });

  test('표에 없는 타입을 받아내는 fallback 행이 있다', () => {
    assert.ok(
      hasFallbackRow(REVIEW_PLAN_SKILL),
      'fallback 행이 없다. 새 타입이 추가되면 분기가 미정의로 조용히 떨어진다 — ' +
        '이 표가 `backend` 를 3개월간 놓친 것과 같은 사고가 재발한다.',
    );
  });

  test('행 파서가 모든 TaskType 을 행에 물린다 (판별식 비-공허 확인)', () => {
    const types = parseTaskTypes();
    const rows = parseSkillTableRows(REVIEW_PLAN_SKILL);

    // 행 파싱이 0건을 내면 아래 「렌즈 필수」 단언이 공허하게 통과한다. 행 집합이 타입 전량을
    // 덮는지를 여기서 먼저 못 박아, 표 서식이 바뀌어 파서가 눈이 멀면 이 테스트가 먼저 죽게 한다.
    const rowTypes = new Set(rows.flatMap((r) => r.types));
    const unrowed = types.filter((t) => !rowTypes.has(t));

    assert.deepEqual(
      unrowed,
      [],
      `행 파서가 못 물린 타입: ${unrowed.join(', ')} — 표 서식이 바뀌었거나 행이 사라졌다.`,
    );
  });

  test('기대 렌즈 표가 TaskType 전량을 덮는다 (두 목록 차집합 0)', () => {
    const expected = Object.keys(EXPECTED_LENSES).filter((k) => k !== FALLBACK_SENTINEL).sort();
    assert.deepEqual(
      expected,
      [...parseTaskTypes()].sort(),
      'EXPECTED_LENSES 의 키 집합이 TaskType 유니온과 다르다 — 타입을 추가하면 기대 렌즈도 ' +
        '함께 정해야 한다. 한쪽만 늘면 그 타입의 렌즈를 아무도 검사하지 않는다.',
    );
  });

  test('모든 행의 리뷰 렌즈가 기대 집합과 정확히 일치한다', () => {
    const rows = parseSkillTableRows(REVIEW_PLAN_SKILL);
    const mismatched: string[] = [];

    for (const row of rows) {
      for (const type of row.types) {
        const want = [...(EXPECTED_LENSES[type] ?? [])].sort();
        const got = [...row.lenses].sort();
        if (JSON.stringify(want) !== JSON.stringify(got)) {
          mismatched.push(`${type}: 기대 [${want.join(', ')}] · 실제 [${got.join(', ')}]`);
        }
      }
    }

    assert.deepEqual(
      mismatched,
      [],
      `렌즈가 기대와 다른 행:\n  ${mismatched.join('\n  ')}\n` +
        '개수 하한만 재면 렌즈를 줄이거나 이름을 오타 내도 초록이다 — 집합을 대조해야 잡힌다. ' +
        '표를 의도적으로 바꿨다면 EXPECTED_LENSES 를 같은 커밋에서 함께 고쳐라.',
    );
  });

  test('타입 행에 `T0`~`T3`·「미진입」 문자열이 없다', () => {
    // 이름을 좁게 적은 이유 — 이 단언이 막는 것은 **두 철자**이지 「도달 불가라는 의미」가
    // 아니다. 「이 단계에 오지 않으므로 실제로는 발행하지 않는다」 같은 우회 문구는 기계가
    // 못 잡는다(실측 GREEN · PR #388 리뷰). 이름이 구현보다 넓게 약속하면 그 자체가
    // `[[invariant-satisfied-by-helptext-not-logic]]` 이므로, 약속을 구현에 맞춰 좁혔다.
    // 의미까지 막는 몫은 위 「렌즈 집합 정확 일치」가 진다 — 렌즈가 줄면 거기서 걸린다.
    const rows = parseSkillTableRows(REVIEW_PLAN_SKILL);
    const claiming = rows.filter((r) => /\bT[0-3]\b|미진입/i.test(r.line));

    assert.deepEqual(
      claiming.flatMap((r) => r.types),
      [],
      `행에 티어·도달 가능성 주장이 달린 타입: ${claiming.flatMap((r) => r.types).join(', ')}\n` +
        '이 표는 티어 무조건이다. 진입 티어는 표가 아니라 스킬 본문이 한 번 선언하고, ' +
        '행이 티어를 다시 적거나 「미진입」이라 적으면 검사되지 않는 주장이 표 안에 생긴다 — ' +
        '그것이 항목 34 의 결함이다. type(제목)과 tier(변경 경로)는 독립 축이라 도달 불가 조합이 ' +
        '없으므로, 티어별로 렌즈를 가르려면 이 판별식을 먼저 의도적으로 고쳐라.',
    );
  });

  test('진입 티어 선언 문장이 스킬 본문에 있다', () => {
    // 표에서 티어를 걷어낸 대가로, 진입 티어라는 사실이 이 한 줄에만 남았다. 그 줄을 지워도
    // 위 단언들은 전부 초록이므로(실측 · PR #388 리뷰) 표를 지키는 만큼 이 줄도 지켜야 한다.
    assert.ok(
      fs.readFileSync(REVIEW_PLAN_SKILL, 'utf8').includes(ENTRY_TIER_DECLARATION),
      `진입 티어 선언이 사라졌다: ${ENTRY_TIER_DECLARATION}\n` +
        '분기 표가 티어를 적지 않는 전제가 이 문장이다. 지우면 어느 티어가 이 단계를 도는지를 ' +
        '문서 어디에서도 알 수 없게 된다.',
    );
  });
});
