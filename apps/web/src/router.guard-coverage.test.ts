// 라우트 가드 누락을 전수 스캔으로 막는 회귀 가드 — 「추가했는데 가드를 안 달아도 아무도 안 본다」 차단
import { describe, it, expect } from 'vitest'
import { routeTree } from './router'

/**
 * 이 판별식이 필요한 이유.
 *
 * 원 결함(`indexRoute` 가드 부재)을 고치면서 「60여 라우트 중 이 하나만」이라고 적었는데
 * **사실이 아니었다** — `workflowsKeyRoute` 도 같은 구멍이었고 코드리뷰가 잡았다.
 * 인스턴스만 고치면 다음 라우트에서 같은 일이 또 난다. 두 목록(라우트 정의 ↔ 가드 배선)이
 * 서로를 검사하지 않는 지배 결함 양식이라, 차집합을 기계가 매번 재계산해야 한다.
 *
 * 계약. 모든 경로 라우트는 **둘 중 하나**를 만족한다.
 * - `beforeLoad` 가 있다 (보호 라우트)
 * - `staticData.requireAuth === false` 를 **명시**한다 (의도적 공개 라우트)
 *
 * 레이아웃 라우트(`path` 없이 `id` 만 가진 pathless 라우트)는 대상이 아니다 —
 * URL 세그먼트에 기여하지 않아 직접 진입할 수 없다.
 */

interface RouteLike {
  id?: string
  children?: RouteLike[]
  options?: {
    path?: string
    beforeLoad?: unknown
    staticData?: { requireAuth?: boolean }
  }
}

/** routeTree 를 깊이 우선으로 평탄화한다 */
function flatten(route: RouteLike): RouteLike[] {
  const children = route.children ?? []
  return [route, ...children.flatMap(flatten)]
}

const ALL_ROUTES = flatten(routeTree as unknown as RouteLike)

/** 직접 진입 가능한 라우트 = `path` 를 가진 것. pathless 레이아웃은 제외 */
const PATH_ROUTES = ALL_ROUTES.filter((r) => typeof r.options?.path === 'string')

describe('라우트 가드 커버리지 — 미인증 진입이 뚫리는 경로가 0이다', () => {
  it('스캔 대상 라우트가 실제로 존재한다 (트리 순회 실패로 인한 공허 통과 차단)', () => {
    expect(PATH_ROUTES.length).toBeGreaterThan(50)
  })

  it('모든 경로 라우트가 beforeLoad 를 갖거나 requireAuth:false 를 명시한다', () => {
    const offenders = PATH_ROUTES.filter((r) => {
      const hasGuard = r.options?.beforeLoad !== undefined
      const explicitlyPublic = r.options?.staticData?.requireAuth === false
      return !hasGuard && !explicitlyPublic
    }).map((r) => r.options?.path ?? r.id ?? '(unknown)')

    // 개수 상한이 아니라 **목록 전수 비교** — 개수 가드는 새 위반이 늘어도 숫자만 올리면 통과한다.
    expect(offenders).toEqual([])
  })

  it('공개 라우트는 requireAuth:false 를 명시로만 얻는다 (누락과 구분되어야 한다)', () => {
    // `staticData` 자체가 없으면 「공개하기로 정했다」가 아니라 「아무도 안 봤다」다.
    // 이 둘이 코드에서 같은 모양이면 판별식이 무의미해진다.
    const publicRoutes = PATH_ROUTES.filter((r) => r.options?.staticData?.requireAuth === false)

    expect(publicRoutes.length).toBeGreaterThan(0)
    for (const r of publicRoutes) {
      expect(r.options?.staticData).toBeDefined()
    }
  })
})
