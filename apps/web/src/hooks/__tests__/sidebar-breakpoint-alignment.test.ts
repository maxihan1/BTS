// 모바일 판정 미디어쿼리(JS)와 컴포넌트가 쓰는 Tailwind 변형(CSS)이 같은 경계를 가리키는지 묶는 판별식
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { resolve, relative, sep } from 'node:path'
import { MOBILE_MEDIA_QUERY, MOBILE_TAILWIND_VARIANT } from '../use-sidebar-drawer'

const SRC = resolve(__dirname, '../..')

function read(rel: string): string {
  return readFileSync(resolve(SRC, rel), 'utf-8')
}

/** Tailwind 상한 변형(`max-<브레이크포인트>:`) — 이 중 우리가 쓰기로 한 경계는 하나뿐이다 */
const MAX_VARIANT_RE = /\bmax-(sm|md|lg|xl|2xl):/g

function isProductionSource(rel: string): boolean {
  if (!/\.(ts|tsx)$/.test(rel)) return false
  if (rel.includes(`__tests__${sep}`)) return false
  if (/\.(test|spec)\.tsx?$/.test(rel)) return false
  if (rel.startsWith(`test${sep}`) || rel.startsWith(`mocks${sep}`)) return false
  return true
}

/**
 * 모바일 상한 변형을 쓰는 프로덕션 파일을 **도출**한다.
 *
 * 🛑 이 목록을 하드코딩하지 마라. 종전에는 세 파일을 손으로 적어 뒀는데, 같은 PR 이
 *    `AccountMenu.tsx` 에 `max-md:hidden` 을 추가하면서 **목록과 실제가 그 자리에서
 *    어긋났다.** 판별식이 스스로 방어한다고 선언한 양식(두 목록이 서로를 검사하지 않는다)에
 *    자기가 걸린 것이다. 도출로 두면 새 파일이 자동 편입된다.
 */
function filesUsingMaxVariant(dir: string = SRC): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules') continue
    const abs = resolve(dir, entry)
    if (statSync(abs).isDirectory()) {
      out.push(...filesUsingMaxVariant(abs))
      continue
    }
    const rel = relative(SRC, abs)
    if (!isProductionSource(rel)) continue
    if (new RegExp(MAX_VARIANT_RE.source).test(readFileSync(abs, 'utf-8'))) out.push(rel)
  }
  return out
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

  it('상한 변형을 쓰는 프로덕션 파일은 전부 MOBILE_TAILWIND_VARIANT 하나만 쓴다 (경계 이원화 차단)', () => {
    const offenders: string[] = []
    for (const rel of filesUsingMaxVariant()) {
      const used = new Set(
        [...read(rel).matchAll(new RegExp(MAX_VARIANT_RE.source, 'g'))].map((m) => `max-${m[1]}`),
      )
      used.delete(MOBILE_TAILWIND_VARIANT)
      if (used.size > 0) offenders.push(`${rel} → ${[...used].join(' ')}`)
    }
    expect(
      offenders,
      '모바일 경계가 둘로 갈렸다 — JS 는 MOBILE_MEDIA_QUERY 하나만 보는데 CSS 가 다른 경계를 쓴다:\n' +
        `${offenders.join('\n')}`,
    ).toEqual([])
  })

  it('스캐너가 실제로 파일을 찾는다 (0건 스캔으로 통과하는 것을 막는다)', () => {
    // 도출이 조용히 빈 배열을 내면 위 단언은 무엇을 넣어도 통과한다.
    const found = filesUsingMaxVariant()
    expect(found.length).toBeGreaterThan(0)
    // ★이 세 파일은 실제로 `max-md:` 를 싣는다. 여기서 빠지면 도출이 고장 났거나 그 파일이
    //   모바일 분기를 잃은 것이다.
    //   `AccountMenu.tsx` 를 굳이 못박는 이유 — 종전 하드코딩 목록이 **양방향으로 틀려
    //   있었다.** `max-md:` 를 안 쓰는 `ShellLayout.tsx`(백드롭은 데스크톱 쪽 `md:hidden` 이다)를
    //   넣어 두고, 실제로 쓰는 이 파일은 빠뜨렸다. 손으로 적은 목록이 어떻게 어긋나는지의 실증이다.
    for (const expected of ['Sidebar.tsx', 'TopBar.tsx', 'AccountMenu.tsx']) {
      expect(
        found.some((rel) => rel.endsWith(expected)),
        `도출 결과에 ${expected} 가 없다 — 스캐너가 고장 났거나 그 파일이 모바일 분기를 잃었다.`,
      ).toBe(true)
    }
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
