// DefaultWorkflowValidatorFactory 단위 테스트 — 4종 validator 생성 + 예외 경로 검증

package com.bts.workflow.engine

import com.bts.workflow.domain.spi.WorkflowValidator
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.validator.CustomExpressionValidator
import com.bts.workflow.validator.NotStatusCategoryValidator
import com.bts.workflow.validator.PermissionValidator
import com.bts.workflow.validator.RequiredFieldValidator
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [DefaultWorkflowValidatorFactory] 단위 테스트.
 *
 * Spring 컨텍스트 없이 MockK 로 [PermissionResolver] 와 [SpelEvaluator] 를 주입해
 * 4종 validator 인스턴스 생성과 예외 경로를 검증한다.
 *
 * 테스트 범위.
 * - "RequiredField" → [RequiredFieldValidator] 생성, type 일치
 * - "permission-check" → [PermissionValidator] 생성, type 일치
 * - "not-status-category" → [NotStatusCategoryValidator] 생성, type 일치
 * - "CustomExpression" → [CustomExpressionValidator] 생성, type 일치
 * - 미지원 type → [IllegalArgumentException] (메시지에 type 포함)
 * - RequiredField config["field"] 누락 → [IllegalArgumentException]
 * - permission-check config["permission"] 누락 → [IllegalArgumentException]
 * - not-status-category config["category"] 누락 → [IllegalArgumentException]
 * - CustomExpression config["expression"] 누락 → [IllegalArgumentException]
 */
class DefaultWorkflowValidatorFactoryTest {

    private val permissionResolver: PermissionResolver = mockk()
    private val spelEvaluator: SpelEvaluator = mockk()

    private val factory = DefaultWorkflowValidatorFactory(
        permissionResolver = permissionResolver,
        spelEvaluator = spelEvaluator,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // 정상 생성 케이스
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `RequiredField — config에 field가 있으면 RequiredFieldValidator를 반환한다`() {
        val config = mapOf<String, Any?>("field" to "resolution")
        val validator: WorkflowValidator = factory.create("RequiredField", config)

        assertThat(validator).isInstanceOf(RequiredFieldValidator::class.java)
        assertThat(validator.type).isEqualTo("RequiredField")
    }

    @Test
    fun `permission-check — config에 permission이 있으면 PermissionValidator를 반환한다`() {
        val config = mapOf<String, Any?>("permission" to "TRANSITION_ISSUE")
        val validator: WorkflowValidator = factory.create("permission-check", config)

        assertThat(validator).isInstanceOf(PermissionValidator::class.java)
        assertThat(validator.type).isEqualTo("permission-check")
    }

    @Test
    fun `permission-check — scope 지정 시 해당 scope로 PermissionValidator를 생성한다`() {
        val config = mapOf<String, Any?>("permission" to "ADMIN_WORKFLOW", "scope" to "PROJECT")
        val validator: WorkflowValidator = factory.create("permission-check", config)

        assertThat(validator).isInstanceOf(PermissionValidator::class.java)
        assertThat(validator.type).isEqualTo("permission-check")
    }

    @Test
    fun `not-status-category — config에 category가 있으면 NotStatusCategoryValidator를 반환한다`() {
        val config = mapOf<String, Any?>("category" to "DONE")
        val validator: WorkflowValidator = factory.create("not-status-category", config)

        assertThat(validator).isInstanceOf(NotStatusCategoryValidator::class.java)
        assertThat(validator.type).isEqualTo("not-status-category")
    }

    @Test
    fun `CustomExpression — config에 expression이 있으면 CustomExpressionValidator를 반환한다`() {
        val config = mapOf<String, Any?>("expression" to "issue.priority == 'HIGH'")
        val validator: WorkflowValidator = factory.create("CustomExpression", config)

        assertThat(validator).isInstanceOf(CustomExpressionValidator::class.java)
        assertThat(validator.type).isEqualTo("CustomExpression")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 미지원 type 예외
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `미지원 type — IllegalArgumentException을 던지고 메시지에 type이 포함된다`() {
        val unknownType = "UnknownValidator"

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
    fun `RequiredField config field 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("RequiredField", emptyMap())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("field")
    }

    @Test
    fun `permission-check config permission 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("permission-check", emptyMap())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("permission")
    }

    @Test
    fun `not-status-category config category 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("not-status-category", emptyMap())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("category")
    }

    @Test
    fun `CustomExpression config expression 누락 — IllegalArgumentException을 던진다`() {
        assertThatThrownBy {
            factory.create("CustomExpression", emptyMap())
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("expression")
    }
}
