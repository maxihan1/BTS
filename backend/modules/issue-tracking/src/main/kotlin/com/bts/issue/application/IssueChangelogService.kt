// 이슈 변경 이력 조회 서비스 — VIEW 가드 재사용, actor 표시명 graceful degrade

package com.bts.issue.application

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
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        val offset = pageable.pageNumber * pageable.pageSize

        val groups = changeHistoryRepository.findByIssuePaged(issueId, limit, offset)
        val total = changeHistoryRepository.countByIssue(issueId)

        val displayNames = resolveActorNames(groups)

        val views = groups.map { group -> group.toView(displayNames) }
        return PageImpl(views, pageable, total)
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
