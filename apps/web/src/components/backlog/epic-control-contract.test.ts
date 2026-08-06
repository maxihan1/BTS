// 에픽 컨트롤 테스트 계약 회귀 봉인 — 짝 테스트 2파일의 로컬 복사본 부활을 막는다 (FR-UX-13 F16 후속 ⑦)
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'
import * as contract from '@/test/backlog-epic-control-contract'

/** 이 파일이 사는 디렉토리 = 짝 테스트 2파일이 사는 곳 */
const BACKLOG_DIR = resolve(import.meta.dirname)

/**
 * 짝을 이루는 두 소비처.
 *
 * 한쪽만 고치면 나머지가 **존재하지 않는 role 을 0개 세며 영구 초록**이 되는 자리다.
 * 부재 단언(`toHaveLength(0)`)은 컴포넌트가 아무것도 안 그려도 통과하므로,
 * 두 파일이 **같은 셀렉터**를 써야 「어디에도 없음」과 「패널에만 있음」이 구분된다.
 */
const PAIRED_TEST_FILES = ['BacklogEpicPanel.test.tsx', 'BacklogFilterBar.test.tsx'] as const

/** 공유 계약 모듈이 단독 소유해야 하는 심볼 8종 */
const SHARED_SYMBOLS = [
  'EPIC_CONTROL_ROLE',
  'queryEpicControls',
  'EPIC_ALPHA',
  'EPIC_BETA',
  'EPIC_UNRESOLVED',
  'EPIC_ALPHA_NAME',
  'EPIC_BETA_NAME',
  'NO_EPIC_LABEL',
] as const

const CONTRACT_MODULE = '@/test/backlog-epic-control-contract'

function readPaired(fileName: string): string {
  return readFileSync(resolve(BACKLOG_DIR, fileName), 'utf8')
}

/**
 * `const`/`let`/`var X =` / `function X(` 형태의 **지역 정의**를 찾는다.
 *
 * `import { X } from …` 은 정의가 아니므로 잡히면 안 된다 — 그 구분이 이 판별식의 전부다.
 * 접미사 오탐 없음. `EPIC_ALPHA` 로 `const EPIC_ALPHA_NAME =` 를 찾으면
 * `EPIC_ALPHA` 뒤가 `_` 라 `\s*[:=]` 에 걸리지 않는다.
 */
function hasLocalDefinition(source: string, symbol: string): boolean {
  return [
    new RegExp(`^\\s*(?:export\\s+)?(?:const|let|var)\\s+${symbol}\\s*[:=]`, 'm'),
    new RegExp(`^\\s*(?:export\\s+)?function\\s+${symbol}\\s*\\(`, 'm'),
  ].some((re) => re.test(source))
}

/**
 * 짝 테스트가 공유 계약 모듈에서 **named import 한 지정자**만 뽑는다.
 *
 * 부분 문자열 일치를 쓰면 **주석에 적힌 모듈 경로**가 단언을 영구 만족시킨다
 * (실제로 그 결함으로 import 를 통째로 지워도 green 이었다). `import { … } from '<모듈>'`
 * 형태만 매치해 그 구멍을 닫는다.
 *
 * `import { X as Y }` 는 지정자가 `'X as Y'` 로 나와 선언 목록과 불일치 → **red** 다.
 * 별칭은 로컬 이름을 갈라 놓는 행위라 이 봉인이 막으려는 대상과 같다 — 통과시키지 않는다.
 */
function importedContractSymbols(source: string): string[] {
  const block = new RegExp(`import\\s*\\{([^}]*)\\}\\s*from\\s*'${CONTRACT_MODULE}'`).exec(source)
  return (block?.[1] ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
}

describe('에픽 컨트롤 테스트 계약 — 단일 정본 봉인', () => {
  it.each(PAIRED_TEST_FILES)('%s 는 공유 심볼을 지역 정의하지 않는다', (fileName) => {
    const source = readPaired(fileName)
    const redefined = SHARED_SYMBOLS.filter((symbol) => hasLocalDefinition(source, symbol))
    expect(redefined).toEqual([])
  })

  // ★ 여기는 원래 `readPaired(fileName).includes(CONTRACT_MODULE)` 였고 **영구초록**이었다 —
  //   이 파일들의 주석(`// 셀렉터 자산은 '<모듈>' 가 단독 소유한다.`)이 경로를 담고 있어
  //   import 를 통째로 지워도 통과했다. 지정자 목록을 8종과 정확히 대조해 그 구멍을 닫는다.
  //   `toEqual` 의 배열 diff 가 「어느 지정자가 빠졌나」를 그대로 보여 준다.
  it.each(PAIRED_TEST_FILES)('%s 는 공유 심볼 8종을 모두 named import 한다', (fileName) => {
    const specifiers = importedContractSymbols(readPaired(fileName))
    expect(specifiers.sort()).toEqual([...SHARED_SYMBOLS].sort())
  })

  // ★ 두 리스트가 서로를 검사하게 만든다 — 위 판별식은 import 줄의 **텍스트**만 보므로
  //   모듈이 비어 있어도 통과한다. 선언 목록과 실 export 표면의 **양방향 차집합**을 0 으로 못박는다.
  it('공유 모듈의 export 표면이 선언 목록과 정확히 일치한다', () => {
    expect(Object.keys(contract).sort()).toEqual([...SHARED_SYMBOLS].sort())
  })

  // ★ 비-공허 짝 — 판별식이 자기 목적을 실제로 잡는지 증명한다.
  //   `active-project-contract.test.ts` 가 남긴 교훈(초안 판별식이 없애려던 대상을
  //   매치하지 못해 봉인이 무의미했다)의 직접 처방이다.
  it('판별식 비-공허 — 지역 정의는 잡고 import 는 안 잡는다', () => {
    expect(
      hasLocalDefinition(`const EPIC_CONTROL_ROLE = 'checkbox' as const`, 'EPIC_CONTROL_ROLE'),
    ).toBe(true)
    expect(
      hasLocalDefinition(`function queryEpicControls(): HTMLElement[] {`, 'queryEpicControls'),
    ).toBe(true)
    expect(
      hasLocalDefinition(
        `import { EPIC_CONTROL_ROLE } from '${CONTRACT_MODULE}'`,
        'EPIC_CONTROL_ROLE',
      ),
    ).toBe(false)
    expect(hasLocalDefinition(`const EPIC_ALPHA_NAME = '결제 개편'`, 'EPIC_ALPHA')).toBe(false)
    // `const`/`function` 만 보면 `let`·`var` 복사본이 통째로 우회한다
    expect(hasLocalDefinition(`let EPIC_CONTROL_ROLE = 'checkbox'`, 'EPIC_CONTROL_ROLE')).toBe(true)
    expect(hasLocalDefinition(`var EPIC_CONTROL_ROLE = 'checkbox'`, 'EPIC_CONTROL_ROLE')).toBe(true)
    // 주석에 모듈 경로가 있어도 import 로 세지 않는다 — 이것이 영구초록의 직접 처방이다
    expect(importedContractSymbols(`// 셀렉터 자산은 '${CONTRACT_MODULE}' 가 단독 소유한다.`)).toEqual(
      [],
    )
    expect(
      importedContractSymbols(`import { EPIC_ALPHA, EPIC_BETA } from '${CONTRACT_MODULE}'`),
    ).toEqual(['EPIC_ALPHA', 'EPIC_BETA'])
  })
})
