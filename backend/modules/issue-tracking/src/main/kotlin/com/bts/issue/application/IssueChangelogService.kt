// 이슈 변경 이력 조회 서비스 — VIEW 가드 재사용, actor 표시명 graceful degrade

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.fieldpermission.adapter.AlwaysAllowFieldPermissionResolver
import com.bts.issue.history.IssueChangeGroup
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.IssueChangeItem
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
    }

    /**
     * 이슈 변경 이력을 페이지 단위로 조회한다.
     *
     * 흐름.
     * 1. [IssueApplicationService.findByKey] 로 VIEW 권한 + 이슈 존재 검증.
     *    실패 시 [com.bts.issue.domain.IssueNotFoundException] 전파.
     * 2. [IssueChangeHistoryRepository.findByIssuePaged] + [IssueChangeHistoryRepository.countByIssue] 로 페이지 조회.
     * 3. actorId 집합을 [UserLookupPort.findDisplayNamesByIds] 로 일괄 해석. 실패 시 emptyMap graceful degrade.
     * 4. [ChangelogGroupView] 뷰 모델로 매핑해 [PageImpl] 반환.
     *
     * @param actor 조회 행위자. VIEW 권한 검증에 사용.
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

        val maskedGroups = maskInvisibleFields(actor, issue.projectKey, groups)

        val views = maskedGroups.map { group -> group.toView(displayNames) }
        return PageImpl(views, pageable, total)
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
