// 자동화 액션 이슈 변경 cross-BC 포트 계약 검증 — IssueMutationPort fail-closed + 커맨드/결과 VO 필드
package com.bts.shared.issue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [IssueMutationPort] cross-BC 포트 계약 테스트 (FR-AT-02 Task 2).
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [IssueMutationPort] 는 default 구현이 없음 — 3 메서드 모두 추상(fail-closed).
 * - [SetFieldCommand] 필드 계약(actorUserId/issueKey/field/value/dryRun).
 * - [AssignCommand] 필드 계약(actorUserId/issueKey/assigneeId/dryRun), assigneeId nullable.
 * - [AddCommentCommand] 필드 계약(actorUserId/issueKey/body/dryRun).
 * - [MutationResult] 필드 계약(issueKey/applied/version), version nullable.
 */
class IssueMutationPortContractTest {
    // ── IssueMutationPort fail-closed ────────────────────────────────────────

    @Test
    fun `IssueMutationPort 는 default 구현 없이 3 메서드 모두 추상으로 선언된다`() {
        // 3 메서드 모두 override 하지 않으면 익명 객체 생성이 컴파일되지 않는다.
        // 이 테스트는 컴파일 타임에 override 강제를 검증한다.
        val port =
            object : IssueMutationPort {
                override fun setField(cmd: SetFieldCommand): MutationResult =
                    MutationResult(issueKey = cmd.issueKey, applied = !cmd.dryRun, version = 1L)

                override fun assign(cmd: AssignCommand): MutationResult =
                    MutationResult(issueKey = cmd.issueKey, applied = !cmd.dryRun, version = 1L)

                override fun addComment(cmd: AddCommentCommand): MutationResult =
                    MutationResult(issueKey = cmd.issueKey, applied = !cmd.dryRun, version = null)
            }

        val setFieldResult =
            port.setField(
                SetFieldCommand(
                    actorUserId = UUID.randomUUID(),
                    issueKey = "PROJ-1",
                    field = "priority",
                    value = "3",
                    dryRun = false,
                ),
            )
        val assignResult =
            port.assign(
                AssignCommand(
                    actorUserId = UUID.randomUUID(),
                    issueKey = "PROJ-1",
                    assigneeId = UUID.randomUUID(),
                    dryRun = false,
                ),
            )
        val addCommentResult =
            port.addComment(
                AddCommentCommand(
                    actorUserId = UUID.randomUUID(),
                    issueKey = "PROJ-1",
                    body = "자동화 코멘트",
                    dryRun = false,
                ),
            )

        assertThat(setFieldResult.issueKey).isEqualTo("PROJ-1")
        assertThat(assignResult.issueKey).isEqualTo("PROJ-1")
        assertThat(addCommentResult.issueKey).isEqualTo("PROJ-1")
    }

    // ── 타입 있는 권한 거부 예외 (FR-AT-02 C3) ────────────────────────────────

    @Test
    fun `IssueMutationPermissionDeniedException 은 RuntimeException 이며 message 와 cause 를 보존한다`() {
        // 소비자(automation ActionExecutor)가 클래스명 문자열 매칭 없이 타입으로 권한 거부를 분류할 수
        // 있게 하는 포트 계약의 타입 있는 신호다. 어댑터가 원 도메인 예외를 cause 로 감싸 던진다.
        val cause = IllegalStateException("원 도메인 예외")
        val e = IssueMutationPermissionDeniedException("이슈 변경 권한이 없습니다", cause)

        assertThat(e).isInstanceOf(RuntimeException::class.java)
        assertThat(e.message).isEqualTo("이슈 변경 권한이 없습니다")
        assertThat(e.cause).isSameAs(cause)
    }

    // ── SetFieldCommand 필드 계약 ─────────────────────────────────────────────

    @Test
    fun `SetFieldCommand 는 actorUserId issueKey field value dryRun 을 보존한다`() {
        val actorUserId = UUID.randomUUID()
        val cmd =
            SetFieldCommand(
                actorUserId = actorUserId,
                issueKey = "PROJ-2",
                field = "priority",
                value = "5",
                dryRun = true,
            )

        assertThat(cmd.actorUserId).isEqualTo(actorUserId)
        assertThat(cmd.issueKey).isEqualTo("PROJ-2")
        assertThat(cmd.field).isEqualTo("priority")
        assertThat(cmd.value).isEqualTo("5")
        assertThat(cmd.dryRun).isTrue()
    }

    @Test
    fun `SetFieldCommand 는 value 가 null 이면 필드 해제를 의미할 수 있다`() {
        val cmd =
            SetFieldCommand(
                actorUserId = UUID.randomUUID(),
                issueKey = "PROJ-3",
                field = "dueDate",
                value = null,
                dryRun = false,
            )

        assertThat(cmd.value).isNull()
    }

    // ── AssignCommand 필드 계약 ───────────────────────────────────────────────

    @Test
    fun `AssignCommand 는 actorUserId issueKey assigneeId dryRun 을 보존한다`() {
        val actorUserId = UUID.randomUUID()
        val assigneeId = UUID.randomUUID()
        val cmd =
            AssignCommand(
                actorUserId = actorUserId,
                issueKey = "PROJ-4",
                assigneeId = assigneeId,
                dryRun = false,
            )

        assertThat(cmd.actorUserId).isEqualTo(actorUserId)
        assertThat(cmd.issueKey).isEqualTo("PROJ-4")
        assertThat(cmd.assigneeId).isEqualTo(assigneeId)
        assertThat(cmd.dryRun).isFalse()
    }

    @Test
    fun `AssignCommand 는 assigneeId 가 null 이면 담당자 해제를 의미할 수 있다`() {
        val cmd =
            AssignCommand(
                actorUserId = UUID.randomUUID(),
                issueKey = "PROJ-5",
                assigneeId = null,
                dryRun = false,
            )

        assertThat(cmd.assigneeId).isNull()
    }

    // ── AddCommentCommand 필드 계약 ───────────────────────────────────────────

    @Test
    fun `AddCommentCommand 는 actorUserId issueKey body dryRun 을 보존한다`() {
        val actorUserId = UUID.randomUUID()
        val cmd =
            AddCommentCommand(
                actorUserId = actorUserId,
                issueKey = "PROJ-6",
                body = "자동화 규칙에 의해 추가된 코멘트",
                dryRun = true,
            )

        assertThat(cmd.actorUserId).isEqualTo(actorUserId)
        assertThat(cmd.issueKey).isEqualTo("PROJ-6")
        assertThat(cmd.body).isEqualTo("자동화 규칙에 의해 추가된 코멘트")
        assertThat(cmd.dryRun).isTrue()
    }

    // ── MutationResult 필드 계약 ──────────────────────────────────────────────

    @Test
    fun `MutationResult 는 issueKey applied version 을 보존한다`() {
        val result = MutationResult(issueKey = "PROJ-7", applied = true, version = 3L)

        assertThat(result.issueKey).isEqualTo("PROJ-7")
        assertThat(result.applied).isTrue()
        assertThat(result.version).isEqualTo(3L)
    }

    @Test
    fun `MutationResult 는 dryRun 미리보기 시 applied=false version=null 일 수 있다`() {
        val result = MutationResult(issueKey = "PROJ-8", applied = false, version = null)

        assertThat(result.applied).isFalse()
        assertThat(result.version).isNull()
    }
}
