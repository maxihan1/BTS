// 워크플로우 스킴 admin UI 의 E2E 셀렉터 정본 — 라벨 변경 시 단일 진입점
import { projectNotFoundLabels } from './project-not-found-labels'

/**
 * workflow-scheme UI 가 E2E 셀렉터로 노출하는 한국어 라벨/텍스트.
 *
 * - PR #22 §F4 학습 — E2E 가 i18n 정본 참조해 hardcoded string drift 차단
 * - 영역 한정 (전면 i18n migration 아님). 라벨/aria-label/button/heading + placeholder
 * - **placeholder 는 이제 포함한다** (2026-08-11 R3 래칫). 옛 주석의 「미포함 · T3 가 별도
 *   결정 가능」이 그 별도 결정이고, ESLint 락이 하드코딩을 막으므로 정본이 여기여야 한다.
 * - Zod error message, FormDescription 등 나머지 보조 텍스트는 여전히 미포함
 *
 * 그룹 — placeholder(최상위) / sidebar / emptyState / create / detail / mapping / standardProtect / inUseModal / assignment
 */
export const workflowSchemeLabels = {
  /** 스킴 키 input placeholder (admin.workflow-schemes.new.tsx) */
  keyPlaceholder: '예: my-scheme-01',
  /** 이름 input placeholder */
  namePlaceholder: '스킴 이름을 입력하세요',
  /** 설명 input placeholder */
  descriptionPlaceholder: '스킴 설명을 입력하세요',
  /** 프로젝트 스킴 할당 Select 미선택 placeholder (말줄임표 포함) */
  schemeSelectPlaceholder: '스킴 선택...',

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
    description: '이 스킴은 사용 중인 프로젝트가 있어 삭제할 수 없습니다. 먼저 프로젝트의 스킴을 변경하세요.',
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
    /**
     * 배정 조회 404 카드 CardTitle.
     * 이 엔드포인트의 404 는 「프로젝트 없음」 하나뿐이다 — 옛 `unassignedTitle`('스킴 미할당')은
     * 404 오독의 산물이라 제거했다 (백엔드가 미배정 프로젝트에 자동 배정하므로 그 상태는 없다).
     *
     * ★값의 소유처는 `project-not-found-labels.ts` 다 — 이 카드(`ProjectNotFoundCard`)를
     *   가져오기 화면도 재사용하므로 어느 한 화면의 네임스페이스가 소유하면 안 된다.
     *   이 키는 기존 E2E·단위 셀렉터를 위해 남긴 **참조**이지 사본이 아니다.
     */
    projectNotFoundTitle: projectNotFoundLabels.title,
    /** 배정 조회 404 안내 문구 — 값의 소유처는 `project-not-found-labels.ts` (위 참조) */
    projectNotFoundMessage: projectNotFoundLabels.message,
    /** 스킴 변경 섹션 CardTitle */
    changeTitle: '스킴 변경',
    /** 스킴 select aria-label */
    schemeSelectAriaLabel: '워크플로우 스킴 선택',
    /** 적용 버튼 visible 텍스트 */
    applyButton: '적용',
    /** 스킴 조회 권한 없음(403) 안내 카드 CardTitle */
    forbiddenTitle: '권한이 없습니다',
    /** 스킴 조회 권한 없음(403) 안내 문구 — 스킴 0건(정상 상태)과 구분되는 전용 메시지 */
    forbiddenMessage: '이 프로젝트의 워크플로우 설정 권한이 없습니다. 프로젝트 관리자에게 문의하세요.',
    /** 스킴 목록 조회 실패(403 이외 — 404/500/네트워크 단절 등) 안내 카드 CardTitle */
    loadErrorTitle: '스킴 목록을 불러오지 못했습니다',
    /** 스킴 목록 조회 실패(403 이외) 안내 문구 — 403 전용 문구와 구분되는 별도 메시지 */
    loadErrorMessage: '스킴 목록을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
  },

  /** 프로젝트 설정의 스킴 관리 구역 (FR-WF-08 PR ⑤) */
  projectManagement: {
    /** 아무 스킴도 고르지 않았을 때 */
    pickPrompt: '왼쪽에서 스킴을 고르면 매핑과 설정을 볼 수 있습니다.',
    /** 전역 템플릿을 골랐을 때의 안내 */
    globalReadOnlyNotice: '전역 공유 템플릿이라 이 프로젝트에서는 고칠 수 없습니다. 복제해서 쓰세요.',
    /** 전역 템플릿을 내 프로젝트 사본으로 만드는 버튼 */
    copyToProject: '내 프로젝트로 복제',
    /** 복제본 이름 접미 */
    copySuffix: '사본',
    /** 이슈타입 지정 없이 적용되는 기본 매핑의 표시 이름 */
    defaultMappingLabel: '기본',
    /** 생성 폼 제목 */
    createHeading: '새 스킴 만들기',
    /** 생성 폼 — 키 입력 라벨 */
    createKeyLabel: '스킴 키',
    /** 생성 폼 — 이름 입력 라벨 */
    createNameLabel: '스킴 이름',
    /** 생성 폼 제출 버튼 */
    createSubmit: '만들기',
  },
}
