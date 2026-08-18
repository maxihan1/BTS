// Jira 소스 이슈 상태를 BTS 워크플로우 상태로 FSM 우회 직접 set 하는 import 전용 서비스 (FR-IM-01 PR2)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * CSV/JSON import 시 소스(Jira) 이슈 상태를 대상 워크플로우 상태로 반영하는 import 전용 서비스.
 *
 * ### FSM 우회 direct-set — transitionIssue 와의 차이
 *
 * [IssueApplicationService.transitionIssue] 는 `workflowPort.plan()` 으로 워크플로우 FSM 의 유효
 * edge(from→to)만 허용한다. 그러나 마이그레이션 소스 상태는 대상 워크플로우의 전환 그래프 경로를
 * 따르지 않으므로(예: 시작 상태에서 임의의 종료 상태로 한 번에), edge 검증으로는 반영할 수 없다.
 * 따라서 이 서비스는 [IssueMoveService](FR-MV-01 cross-project move)와 동형으로 **FSM edge 검증을
 * 우회**하고, "대상 상태집합 포함 여부"만 검증한 뒤 [IssueRepository.applyTransition] 으로
 * `current_state_key` 를 직접 set 한다.
 *
 * ### DONE 카테고리 resolution — null 허용 (게이트1 Maxi 확정)
 *
 * [IssueRepository.applyTransition] 은 FSM validator(DONE 진입 시 resolution 필수, B7)를 거치지 않는
 * raw setter 다. PR2 는 resolution 을 원천 파싱하지 않으므로 DONE 카테고리 상태로 매칭돼도
 * `resolutionId = null` 로 진입한다(glossary: 닫힘 + 해결결과 없음 허용). resolution 파싱은 PR2 범위 밖.
 *
 * ### 권한 — 사전 체크(경고화용)
 *
 * TRANSITION 권한을 [IssuePermissionResolver.hasPermission] 으로 **사전 체크**한다(assertPermission
 * 이 아니다). 권한이 없으면 예외를 던지지 않고 [ImportStatusOutcome.NoPermission] 을 반환해 호출자
 * (어댑터)가 best-effort 경고로 강등할 수 있게 한다 — 참여 트랜잭션에서 예외를 던지면 tx 가
 * rollback-only 로 오염돼 행 전체가 롤백되기 때문이다.
 *
 * @property issueRepository 이슈 조회 + 상태 direct-set 저장소.
 * @property workflowStateCatalog 프로젝트/이슈유형별 워크플로우 상태 목록(name↔key) 조회.
 * @property permissionResolver TRANSITION 권한 사전 체크.
 * @property issueTypeRepository 이슈 유형 id→key 재조회(per-type 워크플로우 대비).
 * @property archiveGuard 프로젝트 아카이브 잠금(FR-PJ-04 PR-4 Task 9c) — [IssueMoveService] 와 동형으로
 *   FSM 우회 direct-set 이므로 [ProjectArchiveGuard.checkByIssue] 로 아카이브 프로젝트 쓰기를 차단한다.
 */
@Service
class IssueImportStatusService(
    private val issueRepository: IssueRepository,
    private val workflowStateCatalog: WorkflowStateCatalog,
    private val permissionResolver: IssuePermissionResolver,
    private val issueTypeRepository: IssueTypeRepository,
    private val archiveGuard: ProjectArchiveGuard,
) {
    /**
     * 소스 상태 이름([statusName])을 대상 워크플로우 상태로 매칭해 이슈 상태를 직접 set 한다.
     *
     * 흐름.
     * 1. TRANSITION 권한 사전 체크 — 없으면 [ImportStatusOutcome.NoPermission](예외 없음).
     * 2. [ProjectArchiveGuard.checkByIssue] — 소속 프로젝트가 아카이브 상태면 [ProjectArchivedException]
     *    (D-ORDER: 권한 다음, 이슈 조회 앞).
     * 3. 이슈 조회로 현재 상태·유형 확보, 유형 id→key 재조회.
     * 4. [statusName] 을 대상 워크플로우 상태 name 과 대소문자 무시 매칭 — 없으면 [ImportStatusOutcome.NoMatch].
     * 5. 매칭 상태 == 현재 상태면 [ImportStatusOutcome.NoOp].
     * 6. else [IssueRepository.applyTransition] 으로 direct-set(resolutionId=null) → [ImportStatusOutcome.Applied].
     *
     * @param actor 상태를 변경하는 행위자(=import 실행자).
     * @param key 대상 이슈 키.
     * @param statusName 소스(Jira) 상태 이름. 예: `"In Progress"`.
     * @param expectedVersion 직전 단계까지의 OCC 버전.
     * @return 처리 결과 [ImportStatusOutcome].
     * @throws IssueVersionConflictException direct-set 시 영향 행 0(동시 수정으로 버전 불일치).
     * @throws com.bts.issue.project.archive.ProjectArchivedException 대상 이슈의 소속 프로젝트가 아카이브 상태일 때.
     */
    @Suppress("ReturnCount") // guard-clause early return 4개(NoPermission·NoMatch·NoOp·Applied) — DEVELOPMENT.md §2.3
    @Transactional
    fun applyImportedStatus(
        actor: ActorId,
        key: IssueKey,
        statusName: String,
        expectedVersion: Long,
    ): ImportStatusOutcome {
        val hasTransition =
            permissionResolver.hasPermission(actor.value, IssuePermission.TRANSITION, IssueScope.Issue(key.value))
        if (!hasTransition) {
            return ImportStatusOutcome.NoPermission
        }
        archiveGuard.checkByIssue(key)
        val issue =
            issueRepository.findByKey(key)
                ?: error("import status target issue not found: ${key.value}")
        val issueTypeKey = issueTypeRepository.findById(issue.typeId)?.key
        val projectKey = ProjectKey.of(key.projectPrefix)
        val matched =
            workflowStateCatalog
                .listStates(projectKey, issueTypeKey)
                .firstOrNull { it.name.equals(statusName, ignoreCase = true) }
                ?: return ImportStatusOutcome.NoMatch
        if (matched.key == issue.currentStateKey) {
            return ImportStatusOutcome.NoOp
        }
        val affected = issueRepository.applyTransition(key, matched.key, expectedVersion, null)
        if (affected == 0) {
            throw IssueVersionConflictException(key, expectedVersion)
        }
        return ImportStatusOutcome.Applied(expectedVersion + 1)
    }

    /**
     * [statusName] 이 (projectKey, issueTypeId) 대상 워크플로우 상태 이름과 매칭되는지만 검사한다
     * (실제 전환·부수효과 없음) — dry-run 미리보기 전용.
     *
     * [applyImportedStatus] 3단계 매칭과 **동일 기준**(같은 [WorkflowStateCatalog.listStates] +
     * 대소문자 무시 name 비교)을 사용하므로, 매칭 결과가 실제 실행의 [ImportStatusOutcome.NoMatch]
     * 여부와 일치한다. 어댑터는 이 결과가 false 면 dry-run 경고를 미리 남긴다(CONCERN-A).
     *
     * @param projectKey 대상 프로젝트 키 문자열(예: `"BTS"`).
     * @param issueTypeId 생성될 이슈의 매칭 유형 id. null(Task 폴백)이면 기본 매핑 상태집합으로 검사.
     * @param statusName 소스(Jira) 상태 이름.
     * @return 대상 워크플로우 상태 name 에 매칭되면 true.
     */
    fun statusNameMatches(
        projectKey: String,
        issueTypeId: IssueTypeId?,
        statusName: String,
    ): Boolean {
        val issueTypeKey = issueTypeId?.let { issueTypeRepository.findById(it)?.key }
        return workflowStateCatalog
            .listStates(ProjectKey.of(projectKey), issueTypeKey)
            .any { it.name.equals(statusName, ignoreCase = true) }
    }
}

/**
 * [IssueImportStatusService.applyImportedStatus] 처리 결과.
 *
 * 어댑터는 [Applied] 면 새 OCC 버전으로 스레딩을 이어가고, [NoOp]/[NoMatch]/[NoPermission] 은
 * best-effort 경고(또는 무시)로 처리한다.
 */
sealed interface ImportStatusOutcome {
    /** 상태 direct-set 성공. @property version set 이후의 OCC 버전. */
    data class Applied(val version: Long) : ImportStatusOutcome

    /** 매칭 상태가 이미 현재 상태와 같아 변경 없음(경고 대상 아님). */
    data object NoOp : ImportStatusOutcome

    /** [statusName] 이 대상 워크플로우 상태 name 에 매칭되지 않음(경고 + 시작 상태 유지). */
    data object NoMatch : ImportStatusOutcome

    /** 행위자가 TRANSITION 권한이 없음(경고 + 시작 상태 유지). */
    data object NoPermission : ImportStatusOutcome
}
