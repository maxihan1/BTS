// 결의안(resolution) 셀렉터의 한국어 라벨 — 이슈 상세·보드·일괄전환 세 화면 공용

/**
 * 결의안 선택 UI 가 노출하는 한국어 문구.
 *
 * ## 왜 화면별 라벨 파일이 아니라 여기 있나
 * 같은 placeholder 를 쓰는 화면은 전환 화면 대화상자 `issue/TransitionScreenDialog.tsx` 하나로 모였다(FR-WF-01 D11) —
 * 상세 · 목록 셀 · 일괄 전환 · 보드가 그것을 쓴다.
 */
export const resolutionLabels = {
  /** 결의안 Select 미선택 상태 placeholder */
  selectPlaceholder: '결의안을 선택하세요',
} as const

/** resolutionLabels const 추론 타입 */
export type ResolutionLabels = typeof resolutionLabels
