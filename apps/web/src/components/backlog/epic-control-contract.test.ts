// 에픽 컨트롤 테스트 계약 회귀 봉인 — 짝 테스트 2파일의 로컬 복사본 부활을 막는다 (FR-UX-13 F16 후속 ⑦)
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

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
 * `const X =` / `function X(` 형태의 **지역 정의**를 찾는다.
 *
 * `import { X } from …` 은 정의가 아니므로 잡히면 안 된다 — 그 구분이 이 판별식의 전부다.
 * 접미사 오탐 없음. `EPIC_ALPHA` 로 `const EPIC_ALPHA_NAME =` 를 찾으면
 * `EPIC_ALPHA` 뒤가 `_` 라 `\s*[:=]` 에 걸리지 않는다.
 */
function hasLocalDefinition(source: string, symbol: string): boolean {
  return [
    new RegExp(`^\\s*(?:export\\s+)?const\\s+${symbol}\\s*[:=]`, 'm'),
    new RegExp(`^\\s*(?:export\\s+)?function\\s+${symbol}\\s*\\(`, 'm'),
  ].some((re) => re.test(source))
}

describe('에픽 컨트롤 테스트 계약 — 단일 정본 봉인', () => {
  it.each(PAIRED_TEST_FILES)('%s 는 공유 심볼을 지역 정의하지 않는다', (fileName) => {
    const source = readPaired(fileName)
    const redefined = SHARED_SYMBOLS.filter((symbol) => hasLocalDefinition(source, symbol))
    expect(redefined).toEqual([])
  })

  it.each(PAIRED_TEST_FILES)('%s 는 공유 계약 모듈에서 읽는다', (fileName) => {
    expect(readPaired(fileName)).toContain(CONTRACT_MODULE)
  })

  // ★ 비-공허 짝 — 판별식이 자기 목적을 실제로 잡는지 증명한다.
  //   `active-project-contract.test.ts` 가 남긴 교훈(초안 판별식이 없애려던 대상을
  //   매치하지 못해 봉인이 무의미했다)의 직접 처방이다.
  it('판별식 비-공허 — 지역 정의는 잡고 import 는 안 잡는다', () => {
    expect(hasLocalDefinition(`const EPIC_CONTROL_ROLE = 'checkbox' as const`, 'EPIC_CONTROL_ROLE')).toBe(true)
    expect(hasLocalDefinition(`function queryEpicControls(): HTMLElement[] {`, 'queryEpicControls')).toBe(true)
    expect(hasLocalDefinition(`import { EPIC_CONTROL_ROLE } from '${CONTRACT_MODULE}'`, 'EPIC_CONTROL_ROLE')).toBe(false)
    expect(hasLocalDefinition(`const EPIC_ALPHA_NAME = '결제 개편'`, 'EPIC_ALPHA')).toBe(false)
  })
})
