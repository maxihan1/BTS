// 워크플로우 관리 admin UI 의 E2E 셀렉터 정본 — 라벨 변경 시 단일 진입점
/**
 * `/admin/workflows` 목록 + 목록 모드 편집기가 노출하는 한국어 라벨.
 *
 * ★ **`nav` 값은 `'워크플로우 관리'` 다. `'워크플로우'` 로 줄이지 마라.**
 * 사이드바에 이미 `'워크플로우 스킴'` 이 있어 `'워크플로우'` 는 그 substring 이 된다.
 * Playwright `getByRole(name:)` 는 **부분일치가 기본**이라 두 링크가 함께 잡혀 strict mode
 * 로 즉사한다 — `jira-parity-contract.md` §2 의 `검색`/`전역 검색` 과 같은 양식이다.
 * `i18n/__tests__/nav-labels.test.ts` FR15 substring 판별식은 `navLabels` 키만 훑으므로
 * 이 리터럴을 **잡아 주지 않는다**. 사람이 지켜야 하는 자리다.
 *
 * 그룹 — placeholder(최상위) / list / editor / statusPanel / transitionPanel / transitionForm / dialog
 *
 * ★ `nav` 블록은 없다. 사이드바·관리 허브 라벨은 `Sidebar.tsx`·`admin.index.tsx` 에 리터럴로
 * 박혀 있고 이 모듈을 안 본다 — 「정본」이라 선언만 하고 소비처가 0 이면 그 선언이 거짓이다.
 * 실제 통합은 부채 123 이다. 그때까지 없는 것으로 둔다.
 */
export const workflowEditorLabels = {
  /** 워크플로우 키 input placeholder */
  keyPlaceholder: '예: my-workflow-01',
  /** 워크플로우 이름 input placeholder */
  namePlaceholder: '워크플로우 이름을 입력하세요',
  /** 워크플로우 설명 input placeholder */
  descriptionPlaceholder: '워크플로우 설명을 입력하세요',
  /** 상태 검색 combobox placeholder */
  statusSearchPlaceholder: '상태 이름으로 검색...',
  /** 전환 이름 input placeholder */
  transitionNamePlaceholder: '예: 검토 요청',

  /** 목록 화면 (`/admin/workflows`) */
  list: {
    /** 페이지 h1 — 페이지당 하나뿐이어야 한다 */
    heading: '워크플로우 관리',
    /** 목록 table aria-label */
    table: '워크플로우 목록',
    /** 새 워크플로우 만들기 버튼 */
    create: '워크플로우 만들기',
    /** 빈 상태 제목 */
    emptyTitle: '워크플로우가 없습니다',
    /** 빈 상태 설명 */
    emptyDescription: '첫 워크플로우를 만들어 상태와 전환을 정의하세요',
    /** 표 헤더 — 이름 */
    columnName: '이름',
    /** 표 헤더 — 키 */
    columnKey: '키',
    /** 표 헤더 — 상태 수 */
    columnStatusCount: '상태',
    /** 표 헤더 — 전환 수 */
    columnTransitionCount: '전환',
    /** 행 액션 — 편집 */
    edit: '편집',
    /** 행 액션 — 복제 */
    duplicate: '복제',
    /** 행 액션 — 삭제 */
    remove: '삭제',
  },

  /** 프로젝트 설정의 워크플로우 화면 (`/projects/$projectKey/settings/workflows`) */
  projectSettings: {
    /** 페이지 h1 */
    heading: '워크플로우',
    /** 페이지 설명 */
    description: '이 프로젝트가 쓸 수 있는 워크플로우입니다. 전역 템플릿은 복제해서 고칩니다.',
    /** 목록 table aria-label */
    table: '프로젝트 워크플로우 목록',
    /** 표 헤더 — 소유 */
    columnOwner: '소유',
    /** 소유 배지 — 전역 공유 템플릿 */
    ownerGlobal: '전역',
    /** 소유 배지 — 이 프로젝트 전용 */
    ownerProject: '이 프로젝트',
    /** 전역 템플릿 행의 주 액션 — Jira 가 권장하는 우회로다 */
    copyToProject: '내 프로젝트로 복제',
    /** 복제본 이름 접미 */
    copySuffix: '사본',
    /** 빈 상태 제목 */
    emptyTitle: '쓸 수 있는 워크플로우가 없습니다',
    /** 빈 상태 설명 */
    emptyDescription: '전역 템플릿이 하나도 없습니다. 시스템 관리자에게 문의하세요',
    /** 403 안내 제목 */
    forbiddenTitle: '워크플로우를 관리할 권한이 없습니다',
    /** 403 안내 본문 */
    forbiddenMessage: '이 프로젝트의 워크플로우는 프로젝트 관리자만 관리할 수 있습니다.',
    /** 404 안내 제목 */
    notFoundTitle: '프로젝트를 찾을 수 없습니다',
    /** 404 안내 본문 */
    notFoundMessage: '주소의 프로젝트 키를 확인하세요.',
    /** 그 밖의 조회 실패 제목 */
    loadErrorTitle: '워크플로우 목록을 불러오지 못했습니다',
    /** 그 밖의 조회 실패 본문 */
    loadErrorMessage: '잠시 후 다시 시도하세요. 문제가 계속되면 관리자에게 문의하세요.',
    /** 편집기에서 목록으로 돌아가는 링크 */
    backToList: '워크플로우 목록으로',
  },

  /** 편집기 셸 (`/admin/workflows/$workflowKey`) */
  editor: {
    /** 탭 목록 aria-label */
    tabs: '워크플로우 편집 탭',
    /** 상태 탭 */
    statusTab: '상태',
    /** 전환 탭 */
    transitionTab: '전환',
    /**
     * 다이어그램 탭 (FR-WF-07 D8).
     *
     * ★ `'상태'`·`'전환'` 을 substring 으로 품으면 안 된다. `getByRole('tab', { name })` 은
     * 기본이 부분 일치라, 품는 순간 `e2e/workflow-editor.spec.ts:89` 가 두 탭을 잡아 즉사한다.
     * `__tests__/workflow-editor-labels.test.ts` 가 양방향으로 대조한다.
     */
    diagramTab: '다이어그램',
    /** 이름·설명 저장 버튼 */
    save: '변경 사항 저장',
    /** 저장 성공 토스트 */
    saved: '워크플로우를 저장했습니다',
    /** 이름 필드 라벨 */
    nameField: '워크플로우 이름',
    /** 설명 필드 라벨 */
    descriptionField: '워크플로우 설명',
    /** 목록으로 돌아가기 */
    backToList: '목록으로',
    /** 생성 폼의 최초 상태 필드 — '상태 추가'(동작)를 빌려 쓰지 마라 */
    initialStatusField: '최초 상태',
    /** 워크플로우를 못 불러왔을 때 — 목록 화면의 빈 상태 문구를 돌려 쓰지 마라 */
    loadFailed: '워크플로우를 불러오지 못했습니다',
    /** 없는 키로 들어왔을 때 */
    notFound: '그 워크플로우를 찾을 수 없습니다',
    /** 상태 카탈로그를 못 불러왔을 때 — 「상태가 없다」와 구별해야 한다 */
    catalogFailed: '상태 카탈로그를 불러오지 못해 상태를 편집할 수 없습니다',
    /** 카탈로그에 없는 상태가 섞여 있을 때 */
    unknownStatuses: '카탈로그에 없는 상태가 있어 순서를 바꿀 수 없습니다',
  },

  /**
   * 다이어그램 캔버스 (FR-WF-07 D8 · 로드맵 PR 9).
   *
   * E2E 셀렉터 정본이다 — 화면과 spec 이 이 상수를 함께 읽는다. 문자열을 spec 에 베끼지 마라.
   */
  diagram: {
    /** 캔버스 aria-label — 스크린 리더가 이 영역을 그래프로 인식하는 유일한 단서다 */
    canvasLabel: '워크플로우 다이어그램 캔버스',
    /** 전체 보기 버튼 */
    fitView: '전체 보기',
    /**
     * 상태가 0개일 때. 상태 패널의 빈 상태와 **다른 문구**여야 한다 —
     * 「상태가 하나도 없다」와 「캔버스에 그릴 것이 없다」는 사용자에게 다른 사실이다.
     */
    empty: '그릴 상태가 없습니다. 상태 탭에서 먼저 상태를 추가하세요',
    /** 전역 전환 목록 패널 — 간선으로 그리지 않는다(모든 노드에서 선을 뽑으면 못 읽는다) */
    globalPanel: '모든 상태에서',
    /** 시작 노드 aria-label — mermaid 의 `[*]` 에 대응한다 */
    initialNode: '이슈 생성 시작점',
    /** 잠긴 워크플로우 안내 — 끌어도 안 움직이는 이유를 말한다 */
    lockedHint: '잠긴 워크플로우라 배치를 바꿀 수 없습니다',
  },

  /** 상태 패널 */
  statusPanel: {
    /** 목록 aria-label */
    list: '편성된 상태 목록',
    /** 상태 추가 버튼 */
    add: '상태 추가',
    /** 상태 제거 버튼 접두 — `${remove} ${상태이름}` 형태로 고유 이름을 만든다 */
    remove: '상태 제거',
    /** 드래그 핸들 접두 — `${dragHandle} ${상태이름}` */
    dragHandle: '순서 변경',
    /** 빈 상태 */
    empty: '편성된 상태가 없습니다',
  },

  /** 전환 패널 */
  transitionPanel: {
    /** 목록 aria-label */
    list: '전환 목록',
    /** 전환 추가 버튼 */
    add: '전환 추가',
    /** 전환 편집 버튼 접두 — `${edit} ${전환이름}` */
    edit: '전환 편집',
    /** 전환 삭제 버튼 접두 — `${remove} ${전환이름}` */
    remove: '전환 삭제',
    /** 빈 상태 */
    empty: '정의된 전환이 없습니다',
    /** 전역 전환 배지 */
    kindGlobal: '모든 상태에서',
    /** 최초 전환 배지 */
    kindInitial: '이슈 생성 시',
  },

  /**
   * 전환 폼의 입력 필드.
   *
   * ★ **배지 문구를 돌려 쓰지 마라.** 처음에 이름 입력에 `transitionPanel.edit`('전환 편집'),
   * 출발 상태에 `kindGlobal`('모든 상태에서'), 도착 상태에 `kindInitial`('이슈 생성 시')을
   * 붙였다가 리뷰에서 잡혔다. 라벨 정본에 그 항목이 없다고 뜻이 다른 문자열을 빌리면
   * 화면에 **거짓말이 뜨고**, 그 거짓말이 E2E 셀렉터로 굳는다.
   */
  transitionForm: {
    /** 전환 이름 입력 */
    name: '전환 이름',
    /** 출발 상태 선택 */
    fromState: '출발 상태',
    /** 도착 상태 선택 */
    toState: '도착 상태',
    /** 다이얼로그 설명 */
    description: '전환 이름과 오갈 상태를 정합니다',
    /** 저장 버튼 */
    submit: '저장',
    /**
     * 전환 종류 라디오 그룹의 이름.
     *
     * ★위 KDoc 이 경고한 자리를 여기서 갚는다 — 종전에는 이 항목이 없어서 `GLOBAL` 전환을
     * **만드는 경로 자체가 없었다**(고르는 컨트롤이 없어 생성은 항상 `NORMAL` 이었다).
     * 뜻이 다른 문자열을 빌리는 대신 정본에 항목을 낸다.
     */
    kind: '전환 종류',
    /** NORMAL — 출발 상태를 지정하는 보통 전환 */
    kindNormal: '특정 상태에서',
    /** NORMAL 설명 — 이름만으로는 두 종류를 가를 수 없다 */
    kindNormalDescription: '고른 출발 상태에서만 쓸 수 있습니다',
    /** GLOBAL — 어느 상태에서나 쓸 수 있는 전환 */
    kindGlobal: '모든 상태에서',
    /** GLOBAL 설명 */
    kindGlobalDescription: '이슈가 어느 상태에 있든 쓸 수 있습니다',
  },

  /**
   * 다이얼로그 **제목**. 각 값은 화면 전체에서 고유해야 한다 (§2 즉사 계약).
   *
   * ★ 이 값들은 `aria-label` 이 아니라 `DialogTitle` 로 들어간다. Radix 가 제목을
   * `aria-labelledby` 로 연결하고 그것이 `aria-label` 을 이기므로, 별도 aria-label 을
   * 주면 지정한 이름과 실제 접근성 이름이 갈린다(실측).
   */
  dialog: {
    /** 상태 선택 다이얼로그 */
    statusPicker: '워크플로우에 추가할 상태 선택',
    /** 상태 선택 다이얼로그 설명 — 뜻이 다른 문구를 빌려 쓰지 마라(한때 관리 허브 카드 설명을 썼다) */
    statusPickerDescription: '카탈로그에서 이 워크플로우에 넣을 상태를 고릅니다',
    /** 전환 폼 다이얼로그 — 생성 */
    transitionCreate: '전환 만들기',
    /** 전환 폼 다이얼로그 — 수정 */
    transitionEdit: '전환 수정',
    /** 확인 다이얼로그 기본 확인 버튼 */
    confirm: '확인',
    /** 확인 다이얼로그 기본 취소 버튼 */
    cancel: '취소',
  },
} as const
