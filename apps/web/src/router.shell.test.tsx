// _shell pathless 재부모화(PR10) 구조 회귀 가드 — login은 셸 밖·보호 라우트는 셸 안·URL 불변
import { describe, it, expect } from 'vitest'
import { router } from './router'

// routesById 값 타입은 큰 union이라 회귀 가드에 필요한 필드만 좁혀서 읽는다.
const byId = router.routesById as Record<string, { parentRoute?: { id?: string }; fullPath?: string }>

describe('_shell 재부모화 (PR10)', () => {
  it('_shell pathless 라우트가 트리에 존재한다 (PR11 사이드바 앵커)', () => {
    expect(byId).toHaveProperty('/_shell')
  })

  it('loginRoute는 셸 밖 — rootRoute(__root__) 직속 유지 (redirectIfAuth + already-authed.spec 계약)', () => {
    // 실패 경로 1 가드: login이 실수로 _shell 아래 들어가면 PR11에서 로그인 화면에 사이드바가 샌다.
    expect(byId['/login']?.parentRoute?.id).toBe('__root__')
  })

  it('보호 라우트(/dashboard)는 _shell 아래로 재부모화된다', () => {
    expect(byId['/_shell/dashboard']?.parentRoute?.id).toBe('/_shell')
  })

  it('pathless라 URL(fullPath)은 불변 — _shell 세그먼트가 URL에 새지 않는다', () => {
    // 재부모화의 핵심 불변식: route id에 /_shell이 붙어도 실제 URL은 그대로여야 한다.
    expect(byId['/_shell/dashboard']?.fullPath).toBe('/dashboard')
    expect(byId['/login']?.fullPath).toBe('/login')
  })
})
