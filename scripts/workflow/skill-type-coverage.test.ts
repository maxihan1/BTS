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

    // 'unknown' 은 detectType 이 실제로 반환하지 않는다(신호 0이면 'backend' 로 떨어진다).
    // 표에 행을 요구하는 대신 fallback 행이 받아내면 충분하다.
    const missing = types.filter((t) => t !== 'unknown' && !covered.has(t));

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
});
