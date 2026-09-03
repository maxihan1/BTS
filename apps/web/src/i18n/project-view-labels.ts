// 프로젝트 뷰 탭바 라벨 정본 — navLabels 를 오염시키지 않는 별도 레지스트리 (Jira 패리티 J5)

/**
 * 프로젝트 뷰 탭바(`ProjectNavTabs`)가 노출하는 문구 전수.
 *
 * **왜 `navLabels` 에 넣지 않는가.** `navLabels` 는 사이드바·상단바 nav 의 단일 출처이고
 * 그 파일의 전수 판별식(`i18n/__tests__/nav-labels.test.ts` FR15)은 **모든 값 쌍이 서로의
 * substring 이 아닐 것**과 **값 중복 0** 을 단언한다. 탭 라벨에는 그 둘을 모두 깨는 값이 있다.
 *
 * - `이슈`·`대시보드`·`캘린더` 는 `navLabels` 의 같은 값과 **완전히 겹친다**(사이드바에도 같은
 *   이름의 링크가 있다). 같은 낱말이 맞으므로 한쪽 이름을 비트는 것은 화면을 거짓말하게 만든다.
 * - `보드` 는 `대시보드` 의 substring 인데 **둘이 같은 탭바 안에 공존한다** — `navLabels` 처럼
 *   「다른 nav 라 안 만난다」로 넘길 수 없는, 이 레지스트리 고유의 위험이다.
 *
 * 그래서 `navLabels` 의 판별식을 면제로 오염시키는 대신 **레지스트리를 갈랐다.** 교차 관계는
 * `i18n/__tests__/project-view-labels.test.ts` 가 두 레지스트리를 함께 훑어 명문화한다 —
 * 면제를 적는 자리가 따로 있다는 것이 이 분리의 목적이다.
 *
 * 🔒 **조회 규약** (판별식이 강제한다).
 * ① 탭 조회는 반드시 `<nav aria-label="프로젝트 뷰 전환">` 으로 **스코프**한다. 사이드바에
 *    같은 이름의 링크가 있어 문서 전역 조회는 strict mode 위반이다.
 * ② `보드` 조회는 Playwright 에서 `exact: true` 가 **필수**다. 기본이 부분 일치라 같은 nav 안의
 *    `대시보드` 가 함께 잡힌다. Testing Library 의 `name` 은 기본이 완전 일치라 유닛은 안전하다.
 * ③ `백로그` 조회도 Playwright 에서 `exact: true` 가 필수다 — 보드 화면의 스크럼 빈 상태에
 *    `백로그로 이동`(`ScrumSprintEmptyState`)이 있고 탭바와 **한 화면에 공존**한다.
 *    이 쌍은 탭바가 생기면서 새로 만들어졌다. 짝 판별식이 관계 자체를 얼려 둔다.
 */
export const projectViewLabels = {
  /** 요약 탭 — 프로젝트 기본 착지 (Jira 패리티 J4) */
  summary: '요약',

  /** 타임라인(Gantt) 탭 */
  timeline: '타임라인',

  /**
   * 보드 탭.
   *
   * ⚠️ `대시보드` 의 substring 이고 **같은 탭바 안에 있다.** 조회 규약 ② 참조.
   */
  board: '보드',

  /**
   * 백로그 탭.
   *
   * 목업 v2 의 8종에는 없었으나 Maxi 확정으로 보드 뒤에 들어왔다(2026-09-03). 근거 둘.
   *
   * ① **Jira 원문이 독립 탭으로 적는다** — "Select the **Backlog** tab from your space
   *    navigation of your scrum space"([use your scrum backlog], 2026-09-03, Cloud) ·
   *    "From your space navigation, select the **Backlog** tab"([plan a sprint], 동일). 보드
   *    안에 든 하위 화면이 아니다.
   * ② **편차 X7 의 운반체다** — 보드 화면의 백로그 링크가 `?board=` 스코프를 싣는 유일한
   *    지점이었고(#432), 탭바가 그 인라인 nav 를 흡수하므로 승계할 자리가 필요했다.
   *
   * [use your scrum backlog]: https://support.atlassian.com/jira-software-cloud/docs/use-your-scrum-backlog/
   * [plan a sprint]: https://support.atlassian.com/jira-software-cloud/docs/plan-a-sprint/
   */
  backlog: '백로그',

  /**
   * 캘린더 탭 — 전역 `/calendar` 로 나간다 (편차 X9).
   *
   * ⚠️ `navLabels.calendar` 와 **값이 같다**. 사이드바 캘린더 링크와 한 화면에 공존하므로
   * 조회 규약 ① 이 필수다.
   */
  calendar: '캘린더',

  /**
   * 대시보드 탭 — 전역 `/dashboards` 로 나간다 (편차 X9).
   *
   * ⚠️ `navLabels.dashboards` 와 값이 같다. 조회 규약 ① 참조.
   */
  dashboards: '대시보드',

  /** 컴포넌트 탭 — 프로젝트 설정 하위 화면 */
  components: '컴포넌트',

  /**
   * 이슈 탭 — `/issues?projectKey=` 로 나간다.
   *
   * ⚠️ `navLabels.issues` 와 값이 같다. 조회 규약 ① 참조.
   */
  issues: '이슈',

  /** 버전 탭 — 프로젝트 설정 하위 화면 */
  versions: '버전',

  /**
   * 오버플로 트리거 라벨 — 폭이 모자라 접힌 탭을 여는 팝오버 버튼.
   *
   * `role="button"` 이라 `getByRole('navigation')` 개수를 늘리지 않는다. 탭 라벨 어느 것과도
   * substring 관계가 아니어야 하며 아래 판별식이 그것을 검사한다.
   */
  overflowTrigger: '더 보기',
} as const

/** projectViewLabels const 추론 타입 */
export type ProjectViewLabels = typeof projectViewLabels
