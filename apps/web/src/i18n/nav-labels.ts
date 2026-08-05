// 전역 사이드바/상단바 nav 라벨 단일 출처 — FR-UX-06 PR11

/**
 * 사이드바·상단바 nav에서 사용하는 한국어 라벨.
 *
 * 🔒 e2e 계약 문자열(`mainNav`·`adminNav`·`projectViewNav`·`search`)은
 * Playwright/유닛 테스트가 `aria-label`로 직접 참조하므로 글자 변경 금지.
 *
 * S3(2026-07-20 Maxi 확정) — 백킹 라우트·기능이 없는 항목(내 작업·최근·필터)은
 * 포함하지 않는다. 각 항목은 해당 기능 FR에서 추가한다. **프로젝트는 PR12에서
 * `GET /api/v1/projects` 백킹이 확인되어 추가됨**(ProjectTree, FR-UX-06 PR12 Task 2).
 *
 * **S3 이연 항목 3종 중 2종이 FR-UX-08 PR-B에서 추가됐다**(F17).
 * - `myWork` — `/issues?assignee=<userId>` 백킹. **`?assignee=me`는 실재하지 않는다**
 *   (`IssueFilterQueryParser`의 센티널은 `unassigned` 하나뿐, 그 외는 UUID 파싱 실패 시 400)
 * - `recent` — `/issues/$key` 백킹 (최근 본 이슈)
 * - `filters`는 **여전히 백킹이 없어 미추가**다. `i18n/__tests__/nav-labels.test.ts`의
 *   S3 가드가 `filters`·`projects` 부재를 계속 단언한다 — 그 블록을 통째로 지우면
 *   남은 둘이 가드를 잃는다.
 */
export const navLabels = {
  /** 사이드바 메인 nav aria-label (🔒 e2e 계약) */
  mainNav: '메인 메뉴',

  /** 사이드바 관리 nav aria-label (🔒 e2e 계약) */
  adminNav: '관리 메뉴',

  /**
   * 사이드바 프로젝트 트리 nav aria-label (🔒 e2e 계약, FR-UX-06 PR12 FR1).
   *
   * ⚠️ '프로젝트'는 '프로젝트 뷰 전환'({@link projectViewNav})의 substring이다.
   * Playwright `getByRole`은 기본이 substring 매칭이므로 e2e에서는 항상 `exact: true`를
   * 명시할 것(playwright-getbyrole-exact-strict-mode). Testing Library `getByRole`의 `name`은
   * 기본이 이미 완전일치라 유닛 테스트에서는 별도 옵션 없이도 두 nav가 섞이지 않는다.
   */
  projectNav: '프로젝트',

  /** board/backlog 뷰 전환 nav aria-label (🔒 e2e 계약, PR11 미접촉) */
  projectViewNav: '프로젝트 뷰 전환',

  /** 상단바 검색 버튼 aria-label (🔒 e2e 계약) — F13 이후 AQL 검색 페이지 제출 버튼 전용 */
  search: '검색',

  /**
   * 상단바 전역 검색 **입력창** aria-label (🔒 e2e 계약, FR-UX-12 F13).
   *
   * ★`search`(`'검색'`)와 반드시 분리한다 — Jira 패리티 계약 §2 「`검색` 이름 분리」
   * (Maxi 확정 2026-07-28 결정 4). 상단바=`전역 검색`, `검색`=AQL 페이지 제출 버튼 전용.
   * 둘을 합치면 `getByRole` strict mode 에서 상단바와 검색 페이지가 동시에 잡힌다.
   */
  globalSearch: '전역 검색',

  /**
   * 상단바 전역 검색 입력창 placeholder (FR-UX-12 F13).
   *
   * ★`aria-label` 이 있으므로 접근성 이름은 `globalSearch` 가 이긴다 — placeholder 는
   * 시각 힌트 전용이다. 여기에 `검색` 을 넣지 말 것(계약 §2 이름 분리를 흐린다).
   */
  globalSearchPlaceholder: '이슈 검색',

  /**
   * 사이드바 "내 작업" 링크 라벨 (FR-UX-08 PR-B, FR12/FR14).
   *
   * 메인 메뉴 nav **최상단**에 온다(스펙 §8-A D-A) — 매일 여는 진입점이라 순서가
   * 곧 중요도 신호다. e2e 계약 문자열이 아니므로 🔒 표시를 붙이지 않는다.
   */
  myWork: '내 작업',

  /**
   * 사이드바 "최근 항목" 그룹 헤더 라벨 (FR-UX-08 PR-B, FR13/FR14).
   *
   * 최근 본 **이슈** 목록이다 — 프로젝트가 아니다(ADR §D1). 프로젝트 목록은
   * `ProjectTree`가 이미 전량 렌더하므로 중복이고, "최근 프로젝트"는 프로젝트
   * 스위처 내부의 정렬 축으로만 쓰인다.
   */
  recent: '최근 항목',

  /** 사이드바 이슈 링크 라벨 */
  issues: '이슈',

  /** 사이드바 대시보드 링크 라벨 */
  dashboards: '대시보드',

  /** 사이드바 캘린더 링크 라벨 */
  calendar: '캘린더',

  /** 상단바 만들기 버튼 라벨 */
  create: '만들기',

  /** 사이드바 관리 nav 제목 라벨 */
  admin: '관리',

  /** 사이드바 접기 토글 aria-label (펼침 상태) */
  collapseSidebar: '사이드바 접기',

  /** 사이드바 펼치기 토글 aria-label (접힘 상태) */
  expandSidebar: '사이드바 펼치기',

  /**
   * 페이지 상단 탐색 경로(breadcrumb) nav aria-label (🔒 e2e 계약, FR-UX-06 PR13 PL-3 신규).
   *
   * 신규 추가 — 기존 계약 문자열(`mainNav`·`adminNav`·`projectNav`·`projectViewNav`·`search`)
   * 어느 것과도 substring 관계가 아니므로 Playwright `getByRole` 조회 시 다른 nav와
   * 혼선 없이 단독 식별된다(PL-9, nav-labels.test.ts 전수 검증).
   */
  breadcrumb: '탐색 경로',
} as const

/** navLabels const 추론 타입 */
export type NavLabels = typeof navLabels
