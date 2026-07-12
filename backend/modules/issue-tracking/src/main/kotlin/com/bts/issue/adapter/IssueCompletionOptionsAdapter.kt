// IssueCompletionOptionsPort 의 issue-tracking BC 구현 — Slack 완료 모달용 결합 fail-closed 조회 (FR-SL-05 Task 3)

package com.bts.issue.adapter

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.shared.issue.DoneTransition
import com.bts.shared.issue.IssueCompletionOptions
import com.bts.shared.issue.IssueCompletionOptionsPort
import com.bts.shared.issue.ResolutionOption
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityDirectory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 전이 목표 상태 카테고리 중 "완료" 판정 기준 문자열 ([com.bts.shared.workflow.AvailableTransitionView.toCategory] 계약). */
private const val DONE_CATEGORY = "DONE"

/**
 * [IssueCompletionOptionsPort] 의 issue-tracking BC 구현 (FR-SL-05 Task 3).
 *
 * slack-integration BC 가 "완료로 표시" 인터랙션(완료 모달)을 열 때, 이 adapter 를 통해 열람 권한
 * 확인 + OCC(낙관적 락) 버전 + DONE 전이 후보 + resolution 목록을 원자적으로 조회한다.
 *
 * ## 가시성 게이트 — [IssueUnfurlAdapter] 동형 재사용
 *
 * BROWSE(Project) 게이트 + `accessibleLevels`/`existsVisibleIssue` 보안등급 게이트 2단 구성을
 * [IssueUnfurlAdapter.getVisibleIssueCard] 와 동일하게 그대로 복제한다 — 근거([IssueUnfurlAdapter]
 * 클래스 KDoc "게이트 2단 구성" 절 참조)도 동일하다. Slack 인터랙션 흐름에는 별도로 BROWSE 를 강제하는
 * 호출자가 없으므로, 보안 스킴이 적용되지 않은(빠른경로 unrestricted=true) 대다수 프로젝트에서
 * 프로젝트 비멤버까지 완료 옵션을 조회할 수 있는 fail-open 을 막기 위해 두 게이트를 모두 통과해야 한다.
 *
 * 가시성 판정([existsVisibleIssue])과 상세 조회(availableTransitions)는 항상 같은 [viewerUserId] 로
 * 수행한다 — 판정과 조회의 행위자가 갈리면 게이트가 무력화(vacuous)되거나 fail-open 이 발생한다.
 *
 * ## availableTransitions 재사용 — DONE 카테고리만 필터
 *
 * 전이 후보는 [IssueApplicationService.availableTransitions] 를 그대로 호출해 얻는다(워크플로우 키
 * 결정 + `WorkflowTransitionPort` 호출 로직 중복 방지). 반환된 [com.bts.shared.workflow.AvailableTransitionView]
 * 중 `toCategory` 가 [DONE_CATEGORY] 인 항목만 [DoneTransition] 으로 변환한다 — Slack 완료 모달은
 * "완료" 로 이어지는 전이만 노출해야 하기 때문이다.
 *
 * 이 호출은 자체적으로 VIEW 권한 게이트(`assertViewIssueOrNotFound`)를 한 번 더 통과해야 한다 — BROWSE
 * 게이트와는 다른 차원의 검사([IssueUnfurlAdapter] 클래스 KDoc 참조)이므로 이중 게이트가 의도된 설계다.
 * 이 호출이 예외(`IssueNotFoundException`/`IssueWorkflowNotConfiguredException` 등)를 던지면 catch 하지
 * 않고 그대로 전파한다 — [IssueApplicationService] 는 클래스 레벨 `@Transactional` 로 이 adapter 의
 * 읽기 전용 트랜잭션에 참여(participate)하므로, 여기서 catch-and-fallback 하면 Spring 이 이미
 * rollback-only 로 마킹한 트랜잭션을 커밋하려다 `UnexpectedRollbackException` 이 터진다
 * (learnings `workflowstatecatalog-mandatory-rollback-poison` 동일 함정). 예외를 전파시키면 이 메서드
 * 자신의 트랜잭션이 정상 롤백되고, 호출자(slack-integration 인터랙션 핸들러)가 예외를 흡수해
 * 모달 렌더만 생략하도록 자연 수렴한다.
 *
 * ## resolution 목록 — 전역 조회 (워크플로우 비종속)
 *
 * [ResolutionRepository.findAllActive] 는 프로젝트/워크플로우로 스코핑되지 않은 전역 활성 resolution
 * 목록이다(현재 도메인 모델에 resolution 의 워크플로우별 스코핑이 없다). 활성 resolution 이 하나도
 * 없으면 빈 목록을 반환한다 — "resolution 미구성" 상태를 오류가 아닌 정상 케이스로 취급한다.
 *
 * @see IssueUnfurlAdapter 동일 결합-fail-closed 게이트 철학의 선례 어댑터.
 */
@Component
class IssueCompletionOptionsAdapter(
    private val issueRepository: IssueRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val securityDirectory: IssueSecurityDirectory,
    private val issueApplicationService: IssueApplicationService,
    private val resolutionRepository: ResolutionRepository,
) : IssueCompletionOptionsPort {
    /**
     * [viewerUserId] 가 [issueKey] 를 볼 수 있으면 완료 옵션을, 없으면 `null` 을 반환한다.
     *
     * 게이트 통과 후에는 버전·DONE 전이 후보·resolution 목록을 조립만 한다 — fail-closed 계약과
     * 게이트 근거는 클래스 KDoc 참조.
     */
    @Transactional(readOnly = true)
    // 각 return 은 독립적 fail-closed 게이트(파싱/BROWSE/보안등급/이슈 부재) — 합치면 보안 판정 가독성 저하
    @Suppress("ReturnCount")
    override fun getCompletionOptions(
        issueKey: String,
        viewerUserId: UUID,
    ): IssueCompletionOptions? {
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

        val transitions = issueApplicationService.availableTransitions(ActorId(viewerUserId), key)
        val doneTransitions =
            transitions
                .filter { it.toCategory == DONE_CATEGORY }
                .map { DoneTransition(toStateKey = it.toStateKey, label = it.name) }
        val resolutions =
            resolutionRepository.findAllActive().map { resolution ->
                // findAllActive 는 영속된 row 만 반환하므로 id 는 항상 non-null 이다(DB PK).
                // Resolution.id 타입 자체는 미영속 상태(Resolution.create 직후)를 표현하기 위해 nullable.
                val id = resolution.id ?: error("resolutions.id must not be null (persisted row)")
                ResolutionOption(id = id, label = resolution.name)
            }

        return IssueCompletionOptions(
            version = issue.version,
            doneTransitions = doneTransitions,
            resolutions = resolutions,
        )
    }

    /**
     * [issueKey] 문자열을 [IssueKey] 로 파싱한다. 형식 오류(정규식 위반)면 `null`(fail-closed).
     *
     * [IssueUnfurlAdapter.parseIssueKeyOrNull] 과 동형 — 예외 message 가 사용자 입력 원문을 포함할 수
     * 있어 로깅하지 않는다(NFR2) — `@Suppress("SwallowedException")` 근거.
     */
    @Suppress("SwallowedException")
    private fun parseIssueKeyOrNull(issueKey: String): IssueKey? =
        try {
            IssueKey(issueKey)
        } catch (invalid: IllegalArgumentException) {
            null
        }
}
