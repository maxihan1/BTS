// LinkApplicationService — 이슈 링크 생성·조회·해제 유스케이스 조율. 모든 public 메서드 @Transactional 명시.

package com.bts.issue.link.application

import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.DuplicateLinkException
import com.bts.issue.link.domain.IssueLink
import com.bts.issue.link.domain.LinkCycleException
import com.bts.issue.link.domain.LinkNotFoundException
import com.bts.issue.link.domain.LinkType
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.link.repository.LinkedIssueRow
import com.bts.issue.repository.IssueRepository
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
     * @param key 링크를 조회할 이슈 키.
     * @return [LinkListResult] — outward(source=key) + inward(target=key) 링크 목록.
     * @throws LinkedIssueNotFoundException 이슈가 없거나 소프트삭제된 경우.
     */
    @Transactional(readOnly = true)
    fun listLinks(key: IssueKey): LinkListResult {
        log.debug("listLinks key={}", key.value)

        val issue =
            issueRepository.findByKey(key)
                ?: throw LinkedIssueNotFoundException(key)

        val outwardRows = linkRepository.findOutwardWithIssue(issue.id.value)
        val inwardRows = linkRepository.findInwardWithIssue(issue.id.value)

        return LinkListResult(
            outward = outwardRows.map { row -> row.toEntry(outward = true) },
            inward = inwardRows.map { row -> row.toEntry(outward = false) },
        )
    }

    /**
     * 링크를 해제(물리 삭제)한다.
     *
     * `issue_links` 는 소프트 삭제 없이 행을 물리 삭제한다 (DATA.md §3).
     *
     * @param key 링크 대상 이슈 키 (존재 확인용).
     * @param linkId 삭제할 링크 BIGINT id.
     * @throws LinkedIssueNotFoundException 이슈가 없거나 소프트삭제된 경우.
     * @throws LinkNotFoundException 링크 id 에 해당하는 행이 없는 경우.
     */
    @Transactional
    fun deleteLink(
        key: IssueKey,
        linkId: Long,
    ) {
        log.debug("deleteLink key={} linkId={}", key.value, linkId)

        issueRepository.findByKey(key)
            ?: throw LinkedIssueNotFoundException(key)

        val deleted = linkRepository.deleteById(linkId)
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
}
