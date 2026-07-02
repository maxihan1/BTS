// 댓글 유스케이스 오케스트레이션 서비스 — create(UPDATE 게이트) + list(VIEW 게이트+렌더링) (FR-IM-01 PR3)

package com.bts.issue.comment.application

import com.bts.issue.comment.domain.Comment
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.markdown.MarkdownRenderer
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
 * @param commentRepository 댓글 저장소.
 * @param issueRepository 이슈 조회 저장소.
 * @param permissionResolver 이슈 권한 판정 포트.
 * @param clock 현재 시각 공급자 (테스트 제어 가능).
 */
@Service
@Transactional
class CommentApplicationService(
    private val commentRepository: CommentRepository,
    private val issueRepository: IssueRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 댓글을 생성한다.
     *
     * ## 실행 순서
     * 1. [IssuePermission.UPDATE] 검증 — 이슈 존재 probe 방지 (worklog 선례와 동일 순서).
     * 2. 이슈 resolve([IssueRepository.findByKey]) — 미존재·소프트삭제 시 [IssueNotFoundException].
     * 3. [CommentRepository.insert] — authorId 는 주입값 그대로 저장(actor 아님, 마이그레이션 작성자 보존 목적).
     *
     * @param actor 작업을 수행하는 행위자 (UPDATE 권한 보유 필요).
     * @param issueKey 댓글을 추가할 이슈 키.
     * @param body 댓글 본문 (raw markdown).
     * @param authorId 댓글 작성자로 기록할 사용자. actor 와 다를 수 있다(예. Import 시 원본 작성자 보존).
     * @return 저장된 [Comment].
     * @throws [IssueAccessDeniedException] UPDATE 권한 미보유 시 (403).
     * @throws [IssueNotFoundException] 이슈 미존재·소프트 삭제 시 (404).
     */
    fun create(
        actor: ActorId,
        issueKey: IssueKey,
        body: String,
        authorId: ActorId,
    ): Comment {
        val createScope = IssueScope.Issue(issueKey.value)
        if (!permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, createScope)) {
            throw IssueAccessDeniedException(actor, IssuePermission.UPDATE, createScope)
        }

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)

        val now = Instant.now(clock)
        val comment =
            Comment(
                id = UUID.randomUUID(),
                issueId = issue.id.value,
                authorId = authorId.value,
                body = body,
                createdAt = now,
                updatedAt = now,
            )
        commentRepository.insert(comment)

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
     * 1. [IssuePermission.VIEW] 검증, scope=[IssueScope.Issue] — 기밀 이슈(security level)의 댓글 누출을
     *    차단하기 위해 프로젝트 단위가 아닌 이슈 단위 scope 로 게이트한다(worklog `listForIssue` 선례).
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
        val listScope = IssueScope.Issue(issueKey.value)
        if (!permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, listScope)) {
            throw IssueAccessDeniedException(actor, IssuePermission.VIEW, listScope)
        }

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
}
