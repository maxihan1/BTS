// IssueUnfurlPort 의 issue-tracking BC 구현 — Slack unfurl 카드용 결합 fail-closed 조회 (FR-SL-03 Task 6)

package com.bts.issue.adapter

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssuePriority
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueUnfurlPort
import com.bts.shared.issue.IssueUnfurlView
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [IssueUnfurlPort] 의 issue-tracking BC 구현 (FR-SL-03 Task 6 · ADR D1 결합 fail-closed 포트).
 *
 * slack-integration BC 가 Slack `link_shared` 이벤트로 공유된 Atlas 이슈 URL을 unfurl 카드로
 * 되돌리기 전, 이 adapter 를 통해 열람 권한 확인 + 상세 조회를 원자적으로 수행한다.
 *
 * ## 게이트 2단 구성 — 왜 BROWSE(Project) 가 accessibleLevels 앞에 필요한가
 *
 * [BoardIssueLookupAdapter][com.bts.issue.adapter.outbound.board.BoardIssueLookupAdapter] 의
 * `accessibleLevels`+`existsVisibleIssue` 조합은 **보안등급(security level)만** 판정하며, 프로젝트
 * BROWSE 권한(멤버십)은 **호출자(보드 컨트롤러)가 별도로 강제한다는 전제**다(그 클래스 KDoc 참조).
 * Slack unfurl 흐름에는 그런 별도 호출자가 없다 — ADR D1이 "권한 확인 + 상세 조회를 어댑터 한 곳에서
 * 원자적으로 수행한다"고 명시한다. accessibleLevels+existsVisibleIssue 만 재사용하면, 보안 스킴이
 * 적용되지 않은(빠른경로 unrestricted=true) 대다수 프로젝트에서 프로젝트 멤버가 아닌 임의의 Atlas
 * 사용자도 이슈를 열람할 수 있는 **fail-open**이 발생한다.
 *
 * 따라서 [IssueEpicService.progress][com.bts.issue.epic.application.IssueEpicService.progress] 의
 * "BROWSE(Project) 진입 게이트" 패턴을 그대로 재사용해 accessibleLevels 게이트 앞에 추가한다.
 * BROWSE 는 보안등급을 판정하지 않으므로([IdentityAccessIssuePermissionResolver] 의 VIEW 전용
 * `passesSecurityGate` 와 달리) 두 게이트는 서로 다른 차원을 검사하며 새 보안 판정 경로를 만들지 않는다.
 *
 * ## fail-closed 계약
 *
 * 다음 중 하나라도 해당하면 `null` 을 반환한다 — "판정 불가 시 허용"으로는 절대 수렴하지 않는다.
 * - [issueKey] 형식 오류(`IssueKey` 정규식 위반).
 * - [viewerUserId] 가 대상 프로젝트에 대한 BROWSE 권한이 없음(비멤버 포함).
 * - `accessibleLevels` 가 빈/제한 집합이라 해당 이슈의 보안등급을 충족하지 못함.
 * - 이슈가 존재하지 않거나 소프트 삭제됨(권한 판정 통과 직후 재조회에서도 동일하게 적용).
 *
 * ## WorkflowStateCatalog 예외를 catch 하지 않는 이유
 *
 * 상태 라벨 해석에 사용하는 [WorkflowStateCatalog.listStates] 는
 * `@Transactional(propagation = Propagation.MANDATORY)`로, 이 메서드가 열어 둔 트랜잭션에 참여한다.
 * 예외를 여기서 catch-and-fallback 하면 Spring 트랜잭션 인터셉터가 이미 공유 트랜잭션을
 * rollback-only 로 마킹한 뒤라, 폴백 의도와 달리 커밋 시점에 `UnexpectedRollbackException` 이
 * 터진다(learnings `workflowstatecatalog-mandatory-rollback-poison`). 예외를 그대로 전파시키면
 * 이 메서드 자신의 트랜잭션이 정상적으로 롤백되고, 호출자(slack-integration `@Async` 핸들러)의
 * 링크별 try-catch 가 "예외=skip"으로 흡수해 카드 렌더만 생략된다(비동기 unfurl 실패로 자연 수렴).
 *
 * @see com.bts.issue.adapter.outbound.board.BoardIssueLookupAdapter
 */
@Component
class IssueUnfurlAdapter(
    private val issueRepository: IssueRepository,
    private val issueTypeRepository: IssueTypeRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val securityDirectory: IssueSecurityDirectory,
    workflowStateCatalog: WorkflowStateCatalog,
    userLookupPort: UserLookupPort,
) : IssueUnfurlPort {
    private val labelResolver = IssueUnfurlLabelResolver(workflowStateCatalog, userLookupPort)

    /**
     * [viewerUserId] 가 [issueKey] 를 볼 수 있으면 라벨이 해석된 unfurl 카드 스냅샷을, 없으면 `null` 을 반환한다.
     *
     * 게이트 통과 후에는 조회·라벨 해석만 수행한다 — fail-closed 계약과 게이트 근거는 클래스 KDoc 참조.
     * 라벨 해석(상태/우선순위/담당자)은 [IssueUnfurlLabelResolver] 가 전담하며, 원시 상태키·priority Int·
     * 담당자 UUID 는 반환값([IssueUnfurlView])에 노출하지 않는다.
     */
    @Transactional(readOnly = true)
    // 각 return은 독립적 fail-closed 게이트(파싱/BROWSE/보안등급/이슈·타입 부재) — 합치면 보안 판정 가독성 저하
    @Suppress("ReturnCount")
    override fun getVisibleIssueCard(
        issueKey: String,
        viewerUserId: UUID,
    ): IssueUnfurlView? {
        val key = parseIssueKeyOrNull(issueKey) ?: return null
        val projectKey = key.projectPrefix

        if (!permissionResolver.hasPermission(viewerUserId, IssuePermission.BROWSE, IssueScope.Project(projectKey))) {
            return null
        }

        val access = securityDirectory.accessibleLevels(viewerUserId, projectKey)
        if (!issueRepository.existsVisibleIssue(projectKey, key.value, viewerUserId, access)) {
            return null
        }

        val issue = issueRepository.findByKey(key) ?: return null
        val issueType = issueTypeRepository.findById(issue.typeId) ?: return null

        return labelResolver.toView(key, projectKey, issue, issueType)
    }

    /**
     * [issueKey] 문자열을 [IssueKey] 로 파싱한다. 형식 오류(정규식 위반)면 `null`(fail-closed).
     *
     * 예외를 던지지 않고 `null` 로 수렴시켜, Slack에서 붙여넣은 임의 문자열이 이슈 키처럼 보이지만
     * 실제로는 잘못된 형식일 때도 unfurl 이 조용히 생략되게 한다(파서 단계 통과 이력과 무관하게 방어).
     * 예외 message 는 사용자 붙여넣기 원문을 포함할 수 있어 로깅하지 않는다(NFR2) — `@Suppress("SwallowedException")` 근거.
     */
    @Suppress("SwallowedException")
    private fun parseIssueKeyOrNull(issueKey: String): IssueKey? =
        try {
            IssueKey(issueKey)
        } catch (invalid: IllegalArgumentException) {
            null
        }
}

/**
 * [IssueUnfurlAdapter] 가 게이트 통과 후 조회한 [Issue]/[IssueType] 을 [IssueUnfurlView] 로 변환하는
 * 라벨 매핑 전담 클래스 (FR-SL-03 Task 6 REFACTOR — 게이트 로직과 라벨 해석 관심사 분리).
 *
 * 원시 상태키(`currentStateKey`)·priority `Int`·담당자 `UUID` 를 여기서 표시 라벨로 해석해,
 * [IssueUnfurlAdapter.getVisibleIssueCard] 가 반환하는 [IssueUnfurlView] 밖으로 원시값이 새지 않게 한다.
 *
 * @property workflowStateCatalog 상태 키 → 표시 라벨 해석에 사용하는 cross-BC 포트(project-workflow 구현).
 * @property userLookupPort 담당자 UUID → 표시명 해석에 사용하는 cross-BC 포트(identity-access 구현).
 */
private class IssueUnfurlLabelResolver(
    private val workflowStateCatalog: WorkflowStateCatalog,
    private val userLookupPort: UserLookupPort,
) {
    /** [issue]/[issueType] 을 라벨이 모두 해석된 [IssueUnfurlView] 로 변환한다. */
    fun toView(
        key: IssueKey,
        projectKey: String,
        issue: Issue,
        issueType: IssueType,
    ): IssueUnfurlView =
        IssueUnfurlView(
            issueKey = key.value,
            summary = issue.summary,
            statusLabel = statusLabel(projectKey, issue.currentStateKey, issueType),
            priorityLabel = IssuePriority.fromNumber(issue.priority).displayName,
            assigneeDisplayName = assigneeDisplayName(issue.assigneeId),
        )

    /**
     * 워크플로우 상태 키를 표시 라벨로 해석한다. 매칭되는 상태가 없으면(정합성 불일치) 원본 키로 폴백한다.
     *
     * [WorkflowStateCatalog.listStates] 가 던지는 예외는 이 함수가 catch 하지 않는다 — [IssueUnfurlAdapter]
     * 클래스 KDoc "WorkflowStateCatalog 예외를 catch 하지 않는 이유" 참조.
     */
    private fun statusLabel(
        projectKey: String,
        currentStateKey: String,
        issueType: IssueType,
    ): String =
        workflowStateCatalog
            .listStates(ProjectKey.of(projectKey), issueType.key)
            .firstOrNull { it.key == currentStateKey }
            ?.name
            ?: currentStateKey

    /**
     * 담당자 UUID 를 표시명으로 해석한다. 미배정([assigneeId]가 `null`) 또는 표시명 미해석 시 `null`
     * (렌더 시 "미지정" 폴백은 [IssueUnfurlView.assigneeDisplayName] 소비측(Task 8 렌더러)의 책임).
     */
    private fun assigneeDisplayName(assigneeId: ActorId?): String? =
        assigneeId?.let { assignee -> userLookupPort.findDisplayNamesByIds(setOf(assignee.value))[assignee.value] }
}
