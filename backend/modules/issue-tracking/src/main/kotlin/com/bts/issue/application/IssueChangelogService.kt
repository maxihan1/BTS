// 이슈 변경 이력 조회 서비스 — VIEW 가드 재사용, actor 표시명 graceful degrade

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.cursor.CursorDecodeException
import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.fieldpermission.adapter.AlwaysAllowFieldPermissionResolver
import com.bts.issue.history.IssueChangeGroup
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.IssueChangeItem
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
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
 * [IssueHistoryRecorder] 가 별도 빈으로 존재하는 것과 동일한 패턴이다.
 *
 * **actor 표시명 graceful degrade.**
 * [UserLookupPort.findDisplayNamesByIds] 호출이 실패해도 이력 조회를 막으면 안 된다.
 * PR #120 [IssueChangeLabelResolver] 의 `fetchDisplayNames` 와 동형의 try/catch 패턴을 사용해
 * 실패 시 actorName=null 로 degrade 하고 이력은 정상 반환한다(line 164-166 선례).
 *
 * **필드 수준 마스킹(FR-PM-07).**
 * VIEW 권한만으로는 부족하다. 단건 상세가 안 보이는 필드의 현재값을 가리므로([IssueApplicationService.maskFieldsForSingle]),
 * changelog 도 같은 필드의 과거 from/to 값·박제 라벨을 가려야 한다. 그렇지 않으면 특정 필드가 제한된
 * 사용자에게 과거 민감값이 누출된다. [maskInvisibleFields] 가 [FieldPermissionResolver.visibleFields] 로
 * 단건과 동일한 마스킹 정책을 적용한다.
 *
 * **삭제된 댓글의 이력 본문 마스킹(FR-CO-02).**
 * 댓글 모더레이션과 댓글 수정 이력 보존은 각각 타당하지만 겹치면 충돌한다 — 상세 근거는
 * [maskDeletedCommentBodies] KDoc 참조. 조회 시점에 삭제된 댓글의 본문만 가린다.
 */
@Service
class IssueChangelogService(
    private val issueApplicationService: IssueApplicationService,
    private val changeHistoryRepository: IssueChangeHistoryRepository,
    private val userLookupPort: UserLookupPort,
    private val issueRepository: IssueRepository,
    // 기본값은 Spring 이 관리하지 않는 단위 테스트 컨텍스트 호환용 fallback 이다(IssueApplicationService 와 동형).
    // prod 컨텍스트에서는 IdentityAccessFieldPermissionResolver(@Profile("prod")) 또는
    // AlwaysAllowFieldPermissionResolver(@Profile("!prod")) Bean 이 타입으로 주입돼 이 기본값을 대체한다.
    private val fieldPermissionResolver: FieldPermissionResolver = AlwaysAllowFieldPermissionResolver(),
    // 위 resolver 와 달리 기본값이 없다 — 삭제 댓글 판정은 선택적 기능이 아니다.
    // 근거는 maskDeletedCommentBodies KDoc 참조.
    private val commentRepository: CommentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** changelog 의 assignee 변경 item field 키. */
        private const val ASSIGNEE_FIELD = "assignee"

        /** 단건 마스킹 candidate 의 담당자 CORE 키([IssueApplicationService.buildCoreCandidates] 와 일치). */
        private const val ASSIGNEE_ID_KEY = "assigneeId"

        /** changelog 의 커스텀 필드 item field prefix([IssueChangeDetector] 와 동일). */
        private const val CUSTOM_FIELD_PREFIX = "customField:"

        /**
         * 단건 상세가 마스킹하는 코어 필드 중 changelog field 키와 동일명인 것.
         *
         * assignee(→assigneeId)·customField 는 별도 매핑이므로 제외한다.
         * status·type·resolution·securityLevel·components·affectsVersions·fixVersions·lifecycle 은
         * 단건도 마스킹하지 않으므로 changelog 도 노출 유지 — 이 집합에 넣지 않는다.
         */
        private val MASKABLE_CORE_FIELDS =
            setOf("description", "environment", "impact", "labels", "summary", "priority")

        /**
         * changelog 의 댓글 본문 변경 item field prefix.
         *
         * 기록 측([IssueHistoryRecorder.COMMENT_FIELD_PREFIX])과 조회 측이 같은 상수를 봐야
         * 한쪽만 바뀌어 마스킹이 조용히 무력화되는 일이 없다.
         */
        private const val COMMENT_FIELD_PREFIX = IssueHistoryRecorder.COMMENT_FIELD_PREFIX
    }

    /**
     * 이슈 변경 이력을 페이지 단위로 조회한다.
     *
     * 흐름.
     * 1. [IssueApplicationService.findByKey] 로 VIEW 권한 + 이슈 존재 검증.
     *    실패 시 [com.bts.issue.domain.IssueNotFoundException] 전파.
     * 2. [IssueChangeHistoryRepository.findByIssuePaged] + [IssueChangeHistoryRepository.countByIssue] 로 페이지 조회.
     *    offset 은 Long 곱 후 Int 범위로 클램프해 오버플로 음수 OFFSET 을 방지한다(코드리뷰 P2).
     * 3. actorId 집합을 [UserLookupPort.findDisplayNamesByIds] 로 일괄 해석. 실패 시 emptyMap graceful degrade.
     * 4. [maskInvisibleFields] 로 안 보이는 필드의 변경 값을 마스킹한다(단건과 동일 정책, 코드리뷰 P1).
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

        val fieldMasked = maskInvisibleFields(actor, issue.projectKey, groups)
        val maskedGroups = maskDeletedCommentBodies(issueId, fieldMasked)

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
     * 4. [resolveActorNames] + [maskInvisibleFields] 로 display name 해석 + 필드 마스킹.
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
        val fieldMasked = maskInvisibleFields(actor, issue.projectKey, groups)
        val maskedGroups = maskDeletedCommentBodies(issueId, fieldMasked)
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
    // 필드 수준 마스킹 (FR-PM-07, 코드리뷰 P1)
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * 변경 이력 항목을 단건 상세([IssueApplicationService.maskFieldsForSingle])와 **동일한 필드 수준
     * 마스킹 정책**으로 필터한다.
     *
     * **왜 단건과 같은 정책인가.**
     * 단건 상세는 [FieldPermissionResolver.visibleFields] 로 안 보이는 필드의 현재값을 가린다(FR-PM-07).
     * changelog 가 같은 필드의 과거 from/to 값·박제 라벨을 그대로 노출하면 VIEW 권한만으로
     * 특정 필드가 제한된 사용자에게 과거 민감값이 누출된다. 두 경로의 마스킹 집합을 일치시켜
     * "현재값은 가리는데 과거값은 보이는" 비대칭을 제거한다.
     *
     * **값 가림 + 필드 존재 유지.**
     * 단건은 필드 존재는 남기되 값을 가린다([IssueResponse.maskInvisible] 의 restrictedFields 시맨틱).
     * changelog 도 동형으로, 안 보이는 필드 item 은 통째로 제거하지 않고 [IssueChangeItem.field] 는 남긴 채
     * [IssueChangeItem.fromValue]/[IssueChangeItem.toValue]/[IssueChangeItem.fromLabel]/[IssueChangeItem.toLabel]
     * 만 null 로 치환한다. item 을 제거하면 "변경이 있었다"는 사실까지 숨겨 단건과 비대칭이 된다.
     *
     * **fail-open 금지.**
     * projectId 를 찾지 못하면 단건([IssueApplicationService.maskFieldsForSingle]:1430)과 동일하게 원본을
     * 그대로 반환한다(방어적). prod 에서는 실제 resolver 가 주입되며, 비주입 시 기본값은 비prod 전용
     * [AlwaysAllowFieldPermissionResolver] 다.
     *
     * @param actor 조회 행위자.
     * @param projectKey 이슈가 속한 프로젝트 키.
     * @param groups 마스킹 전 변경 그룹 목록.
     * @return 안 보이는 필드의 값이 마스킹된 그룹 목록.
     */
    @Suppress("ReturnCount") // empty guard + projectId miss guard + candidates 비어있음 guard — 의도적 조기 반환
    private fun maskInvisibleFields(
        actor: ActorId,
        projectKey: String,
        groups: List<IssueChangeGroup>,
    ): List<IssueChangeGroup> {
        if (groups.isEmpty()) return groups
        val projectId = issueRepository.findProjectIdByKey(projectKey) ?: return groups

        // 페이지 내 모든 item 의 마스킹 candidate 합집합 — resolver 를 1회만 호출(N+1 회피).
        val candidates =
            groups
                .flatMap { it.items }
                .mapNotNull { maskableFieldRef(it.field) }
                .toSet()
        if (candidates.isEmpty()) return groups

        val visible = fieldPermissionResolver.visibleFields(actor.value, projectId, candidates)

        return groups.map { group ->
            group.copy(items = group.items.map { item -> maskItemIfInvisible(item, visible) })
        }
    }

    /**
     * 변경 item 1건을 [visible] 기준으로 마스킹한다.
     *
     * item 의 [IssueChangeItem.field] 가 마스킹 대상이고 [visible] 에 없으면 값·라벨 4종을 null 로 치환한다.
     * 마스킹 대상이 아니거나(예: status·type) 보이는 필드면 원본을 그대로 반환한다.
     */
    @Suppress("ReturnCount") // 비마스킹대상 guard + visible guard + 마스킹 반환 — 의도적 조기 반환
    private fun maskItemIfInvisible(
        item: IssueChangeItem,
        visible: Set<FieldRef>,
    ): IssueChangeItem {
        val ref = maskableFieldRef(item.field) ?: return item
        if (ref in visible) return item
        return item.copy(fromValue = null, toValue = null, fromLabel = null, toLabel = null)
    }

    /**
     * changelog item 의 field 키를 단건 마스킹 candidate 와 동일한 [FieldRef] 로 매핑한다.
     *
     * 마스킹 대상이 아닌 필드(status·type·resolution·securityLevel·components·affectsVersions·
     * fixVersions·lifecycle)는 null 을 반환해 항상 노출시킨다 — 단건 상세도 이 필드들은 마스킹하지 않으므로 정합.
     *
     * 매핑 규칙.
     * - `"assignee"` → `FieldRef(CORE, "assigneeId")` (단건 candidate 의 코어 키와 일치시킴)
     * - `"customField:<key>"` → `FieldRef(CUSTOM, key)`
     * - 그 외 마스킹 대상 동일명(description·environment·impact·labels·summary·priority) → `FieldRef(CORE, name)`
     *
     * @param field changelog item 의 field 키.
     * @return 마스킹 대상이면 대응 [FieldRef], 아니면 null(노출 유지).
     */
    private fun maskableFieldRef(field: String): FieldRef? =
        when {
            field == ASSIGNEE_FIELD -> FieldRef(FieldKind.CORE, ASSIGNEE_ID_KEY)
            field.startsWith(CUSTOM_FIELD_PREFIX) ->
                FieldRef(FieldKind.CUSTOM, field.removePrefix(CUSTOM_FIELD_PREFIX))
            field in MASKABLE_CORE_FIELDS -> FieldRef(FieldKind.CORE, field)
            else -> null
        }

    // ──────────────────────────────────────────────────────────────────────────────
    // 삭제된 댓글 본문 마스킹 (FR-CO-02)
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * 소프트 삭제된 댓글의 수정 이력에서 **값·라벨만** 가린다.
     *
     * **왜 필요한가 — 두 결정이 겹치면 서로를 무력화한다.**
     * 이 제품은 (a) `SOFT_DELETE` 보유자가 남의 부적절한 댓글을 지울 수 있고(모더레이션),
     * (b) 댓글 본문 수정 시 이전·이후 본문을 감사 이력에 남긴다. 각각은 타당하지만 겹치면
     * 우회 경로가 생긴다 — 무해한 댓글을 쓴 뒤 부적절한 내용으로 **수정**하면 그 본문이
     * `issue_change_item.to_value` 에 영구 기록되고, 모더레이터가 댓글을 지워도 이력 탭에서
     * VIEW 권한자 전원이 계속 읽는다. 삭제 버튼이 사실상 아무것도 지우지 못하는 상태다.
     *
     * **왜 지우지 않고 가리는가.**
     * `issue_change_group`/`issue_change_item` 은 append-only 감사 이력이다(DATA.md §3).
     * [IssueChangeHistoryRepository] 에 삭제·수정 메서드가 아예 없는 것도 그 원칙의 표현이다.
     * 그래서 저장은 그대로 두고 **조회 시점에** 가린다. 행은 남아 있으므로 분쟁 시 관리자
     * 직접 조회로 복원할 수 있고(감사 추적성 보존), 노출만 차단된다.
     *
     * **왜 항목을 지우지 않고 값만 비우는가.**
     * "삭제된 댓글이 수정된 적 있다" 는 **사실 자체**는 감사 추적의 대상이라 남아야 한다.
     * 항목을 목록에서 빼면 그 사실까지 사라져 이력이 거짓말을 한다. 필드 수준 마스킹
     * ([maskItemIfInvisible])이 item 을 남긴 채 값만 null 로 만드는 것과 동일한 시맨틱이다.
     *
     * **fail-closed.**
     * 활성 목록에 없으면 가린다. 미존재 id 도 형식이 깨진 field 도 전부 "가림" 으로 수렴한다 —
     * 판정 불능일 때 노출하는 쪽으로 넘어지면 위 우회가 되살아난다.
     *
     * **[commentRepository] 의존은 선택적이 아니다.**
     * 마스킹이 없으면 D1 모더레이션이 우회되므로 타입으로 필수를 못 박는다. nullable 로 두면
     * 다음 사람이 "있으면 좋은 의존" 으로 읽고 주입을 빠뜨려도 컴파일이 통과하는데, 그 순간
     * 이 봉인이 조용히 사라진다. 판정 불능 상태 자체를 만들지 않는 편이 런타임 fallback 보다 낫다.
     *
     * **배치 1회.**
     * 이력 페이지 하나에 댓글 수정 항목이 여러 개 들어갈 수 있어 건별 조회는 N+1 이다.
     * 페이지 내 댓글 id 를 모아 [CommentRepository.findActiveIds] 를 **페이지당 한 번만** 호출한다.
     *
     * @param issueId 조회 중인 이슈 UUID. 댓글 소속 대조에 그대로 넘긴다.
     * @param groups 마스킹 전 변경 그룹 목록.
     * @return 삭제된 댓글의 본문이 가려진 그룹 목록. 댓글 항목이 없으면 원본 그대로.
     */
    private fun maskDeletedCommentBodies(
        issueId: UUID,
        groups: List<IssueChangeGroup>,
    ): List<IssueChangeGroup> {
        val commentItems = groups.flatMap { it.items }.filter { it.field.startsWith(COMMENT_FIELD_PREFIX) }
        if (commentItems.isEmpty()) return groups

        // 파싱 실패분은 여기서 빠지고, maskIfDeletedComment 가 "활성 목록에 없음" 으로 가린다.
        val activeIds =
            commentRepository.findActiveIds(commentItems.mapNotNull { parseCommentId(it.field) }.toSet(), issueId)

        return groups.map { group ->
            group.copy(items = group.items.map { item -> maskIfDeletedComment(item, activeIds) })
        }
    }

    /**
     * 댓글 변경 item 1건을 [activeIds] 기준으로 마스킹한다.
     *
     * 댓글 항목이 아니거나 활성 댓글이면 원본을 그대로 반환하고, 그 외(삭제됨·미존재·
     * commentId 파싱 실패)는 값·라벨 4종을 null 로 치환한다.
     */
    @Suppress("ReturnCount") // 비댓글 guard + 활성 guard + 마스킹 반환 — maskItemIfInvisible 과 동형
    private fun maskIfDeletedComment(
        item: IssueChangeItem,
        activeIds: Set<UUID>,
    ): IssueChangeItem {
        if (!item.field.startsWith(COMMENT_FIELD_PREFIX)) return item
        val commentId = parseCommentId(item.field)
        if (commentId != null && commentId in activeIds) return item
        return item.copy(fromValue = null, toValue = null, fromLabel = null, toLabel = null)
    }

    /**
     * `comment:{commentId}` field 키에서 댓글 UUID 를 추출한다.
     *
     * 형식이 어긋나면 null 을 반환한다 — 호출자가 이를 "판정 불능" 으로 보고 마스킹한다(fail-closed).
     */
    private fun parseCommentId(field: String): UUID? =
        try {
            UUID.fromString(field.removePrefix(COMMENT_FIELD_PREFIX))
        } catch (e: IllegalArgumentException) {
            log.warn("comment_history_field_unparsable field={} — 판정 불능이므로 마스킹합니다", field, e)
            null
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
