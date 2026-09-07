// 가젯 설정 선택기 커버리지 판별식 — 스코프성 필드는 자유 입력으로 남으면 안 된다
//
// ## 왜 있나
//
// `GadgetConfigForm` 은 `configFields` 디스크립터로 폼을 **자동 생성**한다. 편리하지만
// `STRING`·`UUID` 는 전부 `<input type="text">` 로 떨어진다. 그래서 스프린트 번다운 가젯을
// 켜면 사용자가 **보드 UUID 를 손으로 타이핑**해야 했다 — 켜도 쓸 수 없는 가젯이다.
//
// 처방은 「키 → 선택기」 매핑(`PICKER_BY_KEY`)이다. 그런데 그 매핑은 프론트에 있고 필드
// 목록은 백엔드 Kotlin 에 있다. **둘은 서로를 검사하지 않는다** — 누가 `GadgetType.kt` 에
// 스코프성 필드를 추가해도 폼은 조용히 텍스트 입력으로 떨어지고, 그 상태는 「설정이 어렵다」로만
// 드러나 아무도 원인을 못 짚는다. 지금 고치는 그 결함의 재발이다.
//
// 이 저장소가 이름 붙인 지배 결함 양식 그대로다 — `two-lists-never-check-each-other`.
//
// ## 스코프성 필드를 어떻게 가리나 (리뷰 F-5)
//
// 목록을 손으로 적지 않는다. 적는 순간 그것이 **세 번째** 썩는 목록이 된다. 대신 파일에서 유도한다.
//
//   - `FieldType.UUID` 이거나
//   - 키가 `Key` 또는 `Id` 로 끝난다
//
// 넓게 잡은 것은 의도다. **미탐이 오탐보다 위험**하기 때문이다 — 스코프성인데 규칙에 안 걸리면
// 자유 입력으로 조용히 남고, 반대로 스코프성이 아닌데 걸리면 red 가 떠서 사람이 본다.
// 규칙을 좁게(예: `UUID` 만) 잡으면 `projectKey`(STRING)를 놓친다.
//
// ★예외를 두려면 `PICKER_EXEMPT` 에 **사유와 함께** 적어야 한다. 사유 없는 예외는 이 판별식이
//   거부한다 — 예외 목록이 사유 없이 자라면 그것이 판별식을 무력화하는 뒷문이 된다.
//
// ## 미커버 선언
//
//   - **선택기가 실제로 목록을 부르는가.** 이 판별식은 매핑의 **존재**만 본다. 동작은
//     `GadgetConfigForm.test.tsx` 가 잰다.
//   - **`itemSchema` 안의 필드.** `link_list` 의 `label`·`url` 뿐이고 둘 다 위 규칙에 안 걸린다.
//     최상위와 중첩을 구분하지 않고 전량을 훑는 것이 그래서 안전하다 — 구분 로직을 두면
//     그 로직 자체가 틀릴 자리가 된다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** 필드 목록의 정본. */
const KOTLIN_SOURCE =
  'backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/GadgetType.kt';
/** 「키 → 선택기」 매핑의 정본. */
const FORM_SOURCE = 'apps/web/src/components/dashboard/GadgetConfigForm.tsx';

/** 역참조 표식 — 매핑 옆에 이 이름이 있어야 한다. */
const SELF_NAME = 'gadget-config-picker-coverage.test.ts';

/**
 * 선택기가 없어도 되는 스코프성 필드 — **사유 필수**.
 *
 * 비어 있는 것이 정상이다. 항목을 넣으려면 왜 자유 입력이어도 되는지를 값으로 적어라.
 */
const PICKER_EXEMPT: Readonly<Record<string, string>> = {};

function read(relative: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, relative), 'utf8');
}

/** config 필드 하나 — 키와 타입. */
interface ConfigField {
  readonly key: string;
  readonly type: string;
}

/** 소스에서 `ConfigFieldDescriptor(` 가 나오는 **모든** 오프셋. `data class` 선언 자체도 포함. */
function descriptorOffsets(source: string): number[] {
  const offsets: number[] = [];
  for (const m of source.matchAll(/ConfigFieldDescriptor\(/g)) offsets.push(m.index);
  return offsets;
}

/**
 * `GadgetType.kt` 의 `ConfigFieldDescriptor(...)` 를 전부 읽는다.
 *
 * 두 표기를 모두 받는다 — positional(`ConfigFieldDescriptor("projectKey", FieldType.STRING, …)`)
 * 과 named(`ConfigFieldDescriptor(key = "field", type = FieldType.ENUM, …)`).
 * 한쪽만 읽으면 다른 표기로 쓴 필드가 통째로 빠지고, 빠진 필드는 차집합에 안 나타난다.
 *
 * ★★본문을 통째로 삼키는 정규식을 쓰지 않는다. 종전 구현은
 *   `/ConfigFieldDescriptor\(([\s\S]*?)\)(?=,|\s*\))/g` 였는데, 바깥 디스크립터의 매칭이
 *   **중첩된 안쪽 디스크립터를 삼켜** `LINK_LIST.itemSchema` 의 첫 항목(`label`)이 한 번도
 *   방출되지 않았다(실측 — 선언 22건 · 파서 21건). 그 상태에서 `itemSchema` 첫 자리에
 *   UUID·`*Id` 필드를 넣으면 폼은 자유 입력으로 떨어지는데 판별식은 초록이다 —
 *   이 판별식이 막으려던 바로 그 결함이 판별식 안에 있었다.
 *   지금은 **출현 위치를 전부 잡고 다음 출현 직전까지를 창으로** 삼는다(파리티 파서가
 *   enum 상수에 쓴 「다음 선언까지」와 같은 기법). 중첩이 있어도 각 디스크립터가 자기 창을 갖는다.
 *
 * @param source `GadgetType.kt` 원문.
 * @returns 선언 순서대로의 필드 목록(중복 포함 — 같은 키가 여러 가젯에 있다).
 * @throws Error 한 건도 못 읽으면. **못 읽은 것을 빈 것으로 세면 판정이 공허해진다.**
 */
export function parseConfigFields(source: string): ConfigField[] {
  const found: ConfigField[] = [];
  const offsets = descriptorOffsets(source);

  offsets.forEach((offset, index) => {
    const end = offsets[index + 1] ?? source.length;
    const body = source.slice(offset, end);

    const keyMatch = /(?:key\s*=\s*)?"([A-Za-z0-9_]+)"/.exec(body);
    const typeMatch = /FieldType\.([A-Z]+)/.exec(body);
    // `data class ConfigFieldDescriptor(` 선언 자체는 문자열 리터럴이 없어 여기서 걸러진다.
    if (keyMatch === null || typeMatch === null) return;

    found.push({ key: keyMatch[1] as string, type: typeMatch[1] as string });
  });

  if (found.length === 0) {
    throw new Error('ConfigFieldDescriptor 를 한 건도 못 읽었다 — 파서가 소스를 못 읽었다');
  }
  return found;
}

/**
 * 파서가 읽은 수와 **소스의 실제 출현 수**를 맞춰 보기 위한 값.
 *
 * 이 짝이 「하한」의 자리를 대신한다. 하한(`>= 12`)은 실측 21~22 대비 너무 낮아
 * 파서가 절반을 놓쳐도 통과시켰다 — 실제로 1건을 놓치고 있었는데 아무도 몰랐다.
 * 출현 수는 소스에서 세므로 가젯이 늘어도 저절로 따라온다. **숫자를 손으로 적지 않는다.**
 *
 * @param source `GadgetType.kt` 원문.
 * @returns `data class` 선언을 뺀 실제 사용 건수.
 */
export function countDescriptorUsages(source: string): number {
  // 선언은 `data class ConfigFieldDescriptor(` 한 곳뿐이다. 그것만 뺀다.
  const declarations = (source.match(/data class ConfigFieldDescriptor\(/g) ?? []).length;
  return descriptorOffsets(source).length - declarations;
}

/**
 * 스코프성 필드인가 — 「무엇을 보여줄지 고르는」 값인가.
 *
 * @param field 필드.
 * @returns 선택기가 필요하면 true.
 */
export function isScopeField(field: ConfigField): boolean {
  return field.type === 'UUID' || field.key.endsWith('Key') || field.key.endsWith('Id');
}

/**
 * `GadgetConfigForm.tsx` 의 `PICKER_BY_KEY` 가 덮는 키를 읽는다.
 *
 * @param source 폼 원문.
 * @returns 매핑에 적힌 키 집합.
 */
export function parsePickerKeys(source: string): Set<string> {
  const block = /const PICKER_BY_KEY[^=]*=\s*\{([\s\S]*?)\}\s*as const/.exec(source);
  if (block === null) {
    throw new Error('PICKER_BY_KEY 매핑을 못 찾았다 — 이름이 바뀌었거나 형태가 다르다');
  }
  const keys = new Set<string>();
  for (const m of (block[1] as string).matchAll(/^\s*([A-Za-z0-9_]+)\s*:/gm)) {
    keys.add(m[1] as string);
  }
  return keys;
}

describe('가젯 설정 선택기 커버리지', () => {
  test('★파서가 선언된 필드를 하나도 빠뜨리지 않는다 (공허 통과 방지)', () => {
    const source = read(KOTLIN_SOURCE);
    const fields = parseConfigFields(source);

    // ★하한이 아니라 **등식**이다. 소스가 선언한 만큼 정확히 읽어야 한다.
    //   종전에는 `>= 12` 였는데 실측이 21이라 파서가 9건(43%)을 놓쳐도 통과했고,
    //   실제로 중첩 `itemSchema` 의 첫 항목 1건을 놓치고 있었다.
    //   기대값을 손으로 적지 않고 소스에서 세므로 가젯이 늘어도 이 단언은 안 썩는다.
    assert.equal(
      fields.length,
      countDescriptorUsages(source),
      `선언 ${countDescriptorUsages(source)}건 중 ${fields.length}건만 읽었다 — ` +
        '파서가 일부를 삼켰다. 못 읽은 필드는 차집합에 안 나타나므로 미탐이 된다.',
    );

    // 두 표기가 모두 읽혔는지 — positional 과 named 각각의 대표를 짚는다.
    const keys = new Set(fields.map((f) => f.key));
    assert.ok(keys.has('projectKey'), 'positional 표기를 못 읽었다 (projectKey)');
    assert.ok(keys.has('field'), 'named 표기를 못 읽었다 (field)');

    // ★중첩(`itemSchema`) 안의 필드도 읽히는지 — 머리말이 「전량을 훑는다」고 주장하는 자리다.
    //   종전 파서는 바로 이 `label` 을 못 읽으면서 그 주장을 하고 있었다.
    assert.ok(keys.has('label'), '중첩 itemSchema 의 필드를 못 읽었다 (label)');
    assert.ok(keys.has('url'), '중첩 itemSchema 의 필드를 못 읽었다 (url)');
  });

  test('★스코프성 필드가 전부 선택기 매핑에 있다', () => {
    const fields = parseConfigFields(read(KOTLIN_SOURCE));
    const pickers = parsePickerKeys(read(FORM_SOURCE));

    const scopeKeys = [...new Set(fields.filter(isScopeField).map((f) => f.key))].sort();

    // 비-공허 짝 — 규칙이 깨져 0건이면 빈 차집합으로 조용히 통과한다.
    assert.ok(scopeKeys.length > 0, '스코프성 필드가 0건이다 — isScopeField 규칙이 깨졌다');
    assert.ok(pickers.size > 0, 'PICKER_BY_KEY 가 비었다 — 파서가 매핑을 못 읽었다');

    const uncovered = scopeKeys.filter((k) => !pickers.has(k) && PICKER_EXEMPT[k] === undefined);

    assert.deepEqual(
      uncovered,
      [],
      `선택기가 없는 스코프성 필드: ${uncovered.join(', ')}\n` +
        '이 필드들은 폼에서 자유 입력 텍스트로 떨어진다 — 사용자가 UUID 를 손으로 타이핑해야 한다.\n' +
        `PICKER_BY_KEY 에 선택기를 등록하거나, 자유 입력이어도 되는 사유를 PICKER_EXEMPT 에 적어라.`,
    );
  });

  test('예외 목록의 모든 항목에 사유가 있다', () => {
    // 사유 없는 예외가 쌓이면 그것이 판별식을 무력화하는 뒷문이 된다.
    const noReason = Object.entries(PICKER_EXEMPT)
      .filter(([, reason]) => reason.trim().length === 0)
      .map(([key]) => key);

    assert.deepEqual(noReason, [], `사유 없는 예외: ${noReason.join(', ')}`);
  });

  test('폼이 이 판별식을 역참조한다', () => {
    // 매핑을 늘리는 사람이 판별식의 존재를 모르면, 한쪽만 고치고 red 를 만난 뒤에야 알게 된다.
    assert.ok(
      read(FORM_SOURCE).includes(SELF_NAME),
      `${FORM_SOURCE} 가 ${SELF_NAME} 를 언급하지 않는다`,
    );
  });
});
