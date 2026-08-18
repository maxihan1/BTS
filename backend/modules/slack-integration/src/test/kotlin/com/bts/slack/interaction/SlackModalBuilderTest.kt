// SlackModalBuilder 단위 테스트 — IssueCompletionOptions 로 완료 모달 view JSON 조립 검증 (FR-SL-05 Task 7)

package com.bts.slack.interaction

import com.bts.shared.issue.DoneTransition
import com.bts.shared.issue.IssueCompletionOptions
import com.bts.shared.issue.ResolutionOption
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class SlackModalBuilderTest {
    private val objectMapper = ObjectMapper()
    private val builder = SlackModalBuilder(objectMapper)

    private val resolutionId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @Test
    fun `callback_id 는 atlas_complete_modal 이다`() {
        val options = IssueCompletionOptions(version = 3L, doneTransitions = emptyList(), resolutions = emptyList())

        val view = builder.buildCompletionModal(options, "PROJ-1", "C123", "1234.5678")

        val node = objectMapper.readTree(view)
        assertThat(node.get("callback_id").asText()).isEqualTo("atlas_complete_modal")
    }

    @Test
    fun `resolutions 가 있으면 옵션을 담은 static_select 를 렌더한다`() {
        val options =
            IssueCompletionOptions(
                version = 1L,
                doneTransitions = emptyList(),
                resolutions = listOf(ResolutionOption(resolutionId, "완료됨")),
            )

        val view = builder.buildCompletionModal(options, "PROJ-1", "C123", "1234.5678")

        assertThat(view).contains("\"type\":\"static_select\"")
        assertThat(view).contains("완료됨")
        assertThat(view).contains(resolutionId.toString())
    }

    @Test
    fun `resolutions 가 빈 목록이면 resolution 섹션을 생략한다`() {
        val options = IssueCompletionOptions(version = 1L, doneTransitions = emptyList(), resolutions = emptyList())

        val view = builder.buildCompletionModal(options, "PROJ-1", "C123", "1234.5678")

        assertThat(view).doesNotContain("완료됨")
        assertThat(view).doesNotContain("resolution_select")
    }

    @Test
    fun `done 전환이 다중이면 static_select 옵션으로 doneTransitions 를 렌더한다`() {
        val options =
            IssueCompletionOptions(
                version = 1L,
                doneTransitions =
                    listOf(
                        DoneTransition("done", "완료"),
                        DoneTransition("closed", "종료"),
                    ),
                resolutions = emptyList(),
            )

        val view = builder.buildCompletionModal(options, "PROJ-1", "C123", "1234.5678")

        assertThat(view).contains("완료")
        assertThat(view).contains("종료")
        assertThat(view).contains("\"value\":\"done\"")
        assertThat(view).contains("\"value\":\"closed\"")
    }

    @Test
    fun `private_metadata 는 issueKey, expectedVersion, channel, ts 만 담고 toStateKey 는 넣지 않는다`() {
        val options =
            IssueCompletionOptions(
                version = 7L,
                doneTransitions = listOf(DoneTransition("done", "완료")),
                resolutions = emptyList(),
            )

        val view = builder.buildCompletionModal(options, "PROJ-9", "C999", "9999.0001")

        val node = objectMapper.readTree(view)
        val metadata = objectMapper.readTree(node.get("private_metadata").asText())
        assertThat(metadata.get("issueKey").asText()).isEqualTo("PROJ-9")
        assertThat(metadata.get("expectedVersion").asLong()).isEqualTo(7L)
        assertThat(metadata.get("channel").asText()).isEqualTo("C999")
        assertThat(metadata.get("ts").asText()).isEqualTo("9999.0001")
        assertThat(metadata.has("toStateKey")).isFalse()
    }

    @Test
    fun `buildAssignModal 은 atlas_assign_modal callback_id 와 users_select 를 렌더한다`() {
        val view = builder.buildAssignModal("PROJ-1")

        val node = objectMapper.readTree(view)
        assertThat(node.get("type").asText()).isEqualTo("modal")
        assertThat(node.get("callback_id").asText()).isEqualTo("atlas_assign_modal")
        assertThat(node.get("title").get("text").asText()).isEqualTo("담당자 변경")
        assertThat(node.get("submit").get("text").asText()).isEqualTo("변경")
        assertThat(node.get("close").get("text").asText()).isEqualTo("취소")

        val metadata = objectMapper.readTree(node.get("private_metadata").asText())
        assertThat(metadata.get("issueKey").asText()).isEqualTo("PROJ-1")

        val block = node.get("blocks").get(0)
        assertThat(block.get("type").asText()).isEqualTo("input")
        assertThat(block.get("block_id").asText()).isEqualTo("assignee_block")
        val element = block.get("element")
        assertThat(element.get("type").asText()).isEqualTo("users_select")
        assertThat(element.get("action_id").asText()).isEqualTo("assignee_select")
    }

    @Test
    fun `buildCommentModal 은 atlas_comment_modal callback_id 와 multiline plain_text_input 을 렌더한다`() {
        val view = builder.buildCommentModal("PROJ-2")

        val node = objectMapper.readTree(view)
        assertThat(node.get("type").asText()).isEqualTo("modal")
        assertThat(node.get("callback_id").asText()).isEqualTo("atlas_comment_modal")
        assertThat(node.get("title").get("text").asText()).isEqualTo("코멘트")
        assertThat(node.get("submit").get("text").asText()).isEqualTo("등록")
        assertThat(node.get("close").get("text").asText()).isEqualTo("취소")

        val metadata = objectMapper.readTree(node.get("private_metadata").asText())
        assertThat(metadata.get("issueKey").asText()).isEqualTo("PROJ-2")

        val block = node.get("blocks").get(0)
        assertThat(block.get("type").asText()).isEqualTo("input")
        assertThat(block.get("block_id").asText()).isEqualTo("comment_block")
        val element = block.get("element")
        assertThat(element.get("type").asText()).isEqualTo("plain_text_input")
        assertThat(element.get("action_id").asText()).isEqualTo("comment_input")
        assertThat(element.get("multiline").asBoolean()).isTrue()
    }
}
