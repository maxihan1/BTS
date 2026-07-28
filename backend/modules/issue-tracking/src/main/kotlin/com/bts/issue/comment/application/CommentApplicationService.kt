// 댓글 유스케이스 오케스트레이션 서비스 — create(UPDATE 게이트) + update(작성자 한정) + list(VIEW 게이트+렌더링)
// + delete(작성자 OR SOFT_DELETE 모더레이터, FR-CO-02)

package com.bts.issue.comment.application

import com.bts.issue.comment.domain.Comment
import com.bts.issue.comment.domain.CommentBodyBlankException
import com.bts.issue.comment.domain.CommentBodyTooLongException
import com.bts.issue.comment.domain.CommentNotFoundException
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.event.IssueCommentDeleted
import com.bts.issue.event.IssueCommented
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.history.IssueHistoryRecorder
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
 * [create]·[update]·[list] 모두 권한 평가 scope 를 [IssueScope.Issue] 로 고정한다.
 * 이슈 보안 등급(security level, FR-PM-06)은 이슈 단위로 지정되므로, 프로젝트 단위([IssueScope.Project])
 * 로 게이트하면 기밀 이슈에 접근 불가한 사용자도 해당 이슈의 댓글을 열람/작성할 수 있게 되어
 * 보안 등급 우회(정보 누출)로 이어진다. worklog `listForIssue`/`create` 와 동일한 이유로 Issue scope 를 쓴다.
 *
 * @param commentRepository 댓글 저장소.
 * @param issueRepository 이슈 조회 저장소.
 * @param permissionResolver 이슈 권한 판정 포트.
 * @param eventPublisher 댓글 생성 시 [IssueCommented] 이벤트를 발행하는 아웃바운드 어댑터
 *   (FR-AT-01 Task 10 — automation COMMENTED 트리거 감지).
 * @param archiveGuard 아카이브된 프로젝트의 쓰기를 잠그는 가드 (FR-PJ-04).
 * @param historyRecorder 댓글 본문 수정 이력 기록 facade (FR-CO-02 — [update] 전용).
 * @param clock 현재 시각 공급자 (테스트 제어 가능).
 */
@Service
@Transactional
@Suppress("LongParameterList") // 협력자 6 + clock. IssueAttachmentService 와 동일 사유(모듈 선례)
class CommentApplicationService(
    private val commentRepository: CommentRepository,
    private val issueRepository: IssueRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val eventPublisher: IssueEventPublisher,
    private val archiveGuard: ProjectArchiveGuard,
    private val historyRecorder: IssueHistoryRecorder,
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
     * 댓글 본문을 수정한다 — **작성자 본인만 가능하다.**
     *
     * ## ★ 수정 게이트를 삭제 게이트와 공용 헬퍼로 합치지 말 것
     * 합치는 순간 모더레이터가 수정까지 통과한다. 삭제는 `작성자 OR SOFT_DELETE 보유자`,
     * 수정은 `작성자` 뿐이다 — 술어가 다르므로 술어를 공유해서는 안 된다. 두 게이트가 "같아 보인다"는
     * 이유로 하나로 묶으면, 넓은 쪽(삭제)의 술어가 좁은 쪽(수정)에 조용히 이식된다.
     *
     * 근거. 모더레이션의 실제 필요는 **지우기**이고, "남의 글 고치기"는 그 필요를 채우지 못하면서
     * 기록의 신뢰만 깎는다. 관리자가 타인 명의 글의 내용을 바꿀 수 있으면 그 글이 원래 무엇이었는지
     * 아무도 알 수 없기 때문이다. Jira 도 `Edit All Comments` 와 `Delete All Comments` 를 별도
     * 권한으로 나눈다. `CommentApplicationServiceTest` CO2-2 가 이 경계의 판별자이며,
     * 작성자 대조를 지우면 **정확히 그 한 건**이 죽는다(뮤테이션 실증).
     *
     * ## 본문이 같으면 완전 no-op 인 이유
     * 내용이 안 바뀌었는데 `updatedAt` 이 갱신되면 화면의 "(수정됨)" 표시가 거짓말을 한다.
     * 그래서 저장(`UPDATE` 문)도 이력 기록도 **둘 다** 건너뛴다. 이력만 거르고 `updatedAt` 은
     * 갱신하는 절충은 같은 거짓말을 남긴다 — 프론트가 `updatedAt != createdAt` 으로 표시를 결정하기 때문이다.
     *
     * ## 검증 순서를 [create] 와 같게 두는 이유
     * 권한 검증을 이슈·댓글 조회보다 **먼저** 둔다. 순서가 뒤집히면 권한 없는 사용자가 404 와 403 의
     * 차이만으로 "그 이슈·그 댓글이 존재하는가"를 알아낼 수 있다(존재 probe). 같은 서비스 안에서
     * 두 메서드의 순서가 달라지면 어느 쪽이 정답인지 알 수 없게 되므로 순서 자체를 관례로 고정한다.
     *
     * ## 실행 순서 ([create] 와 동일)
     * 1. 본문 검증 — 공백/길이.
     * 2. [IssuePermission.UPDATE] 검증, scope=[IssueScope.Issue].
     * 3. 아카이브 가드.
     * 4. 이슈 resolve → 댓글 resolve([CommentRepository.findActive] — `issue_id` 대조 포함).
     * 5. 작성자 대조 — actor 가 저작자가 아니면 403.
     * 6. 본문 동일하면 no-op 반환, 다르면 [CommentRepository.updateBody] + 이력 기록.
     *
     * @param actor 수정을 수행하는 행위자. **저작자 본인이어야 한다.**
     * @param issueKey 댓글이 속한 이슈 키.
     * @param commentId 수정할 댓글 UUID.
     * @param body 새 본문 (raw markdown).
     * @return 수정된 [Comment]. 본문이 동일하면 기존 [Comment] 를 그대로 반환한다.
     * @throws [CommentBodyBlankException] 본문이 공백만일 때 (400).
     * @throws [CommentBodyTooLongException] 본문이 [MAX_BODY_LENGTH] 초과일 때 (400).
     * @throws [IssueAccessDeniedException] UPDATE 권한 미보유 또는 타인 댓글 수정 시 (403).
     * @throws [IssueNotFoundException] 이슈 미존재·소프트 삭제 시 (404).
     * @throws [CommentNotFoundException] 댓글 미존재·이미 삭제됨·다른 이슈 소속일 때 (404).
     */
    @Suppress("ThrowsCount") // 400/403/404 각기 다른 오류코드라 분리 throw 가 명확 (WorklogService.update 선례)
    fun update(
        actor: ActorId,
        issueKey: IssueKey,
        commentId: UUID,
        body: String,
    ): Comment {
        validateBody(body)
        checkPermission(actor, issueKey, IssuePermission.UPDATE)
        archiveGuard.checkByIssue(issueKey)

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)
        val existing =
            commentRepository.findActive(commentId, issue.id.value)
                ?: throw CommentNotFoundException(commentId)

        // 수정은 작성자 한정. 모더레이터 우회 없음 — 삭제 게이트와 술어가 다르므로 헬퍼 공유 금지 (KDoc 참조).
        if (existing.authorId != actor.value) {
            throw IssueAccessDeniedException(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
        }

        if (existing.body == body) {
            log.debug("comment_update_noop issueKey={} commentId={}", issueKey.value, commentId)
            return existing
        }

        val now = Instant.now(clock)
        // 동시 삭제 레이스 방어 — findActive 통과 후 다른 트랜잭션이 삭제를 커밋한 경우 0 행이 돌아온다.
        if (commentRepository.updateBody(commentId, issue.id.value, body, now) == 0) {
            throw CommentNotFoundException(commentId)
        }

        historyRecorder.recordCommentEdited(
            issueId = issue.id.value,
            issueKey = issueKey.value,
            actor = actor,
            commentId = commentId,
            beforeBody = existing.body,
            afterBody = body,
        )

        log.info("comment_updated issueKey={} commentId={} actor={}", issueKey.value, commentId, actor.value)
        return existing.copy(body = body, updatedAt = now)
    }

    /**
     * 댓글을 소프트 삭제한다 — **작성자 본인 또는 [IssuePermission.SOFT_DELETE] 보유자(모더레이터).**
     *
     * 권한 평가 scope 는 [IssueScope.Issue] 고정이다 (클래스 KDoc "권한 scope 설계" 참조).
     *
     * ## ★ 모더레이터 분기는 질의형 `hasPermission` 이어야 한다 — `checkPermission` 금지
     * [checkPermission] 은 **거부 시 던진다.** 이 `OR` 분기에 그걸 쓰면
     * `SOFT_DELETE` 미보유자는 **작성자여도 그 지점에서 즉시 403** 이 되어, `OR` 의 왼쪽 변
     * (작성자 경로)이 실행되기도 전에 조용히 죽는다. `OR` 는 두 변을 **모두 계산할 수 있어야**
     * 성립하므로, 판정은 던지지 않는 질의형([IssuePermissionResolver.hasPermission])으로 하고
     * **최종 거부만 한 번** 던진다.
     *
     * 이 함정이 실재함은 뮤테이션으로 실증했다 — 이 줄을
     * `checkPermission(actor, issueKey, SOFT_DELETE)` 로 바꾸면
     * `CommentApplicationServiceTest` CO2-9(“작성자는 `SOFT_DELETE` 가 없어도 자기 댓글을 삭제한다”)가
     * **정확히 한 건** 실패한다. 그래서 CO2-9 의 actor 는 `SOFT_DELETE` 를 일부러 갖지 않는다.
     *
     * ## ★ 삭제 게이트를 [update] 의 수정 게이트와 합치지 말 것
     * 술어가 다르다. 삭제는 `UPDATE AND (작성자 OR SOFT_DELETE)`, 수정은 `UPDATE AND 작성자` 다.
     * 공용 헬퍼로 묶으면 **넓은 쪽(삭제)의 술어가 좁은 쪽(수정)에 이식되어 모더레이터가 남의 글을
     * 수정**하게 된다. 모더레이션의 실제 필요는 지우기이고, 남의 글 고치기는 그 필요를 못 채우면서
     * 기록의 신뢰만 깎는다 ([update] KDoc 참조 — Jira 도 두 권한을 분리한다).
     *
     * ## `moderated` 로그 필드
     * 모더레이터가 **타인의 댓글**을 지운 경우에만 `true` 다(`= !isAuthor`). 자기 글 삭제와
     * 모더레이션 삭제는 감사상 무게가 전혀 다른데 요청 형태로는 구분되지 않으므로, 운영에서
     * "누가 남의 글을 지웠나"를 로그만으로 골라낼 수 있게 이 판정 결과를 그대로 남긴다.
     *
     * ## 실행 순서
     * 1. [IssuePermission.UPDATE] 검증 — 공통 전제. 작성자·모더레이터 판정보다 먼저다(존재 probe 방지).
     * 2. 아카이브 가드.
     * 3. 이슈 resolve → 댓글 resolve([CommentRepository.findActive] — `issue_id` 대조 포함).
     * 4. 작성자 **또는** 모더레이터 판정 — 둘 다 아니면 403.
     * 5. [CommentRepository.softDelete] — 영향 행 0 이면 404 (동시 삭제 레이스).
     *
     * @param actor 삭제를 수행하는 행위자.
     * @param issueKey 댓글이 속한 이슈 키.
     * @param commentId 삭제할 댓글 UUID.
     * @throws [IssueAccessDeniedException] UPDATE 미보유, 또는 작성자도 모더레이터도 아닐 때 (403).
     * @throws [IssueNotFoundException] 이슈 미존재·소프트 삭제 시 (404).
     * @throws [CommentNotFoundException] 댓글 미존재·이미 삭제됨·다른 이슈 소속일 때 (404).
     */
    @Suppress("ThrowsCount") // 403/404 각기 다른 오류코드라 분리 throw 가 명확 ([update] 와 동일 사유)
    fun delete(
        actor: ActorId,
        issueKey: IssueKey,
        commentId: UUID,
    ) {
        checkPermission(actor, issueKey, IssuePermission.UPDATE)
        archiveGuard.checkByIssue(issueKey)

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)
        val existing =
            commentRepository.findActive(commentId, issue.id.value)
                ?: throw CommentNotFoundException(commentId)

        // 작성자 OR 모더레이터. hasPermission 은 질의형(던지지 않는다) — 여기서 던지는 판정을 쓰면
        // SOFT_DELETE 미보유 작성자가 자기 댓글도 못 지운다.
        val scope = IssueScope.Issue(issueKey.value)
        val isAuthor = existing.authorId == actor.value
        val isModerator = permissionResolver.hasPermission(actor.value, IssuePermission.SOFT_DELETE, scope)
        if (!isAuthor && !isModerator) {
            throw IssueAccessDeniedException(actor, IssuePermission.SOFT_DELETE, scope)
        }

        // 동시 삭제 레이스 방어 — findActive 통과 후 다른 트랜잭션이 삭제를 커밋한 경우 0 행이 돌아온다.
        if (commentRepository.softDelete(commentId, issue.id.value, Instant.now(clock)) == 0) {
            throw CommentNotFoundException(commentId)
        }

        // 삭제 이벤트 발행 — FR-CO-02 모더레이션의 빠진 절반.
        // 내 댓글이 모더레이터에게 지워져도 신호가 없었다(감사 이력은 조회해야 보이는 기록이지
        // 밀어주는 신호가 아니다). 작성자 id 를 페이로드에 실어 알림 BC 가 cross-BC 조회 없이
        // 수신자를 정하게 한다(IssueMentioned.mentionedUserIds 와 같은 방식).
        //
        // 자기 삭제(actor == author)도 **발행은 한다** — 자기제외는 수신자 해석 단계의 책임이다.
        // 발행을 조건부로 만들면 나중에 automation 이 이 이벤트를 소비할 때 누락이 생긴다.
        eventPublisher.publish(
            IssueCommentDeleted(
                issueKey = issueKey,
                projectKey = issueKey.projectPrefix,
                commentId = commentId,
                commentAuthorId = ActorId(existing.authorId),
                actorId = actor,
                occurredAt = Instant.now(clock),
            ),
        )

        log.info(
            "comment_deleted issueKey={} commentId={} actor={} moderated={}",
            issueKey.value,
            commentId,
            actor.value,
            !isAuthor,
        )
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

        return comments.map(CommentView::of)
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
