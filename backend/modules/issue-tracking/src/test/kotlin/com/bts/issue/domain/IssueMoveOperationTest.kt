// 이슈 이동 도메인 검증 규칙 단위 테스트 — EC1/EC15/EC7/EC8/EC9

package com.bts.issue.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals

class IssueMoveOperationTest {

    // ── 공통 픽스처 ────────────────────────────────────────────────

    private val sourceProjectKey = "SRC"
    private val targetProjectKey = "TGT"

    /** 정상 시나리오에서 사용할 기본 검증 컨텍스트. */
    private fun validContext(
        targetProjectKey: String = this.targetProjectKey,
        hasSubtasks: Boolean = false,
        sourceStatusKey: String = "IN_PROGRESS",
        targetWorkflowStatuses: Set<String> = setOf("TO_DO", "IN_PROGRESS", "DONE"),
        targetStateKey: String? = null,
        componentMappingTargetIds: Set<UUID> = emptySet(),
        targetProjectComponentIds: Set<UUID> = emptySet(),
        versionMappingTargetIds: Set<UUID> = emptySet(),
        targetProjectVersionIds: Set<UUID> = emptySet(),
        requiredFieldKeys: Set<String> = emptySet(),
        providedFieldKeys: Set<String> = emptySet(),
    ): IssueMoveContext = IssueMoveContext(
        sourceProjectKey = sourceProjectKey,
        targetProjectKey = targetProjectKey,
        hasSubtasks = hasSubtasks,
        sourceStatusKey = sourceStatusKey,
        targetWorkflowStatuses = targetWorkflowStatuses,
        targetStateKey = targetStateKey,
        componentMappingTargetIds = componentMappingTargetIds,
        targetProjectComponentIds = targetProjectComponentIds,
        versionMappingTargetIds = versionMappingTargetIds,
        targetProjectVersionIds = targetProjectVersionIds,
        requiredFieldKeys = requiredFieldKeys,
        providedFieldKeys = providedFieldKeys,
    )

    // ── EC1: 같은 프로젝트 이동 ────────────────────────────────────

    @Test
    fun `EC1 - 같은 프로젝트로 이동 시 MoveSameProjectException 발생`() {
        val ctx = validContext(targetProjectKey = sourceProjectKey)
        assertThrows<MoveSameProjectException> {
            IssueMoveOperation.validate(ctx)
        }
    }

    @Test
    fun `EC1 - 다른 프로젝트로 이동 시 통과`() {
        val ctx = validContext()
        // EC1 통과 시나리오 — 워크플로우 상태 일치(targetStateKey 불필요)
        val ctxCompatible = ctx.copy(
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO", "DONE"),
        )
        IssueMoveOperation.validate(ctxCompatible) // 예외 없으면 통과
    }

    // ── EC15: 서브태스크 있음 ──────────────────────────────────────

    @Test
    fun `EC15 - 자식 이슈가 있을 때 IssueHasSubtasksException 발생`() {
        val ctx = validContext(
            hasSubtasks = true,
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO", "DONE"),
        )
        assertThrows<IssueHasSubtasksException> {
            IssueMoveOperation.validate(ctx)
        }
    }

    @Test
    fun `EC15 - 자식 이슈가 없으면 통과`() {
        val ctx = validContext(
            hasSubtasks = false,
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO", "DONE"),
        )
        IssueMoveOperation.validate(ctx)
    }

    // ── EC7: 워크플로우 상태 비호환 ────────────────────────────────

    @Test
    fun `EC7 - 소스 상태가 대상 워크플로우에 없고 targetStateKey 미지정 시 InvalidTargetStateException 발생`() {
        val ctx = validContext(
            sourceStatusKey = "IN_REVIEW",
            targetWorkflowStatuses = setOf("TO_DO", "IN_PROGRESS", "DONE"),
            targetStateKey = null,
        )
        assertThrows<InvalidTargetStateException> {
            IssueMoveOperation.validate(ctx)
        }
    }

    @Test
    fun `EC7 - 소스 상태가 대상 워크플로우에 없고 targetStateKey가 대상 워크플로우에도 없으면 InvalidTargetStateException 발생`() {
        val ctx = validContext(
            sourceStatusKey = "IN_REVIEW",
            targetWorkflowStatuses = setOf("TO_DO", "IN_PROGRESS", "DONE"),
            targetStateKey = "CUSTOM_STATE",
        )
        assertThrows<InvalidTargetStateException> {
            IssueMoveOperation.validate(ctx)
        }
    }

    @Test
    fun `EC7 - 소스 상태가 대상 워크플로우에 없지만 targetStateKey가 유효하면 통과`() {
        val ctx = validContext(
            sourceStatusKey = "IN_REVIEW",
            targetWorkflowStatuses = setOf("TO_DO", "IN_PROGRESS", "DONE"),
            targetStateKey = "TO_DO",
        )
        IssueMoveOperation.validate(ctx)
    }

    @Test
    fun `EC7 - 소스 상태가 대상 워크플로우에 있으면 targetStateKey 없어도 통과`() {
        val ctx = validContext(
            sourceStatusKey = "IN_PROGRESS",
            targetWorkflowStatuses = setOf("TO_DO", "IN_PROGRESS", "DONE"),
            targetStateKey = null,
        )
        IssueMoveOperation.validate(ctx)
    }

    // ── EC8: 컴포넌트/버전 매핑 대상 id 미존재 ─────────────────────

    @Test
    fun `EC8 - 컴포넌트 매핑 대상 id가 대상 프로젝트에 없으면 InvalidTargetMappingException 발생`() {
        val unknownId = UUID.randomUUID()
        val knownId = UUID.randomUUID()
        val ctx = validContext(
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO"),
            componentMappingTargetIds = setOf(knownId, unknownId),
            targetProjectComponentIds = setOf(knownId),
        )
        assertThrows<InvalidTargetMappingException> {
            IssueMoveOperation.validate(ctx)
        }
    }

    @Test
    fun `EC8 - 버전 매핑 대상 id가 대상 프로젝트에 없으면 InvalidTargetMappingException 발생`() {
        val unknownId = UUID.randomUUID()
        val ctx = validContext(
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO"),
            versionMappingTargetIds = setOf(unknownId),
            targetProjectVersionIds = emptySet(),
        )
        assertThrows<InvalidTargetMappingException> {
            IssueMoveOperation.validate(ctx)
        }
    }

    @Test
    fun `EC8 - 컴포넌트와 버전 매핑이 모두 유효하면 통과`() {
        val compId = UUID.randomUUID()
        val verId = UUID.randomUUID()
        val ctx = validContext(
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO"),
            componentMappingTargetIds = setOf(compId),
            targetProjectComponentIds = setOf(compId),
            versionMappingTargetIds = setOf(verId),
            targetProjectVersionIds = setOf(verId),
        )
        IssueMoveOperation.validate(ctx)
    }

    // ── EC9: 대상 프로젝트 필수 커스텀필드 미제공 ──────────────────

    @Test
    fun `EC9 - 필수 커스텀필드 값이 누락되면 RequiredFieldMissingException 발생`() {
        val ctx = validContext(
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO"),
            requiredFieldKeys = setOf("customer_impact", "severity"),
            providedFieldKeys = setOf("severity"),
        )
        val ex = assertThrows<RequiredFieldMissingException> {
            IssueMoveOperation.validate(ctx)
        }
        assertEquals(setOf("customer_impact"), ex.missingKeys)
    }

    @Test
    fun `EC9 - 모든 필수 커스텀필드가 제공되면 통과`() {
        val ctx = validContext(
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO"),
            requiredFieldKeys = setOf("severity"),
            providedFieldKeys = setOf("severity", "extra_field"),
        )
        IssueMoveOperation.validate(ctx)
    }

    @Test
    fun `EC9 - 필수 커스텀필드가 없으면 통과`() {
        val ctx = validContext(
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO"),
            requiredFieldKeys = emptySet(),
            providedFieldKeys = emptySet(),
        )
        IssueMoveOperation.validate(ctx)
    }

    // ── 복합 정상 시나리오 ─────────────────────────────────────────

    @Test
    fun `정상 - 모든 조건을 만족하면 예외 없이 통과`() {
        val compId = UUID.randomUUID()
        val verId = UUID.randomUUID()
        val ctx = validContext(
            hasSubtasks = false,
            sourceStatusKey = "TO_DO",
            targetWorkflowStatuses = setOf("TO_DO", "DONE"),
            targetStateKey = null,
            componentMappingTargetIds = setOf(compId),
            targetProjectComponentIds = setOf(compId),
            versionMappingTargetIds = setOf(verId),
            targetProjectVersionIds = setOf(verId),
            requiredFieldKeys = setOf("severity"),
            providedFieldKeys = setOf("severity"),
        )
        IssueMoveOperation.validate(ctx)
    }
}
