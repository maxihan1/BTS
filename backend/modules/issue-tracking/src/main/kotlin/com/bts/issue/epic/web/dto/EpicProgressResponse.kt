// 에픽 자식 이슈 진행률 응답 DTO (FR-EP-02 Task 3)

package com.bts.issue.epic.web.dto

import com.bts.issue.epic.domain.EpicProgress

/**
 * 에픽 자식 이슈 진행률 응답 DTO (FR-EP-02 Task 3).
 *
 * GET `/api/v1/epics/{key}/progress` 응답.
 *
 * API 응답 계약 (Zod 계약 고정 — frontend 의존):
 * ```json
 * { "total": 10, "done": 4, "donePercentage": 40, "byCategory": { "todo": 3, "inProgress": 3, "done": 4 } }
 * ```
 *
 * 최상위 `done` == `byCategory.done` (동일 값).
 *
 * @property total 전체 직속 자식 이슈 수.
 * @property done DONE 카테고리 이슈 수 (= [byCategory].done 과 동일).
 * @property donePercentage 완료 비율 (0~100 정수, 반올림). 자식 없으면 0.
 * @property byCategory 카테고리별 분류 (todo / inProgress / done).
 */
data class EpicProgressResponse(
    val total: Int,
    val done: Int,
    val donePercentage: Int,
    val byCategory: ByCategoryDto,
) {
    /**
     * 카테고리별 이슈 수 내부 객체.
     *
     * JSON 직렬화 시 `{ "todo": N, "inProgress": N, "done": N }` (camelCase — BTS 표준).
     *
     * @property todo TODO 카테고리 이슈 수.
     * @property inProgress IN_PROGRESS 카테고리 이슈 수.
     * @property done DONE 카테고리 이슈 수.
     */
    data class ByCategoryDto(
        val todo: Int,
        val inProgress: Int,
        val done: Int,
    )

    companion object {
        /**
         * [EpicProgress] VO 를 [EpicProgressResponse] DTO 로 변환한다.
         *
         * @param progress 에픽 진행률 값 객체.
         * @return [EpicProgressResponse] 인스턴스.
         */
        fun from(progress: EpicProgress): EpicProgressResponse =
            EpicProgressResponse(
                total = progress.total,
                done = progress.done,
                donePercentage = progress.donePercentage,
                byCategory =
                    ByCategoryDto(
                        todo = progress.todo,
                        inProgress = progress.inProgress,
                        done = progress.done,
                    ),
            )
    }
}
