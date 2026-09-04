// 이슈 텍스트 길이 제약 2-way 정합 판별식 — 백엔드 IssueTextConstraints ↔ 프론트 사본의 값 일치
//
// ## 왜 있나
//
// 2026-09-04 실측 — 서버는 본문·댓글을 32,767자로 막는데 **프론트에는 상수도 노출도 없었다.**
// 32767 은 `apps/web/src/api/issues.ts` 주석에만 있었고, 본문이 contenteditable 이라
// `maxLength` 속성조차 걸 수 없다. 사용자는 32,767자를 다 쓴 뒤 저장 버튼을 눌러서야
// 서버 400 으로 상한을 알게 됐다.
//
// 그 구멍을 메우려고 프론트에 사본(`apps/web/src/lib/issue-text-constraints.ts`)을 뒀다.
// 사본을 두는 순간 **두 목록이 서로를 검사하지 않는 지배 결함 양식**에 그대로 들어간다
// (`two-lists-never-check-each-other`). 백엔드가 값을 조정해도 프론트는 옛 값을 들고 조용히
// 굴러가고, 카운터는 틀린 숫자를 자신 있게 보여준다. 이 파일이 그 짝이다.
//
// 선례가 이미 있다 — 백엔드 안에서는 `IssueTextConstraintsAlignmentTest` 가 DB 컬럼·도메인
// `require`·REST `@Size` 세 층을 묶는다. 이 판별식은 그 울타리를 **프론트까지** 넓힌다.
//
// ## 왜 `apps/web` 이 아니라 여기인가
//
// `board-summary-contract.test.ts` 와 같은 이유다. `.husky/pre-push` 의 프론트 테스트는
// `vitest related` 의 **모듈 그래프**로 좁히는데, 이 판별식은 Kotlin 을 `readFileSync` 로 읽어
// 그 그래프에 안 걸린다. 그리고 **백엔드 상수만 바뀐 커밋**은 `select-test-scope.ts` 의
// `frontendScope` 가 skip 이라 프론트 테스트가 아예 안 돈다 — 그런데 그 경우가 바로 이
// 판별식이 필요한 순간이다. `scripts/**/*.test.ts` 는 조건 없이 전량 실행되므로 여기 둔다.
//
// ## 왜 import 하지 않고 텍스트로 읽나
//
// Kotlin 은 애초에 import 할 수 없고, 프론트 쪽만 import 하면 그 한쪽이 다시 모듈 그래프를
// 만들어 위 §를 무효로 만든다. 그래서 **양쪽 다** 텍스트로 뽑는다.
//
// ## 미커버 선언
//
//   - **DB 컬럼 길이·도메인 `require`.** 백엔드 안쪽 3층 정렬은
//     `IssueTextConstraintsAlignmentTest` 가 이미 강제한다. 여기서 또 재면 네 번째 목록이 된다.
//   - **카운터가 재는 문자열의 선택**(본문 HTML · 댓글 평문). 값이 아니라 배선이므로
//     `TextLengthCounter` 단위 테스트와 각 부모의 테스트가 맡는다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const REPO_ROOT = path.resolve(import.meta.dirname, '../..')

/** 꼭짓점 ① — 백엔드 단일 출처. */
const KOTLIN_SOURCE =
  'backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/domain/IssueTextConstraints.kt'
/** 꼭짓점 ② — 프론트 사본. */
const TS_SOURCE = 'apps/web/src/lib/issue-text-constraints.ts'

/**
 * 대조할 상수 짝.
 *
 * 「정확히 3」로 못박지 않고 이 표를 정본으로 둔다 — 새 제약이 늘면 여기 한 줄이 늘고,
 * 빠뜨리면 아래 §커버리지 판정이 잡는다.
 */
const PAIRS: ReadonlyArray<{ readonly kotlin: string; readonly ts: string }> = [
  { kotlin: 'SUMMARY_MAX', ts: 'SUMMARY_MAX_LENGTH' },
  { kotlin: 'DESCRIPTION_MAX', ts: 'DESCRIPTION_MAX_LENGTH' },
  { kotlin: 'COMMENT_BODY_MAX', ts: 'COMMENT_BODY_MAX_LENGTH' },
]

function read(relative: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, relative), 'utf8')
}

/**
 * 주석을 걷어낸다.
 *
 * ★이것이 없으면 KDoc 이 판정을 오염시킨다. `IssueTextConstraints.kt` 의 KDoc 은 표 안에
 * `| REST 생성·수정 | ... | **200** |` 처럼 **옛 값들**을 근거로 적어 두고 있어, 주석을 함께
 * 읽으면 상수 하나에 값이 여럿 잡힌다.
 */
function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

/** Kotlin `const val NAME = 123` 를 뽑는다. */
function readKotlinConstants(source: string): Map<string, number> {
  const out = new Map<string, number>()
  const re = /const\s+val\s+([A-Z_]+)\s*(?::\s*Int\s*)?=\s*(\d+)/g
  for (const m of stripComments(source).matchAll(re)) {
    out.set(m[1] as string, Number(m[2]))
  }
  return out
}

/** TS `export const NAME = 123` 를 뽑는다. */
function readTsConstants(source: string): Map<string, number> {
  const out = new Map<string, number>()
  const re = /export\s+const\s+([A-Z_]+)\s*=\s*(\d+)/g
  for (const m of stripComments(source).matchAll(re)) {
    out.set(m[1] as string, Number(m[2]))
  }
  return out
}

describe('이슈 텍스트 길이 제약 — 백엔드 ↔ 프론트 정합', () => {
  const kotlin = readKotlinConstants(read(KOTLIN_SOURCE))
  const ts = readTsConstants(read(TS_SOURCE))

  // ★비-공허 판정. 파서가 아무것도 못 뽑으면 아래 값 대조가 전부 「빈 집합 == 빈 집합」으로
  //   통과한다. 파일 경로가 바뀌거나 문법이 달라졌을 때 조용히 죽는 것을 막는다.
  test('양쪽 파서가 상수를 실제로 뽑는다 (비-공허)', () => {
    assert.ok(kotlin.size >= PAIRS.length, `Kotlin 상수 ${kotlin.size}건 — 파서가 죽었다`)
    assert.ok(ts.size >= PAIRS.length, `TS 상수 ${ts.size}건 — 파서가 죽었다`)
  })

  for (const { kotlin: kName, ts: tName } of PAIRS) {
    test(`${kName} == ${tName}`, () => {
      const kValue = kotlin.get(kName)
      const tValue = ts.get(tName)
      assert.notEqual(kValue, undefined, `${KOTLIN_SOURCE} 에 ${kName} 이 없다`)
      assert.notEqual(tValue, undefined, `${TS_SOURCE} 에 ${tName} 이 없다`)
      assert.equal(
        tValue,
        kValue,
        `프론트 ${tName}=${String(tValue)} 가 백엔드 ${kName}=${String(kValue)} 와 다르다. ` +
          '두 파일을 같은 PR 에서 고칠 것.',
      )
    })
  }

  test('백엔드가 상수를 늘리면 프론트도 따라왔는지 — 미대조 상수 0건', () => {
    const covered = new Set(PAIRS.map((p) => p.kotlin))
    const uncovered = [...kotlin.keys()].filter((k) => !covered.has(k))
    assert.deepEqual(
      uncovered,
      [],
      `백엔드에 새 제약이 생겼는데 이 판별식의 PAIRS 표에 없다: ${uncovered.join(', ')}. ` +
        '프론트 사본과 PAIRS 를 함께 늘릴 것.',
    )
  })

  test('주석 제거가 KDoc 의 옛 값을 삼킨다 — 파서 자체의 회귀 판정', () => {
    // KDoc 안에 다른 값을 심은 픽스처. 주석을 안 걷으면 200 이 잡혀 판정이 오염된다.
    const fixture = [
      '/**',
      ' * | REST | CreateIssueRequest | const val SUMMARY_MAX = 200 |',
      ' */',
      'object X {',
      '    const val SUMMARY_MAX = 255',
      '}',
    ].join('\n')
    assert.equal(readKotlinConstants(fixture).get('SUMMARY_MAX'), 255)
  })
})
