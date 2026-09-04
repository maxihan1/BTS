// 첨부 파일 UI(FR-AC-01 D6) 한국어 라벨 — 모든 사용자 노출 문자열의 단일 출처
// 콜론으로 문장을 끝내는 것 금지 (ko.test 자동검증)

/**
 * 첨부 파일 섹션 UI 문자열.
 *
 * - 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5 + ko.test 검증 대상).
 * - useAttachments.ts 에러 메시지도 이 파일로 일원화한다.
 *
 * 그룹.
 *  section   — 섹션 제목 / 빈 상태 / 로딩
 *  actions   — 드롭존 / 다운로드 / 삭제 버튼
 *  delete    — 인라인 삭제 확인 문구
 *  errors    — 업로드/삭제 에러 토스트 메시지
 *  size      — 파일 크기 단위
 */
export const attachmentLabels = {
  /** 섹션 제목 */
  sectionTitle: '첨부 파일',

  /** 첨부 없음 빈 상태 안내 */
  emptyState: '첨부된 파일이 없습니다.',

  /** 로딩 중 aria-label */
  loadingState: '첨부 파일 목록 로딩 중',

  /** 드롭존 안내 텍스트 (파일 드래그 또는 클릭 안내) */
  dropzoneHint: '파일을 여기에 끌어다 놓거나 클릭해서 선택하세요',

  /** 드롭존 숨김 파일 입력 aria-label */
  fileInputLabel: '첨부 파일 선택',

  /** 업로드 중 aria-label */
  uploadingState: '업로드 중',

  /** 다운로드 버튼 aria-label (파일명 suffix로 사용) */
  downloadButton: '다운로드',

  /** 삭제 버튼 visible 텍스트 */
  deleteButton: '삭제',

  /** 인라인 삭제 확인 — 경고 문구 (하드삭제이므로 복구 불가 명시) */
  deleteWarning: '삭제하면 복구할 수 없습니다. 정말 삭제하시겠습니까?',

  /** 인라인 삭제 확인 버튼 */
  deleteConfirmButton: '확인',

  /** 인라인 삭제 취소 버튼 */
  deleteCancelButton: '취소',

  // ── 에러 메시지 (useAttachments.ts 에서 이동) ──────────────────────────

  /** 업로드 권한 없음 */
  uploadForbidden: '첨부 파일 업로드 권한이 없습니다.',

  /** 업로드 권한 없음 — 파일명 포함 (다중 업로드 시 어느 파일인지 식별) */
  uploadForbiddenNamed: (filename: string) =>
    `${filename} 업로드 권한이 없습니다.`,

  /** 업로드 파일 크기 초과 (100MB) */
  uploadTooLarge: '파일 크기가 너무 큽니다. 100MB 이하의 파일만 업로드할 수 있습니다.',

  /** 업로드 파일 크기 초과 — 파일명 포함 (다중 업로드 시 어느 파일인지 식별) */
  uploadTooLargeNamed: (filename: string) =>
    `${filename}: 파일 크기가 너무 큽니다. 100MB 이하의 파일만 업로드할 수 있습니다.`,

  /** 업로드 기본 에러 */
  uploadDefault: '파일 업로드 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  /** 클라이언트 사전 검증 실패 — 100MB 초과 (토스트용) */
  uploadFileTooLarge: (filename: string) =>
    `${filename}: 파일 크기가 100MB를 초과해 업로드하지 않았습니다.`,

  /** 삭제 권한 없음 */
  deleteForbidden: '첨부 파일 삭제 권한이 없습니다.',

  /** 삭제 대상 없음 */
  deleteNotFound: '삭제하려는 첨부 파일을 찾을 수 없습니다.',

  /** 삭제 기본 에러 */
  deleteDefault: '첨부 파일 삭제 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  /** 다운로드 실패 에러 */
  downloadError: '파일 다운로드 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',

  // ── 파일 크기 단위 ────────────────────────────────────────────────────────

  /** Bytes 단위 suffix */
  sizeBytes: 'B',

  /** Kilobytes 단위 suffix */
  sizeKB: 'KB',

  /** Megabytes 단위 suffix */
  sizeMB: 'MB',

  // ── 미리보기 모달 (FR-AC-02) ──────────────────────────────────────────────

  /** 미리보기 모달 열기 버튼 */
  previewButton: '미리보기',

  /**
   * 썸네일 타일의 접근성 이름 (J6).
   *
   * ★`previewButton` 을 재사용하지 않는다. 같은 행에 「미리보기」 텍스트 버튼이 이미 있어
   * 이름이 겹치면 `getByRole('button', { name: /미리보기/ })` 가 두 개를 잡아 strict mode 로
   * 죽는다 — 실제로 기존 테스트 2건이 그렇게 깨졌다. 하는 일은 같지만 **부르는 이름은 달라야**
   * 셀렉터가 하나를 지목할 수 있다.
   */
  thumbnailButton: (filename: string) => `${filename} 썸네일`,

  /** 미리보기 모달 제목 (파일명 포함) */
  previewTitle: (filename: string) => `${filename} 미리보기`,

  /** 미리보기 로딩 중 안내 텍스트 */
  previewLoading: '미리보기 불러오는 중',

  /** 미리보기 로드 실패 에러 텍스트 */
  previewError: '미리보기를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',

  /** 미리보기 모달 닫기 버튼 */
  previewClose: '닫기',

  /** 갤러리 이전 — 좌우 이동 버튼의 접근성 이름 */
  previewPrevious: '이전 첨부',
  /** 갤러리 다음 */
  previewNext: '다음 첨부',
  /**
   * 갤러리 위치 — 「2 / 5」.
   *
   * 세는 대상은 **미리보기 가능한 첨부**다. 목록 전체를 세면 이동으로 닿을 수 없는 번호가
   * 생겨 「5 중 3인데 다음이 없다」가 된다.
   */
  previewPosition: (current: number, total: number) => `${current} / ${total}`,
} as const

/** 라벨 const 추론 타입 */
export type AttachmentLabels = typeof attachmentLabels
