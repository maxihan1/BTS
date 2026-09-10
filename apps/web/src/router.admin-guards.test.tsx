// 전 라우트 beforeLoad 가드 행렬 음성 회귀 테스트 — 라우트 클래스별 기대 서명 정확 일치 + 미분류 실패
//
// 범위. #299(PR13)에서 이 파일은 workflow-schemes 3라우트 × SYSTEM_ADMIN 1종만 덮었다. 그 결과
// /admin/slack 이 2-가드인 채로 초록을 유지했다(도입 d9e418d9b/#247 → #299 정렬에서 누락).
// 이제 routesById 전 라우트 × 4시나리오를 덮고, 맵에 없는 라우트는 실패로 처리해 신규 라우트에
// 클래스 선언을 강제한다.
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { isRedirect } from '@tanstack/react-router'
import { router } from './router'
import { useAuthStore } from './auth/authStore'
import { makeWhoami } from './mocks/auth-fixtures'

/** 가드 클래스 — 라우트가 요구하는 보호 수준 */
type GuardClass = 'ADMIN_4' | 'PROTECTED_3' | 'AUTH_ONLY' | 'LOGIN' | 'PUBLIC' | 'INDEX_ALWAYS_REDIRECT'

/** 경로 목록을 한 클래스로 묶는 헬퍼 — 맵 리터럴의 반복 제거 */
const cls = (c: GuardClass, ...keys: string[]): [string, GuardClass][] => keys.map((k) => [k, c])

/**
 * routesById 키 → 가드 클래스. 이 맵이 정책 선언이다.
 *
 * 키 형태(실측). shell 자식 라우트는 `/_shell` 접두사가 붙고(pathless 재부모화), 인덱스는 `/_shell/`,
 * 레이아웃 자체는 `/_shell`, 루트는 접두사 없는 `__root__`, `/login`은 shell 밖이라 `/login` 그대로다.
 * 맵과 실제 키가 어긋나면 아래 완전성 테스트가 unclassified/stale 로 잡는다.
 */
const ROUTE_CLASS = new Map<string, GuardClass>([
  ...cls(
    'ADMIN_4',
    '/_shell/admin',
    '/_shell/admin/audit-logs',
    '/_shell/admin/global-permissions',
    '/_shell/admin/notification-policies',
    '/_shell/admin/slack',
    '/_shell/admin/users/new',
    '/_shell/admin/webhooks',
    '/_shell/admin/webhooks/$id/deliveries',
    '/_shell/admin/workflow-schemes',
    '/_shell/admin/workflow-schemes/new',
    '/_shell/admin/workflow-schemes/$schemeKey',
    '/_shell/admin/workflows',
    '/_shell/admin/workflows/new',
    '/_shell/admin/workflows/$workflowKey',
  ),
  ...cls(
    'PROTECTED_3',
    '/_shell/workflows/$key',
    '/_shell/dashboard',
    '/_shell/dashboards',
    '/_shell/dashboards/$dashboardId',
    '/_shell/issues',
    '/_shell/issues/new',
    '/_shell/issues/$key',
    '/_shell/inbox',
    '/_shell/search',
    '/_shell/calendar',
    '/_shell/projects',
    '/_shell/projects/new',
    '/_shell/projects/$projectKey',
    '/_shell/projects/$projectKey/backlog',
    '/_shell/projects/$projectKey/board',
    '/_shell/projects/$projectKey/board/settings',
    '/_shell/projects/$projectKey/timeline',
    // 편차 X9 폐기 (J5-12) — 정본 탭 10종 전부가 프로젝트 스코프 라우트를 갖는다
    '/_shell/projects/$projectKey/issues',
    '/_shell/projects/$projectKey/calendar',
    '/_shell/projects/$projectKey/dashboards',
    '/_shell/projects/$projectKey/sprints/$sprintId/burndown',
    // Jira 패리티 JR-2 — 리포트 착지(4종 카드 목록). 하위 4라우트와 형제다
    '/_shell/projects/$projectKey/reports',
    '/_shell/projects/$projectKey/reports/worklog',
    '/_shell/projects/$projectKey/reports/velocity',
    '/_shell/projects/$projectKey/reports/cfd',
    '/_shell/projects/$projectKey/reports/cycle-time',
    '/_shell/projects/$projectKey/settings/details',
    '/_shell/projects/$projectKey/settings/workflow-scheme',
    '/_shell/projects/$projectKey/settings/members',
    '/_shell/projects/$projectKey/settings/components',
    '/_shell/projects/$projectKey/settings/custom-fields',
    '/_shell/projects/$projectKey/settings/issue-templates',
    '/_shell/projects/$projectKey/settings/automation',
    '/_shell/projects/$projectKey/settings/slack-channels',
    '/_shell/projects/$projectKey/settings/versions',
    '/_shell/projects/$projectKey/settings/project-lead',
    '/_shell/projects/$projectKey/settings/import',
    '/_shell/settings',
    '/_shell/settings/sessions',
    '/_shell/settings/notifications',
    '/_shell/settings/account-links',
    '/_shell/settings/pats',
    '/_shell/settings/keymap',
    '/_shell/settings/calendar',
    '/_shell/settings/slack',
  ),
  ...cls(
    'AUTH_ONLY',
    '/_shell/settings/password',
    '/_shell/settings/mfa',
    '/_shell/settings/profile',
    '/_shell/settings/preferences',
    '/_shell/projects/$projectKey/settings/field-permissions',
  ),
  ...cls('LOGIN', '/login'),
  ...cls(
    'PUBLIC',
    // 키 형태는 task-1 RED 의 unclassified/stale 출력으로 실측했다 —
    // 루트는 '__root__', pathless 레이아웃은 '/_shell'(선행 슬래시 있음), 인덱스는 '/_shell/'.
    '__root__',
    '/_shell',
    '/_shell/dashboards/shared/$token',
  ),
  ...cls('INDEX_ALWAYS_REDIRECT', '/_shell/'),
])

/**
 * 클래스가 PUBLIC·AUTH_ONLY 인 항목의 사유 등재.
 * "정당" 과 "후속 판정 필요" 를 문구로 구분한다 — 초록이 곧 정당함을 뜻하지 않는다.
 */
const CLASS_NOTES: Record<string, string> = {
  '/_shell/settings/password': '정당 — requirePasswordChanged 리다이렉트 목적지 자기 경로',
  '/_shell/settings/mfa': '정당 — requireMfaEnrolled 리다이렉트 목적지 자기 경로',
  '/_shell/dashboards/shared/$token':
    '정당 — 공개 공유 토큰(직교 인증), 세션 불요. 로그인 리다이렉트 금지',
  __root__: '정당 — createRootRoute. 가드 대상 아님',
  '/_shell': '정당 — pathless 레이아웃. ADR 2026-07-17 §70 이 가드 hoist 를 금지',
  '/_shell/':
    '정당 — 2026-09-03 해소. 자체 화면 없이 항상 리다이렉트(가드가 throw). 「리다이렉트 예정」 미완이 닫혔다',
  '/_shell/workflows/$key':
    '정당 — 2026-09-03 해소. 다른 상세 화면과 같은 PROTECTED_3 로 정렬. 게이트 2 리뷰가 잡은 구멍이다',
  '/_shell/settings/profile': '후속 판정 필요 — 비번·MFA 강제 대상이 접근 가능',
  '/_shell/settings/preferences': '후속 판정 필요 — 비번·MFA 강제 대상이 접근 가능',
  '/_shell/projects/$projectKey/settings/field-permissions':
    '후속 판정 필요 — 다른 프로젝트 설정 라우트는 PROTECTED_3',
}

describe('라우트 가드 행렬 — 맵 완전성', () => {
  it('routesById 의 모든 라우트가 ROUTE_CLASS 에 분류되어 있다 (미분류 = 실패)', () => {
    const actual = Object.keys(router.routesById)
    const unclassified = actual.filter((id) => !ROUTE_CLASS.has(id))
    const stale = [...ROUTE_CLASS.keys()].filter((id) => !actual.includes(id))
    expect({ unclassified, stale }).toEqual({ unclassified: [], stale: [] })
  })

  it('발견된 라우트가 59개 이상이다 (열거 실패 시 it.each 무음 통과 차단)', () => {
    expect(Object.keys(router.routesById).length).toBeGreaterThanOrEqual(59)
  })

  it('사유 등재 — AUTH_ONLY·PUBLIC 전 항목이 CLASS_NOTES 를 가진다', () => {
    const needNote = [...ROUTE_CLASS.entries()]
      .filter(([, c]) => c === 'AUTH_ONLY' || c === 'PUBLIC')
      .map(([id]) => id)
    const missing = needNote.filter((id) => CLASS_NOTES[id] === undefined)
    expect(missing).toEqual([])
  })

  // 2026-09-03 에 5 → 3. `/_shell/` 과 `/_shell/workflows/$key` 를 보호 클래스로 옮겨 닫았다.
  it('후속 판정 필요 3건이 사유 문구로 남아 있다 (본 PR 에서 고치지 않음)', () => {
    const pending = Object.entries(CLASS_NOTES)
      .filter(([, note]) => note.startsWith('후속 판정 필요'))
      .map(([id]) => id)
    expect(pending).toHaveLength(3)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 서명 관측 — 4시나리오를 각 라우트의 실제 beforeLoad 에 적용해 redirect 목적지를 읽는다.
// routeGuard.ts 의 개별 가드를 재조합하는 게 아니라 router.ts 에 등록된 합성 beforeLoad 를
// 호출한다("가드 함수는 옳은데 배선을 빼먹었다" 를 잡는 것이 목적).
// ─────────────────────────────────────────────────────────────────────────────

/** beforeLoad 컨텍스트 중 가드가 쓰는 최소 형태 (routeGuard.test.tsx 와 동형) */
interface MinimalBeforeLoadContext {
  location: { href: string; pathname: string }
}

/** redirect() 반환 타입 — Response & { options: { to } } */
interface RedirectResponse extends Response {
  options: { to: string }
}

/** 관측 서명 — [S-1, S-2, S-3, S-4] 각 칸은 redirect 목적지 또는 통과(null) */
type Signature = [string | null, string | null, string | null, string | null]

/**
 * 클래스별 기대 서명.
 * ⚠️ /dashboard(단수 — requireSystemAdmin 거부 목적지)와 /dashboards(복수 — startPage 매핑)는 다른 경로다.
 * LOGIN 은 방향이 반대다(redirectIfAuth — 인증된 사용자를 내보낸다).
 */
const EXPECTED: Record<GuardClass, Signature> = {
  ADMIN_4: ['/login', '/dashboard', '/settings/password', '/settings/mfa'],
  PROTECTED_3: ['/login', null, '/settings/password', '/settings/mfa'],
  AUTH_ONLY: ['/login', null, null, null],
  LOGIN: [null, '/dashboards', '/dashboards', '/dashboards'],
  PUBLIC: [null, null, null, null],
  // 인덱스(`/`)는 자체 화면이 없다 — 어느 시나리오에서도 통과하지 않는다.
  // PROTECTED_3 체인을 다 통과한 뒤 redirectToStartPage 가 start_page 로 보낸다(항상 throw).
  INDEX_ALWAYS_REDIRECT: ['/login', '/dashboards', '/settings/password', '/settings/mfa'],
}

/** 시나리오 4종 — 각 시나리오는 나머지 조건을 전부 통과 상태로 두어 판별자를 유일하게 특정한다 */
// apply 는 블록 본문이어야 한다 — `(): void => useAuthStore.setState(...)` 형태는 setState 의
// 반환값(unknown)을 void 자리에 돌려줘 TS2322 가 된다(tsconfig.app.json 기준 error).
const SCENARIOS = [
  {
    label: 'S-1 미인증',
    apply: (): void => {
      useAuthStore.setState({ accessToken: null, user: null })
    },
  },
  {
    label: 'S-2 비-admin',
    apply: (): void => {
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ isSystemAdmin: false }),
      })
    },
  },
  {
    label: 'S-3 비밀번호 강제',
    apply: (): void => {
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ isSystemAdmin: true, mustChangePassword: true }),
      })
    },
  },
  {
    label: 'S-4 MFA 강제',
    apply: (): void => {
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ isSystemAdmin: true, mfaEnrollmentRequired: true }),
      })
    },
  },
] as const

/**
 * routesById 키에서 실제 pathname 을 만든다.
 * $세그먼트는 임의값으로 치환한다 — 가드가 pathname 을 읽는 곳은
 * requirePasswordChanged/requireMfaEnrolled 의 '/settings/...' 동등 비교뿐이라 값이 판정을 바꾸지 않는다.
 */
function pathnameOf(routeId: string): string {
  const stripped = routeId === '__root__' ? '' : routeId.replace(/^\/_shell/, '')
  const withParams = stripped.replace(/\$[A-Za-z]+/g, 'x-1')
  return withParams === '' ? '/' : withParams
}

/** 한 라우트의 관측 서명을 만든다 */
function observe(routeId: string): Signature {
  const byId = router.routesById as Record<
    string,
    { options: { beforeLoad?: (ctx: MinimalBeforeLoadContext) => void } }
  >
  const beforeLoad = byId[routeId]?.options.beforeLoad
  const pathname = pathnameOf(routeId)
  const ctx: MinimalBeforeLoadContext = { location: { href: pathname, pathname } }

  return SCENARIOS.map((s) => {
    s.apply()
    if (beforeLoad === undefined) return null
    try {
      beforeLoad(ctx)
      return null
    } catch (e) {
      // redirect 가 아닌 진짜 예외(예. pathnameOf 버그의 TypeError)는 원본을 그대로 올린다.
      // 여기서 expect(isRedirect) 로 단정하면 원인 예외가 어서션 실패 메시지에 가려진다.
      if (!isRedirect(e)) throw e
      return (e as RedirectResponse).options.to
    }
  }) as Signature
}

const idsOf = (c: GuardClass): string[] =>
  [...ROUTE_CLASS.entries()].filter(([, v]) => v === c).map(([k]) => k)

describe('라우트 가드 행렬 — ADMIN_4', () => {
  beforeEach(() => useAuthStore.setState({ accessToken: null, user: null }))
  afterEach(() => useAuthStore.setState({ accessToken: null, user: null }))

  it('ADMIN_4 클래스가 14개다 (/admin/workflows 3라우트 편입, FR-WF-04 D6)', () => {
    expect(idsOf('ADMIN_4')).toHaveLength(14)
  })

  it.each(idsOf('ADMIN_4'))('%s — 관측 서명이 ADMIN_4 기대와 정확히 일치', (id) => {
    expect(observe(id)).toEqual(EXPECTED.ADMIN_4)
  })
})

describe('라우트 가드 행렬 — 나머지 클래스', () => {
  beforeEach(() => useAuthStore.setState({ accessToken: null, user: null }))
  afterEach(() => useAuthStore.setState({ accessToken: null, user: null }))

  // ★ 클래스별 개수 어서션 — 이게 없으면 idsOf 가 빈 배열을 돌려줄 때 it.each([]) 가 무음 통과한다.
  //    "발견된 라우트 59개 이상"(routesById 기준)은 이 구멍을 막지 못한다(맵/필터가 죽어도 라우트 수는 그대로).
  it.each([
    // 40 → 41(부채 177 보드 설정) → 44(J5-12 편차 X9 폐기 — 프로젝트 스코프 이슈·캘린더·대시보드)
    // → 45(JR-2 리포트 착지 `/projects/$projectKey/reports`).
    ['PROTECTED_3', 45],
    ['AUTH_ONLY', 5],
    ['LOGIN', 1],
    ['INDEX_ALWAYS_REDIRECT', 1],
  ] as const)('%s 클래스 멤버가 %i개다 (열거 붕괴 시 무음 통과 차단)', (c, n) => {
    expect(idsOf(c)).toHaveLength(n)
  })

  // PUBLIC 은 2026-09-03 에 5 → 3 으로 줄었다. `/_shell/`(인덱스)와 `/_shell/workflows/$key` 가
  // 보호 클래스로 이동했기 때문이다 — 둘 다 「후속 판정 필요」로 등재돼 있던 구멍이었고 그 PR 이 닫았다.
  // 하한만 두어 라우트 추가가 테스트를 깨지 않게 한다.
  it('PUBLIC 클래스 멤버가 3개 이상이다', () => {
    expect(idsOf('PUBLIC').length).toBeGreaterThanOrEqual(3)
  })

  it.each(idsOf('PROTECTED_3'))('%s — PROTECTED_3 기대와 일치', (id) => {
    expect(observe(id)).toEqual(EXPECTED.PROTECTED_3)
  })

  it.each(idsOf('INDEX_ALWAYS_REDIRECT'))('%s — INDEX_ALWAYS_REDIRECT 기대와 일치', (id) => {
    expect(observe(id)).toEqual(EXPECTED.INDEX_ALWAYS_REDIRECT)
  })

  it.each(idsOf('AUTH_ONLY'))('%s — AUTH_ONLY 기대와 일치', (id) => {
    expect(observe(id)).toEqual(EXPECTED.AUTH_ONLY)
  })

  it.each(idsOf('LOGIN'))('%s — LOGIN 기대와 일치 (인증 시 내보냄, 방향 반대)', (id) => {
    expect(observe(id)).toEqual(EXPECTED.LOGIN)
  })

  it.each(idsOf('PUBLIC'))('%s — PUBLIC 기대와 일치 (가드 없음)', (id) => {
    expect(observe(id)).toEqual(EXPECTED.PUBLIC)
  })
})
