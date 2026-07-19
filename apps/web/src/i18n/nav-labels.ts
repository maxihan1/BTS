// 전역 사이드바/상단바 nav 라벨 단일 출처 — FR-UX-06 PR11

/**
 * 사이드바·상단바 nav에서 사용하는 한국어 라벨.
 *
 * 🔒 e2e 계약 문자열(`mainNav`·`adminNav`·`projectViewNav`·`search`)은
 * Playwright/유닛 테스트가 `aria-label`로 직접 참조하므로 글자 변경 금지.
 *
 * S3(2026-07-20 Maxi 확정) — 백킹 라우트·기능이 없는 항목(내 작업·최근·필터·
 * 프로젝트)은 포함하지 않는다. 각 항목은 해당 기능 FR에서 추가한다.
 */
export const navLabels = {
  /** 사이드바 메인 nav aria-label (🔒 e2e 계약) */
  mainNav: '메인 메뉴',

  /** 사이드바 관리 nav aria-label (🔒 e2e 계약) */
  adminNav: '관리 메뉴',

  /** board/backlog 뷰 전환 nav aria-label (🔒 e2e 계약, PR11 미접촉) */
  projectViewNav: '프로젝트 뷰 전환',

  /** 상단바 검색 버튼 aria-label (🔒 e2e 계약, 상단바 단일) */
  search: '검색',

  /** 사이드바 이슈 링크 라벨 */
  issues: '이슈',

  /** 사이드바 대시보드 링크 라벨 */
  dashboards: '대시보드',

  /** 사이드바 캘린더 링크 라벨 */
  calendar: '캘린더',

  /** 사이드바 즐겨찾기 트리거 라벨 */
  starred: '즐겨찾기',

  /** 상단바 만들기 버튼 라벨 */
  create: '만들기',

  /** 사이드바 관리 nav 제목 라벨 */
  admin: '관리',

  /** 사이드바 접기 토글 aria-label (펼침 상태) */
  collapseSidebar: '사이드바 접기',

  /** 사이드바 펼치기 토글 aria-label (접힘 상태) */
  expandSidebar: '사이드바 펼치기',
} as const

/** navLabels const 추론 타입 */
export type NavLabels = typeof navLabels
