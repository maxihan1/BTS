// 전환 규칙(validator) 편집 UI 의 한국어 라벨 + errorCode → 사용자 메시지 단일 출처

/**
 * 전환 규칙(validator) 설정 UI 가 화면·E2E 셀렉터로 노출하는 한국어 문자열.
 *
 * ### 왜 컴포넌트에 인라인하지 않는가
 * 형제 `post-action-labels.ts` 가 세운 관례다 — E2E 가 이 파일을 import 해 셀렉터로 쓰므로
 * 라벨이 바뀌어도 스펙이 같이 움직인다. 컴포넌트에 문자열을 박으면 E2E 가 사본을 들게 되고,
 * 갈린 사실은 화면이 아니라 red 로만 드러난다.
 *
 * ### 형제와 문자열이 겹치지 않게 고른 것들
 * 이 섹션은 `PostActionConfigSection` 과 **같은 라우트(`/workflows/$key`)에 나란히** 산다.
 * Playwright `getByLabel`·`getByText` 는 기본이 부분 일치라, 형제 라벨을 부분문자열로 품으면
 * 형제 스펙이 strict mode 로 즉사한다. 그래서 `전환 선택`(형제) 대신 `규칙 대상 전환`,
 * `유형`(형제) 대신 `규칙 종류`, `설정`(형제) 대신 `필수 값` 을 쓴다.
 *
 * - 그룹 5종 — dialog / form / section / list / error
 */
export const validatorLabels = {
  /** 규칙 추가·수정 다이얼로그 제목과 액션 버튼 */
  dialog: {
    /** create 모드 제목 — `role="dialog"` 의 접근 가능한 이름이 된다(고유해야 한다) */
    createTitle: '전환 규칙 추가',
    /** edit 모드 제목 — `role="dialog"` 의 접근 가능한 이름이 된다(고유해야 한다) */
    editTitle: '전환 규칙 수정',
    /** create 모드 제출 버튼 */
    createButton: '추가',
    /** edit 모드 제출 버튼 */
    saveButton: '저장',
    /** 취소 버튼 */
    cancelButton: '취소',
    /** mutation 진행 중 제출 버튼 */
    submittingButton: '처리 중...',
    /** 삭제 확인 다이얼로그 제목 — 고유해야 한다 */
    deleteTitle: '전환 규칙 삭제',
    /**
     * 삭제 확인 본문.
     *
     * 하드 삭제라는 사실이 문구에 드러나야 한다
     * (ADR `2026-08-25-workflow-transition-rule-hard-delete`).
     */
    deleteDescription:
      '이 규칙을 지우면 되돌릴 수 없습니다. 다시 걸려면 규칙을 처음부터 만들어야 합니다.',
    /** 삭제 확인 버튼 */
    deleteConfirmButton: '규칙 삭제',
    /** 삭제 취소 버튼 */
    deleteCancelButton: '되돌아가기',
  },

  /** 폼 필드 라벨·placeholder·인라인 검증 메시지 */
  form: {
    /** 규칙 종류 select label */
    typeLabel: '규칙 종류 선택',
    /** 규칙 종류 미선택 placeholder 옵션 */
    typePlaceholder: '규칙 종류를 고르세요',
    /** edit 모드에서 규칙 종류를 고정 표시할 때의 label */
    typeFixedLabel: '규칙 종류',
    /** config 입력 구획 제목 */
    configLegend: '규칙 값',
    /** 선택 입력 필드 뒤에 붙는 보조 표기 */
    optionalSuffix: '(선택)',
    /** 필수 config 키가 비었을 때 인라인 에러 */
    errorConfigRequired: '필수 값을 채워 주세요.',
    /** 프론트가 폼을 모르는 type 일 때 안내 제목 */
    unknownTypeTitle: '이 규칙 종류는 화면에서 편집할 수 없습니다',
    /**
     * 프론트가 폼을 모르는 type 일 때 안내 본문.
     *
     * 폼을 추측해 그리면 낡은 화면이 **거짓말을 하게 된다** — 모르면 모른다고 말하고 값을
     * 그대로 보여주는 쪽이 안전하다(제약 C1 · 엣지 E3).
     */
    unknownTypeDescription:
      '저장된 값을 그대로 보여줍니다. 값을 바꾸려면 이 규칙을 지우고 다시 걸어 주세요.',
    /** 읽기 전용으로 보여주는 원본 config 구획 제목 */
    rawConfigLegend: '저장된 값',
  },

  /** 섹션 제목·전환 선택·추가 버튼 */
  section: {
    /** 섹션 제목 */
    title: '전환 규칙 설정',
    /** 섹션 보조 설명 */
    description: '전환을 고르면 그 전환에 걸린 검증 규칙을 보고 고칠 수 있습니다.',
    /** 전환 선택 select label — 형제의 `전환 선택` 을 부분문자열로 품지 않는다 */
    transitionSelectLabel: '규칙 대상 전환',
    /** 전환 미선택 placeholder 옵션 */
    transitionSelectPlaceholder: '전환을 고르세요',
    /** 규칙 추가 버튼 */
    addButton: '규칙 추가',
  },

  /** 규칙 목록 테이블 헤더·빈 상태·행 버튼 */
  list: {
    /** type 컬럼 헤더 — 형제의 `유형` 을 부분문자열로 품지 않는다 */
    typeColumn: '규칙 종류',
    /** config 요약 컬럼 헤더 — 형제의 `설정` 을 부분문자열로 품지 않는다 */
    configColumn: '필수 값',
    /** phase 배지 컬럼 헤더 */
    phaseColumn: '평가 시점',
    /** 행 버튼 컬럼 헤더 — 형제의 `액션` 을 부분문자열로 품지 않는다 */
    actionColumn: '관리',
    /** 행 편집 버튼 — 형제의 `수정` 과 겹치지 않게 `편집` 을 쓴다 */
    editButton: '편집',
    /** 행 삭제 버튼 */
    deleteButton: '삭제',
    /** 규칙 0건 빈 상태 제목 */
    emptyTitle: '걸린 규칙이 없습니다',
    /** 규칙 0건 빈 상태 설명 */
    emptyDescription: '규칙을 걸면 이 전환을 막거나 전환 후보에서 감출 수 있습니다.',
    /** 빈 상태에서 규칙 추가로 유도하는 버튼 */
    emptyActionButton: '첫 규칙 추가',
    /** 로딩 중 스켈레톤의 스크린리더 안내 */
    loadingLabel: '전환 규칙을 불러오는 중',
    /**
     * 평가 시점 배지 문구.
     *
     * **색만으로 뜻을 전하지 않는다** — 배지에 이 텍스트를 함께 싣는다(NFR 접근성).
     * 값의 정본은 응답의 `phase` 이고 화면은 `type → phase` 표를 만들지 않는다.
     */
    phase: {
      /** `EXECUTION` — 눌렀을 때 막는다 */
      EXECUTION: '실행 시 차단',
      /** `AVAILABILITY` — 전환 후보에서 감춘다 */
      AVAILABILITY: '후보에서 감춤',
      /** `null` — 그 행으로는 인스턴스를 만들 수 없어 알 수 없다 */
      unknown: '판정 불가',
    },
    /** `editable=false` + `phase` 가 있는 행의 편집 불가 사유 */
    notEditableTypeReason: '이 규칙 종류는 화면에서 고칠 수 없습니다. 지운 뒤 다시 걸어 주세요.',
    /** `editable=false` + `phase=null` 인 행(손상 행)의 편집 불가 사유 */
    notEditableBrokenReason: '규칙 값이 종류와 맞지 않아 고칠 수 없습니다. 지운 뒤 다시 걸어 주세요.',
  },

  /** 조회·저장 실패 안내 */
  error: {
    /** 목록 조회 실패 */
    loadFailed: '전환 규칙을 불러오지 못했습니다.',
    /** 목록 조회 실패 재시도 버튼 */
    retryButton: '다시 시도',
    /** 삭제 실패 */
    removeFailed: '규칙을 지우지 못했습니다.',
    /** 전환 미선택 시 추가 버튼 비활성 안내 */
    selectTransitionFirst: '전환을 먼저 고르세요.',
    /** errorCode 를 못 읽었을 때의 기본 문구 */
    unknown: '규칙을 저장하지 못했습니다. 값을 확인하고 다시 시도해 주세요.',
  },
} as const

/** 라벨 const 의 추론 타입 — 호출자 타입 안전성 */
export type ValidatorLabels = typeof validatorLabels

/**
 * backend errorCode 를 사용자 노출 문구로 바꾼다.
 *
 * ### 왜 화면이 아니라 여기인가
 * 화면마다 인라인 매핑을 만들면 키가 갈려 raw 코드가 그대로 노출되는 「가짜 그린」이 난다
 * (PR #106). 이 BC 의 매핑은 이 함수 한 벌뿐이고, 형제 `componentErrorMessage` ·
 * `customFieldErrorMessage` 가 세운 관례를 그대로 따른다.
 *
 * 코드 값의 정본은 backend `ValidatorExceptionHandler` 다.
 *
 * ### 모르는 코드가 두 형태로 온다
 * 봉투를 아예 못 읽으면 `null` 이고, 봉투는 읽었으나 `code` 가 없으면 `api/validators.ts` 의
 * `z.string().default('UNKNOWN')` 때문에 `'UNKNOWN'` 이 온다. 둘 다 「모르는 코드」이므로 같은
 * default 로 떨어진다 — 호출부가 `'UNKNOWN'` 을 문자열로 비교하지 않게 하려는 것이다.
 *
 * `fallback` 은 그 default 를 조작별로 갈아 끼우는 자리다. 삭제 경로에서 기본 문구를 쓰면
 * 「규칙을 **저장**하지 못했습니다. **값을 확인하고**」가 떠서, 고칠 값이 없는 조작에 대해
 * 사용자가 무엇을 하라는 말인지 알 수 없다.
 *
 * @param errorCode `ValidatorApiError.errorCode`. 봉투를 못 읽었으면 `null`.
 * @param fallback 모르는 코드일 때 쓸 문구. 생략하면 저장 기준 기본 문구.
 * @return 사용자에게 보여줄 한국어 문구.
 */
export function validatorErrorMessage(
  errorCode: string | null,
  fallback: string = validatorLabels.error.unknown,
): string {
  switch (errorCode) {
    case 'WORKFLOW_VALIDATOR_INVALID':
      return '규칙 값이 이 규칙 종류의 요구와 맞지 않습니다. 값을 확인해 주세요.'
    case 'WORKFLOW_VALIDATOR_TYPE_NOT_EDITABLE':
      return '이 규칙 종류는 화면에서 만들거나 고칠 수 없습니다.'
    case 'WORKFLOW_VALIDATOR_NOT_FOUND':
      return '규칙 또는 전환을 찾을 수 없습니다. 목록을 새로 불러와 주세요.'
    case 'WORKFLOW_SCHEME_ACCESS_DENIED':
      return '워크플로우를 관리할 권한이 없습니다.'
    case 'WORKFLOW_INVALID_REQUEST':
      return '요청 형식이 올바르지 않습니다. 값을 확인해 주세요.'
    case 'WORKFLOW_UNAUTHENTICATED':
      return '로그인이 필요합니다. 다시 로그인해 주세요.'
    default:
      return fallback
  }
}

/**
 * 규칙 행 편집 버튼의 접근 가능한 이름을 만든다.
 *
 * 같은 전환에 같은 type 을 여러 번 걸 수 있어(엣지 E6) type 만으로는 유일하지 않다. 행 순번을
 * 붙여 Playwright strict mode 와 RTL `getByLabelText` 가 행을 정확히 지목하게 한다.
 *
 * @param type validator type 식별자.
 * @param rowNumber 목록에서의 1-based 순번.
 * @return 접근 가능한 이름.
 */
export function validatorEditButtonLabel(type: string, rowNumber: number): string {
  return `${type} 규칙 편집 ${rowNumber}`
}

/**
 * 규칙 행 삭제 버튼의 접근 가능한 이름을 만든다.
 *
 * @param type validator type 식별자.
 * @param rowNumber 목록에서의 1-based 순번.
 * @return 접근 가능한 이름.
 */
export function validatorDeleteButtonLabel(type: string, rowNumber: number): string {
  return `${type} 규칙 삭제 ${rowNumber}`
}
