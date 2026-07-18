// 댓글 유스케이스 오케스트레이션 서비스 — create(UPDATE 게이트) + list(VIEW 게이트+렌더링) (FR-IM-01 PR3)

package com.bts.issue.comment.application

import com.bts.issue.comment.domain.Comment
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.event.IssueCommented
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.markdown.MarkdownRenderer
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 댓글 유스케이스 오케스트레이션 서비스 (FR-IM-01 PR3).
 *
 * ## 권한 scope 설계 — 왜 Project 가 아닌 Issue 인가
 * [create]/[list] 모두 권한 평가 scope 를 [IssueScope.Issue] 로 고정한다.
 * 이슈 보안 등급(security level, FR-PM-06)은 이슈 단위로 지정되므로, 프로젝트 단위([IssueScope.Project])
 * 로 게이트하면 기밀 이슈에 접근 불가한 사용자도 해당 이슈의 댓글을 열람/작성할 수 있게 되어
 * 보안 등급 우회(정보 누출)로 이어진다. worklog `listForIssue`/`create` 와 동일한 이유로 Issue scope 를 쓴다.
 *
 * @param commentRepository 댓글 저장소.
 * @param issueRepository 이슈 조회 저장소.
 * @param permissionResolver 이슈 권한 판정 포트.
 * @param eventPublisher 댓글 생성 시 [IssueCommented] 이벤트를 발행하는 아웃바운드 어댑터
 *   (FR-AT-01 Task 10 — automation COMMENTED 트리거 감지).
 * @param clock 현재 시각 공급자 (테스트 제어 가능).
 */
@Service
@Transactional
class CommentApplicationService(
    private val commentRepository: CommentRepository,
    private val issueRepository: IssueRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val eventPublisher: IssueEventPublisher,
    private val archiveGuard: ProjectArchiveGuard,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 댓글을 생성한다.
     *
     * ## 실행 순서
     * 1. [IssuePermission.UPDATE] 검증 — 이슈 존재 probe 방지 (worklog 선례와 동일 순서).
     *    UPDATE 를 쓰는 이유 — 댓글 작성은 이슈에 부수 정보를 더하는 수정 행위로, 이슈 자체를
     *    수정할 수 있는 권한(UPDATE)을 요구하는 것이 자연스럽다(worklog `create` 와 동일 근거).
     * 2. 이슈 resolve([IssueRepository.findByKey]) — 미존재·소프트삭제 시 [IssueNotFoundException].
     * 3. [CommentRepository.insert] — authorId 는 주입값 그대로 저장(actor 아님, 마이그레이션 작성자 보존 목적).
     * 4. [IssueEventPublisher.publish] 로 [IssueCommented] 발행 — 같은 트랜잭션 내
     *    ([Propagation.MANDATORY][org.springframework.transaction.annotation.Propagation.MANDATORY],
     *    FR-AT-01 Task 10 — automation COMMENTED 트리거 감지).
     *
     * @param actor 작업을 수행하는 행위자 (UPDATE 권한 보유 필요).
     * @param issueKey 댓글을 추가할 이슈 키.
     * @param body 댓글 본문 (raw markdown).
     * @param authorId 댓글 작성자로 기록할 사용자. actor 와 다를 수 있다(예. Import 시 원본 작성자 보존).
     * @param createdAt 댓글의 createdAt/updatedAt 에 사용할 시각. null 이면 [clock] 기준 현재 시각을
     *   사용한다(기존 동작). Import 시 원본(Jira 등) 작성 시각을 보존할 때만 값을 넘긴다
     *   ([com.bts.shared.issue.ImportComment.createdAt] — S1/R7/E5).
     * @return 저장된 [Comment].
     * @throws [IssueAccessDeniedException] UPDATE 권한 미보유 시 (403).
     * @throws [IssueNotFoundException] 이슈 미존재·소프트 삭제 시 (404).
     */
    fun create(
        actor: ActorId,
        issueKey: IssueKey,
        body: String,
        authorId: ActorId,
        createdAt: Instant? = null,
    ): Comment {
        checkPermission(actor, issueKey, IssuePermission.UPDATE)
        // TASK9-RED-PENDING archiveGuard.checkByIssue(issueKey)

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)

        val effectiveCreatedAt = createdAt ?: Instant.now(clock)
        val comment =
            Comment(
                id = UUID.randomUUID(),
                issueId = issue.id.value,
                authorId = authorId.value,
                body = body,
                createdAt = effectiveCreatedAt,
                updatedAt = effectiveCreatedAt,
            )
        commentRepository.insert(comment)

        eventPublisher.publish(
            IssueCommented(
                issueKey = issueKey,
                projectKey = issueKey.projectPrefix,
                commentId = comment.id,
                actorId = actor,
                occurredAt = effectiveCreatedAt,
            ),
        )

        log.info(
            "comment_created issueKey={} commentId={} actor={} authorId={}",
            issueKey.value,
            comment.id,
            actor.value,
            authorId.value,
        )
        return comment
    }

    /**
     * 이슈의 댓글 목록을 조회한다.
     *
     * ## 실행 순서
     * 1. [IssuePermission.VIEW] 검증, scope=[IssueScope.Issue] — 클래스 KDoc "권한 scope 설계" 참조.
     * 2. 이슈 resolve — 미존재 404.
     * 3. [CommentRepository.listByIssue] — created_at ASC 정렬.
     * 4. 각 댓글 body 를 [MarkdownRenderer.renderSafe] 로 렌더링해 bodyHtml 채운 [CommentView] 로 변환.
     *
     * @param actor 조회를 수행하는 행위자.
     * @param issueKey 조회할 이슈 키.
     * @return [CommentView] 목록 (`created_at` 오름차순).
     * @throws [IssueAccessDeniedException] VIEW 권한 미보유 시 (403).
     * @throws [IssueNotFoundException] 이슈 미존재·소프트 삭제 시 (404).
     */
    @Transactional(readOnly = true)
    fun list(
        actor: ActorId,
        issueKey: IssueKey,
    ): List<CommentView> {
        checkPermission(actor, issueKey, IssuePermission.VIEW)

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)
        val comments = commentRepository.listByIssue(issue.id.value)

        log.debug("comment_list issueKey={} actor={} count={}", issueKey.value, actor.value, comments.size)

        return comments.map { comment ->
            CommentView(
                id = comment.id,
                authorId = comment.authorId,
                body = comment.body,
                bodyHtml = MarkdownRenderer.renderSafe(comment.body),
                createdAt = comment.createdAt,
                updatedAt = comment.updatedAt,
            )
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * 권한을 검증한다. scope 는 항상 [IssueScope.Issue] 로 고정한다 (클래스 KDoc 참조).
     *
     * 이슈 존재 여부 probe 방지를 위해 이슈 조회 전 호출한다.
     * [com.bts.issue.worklog.application.WorklogService.checkPermission] 과 동일 패턴.
     *
     * @param actor 행위자.
     * @param issueKey 이슈 키 (scope 생성에 사용).
     * @param permission 요구하는 권한.
     * @throws [IssueAccessDeniedException] 권한 미보유 시.
     */
    private fun checkPermission(
        actor: ActorId,
        issueKey: IssueKey,
        permission: IssuePermission,
    ) {
        val scope = IssueScope.Issue(issueKey.value)
        val allowed = permissionResolver.hasPermission(actor.value, permission, scope)
        if (!allowed) {
            throw IssueAccessDeniedException(actor, permission, scope)
        }
    }
}
