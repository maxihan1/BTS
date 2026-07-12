// SlackInteractionPayloadParser 단위 테스트 — block_actions/view_submission/unknown 파싱 검증 (FR-SL-05 Task 5)
package com.bts.slack.interaction

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [SlackInteractionPayloadParser] 단위 테스트 (FR-SL-05 Task 5).
 *
 * Spring 컨텍스트 없이 [ObjectMapper]를 직접 구성해 파서를 인스턴스화한다([SlackEventsControllerTest]
 * 동형). 실제 Slack이 보내는 `block_actions`/`view_submission` payload 샘플(비관련 필드 다수 포함)로
 * 방어적 파싱(필요 필드만 추출·무관 필드 무시·필드 누락 시 null 안전 처리)을 검증한다.
 */
class SlackInteractionPayloadParserTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val parser = SlackInteractionPayloadParser(objectMapper)

    @Test
    fun `block_actions payload를 BlockActions로 파싱한다`() {
        val json =
            """
            {
              "type": "block_actions",
              "user": {"id": "U083KJ", "username": "alice", "team_id": "T0G9PQBBK"},
              "api_app_id": "A0G9ABC",
              "token": "verification-token",
              "team": {"id": "T0G9PQBBK", "domain": "atlas"},
              "trigger_id": "12466734323.1024.abcd",
              "response_url": "https://hooks.slack.com/actions/T0G9/1234/abcd",
              "channel": {"id": "C0G9QF9GW", "name": "general"},
              "message": {"type": "message", "ts": "1548261231.000200", "text": "PROJ-123"},
              "actions": [
                {
                  "action_id": "atlas_complete",
                  "block_id": "b1",
                  "text": {"type": "plain_text", "text": "완료로 표시", "emoji": true},
                  "value": "PROJ-123",
                  "type": "button",
                  "action_ts": "1548426417.840180"
                }
              ]
            }
            """.trimIndent()

        val result = parser.parse(json)

        assertThat(result).isEqualTo(
            SlackInteractionPayload.BlockActions(
                userId = "U083KJ",
                teamId = "T0G9PQBBK",
                triggerId = "12466734323.1024.abcd",
                responseUrl = "https://hooks.slack.com/actions/T0G9/1234/abcd",
                channel = "C0G9QF9GW",
                messageTs = "1548261231.000200",
                actions =
                    listOf(
                        SlackInteractionPayload.BlockActions.Action(actionId = "atlas_complete", value = "PROJ-123"),
                    ),
            ),
        )
    }

    @Test
    fun `block_actions payload의 actions 배열은 순서를 보존하며 여러 개를 담는다`() {
        val json =
            """
            {
              "type": "block_actions",
              "user": {"id": "U1"},
              "team": {"id": "T1"},
              "trigger_id": "trig1",
              "response_url": "https://hooks.slack.com/actions/x",
              "channel": {"id": "C1"},
              "message": {"ts": "111.222"},
              "actions": [
                {"action_id": "atlas_view", "value": "PROJ-1"},
                {"action_id": "atlas_complete", "value": "PROJ-2"}
              ]
            }
            """.trimIndent()

        val result = parser.parse(json) as SlackInteractionPayload.BlockActions

        assertThat(result.actions).containsExactly(
            SlackInteractionPayload.BlockActions.Action(actionId = "atlas_view", value = "PROJ-1"),
            SlackInteractionPayload.BlockActions.Action(actionId = "atlas_complete", value = "PROJ-2"),
        )
    }

    @Test
    fun `view_submission payload를 ViewSubmission으로 파싱한다`() {
        val json =
            """
            {
              "type": "view_submission",
              "user": {"id": "U0G9WFXNZ", "name": "bob", "team_id": "T0G9PQBBK"},
              "api_app_id": "A0G9ABC",
              "token": "verification-token",
              "team": {"id": "T0G9PQBBK", "domain": "atlas"},
              "trigger_id": "trig-submit-1",
              "view": {
                "id": "VMHU10V25",
                "team_id": "T0G9PQBBK",
                "type": "modal",
                "callback_id": "atlas_complete_modal",
                "private_metadata": "{\"issueKey\":\"PROJ-123\",\"expectedVersion\":3}",
                "state": {
                  "values": {
                    "resolution_block": {
                      "resolution_select": {
                        "type": "static_select",
                        "selected_option": {"value": "resolution-uuid-1"}
                      }
                    }
                  }
                }
              },
              "response_urls": []
            }
            """.trimIndent()

        val result = parser.parse(json)

        assertThat(result).isInstanceOf(SlackInteractionPayload.ViewSubmission::class.java)
        val viewSubmission = result as SlackInteractionPayload.ViewSubmission
        assertThat(viewSubmission.userId).isEqualTo("U0G9WFXNZ")
        assertThat(viewSubmission.teamId).isEqualTo("T0G9PQBBK")
        assertThat(viewSubmission.callbackId).isEqualTo("atlas_complete_modal")
        assertThat(viewSubmission.privateMetadata).isEqualTo("""{"issueKey":"PROJ-123","expectedVersion":3}""")
        assertThat(viewSubmission.stateValues).containsKey("resolution_block")
    }

    @Test
    fun `알 수 없는 type은 Unknown으로 수렴한다`() {
        val json = """{"type": "shortcut", "user": {"id": "U1"}}"""

        assertThat(parser.parse(json)).isEqualTo(SlackInteractionPayload.Unknown)
    }

    @Test
    fun `type 필드가 없으면 Unknown으로 수렴한다`() {
        val json = """{"foo": "bar"}"""

        assertThat(parser.parse(json)).isEqualTo(SlackInteractionPayload.Unknown)
    }

    @Test
    fun `유효하지 않은 JSON은 Unknown으로 수렴한다`() {
        val invalidJson = "{not valid json"

        assertThat(parser.parse(invalidJson)).isEqualTo(SlackInteractionPayload.Unknown)
    }

    @Test
    fun `block_actions payload에서 user team 등 필드가 누락되면 null로 안전 처리한다`() {
        val json = """{"type": "block_actions"}"""

        val result = parser.parse(json) as SlackInteractionPayload.BlockActions

        assertThat(result.userId).isNull()
        assertThat(result.teamId).isNull()
        assertThat(result.triggerId).isNull()
        assertThat(result.responseUrl).isNull()
        assertThat(result.channel).isNull()
        assertThat(result.messageTs).isNull()
        assertThat(result.actions).isEmpty()
    }

    @Test
    fun `view_submission payload에서 view 필드 자체가 없으면 관련 필드가 null 또는 빈 값이다`() {
        val json = """{"type": "view_submission", "user": {"id": "U1"}, "team": {"id": "T1"}}"""

        val result = parser.parse(json) as SlackInteractionPayload.ViewSubmission

        assertThat(result.callbackId).isNull()
        assertThat(result.privateMetadata).isNull()
        assertThat(result.stateValues).isEmpty()
    }
}
