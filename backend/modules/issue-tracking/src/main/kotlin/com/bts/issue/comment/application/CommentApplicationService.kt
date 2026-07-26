// 댓글 유스케이스 오케스트레이션 서비스 — create(UPDATE 게이트) + list(VIEW 게이트+렌더링) (FR-IM-01 PR3)

package com.bts.issue.comment.application

import com.bts.issue.comment.domain.Comment
import com.bts.issue.comment.domain.CommentBodyBlankException
import com.bts.issue.comment.domain.CommentBodyTooLongException
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
     * 사람이 작성한 댓글을 생성한다 (REST · automation 공용).
     *
     * ## 저작자는 항상 actor 다 (FR-CO-01 D4)
     * 이 메서드에는 **`authorId` 파라미터가 없다.** 저작자를 호출자가 지정할 수 있으면 요청자가
     * 남의 이름으로 댓글을 쓸 수 있고, 그 안전성은 컨트롤러의 성실성에만 의존하게 된다.
     * 창구를 아예 없애 **컴파일 수준에서** 위조를 막는다.
     * 원본 저작자 보존이 필요한 Import 는 [createImported] 를 쓴다
     * ([com.bts.issue.worklog.application.WorklogService.create]/`createImported` 선례와 동일 구조).
     *
     * ## 본문 검증을 왜 여기서 하는가 (FR-CO-01 D7)
     * 이 서비스의 호출자는 **둘**이다 — REST 컨트롤러와 automation `AddCommentAction`
     * ([com.bts.issue.adapter.outbound.automation.AutomationIssueMutationAdapter.addComment]).
     * 컨트롤러에서만 검증하면 automation 이 상한을 우회한다(automation 본문은 템플릿 렌더 결과이고
     * 렌더러에는 길이 제한이 없다). 목록 조회는 페이지네이션 없이 전량을 HTML 렌더하므로
     * 대용량 1건이 이슈 화면을 마보한다. 따라서 두 생산자를 한 지점에서 구속한다.
     *
     * ## 실행 순서
     * 1. 본문 검증 — 공백/길이. 권한보다 먼저 하지 않는다(이슈 존재 probe 방지가 우선).
     * 2. [IssuePermission.UPDATE] 검증 — 이슈 존재 probe 방지 (worklog 선례와 동일 순서).
     *    UPDATE 를 쓰는 이유 — 댓글 작성은 이슈에 부수 정보를 더하는 수정 행위로, 이슈 자체를
     *    수정할 수 있는 권한(UPDATE)을 요구하는 것이 자연스럽다(worklog `create` 와 동일 근거).
     * 3. 이슈 resolve([IssueRepository.findByKey]) — 미존재·소프트삭제 시 [IssueNotFoundException].
     * 4. [CommentRepository.insert] — authorId = actor.
     * 5. [IssueEventPublisher.publish] 로 [IssueCommented] 발행 — 같은 트랜잭션 내
     *    ([Propagation.MANDATORY][org.springframework.transaction.annotation.Propagation.MANDATORY],
     *    FR-AT-01 Task 10 — automation COMMENTED 트리거 감지).
     *
     * @param actor 작업을 수행하는 행위자 (UPDATE 권한 보유 필요). **저작자로도 기록된다.**
     * @param issueKey 댓글을 추가할 이슈 키.
     * @param body 댓글 본문 (raw markdown).
     * @return 저장된 [Comment].
     * @throws [com.bts.issue.comment.domain.CommentBodyBlankException] 본문이 공백만일 때 (400).
     * @throws [com.bts.issue.comment.domain.CommentBodyTooLongException] 본문이 [MAX_BODY_LENGTH] 초과일 때 (400).
     * @throws [IssueAccessDeniedException] UPDATE 권한 미보유 시 (403).
     * @throws [IssueNotFoundException] 이슈 미존재·소프트 삭제 시 (404).
     */
    fun create(
        actor: ActorId,
        issueKey: IssueKey,
        body: String,
    ): Comment {
        validateBody(body)
        return insertComment(actor = actor, issueKey = issueKey, body = body, authorId = actor, createdAt = null)
    }

    /**
     * Import 로 가져온 댓글을 생성한다 — **원본 저작자·작성시각을 보존한다.**
     *
     * [create] 와 달리 `authorId`·`createdAt` 을 주입받고 **본문 길이 상한을 적용하지 않는다.**
     * 외부 시스템(Jira 등)의 원본 데이터를 있는 그대로 옮기는 것이 이 경로의 계약이기 때문이다.
     * 길이 면제는 ADR `2026-07-27-fr-co-comment-feature` §잔여위험에 등재돼 있다 — 조용한 면제가 아니다.
     *
     * 이 메서드는 [com.bts.issue.adapter.outbound.imports.IssueImportAdapter] 전용이다.
     * REST 경로로는 도달할 수 없어야 한다(스펙 S10).
     *
     * @param actor Import 를 요청한 행위자 (UPDATE 권한 판정 대상).
     * @param issueKey 댓글을 추가할 이슈 키.
     * @param body 댓글 본문 (raw markdown). 길이 검증 없음.
     * @param authorId 원본 작성자로 기록할 사용자. [actor] 와 다를 수 있다.
     * @param createdAt 원본 작성 시각. null 이면 [clock] 기준 현재 시각.
     * @return 저장된 [Comment].
     * @throws [IssueAccessDeniedException] UPDATE 권한 미보유 시 (403).
     * @throws [IssueNotFoundException] 이슈 미존재·소프트 삭제 시 (404).
     */
    fun createImported(
        actor: ActorId,
        issueKey: IssueKey,
        body: String,
        authorId: ActorId,
        createdAt: Instant? = null,
    ): Comment =
        insertComment(
            actor = actor,
            issueKey = issueKey,
            body = body,
            authorId = authorId,
            createdAt = createdAt,
        )

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
     * 사람이 입력한 본문을 검증한다 ([create] 전용 — [createImported] 는 면제).
     *
     * @param body 검증할 본문.
     * @throws CommentBodyBlankException 공백·개행만일 때.
     * @throws CommentBodyTooLongException [MAX_BODY_LENGTH] 초과일 때.
     */
    private fun validateBody(body: String) {
        if (body.isBlank()) throw CommentBodyBlankException()
        if (body.length > MAX_BODY_LENGTH) {
            throw CommentBodyTooLongException(actual = body.length, max = MAX_BODY_LENGTH)
        }
    }

    /**
     * 권한 → 아카이브 가드 → 이슈 resolve → insert → 이벤트 발행 공통 경로
     * ([create]/[createImported] 공유).
     *
     * 검증 순서는 이슈 존재 probe 방지를 위해 권한을 조회보다 먼저 둔다 (worklog 선례).
     *
     * @param actor 행위자 (권한 판정 대상, 이벤트의 actorId).
     * @param issueKey 대상 이슈 키.
     * @param body 댓글 본문.
     * @param authorId 저작자로 저장할 사용자 ([create]=actor, [createImported]=주입값).
     * @param createdAt createdAt/updatedAt 에 쓸 시각. null 이면 [clock] 기준 현재.
     * @return 저장된 [Comment].
     */
    private fun insertComment(
        actor: ActorId,
        issueKey: IssueKey,
        body: String,
        authorId: ActorId,
        createdAt: Instant?,
    ): Comment {
        checkPermission(actor, issueKey, IssuePermission.UPDATE)
        archiveGuard.checkByIssue(issueKey)

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

    companion object {
        /**
         * 사람이 작성하는 댓글 본문의 최대 **문자 수** (바이트 아님 — 한글은 UTF-8 3바이트라 약 96KB).
         *
         * Jira Cloud 의 댓글 상한(32,767자)과 같은 급으로 잡았다. 상한을 두는 이유는 목록 조회가
         * 페이지네이션 없이 전량을 `renderSafe` HTML 로 렌더하기 때문이다 — 대용량 1건이 그 이슈
         * 화면을 사용 불가로 만든다. [createImported] 는 원본 보존을 위해 이 상한을 적용하지 않는다.
         */
        const val MAX_BODY_LENGTH: Int = 32_000
    }
}
