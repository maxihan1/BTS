// 인라인 animate-pulse 재정의 금지를 소스 전수 스캔으로 강제하는 회귀 가드 (FR-UX-06 PR22)
import { readFileSync, globSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, it, expect } from 'vitest'

/** apps/web/src 루트 — 이 파일 기준 ../.. */
const SRC_ROOT = resolve(import.meta.dirname, '../..')

/**
 * 스캔 대상 = 프로덕션 .tsx 전량.
 * 제외 2종. ①테스트 파일(픽스처가 스켈레톤을 흉내 낼 수 있다)
 * ②`components/ui/skeleton.tsx` — 프리미티브 **자체**가 animate-pulse의 유일한 정의처다.
 */
const FILES = globSync('**/*.tsx', { cwd: SRC_ROOT }).filter(
  (f) => !f.endsWith('.test.tsx') && !f.endsWith('components/ui/skeleton.tsx'),
)

describe('FR-UX-06 PR22 — 로딩 스켈레톤은 프리미티브만 쓴다 (디자인 스펙 §7 인라인 재정의 금지)', () => {
  it('스캔 대상 파일이 실제로 존재한다 (glob 실패로 인한 공허 통과 차단)', () => {
    expect(FILES.length).toBeGreaterThan(100)
  })

  it('animate-pulse 인라인 재정의가 0건이다', () => {
    const offenders = FILES.filter((f) =>
      readFileSync(resolve(SRC_ROOT, f), 'utf-8').includes('animate-pulse'),
    )
    // 개수 상한이 아니라 **목록 전수 비교** — 개수 가드는 새 위반이 늘어도 숫자만 올리면 통과한다.
    expect(offenders).toEqual([])
  })

  it('로컬에 Skeleton이라는 이름을 재정의하지 않는다 (프리미티브와 이름 충돌)', () => {
    const offenders = FILES.filter((f) =>
      /function Skeleton\b|const Skeleton\s*[:=]/.test(readFileSync(resolve(SRC_ROOT, f), 'utf-8')),
    )
    expect(offenders).toEqual([])
  })
})
