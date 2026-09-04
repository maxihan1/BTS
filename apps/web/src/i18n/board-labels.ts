// 칸반 보드 WIP/스윔레인 i18n 라벨

/**
 * 칸반 보드 컬럼 헤더가 노출하는 한국어 라벨/텍스트.
 *
 * 그룹 — placeholder(최상위) / createForm / page / switcher / actions / wip / column / swimlane
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const boardLabels = {
  /** 보드 생성 폼(`board/CreateBoardForm.tsx`) 이름 input 의 예시 placeholder */
  createFormNamePlaceholder: '스프린트 보드',

  /** 보드 페이지 상단 보드 스위처가 현재 보드를 못 찾았을 때의 대체 표기 */
  boardSelectPlaceholder: '보드 선택',

  /**
   * 보드 생성 폼(`board/CreateBoardForm.tsx`) 2단계 wizard 문구 (FR-BD-04 D6).
   *
   * 정본은 ADR `docs/adr/2026-09-01-board-type-and-active-sprint.md` **§D4**,
   * Jira 근거는 **J1**(보드 생성 모달에서 *"Create a Scrum board"* 또는
   * *"Create a Kanban board"* 를 고른다)와 **J2**(*"enter a board name"*)다.
   * 순서가 계약이다 — **종류를 먼저 묻고**(1단계) 그 다음 이름을 받는다(2단계).
   *
   * 🛑 **「보드 만들기」를 이 그룹에 담지 않는다.** 그 문자열은 2단계 제출 버튼 전용이고
   *    `e2e/board-manage.spec.ts` 가 `exact: true` 로 그것을 잡는다. 1단계 진행 버튼에
   *    같은 문구를 쓰면 한 흐름 안에 같은 이름의 버튼이 둘이 돼 어느 쪽을 눌렀는지가
   *    화면에서도 테스트에서도 흐려진다 — `switcher.createItem` 이 「새 보드」인 것과 같은 계약.
   *
   * ⚠️ `createFormNamePlaceholder` 는 최상위에 그대로 둔다. 여기로 옮기면 이 변경의 범위
   *    밖인 `CreateBoardForm.tsx` 의 참조가 깨진다 — 통합은 그 파일을 만지는 작업이 한다.
   */
  createForm: {
    /**
     * 1단계 — 종류 선택 (ADR §D4 「1단계. 종류 선택」 · J1).
     *
     * 라벨과 설명 네 문자열은 §D4 다이어그램의 원문을 그대로 옮긴 것이다.
     * 「○ 스크럼 보드 — 스프린트로 일하는 팀」 · 「○ 칸반 보드 — 흐름으로 일하는 팀」.
     */
    typeStep: {
      /**
       * 종류 선택 라디오 그룹의 레이블 — ADR §D4 「종류 선택」.
       *
       * `role="radiogroup"` 의 접근성 이름이 된다. 라디오 두 개만으로는 스크린리더가
       * 「무엇을 고르는 중인가」를 읽어 줄 수 없다.
       */
      groupLabel: '보드 종류',

      /** 스크럼 라디오 라벨 — ADR §D4 「스크럼 보드」 (J1 *"Create a Scrum board"*) */
      scrumLabel: '스크럼 보드',

      /**
       * 스크럼 라디오 보조 설명 — ADR §D4 「스프린트로 일하는 팀」.
       *
       * 종류 이름만으로는 스크럼/칸반을 아는 사람에게만 선택이 가능하다. 「스프린트」·「흐름」이
       * 두 라디오를 가르는 유일한 판단 근거이므로 라벨과 함께 반드시 노출한다.
       */
      scrumDescription: '스프린트로 일하는 팀',

      /** 칸반 라디오 라벨 — ADR §D4 「칸반 보드」 (J1 *"Create a Kanban board"*) */
      kanbanLabel: '칸반 보드',

      /** 칸반 라디오 보조 설명 — ADR §D4 「흐름으로 일하는 팀」 (`scrumDescription` 과 같은 이유) */
      kanbanDescription: '흐름으로 일하는 팀',
    },

    /**
     * 1단계 → 2단계 진행 버튼.
     *
     * 두 문구를 단계 그룹 안이 아니라 여기 나란히 두는 이유 — 이 둘은 wizard 의 **이동 축**
     * 한 쌍이라 한쪽만 바뀌면 흐름이 어긋난다. 붙여 두면 그 짝이 눈에 보인다.
     *
     * 🛑 「보드 만들기」가 아니다 (위 그룹 주석의 즉사 계약).
     */
    next: '다음',

    /** 2단계 → 1단계 복귀 버튼 — 고른 종류를 확정 전에 되돌릴 수 있는 유일한 자리다 */
    back: '뒤로',
  },

  /** 보드 페이지 — 헤더 및 뷰 전환 링크 */
  page: {
    /**
     * 보드 화면 h1.
     *
     * 형제 뷰(`backlogLabels.page.title` = '백로그')와 같은 규칙이다 — 지금 보고 있는 뷰의
     * 이름을 쓴다. 이 키가 없던 동안 보드만 h1 이 0개라 헤더에 즐겨찾기 별 아이콘 하나만
     * 떠 있었고, 문서당 h1 계약도 그 화면에서만 비어 있었다.
     */
    title: '보드',
    /*
     * ★뷰 전환 링크 2종(백로그·타임라인)이 여기 있었다. Jira 패리티 J5 로 탭바가 정본 9탭을
     * 소유하면서 이 페이지의 인라인 nav 가 사라졌고 그 라벨들도 함께 나갔다.
     * 탭 라벨의 정본은 `i18n/project-view-labels.ts` 다.
     */
    /**
     * 보드 헤더 이슈 생성 진입점 (FR-UX-09 F3 FR-3).
     *
     * 🛑 **컬럼별이 아니라 보드 1곳**이다 — 생성 계약에 상태(`stateKey`)가 없어
     * 컬럼별 버튼은 지키지 못할 약속이 된다 (ADR 2026-08-03 D-3).
     *
     * ⚠️ 값을 고칠 때 `i18n/__tests__/create-entry-point-names.test.ts` 를 먼저 읽을 것 —
     * 상단바 `만들기` · 모달 제출 `이슈 생성` 을 부분 문자열로 포함하면 기존 e2e 가 깨진다.
     */
    createIssue: '이슈 추가',
  },

  /**
   * 보드 스위처 — 보드가 1개여도 상시 노출되는 전환 드롭다운 (FR-BD-01-2c · Jira 근거 J1·J2).
   *
   * Jira Cloud company-managed 는 프로젝트당 보드를 N개 갖고 생성 진입점을 보드 존재 여부와
   * 무관하게 제공한다. BTS 에는 전역 Boards 디렉터리도 사이드바 hover `+` 도 없어
   * 진입점 2곳을 이 드롭다운 하나로 접었다 (plan 의 의도적 편차 X1).
   */
  switcher: {
    /**
     * 스위처 트리거의 aria-label.
     *
     * 트리거의 보이는 텍스트는 현재 보드 **이름**이라 프로젝트마다 접근성 이름이 달라진다.
     * 역할을 앞에 고정해 「이 버튼이 무엇인가」를 이름만으로 알 수 있게 하고, 현재 값도 함께 읽힌다.
     *
     * @param currentName 현재 선택된 보드 이름
     * @returns "보드 선택, 현재 {currentName}"
     */
    triggerAriaLabel: (currentName: string): string => `보드 선택, 현재 ${currentName}`,

    /** 드롭다운 안 보드 라디오 그룹의 섹션 라벨 */
    groupLabel: '보드',

    /**
     * 보드 생성 진입 메뉴 항목.
     *
     * 🛑 「보드 만들기」로 쓰지 마라 — `CreateBoardForm` 제출 버튼이 그 문자열이고
     *    `e2e/board-kanban.spec.ts` 가 `exact: true` 로 그것을 잡는다. 이 항목이 열어 주는
     *    다이얼로그 안에 바로 그 버튼이 들어가므로, 같은 문구를 쓰면 「어느 쪽을 눌렀는가」가
     *    화면에서도 테스트에서도 흐려진다.
     */
    createItem: '새 보드',

    /** 보드 생성 다이얼로그 제목 — Radix 가 이 값을 다이얼로그 접근성 이름으로 쓴다 */
    createDialogTitle: '새 보드',
  },

  /**
   * 보드 관리 `⋯` 메뉴 — 이름 변경 · 삭제 (FR-BD-01-2a/2b · Jira 근거 J3·J4).
   *
   * Jira Cloud 는 이름 변경을 **보드 설정 화면**의 연필로, 삭제를 **Boards 디렉터리**의 행 `⋯`
   * 로 한다. BTS 에는 그 두 화면이 없어 plan 의 의도적 편차 X2 대로 조작을 보드 헤더의 `⋯`
   * 하나로 모았다. 권한이 없으면 항목을 **렌더하지 않는다**(비활성이 아니다 · J5).
   */
  actions: {
    /**
     * `⋯` 트리거의 aria-label.
     *
     * 보이는 것은 점 세 개뿐이라 이름이 없으면 「메뉴」로만 읽힌다. 무엇에 대한 메뉴인지와
     * 대상 보드를 함께 싣는다 — 스위처 트리거와 나란히 있어 구별이 필요하다.
     *
     * @param boardName 현재 보드 이름
     * @returns "보드 관리, {boardName}"
     */
    triggerAriaLabel: (boardName: string): string => `보드 관리, ${boardName}`,

    /**
     * 보드 설정 진입 메뉴 항목 (부채 177 · J7 · 편차 X4).
     *
     * 지라는 사이드바 보드 이름 옆 `⋯` → **Configure board** 로 들어간다(J7). BTS 사이드바는
     * 보드를 개별 노드로 갖지 않아(`?board=` 로 전환한다) 이 `⋯` 에 얹는다 — 새 진입점을
     * 만들지 않는다.
     */
    settingsItem: '보드 설정',

    /** 이름 변경 메뉴 항목 */
    renameItem: '이름 변경',

    /** 삭제 메뉴 항목 */
    deleteItem: '보드 삭제',

    /** 이름 변경 다이얼로그 제목 — Radix 가 접근성 이름으로 쓴다 */
    renameDialogTitle: '보드 이름 변경',

    /** 이름 변경 입력 라벨 */
    renameNameLabel: '보드 이름',

    /** 이름 변경 제출 버튼 */
    renameSubmit: '저장',

    /** 이름 변경 취소 버튼 */
    renameCancel: '취소',

    /** 이름 변경 실패 — 모르는 코드일 때의 기본 문구 */
    renameFailed: '보드 이름을 바꾸지 못했습니다.',

    /**
     * 삭제 확인 다이얼로그 제목.
     *
     * 🛑 **화면 안에서 고유해야 한다** — 같은 이름의 dialog 가 둘이면 Playwright
     * `getByRole('dialog', { name })` 가 strict mode 로 즉사한다 (계약 §2).
     */
    deleteDialogTitle: '보드 삭제',

    /**
     * 삭제 확인 설명.
     *
     * 「이슈는 삭제되지 않습니다」가 **소프트 삭제의 유일한 고지**다(J4). 이 문장이 빠지면
     * 사용자는 보드를 지우면 이슈도 사라진다고 읽는다.
     *
     * 이름은 따옴표로 감싼다 — 「ATLAS 보드 보드를 지웁니다」처럼 이름 끝의 「보드」가 겹쳐 읽히는
     * 것을 실제 화면에서 확인하고 고쳤다. 조사는 이름의 받침에 따라 갈리므로 `을(를)` 형태를 쓴다.
     *
     * @param boardName 지울 보드 이름
     */
    deleteDialogDescription: (boardName: string): string =>
      `「${boardName}」을(를) 지웁니다. 이 보드의 이슈는 삭제되지 않습니다.`,

    /** 삭제 확인 버튼 */
    deleteConfirm: '삭제',

    /** 삭제 취소 버튼 */
    deleteCancel: '취소',

    /** 삭제 실패 — 모르는 코드일 때의 기본 문구 */
    deleteFailed: '보드를 지우지 못했습니다.',

    /**
     * 상태 코드조차 없는 실패 — 연결이 끊겼거나 응답이 상한 안에 오지 않았다.
     *
     * `useDeleteBoard` 의 타임아웃도 여기로 온다. 사용자가 할 다음 행동이 같기 때문에
     * 두 경우를 한 문구로 묶는다 (`lib/move-error-message.ts` 가 세운 세 갈래와 같은 규칙).
     */
    noResponse: '서버 응답이 없습니다. 연결을 확인하고 다시 시도해 주세요.',
  },

  /** WIP(Work In Progress) 제한 관련 라벨 */
  wip: {
    /**
     * 카드 수/WIP 한도 표기 문자열 생성.
     * @param count 현재 카드 수
     * @param limit WIP 한도
     * @returns "{count}/{limit}"
     */
    countLabel: (count: number, limit: number): string => `${count}/${limit}`,

    /** WIP 초과 경고 aria-label — 스크린리더 및 시각 경고 용도 */
    exceededAriaLabel: 'WIP 초과',

    /** WIP 초과 경고 툴팁/보조 텍스트 */
    exceededTooltip: 'WIP 제한을 초과했습니다',

    /** 필터 활성 시 WIP 배지 "(필터됨)" 접미 라벨 */
    filteredSuffix: '(필터됨)',

    /** 필터 활성 시 WIP 배지 aria-label — 전체 WIP 상태 미반영 고지 */
    filteredAriaLabel: '필터 적용 중, 전체 WIP 상태 아님',
  },

  /** 컬럼 헤더 일반 라벨 */
  column: {
    /**
     * 컬럼 aria-label 생성 함수.
     * @param name 컬럼 이름
     * @param count 카드 수
     * @returns "{name} 컬럼, {count}개 카드"
     */
    ariaLabel: (name: string, count: number): string =>
      `${name} 컬럼, ${count}개 카드`,

    /**
     * 카드 수 배지 aria-label 생성 함수.
     * @param count 카드 수
     * @returns "카드 {count}개"
     */
    cardCountAriaLabel: (count: number): string => `카드 ${count}개`,

    /** 카테고리 배지 aria-label 접두사 */
    categoryAriaLabel: (category: string): string => `카테고리: ${category}`,
  },
  /** 스윔레인 셀렉터 관련 라벨 */
  swimlane: {
    /** 셀렉터 접근성 레이블 */
    selectorLabel: '스윔레인',

    /** 셀렉터 옵션 라벨 — SwimlaneField enum 값에 대응 */
    options: {
      /** NONE — 스윔레인 없음 */
      NONE: '없음',
      /** ASSIGNEE — 담당자별 그룹 */
      ASSIGNEE: '담당자',
      /** PRIORITY — 우선순위별 그룹 */
      PRIORITY: '우선순위',
      /** EPIC — 에픽별 그룹 */
      EPIC: '에픽',
    },

    /** 변경 실패 시 토스트 오류 메시지 */
    updateError: '스윔레인 기준 변경에 실패했습니다',
  },

  /**
   * 보드 설정 화면 (부채 177 · 지라 Board settings 의 Columns 탭).
   *
   * 이 PR 은 **Columns 하나만** 만든다. 나머지 6탭을 비활성 골격으로 미리 그리지 않는다 —
   * 「누를 수 있는데 아무 일도 안 일어나는」 화면을 6개 배포하는 것이라, 장부가 경계한
   * 「도달할 UI 가 없는 기능」의 거울상이다(Maxi 확정 2026-09-04).
   */
  settings: {
    /** 화면 h1. 즉사 계약상 화면당 하나뿐이고 문자열이 곧 e2e 셀렉터다. */
    pageHeading: '보드 설정',

    /** h1 아래 설명 */
    pageDescription: '컬럼 구성과 워크플로우 상태 매핑을 바꿉니다.',

    /** 보드 화면으로 돌아가는 링크 */
    backToBoard: '보드로 돌아가기',

    /**
     * `?board=` 없이 들어왔을 때의 안내.
     *
     * ★**기본 보드를 스스로 고르지 않는다.** 「기본 보드」 규칙이 이미 세 곳에서 서로 다르고
     * (부채 164), 여기서 네 번째를 만들면 그 부채가 커진다. 보드를 지목하게 하고 되돌린다.
     */
    boardNotSelected: '어느 보드의 설정인지 지목되지 않았습니다. 보드 화면에서 다시 들어오세요.',

    /** 조회 실패 안내 */
    loadError: '보드 설정을 불러오지 못했습니다.',

    /** 조회 실패 시 재시도 버튼 */
    retry: '다시 시도',

    /** CREATE 권한이 없는 사용자에게 보이는 사유 */
    readOnlyReason: '보드를 설정할 권한이 없습니다.',

    /** 미매핑 상태 패널 제목 — 지라 `Unmapped statuses` 대응 */
    unmappedHeading: '미매핑 상태',

    /**
     * 미매핑이 0건일 때.
     *
     * ★**경고가 아니라 안심이다.** 미매핑 0 은 정상 상태다 — 모든 워크플로우 상태가 컬럼에
     * 배정됐다는 뜻이다. 회색 「없음」으로 그리면 사용자가 정상을 결손으로 읽는다.
     */
    unmappedEmpty: '모든 상태가 컬럼에 배정됐습니다.',

    /** 미매핑 패널 설명 — 무엇을 하는 곳인지 */
    unmappedDescription: '어느 컬럼에도 속하지 않은 상태입니다. 그 상태의 이슈는 보드에 나타나지 않습니다.',

    /** 컬럼이 하나도 없을 때 — 행동 유도가 필요한 빈 상태다 */
    columnsEmpty: '이 보드에 컬럼이 없습니다. 컬럼을 만들면 상태를 끌어다 놓을 수 있습니다.',

    /** 상태 0개 컬럼의 표시 — 미완성임을 명시한다(E1). 그냥 비워 두면 정상으로 보인다 */
    columnNoStates: '상태 없음',

    /**
     * 컬럼 카드의 카드 수 표기.
     *
     * @param count 이 컬럼의 카드 수
     * @returns "카드 {count}개"
     */
    cardCount: (count: number): string => `카드 ${String(count)}개`,

    /**
     * 카드 수가 조회 상한에 잘렸을 때의 표기 (eng 리뷰 BLOCKER-1 · 리뷰 CONCERNS C5).
     *
     * 보드 조회는 `BOARD_CARD_FETCH_LIMIT`(1000)에서 잘린다. 잘린 목록의 길이를 정확한 수인 양
     * 보이면 거짓말이 되므로 **수를 주장하지 않는다.**
     *
     * ★종전 문구 「카드 1000개 이상」은 두 번 틀렸다. ①`truncated` 는 **보드 단위** 플래그라
     * 1001장짜리 보드의 카드 3장짜리 컬럼도 「1000개 이상」으로 읽혔다 — 컬럼에 대해 참이 아니다.
     * ②삭제 창에 끼우면 「카드 1000개 이상**가** 사라집니다」로 조사가 깨졌다. 지금은 수를 아예
     * 주장하지 않고, 삭제 창은 [deleteColumnDescriptionTruncated] 로 문장을 따로 쓴다.
     */
    cardCountTruncated: '카드 수 확인 불가',

    /**
     * 상태 매핑 실패 — 409 전용 문구 (E3).
     *
     * 한 상태는 한 컬럼에만 속할 수 있다(#444 X1). 공통 실패 문구로 뭉개면 사용자가
     * 「다시 해 보면 되나」로 읽는데, 이 실패는 재시도로 풀리지 않는다.
     */
    stateConflict: '그 상태는 이미 다른 컬럼에 있습니다. 먼저 그 컬럼에서 빼세요.',

    /**
     * 상태 매핑 실패 — 그 밖의 모든 실패 (G2).
     *
     * 409 만 되돌리고 나머지를 삼키면 네트워크 단절·500 에서 화면이 서버와 다른 것을
     * 보여 준다. 문구는 공통이되 **되돌림은 모든 실패에서** 일어난다.
     */
    stateChangeFailed: '상태 매핑을 바꾸지 못했습니다.',

    /** 컬럼 추가 버튼 · 다이얼로그 제목 (J23) */
    addColumn: '컬럼 추가',

    /** 컬럼 이름 입력 라벨 */
    columnNameLabel: '컬럼 이름',

    /** 컬럼 추가 제출 버튼 */
    addColumnSubmit: '추가',

    /** 공통 취소 */
    cancel: '취소',

    /** 컬럼 추가 실패 */
    addColumnFailed: '컬럼을 추가하지 못했습니다.',

    /**
     * 컬럼 삭제 확인 제목.
     *
     * ★화면 전체에서 **고유**해야 한다 — 같은 이름의 dialog 가 둘이면 Playwright
     * `getByRole('dialog', { name })` 가 strict mode 로 즉사한다(즉사 계약 §2).
     *
     * @param columnName 지울 컬럼 이름
     */
    deleteColumnTitle: (columnName: string): string => `컬럼 삭제: ${columnName}`,

    /**
     * 컬럼 삭제 확인 설명 — 폭발 반경을 먼저 보인다 (S6).
     *
     * 잘린 보드에서는 이 함수를 쓰지 않는다. 수를 넣을 자리가 없어 문장이 통째로 달라지므로
     * [deleteColumnDescriptionTruncated] 를 쓴다 — 조사(「~가」)를 끼워 맞추려다 「1000개 이상가」가
     * 나온 자리다(리뷰 CONCERNS C5).
     *
     * @param cardCountText 영향 카드 수 문구. 「카드 N개」 형태만 온다.
     */
    deleteColumnDescription: (cardCountText: string): string =>
      `이 컬럼의 상태는 미매핑으로 돌아갑니다. ${cardCountText}가 보드에서 사라집니다. 이슈 자체는 지워지지 않습니다.`,

    /**
     * 컬럼 삭제 확인 설명 — 보드가 조회 상한에 잘렸을 때 (S6 · C5).
     *
     * 수를 모른다는 사실 자체가 사용자에게 필요한 정보다. 「0개」로 뭉개면 안심시키고,
     * 「1000개 이상」으로 부풀리면 컬럼에 대해 거짓이 된다.
     */
    deleteColumnDescriptionTruncated:
      '이 컬럼의 상태는 미매핑으로 돌아갑니다. 이 컬럼의 카드가 보드에서 사라집니다 — 보드가 조회 상한에 걸려 몇 장인지 셀 수 없습니다. 이슈 자체는 지워지지 않습니다.',

    /** 컬럼 삭제 확인 버튼 */
    deleteColumnConfirm: '컬럼 삭제',

    /** 컬럼 삭제 실패 */
    deleteColumnFailed: '컬럼을 삭제하지 못했습니다.',

    /**
     * 컬럼이 1개뿐일 때 삭제가 잠기는 사유 (Sanity G1).
     *
     * ★지라에 대응 제약이 없다. **편차가 아니라 결함 회피**다 — 컬럼 0개 보드는 조회가
     * 자가 치유 경합으로 500 이 되고(부채 179), 이 화면이 그 상태로 가는 클릭 한 번짜리
     * 경로를 만들지 않는다.
     */
    lastColumnLocked: '마지막 컬럼은 지울 수 없습니다.',

    /**
     * 컬럼 이름 편집 입력의 접근성 이름 (J24).
     *
     * @param columnName 지금 이름
     */
    renameColumnLabel: (columnName: string): string => `컬럼 이름 변경: ${columnName}`,

    /**
     * WIP 제한 입력의 접근성 이름 (J29 · 편차 X2).
     *
     * ★**최대치 하나뿐이다.** 지라는 *"you can enter a minimum or maximum value"* 로 둘을
     * 받지만 BTS 스키마에 최소치 칸이 없다. 이름에 「최대」를 박아 두 번째 입력이 없는 것이
     * 누락이 아니라 결정임을 화면에서도 읽히게 한다.
     *
     * @param columnName 대상 컬럼
     */
    wipLimitInputLabel: (columnName: string): string => `${columnName} 최대 카드 수`,

    /** 컬럼 순서 드래그 핸들의 접근성 이름 (J25) */
    reorderHandleLabel: (columnName: string): string => `컬럼 순서 변경: ${columnName}`,

    /** 컬럼 갱신(이름·WIP) 실패 */
    updateColumnFailed: '컬럼을 바꾸지 못했습니다.',

    /** 컬럼 순서 변경 실패 */
    reorderFailed: '컬럼 순서를 바꾸지 못했습니다.',

    /** WIP 제한 표시 — 무제한일 때 */
    wipUnlimited: 'WIP 제한 없음',

  },
} as const

/** boardLabels const 추론 타입 */
export type BoardLabels = typeof boardLabels

/**
 * agile-planning 의 backend errorCode 를 보드 관리 조작의 사용자 문구로 바꾼다.
 *
 * ### 왜 화면이 아니라 여기인가
 * 화면마다 인라인 매핑을 만들면 키가 갈려 raw 코드가 그대로 노출되는 「가짜 그린」이 난다
 * (PR #106). 형제 `validatorErrorMessage` · `componentErrorMessage` 가 세운 관례를 따른다.
 *
 * 코드 값의 정본은 backend `BoardController` 의 예외 매핑이다.
 *
 * `fallback` 은 조작별로 기본 문구를 갈아 끼우는 자리다 — 「지우지 못했습니다」와
 * 「이름을 바꾸지 못했습니다」는 같은 코드에서도 서로 다른 다음 행동을 뜻한다.
 *
 * @param errorCode 응답 body 에서 꺼낸 errorCode. 못 읽었으면 `null`.
 * @param fallback 모르는 코드일 때 쓸 문구.
 * @returns 사용자에게 보여줄 한국어 문구.
 */
export function boardManageErrorMessage(errorCode: string | null, fallback: string): string {
  switch (errorCode) {
    case 'AGILE_ACCESS_DENIED':
      return '이 보드를 관리할 권한이 없습니다.'
    case 'AGILE_BOARD_NOT_FOUND':
      return '보드를 찾을 수 없습니다. 이미 지워졌을 수 있습니다.'
    case 'AGILE_VALIDATION_FAILED':
      return '보드 이름을 확인해 주세요. 비어 있을 수 없습니다.'
    case 'AGILE_UNAUTHENTICATED':
      return '로그인이 필요합니다. 다시 로그인해 주세요.'
    default:
      return fallback
  }
}
