// 이슈 워처 추가/제거/조회 유스케이스 — 권한 분기(self=VIEW/타인=UPDATE) + 사용자 실재 검증 (FR-WT-01).

package com.bts.issue.watcher.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.repository.IssueRepository
import com.bts.issue.watcher.repository.IssueWatcherRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 단일 워처 항목 — userId + 표시명.
 *
 * [IssueWatcherService.listWatchers] 반환 목록의 원소.
 *
 * @property userId 워처 사용자 UUID.
 * @property displayName 표시명. [UserLookupPort.findDisplayNamesByIds] 조회 결과. 미존재 시 빈 문자열.
 */
data class WatcherEntry(
    val userId: UUID,
    val displayName: String,
)

/**
 * 워처 목록 조회 결과.
 *
 * @property watchers 워처 목록 ([WatcherEntry]).
 * @property count 총 워처 수.
 * @property isWatching 현재 actor 가 워처로 등록되어 있는지 여부.
 */
data class WatcherListResult(
    val watchers: List<WatcherEntry>,
    val count: Int,
    val isWatching: Boolean,
)

/**
 * 이슈 워처 유스케이스 조율 서비스.
 *
 * ## 권한 분기
 * - 본인(targetUserId == null 또는 actor 본인 UUID) → [IssuePermission.VIEW] 검증.
 * - 타인(다른 userId) → [IssuePermission.UPDATE] 검증.
 *
 * ## 권한 검증 순서
 * 이슈 존재 probe 방지를 위해 권한 검증을 이슈 조회보다 먼저 수행한다.
 * [IssueAttachmentService] 선례와 동일 패턴.
 *
 * ## 사용자 실재 검증
 * 타인 추가 시에만 [UserLookupPort.exists] 로 대상 사용자 실재를 확인한다.
 * 본인(actor)은 인증 단계에서 이미 실재가 확인되므로 검증하지 않는다.
 *
 * ## 멱등
 * [IssueWatcherRepository.add] 가 `ON CONFLICT DO NOTHING` 으로 멱등을 보장한다.
 * 서비스 계층에서 중복 여부를 사전 조회하지 않는다.
 *
 * @param watcherRepository 워처 저장소.
 * @param permissionResolver 이슈 권한 판정 포트.
 * @param userLookupPort 사용자 실재 검증 및 표시명 조회 포트.
 * @param issueRepository 이슈 키 → UUID 변환 및 존재 확인.
 */
@Service
class IssueWatcherService(
    private val watcherRepository: IssueWatcherRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val userLookupPort: UserLookupPort,
    private val issueRepository: IssueRepository,
    private val archiveGuard: ProjectArchiveGuard,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 워처를 추가한다.
     *
     * ## 실행 순서
     * 1. 권한 검증 (self=VIEW / 타인=UPDATE) — 이슈 존재 probe 차단.
     * 2. 타인 추가 시 [UserLookupPort.exists] 검증.
     * 3. 이슈 키 → UUID 변환 (미존재 시 [IssueNotFoundException]).
     * 4. [IssueWatcherRepository.add] 호출 (ON CONFLICT DO NOTHING 으로 멱등).
     *
     * @param issueKey 워처를 추가할 이슈 키.
     * @param actor 작업을 수행하는 행위자.
     * @param targetUserId 워처로 추가할 사용자 UUID. null 이면 actor 본인.
     * @throws IssueAccessDeniedException 권한 미보유 시.
     * @throws WatcherUserNotFoundException 타인 추가 시 대상 사용자 미존재 시 (422).
     * @throws IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시 (404).
     */
    @Transactional
    fun watch(
        issueKey: IssueKey,
        actor: ActorId,
        targetUserId: UUID?,
    ) {
        val watcherId = targetUserId ?: actor.value
        val isSelf = watcherId == actor.value
        checkPermission(actor, issueKey, isSelf)
        archiveGuard.checkByIssue(issueKey)

        if (!isSelf) {
            if (!userLookupPort.exists(watcherId)) {
                log.warn("watch 대상 사용자 미존재 — issueKey={}", issueKey.value)
                throw WatcherUserNotFoundException(watcherId)
            }
        }

        val issueId = resolveIssueId(issueKey)
        log.debug("watch issueKey={} userId={}", issueKey.value, watcherId)
        watcherRepository.add(issueId, watcherId)
    }

    /**
     * 이슈 워처를 제거한다.
     *
     * ## 권한 분기
     * - self(targetUserId == actor) → [IssuePermission.VIEW].
     * - 타인 → [IssuePermission.UPDATE].
     *
     * 존재하지 않는 워처 제거는 [IssueWatcherRepository.remove] 가 false 반환으로 조용히 처리한다.
     *
     * @param issueKey 워처를 제거할 이슈 키.
     * @param actor 작업을 수행하는 행위자.
     * @param targetUserId 제거할 워처 사용자 UUID.
     * @throws IssueAccessDeniedException 권한 미보유 시.
     * @throws IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시.
     */
    @Transactional
    fun unwatch(
        issueKey: IssueKey,
        actor: ActorId,
        targetUserId: UUID,
    ) {
        val isSelf = targetUserId == actor.value
        checkPermission(actor, issueKey, isSelf)
        archiveGuard.checkByIssue(issueKey)

        val issueId = resolveIssueId(issueKey)
        log.debug("unwatch issueKey={} userId={}", issueKey.value, targetUserId)
        watcherRepository.remove(issueId, targetUserId)
    }

    /**
     * 이슈 워처 목록을 조회한다.
     *
     * @param issueKey 조회할 이슈 키.
     * @param actor 조회를 수행하는 행위자.
     * @return [WatcherListResult] — 워처 목록(userId+displayName) + count + isWatching(actor 기준).
     * @throws IssueAccessDeniedException VIEW 권한 미보유 시.
     * @throws IssueNotFoundException 이슈 미존재 또는 소프트 삭제 시.
     */
    @Transactional(readOnly = true)
    fun listWatchers(
        issueKey: IssueKey,
        actor: ActorId,
    ): WatcherListResult {
        checkPermission(actor, issueKey, isSelf = true) // 목록 조회는 VIEW 권한

        val issueId = resolveIssueId(issueKey)
        val rows = watcherRepository.listByIssue(issueId)
        val count = watcherRepository.countByIssue(issueId)
        val isWatching = watcherRepository.existsForUser(issueId, actor.value)

        val userIds = rows.map { it.userId }.toSet()
        val displayNames = userLookupPort.findDisplayNamesByIds(userIds)

        val watchers =
            rows.map { row ->
                WatcherEntry(
                    userId = row.userId,
                    displayName = displayNames[row.userId] ?: "",
                )
            }

        log.debug("listWatchers issueKey={} count={} isWatching={}", issueKey.value, count, isWatching)
        return WatcherListResult(watchers = watchers, count = count, isWatching = isWatching)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 권한을 검증한다.
     *
     * - self=true → [IssuePermission.VIEW] 검증.
     * - self=false → [IssuePermission.UPDATE] 검증.
     * 권한 미보유 시 [IssueAccessDeniedException].
     *
     * 이슈 존재 여부 probe 방지를 위해 이슈 조회 전 호출한다.
     *
     * @param actor 행위자.
     * @param issueKey 이슈 키 (scope 생성에 사용).
     * @param isSelf true 이면 self(VIEW), false 이면 타인(UPDATE).
     */
    private fun checkPermission(
        actor: ActorId,
        issueKey: IssueKey,
        isSelf: Boolean,
    ) {
        val permission = if (isSelf) IssuePermission.VIEW else IssuePermission.UPDATE
        val scope = IssueScope.Issue(issueKey.value)
        val allowed = permissionResolver.hasPermission(actor.value, permission, scope)
        if (!allowed) {
            throw IssueAccessDeniedException(actor, permission, scope)
        }
    }

    /**
     * 이슈 키 → UUID 변환. 미존재/소프트 삭제 시 [IssueNotFoundException].
     */
    private fun resolveIssueId(issueKey: IssueKey): UUID {
        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)
        return issue.id.value
    }
}
