// 에픽-자식 연결/해제/조회 서비스 — 불변식 5종 + 권한 + changelog 기록 (FR-EP-01 Task 5)

package com.bts.issue.epic.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.epic.domain.EpicChildAlreadyLinkedException
import com.bts.issue.epic.domain.EpicChildCrossProjectException
import com.bts.issue.epic.domain.EpicChildInvalidTypeException
import com.bts.issue.epic.domain.EpicChildNotFoundException
import com.bts.issue.epic.domain.EpicChildSelfReferenceException
import com.bts.issue.epic.domain.EpicTargetNotEpicException
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 에픽 이슈 유형의 hierarchyLevel (V005 seed 기준). */
private const val EPIC_HIERARCHY_LEVEL = 1

/** 에픽에 연결 가능한 이슈의 hierarchyLevel (Story/Task/Bug). */
private const val CHILD_HIERARCHY_LEVEL = 0

/**
 * 에픽-자식 연결/해제/조회 서비스 (FR-EP-01 Task 5).
 *
 * ## 보안 원칙
 * - **fail-closed**: 모든 생성자 인자는 default 없는 non-null 주입.
 *   prod 빈 미주입 시 부팅 실패로 AlwaysAllow default 우회를 구조적으로 차단한다.
 * - **존재 probe 방지**: UPDATE(child) 권한 검증이 이슈 조회보다 선행한다.
 * - **N1 보안**: epic 미존재/소프트삭제는 404 (존재 숨김 — 403 금지).
 * - **BROWSE 진입 게이트**: listChildren 은 Project scope BROWSE 검사로 VIEW_ISSUE 매트릭스를 위임한다.
 *
 * ## 불변식 (connect)
 * 1. UPDATE(child, Issue scope) 권한
 * 2. child 존재 → 404
 * 3. epic 존재 → 404 (보안 N1)
 * 4. self 참조 → 422
 * 5. child.hierarchyLevel ≠ 0 → 422
 * 6. epic.hierarchyLevel ≠ 1 → 422
 * 7. cross-project → 422
 * 8. child.epicId ≠ null → 409
 *
 * @param permissionResolver 이슈 권한 판정 포트 (fail-closed — default 없음).
 * @param securityDirectory 이슈 보안 등급 조회 포트 (fail-closed — default 없음).
 * @param issueRepository 이슈 저장소.
 * @param issueTypeRepository 이슈 타입 저장소 (hierarchyLevel 조회).
 * @param historyRecorder 이슈 변경 이력 기록 facade.
 */
@Service
@Transactional
class IssueEpicService(
    private val permissionResolver: IssuePermissionResolver,
    private val securityDirectory: IssueSecurityDirectory,
    private val issueRepository: IssueRepository,
    private val issueTypeRepository: IssueTypeRepository,
    private val historyRecorder: IssueHistoryRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [childKey] 이슈를 [epicKey] 에픽에 연결한다.
     *
     * 검증 순서 (명세 §Task5 connect 불변식 1~8).
     *
     * @param epicKey 연결할 에픽 이슈 키.
     * @param childKey 에픽에 연결할 자식 이슈 키.
     * @param actor 작업을 수행하는 행위자.
     * @throws IssueAccessDeniedException UPDATE(child) 권한 미보유 시 (403).
     * @throws EpicChildNotFoundException child 또는 epic 미존재·소프트삭제 시 (404).
     * @throws EpicChildSelfReferenceException child 와 epic 이 동일 이슈 시 (422).
     * @throws EpicChildInvalidTypeException child 의 hierarchyLevel 이 0 이 아닐 때 (422).
     * @throws EpicTargetNotEpicException epic 의 hierarchyLevel 이 1 이 아닐 때 (422).
     * @throws EpicChildCrossProjectException child 와 epic 이 다른 프로젝트 소속 시 (422).
     * @throws EpicChildAlreadyLinkedException child 가 이미 에픽에 연결되어 있을 때 (409).
     */
    @Suppress("ThrowsCount") // 불변식 8종이 각기 다른 HTTP 상태코드를 가지므로 단계별 명시 throw 불가피
    fun connect(
        epicKey: IssueKey,
        childKey: IssueKey,
        actor: ActorId,
    ) {
        // 1. UPDATE(child) 권한 선행 — 이슈 존재 probe 방지
        checkUpdatePermission(actor, childKey)

        // 2. child 조회 — 미존재/소프트삭제 시 404
        val child =
            issueRepository.findByKey(childKey)
                ?: run {
                    log.debug("connect: child not found key={}", childKey.value)
                    throw EpicChildNotFoundException()
                }

        // 3. epic 조회 — 미존재/소프트삭제 시 404 (보안 N1 — 존재 숨김, 403 금지)
        val epic =
            issueRepository.findByKey(epicKey)
                ?: run {
                    log.debug("connect: epic not found or soft-deleted key={}", epicKey.value)
                    throw EpicChildNotFoundException()
                }

        // 4. self 참조 검사
        if (child.id == epic.id) {
            log.debug("connect: self-reference childKey={} epicKey={}", childKey.value, epicKey.value)
            throw EpicChildSelfReferenceException()
        }

        // 5. child 유형 검사 (hierarchyLevel == 0 이어야 함)
        val childType = issueTypeRepository.findById(child.typeId)
        val childLevel = childType?.hierarchyLevel ?: 0
        if (childLevel != CHILD_HIERARCHY_LEVEL) {
            log.debug("connect: invalid child type childKey={} hierarchyLevel={}", childKey.value, childLevel)
            throw EpicChildInvalidTypeException()
        }

        // 6. epic 유형 검사 (hierarchyLevel == 1 이어야 함)
        val epicType = issueTypeRepository.findById(epic.typeId)
        val epicLevel = epicType?.hierarchyLevel ?: 0
        if (epicLevel != EPIC_HIERARCHY_LEVEL) {
            log.debug("connect: target is not an epic epicKey={} hierarchyLevel={}", epicKey.value, epicLevel)
            throw EpicTargetNotEpicException()
        }

        // 7. cross-project 검사
        if (child.projectId != epic.projectId) {
            log.debug(
                "connect: cross-project childProjectId={} epicProjectId={}",
                child.projectId,
                epic.projectId,
            )
            throw EpicChildCrossProjectException()
        }

        // 8. 이미 연결됨 검사
        if (child.epicId != null) {
            log.debug("connect: already linked childKey={} existingEpicId={}", childKey.value, child.epicId)
            throw EpicChildAlreadyLinkedException()
        }

        // 연결 실행
        issueRepository.updateEpic(child.id.value, epic.id.value)

        // G1: changelog 기록
        val after = child.copy(epicId = epic.id.value)
        historyRecorder.record(before = child, after = after, actor = actor, projectId = child.projectId)

        log.info(
            "epic_connected epicKey={} childKey={} actor={}",
            epicKey.value,
            childKey.value,
            actor.value,
        )
    }

    /**
     * [childKey] 이슈를 [epicKey] 에픽에서 연결 해제한다.
     *
     * @param epicKey 연결 해제할 에픽 이슈 키.
     * @param childKey 연결 해제할 자식 이슈 키.
     * @param actor 작업을 수행하는 행위자.
     * @throws IssueAccessDeniedException UPDATE(child) 권한 미보유 시 (403).
     * @throws EpicChildNotFoundException child 미존재 또는 child 가 이 epic 에 속하지 않을 때 (404).
     */
    @Suppress("ThrowsCount") // UPDATE(403)·child없음(404)·epic없음(404)·epicId불일치(404) — 각 단계 명시 throw
    fun disconnect(
        epicKey: IssueKey,
        childKey: IssueKey,
        actor: ActorId,
    ) {
        // UPDATE(child) 권한 선행
        checkUpdatePermission(actor, childKey)

        // child 조회
        val child =
            issueRepository.findByKey(childKey)
                ?: run {
                    log.debug("disconnect: child not found key={}", childKey.value)
                    throw EpicChildNotFoundException()
                }

        // epic 조회 (보안 N1 — 미존재도 404)
        val epic =
            issueRepository.findByKey(epicKey)
                ?: run {
                    log.debug("disconnect: epic not found key={}", epicKey.value)
                    throw EpicChildNotFoundException()
                }

        // child 의 epicId 가 이 epic 이 아니면 404
        if (child.epicId != epic.id.value) {
            log.debug(
                "disconnect: child not linked to this epic childKey={} childEpicId={} requestedEpicId={}",
                childKey.value,
                child.epicId,
                epic.id.value,
            )
            throw EpicChildNotFoundException()
        }

        // 연결 해제 실행
        issueRepository.updateEpic(child.id.value, null)

        // G1: changelog 기록
        val after = child.copy(epicId = null)
        historyRecorder.record(before = child, after = after, actor = actor, projectId = child.projectId)

        log.info(
            "epic_disconnected epicKey={} childKey={} actor={}",
            epicKey.value,
            childKey.value,
            actor.value,
        )
    }

    /**
     * [epicKey] 에픽에 속한 자식 이슈 목록을 조회한다.
     *
     * BROWSE(에픽 프로젝트, Project scope) 로 진입을 검사한다.
     * VIEW_ISSUE 매트릭스는 BROWSE 게이트 + accessibleLevels 보안 등급 SQL 푸시다운이 담당한다.
     * [board 동형 패턴: IssueApplicationService.listIssues 참조].
     *
     * @param epicKey 자식 이슈를 조회할 에픽 이슈 키.
     * @param actor 조회를 수행하는 행위자.
     * @return 에픽에 속한 활성 이슈 목록 (created_at 오름차순).
     * @throws IssueAccessDeniedException BROWSE(Project) 권한 미보유 시 (403).
     * @throws EpicChildNotFoundException epic 미존재·소프트삭제 시 (404).
     */
    @Transactional(readOnly = true)
    fun listChildren(
        epicKey: IssueKey,
        actor: ActorId,
    ): List<Issue> {
        val projectKey = epicKey.projectPrefix

        // BROWSE(Project) 진입 게이트 — VIEW_ISSUE 매트릭스 위임 (security C1·N3)
        val browseAllowed =
            permissionResolver.hasPermission(
                actor.value,
                IssuePermission.BROWSE,
                IssueScope.Project(projectKey),
            )
        if (!browseAllowed) {
            throw IssueAccessDeniedException(actor, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        }

        // epic 조회 — 미존재/소프트삭제 시 404
        val epic =
            issueRepository.findByKey(epicKey)
                ?: run {
                    log.debug("listChildren: epic not found key={}", epicKey.value)
                    throw EpicChildNotFoundException()
                }

        // 보안 등급 필터 조회 (accessibleLevels 단독≠VIEW 매트릭스 — BROWSE 진입이 담당)
        val access = securityDirectory.accessibleLevels(actor.value, projectKey)

        val children =
            issueRepository.findEpicChildren(
                epicId = epic.id.value,
                actor = actor.value,
                access = access,
                projectKey = projectKey,
            )

        log.debug(
            "listChildren epicKey={} actor={} count={}",
            epicKey.value,
            actor.value,
            children.size,
        )
        return children
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * child 이슈에 대한 UPDATE 권한을 검증한다.
     *
     * 이슈 존재 probe 방지를 위해 이슈 조회 전 호출한다.
     * [WorklogService.checkPermission] 과 동일 패턴.
     */
    private fun checkUpdatePermission(
        actor: ActorId,
        issueKey: IssueKey,
    ) {
        val scope = IssueScope.Issue(issueKey.value)
        val allowed = permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, scope)
        if (!allowed) {
            throw IssueAccessDeniedException(actor, IssuePermission.UPDATE, scope)
        }
    }
}
