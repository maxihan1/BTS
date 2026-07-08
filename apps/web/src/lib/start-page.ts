// 시작 페이지(startPage) 환경설정 키 → 라우트 매핑 + 화이트리스트 폴백 로직 — FR-PF-02

/**
 * 지원하는 시작 페이지 키 4종 — 백엔드 `UserPreferences.START_PAGES` companion과 동기화 계약
 * (`api/preferences.ts`의 THEMES/LOCALES 선례와 동일한 패턴, 값 목록은 항상 백엔드와 1:1 대응).
 */
export const START_PAGE_KEYS = ['dashboards', 'my_issues', 'issues', 'inbox'] as const

/** {@link START_PAGE_KEYS} 중 하나 — 사용자 환경설정 startPage 값 */
export type StartPage = (typeof START_PAGE_KEYS)[number]

/** 시작 페이지 키별 한국어 표시 라벨 — 설정 화면 select 등에서 사용 */
export const START_PAGE_LABELS: Record<StartPage, string> = {
  dashboards: '대시보드 목록',
  my_issues: '내 이슈',
  issues: '전체 이슈 목록',
  inbox: '받은 알림함',
}

/**
 * 값이 지원하는 {@link StartPage}인지 판별하는 타입 가드.
 * whoami/preferences 등 외부에서 온 느슨한 string 값을 안전하게 좁힐 때 사용한다
 * (`lib/theme.ts`의 isTheme·`api/preferences.ts`의 isLocale 선례와 동일한 패턴).
 *
 * @param value 검사할 값
 * @returns StartPage 여부
 */
export function isStartPage(value: unknown): value is StartPage {
  return typeof value === 'string' && (START_PAGE_KEYS as readonly string[]).includes(value)
}

/** {@link resolveStartPageNav}의 반환 타입 — TanStack Router navigate/redirect의 `{ to, search }` 인자와 호환 */
export interface StartPageNav {
  to: string
  search?: Record<string, string>
}

/** 화이트리스트 밖 키·userId 부재 등 모든 예외 상황의 폴백 목적지 */
const FALLBACK_NAV: StartPageNav = { to: '/dashboards' }

/**
 * 사용자 환경설정 startPage 값을 실제 라우트 이동 대상으로 해석한다.
 * 화이트리스트({@link START_PAGE_KEYS}) 밖 값이거나 `my_issues`인데 userId가 없으면
 * `/dashboards`로 폴백한다(오픈 리다이렉트 방지 — 임의 경로 문자열을 절대 신뢰하지 않음).
 *
 * @param startPage 사용자 환경설정 startPage 값(whoami 등에서 온 느슨한 string)
 * @param userId 현재 로그인 사용자 id — `my_issues` 매핑 시 assignee 필터로 사용
 * @returns 이동할 라우트(`{ to, search? }`)
 */
export function resolveStartPageNav(
  startPage: string | undefined,
  userId: string | undefined,
): StartPageNav {
  if (!isStartPage(startPage)) return FALLBACK_NAV

  switch (startPage) {
    case 'dashboards':
      return { to: '/dashboards' }
    case 'issues':
      return { to: '/issues' }
    case 'inbox':
      return { to: '/inbox' }
    case 'my_issues':
      if (userId === undefined) return FALLBACK_NAV
      return { to: '/issues', search: { assignee: userId } }
    default: {
      // 미래에 StartPage에 새 값이 추가되면 TypeScript 컴파일 에러로 감지된다
      // (WorkflowDiagram.tsx의 categoryToClass 선례와 동일한 패턴).
      const _exhaustive: never = startPage
      return _exhaustive
    }
  }
}
