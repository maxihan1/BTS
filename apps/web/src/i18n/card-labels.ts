// 보드/백로그 카드 공용 밀도 요소(라벨 칩·추정 배지) i18n 라벨

/**
 * 보드·백로그 카드가 공유하는 라벨 칩/추정 배지 접근성 문구.
 *
 * `CardLabelChips`와 `CardEstimateBadge` 두 조각이 이 파일 하나만을 문자열 정본으로
 * 삼는다 (FR-UX-14 F14 Task 2) — 화면별로 분리하면 같은 문구가 두 벌이 되어
 * 한쪽만 바뀌는 drift가 생긴다.
 *
 * 주의: 모든 값은 콜론으로 끝나지 않는다 (글로벌 §5).
 */
export const cardLabels = {
  /**
   * 추정 배지 접근성 레이블.
   * @param formatted formatSeconds 결과 (예: "2h 30m")
   * @returns "추정 {formatted}"
   */
  estimateAriaLabel: (formatted: string): string => `추정 ${formatted}`,

  /**
   * 라벨 오버플로 칩 접근성 레이블.
   *
   * 개수만이 아니라 **숨은 라벨 전문**을 담는다 — `title`은 터치 화면에서 뜨지 않아
   * 그것만으로는 태블릿·폰 사용자가 접힌 라벨을 영영 못 본다 (Maxi 확정 2026-08-07 D6).
   *
   * @param hidden 3개를 넘어 오버플로 칩에 접힌 라벨 목록
   * @returns "라벨 {n}개 더 — {label1}, {label2}, ..."
   */
  moreLabelsAriaLabel: (hidden: readonly string[]): string =>
    `라벨 ${hidden.length}개 더 — ${hidden.join(', ')}`,
} as const

/** cardLabels const 추론 타입 */
export type CardLabels = typeof cardLabels
