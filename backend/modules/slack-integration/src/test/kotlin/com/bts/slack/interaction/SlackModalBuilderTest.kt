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
    fun `done 전이가 다중이면 static_select 옵션으로 doneTransitions 를 렌더한다`() {
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
}
