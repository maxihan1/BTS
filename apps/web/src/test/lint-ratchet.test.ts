// apps/web 강제 수단 래칫 2종의 계약 테스트 — R3 placeholder 규칙 발화 확인 + R4 줄수 베이스라인 판정
import { describe, expect, it } from 'vitest'
import { ESLint } from 'eslint'
import tseslint from 'typescript-eslint'
import { resolve } from 'node:path'
import { OVERSIZED_FUNCTION_BASELINE } from './lint-ratchet-baseline'

/** `apps/web` 루트. 이 파일은 `src/test/` 에 있다. */
const WEB_ROOT = resolve(__dirname, '../..')

/**
 * 실제 `eslint.config.js` 를 로드하는 인스턴스.
 * ★자체 config 로 대체하면 「저장소의 진짜 설정이 막는가」를 못 본다 — 그게 이 테스트의 존재 이유다.
 */
const realEslint = new ESLint({ cwd: WEB_ROOT })

/**
 * `eslint.config.js` 가 계약으로 내보내는 두 상수.
 *
 * `BUTTON_PRIMITIVE_EXEMPT_FILES` 를 **비어 있지 않은 튜플**로 선언한 것이 비-공허 짝의 절반이다
 * (나머지 절반은 아래 런타임 검증). 빈 배열을 허용하면 대조군 ②가 조용히 사라진다.
 */
interface EslintConfigContract {
  readonly PLACEHOLDER_LOCK_TAG: string
  readonly BUTTON_PRIMITIVE_EXEMPT_FILES: readonly [string, ...string[]]
}

/** 원소가 전부 문자열이고 하나 이상인 배열인가. */
const isNonEmptyStringArray = (value: unknown): value is readonly [string, ...string[]] =>
  Array.isArray(value) &&
  value.length > 0 &&
  value.every((entry: unknown) => typeof entry === 'string')

/** 로드한 모듈이 계약을 만족하는가. 실패하면 아래 로더가 던진다 — 조용한 통과를 막는다. */
const isEslintConfigContract = (value: unknown): value is EslintConfigContract =>
  typeof value === 'object' &&
  value !== null &&
  'PLACEHOLDER_LOCK_TAG' in value &&
  typeof value.PLACEHOLDER_LOCK_TAG === 'string' &&
  value.PLACEHOLDER_LOCK_TAG.length > 0 &&
  'BUTTON_PRIMITIVE_EXEMPT_FILES' in value &&
  isNonEmptyStringArray(value.BUTTON_PRIMITIVE_EXEMPT_FILES)

/**
 * 계약 상수를 `eslint.config.js` 에서 **도출**한다 (손으로 베끼지 않는다 — 리뷰 ②A·③A).
 *
 * ★정적 `import` 를 쓰지 않는 이유. `tsconfig.app.json` 은 `allowJs: false` 라
 *   JS 모듈을 정적 import 하면 `TS7016`(암묵 any)로 타입체크가 깨진다 —
 *   절대 규칙 #11(any 금지) 위반이다. 동적 import 로 `unknown` 을 받아 런타임 검증으로 좁힌다.
 */
const loadEslintConfigContract = async (): Promise<EslintConfigContract> => {
  const mod: unknown = await import(/* @vite-ignore */ resolve(WEB_ROOT, 'eslint.config.js'))
  if (!isEslintConfigContract(mod)) {
    throw new Error(
      'eslint.config.js 가 PLACEHOLDER_LOCK_TAG(비어 있지 않은 문자열)와 ' +
        'BUTTON_PRIMITIVE_EXEMPT_FILES(비어 있지 않은 문자열 배열)를 named export 해야 한다',
    )
  }
  return mod
}

/** 로드는 한 번만 한다 (리뷰 ③A — 같은 것을 여러 번 읽지 않는다). */
let contractPromise: Promise<EslintConfigContract> | undefined
const eslintConfigContract = (): Promise<EslintConfigContract> =>
  (contractPromise ??= loadEslintConfigContract())

/**
 * placeholder 락 위반인지 판정하는 **단일 술어**.
 * ★리뷰 ③A — 헬퍼마다 기준이 다르면(하나는 규칙 전체를 세고 하나는 메시지로 거른다)
 *   같은 것을 두 방식으로 세게 되고, 한쪽만 고쳐지는 순간 두 수치가 조용히 갈라진다.
 */
const isPlaceholderLockViolation = (
  m: { ruleId: string | null; message: string },
  tag: string,
): boolean => m.ruleId === 'no-restricted-syntax' && m.message.includes(tag)

/** 가상 경로로 소스를 던져 위반 수만 센다. 파일을 만들지 않으므로 자기탐지가 없다. */
async function violations(code: string, filePath: string): Promise<number> {
  const { PLACEHOLDER_LOCK_TAG } = await eslintConfigContract()
  const [result] = await realEslint.lintText(code, { filePath, warnIgnored: false })
  return (result?.messages ?? []).filter((m) => isPlaceholderLockViolation(m, PLACEHOLDER_LOCK_TAG))
    .length
}

const KO_LITERAL = '<input placeholder="검색어" />'
const KO_TEMPLATE = '<input placeholder={`${x} 검색`} />'
const EN_LITERAL = '<input placeholder="Search" />'

describe('R3. 한글 placeholder 래칫', () => {
  it('양성 ① 기본 적용면에서 한글 리터럴을 막는다', async () => {
    expect(await violations(KO_LITERAL, 'src/components/__probe__/Probe.tsx')).toBe(1)
  })

  it('양성 ② button 예외 블록 안의 파일에서도 막는다', async () => {
    // 이 블록은 배열이라 앞 블록을 **대체**한다. 안 고치면 DashboardForm 3건·
    // GlobalPermissionFormDialog 1건 = 28건 중 4건이 영구 면제된다.
    //
    // ★리뷰 ②A — 경로를 손으로 적으면, 그 파일이 나중에 예외 목록에서 빠지는 순간
    //   이 대조군은 「button 예외 블록」을 전혀 검사하지 않는데도 계속 green 이 된다.
    //   목록을 적지 않고 **설정에서 도출**한다 (button-primitive-usage.test.ts 선례).
    const { BUTTON_PRIMITIVE_EXEMPT_FILES } = await eslintConfigContract()
    const [exemptFile] = BUTTON_PRIMITIVE_EXEMPT_FILES
    expect(exemptFile).toBeDefined() // 비-공허 짝. 도출이 빈 배열이면 대조군이 사라진다
    expect(await violations(KO_LITERAL, exemptFile)).toBe(1)
  })

  it('양성 ③ 프리미티브 레이어에서도 막는다', async () => {
    expect(await violations(KO_LITERAL, 'src/components/ui/__probe__.tsx')).toBe(1)
  })

  it('양성 ④ 템플릿 리터럴 우회를 막는다', async () => {
    expect(await violations(KO_TEMPLATE, 'src/components/__probe__/Probe.tsx')).toBe(1)
  })

  it('음성 ⑤ 영문 placeholder 는 막지 않는다', async () => {
    // 이게 없으면 「무조건 위반」 규칙도 위 4종을 통과한다.
    expect(await violations(EN_LITERAL, 'src/components/__probe__/Probe.tsx')).toBe(0)
  })

  it('음성 ⑥ 테스트 파일은 대상이 아니다 (블록 순서 회귀 가드)', async () => {
    // src/components/ui/** 블록을 배열로 바꾸면서 그 블록이 마지막에 남으면
    // ui 아래 테스트까지 켜져 badge.test.tsx 2건·command.test.tsx 1건이 red 가 된다.
    expect(await violations(KO_LITERAL, 'src/components/ui/__probe__.test.tsx')).toBe(0)
  })
})

/**
 * 소스 전량을 **실제 config** 로 훑어 placeholder 락 위반 좌표를 모은다.
 * ★리뷰 ③A — 메모화. 이 린트는 1288파일이라 가장 비싸다. 두 번 돌 이유가 없다.
 */
async function computePlaceholderHits(): Promise<string[]> {
  const { PLACEHOLDER_LOCK_TAG } = await eslintConfigContract()
  const results = await realEslint.lintFiles(['src'])
  // ★집계 전에 파싱 실패 0 을 먼저 단언한다. 2026-08-11 이 세션의 1차 측정이
  //   파서 미부착으로 1222건 파싱 실패했고 히트 0 을 「깨끗함」으로 오독했다.
  expect(
    results
      .flatMap((r) => r.messages)
      .filter((m) => m.fatal)
      .map((m) => m.message),
  ).toEqual([])
  expect(results.length).toBeGreaterThan(500) // 비-공허. 실측 1288
  return results.flatMap((r) =>
    r.messages
      // ★리뷰 ③A — 대조군과 **같은 술어**를 쓴다.
      .filter((m) => isPlaceholderLockViolation(m, PLACEHOLDER_LOCK_TAG))
      .map((m) => `${r.filePath.replace(`${WEB_ROOT}/`, '')}:${m.line}`),
  )
}

let placeholderCache: Promise<string[]> | undefined
const productionPlaceholderHits = (): Promise<string[]> =>
  (placeholderCache ??= computePlaceholderHits())

describe('R3. 소스 전량 placeholder 잔량', () => {
  it('비-테스트 소스에 한글 placeholder 하드코딩이 0건이다', async () => {
    // 개수가 아니라 목록 전수 비교 — 개수 가드는 하나 고치고 하나 늘리면 통과한다.
    expect(await productionPlaceholderHits()).toEqual([])
  }, 60_000)
})

// ─────────────────────────────────────────────────────────────────────────
// R4. 컴포넌트 200줄 래칫 — 「지금보다 나빠지지 않는다」만 강제하는 단조 가드.
// ─────────────────────────────────────────────────────────────────────────

/** 한 함수가 넘어서는 안 되는 raw 줄수. 넘는 것들은 베이스라인에 동결돼 있다. */
const MAX_COMPONENT_LINES = 200

/** 훑은 파일 수 하한 (비-공허 단언). 실측 600 — 스캔이 비면 아래 단언이 전부 참이 된다. */
const MIN_SCANNED_FILES = 500

/** 계약 테스트·모의 서버는 대상 밖이다. 베이스라인 파일도 `src/test/**` 라 자기탐지가 없다. */
const RATCHET_IGNORES = ['**/*.test.ts', '**/*.test.tsx', 'src/test/**', 'src/mocks/**', 'dist/**']

/**
 * R4 전용 인스턴스. `eslint.config.js` 를 **로드하지 않는다** —
 * 줄수 규칙은 이 테스트가 들고 있고 저장소 설정은 건드리지 않는다.
 * `noInlineConfig` 로 소스의 `eslint-disable` 주석 우회를 무력화한다(현재 57건 존재).
 */
const ratchetEslint = new ESLint({
  cwd: WEB_ROOT,
  overrideConfigFile: true,
  overrideConfig: [
    { ignores: RATCHET_IGNORES },
    {
      files: ['**/*.{ts,tsx}'],
      languageOptions: {
        // ★파서를 빠뜨리면 .tsx 가 통째로 파싱 실패하고 히트 0 이 「깨끗함」으로 읽힌다.
        //   2026-08-11 이 세션의 1차 측정이 정확히 그 상태였다(1288 중 1222 파일 파싱 실패).
        parser: tseslint.parser,
        parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
      },
      linterOptions: { noInlineConfig: true },
      rules: { 'max-lines-per-function': ['error', { max: MAX_COMPONENT_LINES }] },
    },
  ],
})

/** `max-lines-per-function` 메시지 형식. 예) `Function 'IssueDetailPage' has too many lines (1041).` */
const LINES_RE = /^(.*?) has too many lines \((\d+)\)/

/** 한 파일에서 200줄을 넘긴 함수들. `entries` 는 키→줄수, `dupes` 는 키가 겹친 목록. */
interface OversizedScan {
  readonly entries: Map<string, number>
  readonly dupes: readonly string[]
}

/**
 * 위반 메시지에서 「서술자」와 「줄수」를 뜯어낸다.
 * 형식이 바뀌면 조용히 0건으로 넘어가지 않도록 **던진다** — 침묵이 가짜 그린을 만든다.
 */
const parseOversizedMessage = (message: string): { descriptor: string; lines: number } => {
  const matched = LINES_RE.exec(message)
  const descriptor = matched?.[1]
  const rawLines = matched?.[2]
  if (descriptor === undefined || rawLines === undefined) {
    throw new Error(`max-lines-per-function 메시지 형식이 바뀌었다. 파서를 갱신하라: ${message}`)
  }
  return { descriptor, lines: Number(rawLines) }
}

async function computeOversizedFunctions(): Promise<OversizedScan> {
  const results = await ratchetEslint.lintFiles(['src'])
  // ★집계 전에 파싱 실패 0 을 먼저 단언한다. 파서가 빠지면 히트 0 이 「깨끗함」으로 읽힌다.
  expect(
    results
      .flatMap((r) => r.messages)
      .filter((m) => m.fatal)
      .map((m) => m.message),
  ).toEqual([])
  expect(results.length).toBeGreaterThan(MIN_SCANNED_FILES)

  const entries = new Map<string, number>()
  const dupes: string[] = []
  for (const result of results) {
    // ★상대 경로 — worktree·CI 의 절대경로가 달라 베이스라인 키가 통째로 어긋난다.
    const rel = result.filePath.replace(`${WEB_ROOT}/`, '')
    for (const m of result.messages) {
      if (m.ruleId !== 'max-lines-per-function') continue
      const { descriptor, lines } = parseOversizedMessage(m.message)
      const key = `${rel}::${descriptor}`
      if (entries.has(key)) dupes.push(key)
      entries.set(key, lines)
    }
  }
  return { entries, dupes }
}

/**
 * ★판정 3개가 각자 부르면 600파일 린트가 3번 돈다.
 * 모듈 레벨에서 **한 번만** 계산해 돌려쓴다. `beforeAll` 이 아니라 메모화된 Promise 인 이유는
 * R3 쪽 헬퍼와 호출 시점이 달라도 같은 결과를 공유해야 하기 때문이다.
 */
let oversizedCache: Promise<OversizedScan> | undefined
const oversizedFunctions = (): Promise<OversizedScan> =>
  (oversizedCache ??= computeOversizedFunctions())

describe('R4. 컴포넌트 200줄 래칫 (단조)', () => {
  it('베이스라인에 없는 신규 위반이 없다', async () => {
    const { entries } = await oversizedFunctions()
    expect(entries.size).toBeGreaterThan(0) // 비-공허. 스캔이 비면 모든 단언이 참이 된다
    // ★실패 출력을 베이스라인에 **그대로 붙여 넣을 수 있는 형태**로 만든다.
    //   손으로 옮겨 적으면 숫자가 틀리고, 틀린 숫자는 그만큼의 증가 여지로 남는다.
    const unknown = [...entries]
      .filter(([k]) => !(k in OVERSIZED_FUNCTION_BASELINE))
      .map(([k, lines]) => `${JSON.stringify(k)}: ${lines},`)
      .sort()
    // 목록 전수 비교 — 개수 상한은 하나 고치고 하나 늘리면 통과한다.
    expect(unknown).toEqual([])
  }, 60_000)

  it('베이스라인 대비 늘어난 함수가 없다', async () => {
    const { entries } = await oversizedFunctions()
    const grown: string[] = []
    for (const [key, lines] of entries) {
      const frozen = OVERSIZED_FUNCTION_BASELINE[key]
      if (frozen === undefined) continue // 신규 항목은 앞 판정의 몫이다
      if (lines > frozen) grown.push(`${key}: ${frozen} → ${lines}`)
    }
    expect(grown.sort()).toEqual([])
  }, 60_000)

  it('같은 키가 두 번 나오지 않는다 (키 충돌 감지)', async () => {
    // 익명 화살표가 한 파일에 둘 이상 200줄을 넘기면 키가 겹쳐 하나가 조용히 사라진다.
    expect((await oversizedFunctions()).dupes).toEqual([])
  }, 60_000)
})
