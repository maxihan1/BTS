// 백로그·스프린트 UI i18n 라벨 (FR-BL-01/02 D6/D7)

/**
 * 백로그·스프린트 칸 및 카드가 노출하는 한국어 라벨.
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const backlogLabels = {
  /** 백로그 페이지 — 헤더 및 접근 거부 메시지 */
  page: {
    title: '백로그',
    boardLink: '보드',
    timelineLink: '타임라인',
    velocityLink: '벨로시티',
    cfdLink: '누적 흐름도',
    cycleTimeLink: '사이클/리드 타임',
    accessDenied: '접근 권한이 없습니다',
    accessDeniedDetail: '해당 프로젝트의 백로그에 접근할 권한이 없습니다.',
  },
  /** 백로그 칸 헤더 */
  backlogTitle: '백로그',

  /** 스프린트 상태 배지 라벨 */
  status: {
    PLANNED: 'PLANNED',
    ACTIVE: 'ACTIVE',
    COMPLETED: 'COMPLETED',
  },

  /** 시작 버튼 */
  startSprint: '스프린트 시작',

  /** 완료 버튼 */
  completeSprint: '스프린트 완료',

  /** 빈 이슈 목록 placeholder */
  emptyIssues: '이슈 없음',

  /** 담당자 미배정 텍스트 */
  unassigned: '미배정',

  /** 담당자 이름 미확인 title */
  unknownAssigneeTitle: '담당자 (이름 미확인)',

  /** 담당자 이름 미확인 aria-label */
  unknownAssigneeAriaLabel: '담당자 이름 미확인',

  /** 드래그 가능 카드 aria-roledescription */
  draggableCard: 'draggable card',

  /** 칸 aria-label 생성 함수 */
  columnAriaLabel: (name: string, count: number): string =>
    `${name} 칸, ${count}개 이슈`,

  /** 카드 aria-label 생성 함수 */
  cardAriaLabel: (key: string, summary: string): string =>
    `${key} — ${summary}`,

  /** 스프린트 생성 폼 aria-label */
  createSprintFormLabel: '스프린트 생성 폼',

  /** 스프린트 이름 입력 placeholder */
  sprintNamePlaceholder: '스프린트 이름',

  /** 스프린트 생성 버튼 텍스트 */
  createSprint: '스프린트 생성',

  /** truncated 경고 배너 메시지 */
  truncatedWarning: '이슈 수가 많아 일부만 표시됩니다. 필터를 적용해 범위를 줄이세요.',

  /** 순서 반영 실패 경고 메시지 */
  rerankFailedWarning: '이슈 이동은 완료됐지만 순서 반영이 실패했습니다. 잠시 후 새로 고침 시 위치가 기본값으로 보일 수 있습니다.',

  /** 이동 실패 에러 메시지 */
  moveFailedError: '이슈 이동에 실패했습니다. 다시 시도해 주세요.',

  // ── FR-UX-13 F5 — 백로그 조회 실패 안내 ─────────────────────────────────

  /** 백로그 조회 실패 안내 (FR-UX-13 F5) */
  loadFailed: '백로그를 불러올 수 없습니다.',

  /**
   * 조회 실패 재시도 버튼.
   *
   * ⚠️ `moveFailedError`('…다시 시도해 주세요.')가 이 값을 **부분 문자열로 포함**한다.
   * 둘은 role 이 달라(button vs 토스트 텍스트) 공존해도 되지만, 테스트 조회는 반드시
   * 버튼 role + 정확 일치 이름으로 한다 (Playwright 는 부분 일치가 기본이라 특히 그렇다).
   */
  retry: '다시 시도',

  /**
   * 재조회 중 버튼 라벨 (design review D3).
   *
   * ⚠️ `retry`('다시 시도')를 부분 문자열로 포함하지만 **판별식 목록에 넣지 않는다** —
   * `create-entry-point-names.test.ts` §제외 3종 ②「같은 버튼의 다른 상태」에 해당한다
   * (`submitButton`/`submitButtonPending` 과 동형). 한 버튼이 둘 중 하나만 보이므로
   * 같은 순간에 두 이름이 화면에 있을 수 없다. **넣으면 판별식이 구조적으로 실패한다.**
   */
  retrying: '다시 시도 중…',

  // ── FR-UX-09 F3 — 이슈 생성 진입점 ──────────────────────────────────────
  //
  // ⚠️ 이 두 값을 고칠 때는 `i18n/__tests__/create-entry-point-names.test.ts` 를 먼저 읽을 것.
  // 같은 화면의 `만들기`(상단바) · `이슈 생성`(모달 제출) · `스프린트 생성` 중 어느 것도
  // **부분 문자열로 포함하면 안 된다** — Playwright·Testing Library 둘 다 기본이 부분 일치라
  // 기존 e2e 가 strict mode 로 깨진다.

  /** 백로그 칸 이슈 생성 진입점 (아이콘 버튼의 sr-only 이름) */
  createIssueInBacklog: '백로그 칸에 이슈 추가',

  /**
   * 스프린트 칸 이슈 생성 진입점 이름.
   *
   * **스프린트 이름을 포함하는 것이 요구사항이다** (FR-10) — 스프린트가 여러 개면
   * 같은 화면에 진입점이 N개 뜨는데, 이름이 같으면 조회가 strict mode 로 깨진다.
   */
  createIssueInSprint: (sprintName: string): string => `${sprintName} 스프린트에 이슈 추가`,
} as const

/** backlogLabels const 추론 타입 */
export type BacklogLabels = typeof backlogLabels
