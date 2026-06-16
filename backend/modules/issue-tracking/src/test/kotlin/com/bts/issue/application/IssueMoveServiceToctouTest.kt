// TOCTOU 재조회 단위 테스트 — 락 획득 후 findDirectChildren 재조회 강화 검증 (FR-MV-01)

package com.bts.issue.application

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IncompleteSubtaskMappingException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueKeyRedirectRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * TOCTOU 재조회 단위 테스트 — IssueMoveService.moveWithSubtasks.
 *
 * 동시성 자체를 재현하지 않고, "락 획득 후 findDirectChildren 재조회가 실행되며
 * 재조회 결과를 IncompleteSubtaskMapping 검증에 사용한다"는 계약을 MockK로 단언한다.
 *
 * ## 검증 시나리오
 * - ST6: 락 전 자식 1개, 락 후 재조회 자식 2개 → 요청에 1개만 제공 →
 *         재조회 결과로 불완전 감지 → IncompleteSubtaskMappingException
 * - ST7: findDirectChildren 이 락 후 두 번째로 호출되는지(재조회) verify
 */
class IssueMoveServiceToctouTest {
    companion object {
        private const val SRC_PROJECT = "TSRC"
        private const val DST_PROJECT = "TDST"
        private const val STATE_OPEN = "open"
    }

    private val issueRepository = mockk<IssueRepository>()
    private val redirectRepository = mockk<IssueKeyRedirectRepository>(relaxed = true)
    private val permissionResolver = mockk<IssuePermissionResolver>(relaxed = true)
    private val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    private val workflowStateCatalog = mockk<WorkflowStateCatalog>()
    private val historyRecorder = mockk<IssueHistoryRecorder>(relaxed = true)
    private val componentRepository = mockk<ComponentRepository>()
    private val versionRepository = mockk<VersionRepository>()
    private val customFieldDefinitionRepository = mockk<CustomFieldDefinitionRepository>()

    private val actor = ActorId(UUID.fromString("11111111-1111-4111-8111-111111111111"))

    private lateinit var sut: IssueMoveService

    // 루트 이슈 고정 UUID
    private val rootUuid = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val child1Uuid = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
    private val child2Uuid = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")

    private val dstProjectUuid = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
    private val srcProjectUuid = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")

    private val rootKey = IssueKey.of(SRC_PROJECT, 1)
    private val child1Key = IssueKey.of(SRC_PROJECT, 2)
    private val child2Key = IssueKey.of(SRC_PROJECT, 3)

    @BeforeEach
    fun setUp() {
        sut =
            IssueMoveService(
                issueRepository = issueRepository,
                redirectRepository = redirectRepository,
                permissionResolver = permissionResolver,
                workflowKeyResolver = workflowKeyResolver,
                workflowStateCatalog = workflowStateCatalog,
                historyRecorder = historyRecorder,
                componentRepository = componentRepository,
                versionRepository = versionRepository,
                customFieldDefinitionRepository = customFieldDefinitionRepository,
            )

        // 권한 허용
        every { permissionResolver.hasPermission(any(), IssuePermission.UPDATE, any<IssueScope>()) } returns true
        every { permissionResolver.hasPermission(any(), IssuePermission.CREATE, any<IssueScope>()) } returns true

        // 워크플로우 설정
        every {
            workflowKeyResolver.resolveExisting(ProjectKey.of(DST_PROJECT), null)
        } returns WorkflowStartState(workflowKey = "wf", startStateKey = STATE_OPEN)
        every {
            workflowStateCatalog.listStates(ProjectKey.of(DST_PROJECT), null)
        } returns listOf(WorkflowStateView(key = STATE_OPEN, name = "Open"))

        // 대상 프로젝트 ID
        every { issueRepository.findProjectIdByKey(DST_PROJECT) } returns dstProjectUuid

        // 컴포넌트/버전/커스텀필드 없음
        every { componentRepository.findByProject(dstProjectUuid) } returns emptyList()
        every { versionRepository.findByProject(dstProjectUuid) } returns emptyList()
        every { customFieldDefinitionRepository.findActiveByProject(dstProjectUuid) } returns emptyList()

        // 키 발번 stub
        every { issueRepository.incrementKeySequence(DST_PROJECT) } returnsMany listOf(1L, 2L, 3L, 4L, 5L)
    }

    /**
     * ST6 — 락 후 재조회에서 신규 자식 발견 시 IncompleteSubtaskMappingException.
     *
     * 락 전 findDirectChildren(rootUuid) → [child1] 만 반환 (요청도 child1만 제공).
     * 락 후 findDirectChildren(rootUuid) 재조회 → [child1, child2] 반환 (동시 추가 시뮬레이션).
     * 요청에 child2가 없으므로 IncompleteSubtaskMappingException.
     *
     * 현재 코드(재조회 없음)는 1차 조회 결과만 쓰므로 이 테스트가 RED.
     */
    @Test
    fun `ST6 락 후 재조회에서 신규 자식 발견 시 IncompleteSubtaskMappingException`() {
        val rootIssue = makeIssue(rootUuid, rootKey, srcProjectUuid, version = 1L, parentId = null)
        val child1Issue = makeIssue(child1Uuid, child1Key, srcProjectUuid, version = 1L, parentId = rootUuid)
        val child2Issue = makeIssue(child2Uuid, child2Key, srcProjectUuid, version = 1L, parentId = rootUuid)

        // 1차 조회 (락 없이 id 파악): child1만 반환
        every { issueRepository.findByKey(rootKey) } returns rootIssue

        // findDirectChildren: 1차 = [child1], 2차(락 후 재조회) = [child1, child2]
        every {
            issueRepository.findDirectChildren(rootUuid)
        } returnsMany listOf(
            listOf(child1Issue),        // 1차: 락 전 id 파악용
            listOf(child1Issue, child2Issue), // 2차: 락 후 재조회 — 동시 추가된 child2 발견
        )

        // 락 획득 (id 오름차순: rootUuid < child1Uuid)
        every { issueRepository.findByKeyForUpdate(rootKey) } returns rootIssue
        every { issueRepository.findByKeyForUpdate(child1Key) } returns child1Issue

        // child1에 자식 없음
        every { issueRepository.countDirectChildren(child1Uuid) } returns 0

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
                subtasks =
                    listOf(
                        SubtaskMoveSpec(
                            // child1만 제공, child2 누락
                            issueKey = child1Key.value,
                            expectedVersion = 1L,
                            targetStateKey = null,
                            targetStateIsDone = false,
                            componentMapping = emptyMap(),
                            affectsVersionMapping = emptyMap(),
                            fixVersionMapping = emptyMap(),
                            additionalCustomFields = emptyMap(),
                        ),
                    ),
            )

        assertThrows<IncompleteSubtaskMappingException> {
            sut.move(actor, rootKey, request)
        }
    }

    /**
     * ST7 — findDirectChildren 이 락 후 두 번째로 호출됨을 verify.
     *
     * ST6 가 예외를 단언한다면, ST7 은 재조회 호출 자체가 코드에 존재함을 명시적으로 단언한다.
     * 두 번 호출되지 않으면(재조회 없으면) verify(exactly = 2) 가 실패 → RED.
     */
    @Test
    fun `ST7 findDirectChildren 이 락 후 두 번 호출됨(재조회 verify)`() {
        val rootIssue = makeIssue(rootUuid, rootKey, srcProjectUuid, version = 1L, parentId = null)
        val child1Issue = makeIssue(child1Uuid, child1Key, srcProjectUuid, version = 1L, parentId = rootUuid)

        every { issueRepository.findByKey(rootKey) } returns rootIssue

        // 두 번 모두 같은 결과 반환 (정상 happy-path — 재조회 결과 일치 시 예외 없음)
        every { issueRepository.findDirectChildren(rootUuid) } returns listOf(child1Issue)
        every { issueRepository.findByKeyForUpdate(rootKey) } returns rootIssue
        every { issueRepository.findByKeyForUpdate(child1Key) } returns child1Issue
        every { issueRepository.countDirectChildren(child1Uuid) } returns 0
        every { issueRepository.countDirectChildren(rootUuid) } returns 1

        // 이동 실행 — 이동 관련 stub
        every {
            issueRepository.moveIssue(
                oldKey = any(),
                newKey = any(),
                targetProjectId = any(),
                targetStateKey = any(),
                resolvedResolutionId = any(),
                filteredCustomFields = any(),
                expectedVersion = any(),
                newParentId = any(),
            )
        } returns 1
        every { issueRepository.deleteComponentsByIssueId(any()) } returns Unit
        every { issueRepository.deleteVersionsByIssueId(any(), any()) } returns Unit

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
                subtasks =
                    listOf(
                        SubtaskMoveSpec(
                            issueKey = child1Key.value,
                            expectedVersion = 1L,
                            targetStateKey = null,
                            targetStateIsDone = false,
                            componentMapping = emptyMap(),
                            affectsVersionMapping = emptyMap(),
                            fixVersionMapping = emptyMap(),
                            additionalCustomFields = emptyMap(),
                        ),
                    ),
            )

        sut.move(actor, rootKey, request)

        // 락 전 1차 + 락 후 재조회 2차 = exactly 2회
        verify(exactly = 2) { issueRepository.findDirectChildren(rootUuid) }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 테스트용 Issue 팩토리. 필수 필드만 설정하고 나머지는 기본값 사용.
     */
    private fun makeIssue(
        id: UUID,
        key: IssueKey,
        projectId: UUID,
        version: Long,
        parentId: UUID?,
    ): com.bts.issue.domain.Issue =
        com.bts.issue.domain.Issue(
            id = IssueId(id),
            key = key,
            projectId = projectId,
            summary = "Test Issue",
            reporterId = actor,
            currentStateKey = STATE_OPEN,
            version = version,
            deletedAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            typeId = IssueTypeId(1L),
            parentId = parentId,
        )
}
