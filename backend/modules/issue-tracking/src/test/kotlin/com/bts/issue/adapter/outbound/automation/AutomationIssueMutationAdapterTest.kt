// AutomationIssueMutationAdapter 단위 테스트 — IssueMutationPort 위임/OCC 재시도/dryRun 롤백 (FR-AT-02 Task 7)

package com.bts.issue.adapter.outbound.automation

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.application.AppChangeAssigneeRequest
import com.bts.issue.application.AppChangeVersionsRequest
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.comment.domain.Comment
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.shared.issue.AddCommentCommand
import com.bts.shared.issue.AssignCommand
import com.bts.shared.issue.IssueMutationPermissionDeniedException
import com.bts.shared.issue.SetFieldCommand
import com.bts.shared.issue.SetFixVersionsCommand
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * [AutomationIssueMutationAdapter] 단위 테스트 (FR-AT-02 Task 7).
 *
 * [IssueApplicationService]/[CommentApplicationService] 는 MockK 로 격리한다.
 * [TransactionTemplate] 은 MockK 로 대체하되 `execute` 호출 시 전달된 콜백을 즉시 동기 실행하도록
 * stub 한다([com.bts.search.imports.mapping.ImportMappingServiceTest] 동일 패턴) — 이를 통해
 * dryRun 시 [TransactionStatus.setRollbackOnly] 호출 여부를 mockk `verify` 로 직접 단언한다.
 *
 * 검증 항목.
 * - setField 가 [IssueApplicationService.updateIssue] 에 actor·expectedVersion(현재 version)·
 *   디코딩된 필드값을 전달한다.
 * - setField 가 미지원 필드에 대해 updateIssue 호출 없이 [IllegalArgumentException] 을 던진다.
 * - setField/assign 이 OCC 충돌([IssueVersionConflictException]) 시 현재 version 을 1 회
 *   재조회해 재시도하고, 재시도도 충돌이면 예외를 전파한다.
 * - assign 이 [IssueApplicationService.changeAssignee] 에 위임한다.
 * - addComment 가 actor=authorId=룰 actor 로 [CommentApplicationService.create] 에 위임하고
 *   version 은 항상 null 이다.
 * - dryRun=true 이면 [TransactionStatus.setRollbackOnly] 가 호출되고 applied=false, version=null 이다.
 */
class AutomationIssueMutationAdapterTest {
    private val issueApplicationService: IssueApplicationService = mockk()
    private val commentApplicationService: CommentApplicationService = mockk()
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val transactionTemplate: TransactionTemplate = mockk()
    private val status: TransactionStatus = mockk(relaxed = true)

    private lateinit var adapter: AutomationIssueMutationAdapter

    private val actorUuid: UUID = UUID.randomUUID()
    private val actor = ActorId(actorUuid)
    private val issueKey = IssueKey("PROJ-1")

    @BeforeEach
    fun setUp() {
        adapter =
            AutomationIssueMutationAdapter(
                issueApplicationService = issueApplicationService,
                commentApplicationService = commentApplicationService,
                objectMapper = objectMapper,
                transactionTemplate = transactionTemplate,
            )
        every { transactionTemplate.execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(status)
        }
    }

    private fun issueResponse(version: Long): IssueResponse =
        IssueResponse(
            key = issueKey.value,
            id = UUID.randomUUID(),
            projectKey = issueKey.projectPrefix,
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = UUID.randomUUID(),
            version = version,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            typeId = 1L,
            typeKey = "task",
            typeName = "Task",
        )

    // ── setField ────────────────────────────────────────────────────────────

    @Test
    fun `setField delegates to updateIssue with decoded value and current version`() {
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 3L)
        every { issueApplicationService.updateIssue(actor, issueKey, any()) } returns issueResponse(version = 4L)

        val cmd =
            SetFieldCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                field = "priority",
                value = "2",
                dryRun = false,
            )
        val result = adapter.setField(cmd)

        assertThat(result.issueKey).isEqualTo(issueKey.value)
        assertThat(result.applied).isTrue()
        assertThat(result.version).isEqualTo(4L)
        verify {
            issueApplicationService.updateIssue(
                actor,
                issueKey,
                match { it.priority == 2 && it.expectedVersion == 3L && it.summary == null },
            )
        }
    }

    @Test
    fun `setField decodes labels list value for labels field`() {
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 1L)
        every { issueApplicationService.updateIssue(actor, issueKey, any()) } returns issueResponse(version = 2L)

        val cmd =
            SetFieldCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                field = "labels",
                value = """["bug","urgent"]""",
                dryRun = false,
            )
        adapter.setField(cmd)

        verify {
            issueApplicationService.updateIssue(
                actor,
                issueKey,
                match { it.labels == listOf("bug", "urgent") },
            )
        }
    }

    @Test
    fun `setField throws for unsupported field without calling updateIssue`() {
        val cmd =
            SetFieldCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                field = "dueDate",
                value = "\"2026-01-01\"",
                dryRun = false,
            )

        assertThatThrownBy { adapter.setField(cmd) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("dueDate")

        // any() 는 IssueKey(value class) 시그니처 생성 시 랜덤 문자열로 REGEX init 검증을 깨뜨리므로
        // (MockK value class 주의 — 실인스턴스 사용) 실 actor/issueKey 인스턴스로 검증한다.
        verify(exactly = 0) { issueApplicationService.updateIssue(actor, issueKey, any()) }
        verify(exactly = 0) { issueApplicationService.findByKey(actor, issueKey) }
    }

    @Test
    fun `setField retries once on OCC conflict and succeeds on second attempt`() {
        every { issueApplicationService.findByKey(actor, issueKey) } returnsMany
            listOf(issueResponse(version = 3L), issueResponse(version = 5L))
        every { issueApplicationService.updateIssue(actor, issueKey, any()) } throws
            IssueVersionConflictException(issueKey, 5L) andThen
            issueResponse(version = 6L)

        val cmd =
            SetFieldCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                field = "priority",
                value = "1",
                dryRun = false,
            )
        val result = adapter.setField(cmd)

        assertThat(result.applied).isTrue()
        assertThat(result.version).isEqualTo(6L)
        verify(exactly = 2) { issueApplicationService.findByKey(actor, issueKey) }
        verify(exactly = 2) { issueApplicationService.updateIssue(actor, issueKey, any()) }
    }

    @Test
    fun `setField translates IssueAccessDeniedException into IssueMutationPermissionDeniedException`() {
        // FR-AT-02 C3 — 도메인 권한 예외를 포트 계약의 타입 있는 예외로 번역해, 소비자(automation
        // ActionExecutor)가 클래스명 문자열 매칭 없이 권한 거부를 분류할 수 있게 한다.
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 3L)
        every { issueApplicationService.updateIssue(actor, issueKey, any()) } throws
            IssueAccessDeniedException(actor = actor, permission = IssuePermission.UPDATE, scope = IssueScope.Global)

        val cmd =
            SetFieldCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                field = "priority",
                value = "1",
                dryRun = false,
            )

        assertThatThrownBy { adapter.setField(cmd) }
            .isInstanceOf(IssueMutationPermissionDeniedException::class.java)
    }

    @Test
    fun `setField propagates exception when OCC conflict persists after retry`() {
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 3L)
        every { issueApplicationService.updateIssue(actor, issueKey, any()) } throws
            IssueVersionConflictException(issueKey, 3L)

        val cmd =
            SetFieldCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                field = "priority",
                value = "1",
                dryRun = false,
            )

        assertThatThrownBy { adapter.setField(cmd) }.isInstanceOf(IssueVersionConflictException::class.java)
        verify(exactly = 2) { issueApplicationService.updateIssue(actor, issueKey, any()) }
    }

    @Test
    fun `setField dryRun sets rollback only and returns applied false version null`() {
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 3L)
        every { issueApplicationService.updateIssue(actor, issueKey, any()) } returns issueResponse(version = 4L)

        val cmd =
            SetFieldCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                field = "priority",
                value = "1",
                dryRun = true,
            )
        val result = adapter.setField(cmd)

        assertThat(result.applied).isFalse()
        assertThat(result.version).isNull()
        verify { status.setRollbackOnly() }
    }

    // ── assign ──────────────────────────────────────────────────────────────

    @Test
    fun `assign delegates to changeAssignee with current version`() {
        val newAssignee = UUID.randomUUID()
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 2L)
        every { issueApplicationService.changeAssignee(actor, issueKey, any()) } returns issueResponse(version = 3L)

        val cmd =
            AssignCommand(actorUserId = actorUuid, issueKey = issueKey.value, assigneeId = newAssignee, dryRun = false)
        val result = adapter.assign(cmd)

        assertThat(result.applied).isTrue()
        assertThat(result.version).isEqualTo(3L)
        verify {
            issueApplicationService.changeAssignee(
                actor,
                issueKey,
                AppChangeAssigneeRequest(assigneeId = newAssignee, expectedVersion = 2L),
            )
        }
    }

    @Test
    fun `assign supports unassign with null assigneeId`() {
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 1L)
        every { issueApplicationService.changeAssignee(actor, issueKey, any()) } returns issueResponse(version = 2L)

        val cmd = AssignCommand(actorUserId = actorUuid, issueKey = issueKey.value, assigneeId = null, dryRun = false)
        adapter.assign(cmd)

        verify {
            issueApplicationService.changeAssignee(
                actor,
                issueKey,
                AppChangeAssigneeRequest(assigneeId = null, expectedVersion = 1L),
            )
        }
    }

    @Test
    fun `assign dryRun sets rollback only and returns applied false version null`() {
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 1L)
        every { issueApplicationService.changeAssignee(actor, issueKey, any()) } returns issueResponse(version = 2L)

        val cmd =
            AssignCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                assigneeId = UUID.randomUUID(),
                dryRun = true,
            )
        val result = adapter.assign(cmd)

        assertThat(result.applied).isFalse()
        assertThat(result.version).isNull()
        verify { status.setRollbackOnly() }
    }

    // ── addComment ──────────────────────────────────────────────────────────

    /**
     * automation 경로가 **저작자 지정 없는 3인자 `create`** 를 경유함을 고정한다 (FR-CO-01 D4).
     *
     * ## 이 테스트가 증명하는 것과 증명하지 않는 것
     * 이 클래스는 [commentApplicationService] 를 MockK 로 대체하므로 **길이 상한 자체는 증명할 수 없다**
     * (mock 은 시키는 대로 반환한다). 상한이 automation 경로에도 걸린다는 명제는 두 조각의 사슬로 증명한다.
     *
     * 1. `CommentApplicationServiceTest.CO-2` — **실제 서비스**로 `create` 가 32,001자를 거부함
     * 2. 이 테스트 — 어댑터가 **그 `create`** 를 경유함 (다른 우회 경로를 쓰지 않음)
     *
     * 두 조각이 함께 있어야 명제가 성립한다. 어느 한쪽만으로는 vacuous 하다.
     */
    @Test
    fun `addComment delegates to 3-arg create without authorId (FR-CO-01 D4)`() {
        val bodySlot = slot<String>()
        every {
            commentApplicationService.create(
                actor = actor,
                issueKey = issueKey,
                body = capture(bodySlot),
            )
        } returns
            Comment(
                id = UUID.randomUUID(),
                issueId = UUID.randomUUID(),
                authorId = actorUuid,
                body = "자동화 댓글",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )

        val cmd = AddCommentCommand(actorUserId = actorUuid, issueKey = issueKey.value, body = "자동화 댓글", dryRun = false)
        val result = adapter.addComment(cmd)

        assertThat(result.issueKey).isEqualTo(issueKey.value)
        assertThat(result.applied).isTrue()
        assertThat(result.version).isNull()
        assertThat(bodySlot.captured).isEqualTo("자동화 댓글")

        // 저작자 지정 창구를 경유하지 않음을 명시 고정 — createImported 는 Import 전용이다
        verify(exactly = 0) {
            commentApplicationService.createImported(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `addComment dryRun sets rollback only and returns applied false`() {
        every {
            commentApplicationService.create(actor = actor, issueKey = issueKey, body = any())
        } returns
            Comment(
                id = UUID.randomUUID(),
                issueId = UUID.randomUUID(),
                authorId = actorUuid,
                body = "미리보기",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
            )

        val cmd = AddCommentCommand(actorUserId = actorUuid, issueKey = issueKey.value, body = "미리보기", dryRun = true)
        val result = adapter.addComment(cmd)

        assertThat(result.applied).isFalse()
        assertThat(result.version).isNull()
        verify { status.setRollbackOnly() }
    }

    // ── setFixVersions ──────────────────────────────────────────────────────

    @Test
    fun `setFixVersions delegates to changeFixVersions with refetched version as expectedVersion`() {
        val versionIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 3L)
        every { issueApplicationService.changeFixVersions(actor, issueKey, any()) } returns issueResponse(version = 4L)

        val cmd =
            SetFixVersionsCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                versionIds = versionIds,
                dryRun = false,
            )
        val result = adapter.setFixVersions(cmd)

        assertThat(result.issueKey).isEqualTo(issueKey.value)
        assertThat(result.applied).isTrue()
        assertThat(result.version).isEqualTo(4L)
        verify {
            issueApplicationService.changeFixVersions(
                actor,
                issueKey,
                AppChangeVersionsRequest(versionIds = versionIds, expectedVersion = 3L),
            )
        }
    }

    @Test
    fun `setFixVersions dryRun sets rollback only and returns applied false version null`() {
        val versionIds = listOf(UUID.randomUUID())
        every { issueApplicationService.findByKey(actor, issueKey) } returns issueResponse(version = 3L)
        every { issueApplicationService.changeFixVersions(actor, issueKey, any()) } returns issueResponse(version = 4L)

        val cmd =
            SetFixVersionsCommand(
                actorUserId = actorUuid,
                issueKey = issueKey.value,
                versionIds = versionIds,
                dryRun = true,
            )
        val result = adapter.setFixVersions(cmd)

        assertThat(result.applied).isFalse()
        assertThat(result.version).isNull()
        verify { status.setRollbackOnly() }
    }
}
