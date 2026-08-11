// apps/web 강제 수단 래칫 2종의 계약 테스트 — R3 placeholder 규칙 발화 확인 + R4 줄수 베이스라인 판정
import { describe, expect, it } from 'vitest'
import { ESLint } from 'eslint'
import { resolve } from 'node:path'

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
