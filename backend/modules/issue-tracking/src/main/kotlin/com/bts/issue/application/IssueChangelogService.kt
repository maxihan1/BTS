// 이슈 변경 이력 조회 서비스 — VIEW 가드 재사용, actor 표시명 graceful degrade

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.cursor.CursorDecodeException
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.history.IssueChangeGroup
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.IssueChangeItem
import com.bts.shared.user.UserLookupPort
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * 이슈 변경 그룹 하나를 REST 레이어로 노출하기 위한 뷰 모델.
 *
 * B3 컨트롤러가 [ChangelogGroupView] 를 REST DTO([IssueChangelogResponse])로 매핑한다.
 *
 * @property actorId 변경을 수행한 행위자 UUID. null 이면 시스템 자동 처리.
 * @property actorName [actorId] 에 대응하는 표시명. identity-access 조회 실패 또는 actorId=null 이면 null.
 * @property createdAt 변경 그룹 생성 시각.
 * @property items 도메인 [IssueChangeItem] 목록. 라벨은 #120(PR-B IssueChangeLabelResolver)에서 박제된 값이 그대로 포함된다.
 */
data class ChangelogGroupView(
    val actorId: UUID?,
    val actorName: String?,
    val createdAt: Instant,
    val items: List<IssueChangeItem>,
)

/**
 * changelog cursor keyset seek 의 위치 정보 (FR-API-01 Task 5).
 *
 * [ChangelogCursorCodec] 으로 인코딩/디코딩된다.
 * 컨트롤러가 [ChangelogCursorCodec.decode] 로 디코딩 후 서비스에 주입,
 * 서비스가 DB 조회 후 [ChangelogCursorCodec.encode] 로 인코딩해 next 토큰으로 반환한다.
 *
 * @property createdAt cursor 기준 created_at (UTC OffsetDateTime).
 * @property groupId cursor 기준 group id (BIGINT). created_at 동률 tie-break 용.
 */
data class ChangelogCursorPosition(val createdAt: OffsetDateTime, val groupId: Long)

/**
 * changelog cursor 토큰 인코더/디코더 (FR-API-01 Task 5).
 *
 * 토큰 형식: `v1:<base64url(createdAt.toInstant()|groupId)>`.
 * 기존 [com.bts.issue.adapter.inbound.rest.cursor.CursorCodec] 과 동형이나
 * group id 가 BIGINT 이므로 별도 정의한다.
 *
 * **배치 없는 Base64URL** — `=` 패딩은 URL 쿼리 파라미터에서 인코딩 충돌을 일으키므로 제거.
 *
 * **timezone 일치 보장.**
 * [encode] 와 [decode] 모두 UTC 기준 [Instant] 문자열을 사용한다.
 * DB 조회 시 `Timestamp.from(cursorCreatedAt.toInstant())` 도 UTC 기준이므로 일치한다.
 */
object ChangelogCursorCodec {
    private const val VERSION = "v1"
    private const val SEPARATOR = "|"

    /**
     * [createdAt], [groupId] 를 opaque Base64URL cursor 토큰으로 인코딩한다.
     *
     * @param createdAt cursor 기준 생성 시각.
     * @param groupId cursor 기준 group id (BIGINT).
     * @return `v1:<base64url>` 형식 cursor 토큰.
     */
    fun encode(
        createdAt: OffsetDateTime,
        groupId: Long,
    ): String {
        val payload = "${createdAt.toInstant()}$SEPARATOR$groupId"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
        return "$VERSION:$encoded"
    }

    /**
     * [token] 을 [ChangelogCursorPosition] 으로 디코딩한다.
     *
     * 빈 문자열은 첫 페이지를 의미하며 null 을 반환한다
     * ([com.bts.issue.adapter.inbound.rest.cursor.CursorCodec.decode] 와 동형).
     *
     * @param token cursor 토큰 문자열.
     * @return 디코딩된 [ChangelogCursorPosition]. 빈 문자열이면 null.
     * @throws CursorDecodeException 형식 오류 또는 파싱 실패 시 (이슈 목록 [CursorCodec] 과 동일 예외 — 400 ISSUE_INVALID_CURSOR 통일).
     */
    @Suppress("ThrowsCount")
    fun decode(token: String): ChangelogCursorPosition? {
        if (token.isBlank()) return null
        val colonIdx = token.indexOf(':')
        if (colonIdx == -1 || token.substring(0, colonIdx) != VERSION) {
            throw CursorDecodeException("changelog cursor 형식 오류 (버전 불일치): $token")
        }
        val encoded = token.substring(colonIdx + 1)
        val payload =
            try {
                Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                throw CursorDecodeException("changelog cursor base64 디코드 실패: ${e.message}", e)
            }
        val sep = payload.indexOf(SEPARATOR)
        if (sep == -1) {
            throw CursorDecodeException("changelog cursor payload 구분자 없음: $payload")
        }
        val createdAtStr = payload.substring(0, sep)
        val groupIdStr = payload.substring(sep + 1)
        val createdAt =
            try {
                Instant.parse(createdAtStr).atOffset(ZoneOffset.UTC)
            } catch (e: java.time.format.DateTimeParseException) {
                throw CursorDecodeException("changelog cursor createdAt 파싱 실패: $createdAtStr", e)
            }
        val groupId =
            try {
                groupIdStr.toLong()
            } catch (e: NumberFormatException) {
                throw CursorDecodeException("changelog cursor groupId 파싱 실패: $groupIdStr", e)
            }
        return ChangelogCursorPosition(createdAt, groupId)
    }
}

/**
 * 이슈 변경 이력 조회 서비스.
 *
 * **설계 의도.**
 * [IssueApplicationService] 생성자를 수정하면 기존 단위 테스트 ~30개가 의존하는 생성자 시그니처가 바뀌어
 * 전부 컴파일 에러가 난다(learning `plan-files-constructor-injection-existing-tests`).
 * 대신 이 서비스를 별도 빈으로 정의해 [IssueApplicationService.findByKey] 를 위임 호출한다.
 * [com.bts.issue.history.IssueHistoryRecorder] 가 별도 빈으로 존재하는 것과 동일한 패턴이다.
 *
 * **actor 표시명 graceful degrade.**
 * [UserLookupPort.findDisplayNamesByIds] 호출이 실패해도 이력 조회를 막으면 안 된다.
 * PR #120 [IssueChangeLabelResolver] 의 `fetchDisplayNames` 와 동형의 try/catch 패턴을 사용해
 * 실패 시 actorName=null 로 degrade 하고 이력은 정상 반환한다(line 164-166 선례).
 *
 * **항목 마스킹은 [IssueChangeItemMasker] 에 위임한다.**
 * 필드 수준 권한(FR-PM-07)과 삭제된 댓글 본문(FR-CO-02) 두 가림을 그 협력자가 함께 판정한다.
 * 프로젝트 활동 피드([com.bts.issue.summary.application.ProjectSummaryService])도 **같은 협력자**를
 * 쓴다 — 같은 `issue_change_item` 행을 읽는 두 경로가 판정을 각자 복사하면 한쪽에 필드가 추가될 때
 * 다른 쪽이 조용히 샌다. 가림의 근거와 fail-closed 규칙은 [IssueChangeItemMasker] KDoc 에 있다.
 */
@Service
class IssueChangelogService(
    private val issueApplicationService: IssueApplicationService,
    private val changeHistoryRepository: IssueChangeHistoryRepository,
    private val userLookupPort: UserLookupPort,
    // 마스킹 협력자는 기본값이 없다 — 필드 권한·삭제 댓글 가림은 선택적 기능이 아니다.
    // 근거는 IssueChangeItemMasker KDoc 참조.
    private val masker: IssueChangeItemMasker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 변경 이력을 페이지 단위로 조회한다.
     *
     * 흐름.
     * 1. [IssueApplicationService.findByKey] 로 VIEW 권한 + 이슈 존재 검증.
     *    실패 시 [com.bts.issue.domain.IssueNotFoundException] 전파.
     * 2. [IssueChangeHistoryRepository.findByIssuePaged] + [IssueChangeHistoryRepository.countByIssue] 로 페이지 조회.
     *    offset 은 Long 곱 후 Int 범위로 클램프해 오버플로 음수 OFFSET 을 방지한다(코드리뷰 P2).
     * 3. actorId 집합을 [UserLookupPort.findDisplayNamesByIds] 로 일괄 해석. 실패 시 emptyMap graceful degrade.
     * 4. [maskItems] 로 안 보이는 필드와 삭제된 댓글의 본문을 마스킹한다([IssueChangeItemMasker]).
     * 5. [ChangelogGroupView] 뷰 모델로 매핑해 [PageImpl] 반환.
     *
     * @param actor 조회 행위자. VIEW 권한 검증 + 필드 수준 마스킹에 사용.
     * @param key 조회할 이슈 키.
     * @param pageable 페이지 정보. pageSize=limit, pageNumber*pageSize=offset 으로 변환.
     * @return 변경 이력 [Page]. 이력 없으면 빈 Page.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제·VIEW 미인가(존재 숨김).
     */
    @Transactional(readOnly = true)
    fun findChangelog(
        actor: ActorId,
        key: IssueKey,
        pageable: Pageable,
    ): Page<ChangelogGroupView> {
        val issue = issueApplicationService.findByKey(actor, key)
        val issueId = issue.id

        val limit = pageable.pageSize
        // pageNumber * pageSize 를 Int 로 곱하면 오버플로로 음수 OFFSET 이 되어 Postgres 가 500 을 던진다.
        // Long 으로 곱한 뒤 repository offset(Int) 범위로 안전하게 클램프한다(코드리뷰 P2).
        val offset = (pageable.pageNumber.toLong() * pageable.pageSize).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        val groups = changeHistoryRepository.findByIssuePaged(issueId, limit, offset)
        val total = changeHistoryRepository.countByIssue(issueId)

        val displayNames = resolveActorNames(groups)

        val maskedGroups = maskItems(actor, issue.projectKey, issueId, groups)

        val views = maskedGroups.map { group -> group.toView(displayNames) }
        return PageImpl(views, pageable, total)
    }

    /**
     * 이슈 변경 이력을 cursor keyset seek 으로 조회한다 (FR-API-01 Task 5).
     *
     * 흐름.
     * 1. [IssueApplicationService.findByKey] 로 VIEW 권한 + 이슈 존재 검증 (404 게이트).
     *    미존재·소프트삭제·VIEW 미인가 모두 [com.bts.issue.domain.IssueNotFoundException] 으로 전파.
     * 2. [IssueChangeHistoryRepository.findByIssueCursor] 로 cursor seek (내부에서 limit+1 조회).
     * 3. hasNext 판정: pairs.size > limit.
     * 4. [resolveActorNames] + [maskItems] 로 display name 해석 + 항목 마스킹.
     * 5. next 토큰: hasNext 이면 [ChangelogCursorCodec.encode] 로 마지막 표시 항목을 인코딩.
     *
     * **`!!` 금지 (DEVELOPMENT.md §1).**
     * [IssueChangeGroup.createdAt] null 체크는 `?: error(...)` 로 처리한다.
     *
     * **cartesian product 안전.**
     * [IssueChangeHistoryRepository.findByIssueCursor] 가 기존 2-step 패턴을 유지한다
     * (learnings: jOOQ-cartesian-product).
     *
     * @param actor 조회 행위자. VIEW 권한 검증 + 필드 마스킹에 사용.
     * @param key 조회할 이슈 키.
     * @param cursor cursor 위치. null 이면 첫 페이지 (seek 없음).
     * @param limit 반환할 최대 그룹 수.
     * @return cursor 페이지 결과. next=null 이면 마지막 페이지.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제·VIEW 미인가 → 404.
     */
    @Transactional(readOnly = true)
    fun findChangelogByCursor(
        actor: ActorId,
        key: IssueKey,
        cursor: ChangelogCursorPosition?,
        limit: Int,
    ): CursorPage<ChangelogGroupView> {
        val issue = issueApplicationService.findByKey(actor, key)
        val issueId = issue.id

        val pairs =
            changeHistoryRepository.findByIssueCursor(
                issueId,
                cursor?.createdAt?.toInstant(),
                cursor?.groupId,
                limit,
            )

        val hasNext = pairs.size > limit
        val pagePairs = if (hasNext) pairs.dropLast(1) else pairs
        val groups = pagePairs.map { it.second }

        val displayNames = resolveActorNames(groups)
        val maskedGroups = maskItems(actor, issue.projectKey, issueId, groups)
        val views = maskedGroups.map { group -> group.toView(displayNames) }

        val next =
            if (hasNext) {
                val (lastGroupId, lastGroup) = pagePairs.last()
                val createdAt =
                    lastGroup.createdAt
                        ?: error(
                            "IssueChangeGroup.createdAt DB 로드 후 null 일 수 없습니다 (issueId=$issueId)",
                        )
                ChangelogCursorCodec.encode(createdAt.atOffset(ZoneOffset.UTC), lastGroupId)
            } else {
                null
            }

        return CursorPage(items = views, next = next)
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 항목 마스킹 (FR-PM-07 필드 권한 + FR-CO-02 삭제 댓글) — 판정은 IssueChangeItemMasker 소유
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * 변경 그룹의 항목을 [IssueChangeItemMasker] 판정표로 가린다.
     *
     * 항목을 제거하지 않고 값·라벨 4종만 null 로 치환한다 — "변경이 있었다" 는 사실은 감사 추적
     * 대상이라 남아야 한다. 근거는 [IssueChangeItemMasker] KDoc 참조.
     *
     * **조회 1벌.** 페이지 전체 항목을 한 번에 넘겨 필드 권한 1회 + 활성 댓글 1회로 판정을 끝낸다.
     * 그룹·항목마다 부르면 N+1 이다.
     *
     * @param actor 조회 행위자.
     * @param projectKey 이슈가 속한 프로젝트 키.
     * @param issueId 조회 중인 이슈 UUID. 댓글 소속 대조에 쓴다.
     * @param groups 마스킹 전 변경 그룹 목록.
     * @return 값이 마스킹된 그룹 목록. 항목이 없으면 원본 그대로.
     */
    private fun maskItems(
        actor: ActorId,
        projectKey: String,
        issueId: UUID,
        groups: List<IssueChangeGroup>,
    ): List<IssueChangeGroup> {
        if (groups.isEmpty()) return groups
        val mask = masker.planFor(actor, projectKey, mapOf(issueId to groups.flatMap { it.items }))
        return groups.map { group -> group.copy(items = mask.applyAll(issueId, group.items)) }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // 헬퍼
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * 그룹 목록에서 non-null actorId 를 수집해 표시명을 일괄 조회한다.
     *
     * [UserLookupPort.findDisplayNamesByIds] 호출 실패 시 emptyMap 으로 degrade —
     * 이력 조회 자체를 막으면 안 된다(#120 IssueChangeLabelResolver:164-166 선례).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun resolveActorNames(groups: List<IssueChangeGroup>): Map<UUID, String> {
        val actorIds = groups.mapNotNull { it.actorId }.toSet()
        if (actorIds.isEmpty()) return emptyMap()
        return try {
            userLookupPort.findDisplayNamesByIds(actorIds)
        } catch (e: Exception) {
            log.warn(
                "UserLookupPort.findDisplayNamesByIds failed for actorIds={}, degrade to null actorName",
                actorIds,
                e,
            )
            emptyMap()
        }
    }

    /**
     * [IssueChangeGroup] 도메인 객체를 [ChangelogGroupView] 뷰 모델로 변환한다.
     *
     * DB 에서 로드된 그룹의 [IssueChangeGroup.createdAt] 은 반드시 non-null 이어야 한다.
     * [displayNames] 맵에 [IssueChangeGroup.actorId] 가 없으면 actorName=null 로 graceful degrade.
     */
    private fun IssueChangeGroup.toView(displayNames: Map<UUID, String>): ChangelogGroupView =
        ChangelogGroupView(
            actorId = actorId,
            actorName = actorId?.let { displayNames[it] },
            createdAt =
                requireNotNull(createdAt) {
                    "IssueChangeGroup.createdAt must not be null after DB load (issueId=$issueId)"
                },
            items = items,
        )
}
