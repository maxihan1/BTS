// 모바일 판정 미디어쿼리(JS)와 컴포넌트가 쓰는 Tailwind 변형(CSS)이 같은 경계를 가리키는지 묶는 판별식
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { MOBILE_MEDIA_QUERY, MOBILE_TAILWIND_VARIANT } from '../use-sidebar-drawer'

const SRC = resolve(__dirname, '../..')

/**
 * 모바일 분기를 **양쪽**으로 표현하는 파일들.
 *
 * JS 는 `MOBILE_MEDIA_QUERY` 로 상태를 고르고, CSS 는 `max-md:` 로 배치를 고른다.
 * 한쪽만 바꾸면 두 판정이 어긋나는 폭 구간이 생기는데, 그 구간은 jsdom(레이아웃 없음)에서
 * 재현되지 않아 어떤 단위 테스트에도 걸리지 않는다.
 */
const FILES_USING_MOBILE_VARIANT = [
  'components/layout/Sidebar.tsx',
  'components/layout/TopBar.tsx',
  'components/layout/ShellLayout.tsx',
]

function read(rel: string): string {
  return readFileSync(resolve(SRC, rel), 'utf-8')
}

describe('사이드바 모바일 브레이크포인트 — JS 미디어쿼리와 Tailwind 변형이 같은 경계를 가리킨다', () => {
  it('MOBILE_TAILWIND_VARIANT 는 Tailwind `md`(768px) 미만을 뜻하는 `max-md` 다', () => {
    expect(MOBILE_TAILWIND_VARIANT).toBe('max-md')
  })

  it('MOBILE_MEDIA_QUERY 의 상한이 Tailwind md 브레이크포인트(768px) 바로 아래다', () => {
    const match = /\(max-width:\s*([\d.]+)px\)/.exec(MOBILE_MEDIA_QUERY)
    expect(match, `미디어쿼리 형식이 바뀌었다: ${MOBILE_MEDIA_QUERY}`).not.toBeNull()

    const upperBound = Number(match?.[1])
    // md = 768px. `max-md` 는 767.98px 까지 매칭되므로 JS 상한도 768 미만이어야 한다.
    expect(upperBound).toBeLessThan(768)
    expect(upperBound).toBeGreaterThanOrEqual(767)
  })

  it('모바일 분기를 쓰는 파일은 전부 MOBILE_TAILWIND_VARIANT 접두사만 쓴다 (다른 브레이크포인트 혼입 차단)', () => {
    const offenders: string[] = []
    for (const rel of FILES_USING_MOBILE_VARIANT) {
      const source = read(rel)
      // `max-sm:` `max-lg:` 등 다른 상한 변형이 섞이면 경계가 둘이 된다.
      const otherMaxVariants = source.match(/\bmax-(sm|lg|xl|2xl):/g)
      if (otherMaxVariants !== null) {
        offenders.push(`${rel} → ${[...new Set(otherMaxVariants)].join(' ')}`)
      }
    }
    expect(offenders, `모바일 경계가 둘로 갈렸다:\n${offenders.join('\n')}`).toEqual([])
  })

  it('Sidebar 는 max-md 오프캔버스 클래스를 실제로 싣는다 (판정만 있고 배치가 없는 공허 통과 차단)', () => {
    const source = read('components/layout/Sidebar.tsx')
    expect(source).toContain(`${MOBILE_TAILWIND_VARIANT}:fixed`)
    expect(source).toContain(`${MOBILE_TAILWIND_VARIANT}:-translate-x-full`)
    // 닫힘에서 포커스가 화면 밖 링크로 새지 않도록 invisible 을 함께 건다.
    expect(source).toContain(`${MOBILE_TAILWIND_VARIANT}:invisible`)
  })

  it('ShellLayout 백드롭은 데스크톱에서 렌더되지 않도록 md:hidden 을 단다', () => {
    const source = read('components/layout/ShellLayout.tsx')
    expect(source).toContain('md:hidden')
  })
})
