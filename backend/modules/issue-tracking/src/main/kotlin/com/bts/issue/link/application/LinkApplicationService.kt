// LinkApplicationService — 이슈 링크 생성·조회·해제 유스케이스 조율. 모든 public 메서드 @Transactional 명시.

package com.bts.issue.link.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.DuplicateLinkException
import com.bts.issue.link.domain.IssueLink
import com.bts.issue.link.domain.LinkCycleException
import com.bts.issue.link.domain.LinkNotFoundException
import com.bts.issue.link.domain.LinkType
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.link.repository.LinkedIssueRow
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// ── 읽기 모델 ─────────────────────────────────────────────────────────────────

/**
 * [LinkApplicationService.createLink] 성공 결과.
 *
 * @property linkId 생성된 링크의 BIGINT id.
 * @property linkType 링크 유형.
 * @property sourceIssueId source 이슈 UUID.
 * @property targetIssueId target 이슈 UUID.
 */
data class LinkResult(
    val linkId: Long,
    val linkType: LinkType,
    val sourceIssueId: UUID,
    val targetIssueId: UUID,
)

/**
 * [LinkApplicationService.listLinks] 의 방향별 항목.
 *
 * @property linkId 링크 BIGINT id.
 * @property linkType 링크 유형.
 * @property relationLabel 이 이슈 관점에서의 관계 라벨. outward 이면 [LinkType.outwardLabel], inward 이면 [LinkType.inwardLabel].
 * @property otherIssueKey 상대 이슈 키 (예: "BTS-2").
 * @property otherIssueSummary 상대 이슈 제목.
 * @property otherCurrentStateKey 상대 이슈 현재 상태 키.
 * @property otherIssueId 상대 이슈 내부 UUID.
 */
data class LinkEntry(
    val linkId: Long,
    val linkType: LinkType,
    val relationLabel: String,
    val otherIssueKey: String,
    val otherIssueSummary: String,
    val otherCurrentStateKey: String,
    val otherIssueId: UUID,
)

/**
 * [LinkApplicationService.listLinks] 결과.
 *
 * @property outward 이 이슈가 source 인 링크 목록.
 * @property inward 이 이슈가 target 인 링크 목록.
 */
data class LinkListResult(
    val outward: List<LinkEntry>,
    val inward: List<LinkEntry>,
)

// ── 서비스 ────────────────────────────────────────────────────────────────────

/**
 * 이슈 링크 생성·조회·해제 유스케이스를 조율하는 Application Service.
 *
 * ## 트랜잭션 경계 (DATA.md §6)
 * - [createLink] / [deleteLink] — 기본 `@Transactional` (읽기+쓰기).
 * - [listLinks] — `@Transactional(readOnly = true)`.
 *
 * ## 도메인 팩토리 경유 (PATCH domain-bypass 방지)
 * 링크 생성은 반드시 [IssueLink.create] 를 통해 `LinkSelfReferenceException` 불변식 검증을 거친다.
 *
 * ## BC 격리
 * `IssueRepository` 와 `IssueLinkRepository` 모두 issue-tracking BC 내부라 직접 의존 가능.
 * 타 BC 호출은 이벤트 발행으로만 한다 (현재 Task 5 범위에선 이벤트 발행 없음).
 */
@Service
class LinkApplicationService(
    private val issueRepository: IssueRepository,
    private val linkRepository: IssueLinkRepository,
    private val archiveGuard: ProjectArchiveGuard,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 링크를 생성한다.
     *
     * 검증 순서.
     * 1. source 이슈 존재 여부 (소프트삭제 포함) — [LinkedIssueNotFoundException].
     * 2. sourceKey == targetKey 자기참조 조기 차단 (IssueKey 비교 — UUID 조회 전 빠른 실패).
     * 3. target 이슈 존재 여부 — [LinkedIssueNotFoundException].
     * 4. UUID 레벨 자기참조 → [IssueLink.create] 가 [com.bts.issue.link.domain.LinkSelfReferenceException] 발생.
     * 5. 중복 링크 — [DuplicateLinkException].
     * 6. BLOCKS 전용 순환 탐지 — [LinkCycleException]. 다른 타입은 skip.
     *
     * @param sourceKey 링크 출발 이슈 키.
     * @param targetKey 링크 도착 이슈 키.
     * @param linkTypeCode 링크 유형 코드 문자열 (예: "blocks").
     * @return 생성된 링크의 [LinkResult].
     * @throws LinkedIssueNotFoundException source 또는 target 이슈가 없거나 소프트삭제된 경우.
     * @throws com.bts.issue.link.domain.LinkSelfReferenceException source == target 인 경우.
     * @throws DuplicateLinkException 동일 조합의 링크가 이미 존재하는 경우.
     * @throws LinkCycleException BLOCKS 순환이 탐지된 경우.
     */
    @Transactional
    fun createLink(
        actor: ActorId,
        sourceKey: IssueKey,
        targetKey: IssueKey,
        linkTypeCode: String,
    ): LinkResult {
        log.debug(
            "createLink sourceKey={} targetKey={} linkTypeCode={}",
            sourceKey.value,
            targetKey.value,
            linkTypeCode,
        )
        // ★권한을 **리소스 조회보다 먼저** 건다 — 뒤에 두면 권한 없는 사용자가 404/409 차이로
        // 이슈 실재를 열거한다([[auth-extraction-before-resource-lookup]]).
        //
        // ★★양끝을 모두 검사한다. source 만 보면 볼 수 없는 이슈를 target 으로 지목해
        // 「존재하지 않음(404)」 과 「이미 링크됨(409)」 의 차이로 실재를 확인할 수 있다.
        // 링크는 양방향 관계이므로 쓰기 권한도 양쪽에 필요하다는 것이 의미상으로도 맞다.
        checkPermission(actor, sourceKey, IssuePermission.UPDATE)
        checkPermission(actor, targetKey, IssuePermission.UPDATE)
        // 아카이브 잠금 — source/target 어느 한쪽이라도 아카이브된 프로젝트 소속이면 409 (양방향 관계 쓰기).
        archiveGuard.checkByIssue(sourceKey)
        archiveGuard.checkByIssue(targetKey)
        val linkType = LinkType.fromCode(linkTypeCode)
        val link = resolveAndValidateLink(sourceKey, targetKey, linkType)
        val saved = linkRepository.insert(link)
        log.info(
            "createLink success linkId={} source={} target={} type={}",
            saved.id,
            saved.sourceId,
            saved.targetId,
            saved.linkType,
        )
        return LinkResult(
            linkId = requireNotNull(saved.id) { "insert 후 id 는 null 이 아니어야 한다." },
            linkType = saved.linkType,
            sourceIssueId = saved.sourceId,
            targetIssueId = saved.targetId,
        )
    }

    /**
     * 링크 생성 전 이슈 존재·자기참조·중복·순환 검증을 수행하고 검증된 [IssueLink] 를 반환한다.
     *
     * [createLink] 에서 throw 횟수가 detekt ThrowsCount 를 넘어 헬퍼 메서드로 분리.
     *
     * @throws LinkedIssueNotFoundException source 또는 target 이슈 미존재.
     * @throws com.bts.issue.link.domain.LinkSelfReferenceException 자기참조.
     * @throws DuplicateLinkException 중복 링크.
     * @throws LinkCycleException BLOCKS 순환.
     */
    @Suppress("ThrowsCount")
    private fun resolveAndValidateLink(
        sourceKey: IssueKey,
        targetKey: IssueKey,
        linkType: LinkType,
    ): IssueLink {
        val sourceIssue =
            issueRepository.findByKey(sourceKey)
                ?: throw LinkedIssueNotFoundException(sourceKey)

        // IssueKey 레벨 자기참조 조기 차단 — target 조회 전 빠른 실패
        if (sourceKey.value == targetKey.value) {
            throw com.bts.issue.link.domain.LinkSelfReferenceException(sourceIssue.id.value)
        }

        val targetIssue =
            issueRepository.findByKey(targetKey)
                ?: throw LinkedIssueNotFoundException(targetKey)

        // 도메인 팩토리 경유 — UUID 레벨 자기참조 검증 포함 (patch-merge-domain-bypass 방지)
        val link = IssueLink.create(sourceIssue.id.value, targetIssue.id.value, linkType)

        if (linkRepository.existsLink(link.sourceId, link.targetId, linkType)) {
            throw DuplicateLinkException(link.sourceId, link.targetId, linkType)
        }

        // BLOCKS 전용 순환 탐지 (역방향 경로 존재 → 추가 시 순환 형성)
        if (linkType == LinkType.BLOCKS && linkRepository.existsBlocksPath(link.targetId, link.sourceId)) {
            throw LinkCycleException(link.sourceId, link.targetId)
        }

        return link
    }

    /**
     * 이슈에 연결된 모든 링크를 조회한다 (outward + inward).
     *
     * N+1 없이 단일 JOIN 쿼리로 상대 이슈 정보를 함께 조회한다.
     * 소프트삭제된 상대 이슈는 쿼리 단에서 제외된다.
     *
     * ## 읽기에도 양끝 검사 — 상대 이슈 VIEW 필터
     * 중심 이슈 VIEW 만 확인하면, 응답에 실리는 상대 이슈의 `summary`·`statusKey` 가
     * 볼 권한 없는 이슈의 내용까지 흘린다. 쓰기(`createLink`)는 양끝을 검사하면서
     * 읽기만 한쪽이던 비대칭을 여기서 닫는다.
     *
     * **거부가 아니라 제외**다. 중심 이슈는 볼 수 있으므로 목록 자체는 성공해야 하고,
     * 못 보는 상대만 빠진다. 403 으로 만들면 "이 이슈에는 내가 못 보는 링크가 있다" 는
     * 사실 자체가 오라클이 된다.
     *
     * @param key 링크를 조회할 이슈 키.
     * @return [LinkListResult] — outward + inward 중 **actor 가 상대를 볼 수 있는 것만**.
     * @throws LinkedIssueNotFoundException 이슈가 없거나 소프트삭제된 경우.
     */
    @Transactional(readOnly = true)
    fun listLinks(
        actor: ActorId,
        key: IssueKey,
    ): LinkListResult {
        log.debug("listLinks key={}", key.value)
        // 읽기이므로 VIEW. 조회보다 먼저 걸어야 404 로 실재를 열거당하지 않는다.
        checkPermission(actor, key, IssuePermission.VIEW)

        val issue =
            issueRepository.findByKey(key)
                ?: throw LinkedIssueNotFoundException(key)

        val outwardRows = linkRepository.findOutwardWithIssue(issue.id.value)
        val inwardRows = linkRepository.findInwardWithIssue(issue.id.value)

        return LinkListResult(
            outward = outwardRows.filter { canViewOther(actor, it) }.map { row -> row.toEntry(outward = true) },
            inward = inwardRows.filter { canViewOther(actor, it) }.map { row -> row.toEntry(outward = false) },
        )
    }

    /**
     * 상대 이슈를 actor 가 볼 수 있는지 — **던지지 않는** 판정이다.
     *
     * [checkPermission] 과 술어는 같지만 예외 대신 Boolean 을 준다. 목록 필터에서는
     * 한 건이 막혔다고 요청 전체를 실패시키면 안 되기 때문이다.
     */
    private fun canViewOther(
        actor: ActorId,
        row: LinkedIssueRow,
    ): Boolean =
        permissionResolver.hasPermission(
            actor.value,
            IssuePermission.VIEW,
            IssueScope.Issue(row.otherIssueKey),
        )

    /**
     * 링크를 해제(물리 삭제)한다.
     *
     * `issue_links` 는 소프트 삭제 없이 행을 물리 삭제한다 (DATA.md §3).
     *
     * ## 권한이 거는 대상과 삭제가 지우는 대상을 **일치시킨다**
     * 권한은 경로 이슈 [key] 에 걸리는데 삭제 대상은 전역 순번 [linkId] 다.
     * 둘을 묶지 않으면 UPDATE 를 가진 아무 이슈나 경로에 넣고 **남의 링크 id** 를
     * 붙여 지울 수 있다(IDOR). 그래서 [IssueLinkRepository.deleteByIdAndIssue] 로
     * **WHERE 절에서 함께 좁힌다.**
     *
     * 소속이 아니면 **404** 다 — 403 이면 "그 id 는 존재한다" 는 오라클이 되어,
     * 미존재 id 와 남의 링크 id 를 응답으로 구분할 수 있게 된다.
     *
     * @param key 링크 대상 이슈 키 (존재 확인 + **삭제 범위 한정**).
     * @param linkId 삭제할 링크 BIGINT id.
     * @throws LinkedIssueNotFoundException 이슈가 없거나 소프트삭제된 경우.
     * @throws LinkNotFoundException 링크가 없거나 **[key] 소속이 아닌** 경우.
     */
    @Transactional
    fun deleteLink(
        actor: ActorId,
        key: IssueKey,
        linkId: Long,
    ) {
        log.debug("deleteLink key={} linkId={}", key.value, linkId)
        checkPermission(actor, key, IssuePermission.UPDATE)
        archiveGuard.checkByIssue(key)

        val issue =
            issueRepository.findByKey(key)
                ?: throw LinkedIssueNotFoundException(key)

        val deleted = linkRepository.deleteByIdAndIssue(linkId, issue.id.value)
        if (!deleted) {
            throw LinkNotFoundException(linkId)
        }

        log.info("deleteLink success key={} linkId={}", key.value, linkId)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [LinkedIssueRow] 를 [LinkEntry] 로 변환한다.
     *
     * @param outward true 이면 이 이슈가 source (outwardLabel 사용), false 이면 target (inwardLabel 사용).
     */
    private fun LinkedIssueRow.toEntry(outward: Boolean): LinkEntry =
        LinkEntry(
            linkId = linkId,
            linkType = linkType,
            relationLabel = if (outward) linkType.outwardLabel else linkType.inwardLabel,
            otherIssueKey = otherIssueKey,
            otherIssueSummary = otherIssueSummary,
            otherCurrentStateKey = otherCurrentStateKey,
            otherIssueId = otherIssueId,
        )

    /**
     * 이슈 스코프 권한을 강제한다 — 미보유 시 [IssueAccessDeniedException].
     *
     * 형제 `WorklogService.checkPermission` 과 **같은 형태**다. 술어를 새로 만들지 않는다.
     *
     * ## 2026-07-27 이전에는 이 게이트가 아예 없었다
     * 이 서비스는 `permissionResolver` 를 주입조차 받지 않았고, 막고 있던 것은
     * `SecurityConfig` 의 `.authenticated()` 뿐이었다. ⇒ 인증만 통과하면 누구나
     * **자기가 멤버가 아닌 프로젝트의, 볼 수도 없는 기밀 이슈**에 링크를 걸고 지울 수 있었다.
     *
     * 잠복한 이유는 컨트롤러 KDoc 이 *"issue_links / parent_id 는 created_by 를 저장하지 않으므로
     * actor 추출이 불필요하다"* 라고 적어둔 데 있다 — **「누가 만들었는지 기록 안 함」 을
     * 「누가 만들어도 되는지 검사 안 해도 됨」 의 근거로** 쓴 문장이다. 감사 흔적의 부재는
     * 권한 검사 면제의 근거가 아니다.
     */
    private fun checkPermission(
        actor: ActorId,
        issueKey: IssueKey,
        permission: IssuePermission,
    ) {
        val scope = IssueScope.Issue(issueKey.value)
        if (!permissionResolver.hasPermission(actor.value, permission, scope)) {
            throw IssueAccessDeniedException(actor, permission, scope)
        }
    }
}
