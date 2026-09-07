// 가젯 카탈로그 ↔ 렌더러 정합 판별식 — 「켤 수 있는 것」과 「그릴 수 있는 것」이 같아야 한다
//
// ## 왜 있나
//
// 가젯이 화면에 뜨려면 **두 곳이 동시에** 알아야 한다.
//
//   ① `GadgetType.enabled`        — 카탈로그 노출 + 쓰기 수용. 무엇을 **켤 수 있나**
//   ② `GadgetRenderer` 의 `case`  — 실제 렌더. 무엇을 **그릴 수 있나**
//
// 둘은 서로를 검사하지 않는다. `enabled=true` 인데 `case` 가 없으면 사용자는
// **「지원되지 않는 가젯입니다」**를 본다 — 카탈로그에서 고를 수 있었는데 놓으면 안 뜬다.
// 반대로 `case` 만 있고 `enabled=false` 면 그 코드는 도달 불가라 조용히 썩는다.
//
// 이 저장소가 스스로 이름 붙인 지배 결함 양식 그대로다 — `two-lists-never-check-each-other`.
// 처방도 그 양식이 정한 대로다. **양방향 차집합 + 비-공허 짝.**
//
// ## 왜 `apps/web` 이 아니라 여기인가
//
// `card-field-catalog-parity.test.ts` 와 같은 이유다. Kotlin 은 import 할 수 없어
// `readFileSync` 로 읽는데, 그러면 vitest 모듈 그래프에 안 걸려 `.husky/pre-push` 의
// `vitest related` 가 이 판정을 부르지 않는다. 그리고 **`GadgetType.kt` 만 바뀐 커밋**은
// `select-test-scope.ts` 의 `frontendScope` 가 skip 이라 프론트 테스트가 아예 안 도는데,
// 그 순간이 바로 이 판별식이 필요한 순간이다. `scripts/**/*.test.ts` 는 조건 없이 전량 실행된다.
//
// ## ★파서가 자기가 세려는 것과 다른 것을 셀 수 있다 (리뷰 F-1)
//
// `GadgetType.kt` 에서 `key = "` 로 시작하는 줄은 **15줄**인데 enum 상수는 **12개**뿐이다.
// 나머지 3줄은 **필드 디스크립터**다.
//
//   ```kotlin
//   PIE_CHART(
//       key = "pie_chart",          // ← 들여쓰기 8칸. enum 상수의 키다
//       ...
//       configFields = listOf(
//           ConfigFieldDescriptor(
//               key = "field",      // ← 들여쓰기 20칸. 이건 필드 이름이지 가젯 타입이 아니다
//   ```
//
// 블록 안의 모든 `key =` 를 잡으면 `field`·`links` 가 가젯 타입 집합에 섞여 **영구 red** 가
// 되거나, 더 나쁘게 `PIE_CHART` 블록의 키를 `field` 로 읽어 `pie_chart` 자체를 놓친다.
// 그래서 두 조건을 **함께** 건다 — ①들여쓰기가 정확히 8칸 ②블록 안 **첫 번째** 것.
// 선언에서 `key` 가 항상 첫 인자라 ②는 ①이 깨져도 버틴다.
//
// ## ★공허 통과 방지 — 개수를 등식으로 잰다
//
// 정규식이 깨져 아무것도 못 읽으면 **두 빈 집합이 같아져 조용히 통과**한다. 그래서 파싱
// 결과의 개수를 함께 단언한다. 하한(`≥ N`)이 아니라 **등식 `== 12`** 다 — 하한은 파서가
// 3줄을 더 먹어도(15개) 통과시키지만 등식은 즉시 걸린다. 12 는 스펙 A3 이 이미 카탈로그
// 총수로 단언하는 수와 같다.
//
// ## 미커버 선언
//
//   - **`config` 스키마 정합.** 가젯이 요구하는 config 필드와 폼이 그리는 입력의 대조는
//     `gadget-config-picker-coverage.test.ts` 가 잰다. 여기서 또 재면 세 번째 목록이 된다.
//   - **공개(익명) 화이트리스트.** `PublicGadgetRenderer` 는 의도적으로 **더 좁다**(정적 2종만).
//     같아야 하는 축이 아니므로 이 판별식의 대상이 아니다 — 백엔드 `AnonymousLayoutSanitizer`
//     와의 정합은 그쪽 단위 테스트가 잰다.
//   - **`case` 의 렌더 결과.** 가젯이 실제로 무엇을 그리는지는 각 가젯 컴포넌트 테스트가 잰다.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/**
 * `case` 줄이 주석 안인지 가리기 위한 최소 주석 제거 — **TSX 에만 쓴다.**
 *
 * ★왜 기존 `stripComments` 를 안 쓰나. 저장소에 이미 **9벌**이 있는데 어느 것도 쓸 수 없다.
 *   - `card-field-catalog-parity.test.ts` · `toolbar-shortcut-coverage.test.ts` 등은 **테스트
 *     파일**이라 import 하면 그쪽 테스트가 내 프로세스에서 함께 돈다(실측 — 19건 중 13건이 남의 것).
 *   - `git-spawn-sweep.ts` 는 모듈이지만 최상위에서 `package.json` 의 러너 글로브를 읽어
 *     초기화한다. import 하면 그 모듈이 깨질 때 이 판별식도 같이 죽는다.
 *
 * 그래서 **이 판별식이 실제로 필요한 것만** 둔다 — 줄 주석과 블록 주석의 경계뿐이다.
 * 문자열·정규식 리터럴은 따라가지 않는다. `case '<key>':` 를 찾는 맥락에서 그 안에
 * 주석 여는 기호가 나올 자리가 없기 때문이다.
 *
 * ★부채. `stripComments` 9벌은 이 PR 이 만든 것이 아니고 고치는 것도 이 PR 범위가 아니다.
 *   공용 모듈로 모으는 것은 별건으로 등재할 값어치가 있다.
 *
 * @param source TSX 원문.
 * @returns 주석이 공백으로 치환된 소스. **줄 수를 보존한다** — 줄 단위로 다시 훑기 때문이다.
 */
export function stripCommentsForCaseScan(source: string): string {
  const out: string[] = [];
  let inBlock = false;
  for (const line of source.split('\n')) {
    let kept = '';
    let i = 0;
    while (i < line.length) {
      if (inBlock) {
        if (line.slice(i, i + 2) === '*/') {
          inBlock = false;
          i += 2;
          continue;
        }
        i += 1;
        continue;
      }
      if (line.slice(i, i + 2) === '/*') {
        inBlock = true;
        i += 2;
        continue;
      }
      if (line.slice(i, i + 2) === '//') break;
      kept += line[i];
      i += 1;
    }
    out.push(kept);
  }
  return out.join('\n');
}

/** 꼭짓점 ① — 켤 수 있는 것의 정본. */
const KOTLIN_SOURCE =
  'backend/modules/notification/src/main/kotlin/com/bts/notification/dashboard/domain/GadgetType.kt';
/** 꼭짓점 ② — 그릴 수 있는 것의 정본. */
const RENDERER_SOURCE = 'apps/web/src/components/dashboard/gadgets/GadgetRenderer.tsx';

/** 역참조 표식 — 렌더러가 이 이름을 들고 있어야 한다. */
const SELF_NAME = 'gadget-catalog-renderer-parity.test.ts';

/**
 * 카탈로그에 선언된 가젯 타입 총수.
 *
 * SDD 14.2 의 표준 12종이고 스펙 A3 이 같은 수를 카탈로그 응답에서 단언한다.
 * 여기서는 **파서가 제대로 읽었는지**를 재는 용도다 — 12 가 아니면 정규식이 깨졌거나
 * 필드 디스크립터를 먹었거나(15) 아무것도 못 읽은 것(0)이다.
 */
const DECLARED_TYPE_COUNT = 12;

function read(relative: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, relative), 'utf8');
}

/** enum 상수 하나 — 키와 활성 여부. */
interface GadgetConstant {
  readonly name: string;
  readonly key: string;
  readonly enabled: boolean;
}

/**
 * `GadgetType.kt` 의 enum 상수를 파싱한다.
 *
 * 블록 분할은 **다음 상수 선언 줄까지**로 한다. 괄호 균형을 세지 않는 이유는 `configFields`
 * 안에 중첩 괄호가 깊어 파서가 복잡해지는데, 상수 선언은 항상 줄 시작 4칸 + 대문자라
 * 경계가 이미 자명하기 때문이다.
 *
 * @param source `GadgetType.kt` 원문.
 * @returns 선언 순서대로의 상수 목록.
 * @throws Error 한 블록에서 키나 `enabled` 를 못 찾으면. **못 읽은 것을 빈 것으로 세면 판정이 공허해진다.**
 */
export function parseGadgetConstants(source: string): GadgetConstant[] {
  // ★Kotlin 쪽은 주석을 걷지 않는다. 아래 정규식이 전부 **줄 시작 + 정확한 들여쓰기**를
  //   요구하는데, Kotlin 주석은 `//` 나 ` * ` 로 시작하므로 `^ {4}[A-Z_]+\($` ·
  //   `^ {8}key = "` 를 만족할 수 없다. 블록 주석 안에 정확히 그 모양을 넣는 것은 이론상
  //   가능하지만, 그 경우 아래 **개수 등식 12** 가 즉시 잡는다.
  const lines = source.split('\n');

  // 상수 선언 줄 — 들여쓰기 4칸 + 대문자 식별자 + 여는 괄호.
  const starts: Array<{ name: string; line: number }> = [];
  lines.forEach((line, index) => {
    const m = /^ {4}([A-Z][A-Z0-9_]*)\($/.exec(line);
    if (m !== null) starts.push({ name: m[1] as string, line: index });
  });

  return starts.map((start, i) => {
    const end = i + 1 < starts.length ? (starts[i + 1] as { line: number }).line : lines.length;
    const block = lines.slice(start.line, end);

    // ★들여쓰기 정확히 8칸 + 블록 안 첫 번째 것. 20칸짜리 필드 디스크립터의 key 를 거른다.
    const keyLine = block.find((l) => /^ {8}key = "/.test(l));
    if (keyLine === undefined) {
      throw new Error(`${start.name}: 들여쓰기 8칸 key 를 못 찾았다 — 파서가 소스를 못 읽었다`);
    }
    const keyMatch = /^ {8}key = "([a-z0-9_]+)",/.exec(keyLine);
    if (keyMatch === null) {
      throw new Error(`${start.name}: key 줄의 형식이 예상과 다르다 — ${keyLine.trim()}`);
    }

    const enabledLine = block.find((l) => /^ {8}enabled = /.test(l));
    if (enabledLine === undefined) {
      throw new Error(`${start.name}: 들여쓰기 8칸 enabled 를 못 찾았다 — 파서가 소스를 못 읽었다`);
    }
    const enabledMatch = /^ {8}enabled = (true|false),/.exec(enabledLine);
    if (enabledMatch === null) {
      throw new Error(`${start.name}: enabled 줄의 형식이 예상과 다르다 — ${enabledLine.trim()}`);
    }

    return {
      name: start.name,
      key: keyMatch[1] as string,
      enabled: enabledMatch[1] === 'true',
    };
  });
}

/**
 * `GadgetRenderer.tsx` 가 `case` 로 그리는 가젯 타입을 파싱한다.
 *
 * fall-through(`case 'a': case 'b': return X`)를 쓰는 자리가 이미 있으므로 줄마다 독립으로 잡는다.
 *
 * @param source `GadgetRenderer.tsx` 원문.
 * @returns `case` 에 적힌 타입 키 집합.
 */
export function parseRenderedTypes(source: string): Set<string> {
  // ★여기는 주석을 반드시 걷는다. 주석 처리된 `case 'pie_chart':` 를 먹으면 「그릴 수 있다」로
  //   읽혀 차집합이 0 이 되고, 실제로는 안 그려지는데 **초록**이 난다 — false green 이다.
  //   Kotlin 쪽과 달리 이 정규식은 `^\s*case` 라 들여쓰기가 방벽이 되지 못한다.
  const body = stripCommentsForCaseScan(source);
  const found = new Set<string>();
  for (const line of body.split('\n')) {
    const m = /^\s*case '([a-z0-9_]+)':/.exec(line);
    if (m !== null) found.add(m[1] as string);
  }
  return found;
}

describe('가젯 카탈로그 ↔ 렌더러 정합', () => {
  test('파서가 enum 상수 12종을 정확히 읽는다 (공허 통과 방지)', () => {
    const constants = parseGadgetConstants(read(KOTLIN_SOURCE));

    assert.equal(
      constants.length,
      DECLARED_TYPE_COUNT,
      `enum 상수를 ${constants.length}개 읽었다. ${DECLARED_TYPE_COUNT} 이 아니면 파서가 깨졌다 — ` +
        `0 이면 정규식 미매치, ${DECLARED_TYPE_COUNT} 초과면 필드 디스크립터의 key 를 먹은 것이다.`,
    );

    // 키가 중복이면 블록 경계가 어긋나 같은 줄을 두 번 읽은 것이다.
    assert.equal(
      new Set(constants.map((c) => c.key)).size,
      constants.length,
      '가젯 키가 중복이다 — 블록 경계가 어긋났다',
    );
  });

  test('★렌더러 case 집합과 enabled=true 집합이 정확히 같다', () => {
    const constants = parseGadgetConstants(read(KOTLIN_SOURCE));
    const rendered = parseRenderedTypes(read(RENDERER_SOURCE));

    const enabled = constants.filter((c) => c.enabled).map((c) => c.key);

    // 공허 방지 짝 — 어느 쪽이든 0 이면 두 빈 집합이 같아져 조용히 통과한다.
    assert.ok(enabled.length > 0, 'enabled=true 가 0건이다 — 파서가 enabled 를 못 읽었다');
    assert.ok(rendered.size > 0, 'case 가 0건이다 — 렌더러 파서가 깨졌다');

    const enabledOnly = enabled.filter((k) => !rendered.has(k)).sort();
    const renderedOnly = [...rendered].filter((k) => !enabled.includes(k)).sort();

    assert.deepEqual(
      { 켤수있는데못그림: enabledOnly, 그릴수있는데못켬: renderedOnly },
      { 켤수있는데못그림: [], 그릴수있는데못켬: [] },
      '카탈로그와 렌더러가 갈렸다.\n' +
        `  켤 수 있는데 못 그림 → 사용자가 「지원되지 않는 가젯입니다」를 본다: ${enabledOnly.join(', ') || '없음'}\n` +
        `  그릴 수 있는데 못 켬 → 도달 불가 코드다: ${renderedOnly.join(', ') || '없음'}`,
    );
  });

  test('렌더러가 이 판별식을 역참조한다', () => {
    // 「이 축은 판별식이 지킨다」가 목록 옆에 없으면, 목록을 늘리는 사람은 판별식의 존재를
    // 모른 채 한쪽만 고치고 red 를 만난 뒤에야 알게 된다.
    // ★백엔드 `GadgetType.kt` 에는 요구하지 않는다 — 이 판별식은 그 파일을 읽기만 하고,
    //   다른 BC 의 파일에 우리 쪽 표기를 강제하면 그것이 또 하나의 갈라질 규약이 된다.
    //   `card-field-catalog-parity.test.ts` 가 세운 선례와 같은 판단이다.
    assert.ok(
      read(RENDERER_SOURCE).includes(SELF_NAME),
      `${RENDERER_SOURCE} 가 ${SELF_NAME} 를 언급하지 않는다 — 목록을 늘리는 사람이 판별식을 모른다`,
    );
  });
});
