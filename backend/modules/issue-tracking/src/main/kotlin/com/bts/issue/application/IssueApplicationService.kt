// IssueApplicationService — 이슈 CRUD + 전이 유스케이스 조율. 모든 public 메서드 @Transactional 명시

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueCreated
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueRepository
import com.bts.workflow.port.inbound.WorkflowTransitionPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 이슈 CRUD + 전이 유스케이스를 조율하는 Application Service.
 *
 * - 권한 검증: [IssuePermissionResolver] 를 통해 각 메서드 진입 직후 체크
 * - 이슈 키 발급: [IssueRepository.incrementKeySequence] (pg_advisory_xact_lock 포함)
 * - 이벤트 발행: [IssueEventPublisher] (Propagation.MANDATORY — 같은 트랜잭션)
 * - 워크플로우 전이: [WorkflowTransitionPort] (inbound port — BC 격리 준수)
 *
 * 모든 public 메서드는 @Transactional 을 명시한다 (DEVELOPMENT.md §절대규칙).
 */
@Service
@Transactional
class IssueApplicationService(
    private val repo: IssueRepository,
    private val eventPublisher: IssueEventPublisher,
    private val permissionResolver: IssuePermissionResolver,
    private val workflowPort: WorkflowTransitionPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 이슈를 생성한다.
     *
     * 흐름.
     * 1. CREATE 권한 검증 (Project 범위)
     * 2. pg_advisory_xact_lock 으로 보호된 key_sequence 증가
     * 3. IssueKey 발급 → Issue.create
     * 4. DB INSERT
     * 5. IssueCreated 이벤트 발행
     *
     * @param actor 이슈를 생성하는 행위자.
     * @param request 생성 요청 DTO.
     * @return 삽입된 [Issue].
     * @throws IssueAccessDeniedException 권한 없을 때.
     */
    fun createIssue(
        actor: ActorId,
        request: CreateIssueRequest,
    ): Issue {
        assertPermission(actor, IssuePermission.CREATE, IssueScope.Project(request.projectKey))

        val seq = repo.incrementKeySequence(request.projectKey)
        val key = IssueKey.of(request.projectKey, seq)
        val issue = Issue.create(
            id = IssueId(UUID.randomUUID()),
            key = key,
            projectId = UUID.randomUUID(), // Wave 5 Controller 에서 project lookup 으로 대체
            summary = request.summary,
            reporterId = request.reporterId,
            currentStateKey = "OPEN",
        )
        val saved = repo.insert(issue)
        eventPublisher.publish(
            IssueCreated(
                issueKey = saved.key,
                projectKey = request.projectKey,
                summary = saved.summary,
                reporterId = saved.reporterId,
                occurredAt = Instant.now(clock),
            ),
        )
        log.info("issue_created key={} actor={}", saved.key.value, actor.value)
        return saved
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun assertPermission(
        actor: ActorId,
        permission: IssuePermission,
        scope: IssueScope,
    ) {
        if (!permissionResolver.hasPermission(actor, permission, scope)) {
            throw IssueAccessDeniedException(actor, permission, scope)
        }
    }
}
