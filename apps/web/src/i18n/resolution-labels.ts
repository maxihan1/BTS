// 결의안(resolution) 셀렉터의 한국어 라벨 — 이슈 상세·보드·일괄전환 세 화면 공용

/**
 * 결의안 선택 UI 가 노출하는 한국어 문구.
 *
 * ## 왜 화면별 라벨 파일이 아니라 여기 있나
 * 같은 placeholder 를 쓰는 화면이 셋이다 — `issue/ResolutionModal.tsx` ·
 * `board/ResolutionPickerModal.tsx` · `issues/BulkTransitionDialog.tsx`.
 * 한쪽 화면의 네임스페이스에 두면 나머지 둘이 남의 네임스페이스를 뒤지게 되고,
 * 사본을 만들면 세 문구가 서로를 검사하지 않고 갈라진다
 * (`project-not-found-labels.ts` 가 같은 이유로 분리된 선례).
 */
export const resolutionLabels = {
  /** 결의안 Select 미선택 상태 placeholder */
  selectPlaceholder: '결의안을 선택하세요',
} as const

/** resolutionLabels const 추론 타입 */
export type ResolutionLabels = typeof resolutionLabels
