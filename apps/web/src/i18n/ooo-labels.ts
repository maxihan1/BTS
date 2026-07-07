// 부재중 설정 모달 + Header 표시 한국어 라벨 상수 (FR-PR-03 Task 7/8)

/** 부재중 설정 모달 + Header 부재중 표시의 고정 한국어 라벨(BC 내 상수, status-labels 관례). */
export const oooLabels = {
  title: '부재중 설정',
  startsAtLabel: '시작',
  endsAtLabel: '종료',
  delegateLabel: '대리자',
  delegateSearchPlaceholder: '대리자 검색(이름/아이디)',
  delegateNoResults: '검색 결과가 없습니다.',
  delegateSelected: '선택됨',
  messageLabel: '안내 메시지',
  messagePlaceholder: '예: 휴가 중입니다. 급한 건 담당자에게 문의해 주세요.',
  saveButton: '저장',
  savingButton: '저장 중...',
  clearButton: '부재중 해제',
  periodRequiredError: '시작·종료 시각을 입력해 주세요',
  endBeforeStartError: '종료 시각은 시작 시각보다 이후여야 합니다',
  endNotFutureError: '종료 시각은 현재보다 이후여야 합니다',
  saveFailedError: '저장에 실패했습니다',
  accountMenuItem: '부재중 설정',
  headerBadge: '부재중',
  headerBadgeReturnPrefix: '복귀 예정일',
} as const
