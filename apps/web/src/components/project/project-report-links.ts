// 프로젝트 리포트 4종 링크 정본 — 착지 화면·서브내비가 같은 한 목록을 소비한다 (Jira 패리티 JR-2)

/**
 * 리포트 링크 1건.
 *
 * `to` 는 `$projectKey` 플레이스홀더를 포함한 **라우트 경로**다 — 완성된 URL 이 아니다.
 * 소비자는 TanStack `Link` 의 `params={{ projectKey }}` 로 치환한다.
 */
export interface ProjectReportLink {
  /** TanStack Router 라우트 경로 (`$projectKey` 플레이스홀더 포함) */
  readonly to: string
  /**
   * 화면 문구.
   *
   * 🔒 **e2e 계약 문자열이다.** `e2e/project-velocity.spec.ts`·`e2e/project-cfd.spec.ts`·
   * `e2e/project-cycle-time.spec.ts` 가 이 라벨로 링크를 찾는다. 글자를 바꾸면 즉사한다.
   */
  readonly label: string
  /**
   * 이 리포트가 답하는 질문 한 줄.
   *
   * 제목만으로는 「벨로시티」와 「사이클/리드 타임」 중 무엇을 눌러야 할지 알 수 없다 —
   * 둘 다 「빠르기」를 말하는 이름이라 이름끼리 구분되지 않는다. 착지 카드가 이 한 줄을
   * 함께 내야 고르는 일이 성립한다 (plan ★리뷰 D-4).
   */
  readonly question: string
}

/**
 * 리포트 4종 **단일 정본**.
 *
 * 🛑 목록을 두 벌 두지 않는다. 한때 이 4개는 `components/layout/ProjectTree.tsx` 의
 * `REPORT_LINKS` 안에만 있었고, 사이드바 트리가 그 4화면의 유일한 진입로였다. Jira 는
 * 리포트를 **스페이스 내비게이션(수평 탭)** 에서 열고 신 내비게이션 사이드바에는 Reports 가
 * 아예 없다(JR-1·JR-3). 그래서 목록이 사이드바 컴포넌트 안에 살 이유가 사라졌고, 착지 화면·
 * 서브내비·도달성 판별식 셋이 함께 읽을 수 있는 자리로 옮겼다.
 *
 * `export` 인 이유는 테스트 편의가 아니라 **판별식이 정본을 직접 읽게 하기 위해서**다
 * (`scripts/workflow/project-nav-reachability.test.ts` 가 이 배열의 `to` 를 도달 집합에
 * 넣는다). 목록을 베껴 두면 「두 목록이 서로를 검사하지 않는」 지배 결함 양식이 재현된다.
 */
export const PROJECT_REPORT_LINKS: readonly ProjectReportLink[] = [
  {
    to: '/projects/$projectKey/reports/velocity',
    label: '벨로시티',
    question: '스프린트마다 얼마나 끝냈나',
  },
  {
    to: '/projects/$projectKey/reports/cfd',
    label: '누적 흐름도(CFD)',
    question: '어느 단계에 일이 쌓이나',
  },
  {
    to: '/projects/$projectKey/reports/cycle-time',
    label: '사이클/리드 타임',
    question: '하나 끝내는 데 얼마나 걸리나',
  },
  {
    to: '/projects/$projectKey/reports/worklog',
    label: '작업 로그',
    question: '누가 어디에 시간을 썼나',
  },
]
