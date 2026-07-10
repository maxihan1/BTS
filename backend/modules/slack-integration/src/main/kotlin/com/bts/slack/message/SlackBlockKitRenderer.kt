// 알림 제목/이슈 링크·unfurl 카드를 Slack Block Kit 으로 렌더하는 컴포넌트 (FR-SL-02 Task 6 / FR-SL-03 Task 8)

package com.bts.slack.message

import com.bts.shared.issue.IssueUnfurlView
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 알림 제목/이슈 키, Slack unfurl 카드를 Slack Block Kit 메시지로 렌더한다 (FR-SL-02 Task 6 / FR-SL-03 Task 8).
 *
 * [render]는 인앱 알림과 동일 수준(제목 + 이슈 링크)만 렌더한다. [renderUnfurlCard]는 Slack `link_shared`
 * unfurl용 이슈 스냅샷 카드(키·제목·상태·우선순위·담당자)를 렌더한다 — cross-BC 조회 결과([IssueUnfurlView])를
 * 그대로 소비한다.
 *
 * ## 이슈 링크
 * `{atlasBaseUrl}/issues/{issueKey}` 로 이슈 상세 페이지를 mrkdwn 링크로 건다. `atlasBaseUrl`은
 * `bts.atlas.base-url` 로 주입한다(미설정이어도 부팅이 깨지지 않도록 빈 문자열 기본값 — 실제 발송 시점에만
 * 의미가 있고, notification 파이프라인 발화는 D6 연결 이후이므로 운영 배포 시 값을 채운다).
 *
 * ## unfurl 카드 = 정보 카드만 (ADR D3)
 * [renderUnfurlCard]는 상태 변경·댓글 같은 액션 버튼을 절대 포함하지 않는다. 버튼 상호작용은
 * FR-SL-05(인터랙티브)의 책임이라 이 메서드가 선점하면 BC 스코프가 번진다.
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

    /**
     * Slack unfurl용 이슈 스냅샷 카드를 렌더한다 (FR-SL-03 Task 8).
     *
     * 카드는 정확히 두 블록이다 — (1) 키+제목 링크 섹션, (2) 상태·우선순위·담당자 필드 섹션. 담당자가
     * 없으면(`assigneeDisplayName == null`) "미지정"으로 폴백한다. 액션 버튼은 절대 포함하지 않는다(ADR D3).
     *
     * @param view cross-BC 결합 포트([com.bts.shared.issue.IssueUnfurlPort])가 확인한 열람 가능 이슈 스냅샷.
     * @return `{"blocks": [...]}` 형태의 카드 JSON — [com.bts.slack.message.SlackUnfurlClient]가 URL 키에
     *   매핑해 `chat.unfurl`의 `unfurls`에 싣는다.
     */
    fun renderUnfurlCard(view: IssueUnfurlView): com.fasterxml.jackson.databind.node.ObjectNode {
        val titleSection =
            objectMapper.createObjectNode().apply {
                put("type", "section")
                set<com.fasterxml.jackson.databind.node.ObjectNode>(
                    "text",
                    mrkdwnText("<${issueUrl(view.issueKey)}|${view.issueKey}> ${view.summary}"),
                )
            }

        val assigneeLabel = view.assigneeDisplayName ?: UNASSIGNED_LABEL
        val detailSection =
            objectMapper.createObjectNode().apply {
                put("type", "section")
                set<com.fasterxml.jackson.databind.node.ArrayNode>(
                    "fields",
                    objectMapper.createArrayNode().apply {
                        add(mrkdwnText("*상태*\n${view.statusLabel}"))
                        add(mrkdwnText("*우선순위*\n${view.priorityLabel}"))
                        add(mrkdwnText("*담당자*\n$assigneeLabel"))
                    },
                )
            }

        val blocks =
            objectMapper.createArrayNode().apply {
                add(titleSection)
                add(detailSection)
            }

        return objectMapper.createObjectNode().apply {
            set<com.fasterxml.jackson.databind.node.ArrayNode>("blocks", blocks)
        }
    }

    /** `{atlasBaseUrl}/issues/{issueKey}` — base URL 끝 슬래시를 제거해 이중 슬래시를 막는다. */
    private fun issueUrl(issueKey: String): String = "${atlasBaseUrl.trimEnd('/')}/issues/$issueKey"

    /** `{"type": "mrkdwn", "text": text}` — Block Kit text object. */
    private fun mrkdwnText(text: String): com.fasterxml.jackson.databind.node.ObjectNode =
        objectMapper.createObjectNode().apply {
            put("type", "mrkdwn")
            put("text", text)
        }

    private companion object {
        const val UNASSIGNED_LABEL = "미지정"
    }
}
