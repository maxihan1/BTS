// 변경 이력 항목 마스킹 협력자 — 필드 권한(FR-PM-07)·삭제 댓글 본문(FR-CO-02) 판정을 이력 조회 경로들이 공유한다

package com.bts.issue.application

import com.bts.issue.comment.repository.CommentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.fieldpermission.adapter.AlwaysAllowFieldPermissionResolver
import com.bts.issue.history.IssueChangeItem
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * `issue_change_item` 을 노출하는 **모든** 조회 경로가 공유하는 마스킹 협력자.
 *
 * ## 왜 협력자로 뽑았나
 * 같은 행을 읽는 경로가 둘이다 — 이슈 단건 이력([IssueChangelogService])과 프로젝트 활동 피드
 * ([com.bts.issue.summary.application.ProjectSummaryService]). 판정을 각자 복사해 두면
 * 「두 목록이 서로를 확인하지 않는」 형태가 되어, 한쪽에 마스킹 대상 필드가 추가될 때 다른 쪽이
 * 조용히 샌다. 마스킹 집합·판정 규칙을 여기 한 곳에만 둔다.
 *
 * ## 무엇을 가리나
 * 1. **필드 수준 권한(FR-PM-07)** — 단건 상세가 현재값을 가리는 필드
 *    (`description·environment·impact·labels·summary·priority` + `assignee` + `customField:*`)는
 *    과거 from/to 값과 박제 라벨도 가린다. 아니면 "현재값은 가리는데 과거값은 보이는" 비대칭이 남는다.
 * 2. **삭제된 댓글 본문(FR-CO-02)** — 소프트 삭제된 댓글의 수정 이력 본문. 이력은 append-only 라
 *    행을 지울 수 없으므로 조회 시점에 가린다. 아니면 모더레이션이 우회된다.
 *
 * 두 경우 모두 **항목은 남기고 값 4종만 null 로** 치환한다 — "변경이 있었다"는 사실 자체는 감사
 * 추적 대상이라 남아야 한다.
 *
 * ## 조회 횟수
 * [planFor] 가 한 번 돌 때 필드 권한 1회 + 활성 댓글 1회, 총 2쿼리다. 이력 페이지든 프로젝트
 * 피드든 항목 수와 무관하게 고정이다(N+1 차단).
 *
 * @param issueRepository 프로젝트 키 → id 해석용.
 * @param commentRepository 활성 댓글 배치 판정용. 기본값이 없다 — 마스킹이 빠지면 모더레이션이
 *        우회되므로 "있으면 좋은 의존" 으로 읽히면 안 된다.
 * @param fieldPermissionResolver 필드 수준 권한 판정 포트. 기본값은 Spring 이 관리하지 않는 단위 테스트
 *        컨텍스트 호환용이며, prod 에서는 `IdentityAccessFieldPermissionResolver` 가 타입으로 주입된다.
 */
@Component
class IssueChangeItemMasker(
    private val issueRepository: IssueRepository,
    private val commentRepository: CommentRepository,
    private val fieldPermissionResolver: FieldPermissionResolver = AlwaysAllowFieldPermissionResolver(),
) {
    /**
     * 항목 전체를 훑어 마스킹 판정표를 만든다.
     *
     * @param actor 조회 행위자.
     * @param projectKey 항목이 속한 프로젝트 키. 필드 권한은 항상 프로젝트 스코프다.
     * @param itemsByIssue `이슈 id → 그 이슈의 변경 항목`. 댓글 소속 대조에 이슈 id 가 필요하다.
     * @return 항목마다 [IssueChangeItemMask.apply] 로 적용할 판정표.
     */
    fun planFor(
        actor: ActorId,
        projectKey: String,
        itemsByIssue: Map<UUID, List<IssueChangeItem>>,
    ): IssueChangeItemMask =
        IssueChangeItemMask(
            invisibleFields = resolveInvisibleFields(actor, projectKey, itemsByIssue.values.flatten()),
            activeCommentOwners = resolveActiveCommentOwners(itemsByIssue),
        )

    /**
     * 마스킹 대상 필드 중 [actor] 에게 **보이지 않는** 것을 고른다.
     *
     * **projectId 미해석은 원본 유지(폴백).** 단건 상세([IssueApplicationService] 의 필드 마스킹)와
     * 같은 방어적 선택이다. 두 경로의 폴백이 어긋나면 같은 사용자가 화면에 따라 다른 것을 보게 된다.
     */
    @Suppress("ReturnCount") // candidates 없음 guard + projectId 미해석 guard + 판정 — 의도적 조기 반환
    private fun resolveInvisibleFields(
        actor: ActorId,
        projectKey: String,
        items: List<IssueChangeItem>,
    ): Set<FieldRef> {
        val candidates = items.mapNotNull { maskableFieldRef(it.field) }.toSet()
        if (candidates.isEmpty()) return emptySet()
        val projectId = issueRepository.findProjectIdByKey(projectKey) ?: return emptySet()
        return candidates - fieldPermissionResolver.visibleFields(actor.value, projectId, candidates)
    }

    /**
     * 항목에 등장하는 댓글 id 를 이슈별로 모아 **한 번에** 활성 여부를 묻는다.
     *
     * 소속 대조는 [CommentRepository.findActiveOwners] 의 `WHERE` 안에서 이뤄진다 —
     * 호출자의 성실성에 맡기지 않는다([CommentRepository] 클래스 KDoc).
     */
    private fun resolveActiveCommentOwners(itemsByIssue: Map<UUID, List<IssueChangeItem>>): Map<UUID, UUID> {
        val idsByIssue =
            itemsByIssue
                .mapValues { (_, items) -> commentIdsOf(items) }
                .filterValues { it.isNotEmpty() }
        if (idsByIssue.isEmpty()) return emptyMap()
        return commentRepository.findActiveOwners(idsByIssue)
    }

    private fun commentIdsOf(items: List<IssueChangeItem>): Set<UUID> =
        items.asSequence()
            .map { it.field }
            .filter { it.startsWith(COMMENT_FIELD_PREFIX) }
            .mapNotNull { parseCommentId(it) }
            .toSet()
}

/**
 * [IssueChangeItemMasker.planFor] 가 조회 결과로 굳힌 마스킹 판정표.
 *
 * 조회는 이미 끝났으므로 [apply] 는 순수 함수다 — 항목이 몇 개든 추가 쿼리가 없다.
 *
 * @property invisibleFields 마스킹 대상이면서 행위자에게 보이지 않는 필드 참조.
 * @property activeCommentOwners `활성 댓글 id → 소속 이슈 id`. 여기 없는 댓글은 삭제·미존재로 본다.
 */
class IssueChangeItemMask internal constructor(
    private val invisibleFields: Set<FieldRef>,
    private val activeCommentOwners: Map<UUID, UUID>,
) {
    /**
     * 변경 항목 1건에 판정을 적용한다.
     *
     * @param issueId 항목이 기록된 이슈 UUID. 댓글 소속 대조에 쓴다.
     * @param item 원본 항목.
     * @return 가릴 필요가 없으면 원본 그대로, 아니면 값·라벨 4종이 null 로 치환된 사본.
     */
    fun apply(
        issueId: UUID,
        item: IssueChangeItem,
    ): IssueChangeItem =
        if (isInvisibleField(item.field) || isDeletedComment(issueId, item.field)) {
            item.copy(fromValue = null, toValue = null, fromLabel = null, toLabel = null)
        } else {
            item
        }

    /**
     * 한 이슈에 속한 항목 목록 전체에 [apply] 를 적용한다.
     *
     * @param issueId 항목들이 기록된 이슈 UUID.
     * @param items 원본 항목 목록.
     * @return 같은 순서·같은 개수의 마스킹된 목록. 항목을 제거하지 않는다.
     */
    fun applyAll(
        issueId: UUID,
        items: List<IssueChangeItem>,
    ): List<IssueChangeItem> = items.map { apply(issueId, it) }

    private fun isInvisibleField(field: String): Boolean {
        val ref = maskableFieldRef(field) ?: return false
        return ref in invisibleFields
    }

    /**
     * 댓글 본문 항목이 **가려야 할 상태**인지 판정한다. 판정 불능은 전부 "가림" 으로 수렴한다(fail-closed).
     *
     * 노출하는 쪽으로 넘어지면 삭제된 댓글의 본문이 이력에 그대로 남아 모더레이션이 무력화된다.
     */
    @Suppress("ReturnCount") // 비댓글 guard + 파싱 실패 guard + 소속 대조 — 의도적 조기 반환
    private fun isDeletedComment(
        issueId: UUID,
        field: String,
    ): Boolean {
        if (!field.startsWith(COMMENT_FIELD_PREFIX)) return false
        val commentId = parseCommentId(field) ?: return true
        return activeCommentOwners[commentId] != issueId
    }
}

private val maskerLog = LoggerFactory.getLogger(IssueChangeItemMasker::class.java)

/** 이력의 assignee 변경 item field 키. */
private const val ASSIGNEE_FIELD = "assignee"

/** 단건 마스킹 candidate 의 담당자 CORE 키([IssueApplicationService] 의 코어 candidate 와 일치). */
private const val ASSIGNEE_ID_KEY = "assigneeId"

/** 커스텀 필드 item field prefix(`IssueChangeDetector` 와 동일). */
private const val CUSTOM_FIELD_PREFIX = "customField:"

/**
 * 댓글 본문 변경 item field prefix.
 *
 * 기록 측([IssueHistoryRecorder.COMMENT_FIELD_PREFIX])과 조회 측이 같은 상수를 봐야
 * 한쪽만 바뀌어 마스킹이 조용히 무력화되는 일이 없다.
 */
private const val COMMENT_FIELD_PREFIX = IssueHistoryRecorder.COMMENT_FIELD_PREFIX

/**
 * 단건 상세가 마스킹하는 코어 필드 중 이력 field 키와 동일명인 것.
 *
 * assignee(→assigneeId)·customField 는 별도 매핑이므로 제외한다.
 * status·type·resolution·securityLevel·components·affectsVersions·fixVersions·lifecycle 은
 * 단건도 마스킹하지 않으므로 이력도 노출 유지 — 이 집합에 넣지 않는다.
 */
private val MASKABLE_CORE_FIELDS =
    setOf("description", "environment", "impact", "labels", "summary", "priority")

/**
 * 이력 item 의 field 키를 단건 마스킹 candidate 와 동일한 [FieldRef] 로 매핑한다.
 *
 * 마스킹 대상이 아닌 필드는 null 을 반환해 항상 노출시킨다 — 단건 상세도 그 필드들은 마스킹하지
 * 않으므로 정합이다.
 */
private fun maskableFieldRef(field: String): FieldRef? =
    when {
        field == ASSIGNEE_FIELD -> FieldRef(FieldKind.CORE, ASSIGNEE_ID_KEY)
        field.startsWith(CUSTOM_FIELD_PREFIX) ->
            FieldRef(FieldKind.CUSTOM, field.removePrefix(CUSTOM_FIELD_PREFIX))
        field in MASKABLE_CORE_FIELDS -> FieldRef(FieldKind.CORE, field)
        else -> null
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
        maskerLog.warn("comment_history_field_unparsable field={} — 판정 불능이므로 마스킹합니다", field, e)
        null
    }
