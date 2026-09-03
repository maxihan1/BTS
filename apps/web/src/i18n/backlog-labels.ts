// 백로그·스프린트 UI i18n 라벨 (FR-BL-01/02 D6/D7)

/**
 * 백로그 섹션 이름.
 *
 * 칸 헤더(`backlogTitle`)와 완료 다이얼로그의 이관 대상 옵션(`completeDialog.backlogOption`)이
 * **같은 문자열**이어야 하므로 상수로 뽑았다. 두 벌로 갈리면 한쪽만 고쳐져 화면에서 서로
 * 다른 말이 된다.
 */
const BACKLOG_SECTION_NAME = '백로그'

/**
 * 스프린트 편집 폼(`SprintForm`)이 노출하는 문구 전수.
 *
 * **시작 다이얼로그와 편집 다이얼로그가 같은 폼을 쓴다**(NFR-1). 그래서 필드 라벨과
 * 검증 문구는 여기 한 벌만 두고, `startDialog` 는 이 값을 **참조**한다 —
 * 두 벌로 적으면 한쪽만 고쳐져 같은 입력칸이 화면마다 다른 이름을 갖는다.
 * (`BACKLOG_SECTION_NAME` 이 세운 선례와 같은 형태다.)
 *
 * ⚠️ `nameLabel`('이름')은 스프린트 **생성** 폼의 `sprintNamePlaceholder`('스프린트 이름')에
 * **부분 문자열로 포함된다.** 둘은 같은 화면에 공존할 수 있으므로(백로그 칸 헤더의 생성 폼 +
 * 편집 다이얼로그) 조회는 반드시 **정확 일치**로 한다 — Testing Library 의 문자열 조회는
 * 기본이 정확 일치지만 **Playwright 는 부분 일치가 기본**이라 `exact: true` 가 필수다.
 * `retry`('다시 시도') ⊂ `moveFailedError` 와 같은 계약이다.
 */
const SPRINT_FORM_LABELS = {
  /** 이름 필드 라벨 (FR-1) */
  nameLabel: '이름',
  /** 시작일 필드 라벨 */
  startDateLabel: '시작일',
  /** 종료일 필드 라벨 */
  endDateLabel: '종료일',
  /** 목표 필드 라벨 */
  goalLabel: '목표',
  /** 이름을 비운 채 제출했을 때. 백엔드가 이름에 `null` 을 허용하지 않는다 */
  nameRequired: '이름을 입력해 주세요.',
  /** E8 — 종료일이 시작일보다 빠를 때의 필드 에러. 백엔드 왕복을 만들지 않는다 */
  endBeforeStart: '종료일은 시작일보다 빠를 수 없습니다.',
  /**
   * FR-2 · J1 — 완료된 스프린트에서 날짜 칸이 잠긴 사유.
   *
   * 숨기지 않고 **비활성 + 사유**로 둔다. 권한 부재(렌더 자체를 안 한다)와 규율이 갈리는
   * 것이 의도다 — 상태 제약은 「있는 조작이 지금은 안 되는 이유」를 알려야 한다.
   */
  datesLocked: '완료된 스프린트는 이름과 목표만 수정할 수 있습니다.',
  /**
   * 낙관적 잠금 409 — 남이 먼저 고쳤다. 기준값(`version`)만 서버 최신으로 갈아끼운다.
   *
   * ★「최신 값을 불러왔으니」라고 말하지 않는다. 폼은 **사용자가 친 값을 그대로 유지**하기
   *   때문이다 — 서버 값으로 덮으면 재시도의 변경분이 0이 되어 남의 값으로 저장된다
   *   (`SprintForm.SprintFormActions.recoverFromConflict` 주석). 「불러왔다」고 하면
   *   화면에 남아 있는 내 입력과 안내가 서로 다른 말을 한다.
   */
  conflict:
    '다른 사람이 먼저 수정했습니다. 입력하신 값은 그대로 두었으니 확인 후 다시 시도해 주세요.',
} as const

/**
 * 백로그·스프린트 칸 및 카드가 노출하는 한국어 라벨.
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const backlogLabels = {
  /**
   * 백로그 페이지 — 헤더 및 접근 거부 메시지.
   *
   * ★뷰 전환 링크 5종(보드·타임라인·벨로시티·누적 흐름도·사이클/리드 타임)이 여기 있었다.
   * Jira 패리티 J5 로 탭바가 정본 9탭을 소유하면서 이 페이지의 인라인 nav 가 사라졌고,
   * 그 라벨들도 함께 나갔다. 탭 라벨의 정본은 `i18n/project-view-labels.ts` 다.
   * 리포트 3종은 탭이 아니므로 사이드바 트리(`ProjectTree`)의 라벨이 정본이다.
   */
  page: {
    title: '백로그',
    accessDenied: '접근 권한이 없습니다',
    accessDeniedDetail: '해당 프로젝트의 백로그에 접근할 권한이 없습니다.',
  },
  /** 백로그 칸 헤더 */
  backlogTitle: BACKLOG_SECTION_NAME,

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

  /**
   * 스프린트 편집 진입점 (FR-1 · J2 *"Edit sprint"*).
   *
   * `⋯` 메뉴 항목 · 다이얼로그 제목 · 제출 버튼이 **같은 문자열을 재사용**한다
   * (`startSprint`·`completeSprint` 가 세운 관례 — 새 문자열을 만들지 않는다).
   * ★ 세 스프린트 조작 이름(`startSprint`·`completeSprint`·`editSprint`)은 서로
   *   **부분 문자열 관계가 아니어야** 한다. 겹치면 `getByRole('dialog', { name })` 이
   *   strict mode 로 즉사한다(`jira-parity-contract.md` §2).
   *   `EditSprintDialog.test.tsx` 가 그 관계를 전수로 잰다.
   */
  editSprint: '스프린트 편집',

  /**
   * 스프린트 삭제 진입점 (FR-3 · J5).
   *
   * `⋯` 메뉴 항목 · 확인 다이얼로그 제목이 **같은 문자열을 재사용**한다
   * (보드 관리의 `boardLabels.actions.deleteItem`/`deleteDialogTitle` 이 세운 관례).
   * ★ `editSprint`·`startSprint`·`completeSprint` 와 서로 **부분 문자열 관계가 아니다** —
   *   겹치면 `getByRole('dialog', { name })` 이 strict mode 로 즉사한다(계약 §2).
   *   `SprintColumnHeader.test.tsx` H2-2 가 편집 이름과의 관계를 잰다.
   */
  deleteSprint: '스프린트 삭제',

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

  /**
   * 스프린트 생성 실패 에러 메시지 (FR-BD-04 PR ⑤).
   *
   * 🛑 `moveFailedError` 를 재사용하지 마라. 생성 실패에 「이슈 이동에 실패했습니다」가 뜨면
   * 사용자는 **하지도 않은 동작**의 이름을 받는다. PR ⑤ 가 칸반 보드 지정에 404 를 넣으면서
   * 이 경로가 실제로 실패할 수 있게 됐다 — 그 전에는 항상 201 이었다.
   */
  sprintCreateFailedError: '스프린트 생성에 실패했습니다. 다시 시도해 주세요.',

  /**
   * 스프린트를 받을 수 없는 보드를 지정했을 때 (FR-BD-04 PR ⑤ · 404).
   *
   * 🛑 「다시 시도」를 말하지 않는다. 이 실패는 **재시도로 절대 성공하지 않는다** —
   * 칸반 보드는 활성 스프린트를 조회하지 않으므로 스프린트를 매달 수 없다. 읽기 경로는
   * 막지 않아 그 보드의 백로그 URL 은 200 으로 열리므로, 사용자는 자기가 어느 보드에
   * 있는지 모른 채 이 실패를 만난다(보드 스위처는 스크럼만 노출한다).
   */
  sprintCreateBoardRejectedError: '이 보드에는 스프린트를 만들 수 없습니다. 스크럼 보드를 선택해 주세요.',

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

  // ── FR-UX-13 F15 — 섹션 접기/펼치기 (FR-2) ──────────────────────────────

  /**
   * 섹션 접기/펼치기 토글의 접근 이름.
   *
   * **상태(접힘/펼침)로 이름을 바꾸지 않는다.** 섹션 A 는 접힘이고 B 는 펼침인 상태가
   * **동시에** 성립하므로, 상태별 이름을 쓰면 같은 화면에 두 이름이 공존해
   * `create-entry-point-names.test.ts` §제외 3종 ②「같은 버튼의 다른 상태」 면제가
   * 성립하지 않는다 (스펙 §리뷰 반영 C-9). 상태는 `aria-expanded` 가 말한다.
   *
   * 이름은 **`aria-label` 로만** 준다 — `sr-only` 텍스트 노드를 넣으면 섹션 textContent
   * 맨 앞에 글자가 끼어 `backlog.spec.ts` 의 `^` 앵커 정규식이 깨진다 (FR-2).
   */
  collapseSection: (sectionName: string): string => `${sectionName} 섹션 접기/펼치기`,

  // ── FR-UX-13 F15 — 스프린트 시작 다이얼로그 (FR-3 · FR-4) ────────────────
  //
  // ⚠️ 다이얼로그 제목과 제출 버튼 이름은 **`startSprint`('스프린트 시작')를 재사용**한다.
  //    새 문자열을 만들면 「이름 중복이 없다」 판별식이 깨지고, 즉사 계약이 고정한
  //    트리거 이름과도 갈린다 (FR-10). 취소 버튼도 `ko.ts` 의 `취소` 를 재사용한다.

  /**
   * 스프린트 편집 폼(`SprintForm`) 문구군 — 시작·편집 다이얼로그 공용.
   *
   * 정본은 모듈 상수 {@link SPRINT_FORM_LABELS} 다. `startDialog` 의 필드 라벨은 같은 값을
   * 참조하는 **별칭**이라 두 경로가 갈릴 수 없다 — 기존 경로를 유지하는 이유는
   * `e2e/backlog.spec.ts` 와 `backlog-labels.test.ts` 가 그 경로로 읽고 있어서다.
   */
  sprintForm: SPRINT_FORM_LABELS,

  /** 스프린트 시작 다이얼로그 문구군 */
  startDialog: {
    /** DialogDescription — aria-describedby 를 채운다 */
    description: '기간과 목표를 확인한 뒤 스프린트를 시작합니다.',
    /** 시작일 필드 라벨. 정본은 `sprintForm` */
    startDateLabel: SPRINT_FORM_LABELS.startDateLabel,
    /** 종료일 필드 라벨. 정본은 `sprintForm` */
    endDateLabel: SPRINT_FORM_LABELS.endDateLabel,
    /** 목표 필드 라벨. 정본은 `sprintForm` */
    goalLabel: SPRINT_FORM_LABELS.goalLabel,
    /** E8 — 종료일이 시작일보다 빠를 때의 필드 에러. 정본은 `sprintForm` */
    endBeforeStart: SPRINT_FORM_LABELS.endBeforeStart,
    /**
     * 진행 중 제출 버튼 라벨.
     *
     * `startSprint`('스프린트 시작')를 부분 문자열로 포함하지만 **판별식 목록에 넣지 않는다** —
     * 한 버튼이 둘 중 하나만 보이므로 §제외 3종 ②「같은 버튼의 다른 상태」다.
     */
    pending: '스프린트 시작 중…',

    // FR-4 실패 4갈래 — 하나로 뭉뚱그리면 거짓말이 된다.
    // 「전부 실패」와 「절반 성공」은 사용자가 다음에 해야 할 일이 다르다.

    /** ① `PATCH` 가 비-409 로 실패 — 아무것도 바뀌지 않았다. 재시도는 `PATCH` + `start` */
    patchFailed: '기간·목표를 저장하지 못했습니다. 스프린트는 시작되지 않았습니다.',
    /**
     * ② `PATCH` 409(E9) — 남이 먼저 고쳤다. 기준값(`version`)만 서버 최신으로 갈아끼운다.
     *
     * 편집 다이얼로그의 409 와 **같은 상황·같은 처방**이므로 공용 폼 문구를 참조한다
     * (정본 {@link SPRINT_FORM_LABELS}). 문구가 왜 「불러왔다」고 말하지 않는지도 거기 있다.
     */
    patchConflict: SPRINT_FORM_LABELS.conflict,
    /** ③ `start` 가 비-409 로 실패 — 수정은 남았다. 재시도는 `start` 만 나간다(변경분 0) */
    startFailed: '기간·목표는 저장했지만 스프린트를 시작하지 못했습니다.',
    /**
     * ④ `start` 409(E10) — 남이 이미 시작했다.
     *
     * **나머지 셋과 처방이 정반대다** — 재시도해도 반드시 409 이므로 재시도 버튼을 주지 않고
     * 백로그를 invalidate 한 뒤 다이얼로그를 닫는다.
     */
    startConflict: '이미 시작된 스프린트입니다.',
  },

  // ── FR-BL-02 D6 — 스프린트 편집 다이얼로그 (FR-1 · FR-2 · J1 · J2) ─────────
  //
  // ⚠️ 다이얼로그 제목과 제출 버튼 이름은 **`editSprint`('스프린트 편집')를 재사용**한다.
  //    시작·완료 다이얼로그가 세운 관례이고, `⋯` 메뉴 항목도 같은 문자열을 쓴다.

  /** 스프린트 편집 다이얼로그 문구군 */
  editDialog: {
    /** DialogDescription — aria-describedby 를 채운다 */
    description: '스프린트의 이름·기간·목표를 수정합니다.',
    /**
     * 진행 중 제출 버튼 라벨.
     *
     * `editSprint`('스프린트 편집')를 부분 문자열로 포함하지만 **판별식 목록에 넣지 않는다** —
     * 한 버튼이 둘 중 하나만 보이므로 「같은 버튼의 다른 상태」다
     * (`create-entry-point-names.test.ts` §제외 3종 ②).
     */
    pending: '스프린트 편집 중…',
    /**
     * 비-409 저장 실패. 아무것도 바뀌지 않았으므로 재시도가 그대로 다시 보낸다.
     *
     * 시작 다이얼로그의 `patchFailed` 를 재사용하지 않는다 — 그 문구는 「스프린트는
     * 시작되지 않았습니다」까지 말해 편집 화면에서는 거짓이다(시작을 시도한 적이 없다).
     */
    saveFailed: '스프린트를 수정하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    /** 낙관적 잠금 409. 시작 다이얼로그의 409 와 같은 상황이라 공용 폼 문구를 참조한다 */
    saveConflict: SPRINT_FORM_LABELS.conflict,
  },

  // ── FR-BL-02 D6 — 스프린트 칸 헤더 `⋯` 관리 메뉴 (FR-3 · FR-5 · J3~J5) ─────
  //
  // ⚠️ 메뉴 항목 이름은 최상위 `editSprint`·`deleteSprint` 를 재사용한다. 새 문자열을
  //    만들면 「이름 중복이 없다」 판별식이 지키는 집합과 화면이 갈린다.

  /** 스프린트 `⋯` 관리 메뉴 문구군 */
  sprintActions: {
    /**
     * `⋯` 트리거의 aria-label.
     *
     * 보이는 것은 점 세 개뿐이라 이름이 없으면 「메뉴」로만 읽힌다. **스프린트 이름을
     * 포함하는 것이 요구사항이다** — 스프린트가 여러 개면 같은 화면에 트리거가 N개 뜨는데
     * 이름이 같으면 조회가 strict mode 로 깨진다 (`createIssueInSprint` 와 같은 계약).
     *
     * @param sprintName 대상 스프린트 이름
     * @returns "스프린트 관리, {sprintName}"
     */
    triggerAriaLabel: (sprintName: string): string => `스프린트 관리, ${sprintName}`,

    /**
     * 삭제 확인 설명 — **이슈 개수에 따라 갈린다** (Sanity ❓3 · E-5).
     *
     * 0건이면 「백로그로 돌아갑니다」를 **아예 넣지 않는다.** 옮길 것이 없는데 옮긴다고
     * 적으면 문구가 거짓이 되고, 사용자는 있지도 않은 이슈를 백로그에서 찾게 된다.
     *
     * 이름은 따옴표로 감싸고 조사는 `을(를)` 형태를 쓴다 — 받침에 따라 갈리기 때문이다
     * (`boardLabels.actions.deleteDialogDescription` 이 실제 화면에서 확인하고 정한 형태).
     *
     * @param sprintName 지울 스프린트 이름
     * @param issueCount 그 스프린트에 담긴 이슈 수
     */
    deleteDescription: (sprintName: string, issueCount: number): string =>
      issueCount > 0
        ? `「${sprintName}」을(를) 지웁니다. 이슈 ${issueCount}건은 삭제되지 않고 백로그로 돌아갑니다.`
        : `「${sprintName}」을(를) 지웁니다.`,

    /** 삭제 확인 버튼 */
    deleteConfirm: '삭제',

    /** 삭제 취소 버튼 */
    deleteCancel: '취소',

    /**
     * 삭제 진행 중 확인 버튼 라벨.
     *
     * `deleteConfirm`('삭제')을 부분 문자열로 포함하지만 **판별식 목록에 넣지 않는다** —
     * 한 버튼이 둘 중 하나만 보이므로 「같은 버튼의 다른 상태」다
     * (`create-entry-point-names.test.ts` §제외 3종 ②).
     */
    deleting: '삭제 중…',

    /** 403 — 권한이 없다. 재시도해도 결과가 같으므로 값이 아니라 사람이 바뀌어야 한다 */
    deleteForbidden: '이 스프린트를 삭제할 권한이 없습니다.',

    /** 404 — 이미 지워졌다. 창을 닫고 목록을 다시 보면 없다 */
    deleteNotFound: '스프린트를 찾을 수 없습니다. 이미 지워졌을 수 있습니다.',

    /** 그 밖의 상태 코드 — 그냥 다시 보내면 되는 실패 */
    deleteFailed: '스프린트를 지우지 못했습니다.',

    /**
     * 상태 코드조차 없는 실패 — 연결이 끊겼거나 응답이 상한 안에 오지 않았다.
     *
     * `useDeleteSprint` 의 `DeleteTimeoutError`(`lib/delete-timeout.ts`)도 여기로 온다.
     * 사용자가 할 다음 행동이 같기 때문에 두 경우를 한 문구로 묶는다
     * (`boardLabels.actions.noResponse` 와 같은 규칙).
     */
    noResponse: '서버 응답이 없습니다. 연결을 확인하고 다시 시도해 주세요.',
  },

  // ── FR-UX-13 F15 — 스프린트 완료 다이얼로그 (FR-5 · FR-6 · FR-7) ─────────

  /** 스프린트 완료 다이얼로그 문구군 */
  completeDialog: {
    /** DialogDescription — aria-describedby 를 채운다 */
    description: '미완료 이슈를 옮긴 뒤 스프린트를 완료합니다.',
    /** 요약 행 — 완료/미완료 건수 */
    summary: (done: number, remaining: number): string => `완료 ${done}건 · 미완료 ${remaining}건`,
    /** E11 — 미완료가 0건일 때. 목록·Select 없이 이 문구만 보인다 */
    noIssuesToMove: '옮길 이슈가 없습니다.',
    /** 이관 대상 Select 라벨 */
    moveTargetLabel: '이관 대상',
    /** 이관 대상 Select 의 기본 옵션. 칸 헤더의 「백로그」와 같은 문자열이어야 한다 */
    backlogOption: BACKLOG_SECTION_NAME,
    /** 이관에 성공한 행 — 재시도 대상에서 빠지고 시각적으로 잠긴다 */
    rowMoved: '이관됨',
    /** 이관에 실패한 행 */
    rowFailed: '이관 실패',
    /** 이관 진행 표시 — 직렬 실행이므로 진행 수를 정직하게 셀 수 있다 */
    moveProgress: (done: number, total: number): string => `이관 중 ${done}/${total}`,
    /**
     * 부분 실패 요약 alert (`role="alert"`).
     *
     * 「스프린트는 완료되지 않았습니다」를 반드시 붙인다 — 1건이라도 실패하면 `complete` 를
     * 보내지 않는 것이 안전 요구이고(C1 영구 동결), 사용자가 그 사실을 알아야 재시도한다.
     */
    moveFailedAlert: (total: number, failed: number): string =>
      `이슈 ${total}개 중 ${failed}개를 옮기지 못했습니다. 스프린트는 완료되지 않았습니다.`,
    /**
     * 403 — 이관 권한 없음 (C-15).
     *
     * 이관(`POST /{id}/issues`·`DELETE`)은 **UPDATE** 권한인데 `complete` 만 CREATE 다.
     * CREATE 만 있는 사용자는 전건 403 을 받으므로 별도 문구가 필요하다.
     */
    moveForbidden: '이슈를 옮길 권한이 없습니다. 스프린트는 완료되지 않았습니다.',
    /**
     * E14 — 워크플로우 조회 실패. 전건을 미완료로 보고(FR-7 안전측) **제출도 막는다**.
     *
     * 차단 사실을 말하는 것이 요구다. 버튼만 잠그고 「모든 이슈를 미완료로 봅니다」까지만
     * 말하면 사용자는 왜 눌리지 않는지 알 수 없다. `truncatedBlocked` 의 **결**(「…어
     * 안전하게 완료할 수 없습니다」)을 따르되 그 값을 재사용하지 않는다 —
     * 「일부 이슈만 표시되어」는 여기서 거짓이고(목록은 전건 다 있다), 처방도 다르다.
     */
    workflowLoadFailed:
      '상태 분류를 불러오지 못해 안전하게 완료할 수 없습니다. 모든 이슈를 미완료로 표시하고 있으니 잠시 후 다시 시도해 주세요.',
    /** E15 — 목록이 불완전하면 「0건」이라는 관측 자체를 믿을 수 없다. 제출을 막는다 */
    truncatedBlocked: '일부 이슈만 표시되어 안전하게 완료할 수 없습니다.',
    /**
     * C-7 — 완료 직전 재검증이 막았을 때.
     *
     * 갈래 셋(재조회 실패 · 그 사이 미완료가 늘었다 · `complete` 자체가 실패)이 공유하는
     * **참인 사실**만 말한다. 원인을 단정하면 셋 중 둘에서 거짓이 된다.
     *
     * ★ 원래 `startDialog.patchConflict` 를 재사용했다. 그 문구가 「입력하신 값은 그대로
     *   두었으니」로 바뀌면서 **입력 폼이 없는** 완료 다이얼로그에서 거짓이 되어 분리했다.
     */
    staleBlocked: '스프린트를 완료하지 못했습니다. 최신 목록을 확인한 뒤 다시 시도해 주세요.',
    /** 진행 중 제출 버튼 라벨. `pending` 은 §제외 3종 ② 라 판별식에 넣지 않는다 */
    pending: '스프린트 완료 중…',
  },

  // ── FR-UX-13 F15 — 드래그 공지 (FR-9 · FR-17) ───────────────────────────
  //
  // 조사(助詞) 계산이 필요 없도록 가변 부분 뒤에 항상 고정 명사를 붙였다.
  // 「스프린트」·「백로그」는 종성이 없어 항상 `스프린트로`·`백로그로` 다 — 받침 판별이
  // **원리적으로** 불필요하다. 따라서 `KanbanBoard.tsx` 의 비-export `josaEuro` 를
  // 공용 모듈로 끌어내는 리팩터를 하지 않는다 (무관한 파일 무변경).

  /** dnd-kit `accessibility.announcements` 에 들어가는 한국어 공지 */
  announce: {
    /** onDragStart — 내부 droppable id 가 아니라 **이슈 키**를 읽는다 */
    dragStart: (issueKey: string): string => `${issueKey} 카드를 집었습니다.`,
    /** onDragOver — 스프린트 섹션 위 */
    overSprint: (sprintName: string): string => `${sprintName} 스프린트 위에 있습니다.`,
    /** onDragOver — 백로그 섹션 위 */
    overBacklog: '백로그 위에 있습니다.',
    /** onDragOver — 드롭 대상 없음 (E4 접힌 섹션 포함) */
    outOfDropZone: '드롭 가능한 영역을 벗어났습니다.',
    /**
     * 판정이 `noop-move` 인 경우 (C-4).
     *
     * 원안이 `onDragEnd` 를 4종으로 적어 이 갈래를 빠뜨렸다. 빠뜨리면 그 순간
     * 스크린리더가 **침묵**하는데 테스트는 초록이다.
     */
    cannotMoveHere: '이동할 수 없는 위치입니다.',
    /** onDragEnd — assign */
    movedToSprint: (sprintName: string): string => `${sprintName} 스프린트로 옮겼습니다.`,
    /** onDragEnd — unassign */
    movedToBacklog: '백로그로 옮겼습니다.',
    /** onDragEnd — rerank */
    reordered: '순서를 변경했습니다.',
    /** onDragEnd — noop */
    noChange: '변경 사항이 없습니다.',
    /** onDragCancel (E16) */
    cancelled: '취소했습니다.',
    /**
     * FR-17 — UPDATE 권한이 없는 사용자의 onDragEnd.
     *
     * `use-backlog-drag.ts` 의 `if (!canReorderIssue) return` 이 mutation 을 0건으로 막는데
     * 공지가 「옮겼습니다」를 읽으면 스크린리더 사용자에게만 거짓말을 하게 된다.
     */
    forbidden: '권한이 없어 이동할 수 없습니다.',
    /**
     * `screenReaderInstructions.draggable`.
     *
     * 지정하지 않으면 dnd-kit 의 **영어 기본값**이 그대로 남는다 — 공지가 꺼져 있는 것이 아니라
     * 영어로 켜져 있는 것이다 (착수 전 실측으로 정정한 서술).
     */
    instructions: '스페이스바로 카드를 집습니다. 방향키로 이동하고, 스페이스바로 놓거나 Esc 로 취소합니다.',
  },

  // ── FR-UX-13 F16 — 백로그 필터 바 ────────────────────────────────────────

  /** 백로그 필터 바(제목 검색 + 에픽 축) 문구군 */
  filter: {
    /**
     * 제목 검색 입력의 라벨 겸 접근명.
     *
     * **`검색` 으로 짓지 않는다** — 상단바 전역 검색이 `전역 검색`(`role="searchbox"`), AQL 페이지
     * 제출 버튼이 `검색` 이고 e2e 가 `exact: true` 로 그 둘을 가르고 있다. 같은 화면에 세 번째
     * `검색` 이 생기면 이름 분리 계약이 즉사한다 (`jira-parity-contract.md` §2).
     */
    searchLabel: '백로그 검색',

    /** 제목 검색 입력 placeholder — `담당자 검색...` 과 같은 결 */
    searchPlaceholder: '제목 검색...',

    /**
     * `NO_EPIC`(`lib/backlog-filter.ts`) 센티널의 표시명.
     *
     * 필터 바의 에픽 칩과 에픽 패널의 「에픽 없음」 항목이 **같은 문자열**이어야 칩과 목록이
     * 서로 다른 말을 하지 않는다. 두 컴포넌트가 여기서 함께 읽는다.
     */
    noEpic: '에픽 없음',
  },
} as const

/** backlogLabels const 추론 타입 */
export type BacklogLabels = typeof backlogLabels

/** 권한 미충족 — `deleteSprint` 의 `ApiError` 계약 */
const FORBIDDEN_STATUS = 403

/** 스프린트 미존재 — `deleteSprint` 의 `ApiError` 계약 */
const NOT_FOUND_STATUS = 404

/**
 * 스프린트 삭제 실패의 HTTP 상태를 사용자 문구로 바꾼다 (FR-3 · E-3).
 *
 * ### 왜 화면이 아니라 여기인가
 * 화면마다 인라인 매핑을 만들면 키가 갈려 raw 코드가 그대로 노출되는 「가짜 그린」이 난다
 * (PR #106). 형제 `boardManageErrorMessage`(`i18n/board-labels.ts`)가 세운 관례를 따른다.
 *
 * ### 왜 errorCode 가 아니라 status 인가
 * `deleteSprint`(`api/backlog.ts`)가 던지는 `ApiError` 의 계약이 **상태 코드**로 적혀 있고
 * (`404 = 스프린트 미존재, 403 = 권한 미충족`) 그 두 갈래를 `use-backlog.test.tsx` 가 이미
 * 고정했다. 보드 쪽이 `errorCode` 를 쓰는 것은 그 엔드포인트가 코드를 4종으로 나눠 주기
 * 때문이고, 여기서 흉내내면 백엔드가 주지 않는 코드를 기다리는 죽은 분기가 된다.
 *
 * @param status 응답 상태 코드. 응답 자체가 없었으면(연결 끊김 · 삭제 상한) `null`
 * @returns 사용자에게 보여줄 한국어 문구
 */
export function sprintDeleteErrorMessage(status: number | null): string {
  const actions = backlogLabels.sprintActions
  if (status === null) return actions.noResponse
  switch (status) {
    case FORBIDDEN_STATUS:
      return actions.deleteForbidden
    case NOT_FOUND_STATUS:
      return actions.deleteNotFound
    default:
      return actions.deleteFailed
  }
}
