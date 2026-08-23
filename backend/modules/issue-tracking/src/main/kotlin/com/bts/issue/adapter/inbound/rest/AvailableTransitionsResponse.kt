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
 * @property key 하위호환용 계산 문자열. NORMAL 은 `"open__in_progress"`, GLOBAL·INITIAL 은
 *   `"GLOBAL__done"` 처럼 `KIND__to` 형태다.
 *   **식별자가 아니다.** 같은 상태쌍의 전환 둘은 이 값이 서로 같으므로 이 값으로 전환을 지목하면
 *   틀린 전환을 고른다. 지목에는 반드시 [transitionId] 를 써라. ADR §D3 이 「기존 `key` 는 남긴다」로
 *   정해 두었기에 삭제하지 않고 남긴다.
 *
 *   **여기서 재계산하지 않는다.** 정본 구현은 project-workflow 의 `WorkflowTransition.key` 게터
 *   하나뿐이고, 엔진이 그 결과를 published language [AvailableTransitionView.key] 에 실어 보내며
 *   이 DTO 는 그 값을 그대로 옮긴다. 종전처럼 `"${fromStateKey}__${toStateKey}"` 로 조립하면
 *   같은 GLOBAL 전환을 도메인은 `"GLOBAL__done"`, 응답은 `"open__done"` 이라 부르는 발산이 생기고,
 *   이 값이 `PostActionController` 의 `.../transitions/{transitionKey}/post-actions` 경로 세그먼트로
 *   소비되므로 그 경로가 빗나간다. 규칙 사본을 다시 만들지 마라 — 서로를 검사하지 않는 두 벌이 된다.
 *
 *   null 은 「키가 없다」가 아니라 **미계산**이다 — 뷰를 직접 만든 테스트 픽스처에서만 나온다.
 *   유일한 생산자인 `WorkflowEngine.availableTransitions` 가 항상 채우므로 실 API 응답은 non-null 이다.
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
    val key: String?,
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
         * [TransitionItem.key] 는 [AvailableTransitionView.key] 를 **손대지 않고** 옮긴다.
         * 정본은 project-workflow 의 도메인 게터 하나이고, 여기서 재조립하거나 `?:` 로 폴백을 두면
         * 규칙 구현이 두 벌이 되어 GLOBAL 전환에서 이름이 갈린다.
         *
         * @param view shared-kernel 의 가용 전환 뷰.
         * @return REST 응답 항목.
         */
        fun from(view: AvailableTransitionView): TransitionItem =
            TransitionItem(
                fromStateKey = view.fromStateKey,
                toStateKey = view.toStateKey,
                name = view.name,
                key = view.key,
                toCategory = view.toCategory,
                transitionId = view.transitionId,
                kind = view.kind,
            )
    }
}
