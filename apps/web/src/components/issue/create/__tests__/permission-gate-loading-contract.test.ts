// 게이트 훅 소비처가 자기 로딩 프레임 계약을 선언했는지 강제하는 판별식 (부채 매핑 15·37·40·43)
//
// ★이 판별식이 재는 것은 「세 화면이 **같은** 계약을 따르는가」가 **아니다.**
// 소비처마다 계약이 다르고, 그게 맞다.
//   · 임포트 라우트 — 로딩 중엔 판정을 하나도 안 그린다. 존재(404)와 권한 두 판정이 경쟁해
//     먼저 정착한 쪽이 **틀린 사유를 먼저** 보여 주던 실제 결함이 있었다.
//   · IssueCreateForm · IssueMetaPanel — 로딩 중에도 폼과 액션이 **열려 있어야** 한다.
//     미지를 거부로 읽으면 CREATE 를 실제로 가진 사용자를 영구 차단한다(훅 KDoc).
// 폐기된 예약 PR #370 본문이 「소비처 3곳 전부가 같은 계약을 따른다」를 완료 조건으로 적었다.
// 그대로 구현하면 회귀다. 그 오류를 버리고 **선언 강제**만 살렸다.
//
// ★재는 것은 셋이다.
//   ① 소비처가 전부 어느 쪽 계약을 따르는지 **선언돼 있는가** (매핑 15 · 양방향 차집합)
//   ② 선언한 **계약 값**을 그 화면의 테스트가 **제목으로 떠안고 있는가** (매핑 37)
//   ③ 소비처 **탐지**가 배럴·확장자·동적 import·제출 훅 경유를 놓치지 않는가 (매핑 40·43)
// 지금 소비처는 3곳인데 목록을 손으로 관리하면 4번째 소비처에서 재발한다 —
// 이 저장소의 지배 결함 양식(두 목록이 서로를 검사하지 않는다)이라 처방도 표준 그대로다.
// **차집합 판별식 + 비-공허 짝 + CI 배선.**
//
// ★CI 배선을 `scripts/` 가 아니라 여기 둔 이유 (Maxi 확정 2026-08-14).
// `workflow-scripts-ci.yml` 의 `paths` 는 `apps/web/**` 를 **의도적으로** 걸지 않는다(러너 1대 큐).
// 판별식을 거기 두면 **4번째 소비처를 추가하는 바로 그 PR 에서 0회 실행**된다.
// `frontend-ci` 는 `apps/web/**` 를 통째로 걸므로 여기 두면 반드시 돈다.
//
// ★판정 로직은 전부 **순수 함수**로 빼고 픽스처 대조군을 붙였다 (매핑 37·40·43 착수 시 확정).
// 인라인 단언은 대조군이 없어 「판정을 지워도 초록」이 된다 — #385 에서 실측한 양식이다.
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, resolve, sep } from 'node:path'

/**
 * 로딩 프레임 계약 2종.
 *
 * - `no-verdict-while-loading` — 권한/존재가 정착하기 전에는 **판정을 하나도 그리지 않는다**.
 * - `open-while-loading` — 정착 전에도 폼·액션을 **그대로 연다**(미지 ≠ 거부).
 */
type LoadingFrameContract = 'no-verdict-while-loading' | 'open-while-loading'

/** 계약 리터럴 전량. 아래 ②의 제목 매칭이 이 값을 그대로 쓴다. */
const ALL_CONTRACTS: readonly LoadingFrameContract[] = [
  'no-verdict-while-loading',
  'open-while-loading',
]

interface ConsumerContract {
  /** 그 화면이 따르는 계약 */
  contract: LoadingFrameContract
  /**
   * 그 화면의 **로딩 프레임 단언이 사는** 테스트 파일.
   *
   * ★규약으로 유도하지 않는다. 세 소비처가 서로 다른 규약을 쓴다 —
   * `__tests__/IssueCreateForm.test.tsx` 는 하위 디렉터리, `IssueMetaPanel.test.tsx` 는 동석,
   * 라우트는 `.test.tsx` 접미. 게다가 `IssueMetaPanel` 은 테스트 파일이 **3개**이고
   * 게이트 단언은 그중 하나에만 있다. 유도하면 조용히 틀린 파일을 읽고 초록이 된다.
   */
  testFile: string
}

/**
 * 게이트 훅 소비처 → 그 화면이 따르는 계약 + 그 계약을 떠안는 테스트 파일.
 *
 * **새 소비처를 만들면 여기에 한 줄 추가한다.** 안 하면 아래 「선언되지 않은 소비처」가 red 다.
 * 반대로 소비처가 아닌 경로를 남겨 두면 「썩은 선언」이 red 다. 양방향이라 한쪽으로 못 샌다.
 */
const LOADING_FRAME_CONTRACTS: Readonly<Record<string, ConsumerContract>> = {
  // 존재(404)와 권한 두 판정이 경쟁한다. 먼저 정착한 쪽을 그리면 사용자가 틀린 사유를 먼저 읽는다.
  'src/routes/projects.$projectKey.settings.import.tsx': {
    contract: 'no-verdict-while-loading',
    testFile: 'src/routes/projects.$projectKey.settings.import.test.tsx',
  },
  // 제출 차단 + 「권한이 없습니다」는 **사실 주장**이라, 미지를 거부로 읽으면 거짓 문구가 된다.
  'src/components/issue/IssueCreateForm.tsx': {
    contract: 'open-while-loading',
    testFile: 'src/components/issue/__tests__/IssueCreateForm.test.tsx',
  },
  // 클론 액션 노출. 로딩 중 숨기면 권한 있는 사용자에게서 액션이 깜빡였다 나타난다.
  'src/components/issue/IssueMetaPanel.tsx': {
    contract: 'open-while-loading',
    testFile: 'src/components/issue/IssueMetaPanel.test.tsx',
  },
}

/**
 * 소비처로 세는 모듈 needle.
 *
 * ★초판은 `'useIssueCreatePermissionGate' + '('` 로 **호출부**를 봤고, 게이트 2 리뷰가
 *   실행으로 그 구멍을 열었다 — `import { useIssueCreatePermissionGate as useGate }` 뒤
 *   `useGate(k)` 로 쓰면 이름 뒤에 `(` 가 없어 **판별식이 6/6 초록**이었다.
 *   그래서 2판은 import 문만 봤고, 그때 남긴 구멍 둘을 여기서 닫는다.
 *
 * ★`use-issue-create-submit` 을 세는 이유 (부채 매핑 `43`).
 *   그 훅의 `isCreateExplicitlyDenied` 는 **평범한 boolean 파라미터**다. 새 화면이
 *   `useIssueCreateSubmit(state, { isCreateExplicitlyDenied: false })` 로 쓰면 게이트 훅을
 *   **한 번도 안 들여오고** 무게이트 제출 경로가 생긴다 — 2판 판정식은 그 화면을 못 본다.
 *   제출 훅을 소비처로 세면 그 화면도 계약 선언을 강요당한다. #385 분할이 만든 경로이지 선재가 아니다.
 *
 * ★배럴을 세는 이유 (부채 매핑 `40`). `create/index.ts` 가 훅을 re-export 하면
 *   화면이 `from '@/components/issue/create'` 로 들여와 **2판 판정식에서 사라진다**.
 *   지금 배럴은 없다(실측) — 잠재를 미리 닫는다.
 */
const CONSUMER_MODULE_NEEDLES: readonly string[] = [
  'use-issue-create-permission-gate',
  'use-issue-create-submit',
]

/**
 * 배럴 경로. **끝이거나 `/index` 로만** 이어질 때 배럴로 센다.
 *
 * `components/issue/create` 를 부분일치로 쓰면 `create/issue-create-schema` 를 들여오는
 * 무관한 화면까지 소비처가 된다 — 계약과 상관없는 파일에 선언을 강요하는 오탐이다.
 */
const BARREL_PATH = 'components/issue/create'

/**
 * **모듈 지정자**를 전부 뽑는다 — `from '…'` · `import('…')` · `require('…')`.
 *
 * ★`from` 만 보던 2판이 놓친 것을 여기서 닫는다(매핑 `40`). 동적 import 와 `require` 는
 *   문법이 달라 `from` 이 아예 안 나온다.
 * ★모듈 지정자 문법을 요구하는 이유. 경로만 grep 하면 **KDoc 안의 경로 언급이 오탐**된다 —
 *   `routes/issues.index.tsx` 가 이 훅 파일을 「정본 논거」로 인용한다(실측).
 */
export function extractModuleSpecifiers(source: string): string[] {
  const pattern = /(?:\bfrom\s*|\bimport\s*\(\s*|\brequire\s*\(\s*)(['"])([^'"]+)\1/g
  const out: string[] = []
  for (const m of source.matchAll(pattern)) out.push(m[2] as string)
  return out
}

/**
 * 그 모듈 지정자가 게이트 계약 대상인가.
 *
 * 확장자 명시(`….js`)는 부분일치라 자동으로 함께 잡힌다 — 2판이 `['"]` 로 끝을 못 박아
 * 놓쳤던 자리다.
 */
export function isConsumerSpecifier(specifier: string): boolean {
  if (CONSUMER_MODULE_NEEDLES.some((needle) => specifier.includes(needle))) return true
  return specifier === BARREL_PATH
    || specifier.endsWith(`/${BARREL_PATH}`)
    || specifier.endsWith(`/${BARREL_PATH}/index`)
    || specifier.endsWith(`${BARREL_PATH}/index`)
}

/** 소스 한 벌이 게이트 계약 소비처인가 — 위 둘의 합성. */
export function isConsumerSource(source: string): boolean {
  return extractModuleSpecifiers(source).some(isConsumerSpecifier)
}

/**
 * 테스트 **제목**만 뽑는다 (`it` · `test` · `describe` · `it.each` 류 포함).
 *
 * ★주석을 세지 않는 것이 이 함수의 전부다 (매핑 `37`).
 *   계약 리터럴이 「파일 어딘가에 있으면 통과」면 **주석 한 줄로 만족되는 판정**이 된다 —
 *   저장소 메모리 `[[invariant-satisfied-by-helptext-not-logic]]` 가 적발한 그 양식이고,
 *   실제로 착수 시점의 `IssueMetaPanel.test.tsx` 는 계약명을 **주석에만** 갖고 있었다.
 *   제목은 실행되는 테스트의 이름이라 지우면 테스트가 함께 사라진다.
 */
export function extractTestTitles(source: string): string[] {
  const pattern = /(?:^|[^.\w$])(?:it|test|describe)(?:\.\w+)*\s*\(\s*(['"`])((?:\\.|(?!\1)[\s\S])*?)\1/g
  const out: string[] = []
  for (const m of source.matchAll(pattern)) out.push(m[2] as string)
  return out
}

/**
 * 선언한 계약을 그 테스트 파일이 **제목으로** 떠안고 있는가.
 *
 * 다른 계약명을 적어 둔 것으로는 만족되지 않는다 — 계약 값을 바꾸면 제목도 함께 바꿔야 하고,
 * 제목을 바꾸려면 그 테스트가 실제로 무엇을 재는지 다시 보게 된다. 그것이 이 판정의 목적이다.
 */
export function declaresContractInTitle(
  testSource: string,
  contract: LoadingFrameContract,
): boolean {
  return extractTestTitles(testSource).some((title) => title.includes(contract))
}

/**
 * 훅 **정의** 파일들 — 소비처가 아니다.
 *
 * ★배럴 needle 이 들어오면서 3판에서도 여전히 도달 불가다. 정의 파일은 자기를 import 하지
 * 않고 배럴도 아직 없다. 그 사실을 숨기지 않고 적어 둔다 — 「보호가 있다」고 읽히면 다음
 * 사람이 틀린 근거로 안심한다(#383 C4 · 게이트 2 재리뷰 지적).
 * 지우지 않는 이유는 배럴이 실제로 생기면 그 배럴이 정의 파일을 re-export 하면서
 * **정의 파일 자신이 배럴을 import 하는 순환**이 흔히 생기기 때문이다.
 */
const DEFINITION_FILES: readonly string[] = [
  'src/components/issue/create/use-issue-create-permission-gate.ts',
  'src/components/issue/create/use-issue-create-submit.ts',
]

const WEB_ROOT = resolve(__dirname, '../../../../..')
const SRC = join(WEB_ROOT, 'src')

/** `src` 아래 모든 `.ts`/`.tsx` 를 훑되 **테스트 파일은 제외**한다(EC7). */
function collectSourceFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      collectSourceFiles(full, acc)
      continue
    }
    if (!full.endsWith('.ts') && !full.endsWith('.tsx')) continue
    if (/\.(test|spec)\.tsx?$/.test(full)) continue
    acc.push(full)
  }
  return acc
}

/** 저장소 상대 경로로 정규화한다 (Windows 구분자도 `/` 로 통일). */
function toRelative(full: string): string {
  return relative(WEB_ROOT, full).split(sep).join('/')
}

/**
 * 실제 소비처 집합과 선언 집합의 **양방향 차집합**.
 *
 * 순수 함수로 뺀 이유 — 소스 스캔 결과에 기대지 않으므로 파일이 어떻게 바뀌어도
 * 아래 대조군 3종이 살아 있다. 스캔이 0건을 돌려주는 사고가 나도 대조군은 red 를 유지한다.
 *
 * @param actual 스캐너가 찾은 실제 소비처 경로들
 * @param declared 레지스트리에 선언된 경로들
 * @returns `undeclared` = 쓰는데 선언 없음 · `stale` = 선언했는데 안 씀
 */
function diffConsumers(
  actual: readonly string[],
  declared: readonly string[],
): { undeclared: string[]; stale: string[] } {
  const actualSet = new Set(actual)
  const declaredSet = new Set(declared)
  return {
    undeclared: actual.filter((p) => !declaredSet.has(p)).sort(),
    stale: declared.filter((p) => !actualSet.has(p)).sort(),
  }
}

const sourceFiles = collectSourceFiles(SRC)
const consumers = sourceFiles
  .filter((f) => isConsumerSource(readFileSync(f, 'utf-8')))
  .map(toRelative)
  .filter((p) => !DEFINITION_FILES.includes(p))
  .sort()

describe('로딩 프레임 계약 — 소비처 선언 강제', () => {
  it('소스를 실제로 훑었다 (비-공허 짝)', () => {
    // 경로가 깨지면 0건을 훑고 아래 단언이 전부 공허하게 통과한다.
    expect(sourceFiles.length).toBeGreaterThan(400)
    // 스캐너가 「반드시 걸려야 하는 파일」을 실제로 찾았는가 — 탐지 로직 자체의 대조군.
    expect(consumers).toContain('src/components/issue/IssueCreateForm.tsx')
  })

  it('★게이트 훅을 쓰는 화면은 전부 자기 로딩 프레임 계약을 선언한다', () => {
    const { undeclared } = diffConsumers(consumers, Object.keys(LOADING_FRAME_CONTRACTS))

    expect(undeclared).toEqual([])
  })

  it('선언에 썩은 항목이 없다 (소비처가 사라지면 선언도 지울 것)', () => {
    const { stale } = diffConsumers(consumers, Object.keys(LOADING_FRAME_CONTRACTS))

    expect(stale).toEqual([])
  })
})

// ★여기부터가 매핑 `37` 이 연 구멍이다 — 2판은 선언의 **존재**만 재고 **값**은 아무도 안 읽었다.
//   #384 가 임포트 라우트에 두 번째 유보 규칙(진행 중 동결)을 넣었을 때 선언 문자열은 한 글자도
//   안 바뀌었고, 바꿔야 한다고 알려 주는 판정이 없었다.
describe('로딩 프레임 계약 — 선언한 계약 값이 테스트 제목을 떠안는다 (매핑 37)', () => {
  it('선언한 테스트 파일이 실재한다 (비-공허 짝)', () => {
    for (const [screen, { testFile }] of Object.entries(LOADING_FRAME_CONTRACTS)) {
      const titles = extractTestTitles(readFileSync(join(WEB_ROOT, testFile), 'utf-8'))
      // 파일을 읽었는데 제목이 0개면 추출기가 고장 난 것이다 — 아래 단언이 통째로 공허해진다.
      expect(titles.length, `${screen} → ${testFile} 에서 테스트 제목을 하나도 못 뽑았다`)
        .toBeGreaterThan(0)
    }
  })

  it('★선언한 계약 값이 그 화면 테스트의 제목에 있다 (주석은 세지 않는다)', () => {
    const missing = Object.entries(LOADING_FRAME_CONTRACTS)
      .filter(([, { contract, testFile }]) =>
        !declaresContractInTitle(readFileSync(join(WEB_ROOT, testFile), 'utf-8'), contract))
      .map(([screen, { contract, testFile }]) => `${screen} (${contract}) → ${testFile}`)

    expect(missing).toEqual([])
  })
})

describe('로딩 프레임 계약 — 차집합 판정 대조군 (비-공허 짝)', () => {
  // ★픽스처 경로는 실물과 겹치지 않는 가짜다. 실물을 쓰면 실물이 바뀔 때 대조군이 함께 죽는다.
  const A = 'src/fixture/a.tsx'
  const B = 'src/fixture/b.tsx'

  it('쓰는데 선언이 없으면 잡는다 (4번째 화면이 조용히 생기는 경우)', () => {
    expect(diffConsumers([A, B], [A])).toEqual({ undeclared: [B], stale: [] })
  })

  it('선언했는데 안 쓰면 잡는다 (화면을 지우고 선언만 남긴 경우)', () => {
    expect(diffConsumers([A], [A, B])).toEqual({ undeclared: [], stale: [B] })
  })

  it('정확히 일치하면 위반 0 이다 (오탐 대조군)', () => {
    expect(diffConsumers([A, B], [B, A])).toEqual({ undeclared: [], stale: [] })
  })
})

describe('소비처 탐지 대조군 — 2판이 놓친 경로들 (매핑 40·43)', () => {
  it('평범한 import 를 잡는다 (기준선)', () => {
    expect(isConsumerSource(`import { useIssueCreatePermissionGate } from '@/components/issue/create/use-issue-create-permission-gate'`)).toBe(true)
  })

  it('★alias 를 써도 잡는다 (2판이 이것 때문에 호출부 매칭을 버렸다)', () => {
    expect(isConsumerSource(`import { useIssueCreatePermissionGate as useGate } from '../create/use-issue-create-permission-gate'\nconst v = useGate(k)`)).toBe(true)
  })

  it('★확장자를 명시해도 잡는다 (2판 구멍 · 매핑 40)', () => {
    expect(isConsumerSource(`import { x } from './create/use-issue-create-permission-gate.js'`)).toBe(true)
  })

  it('★동적 import 도 잡는다 (2판 구멍 · 매핑 40)', () => {
    expect(isConsumerSource(`const m = await import('@/components/issue/create/use-issue-create-permission-gate')`)).toBe(true)
  })

  it('★require 도 잡는다 (2판 구멍 · 매핑 40)', () => {
    expect(isConsumerSource(`const m = require('@/components/issue/create/use-issue-create-permission-gate')`)).toBe(true)
  })

  it('★배럴 재export 경유도 잡는다 (2판 구멍 · 매핑 40 — 지금은 잠재)', () => {
    expect(isConsumerSource(`import { useIssueCreatePermissionGate } from '@/components/issue/create'`)).toBe(true)
    expect(isConsumerSource(`import { useIssueCreatePermissionGate } from '@/components/issue/create/index'`)).toBe(true)
  })

  it('★제출 훅만 들여와도 잡는다 (매핑 43 — 게이트 훅을 한 번도 안 부르는 무게이트 경로)', () => {
    // 이것이 #385 분할이 연 경로다. 게이트 훅 import 가 0 이라 2판은 이 화면을 못 봤다.
    const bypass = [
      `import { useIssueCreateSubmit } from '@/components/issue/create/use-issue-create-submit'`,
      `const submit = useIssueCreateSubmit(state, { isCreateExplicitlyDenied: false })`,
    ].join('\n')

    expect(isConsumerSource(bypass)).toBe(true)
  })

  it('배럴 하위의 무관한 모듈은 소비처로 세지 않는다 (오탐 대조군)', () => {
    expect(isConsumerSource(`import { schema } from '@/components/issue/create/issue-create-schema'`)).toBe(false)
  })

  it('KDoc 이 경로를 인용하기만 하면 세지 않는다 (오탐 대조군 · 실측 사례)', () => {
    expect(isConsumerSource(`// 정본 논거는 create/use-issue-create-permission-gate 의 KDoc 이다`)).toBe(false)
  })

  it('무관한 import 는 세지 않는다 (오탐 대조군)', () => {
    expect(isConsumerSource(`import { useQuery } from '@tanstack/react-query'`)).toBe(false)
  })
})

describe('계약 제목 판정 대조군 — 주석으로는 만족되지 않는다 (매핑 37)', () => {
  it('제목에 계약명이 있으면 통과한다 (기준선)', () => {
    const src = `it('★로딩 중에도 클론이 열려 있다 (open-while-loading)', async () => {})`

    expect(declaresContractInTitle(src, 'open-while-loading')).toBe(true)
  })

  it('★주석에만 있으면 통과하지 않는다 (착수 시점 IssueMetaPanel 의 실제 형태)', () => {
    const src = [
      `it('★권한 조회가 진행 중인 프레임에서도 클론이 열려 있다', async () => {`,
      `  // ★계약명 \`open-while-loading\` — 소비처 레지스트리가 선언한 그 계약이다.`,
      `})`,
    ].join('\n')

    expect(declaresContractInTitle(src, 'open-while-loading')).toBe(false)
  })

  it('★다른 계약명으로는 만족되지 않는다 (복제 회귀 방지)', () => {
    const src = `it('로딩 중 판정을 안 그린다 (no-verdict-while-loading)', async () => {})`

    expect(declaresContractInTitle(src, 'open-while-loading')).toBe(false)
  })

  it('describe 제목도 센다 (계약은 블록 단위로 선언되기도 한다)', () => {
    const src = `describe('임포트 페이지 — 로딩 프레임 (no-verdict-while-loading)', () => {})`

    expect(declaresContractInTitle(src, 'no-verdict-while-loading')).toBe(true)
  })

  it('제목이 하나도 없으면 통과하지 않는다 (빈 파일 대조군)', () => {
    expect(declaresContractInTitle('', 'open-while-loading')).toBe(false)
  })

  it('계약 리터럴 2종이 서로를 만족시키지 않는다 (전수 교차 대조군)', () => {
    for (const declared of ALL_CONTRACTS) {
      for (const written of ALL_CONTRACTS) {
        const src = `it('로딩 프레임 (${written})', () => {})`

        expect(declaresContractInTitle(src, declared)).toBe(declared === written)
      }
    }
  })
})
