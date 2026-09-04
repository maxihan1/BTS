// 보드 목록 요약 3-way 정합 판별식 — 백엔드 DTO ↔ 프론트 zod ↔ MSW 조립부의 키 차집합 0
//
// ## 왜 있나
//
// `boardSummarySchema.canDelete` 는 `.optional()` 이다(스펙 C-2 — 기본값이 fail-closed 와
// 일치하는 유일한 필드라 그것이 안전한 쪽이다). 대가는 **런타임 파싱이 백엔드 누락을 못 잡는
// 것**이다. 서버가 필드를 빼도 zod 는 통과시키고 화면은 조용히 「삭제 못 함」으로 굳는다.
//
// 그 자리에 목록이 셋 있다.
//   ① 백엔드 `BoardSummaryResponse` 주 생성자
//   ② 프론트 `boardSummarySchema`
//   ③ MSW `toResponseSummary`
//
// 셋은 서로를 모른다. 이 저장소가 이미 이름 붙인 지배 결함 양식이고
// (`two-lists-never-check-each-other`), `board-handlers.ts` 의 주석이 그 사고를 **이미 경고하고
// 있다.** 그러나 주석은 다음 필드 추가 때 읽히지 않는다. 이 파일이 그 경고를 기계로 바꾼다.
//
// ## 왜 `apps/web/src/api/__tests__/` 가 아니라 여기인가
//
// `.husky/pre-push` 는 프론트 테스트를 `push-frontend-tests.ts` 로 돌리지만, 좁힘이
// `vitest related` 의 **모듈 그래프**다. 이 판별식은 `BoardResponses.kt` 를 `import` 가 아니라
// `readFileSync` 로 읽으므로 그 그래프에 안 걸리고, **백엔드 DTO 만 바뀐 커밋**은
// `select-test-scope.ts` 의 `frontendScope` 가 `skip`(프론트 변경 0건)이라 아무것도 안 돈다.
// 그런데 「백엔드가 필드를 바꿨는데 프론트 둘이 못 따라간다」가 정확히 이 판별식이 존재하는
// 이유다. `scripts/**/*.test.ts` 는 같은 훅의 **줄 2에서 조건 없이 전량** 실행되므로, 여기 두면
// 어느 꼭짓점이 바뀌든 항상 돈다.
//
// ## 왜 import 하지 않고 텍스트로 읽나
//
// `node --test` 런타임에서 `apps/web/src/api/boards.ts` 를 import 하면 `api/client` 를 거쳐
// `import.meta.env` 에 닿아 깨진다. `apps/web/e2e/fixtures/board-helpers.ts` 머리 주석이 같은
// 이유를 이미 기록해 뒀다. 그래서 세 꼭짓점을 **전부** 텍스트로 뽑는다 — 한쪽만 import 하면
// 그 한쪽이 다시 모듈 그래프를 만들어 위 §를 무효로 만든다.
//
// ## 헬퍼 계약 픽스처가 왜 따로 있나
//
// 이 판별식은 **자기 저장소의 파일을 읽어** 판정한다. 그러면 추출 함수가 틀렸을 때 red 를
// 보려면 그 파일 자체를 뮤테이션해야 한다 — 판정 논리의 회귀를 파일과 무관하게 잡을 수 없다.
// 메모리 `self-reading-guard-needs-helper-level-tests` 의 처방대로, 세 추출 함수를 픽스처
// 문자열로 직접 재는 describe 를 따로 둔다. 결함 픽스처(KDoc 유령 필드 · spread)가 그 자체로
// 회귀 판정이다.
//
// ## 미커버 선언 — 무엇을 일부러 안 보나
//
//   - **`BoardDetailResponse` 삼각형.** 필드 10+ · `JsonNullable` · 중첩 DTO(#444 로
//     `states`·`unmappedStates` 추가)라 파서가 무거워지고 이 PR 의 신호가 묻힌다. 넓히려면
//     별건으로 한다(TODOS 후보).
//   - **런타임 축.** 「핸들러를 실제로 태워 응답 키를 본다」는 증명은 이 파일이 안 한다.
//     `canDelete` 의 런타임 경로는 `apps/web/src/mocks/board-handlers.test.ts` 가 덮고,
//     그것은 zod·MSW 어느 쪽이 바뀌든 `vitest related` 로 딸려 온다.
//   - **값의 옳음.** 키 집합만 본다. `canDelete` 가 **맞는 값**인지는 백엔드
//     `BoardControllerIntegrationTest` 와 위 MSW 테스트가 진다.
//   - **★타입 정합.** 키 **이름만** 비교하고 타입은 안 본다. 그래서 백엔드가 `val n: Int` 인데
//     zod 가 `n: z.string()` 이면 세 집합의 이름이 완전히 일치해 **차집합 0 으로 초록**이고,
//     런타임에는 목록 응답 전건이 zod 파싱에 실패해 보드 스위처·백로그 헤더·`ProjectViewChrome`
//     탭바가 **동시에 죽는다** — 스펙 C-2 가 `.optional()` 로 피하려던 바로 그 파국이다.
//     ⇒ **이 판별식을 「계약이 맞다」의 증명으로 과신하지 마라.** 이름 축만 갚는다.
//     넓히려면 추출기가 타입 토큰을 함께 잡아 `Boolean↔z.boolean` · `String↔z.string` ·
//     `UUID↔z.string().uuid` 소형 매핑표로 단언한다. 지금 필드(`canDelete: Boolean` ↔
//     `z.boolean()`)는 `boards.test.ts` T-BD-21d(비-boolean 거부)가 별도로 지킨다.

import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const REPO_ROOT = path.resolve(import.meta.dirname, '../..')

/** 꼭짓점 ① — 백엔드 응답 DTO. */
const BACKEND_DTO =
  'backend/modules/agile-planning/src/main/kotlin/com/bts/agileplanning/web/dto/BoardResponses.kt'
/** 꼭짓점 ② — 프론트 파싱 스키마. */
const ZOD_SCHEMA = 'apps/web/src/api/boards.ts'
/** 꼭짓점 ③ — MSW 목록 조립부. */
const MSW_HANDLERS = 'apps/web/src/mocks/board-handlers.ts'

const SOURCES = [BACKEND_DTO, ZOD_SCHEMA, MSW_HANDLERS] as const

const KOTLIN_CLASS = 'BoardSummaryResponse'
const ZOD_SCHEMA_NAME = 'boardSummarySchema'
const MSW_FUNCTION = 'toResponseSummary'

/**
 * 세 꼭짓점이 최소한 이만큼 키를 갖는다.
 *
 * 현재 계약은 `{boardId, projectKey, name, boardType, canDelete}` 5개다. 「정확히 5」로 두지
 * 않는 이유 — 그러면 이 파일이 **네 번째 목록**이 되어 필드가 정상적으로 늘 때마다 여기를 또
 * 고쳐야 한다. 하한만 두고, 갈라짐은 차집합이 잡는다.
 */
const MIN_FIELD_COUNT = 5

/**
 * 카나리 키.
 *
 * 파서가 KDoc·주석이 아니라 **본문**을 읽는다는 증거다. 이것이 없으면 세 집합이 전부 비었을 때
 * 「차집합 0」이 성립해 가짜 초록이 된다. `canDelete` 는 이 PR 이 추가한 필드고 `boardType` 은
 * 직전 PR(FR-BD-04)이 추가한 필드라, 둘 다 「최근에 실제로 움직인 자리」다.
 */
const CANARY_KEYS = ['boardType', 'canDelete'] as const

/**
 * 픽스처 안에 심는 줄 주석의 여는 자리. **런타임에 잇는다.**
 *
 * 소스에 `//` 를 그대로 적으면 `git-fixture-isolation.test.ts` 의 「scripts 전량에서 살아남은
 * 줄 주석이 하나도 없다」가 이 픽스처를 위반으로 읽는다 — 그 판정은 템플릿 리터럴 안의
 * 주석까지 걷지 않으므로, 문자열 안의 그것과 코드 자리에 살아남은 그것을 텍스트로 가를 길이
 * 없기 때문이다. 그쪽이 자기 미끼에 쓴 처방(`COMMENT_OPEN`)을 그대로 따른다.
 */
const LINE_COMMENT_OPEN = '/'.repeat(2)

/** 객체 리터럴·zod 블록의 한 줄에서 키 이름을 잡는 패턴. */
const OBJECT_KEY_PATTERN = /^([A-Za-z][A-Za-z0-9_]*)\s*:/
/** Kotlin 주 생성자 한 줄에서 필드 이름을 잡는 패턴. */
const KOTLIN_FIELD_PATTERN = /^val\s+([A-Za-z][A-Za-z0-9_]*)\s*:/

/**
 * 저장소 상대 경로의 파일을 읽는다.
 *
 * @param relative 저장소 루트 기준 상대 경로.
 * @returns 파일 전문.
 */
function read(relative: string): string {
  return fs.readFileSync(path.join(REPO_ROOT, relative), 'utf-8')
}

/**
 * 주석을 걷어낸다. 블록 주석은 통째로, 줄 주석은 **줄 전체가 주석일 때만** 지운다.
 *
 * 왜 줄 전체일 때만인가. `'/api/v1/boards'` 같은 URL 문자열의 `//` 를 주석으로 오인해 뒤를
 * 잘라내면 그 줄의 중괄호가 사라져 블록 짝맞춤이 **조용히** 어긋난다.
 *
 * @param source 원본 텍스트.
 * @returns 주석이 걷힌 텍스트.
 */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .filter((line) => !line.trim().startsWith('//'))
    .join('\n')
}

/**
 * `openIndex` 의 여는 기호부터 짝이 맞는 닫는 기호까지의 **내부** 텍스트를 돌려준다.
 *
 * 짝이 안 맞거나 시작점이 없으면 빈 문자열이다 — 부르는 쪽의 개수·카나리 단언이 그것을 red 로
 * 잡는다. 여기서 throw 하면 「어느 꼭짓점이 비었나」가 스택 트레이스에 묻힌다.
 *
 * @param source 대상 텍스트(주석이 걷힌 상태).
 * @param openIndex 여는 기호의 인덱스.
 * @param open 여는 기호.
 * @param close 닫는 기호.
 * @returns 여닫는 기호 사이의 내부 텍스트.
 */
function sliceBalanced(source: string, openIndex: number, open: string, close: string): string {
  if (openIndex < 0) {
    return ''
  }
  let depth = 0
  for (let i = openIndex; i < source.length; i += 1) {
    const ch = source[i]
    if (ch === open) {
      depth += 1
    } else if (ch === close) {
      depth -= 1
      if (depth === 0) {
        return source.slice(openIndex + 1, i)
      }
    }
  }
  return ''
}

/**
 * 블록 내부에서 **최상위 깊이의 키만** 뽑는다.
 *
 * 중첩 객체의 키를 세면 있지도 않은 필드를 요구하며 red 로 굳고, 사람이 그것을 「이 판별식은
 * 원래 시끄럽다」로 배운다.
 *
 * @param inner 블록 내부 텍스트.
 * @param keyPattern 줄 머리에서 키 이름을 잡는 정규식(캡처 그룹 1 = 이름).
 * @returns 최상위 키 이름 집합.
 */
function topLevelKeys(inner: string, keyPattern: RegExp): Set<string> {
  const keys = new Set<string>()
  let depth = 0
  for (const rawLine of inner.split('\n')) {
    const line = rawLine.trim()
    const matched = depth === 0 ? keyPattern.exec(line) : null
    const name = matched === null ? undefined : matched[1]
    if (name !== undefined) {
      keys.add(name)
    }
    depth += (line.match(/[{([]/g) ?? []).length - (line.match(/[)\]}]/g) ?? []).length
  }
  return keys
}

/**
 * Kotlin `data class <name>( … )` 의 **괄호 본문**에서 `val <field>:` 이름을 뽑는다.
 *
 * ★괄호 밖을 읽으면 안 된다. 바로 위 KDoc 이 `@property boardId …` 로 필드명을 **그대로
 * 나열**하므로, 파일 전체를 훑으면 KDoc 토큰을 필드로 오인해 「파서가 본문을 안 읽어도
 * 통과」하는 상태가 된다. `bulk-operation-enum-parity.test.ts` 가 명시적으로 경고하는 실패
 * 양식이다. 여기서는 ① 주석 제거 ② 괄호 짝맞춤 두 겹으로 막는다.
 *
 * @param source `.kt` 파일 전문.
 * @param className 대상 data class 이름.
 * @returns 주 생성자 필드 이름 집합. 선언이 없으면 빈 집합.
 */
function kotlinDataClassFields(source: string, className: string): Set<string> {
  const clean = stripComments(source)
  const anchor = `data class ${className}(`
  const declared = clean.indexOf(anchor)
  if (declared < 0) {
    return new Set()
  }
  const inner = sliceBalanced(clean, declared + anchor.length - 1, '(', ')')
  return topLevelKeys(inner, KOTLIN_FIELD_PATTERN)
}

/**
 * zod `const <name> = z.object({ … })` 블록의 최상위 키를 뽑는다.
 *
 * @param source `.ts` 파일 전문.
 * @param schemaName 대상 스키마 상수 이름.
 * @returns 최상위 키 집합. 선언이 없으면 빈 집합.
 */
function zodObjectKeys(source: string, schemaName: string): Set<string> {
  const clean = stripComments(source)
  const declared = new RegExp(`const\\s+${schemaName}\\s*=\\s*z\\.object\\(\\s*\\{`).exec(clean)
  if (declared === null) {
    return new Set()
  }
  const inner = sliceBalanced(clean, clean.indexOf('{', declared.index), '{', '}')
  return topLevelKeys(inner, OBJECT_KEY_PATTERN)
}

/**
 * MSW 조립 함수의 `return { … }` 객체 리터럴 **원문**을 돌려준다.
 *
 * 키가 아니라 원문을 돌려주는 이유. 「spread 가 없다」는 **모양** 자체를 따로 단언해야 하기
 * 때문이다. `...stored` 로 바뀌면 키 추출이 조용히 틀려지므로 그 순간 red 가 나야 한다.
 *
 * @param source `.ts` 파일 전문.
 * @param functionName 대상 함수 이름.
 * @returns 반환 객체 리터럴의 내부 텍스트. 못 찾으면 빈 문자열.
 */
function returnLiteralBody(source: string, functionName: string): string {
  const clean = stripComments(source)
  const declared = clean.indexOf(`function ${functionName}(`)
  if (declared < 0) {
    return ''
  }
  const returnAt = clean.indexOf('return {', declared)
  if (returnAt < 0) {
    return ''
  }
  return sliceBalanced(clean, clean.indexOf('{', returnAt), '{', '}')
}

/**
 * 세 꼭짓점의 키 집합을 라벨과 함께 모은다.
 *
 * describe 바깥(모듈 로드 시점)이 아니라 테스트 안에서 부른다 — 로드 시점에 읽으면 경로가
 * 틀렸을 때 파일 존재 단언조차 못 돌고 수집 단계에서 통째로 죽는다.
 *
 * @returns `[라벨, 키 집합]` 3쌍.
 */
function threeVertices(): ReadonlyArray<readonly [string, Set<string>]> {
  const mswKeys = topLevelKeys(returnLiteralBody(read(MSW_HANDLERS), MSW_FUNCTION), OBJECT_KEY_PATTERN)
  return [
    [`백엔드 DTO(${KOTLIN_CLASS})`, kotlinDataClassFields(read(BACKEND_DTO), KOTLIN_CLASS)],
    [`zod(${ZOD_SCHEMA_NAME})`, zodObjectKeys(read(ZOD_SCHEMA), ZOD_SCHEMA_NAME)],
    [`MSW(${MSW_FUNCTION})`, mswKeys],
  ]
}

describe('보드 목록 요약 — 비-공허', () => {
  test('읽으려는 세 파일이 전부 실재한다', () => {
    // 경로가 틀리면 아래 차집합이 빈 집합끼리 비교해 조용히 통과한다.
    const missing = SOURCES.filter((relative) => !fs.existsSync(path.join(REPO_ROOT, relative)))
    assert.deepEqual(missing, [])
  })

  test('세 집합이 각각 5키 이상이다', () => {
    const thin = threeVertices()
      .filter(([, keys]) => keys.size < MIN_FIELD_COUNT)
      .map(([label, keys]) => `${label} 이 ${keys.size}키뿐이다 — 추출 앵커가 어긋났다`)
    assert.deepEqual(thin, [])
  })

  test('★카나리 — 세 집합 전부가 boardType 과 canDelete 를 실제로 담는다', () => {
    // 개수 단언만으로는 주석에서 긁어 채워질 수 있다. 「최근에 실제로 움직인 두 필드가
    // 본문에서 잡힌다」를 따로 못박는다.
    const absent: string[] = []
    for (const [label, keys] of threeVertices()) {
      for (const canary of CANARY_KEYS) {
        if (!keys.has(canary)) {
          absent.push(`${label} 에 '${canary}' 가 없다`)
        }
      }
    }
    assert.deepEqual(absent, [])
  })

  test('★MSW 조립부가 spread 없는 명시적 객체 리터럴이다', () => {
    // `...stored` 로 바뀌면 텍스트 추출이 조용히 틀려진다. 모양 자체를 단언해 그 순간 red 를 낸다.
    const literal = returnLiteralBody(read(MSW_HANDLERS), MSW_FUNCTION)
    const violations = [
      literal.length === 0 ? `${MSW_FUNCTION} 의 return 객체 리터럴을 못 찾았다` : null,
      literal.includes('...') ? `${MSW_FUNCTION} 에 spread 가 들어왔다 — 키 추출이 못 미친다` : null,
    ].filter((violation) => violation !== null)
    assert.deepEqual(violations, [])
  })
})

describe('보드 목록 요약 — 세 목록의 양방향 차집합 0', () => {
  test('★어느 꼭짓점에만 있는 키도 없다', () => {
    const vertices = threeVertices()
    const gaps: string[] = []
    for (const [fromLabel, fromKeys] of vertices) {
      for (const [toLabel, toKeys] of vertices) {
        if (fromLabel === toLabel) {
          continue
        }
        for (const key of fromKeys) {
          if (!toKeys.has(key)) {
            gaps.push(`'${key}' 가 ${fromLabel} 에만 있다 — ${toLabel} 에 없다`)
          }
        }
      }
    }
    assert.deepEqual(gaps, [])
  })
})

describe('추출 헬퍼 계약 — 픽스처로 직접 잰다', () => {
  const KOTLIN_FIXTURE = `
/**
 * 보드 목록 항목.
 *
 * @property boardId 보드 UUID.
 * @property ghostInKdoc KDoc 에만 있고 본문에는 없는 이름.
 */
data class Sample(
    val boardId: UUID,
    val canDelete: Boolean,
) {
    companion object {
        val ghostInCompanion: String = ""
    }
}

data class Sibling(
    val ghostInSibling: String,
)
`

  const ZOD_FIXTURE = `
export const sampleSchema = z.object({
  /** ghostInBlockComment: 주석 안의 이름 */
  boardId: z.string().uuid(),
  ${LINE_COMMENT_OPEN} ghostInLineComment: z.string(),
  nested: z.object({
    ghostInNested: z.string(),
  }),
  canDelete: z.boolean().optional(),
})

export const otherSchema = z.object({ ghostInOther: z.string() })
`

  const MSW_FIXTURE = `
function toSample(stored: Stored): Summary {
  return {
    boardId: stored.boardId,
    nested: {
      ghostInNested: 1,
    },
    canDelete: stored.canDelete ?? true,
  }
}
`

  const MSW_SPREAD_FIXTURE = `
function toSample(stored: Stored): Summary {
  return {
    ...stored,
    canDelete: true,
  }
}
`

  test('Kotlin — 괄호 본문만 읽는다(KDoc·companion·형제 클래스를 세지 않는다)', () => {
    assert.deepEqual([...kotlinDataClassFields(KOTLIN_FIXTURE, 'Sample')].sort(), [
      'boardId',
      'canDelete',
    ])
  })

  test('Kotlin — 대상 선언이 없으면 빈 집합이다(개수 단언이 red 로 잡는 자리)', () => {
    assert.equal(kotlinDataClassFields(KOTLIN_FIXTURE, 'Absent').size, 0)
  })

  test('zod — 주석 속 이름·중첩 키·다른 스키마를 세지 않는다', () => {
    assert.deepEqual([...zodObjectKeys(ZOD_FIXTURE, 'sampleSchema')].sort(), [
      'boardId',
      'canDelete',
      'nested',
    ])
  })

  test('zod — 대상 스키마가 없으면 빈 집합이다', () => {
    assert.equal(zodObjectKeys(ZOD_FIXTURE, 'absentSchema').size, 0)
  })

  test('MSW — return 리터럴의 최상위 키만 뽑는다', () => {
    const keys = topLevelKeys(returnLiteralBody(MSW_FIXTURE, 'toSample'), OBJECT_KEY_PATTERN)
    assert.deepEqual([...keys].sort(), ['boardId', 'canDelete', 'nested'])
  })

  test('★MSW — spread 픽스처를 모양 단언이 잡는다', () => {
    assert.ok(returnLiteralBody(MSW_SPREAD_FIXTURE, 'toSample').includes('...'))
  })

  test('★MSW — spread 로 바뀌면 키 추출이 조용히 줄어든다(모양 단언이 필요한 이유)', () => {
    // 차집합만 있으면 이 손실이 「필드가 없다」인지 「파서가 못 읽는다」인지 갈리지 않는다.
    const keys = topLevelKeys(returnLiteralBody(MSW_SPREAD_FIXTURE, 'toSample'), OBJECT_KEY_PATTERN)
    assert.deepEqual([...keys], ['canDelete'])
  })

  test('MSW — 대상 함수가 없으면 빈 문자열이다', () => {
    assert.equal(returnLiteralBody(MSW_FIXTURE, 'absentFunction'), '')
  })
})
