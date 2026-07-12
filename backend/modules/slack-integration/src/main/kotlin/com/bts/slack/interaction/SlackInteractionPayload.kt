// Slack 인터랙티브 payload(block_actions/view_submission) 파싱 결과를 표현하는 값 객체(VO) 계층 (FR-SL-05 Task 5)

package com.bts.slack.interaction

/**
 * Slack이 `POST /slack/interactions`로 보내는 인터랙티브 payload를 파싱한 결과.
 *
 * [SlackInteractionPayloadParser.parse]의 반환 타입이다. 파서는 Slack 원문 JSON 필드를 그대로
 * 옮겨 담기만 하며(raw 보존), 값의 의미 해석(예: [ViewSubmission.privateMetadata]의 JSON 문자열
 * 구조, [ViewSubmission.stateValues]에서 사용자가 실제로 선택한 값 추출)은 후속 오케스트레이션
 * 서비스(`SlackInteractionService`, FR-SL-05 Task 8)의 책임이다.
 *
 * @see SlackInteractionPayloadParser
 */
sealed interface SlackInteractionPayload {
    /**
     * 버튼·셀렉트 클릭 (`type=block_actions`).
     *
     * @property userId 클릭한 Slack 사용자 id(`user.id`). 방어적 파싱이라 필드 누락 시 null.
     * @property teamId 발신 워크스페이스 id(`team.id`).
     * @property triggerId 모달을 열 때(`views.open`) 필요한 1회용 토큰(`trigger_id`). 3초 내 미사용 시 만료.
     * @property responseUrl 지연 응답용 1회용 웹훅 URL(`response_url`).
     * @property channel 원본 메시지가 게시된 채널 id(`channel.id`).
     * @property messageTs 원본 메시지 타임스탬프(`message.ts`). `chat.update` 갱신에 필요.
     * @property actions 클릭된 액션 목록(`actions[]`). 대부분 1개이나 Slack 프로토콜상 배열로 정의된다.
     */
    data class BlockActions(
        val userId: String?,
        val teamId: String?,
        val triggerId: String?,
        val responseUrl: String?,
        val channel: String?,
        val messageTs: String?,
        val actions: List<Action>,
    ) : SlackInteractionPayload {
        /**
         * [BlockActions.actions] 배열의 원소 하나.
         *
         * @property actionId 버튼/셀렉트에 박제한 식별자(`action_id`). 예: `atlas_complete`, `atlas_view`.
         * @property value 버튼/셀렉트에 박제한 값(`value`). 예: 이슈 키.
         */
        data class Action(
            val actionId: String?,
            val value: String?,
        )
    }

    /**
     * 모달 제출 (`type=view_submission`).
     *
     * @property userId 제출한 Slack 사용자 id(`user.id`).
     * @property teamId 발신 워크스페이스 id(`team.id`).
     * @property callbackId 모달을 열 때 지정한 콜백 식별자(`view.callback_id`). 예: `atlas_complete_modal`.
     * @property privateMetadata 모달을 열 때 박제한 원문 JSON 문자열(`view.private_metadata`). 파서는
     *   해석하지 않고 그대로 보존한다 — 구조 해석은 소비자(서비스)가 담당.
     * @property stateValues 모달 입력 블록별 사용자 선택값(`view.state.values`). Slack이 블록 id를 키로
     *   중첩 구조를 보내므로 해석 없이 Map으로 그대로 보존한다.
     */
    data class ViewSubmission(
        val userId: String?,
        val teamId: String?,
        val callbackId: String?,
        val privateMetadata: String?,
        val stateValues: Map<String, Any?>,
    ) : SlackInteractionPayload

    /** 그 외 payload 타입(예: `shortcut`, `message_action`) 또는 `type` 필드 부재·유효하지 않은 JSON. 호출자는 200 no-op으로 수렴시킨다. */
    data object Unknown : SlackInteractionPayload
}
