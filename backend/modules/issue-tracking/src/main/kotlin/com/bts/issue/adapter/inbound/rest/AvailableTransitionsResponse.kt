// GET /api/v1/issues/{key}/transitions 응답 DTO — 현재 상태에서 이동 가능한 전이 목록

package com.bts.issue.adapter.inbound.rest

import com.bts.shared.workflow.AvailableTransitionView

/**
 * GET /api/v1/issues/{key}/transitions 응답 래퍼.
 *
 * `data.transitions` 배열로 직렬화된다.
 *
 * @property transitions 현재 이슈 상태에서 이동 가능한 전이 항목 목록.
 */
data class AvailableTransitionsResponse(
    val transitions: List<TransitionItem>,
) {
    companion object {
        /**
         * [AvailableTransitionView] 목록을 [AvailableTransitionsResponse] 로 변환한다.
         *
         * @param views shared-kernel 의 가용 전이 뷰 목록.
         * @return REST 응답 DTO.
         */
        fun from(views: List<AvailableTransitionView>): AvailableTransitionsResponse =
            AvailableTransitionsResponse(
                transitions = views.map { TransitionItem.from(it) },
            )
    }
}

/**
 * 전이 단건 항목.
 *
 * 전이 동일성은 ([fromStateKey], [toStateKey]) 쌍으로 식별한다 (ADR 2026-05-28-workflow-transition-identity-policy).
 * [key] 는 프론트엔드 `workflowTransitionViewSchema` 와 형태 정합을 위한 computed 식별자다.
 *
 * @property fromStateKey 전이 출발 상태 키. 예: `"open"`
 * @property toStateKey 전이 도착 상태 키. 예: `"in_progress"`
 * @property name 전이 표시 이름. UI 버튼 레이블 용도. 예: `"시작"`
 * @property key computed 전이 식별자 (`"${fromStateKey}__${toStateKey}"`). 예: `"open__in_progress"`
 * @property toCategory 전이 목표 상태의 카테고리 문자열. 예: `"DONE"`, `"IN_PROGRESS"`, `"TODO"`.
 *   프론트엔드가 종료(DONE) 전이를 판별할 때 사용한다.
 *   null 은 워크플로우 미설정 등 비정상 상태를 의미한다. 실 API 응답은 항상 non-null.
 */
data class TransitionItem(
    val fromStateKey: String,
    val toStateKey: String,
    val name: String,
    val key: String,
    val toCategory: String?,
) {
    companion object {
        /**
         * [AvailableTransitionView] 를 [TransitionItem] 으로 변환한다.
         *
         * [TransitionItem.toCategory] 는 [AvailableTransitionView.toCategory] 를 그대로 전달한다.
         * 프론트엔드는 이 값으로 DONE 전이 여부를 판별한다.
         *
         * @param view shared-kernel 의 가용 전이 뷰.
         * @return REST 응답 항목.
         */
        fun from(view: AvailableTransitionView): TransitionItem =
            TransitionItem(
                fromStateKey = view.fromStateKey,
                toStateKey = view.toStateKey,
                name = view.name,
                key = "${view.fromStateKey}__${view.toStateKey}",
                toCategory = view.toCategory,
            )
    }
}
