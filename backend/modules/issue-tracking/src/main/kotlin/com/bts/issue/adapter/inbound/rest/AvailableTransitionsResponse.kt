// GET /api/v1/issues/{key}/transitions 응답 DTO — 현재 상태에서 이동 가능한 전환 목록

package com.bts.issue.adapter.inbound.rest

import com.bts.shared.workflow.AvailableTransitionView
import java.util.UUID

/**
 * GET /api/v1/issues/{key}/transitions 응답 래퍼.
 *
 * `data.transitions` 배열로 직렬화된다.
 *
 * @property transitions 현재 이슈 상태에서 이동 가능한 전환 항목 목록.
 */
data class AvailableTransitionsResponse(
    val transitions: List<TransitionItem>,
) {
    companion object {
        /**
         * [AvailableTransitionView] 목록을 [AvailableTransitionsResponse] 로 변환한다.
         *
         * @param views shared-kernel 의 가용 전환 뷰 목록.
         * @return REST 응답 DTO.
         */
        fun from(views: List<AvailableTransitionView>): AvailableTransitionsResponse =
            AvailableTransitionsResponse(
                transitions = views.map { TransitionItem.from(it) },
            )
    }
}

/**
 * 전환 단건 항목.
 *
 * 전환의 1급 식별자는 [transitionId] (`workflow_transitions.id`) 다 —
 * ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D1 · §D3 참조.
 * (구 ADR `2026-05-28-workflow-transition-identity-policy` 의 「identity = (from, to)」 정책은
 * 그 ADR 이 대체했다. `UNIQUE(workflow_id, from_state_id, to_state_id)` 가 해제돼
 * 같은 상태쌍에 이름만 다른 전환을 여럿 둘 수 있다.)
 *
 * @property fromStateKey 전환 출발 상태 키. 예: `"open"`
 * @property toStateKey 전환 도착 상태 키. 예: `"in_progress"`
 * @property name 전환 표시 이름. UI 버튼 레이블 용도. 예: `"시작"`
 * @property key 하위호환용 계산 문자열 (`"${fromStateKey}__${toStateKey}"`). 예: `"open__in_progress"`.
 *   **식별자가 아니다.** 같은 상태쌍의 전환 둘은 이 값이 서로 같으므로 이 값으로 전환을 지목하면
 *   틀린 전환을 고른다. 지목에는 반드시 [transitionId] 를 써라. ADR §D3 이 「기존 `key` 는 남긴다」로
 *   정해 두었기에 삭제하지 않고 남긴다.
 *
 *   재계산인 이유. 정본 게터는 project-workflow 의 `WorkflowTransition.key` 인데, 그것은 그 BC 의
 *   내부 도메인 타입이라 issue-tracking 이 import 하면 BC 격리를 깬다. 두 BC 사이의 published
 *   language 인 [AvailableTransitionView] 에는 `key` 필드가 없다. 그래서 여기서 조립한다 —
 *   **[transitionId] 가 이 값을 대체했으므로 이 재계산을 더 늘리지 마라.**
 *
 *   `"null__..."` 이 나오지 않는다(실측). GLOBAL 전환은 정의상 출발 상태가 없지만,
 *   `WorkflowEngine.availableTransitions` 가 `transition.fromStateKey ?: req.fromStateKey` 로
 *   **요청한 현재 상태**를 채워 넘기고 [AvailableTransitionView.fromStateKey] 도 non-null 이다.
 * @property toCategory 전환 목표 상태의 카테고리 문자열. 예: `"DONE"`, `"IN_PROGRESS"`, `"TODO"`.
 *   프론트엔드가 종료(DONE) 전환을 판별할 때 사용한다.
 *   null 은 워크플로우 미설정 등 비정상 상태를 의미한다. 실 API 응답은 항상 non-null.
 * @property transitionId 전환 1급 식별자. 클라이언트는 이 값을
 *   `POST /api/v1/issues/{key}/transition` 의 `transitionId` 에 그대로 되실어 그 전환을 지목 실행한다.
 *   null 은 「전환 ID 가 없다」가 아니라 **미계산**이다 — 실 API 응답은 항상 non-null.
 * @property kind 전환 종류 문자열. `"NORMAL"`(출발 상태 지정) · `"GLOBAL"`(어느 상태에서나).
 *   `"INITIAL"`(이슈 생성 진입 전용)은 이 목록에 나오지 않는다. null 은 미계산 상태다.
 */
data class TransitionItem(
    val fromStateKey: String,
    val toStateKey: String,
    val name: String,
    val key: String,
    val toCategory: String?,
    val transitionId: UUID?,
    val kind: String?,
) {
    companion object {
        /**
         * [AvailableTransitionView] 를 [TransitionItem] 으로 변환한다.
         *
         * [TransitionItem.toCategory] 는 [AvailableTransitionView.toCategory] 를 그대로 전달한다.
         * 프론트엔드는 이 값으로 DONE 전환 여부를 판별한다.
         *
         * [TransitionItem.transitionId] · [TransitionItem.kind] 도 그대로 전달한다 —
         * 409 `AMBIGUOUS_TRANSITION` 재요청은 후보 id 를 되실어 보내는 왕복이라
         * 목록 응답에 id 가 없으면 그 왕복이 API 로 성립하지 않는다 (ADR 2026-08-18 §D3).
         *
         * @param view shared-kernel 의 가용 전환 뷰.
         * @return REST 응답 항목.
         */
        fun from(view: AvailableTransitionView): TransitionItem =
            TransitionItem(
                fromStateKey = view.fromStateKey,
                toStateKey = view.toStateKey,
                name = view.name,
                key = "${view.fromStateKey}__${view.toStateKey}",
                toCategory = view.toCategory,
                transitionId = view.transitionId,
                kind = view.kind,
            )
    }
}
