// 워크플로우 스킴 admin UI 의 E2E 셀렉터 정본 — 라벨 변경 시 단일 진입점

/**
 * workflow-scheme UI 가 E2E 셀렉터로 노출하는 한국어 라벨/텍스트.
 *
 * - PR #22 §F4 학습 — E2E 가 i18n 정본 참조해 hardcoded string drift 차단
 * - 영역 한정 (전면 i18n migration 아님). 라벨/aria-label/button/heading 만
 * - placeholder, Zod error message, FormDescription 등 보조 텍스트는 미포함 (T3 가 별도 결정 가능)
 *
 * 그룹 — sidebar / emptyState / create / detail / mapping / standardProtect / inUseModal / assignment
 */
export const workflowSchemeLabels = {
  /** 좌 사이드바 (WorkflowSchemeSidebar.tsx) */
  sidebar: {
    /** nav 요소 aria-label */
    nav: '워크플로우 스킴 목록',
    /** h2 헤더 텍스트 */
    header: '워크플로우 스킴',
    /** 표준 스킴 그룹 헤더 */
    standardGroup: '표준',
    /** 커스텀 스킴 그룹 헤더 */
    customGroup: '커스텀',
    /** 새 스킴 추가 버튼 aria-label */
    addSchemeAriaLabel: '새 스킴 추가',
    /** 새 스킴 추가 버튼 visible 텍스트 (전각 ＋) */
    addSchemeButtonText: '＋ 새 스킴 생성',
    /** 로딩 스켈레톤 aria-label */
    loadingStatus: '로딩 중',
  },

  /** 스킴 미선택 빈 상태 (admin.workflow-schemes.tsx) */
  emptyState: {
    /** h2 heading */
    heading: '스킴을 선택하세요',
    /** 안내 문구 */
    message: '왼쪽 목록에서 워크플로우 스킴을 선택하거나 새 스킴을 생성하세요.',
    /** 빈 상태 「+ 새 스킴」 버튼 visible 텍스트 */
    addSchemeButton: '+ 새 스킴',
  },

  /** 스킴 생성 폼 (admin.workflow-schemes.new.tsx) */
  create: {
    /** 스킴 키 필드 label */
    keyLabel: '스킴 키',
    /** 이름 필드 label */
    nameLabel: '이름',
    /** 설명 필드 label */
    descriptionLabel: '설명 (선택)',
    /** 제출 버튼 visible 텍스트 */
    submitButton: '스킴 생성',
    /** 취소 버튼 visible 텍스트 */
    cancelButton: '취소',
  },

  /** 스킴 상세 페이지 (admin.workflow-schemes.$schemeKey.tsx) */
  detail: {
    /** 스킴을 찾을 수 없을 때 에러 heading */
    notFoundHeading: '스킴을 찾을 수 없습니다',
    /** 스켈레톤 aria-label (사이드바와 동일) */
    loadingStatus: '로딩 중',
  },

  /** 매핑 테이블 (MappingTable.tsx) */
  mapping: {
    /** 이슈 타입 컬럼 헤더 */
    issueTypeColumn: '이슈 타입',
    /** 워크플로우 컬럼 헤더 */
    workflowColumn: '워크플로우',
    /** 기본 여부 컬럼 헤더 */
    isDefaultColumn: '기본 여부',
    /** 액션 컬럼 헤더 */
    actionColumn: '액션',
    /** 기본값 매핑 표시 레이블 */
    defaultLabel: '기본값 (모든 이슈 타입)',
    /** 기본값 매핑 row 앞 prefix 표시 (★ 포함) */
    defaultRowPrefix: '★ 기본값 (모든 이슈 타입)',
    /** 기본값 badge 텍스트 */
    defaultBadge: '기본',
    /** 이슈 타입 select aria-label */
    issueTypeSelectAriaLabel: '이슈 타입 선택',
    /** 워크플로우 select aria-label */
    workflowSelectAriaLabel: '워크플로우 선택',
    /** 추가 버튼 aria-label */
    addMappingAriaLabel: '매핑 추가',
    /** 추가 버튼 visible 텍스트 */
    addMappingButton: '추가',
    /** 삭제 버튼 visible 텍스트 */
    deleteButton: '삭제',
    /** 삭제 확인 dialog aria-label */
    confirmDeleteDialogAriaLabel: '매핑 삭제 확인',
    /** 삭제 확인 dialog 본문 텍스트 */
    confirmDeleteText: '삭제하시겠습니까?',
    /** 삭제 확인 버튼 visible 텍스트 */
    confirmDeleteButton: '확인',
    /** 삭제 취소 버튼 visible 텍스트 */
    confirmCancelButton: '취소',
  },

  /** 표준 스킴 보호 (SchemeMetaPanel.tsx) */
  standardProtect: {
    /** 표준 스킴 안내 카드 텍스트 */
    notice: '표준 스킴 — 키/이름 변경 + 삭제 불가. 매핑만 자유 변경 가능',
    /** name/description disabled 이유 tooltip 텍스트 */
    fieldTooltip: '표준 스킴은 키/이름/설명 변경 불가',
    /** 삭제 disabled 이유 tooltip 텍스트 */
    deleteTooltip: '표준 스킴은 삭제 불가',
  },

  /** 우 메타 패널 (SchemeMetaPanel.tsx) */
  metaPanel: {
    /** 스킴 키 label */
    schemeKeyLabel: '스킴 키',
    /** 이름 label */
    nameLabel: '이름',
    /** 설명 label */
    descriptionLabel: '설명',
    /** 저장 버튼 visible 텍스트 */
    saveButton: '저장',
    /** 저장 진행 중 버튼 visible 텍스트 */
    savingButton: '저장 중...',
    /** 삭제 버튼 visible 텍스트 */
    deleteButton: '삭제',
    /** 삭제 진행 중 버튼 visible 텍스트 */
    deletingButton: '삭제 중...',
    /** 통계 카드 — 매핑 수 label */
    mappingsCountLabel: '매핑 수',
    /** 통계 카드 — 사용 중 프로젝트 label */
    usedByProjectsLabel: '사용 중 프로젝트',
  },

  /** 사용 중 삭제 차단 모달 (SchemeInUseModal.tsx) */
  inUseModal: {
    /** AlertDialog title */
    title: '스킴을 삭제할 수 없습니다',
    /** AlertDialog description */
    description: '이 스킴은 사용 중인 프로젝트가 있어 삭제할 수 없습니다.\n먼저 프로젝트의 스킴을 변경하세요.',
    /** 사용 중인 프로젝트 label */
    usedByProjectsLabel: '사용 중인 프로젝트',
    /** 확인 버튼 visible 텍스트 */
    confirmButton: '확인',
  },

  /** 프로젝트 스킴 할당 설정 (projects.$projectKey.settings.workflow-scheme.tsx) */
  assignment: {
    /** 페이지 h1 heading */
    pageHeading: '워크플로우 스킴 설정',
    /** 페이지 설명 문구 */
    pageDescription: '이 프로젝트에 적용할 워크플로우 스킴을 지정합니다.',
    /** 현재 할당된 스킴 카드 CardTitle */
    currentSchemeTitle: '현재 할당된 스킴',
    /** 현재 적용 badge 텍스트 */
    currentSchemeBadge: '현재 적용',
    /** 스킴 미할당 카드 CardTitle */
    unassignedTitle: '스킴 미할당',
    /** 스킴 지정 섹션 CardTitle (할당 없을 때) */
    assignTitle: '스킴 지정',
    /** 스킴 변경 섹션 CardTitle (할당 있을 때) */
    changeTitle: '스킴 변경',
    /** 스킴 select aria-label */
    schemeSelectAriaLabel: '워크플로우 스킴 선택',
    /** 적용 버튼 visible 텍스트 */
    applyButton: '적용',
  },
} as const

/** 라벨 const 의 추론 타입 — 호출자 타입 안전성 */
export type WorkflowSchemeLabels = typeof workflowSchemeLabels
