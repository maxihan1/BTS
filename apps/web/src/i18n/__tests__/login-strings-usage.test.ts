// 폐기된 identifier-first 2단계 로케이터가 e2e 에 남지 않았는지 전수 스캔하는 회귀 가드
import { readFileSync, globSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/**
 * apps/web/e2e 루트 — 이 파일 기준 ../../../e2e.
 *
 * 이 가드가 필요한 이유. e2e 는 타입체크·린트 **양쪽 모두 밖**이다
 * (`tsconfig.app.json` 의 `include: ["src"]`, `package.json` 의 `lint: eslint src`).
 * 따라서 `loginStrings.continueButton` 을 i18n 에서 지워도 컴파일 오류가 나지 않고,
 * Playwright 런타임에 `getByRole('button', { name: undefined })` 가 되어
 * **아무 버튼이나 매칭**된다 — 조용한 가짜초록. src 안의 이 테스트만이 그것을 잡는다.
 */
const E2E_ROOT = resolve(import.meta.dirname, '../../../e2e')

const FILES = globSync('**/*.ts', { cwd: E2E_ROOT })

/** 1단계(이메일 선입력) 삭제와 함께 폐기된 심볼 — i18n/ko.ts 에서 제거된다 */
const RETIRED_SYMBOLS = ['loginStrings.continueButton', 'loginStrings.emailLabel'] as const

describe('로그인 단일화면 전환 — 폐기된 2단계 로케이터 잔존 금지', () => {
  it('스캔 대상 e2e 파일이 실제로 존재한다 (glob 실패로 인한 공허 통과 차단)', () => {
    expect(FILES.length).toBeGreaterThan(100)
  })

  it.each(RETIRED_SYMBOLS)('%s 를 참조하는 e2e 파일이 0건이다', (symbol) => {
    const offenders = FILES.filter((f) =>
      readFileSync(resolve(E2E_ROOT, f), 'utf-8').includes(symbol),
    )
    // 개수 상한이 아니라 **목록 전수 비교** — 개수 가드는 새 위반이 늘어도 숫자만 올리면 통과한다.
    expect(offenders).toEqual([])
  })
})
