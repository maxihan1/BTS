// 렌더된 Slack 메시지 — 접근성 폴백 text + Block Kit blocks JSON (FR-SL-02 Task 6)

package com.bts.slack.message

/**
 * [SlackBlockKitRenderer]가 렌더한 Slack 메시지 표현.
 *
 * [SlackMessageClient]가 `chat.postMessage`의 `text`(폴백)와 `blocks`(리치 렌더)에 각각 싣는다.
 *
 * @property text 접근성/알림 폴백 문자열(blocks 미지원 클라이언트·푸시 프리뷰용).
 * @property blocks Block Kit `blocks` 배열의 JSON 문자열.
 */
data class RenderedSlackMessage(
    val text: String,
    val blocks: String,
)
