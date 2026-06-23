// 에픽 자식 이슈들의 워크플로우 카테고리 비율을 집계하는 진행률 값 객체

package com.bts.issue.epic.domain

import kotlin.math.roundToInt

/**
 * 에픽의 직속 자식 이슈들로부터 계산된 진행률 값 객체 (Value Object).
 *
 * 이 클래스는 외부 의존이 없는 순수 도메인 객체이며, [of] 팩토리 메서드로만 생성한다.
 *
 * API 응답 계약:
 * ```json
 * { "total": 4, "done": 2, "donePercentage": 50, "byCategory": { "todo": 1, "inProgress": 1, "done": 2 } }
 * ```
 *
 * @property total 전체 직속 자식 이슈 수.
 * @property done DONE 카테고리 이슈 수 (= byCategory.done 과 동일).
 * @property donePercentage 완료 비율 (0~100 정수, 반올림). 자식이 없으면 0.
 * @property todo TODO 카테고리 이슈 수.
 * @property inProgress IN_PROGRESS 카테고리 이슈 수.
 */
data class EpicProgress(
    val total: Int,
    val done: Int,
    val donePercentage: Int,
    val todo: Int,
    val inProgress: Int,
) {
    companion object {
        private const val PERCENT_FULL = 100.0

        /**
         * 자식 이슈 카테고리 문자열 목록으로부터 [EpicProgress] 를 생성한다.
         *
         * @param categories 각 자식 이슈의 워크플로우 카테고리 문자열 목록.
         *   WorkflowStateView.category 가 "TODO" / "IN_PROGRESS" / "DONE" 문자열로 넘어온다.
         *
         * 정규화 규칙:
         * - "TODO" → TODO 카운트
         * - "IN_PROGRESS" → IN_PROGRESS 카운트
         * - "DONE" → DONE 카운트
         * - null 또는 비표준 문자열(EC2: "FOO" 등) → TODO 카운트로 폴백.
         *   EC3 (StateCategory enum 3값 외 경로) 는 enum 상수가 3개뿐이므로 정상 경로에서
         *   도달 불가하지만, 외부 입력 방어를 위해 동일하게 TODO 로 취급한다.
         */
        fun of(categories: List<String?>): EpicProgress {
            val counts =
                categories
                    .map { normalizeCategory(it) }
                    .groupingBy { it }
                    .eachCount()

            val todo = counts.getOrDefault(NormalizedCategory.TODO, 0)
            val inProgress = counts.getOrDefault(NormalizedCategory.IN_PROGRESS, 0)
            val done = counts.getOrDefault(NormalizedCategory.DONE, 0)
            val total = todo + inProgress + done

            val donePercentage =
                if (total > 0) {
                    (done * PERCENT_FULL / total).roundToInt()
                } else {
                    0
                }

            return EpicProgress(
                total = total,
                done = done,
                donePercentage = donePercentage,
                todo = todo,
                inProgress = inProgress,
            )
        }

        /**
         * 카테고리 문자열을 표준 3값으로 정규화한다.
         *
         * EC2: 비표준 문자열 → [NormalizedCategory.TODO] 폴백.
         * EC3: null → [NormalizedCategory.TODO] 폴백 (도달 불가 방어).
         */
        private fun normalizeCategory(raw: String?): NormalizedCategory =
            when (raw) {
                "IN_PROGRESS" -> NormalizedCategory.IN_PROGRESS
                "DONE" -> NormalizedCategory.DONE
                else -> NormalizedCategory.TODO
            }
    }

    /**
     * 카테고리 정규화 내부 표현.
     *
     * 외부 enum(StateCategory, project-workflow BC) 을 import 하지 않고 BC 격리를 유지한다.
     */
    private enum class NormalizedCategory { TODO, IN_PROGRESS, DONE }
}
