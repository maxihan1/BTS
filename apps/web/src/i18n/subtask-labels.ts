// 이슈 상세 「하위 작업」 섹션(JA-D-2) 한국어 라벨 — 사용자 노출 문자열의 단일 출처
// 콜론으로 문장을 끝내는 것 금지 (ko.test 자동검증)

export const subtaskLabels = {
  sectionTitle: '하위 작업',
  addButton: '하위 작업 추가',
  inputLabel: '하위 작업 제목',
  inputPlaceholder: '무엇을 해야 하나요?',
  createFailed: '하위 작업을 만들지 못했습니다.',
  /** 「3/5 완료」 */
  progress: (done: number, total: number) => `${done}/${total} 완료`,
} as const
