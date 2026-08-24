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
 * 그룹 — placeholder(최상위) / nav / list / editor / statusPanel / transitionPanel / dialog
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

  /** 사이드바·관리 허브 진입점 */
  nav: {
    /** ★ `'워크플로우'` 금지 — 위 substring 경고 참조 */
    label: '워크플로우 관리',
    /** 관리 허브 카드 설명 */
    description: '워크플로우를 만들고 상태와 전환을 편집합니다',
  },

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

  /** 편집기 셸 (`/admin/workflows/$workflowKey`) */
  editor: {
    /** 탭 목록 aria-label */
    tabs: '워크플로우 편집 탭',
    /** 상태 탭 */
    statusTab: '상태',
    /** 전환 탭 */
    transitionTab: '전환',
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

  /** 다이얼로그 — 각 `aria-label` 은 화면 전체에서 고유해야 한다 (§2 즉사 계약) */
  dialog: {
    /** 상태 선택 다이얼로그 */
    statusPicker: '워크플로우에 추가할 상태 선택',
    /** 상태 선택 — 새 상태 만들기 토글 */
    statusPickerCreate: '새 상태 만들기',
    /** 전환 폼 다이얼로그 — 생성 */
    transitionCreate: '전환 만들기',
    /** 전환 폼 다이얼로그 — 수정 */
    transitionEdit: '전환 수정',
    /** 워크플로우 생성 다이얼로그 */
    workflowCreate: '워크플로우 만들기 폼',
    /** 확인 다이얼로그 기본 확인 버튼 */
    confirm: '확인',
    /** 확인 다이얼로그 기본 취소 버튼 */
    cancel: '취소',
  },
} as const
