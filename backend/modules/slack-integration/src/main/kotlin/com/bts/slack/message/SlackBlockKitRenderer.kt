// 알림 제목/이슈 링크를 Slack Block Kit 으로 렌더하는 컴포넌트 (FR-SL-02 Task 6)

package com.bts.slack.message

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 알림 제목과 이슈 키를 Slack Block Kit 메시지로 렌더한다 (FR-SL-02 Task 6).
 *
 * 현재는 인앱 알림과 동일 수준(제목 + 이슈 링크)만 렌더한다. 이슈 summary·행위자 이름 등 rich 콘텐츠는
 * cross-BC 조회가 필요하므로 후속 범위다(스펙 FR8).
 *
 * ## 이슈 링크
 * `{atlasBaseUrl}/issues/{issueKey}` 로 이슈 상세 페이지를 mrkdwn 링크로 건다. `atlasBaseUrl`은
 * `bts.atlas.base-url` 로 주입한다(미설정이어도 부팅이 깨지지 않도록 빈 문자열 기본값 — 실제 발송 시점에만
 * 의미가 있고, notification 파이프라인 발화는 D6 연결 이후이므로 운영 배포 시 값을 채운다).
 *
 * @param atlasBaseUrl BTS 웹 기준 URL(`bts.atlas.base-url`). 끝 슬래시는 정규화한다.
 * @param objectMapper Block Kit JSON 직렬화용 Jackson.
 */
@Component
class SlackBlockKitRenderer(
    @param:Value("\${bts.atlas.base-url:}") private val atlasBaseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 제목과 (선택) 이슈 키로 Block Kit 메시지를 렌더한다.
     *
     * @param title 알림 제목(폴백 text 겸 section 본문).
     * @param issueKey 이슈 키. null 이면 링크 없이 제목만 렌더한다.
     * @return 폴백 text + blocks JSON.
     */
    fun render(
        title: String,
        issueKey: String?,
    ): RenderedSlackMessage {
        val mrkdwn =
            if (issueKey != null) {
                "$title\n<${issueUrl(issueKey)}|$issueKey 보기>"
            } else {
                title
            }

        val blocks =
            objectMapper.createArrayNode().apply {
                add(
                    objectMapper.createObjectNode().apply {
                        put("type", "section")
                        set<com.fasterxml.jackson.databind.node.ObjectNode>(
                            "text",
                            objectMapper.createObjectNode().apply {
                                put("type", "mrkdwn")
                                put("text", mrkdwn)
                            },
                        )
                    },
                )
            }

        return RenderedSlackMessage(
            text = title,
            blocks = objectMapper.writeValueAsString(blocks),
        )
    }

    /** `{atlasBaseUrl}/issues/{issueKey}` — base URL 끝 슬래시를 제거해 이중 슬래시를 막는다. */
    private fun issueUrl(issueKey: String): String = "${atlasBaseUrl.trimEnd('/')}/issues/$issueKey"
}
