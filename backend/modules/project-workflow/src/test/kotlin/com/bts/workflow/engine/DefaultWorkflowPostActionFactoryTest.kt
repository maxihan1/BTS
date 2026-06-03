// DefaultWorkflowPostActionFactory 단위 테스트 — 5종 PostAction 생성 + 예외 경로 검증

package com.bts.workflow.engine

import com.bts.workflow.domain.spi.WorkflowPostAction
import com.bts.workflow.postaction.AddWatcherPostAction
import com.bts.workflow.postaction.CallWebhookPostAction
import com.bts.workflow.postaction.NotifyPostAction
import com.bts.workflow.postaction.RunAutomationPostAction
import com.bts.workflow.postaction.SetFieldPostAction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [DefaultWorkflowPostActionFactory] 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 Kotlin 으로 5종 PostAction 인스턴스 생성과
 * 예외 경로를 검증한다.
 *
 * 테스트 범위.
 * - "SET_FIELD" → [SetFieldPostAction] 생성, type 일치
 * - "NOTIFY" → [NotifyPostAction] 생성, type 일치
 * - "ADD_WATCHER" → [AddWatcherPostAction] 생성, type 일치
 * - "RUN_AUTOMATION" → [RunAutomationPostAction] 생성, type 일치
 * - "CALL_WEBHOOK" → [CallWebhookPostAction] 생성, type 일치
 * - 미지원 type → [IllegalArgumentException] (메시지에 type 포함)
 * - SET_FIELD config["field"] 누락 → [IllegalArgumentException]
 * - NOTIFY config["channel"] 누락 → [IllegalArgumentException]
 * - NOTIFY config["recipients"] 누락 → [IllegalArgumentException]
 * - ADD_WATCHER config["watcher"] 누락 → [IllegalArgumentException]
 * - RUN_AUTOMATION config["automationKey"] 누락 → [IllegalArgumentException]
 * - CALL_WEBHOOK config["url"] 누락 → [IllegalArgumentException]
 * - CALL_WEBHOOK config["method"] 누락 → [IllegalArgumentException]
 */
class DefaultWorkflowPostActionFactoryTest {
    private val factory = DefaultWorkflowPostActionFactory()

    // ─────────────────────────────────────────────────────────────────────────
    // 정상 생성 케이스
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `SET_FIELD — config에 field와 value가 있으면 SetFieldPostAction을 반환한다`() {
        val config = mapOf<String, Any?>("field" to "resolution", "value" to "Fixed")
        val action: WorkflowPostAction = factory.create("SET_FIELD", config)

        assertThat(action).isInstanceOf(SetFieldPostAction::class.java)
        assertThat(action.type).isEqualTo("SET_FIELD")
    }

    @Test
    fun `SET_FIELD — value가 null이어도 SetFieldPostAction을 반환한다`() {
        val config = mapOf<String, Any?>("field" to "resolution", "value" to null)
        val action: WorkflowPostAction = factory.create("SET_FIELD", config)

        assertThat(action).isInstanceOf(SetFieldPostAction::class.java)
        assertThat(action.type).isEqualTo("SET_FIELD")
    }

    @Test
    fun `NOTIFY — config에 channel과 recipients가 있으면 NotifyPostAction을 반환한다`() {
        val config = mapOf<String, Any?>("channel" to "slack", "recipients" to "team-dev")
        val action: WorkflowPostAction = factory.create("NOTIFY", config)

        assertThat(action).isInstanceOf(NotifyPostAction::class.java)
        assertThat(action.type).isEqualTo("NOTIFY")
    }

    @Test
    fun `ADD_WATCHER — config에 watcher가 있으면 AddWatcherPostAction을 반환한다`() {
        val config = mapOf<String, Any?>("watcher" to "\${actor}")
        val action: WorkflowPostAction = factory.create("ADD_WATCHER", config)

        assertThat(action).isInstanceOf(AddWatcherPostAction::class.java)
        assertThat(action.type).isEqualTo("ADD_WATCHER")
    }

    @Test
    fun `RUN_AUTOMATION — config에 automationKey가 있으면 RunAutomationPostAction을 반환한다`() {
        val config = mapOf<String, Any?>("automationKey" to "auto-close-duplicates")
        val action: WorkflowPostAction = factory.create("RUN_AUTOMATION", config)

        assertThat(action).isInstanceOf(RunAutomationPostAction::class.java)
        assertThat(action.type).isEqualTo("RUN_AUTOMATION")
    }

    @Test
    fun `CALL_WEBHOOK — config에 url과 method가 있으면 CallWebhookPostAction을 반환한다`() {
        val config = mapOf<String, Any?>("url" to "https://example.com/hook", "method" to "POST")
        val action: WorkflowPostAction = factory.create("CALL_WEBHOOK", config)

        assertThat(action).isInstanceOf(CallWebhookPostAction::class.java)
        assertThat(action.type).isEqualTo("CALL_WEBHOOK")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 미지원 type 예외
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `미지원 type — IllegalArgumentException을 던지고 메시지에 type이 포함된다`() {
        val unknownType = "UNKNOWN_ACTION"

        assertThatThrownBy {
            factory.create(unknownType, emptyMap())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(unknownType)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 필수 config 키 누락 예외
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `SET_FIELD config field 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("SET_FIELD", emptyMap())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("field")
    }

    @Test
    fun `NOTIFY config channel 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("NOTIFY", mapOf("recipients" to "team-dev"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("channel")
    }

    @Test
    fun `NOTIFY config recipients 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("NOTIFY", mapOf("channel" to "slack"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("recipients")
    }

    @Test
    fun `ADD_WATCHER config watcher 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("ADD_WATCHER", emptyMap())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("watcher")
    }

    @Test
    fun `RUN_AUTOMATION config automationKey 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("RUN_AUTOMATION", emptyMap())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("automationKey")
    }

    @Test
    fun `CALL_WEBHOOK config url 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("CALL_WEBHOOK", mapOf("method" to "POST"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("url")
    }

    @Test
    fun `CALL_WEBHOOK config method 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("CALL_WEBHOOK", mapOf("url" to "https://example.com/hook"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("method")
    }
}
