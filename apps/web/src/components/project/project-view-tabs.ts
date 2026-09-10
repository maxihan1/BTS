// 프로젝트 뷰 탭 정본 10종 + 활성 판정 순수 함수 (Jira 패리티 J5 · JR-1)
import { projectViewLabels } from '@/i18n/project-view-labels'

/**
 * 탭 안정 식별자. 화면 문구가 아니라 **이것**이 정체성이다.
 *
 * PR ⑦(J5-c 커스터마이즈)이 탭 순서·표시 여부·이름 변경을 서버에 저장할 때 이 키로 참조한다 —
 * 라벨로 참조하면 이름을 바꾸는 순간 설정이 고아가 된다.
 */
export type ProjectViewTabKey =
  | 'summary'
  | 'timeline'
  | 'board'
  | 'backlog'
  | 'calendar'
  | 'dashboards'
  | 'components'
  | 'issues'
  | 'versions'
  | 'reports'

/** 탭 1개의 정의 — 전부 데이터다(함수 없음). PR ⑦ 이 그대로 직렬화해 서버 설정과 대조한다 */
export interface ProjectViewTab {
  /** 안정 식별자 */
  readonly key: ProjectViewTabKey
  /** TanStack Router 라우트 경로 (`$projectKey` 플레이스홀더 포함 가능) */
  readonly to: string
  /** 화면 문구 — 정본은 `projectViewLabels` */
  readonly label: string
  /**
   * `$projectKey` path param 을 받는가.
   *
   * 편차 X9 폐기(2026-09-07) 이후 **정본 10탭은 전부 `true`** 다. 축 자체는 남긴다 — `Link` 에
   * 그 라우트가 모르는 param 을 넘기면 TanStack 이 조용히 무시하는데, 무시에 기대면 라우트가
   * 나중에 param 을 갖게 될 때 엉뚱한 값이 실린다. 전역 탭이 다시 생기는 날의 안전장치다.
   */
  readonly usesProjectParam: boolean
  /**
   * 활성 판정이 **완전 일치**인가.
   *
   * 🛑 요약만 `true` 이고, 이것이 빠지면 즉사한다 — `/projects/ATLAS` 는 모든 하위 경로의
   * 접두사라 전 화면에서 요약이 함께 강조된다. `Sidebar.tsx` 의 `ISSUES_ACTIVE_OPTIONS`
   * (`/issues` exact)가 같은 함정의 선례다. `project-view-tabs.test.ts` 의 「실 라우트 전수에서
   * 활성 탭이 2개 이상이 되지 않는다」가 이 필드를 지킨다.
   */
  readonly exact: boolean
}

/**
 * 기본 탭 10종 — **이 순서가 정본이다** (Maxi 확정 2026-09-03 목업 v2 + 백로그 · 2026-09-08 리포트).
 *
 * ### Jira 대조 (조회일 2026-09-03 · 전부 Cloud)
 * - 탭이 수평 나열이고 집합은 스페이스 유형·활성 기능에 달렸다 — "depend on your space's type
 *   … and which features are enabled" ([space navigation])
 * - 백로그는 **보드와 형제인 독립 탭**이다 — "Select the **Backlog** tab from your space
 *   navigation of your scrum space" ([use your scrum backlog]) · "You'll see a new **Backlog**
 *   tab in your space navigation" ([enable the backlog])
 * - 「스프린트」라는 탭은 **없다**. 스프린트는 백로그 화면 안에서 계획된다 — "In Jira scrum
 *   spaces, sprints are planned on the Backlog screen" ([use your scrum backlog])
 *
 * ### 리포트 탭 (JR-1·JR-3 · 조회일 2026-09-08 · Cloud)
 * 리포트는 **스페이스 내비게이션(수평 탭)** 에서 연다 — "Select **Reports** from the space
 * navigation" ([generate a report]). 신 내비게이션 문서의 사이드바 항목 열거에는 Reports 가
 * 아예 없다 ([what is the new navigation in Jira]). 그래서 사이드바 트리의 리포트 그룹을
 * 이 탭 한 행이 대신한다.
 *
 * [generate a report]: https://support.atlassian.com/jira-software-cloud/docs/generate-a-report/
 * [what is the new navigation in Jira]: https://support.atlassian.com/jira-software-cloud/docs/what-is-the-new-navigation-in-jira/
 *
 * ### 편차 X9 는 폐기됐다 (Maxi 확정 2026-09-07)
 * 한때 캘린더·대시보드·이슈 세 탭이 전역 라우트(`/calendar`·`/dashboards`·`/issues`)를 가리켰고,
 * `ProjectViewChrome` 의 마운트 조건이 `params.projectKey` 라 **누르는 순간 헤더와 탭바가
 * 통째로 사라졌다.** Jira 는 캘린더·목록도 스페이스 안의 탭이라 눌러도 스페이스 크롬이 남는다
 * (J5-12 · 실물 조회 2026-09-07). 그래서 `router.ts` 에 프로젝트 스코프 형제 라우트 3종을
 * 신설하고 **10탭 전부가 `$projectKey` 를 받는다**.
 *
 * ⚠️ 남은 편차는 **X-J5-13** 이다 — 프로젝트 캘린더·대시보드는 *탐색* 패리티까지고 내용은
 * 아직 프로젝트로 좁히지 않는다(백킹 API 에 프로젝트 축이 없다). 근거와 대안 기각 사유는
 * `router.ts` 의 `projectCalendarRoute` KDoc 에 있다.
 *
 * [space navigation]: https://support.atlassian.com/jira-software-cloud/docs/manage-and-customize-the-project-navigation/
 * [use your scrum backlog]: https://support.atlassian.com/jira-software-cloud/docs/use-your-scrum-backlog/
 * [enable the backlog]: https://support.atlassian.com/jira-software-cloud/docs/enable-the-backlog/
 */
export const PROJECT_VIEW_TABS: readonly ProjectViewTab[] = [
  {
    key: 'summary',
    to: '/projects/$projectKey',
    label: projectViewLabels.summary,
    usesProjectParam: true,
    exact: true,
  },
  {
    key: 'timeline',
    to: '/projects/$projectKey/timeline',
    label: projectViewLabels.timeline,
    usesProjectParam: true,
    exact: false,
  },
  {
    key: 'board',
    to: '/projects/$projectKey/board',
    label: projectViewLabels.board,
    usesProjectParam: true,
    exact: false,
  },
  {
    key: 'backlog',
    to: '/projects/$projectKey/backlog',
    label: projectViewLabels.backlog,
    usesProjectParam: true,
    exact: false,
  },
  {
    key: 'calendar',
    to: '/projects/$projectKey/calendar',
    label: projectViewLabels.calendar,
    usesProjectParam: true,
    exact: false,
  },
  {
    key: 'dashboards',
    to: '/projects/$projectKey/dashboards',
    label: projectViewLabels.dashboards,
    usesProjectParam: true,
    exact: false,
  },
  {
    key: 'components',
    to: '/projects/$projectKey/settings/components',
    label: projectViewLabels.components,
    usesProjectParam: true,
    exact: false,
  },
  {
    key: 'issues',
    to: '/projects/$projectKey/issues',
    label: projectViewLabels.issues,
    usesProjectParam: true,
    exact: false,
  },
  {
    key: 'versions',
    to: '/projects/$projectKey/settings/versions',
    label: projectViewLabels.versions,
    usesProjectParam: true,
    exact: false,
  },
  {
    // 🛑 **맨 끝이 자리다.** 위치가 오버플로에서 무엇이 접히는지를 정하는데 Jira 는 Reports 의
    //    순서를 문서로 못박지 않는다(JR-1·JR-3 어느 쪽도 서술 없음). 끝에 두면 기존 9탭의
    //    인덱스가 그대로라 `resolveActiveTabIndex`·핀 고정의 회귀 표면이 최소다.
    // 🛑 `exact: false` 여야 한다 — 리포트 4화면(`/reports/velocity` 등)에서도 이 탭이 활성이고
    //    탭바가 남아야 한다(채택 A-3). 리포트는 «탭»이므로 자기 하위에서 자기를 지우면 안 된다.
    key: 'reports',
    to: '/projects/$projectKey/reports',
    label: projectViewLabels.reports,
    usesProjectParam: true,
    exact: false,
  },
]

/**
 * 탭의 `to` 를 실제 경로로 푼다 — `$projectKey` 만 치환한다.
 *
 * `Link` 가 내부에서 하는 것과 같은 일이지만, 활성 판정 순수 함수가 라우터 없이도 돌아야
 * 하므로 여기에 한 벌 둔다. **두 층이 어긋나면 판별식이 잡는다**(아래 참조).
 */
export function resolveTabHref(tab: ProjectViewTab, projectKey: string): string {
  return tab.usesProjectParam ? tab.to.replace('$projectKey', projectKey) : tab.to
}

/**
 * 이 탭이 지금 경로에서 활성인가.
 *
 * 판정 규칙은 `Link` 의 것을 그대로 흉내낸다 — `exact` 면 완전 일치, 아니면 경로 세그먼트
 * 경계까지 포함한 접두 일치다. 🛑 `startsWith(href)` 만 쓰면 `/projects/AT` 가 `/projects/ATLAS`
 * 를 활성으로 만든다. 그래서 **`href` 자신이거나 `href + '/'` 로 시작할 때**만 활성이다.
 *
 * 🛑 **판별식이 이 함수를 부르게 하라.** 같은 식을 테스트 안에 베껴 두면 데이터(`exact` 플래그)만
 *    지키고 규칙을 바꿔도 red 가 안 난다 — 이 저장소가 반복 적발한 「두 목록이 서로를 검사하지
 *    않는다」 양식이다.
 *
 * ⚠️ **접두 분기의 관측 표면은 리포트 탭 하나다.** 나머지 9탭의 목적지가 전부 말단 경로라
 *    `/projects/ATLAS/board/…` 같은 하위 라우트가 아직 없다(실측 — 이 분기를 완전 일치로 바꿔도
 *    라우트 전수 판별식이 통과했다). 그래서 이 규칙은 **직접 단위 테스트**가 지킨다
 *    (`project-view-tabs.test.ts` §isTabActive). 하위 라우트가 생기는 날을 위한 대비다.
 */
export function isTabActive(tab: ProjectViewTab, pathname: string, projectKey: string): boolean {
  const href = resolveTabHref(tab, projectKey)
  return tab.exact ? pathname === href : pathname === href || pathname.startsWith(`${href}/`)
}

/**
 * 지금 경로에서 활성인 탭의 인덱스. 없으면 `-1`.
 *
 * ### 활성 판정은 두 층이고, 판별식이 그 둘을 대조한다
 * ① **시각·ARIA** 는 `Link` 의 `activeOptions`/`activeProps` 가 소유한다 — 라우터가 판정한다.
 * ② **오버플로 핀 고정용 인덱스**는 이 함수가 소유한다 — `ResizeObserver` 콜백 안에서
 *    라우터 훅을 부를 수 없어 순수 함수여야 한다.
 *
 * ③ 대조는 **두 군데**가 나눠 한다. 어느 한쪽만 있으면 보증이 절반이다.
 *    - `project-view-tabs.test.ts` — `resolveTabHref` 와 `router.buildLocation()` 의 **href 생성**
 *      일치. 목적지가 갈리는 것을 막는다.
 *    - `ProjectNavTabs.test.tsx` — 실 렌더의 `aria-current`(라우터 판정)와 이 함수의 결론이
 *      프로젝트 스코프 탭 목적지 전수에서 같은지. **활성 판정 자체**를 대조하는 자리다.
 *
 * @param pathname 현재 URL 의 pathname (search·hash 없음)
 * @param projectKey 지금 보고 있는 프로젝트 키
 * @param tabs 판정 대상 탭 목록. 기본값은 정본 10종
 * @returns 활성 탭 인덱스. 어느 탭에도 안 걸리면 `-1`
 */
export function resolveActiveTabIndex(
  pathname: string,
  projectKey: string,
  tabs: readonly ProjectViewTab[] = PROJECT_VIEW_TABS,
): number {
  return tabs.findIndex((tab) => isTabActive(tab, pathname, projectKey))
}

/**
 * **보드 탭**에 실을 보드 스코프 (편차 X7 승계 · FR-BD-04 PR ⑥ · #432).
 *
 * 백로그 화면의 인라인 nav 가 보드 링크에 `?board=` 를 얹던 일을 이어받는다 — 없으면
 * 백로그→보드 이동에서 스코프가 증발해 보드 화면이 `boards[0]` 으로 되돌아간다.
 *
 * **종류를 가리지 않는다.** 보드 화면은 칸반·스크럼을 다 열기 때문이다.
 *
 * 🛑 **보장 범위는 보드↔백로그 왕복까지다.** 스코프의 유일한 출처가 URL 의 `?board=` 이고 그것을
 *    싣는 탭이 보드·백로그 둘뿐이라, 나머지 7탭 중 하나를 밟으면 파라미터가 사라진다
 *    (실측 — 보드 → 타임라인 → 보드 는 스코프 없이 착지해 `boards[0]` 로 튄다).
 *    전 탭이 스코프를 나르게 하려면 탭 정의에 「스코프 운반」 축을 추가해야 하는데, 그것은
 *    편차 X7 의 완전 해소(별건)다. **여기서 그 이상을 약속하지 않는다.**
 *
 * @param currentBoardId 지금 보고 있는 보드 UUID. 미확정이면 undefined
 */
export function resolveBoardTabSearch(
  currentBoardId: string | undefined,
): { readonly board: string } | undefined {
  return currentBoardId === undefined ? undefined : { board: currentBoardId }
}

/**
 * **백로그 탭**에 실을 보드 스코프 (편차 X7 승계 · FR-BD-04 PR ⑥ · #432).
 *
 * 원래 `routes/projects.$projectKey.board.tsx` 의 `useBoardViewNavLinks` 가 하던 일이다.
 * 탭바가 그 인라인 nav 를 흡수했으므로 판정도 함께 옮겼다 — 옮기지 않으면 보드→백로그
 * 이동에서 `?board=` 가 증발해 서버가 `findScrumBoardIdByProject`(`created_at ASC LIMIT 1`)로
 * **첫 번째** 스크럼 보드에 폴백하고, 사용자는 왕복마다 스위처를 다시 눌러야 한다.
 *
 * 🛑 **스크럼일 때만 싣는다** — 여기가 {@link resolveBoardTabSearch} 와 갈리는 지점이다.
 *    백로그는 스크럼 보드만 연다(편차 X4 — 칸반은 백로그 없이 간다). 칸반 id 를 실어 보내면
 *    그 보드로 스코프된 백로그가 열리고, 거기서 만든 스프린트는 `getBoard` 가 SCRUM 일 때만
 *    활성 스프린트를 조회하므로 어느 화면에도 안 나타난다.
 *
 * 🛑 종류를 **보드 목록 요약에서** 읽는다(상세가 아니다). 탭바는 상세를 기다리지 않고
 *    렌더되므로 상세에서 읽으면 로딩 창에서 `boardType` 이 undefined 라 `search` 가 안 붙고,
 *    **이 함수가 없애려는 바로 그 증상**이 재현된다.
 *
 * @param boards 프로젝트 보드 목록 요약. 로딩 중이면 undefined
 * @param currentBoardId 지금 보고 있는 보드 UUID. 미확정이면 undefined
 * @returns 실을 search. 스크럼이 아니거나 미확정이면 undefined
 */
export function resolveBacklogTabSearch(
  boards: readonly { readonly boardId: string; readonly boardType: string }[] | undefined,
  currentBoardId: string | undefined,
): { readonly board: string } | undefined {
  if (currentBoardId === undefined) return undefined
  const boardType = boards?.find((board) => board.boardId === currentBoardId)?.boardType
  return boardType === 'SCRUM' ? { board: currentBoardId } : undefined
}
