// R4 줄수 래칫의 동결 베이스라인 — 200줄을 넘는 비-테스트 함수의 현재 상태를 얼린다.
//
// 키 = `<apps/web 기준 상대경로>::<ESLint 서술자>`. 값 = raw 줄수(빈 줄·주석 포함).
// ★줄인 뒤에는 이 숫자를 **함께 낮춰라.** 낮추지 않으면 그만큼 다시 늘릴 여지가 남는다.
// ★새 항목을 여기 추가하는 것은 「200줄 넘는 컴포넌트를 하나 더 승인한다」는 뜻이다. 리뷰에서 그렇게 읽어라.
export const OVERSIZED_FUNCTION_BASELINE: Readonly<Record<string, number>> = {}
