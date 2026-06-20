// 워크로그 유스케이스 오케스트레이션 서비스 — 권한·집계·자동차감·이력 (FR-TT-01)

package com.bts.issue.worklog.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueRepository
import com.bts.issue.worklog.domain.Worklog
import com.bts.issue.worklog.repository.WorklogRepository
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
 * 워크로그 목록 조회 결과 VO.
 *
 * [WorklogService.listForIssue] 가 반환하는 워크로그 목록 + 이슈 추정 요약.
 *
 * @property worklogs 활성 워크로그 목록 (started_at DESC).
 * @property originalEstimateSeconds 최초 추정 시간(초). null 이면 미추정.
 * @property timeSpentSeconds 누적 기록 작업 시간(초).
 * @property remainingEstimateSeconds 잔여 추정 시간(초). null 이면 미추정.
 */
data class WorklogListView(
    val worklogs: List<Worklog>,
    val originalEstimateSeconds: Int?,
    val timeSpentSeconds: Int,
    val remainingEstimateSeconds: Int?,
)

/**
 * 워크로그 유스케이스 오케스트레이션 서비스 (FR-TT-01).
 *
 * ## 권한 검증 순서
 * 이슈 존재 probe 방지를 위해 권한 검증을 이슈 조회보다 먼저 수행한다.
 * [com.bts.issue.watcher.application.IssueWatcherService] 와 동일 패턴.
 *
 * ## 트랜잭션
 * worklog write + 롤업 + 이력은 단일 트랜잭션(NFR1). 클래스 레벨 @Transactional 로 기본 적용.
 *
 * ## no-bump 원칙
 * 롤업(recomputeTimeSpent*) 은 issues.version 을 증가시키지 않는다.
 * 수동 추정 변경(T6 EstimatePatch)과 달리 worklog 롤업은 version 불변 경로다.
 *
 * ## author 제한
 * worklog update/delete 는 작성자(authorId == actor)만 허용한다.
 * 작성자 불일치 시 [IssueAccessDeniedException] (403).
 *
 * @param worklogRepository 워크로그 저장소.
 * @param issueRepository 이슈 조회 및 롤업 저장소.
 * @param permissionResolver 이슈 권한 판정 포트.
 * @param historyRecorder 이슈 변경 이력 recorder.
 * @param clock 현재 시각 공급자 (테스트 제어 가능).
 */
@Service
@Transactional
class WorklogService(
    private val worklogRepository: WorklogRepository,
    private val issueRepository: IssueRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val historyRecorder: IssueHistoryRecorder,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 워크로그를 추가한다.
     *
     * ## 실행 순서
     * 1. [IssuePermission.UPDATE] 검증 — 이슈 존재 probe 방지.
     * 2. 이슈 resolve([IssueRepository.findByKey]) — 미존재·소프트삭제 시 [IssueNotFoundException].
     * 3. [WorklogRepository.insert] — 워크로그 1건 삽입.
     * 4. 롤업.
     *    - [newRemainingEstimateSeconds] != null → [IssueRepository.recomputeTimeSpentSetRemaining].
     *    - null → [IssueRepository.recomputeTimeSpentWithDecrement].
     * 5. after = before.copy(timeSpentSeconds, remainingEstimateSeconds) 스냅샷 갱신.
     * 6. [IssueHistoryRecorder.record] — remaining 변경 시 이력 기록.
     *
     * @param actor 작업을 수행하는 행위자.
     * @param issueKey 워크로그를 추가할 이슈 키.
     * @param timeSpentSeconds 소요 시간(초, 양수).
     * @param startedAt 작업 시작 시각.
     * @param comment 선택적 코멘트.
     * @param newRemainingEstimateSeconds 잔여 추정 시간 직접 지정(초). null 이면 자동 차감.
     * @return 삽입된 [Worklog].
     * @throws [IssueAccessDeniedException] UPDATE 권한 미보유 시 (403).
     * @throws [IssueNotFoundException] 이슈 미존재·소프트 삭제 시 (404).
     */
    @Suppress("LongParameterList") // worklog 생성 입력 불가분 (모듈 선례)
    fun create(
        actor: ActorId,
        issueKey: IssueKey,
        timeSpentSeconds: Int,
        startedAt: Instant,
        comment: String?,
        newRemainingEstimateSeconds: Int?,
    ): Worklog {
        checkPermission(actor, issueKey, IssuePermission.UPDATE)

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)
        val before = issue

        val worklog =
            Worklog(
                id = UUID.randomUUID(),
                issueId = issue.id.value,
                authorId = actor.value,
                timeSpentSeconds = timeSpentSeconds,
                startedAt = startedAt,
                comment = comment,
                createdAt = Instant.now(clock),
                updatedAt = Instant.now(clock),
            )
        worklogRepository.insert(worklog)

        val result =
            if (newRemainingEstimateSeconds != null) {
                issueRepository.recomputeTimeSpentSetRemaining(issue.id.value, newRemainingEstimateSeconds)
            } else {
                issueRepository.recomputeTimeSpentWithDecrement(issue.id.value, timeSpentSeconds)
            }

        val after =
            before.copy(
                timeSpentSeconds = result.timeSpent,
                remainingEstimateSeconds = result.remaining,
            )

        historyRecorder.record(before = before, after = after, actor = actor, projectId = issue.projectId)

        log.info(
            "worklog_created issueKey={} worklogId={} actor={} timeSpent={} remaining={}",
            issueKey.value,
            worklog.id,
            actor.value,
            result.timeSpent,
            result.remaining,
        )
        return worklog
    }

    /**
     * 워크로그를 수정한다.
     *
     * ## 실행 순서
     * 1. [IssuePermission.UPDATE] 검증.
     * 2. 이슈 resolve — 미존재 404.
     * 3. [WorklogRepository.findById] — 미존재 404.
     * 4. worklog.issueId != issue.id → 404 (E4 이슈 불일치).
     * 5. worklog.authorId != actor → 403 (E5 작성자 한정).
     * 6. [WorklogRepository.update] — 필드 수정.
     * 7. [IssueRepository.recomputeTimeSpent] — time_spent 재집계 (remaining 미조정).
     *
     * @param actor 작업을 수행하는 행위자.
     * @param issueKey 워크로그가 속한 이슈 키.
     * @param worklogId 수정할 워크로그 UUID.
     * @param timeSpentSeconds 새 소요 시간(초). null 이면 기존 값 유지.
     * @param startedAt 새 시작 시각. null 이면 기존 값 유지.
     * @param comment 새 코멘트. null 이면 기존 값 유지.
     * @return 수정된 [Worklog].
     * @throws [IssueAccessDeniedException] UPDATE 권한 미보유 또는 타인 수정 시 (403).
     * @throws [IssueNotFoundException] 이슈·워크로그 미존재·이슈 불일치 시 (404).
     */
    @Suppress("LongParameterList", "ThrowsCount") // 입력 불가분 + 오류코드별 분리 throw
    fun update(
        actor: ActorId,
        issueKey: IssueKey,
        worklogId: UUID,
        timeSpentSeconds: Int?,
        startedAt: Instant?,
        comment: String?,
    ): Worklog {
        checkPermission(actor, issueKey, IssuePermission.UPDATE)

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)

        val existing = worklogRepository.findById(worklogId) ?: throw IssueNotFoundException(issueKey)

        // E4: worklog 가 이 이슈에 속하지 않으면 404
        if (existing.issueId != issue.id.value) {
            throw IssueNotFoundException(issueKey)
        }

        // E5: 작성자 한정 — actor 가 author 가 아니면 403
        if (existing.authorId != actor.value) {
            throw IssueAccessDeniedException(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
        }

        val newTimeSpent = timeSpentSeconds ?: existing.timeSpentSeconds
        val newStartedAt = startedAt ?: existing.startedAt
        val newComment = comment ?: existing.comment

        worklogRepository.update(
            id = worklogId,
            timeSpentSeconds = newTimeSpent,
            startedAt = newStartedAt,
            comment = newComment,
        )

        issueRepository.recomputeTimeSpent(issue.id.value)

        val updated =
            worklogRepository.findById(worklogId)
                ?: error("update 직후 worklog 미존재: $worklogId")

        log.info(
            "worklog_updated issueKey={} worklogId={} actor={}",
            issueKey.value,
            worklogId,
            actor.value,
        )
        return updated
    }

    /**
     * 워크로그를 소프트 삭제한다.
     *
     * ## 실행 순서
     * 1. [IssuePermission.UPDATE] 검증.
     * 2. 이슈 resolve — 미존재 404.
     * 3. [WorklogRepository.findById] — 미존재 404.
     * 4. worklog.issueId != issue.id → 404 (이슈 불일치).
     * 5. worklog.authorId != actor → 403 (작성자 한정).
     * 6. [WorklogRepository.softDelete].
     * 7. [IssueRepository.recomputeTimeSpent] — time_spent 재집계 (remaining 미복원).
     *
     * @param actor 작업을 수행하는 행위자.
     * @param issueKey 워크로그가 속한 이슈 키.
     * @param worklogId 삭제할 워크로그 UUID.
     * @throws [IssueAccessDeniedException] UPDATE 권한 미보유 또는 타인 삭제 시 (403).
     * @throws [IssueNotFoundException] 이슈·워크로그 미존재·이슈 불일치 시 (404).
     */
    @Suppress("ThrowsCount") // 권한(403)·이슈없음(404)·워크로그없음/불일치(404)·타인(403) 각기 다른 오류코드라 분리 throw가 명확
    fun delete(
        actor: ActorId,
        issueKey: IssueKey,
        worklogId: UUID,
    ) {
        checkPermission(actor, issueKey, IssuePermission.UPDATE)

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)

        val existing = worklogRepository.findById(worklogId) ?: throw IssueNotFoundException(issueKey)

        // 이슈 불일치 → 404
        if (existing.issueId != issue.id.value) {
            throw IssueNotFoundException(issueKey)
        }

        // 작성자 한정 → 403
        if (existing.authorId != actor.value) {
            throw IssueAccessDeniedException(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
        }

        worklogRepository.softDelete(worklogId)
        issueRepository.recomputeTimeSpent(issue.id.value)

        log.info(
            "worklog_deleted issueKey={} worklogId={} actor={}",
            issueKey.value,
            worklogId,
            actor.value,
        )
    }

    /**
     * 이슈의 워크로그 목록과 추정 요약을 조회한다.
     *
     * ## 실행 순서
     * 1. [IssuePermission.VIEW] 검증.
     * 2. 이슈 resolve — 미존재 404.
     * 3. [WorklogRepository.findByIssueId] — started_at DESC 정렬.
     * 4. 이슈의 original/timeSpent/remaining 요약과 함께 [WorklogListView] 반환.
     *
     * @param actor 조회를 수행하는 행위자.
     * @param issueKey 조회할 이슈 키.
     * @return [WorklogListView] — 워크로그 목록 + 추정 요약.
     * @throws [IssueAccessDeniedException] VIEW 권한 미보유 시 (403).
     * @throws [IssueNotFoundException] 이슈 미존재·소프트 삭제 시 (404).
     */
    @Transactional(readOnly = true)
    fun listForIssue(
        actor: ActorId,
        issueKey: IssueKey,
    ): WorklogListView {
        checkPermission(actor, issueKey, IssuePermission.VIEW)

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)
        val worklogs = worklogRepository.findByIssueId(issue.id.value)

        log.debug(
            "listForIssue issueKey={} actor={} count={}",
            issueKey.value,
            actor.value,
            worklogs.size,
        )
        return WorklogListView(
            worklogs = worklogs,
            originalEstimateSeconds = issue.originalEstimateSeconds,
            timeSpentSeconds = issue.timeSpentSeconds,
            remainingEstimateSeconds = issue.remainingEstimateSeconds,
        )
    }

    // ── private helpers ────────────────────────────────────────────────────────────

    /**
     * 권한을 검증한다.
     *
     * 이슈 존재 여부 probe 방지를 위해 이슈 조회 전 호출한다.
     * [com.bts.issue.watcher.application.IssueWatcherService.checkPermission] 과 동일 패턴.
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
}
