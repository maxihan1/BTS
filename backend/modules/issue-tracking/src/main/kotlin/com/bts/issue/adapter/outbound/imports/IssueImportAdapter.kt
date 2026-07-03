// IssueImportPort 구현 — CSV/JSON import 행 1건을 issue-tracking 생성 유스케이스로 위임하는 cross-BC 쓰기 어댑터

package com.bts.issue.adapter.outbound.imports

import com.bts.issue.application.AppChangeAssigneeRequest
import com.bts.issue.application.AppChangeVersionsRequest
import com.bts.issue.application.CreateIssueRequest
import com.bts.issue.application.ImportStatusOutcome
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueImportStatusService
import com.bts.issue.application.UpdateIssueRequest
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.component.application.ComponentApplicationService
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.application.VersionApplicationService
import com.bts.issue.version.repository.VersionRepository
import com.bts.issue.worklog.application.WorklogService
import com.bts.shared.issue.ImportComment
import com.bts.shared.issue.ImportWorklog
import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportPort
import com.bts.shared.issue.IssueImportResult
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.VersionPermission
import com.bts.shared.permission.VersionPermissionResolver
import com.bts.shared.user.UserLookupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionAspectSupport
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * [IssueImportPort] 의 issue-tracking BC 구현 (FR-IM-01 Task 8).
 *
 * search-export-import 모듈이 파싱한 CSV/JSON 행 1건([IssueImportCommand])을 받아
 * [IssueApplicationService] 의 기존 생성/수정 유스케이스로 위임한다.
 *
 * ### 책임 — 행 1건 원자 생성, 권한 위임, 컴포넌트/버전 자동생성, 상태 반영
 *
 * 1. **행 원자성** — 하나의 [importIssue] 호출(=하나의 트랜잭션) 안에서
 *    이슈 생성(createIssue) + 후속 필드 설정(updateIssue/changeAssignee/버전링크/상태)을 모두 수행한다.
 *    후속 설정이 실패하면 이미 삽입된 이슈까지 함께 롤백된다 — 행 하나가 전부-성공 또는 전부-실패.
 * 2. **권한 위임** — 이 어댑터 자신은 권한을 판단하지 않는다.
 *    [IssueImportCommand.requesterUserId] 를 actor 로 [IssueApplicationService.createIssue] 를
 *    그대로 호출해 기존 CREATE_ISSUE 권한 게이트를 재사용한다.
 *    유일한 예외는 [IssueImportCommand.dryRun] 검증 경로([validateDryRun]) — 실제 생성/수정을
 *    호출할 수 없으므로 [executeImport] 가 호출하는 것과 동일한 [IssuePermissionResolver]/
 *    [IssuePermission] 조합을 직접 호출해 미리보기 판정만 수행한다. CREATE 는 항상 확인하고,
 *    [IssueScope.Project] 로는 판정할 수 없는 [IssueScope.Issue] 전용 UPDATE 도 행이 실제로
 *    update 를 유발하는 경우([validateDryRun] §UPDATE 미러 참조)에 한해 Project 스코프로 미리
 *    확인한다 — dry-run 결과가 실제 처리 결과와 정합해야 하기 때문이다(코드리뷰 CONCERN C1).
 * 3. **컴포넌트/버전 자동생성 — 사전 권한 체크(throw 0)** — [resolveComponentIds]/[resolveVersionIds]
 *    는 이름이 미매칭이어도 [ComponentApplicationService.create]/[VersionApplicationService.create]
 *    를 곧바로 호출해 예외로 실패시키지 않는다. 두 서비스 모두 이 메서드와 같은 REQUIRED 트랜잭션에
 *    참여하므로, 그 안에서 권한 예외가 던져지면 공유 트랜잭션이 rollback-only 로 오염돼 catch 해도
 *    행 전체가 롤백된다(메모리 transaction-self-invocation-requires-new 동형 함정). 따라서
 *    [ComponentPermissionResolver]/[VersionPermissionResolver] 로 CREATE 권한을 **먼저** 확인하고,
 *    권한이 없으면 create 를 아예 호출하지 않고 경고만 추가한다(throw 0, 오염 0).
 * 4. **상태 반영 — best-effort** — [IssueImportCommand.statusName] 이 있으면
 *    [IssueImportStatusService.applyImportedStatus] 로 위임한다. 이 서비스도 TRANSITION 권한을
 *    사전 체크해 예외를 던지지 않으므로([ImportStatusOutcome.NoPermission]), 3과 동일하게 tx 오염이 없다.
 * 5. **댓글/worklog 동반 생성 — 사전 체크(throw 0, PR3)** — [applyComments]/[applyWorklogs] 는
 *    [CommentApplicationService.create]/[WorklogService.createImported] 가 같은 REQUIRED 트랜잭션에
 *    참여하므로(3과 동일 오염 위험) UPDATE 권한을 [hasIssueUpdatePermission] 으로 **먼저** 확인하고,
 *    권한이 없으면 전량 스킵 + 집약 경고만 남긴다. worklog 는 추가로 timeSpentSeconds≤0
 *    (worklogs.time_spent_seconds CHECK 23514)도 항목별 사전체크해 스킵한다 — 이 두 조건이 이
 *    두 서비스에서 던질 수 있는 예외의 전부다(★2 "잔여 throw 집합"). startedAt 이 null 이면
 *    throw 대상이 아니라 [Instant.now] 로 대체한다([ImportWorklog.startedAt] KDoc).
 *
 * ### 트랜잭션 롤백 안전성 (CONCERN #1)
 *
 * [com.bts.issue.event.IssueEventPublisher.publish] 는 `pgmq.send` 를 호출자의 트랜잭션 안에서
 * 실행하는 outbox 패턴이다(Propagation.MANDATORY, DATA.md §7.2). 즉 이벤트 enqueue 도 일반 INSERT 와
 * 동일하게 트랜잭션 롤백 시 함께 소멸한다. 따라서 [importIssue] 내부에서 예외를 캐치해 결과
 * 객체로 변환할 때는 [TransactionAspectSupport.currentTransactionStatus] 로 트랜잭션을
 * rollback-only 로 명시 설정해야 한다 — 캐치된 예외는 프록시 경계를 벗어나지 않으므로
 * 이 호출 없이는 Spring 이 기본적으로 커밋을 시도한다.
 *
 * @see IssueImportPort
 * @see IssueApplicationService
 */
@Suppress("TooManyFunctions", "LongParameterList")
@Component
class IssueImportAdapter(
    private val issueApplicationService: IssueApplicationService,
    private val issueRepository: IssueRepository,
    private val issueTypeRepository: IssueTypeRepository,
    private val componentRepository: ComponentRepository,
    private val userLookupPort: UserLookupPort,
    private val permissionResolver: IssuePermissionResolver,
    private val componentApplicationService: ComponentApplicationService,
    private val versionApplicationService: VersionApplicationService,
    private val versionRepository: VersionRepository,
    private val componentPermissionResolver: ComponentPermissionResolver,
    private val versionPermissionResolver: VersionPermissionResolver,
    private val issueImportStatusService: IssueImportStatusService,
    private val commentApplicationService: CommentApplicationService,
    private val worklogService: WorklogService,
    private val clock: Clock = Clock.systemUTC(),
) : IssueImportPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 파싱된 import 행 1건으로 이슈 생성을 요청한다.
     *
     * 흐름.
     * 1. summary 공백 검증 — 즉시 [IssueImportResult.VALIDATION] 실패 반환(부수 효과 없음).
     * 2. 프로젝트 존재 확인 — 없으면 [IssueImportResult.NOT_FOUND] (부수 효과 없음).
     * 3. [cmd.dryRun] 이면 [validateDryRun], 아니면 [executeImport] 위임.
     * 4. 위 3에서 발생한 도메인/검증 예외는 트랜잭션을 rollback-only 로 표시한 뒤
     *    [toFailure] 로 결과 객체 변환 — 도메인 예외가 호출자(cross-BC)까지 누출되지 않는다.
     *
     * @param cmd 이슈 생성 커맨드.
     * @return 생성 결과. dryRun 이면 실제 생성 없이 검증 결과만 반환.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    @Transactional
    override fun importIssue(cmd: IssueImportCommand): IssueImportResult {
        if (cmd.summary.isBlank()) {
            return IssueImportResult.failure(IssueImportResult.VALIDATION, "summary must not be blank")
        }
        val actor = ActorId(cmd.requesterUserId)
        val projectId =
            issueRepository.findProjectIdByKey(cmd.projectKey)
                ?: return IssueImportResult.failure(
                    IssueImportResult.NOT_FOUND,
                    "project not found: ${cmd.projectKey}",
                )

        return try {
            if (cmd.dryRun) {
                validateDryRun(cmd, actor, projectId)
            } else {
                executeImport(cmd, actor, projectId)
            }
        } catch (e: RuntimeException) {
            // 캐치된 예외는 프록시 경계를 벗어나지 않으므로 명시적으로 rollback-only 표시가 필요하다
            // (CONCERN #1 — createIssue 의 INSERT + IssueCreated pgmq.send 도 함께 롤백돼야 함).
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
            toFailure(cmd, e)
        }
    }

    /**
     * dryRun 검증 — 실제 생성/수정/자동생성/전이 호출 없이 유효성만 확인한다.
     *
     * CREATE_ISSUE 권한을 [permissionResolver] 로 직접 확인한다(이 경로에서만 예외적으로 권한을
     * 판단 — createIssue 를 호출할 수 없는 dryRun 특성상 불가피하다. 클래스 KDoc 참조).
     * 통과하면 [resolveFields] 로 실제 생성 시와 동일한 경고(warnings) 를 미리 계산한다
     * ([cmd.dryRun]=true 이므로 [resolveComponentIds]/[resolveVersionIds] 내부에서 실제
     * create 호출 없이 권한 체크만 수행된다). [rowTriggersUpdate] 로 이 행이 [executeImport] 에서
     * updateIssue/changeAssignee/changeFixVersions/changeAffectsVersions 를 유발할지 판정해
     * 유발한다면 UPDATE 권한도 미리 확인한다(§UPDATE 미러, CONCERN C1).
     * 마지막으로 [cmd.statusName] 이 있으면 TRANSITION 권한을 미리 확인해 경고로 남긴다 —
     * 상태 반영은 [IssueImportStatusService] 가 best-effort 로 처리하므로(권한 없어도 예외를 던지지
     * 않음) FORBIDDEN 하드 실패로 미러하지 않는다. 댓글/worklog 도 동일하게 [warnCommentsWorklogsIfNeeded]
     * 로 미리보기 경고만 남기고 [rowTriggersUpdate] 에는 엮지 않는다(PR3, ★C3 — CONCERN-A 재발 방지).
     *
     * ### UPDATE 미러 — Project 스코프로 예측하는 이유
     *
     * 실제 [IssueApplicationService.updateIssue]/[changeAssignee]/[changeAffectsVersions]/
     * [changeFixVersions] 는 [IssueScope.Issue] 로 UPDATE 를 검증하지만, dryRun 시점에는 아직
     * 이슈가 생성되지 않아 issueKey 가 없다. identity-access BC 의 prod [IssuePermissionResolver]
     * 구현체는 두 스코프 모두 결국 동일한 projectId 로 멤버십+역할 매트릭스(EDIT_ISSUE)를 판정하며
     * (UPDATE 는 VIEW 처럼 이슈별 보안 등급 게이트가 추가되지 않는다), 따라서 [IssueScope.Project] 로
     * 미리 확인해도 실제 [IssueScope.Issue] 판정과 동일한 결과를 낸다. TRANSITION 미리보기
     * ([hasTransitionPermission])도 동일 근거를 따른다.
     *
     * @param cmd 검증할 import 커맨드.
     * @param actor 권한 판정 대상 행위자.
     * @param projectId 대상 프로젝트 내부 식별자.
     * @return 검증 통과 시 [IssueImportResult.success] (issueKey=[DRY_RUN_MARKER]), 실패 시 해당 사유.
     */
    @Suppress("ReturnCount") // guard-clause early return 3개(CREATE 거부·UPDATE 거부·통과) — DEVELOPMENT.md §2.3
    private fun validateDryRun(
        cmd: IssueImportCommand,
        actor: ActorId,
        projectId: UUID,
    ): IssueImportResult {
        val hasCreatePermission =
            permissionResolver.hasPermission(actor.value, IssuePermission.CREATE, IssueScope.Project(cmd.projectKey))
        if (!hasCreatePermission) {
            return IssueImportResult.failure(
                IssueImportResult.FORBIDDEN,
                "actor has no CREATE_ISSUE permission for project: ${cmd.projectKey}",
            )
        }
        val warnings = mutableListOf<String>()
        val resolution = resolveFields(cmd, projectId, actor, warnings)
        if (rowTriggersUpdate(cmd, resolution) && !hasUpdatePermission(actor, cmd.projectKey)) {
            return IssueImportResult.failure(
                IssueImportResult.FORBIDDEN,
                "actor has no EDIT_ISSUE permission for project: ${cmd.projectKey}",
            )
        }
        warnStatusIfNeeded(cmd, resolution, actor, warnings)
        warnCommentsWorklogsIfNeeded(cmd, resolution, actor, warnings)
        return IssueImportResult.success(DRY_RUN_MARKER, warnings)
    }

    /**
     * [cmd.statusName] 이 지정됐을 때 실제 실행([applyStatusIfPresent])이 낼 best-effort 경고를
     * dry-run 에서 미리 산출한다 — 경고 미리보기 갭 수정(CONCERN-A).
     *
     * 1. TRANSITION 권한 없음 → 실행의 [ImportStatusOutcome.NoPermission] 경고를 미러.
     * 2. 권한 있으나 statusName 이 대상 워크플로우 상태에 없음 → 실행의 [ImportStatusOutcome.NoMatch]
     *    경고를 미러([IssueImportStatusService.statusNameMatches] 로 실제 실행과 동일 기준 검사).
     *
     * 두 경우 모두 실제 실행은 best-effort 강등(예외 없음, 클래스 KDoc §4)하므로 FORBIDDEN 실패가
     * 아닌 경고로만 남긴다 — dry-run 유효성 판정은 실행과 어긋나지 않는다. 실행 시 "자동 생성했습니다"
     * 같은 informational 경고는 dry-run 에서 생성이 없어 그대로 옮기면 거짓이므로 미러하지 않는다(실행 전용).
     */
    private fun warnStatusIfNeeded(
        cmd: IssueImportCommand,
        resolution: FieldResolution,
        actor: ActorId,
        warnings: MutableList<String>,
    ) {
        val statusName = cmd.statusName ?: return
        if (!hasTransitionPermission(actor, cmd.projectKey)) {
            warnings += "상태 변경 권한이 없어 '$statusName' 적용이 건너뛰어질 수 있습니다."
            return
        }
        if (!issueImportStatusService.statusNameMatches(cmd.projectKey, resolution.typeId, statusName)) {
            warnings += "상태 '$statusName' 을(를) 찾을 수 없어 건너뛰어질 수 있습니다."
        }
    }

    /** 프로젝트 스코프로 TRANSITION 권한을 미리 확인한다(§UPDATE 미러와 동일 근거 — [validateDryRun] KDoc). */
    private fun hasTransitionPermission(
        actor: ActorId,
        projectKey: String,
    ): Boolean =
        permissionResolver.hasPermission(
            actor.value,
            IssuePermission.TRANSITION,
            IssueScope.Project(projectKey),
        )

    /**
     * [cmd]/[resolution] 조합이 [executeImport] 에서 updateIssue/changeAssignee/changeAffectsVersions/
     * changeFixVersions 호출을 유발하는지 판정한다 — [executeImport] 가 위임하는 헬퍼들의 트리거
     * 조건을 그대로 미러한다. 더도 덜도 아니게 실제 경로와 정확히 일치시켜야 dryRun 예측이 어긋나지 않는다.
     */
    private fun rowTriggersUpdate(
        cmd: IssueImportCommand,
        resolution: FieldResolution,
    ): Boolean =
        cmd.priority != null ||
            cmd.labels.isNotEmpty() ||
            resolution.assigneeId != null ||
            resolution.affectsVersionIds.isNotEmpty() ||
            resolution.fixVersionIds.isNotEmpty()

    /** 프로젝트 스코프로 EDIT_ISSUE(UPDATE) 권한을 미리 확인한다(§UPDATE 미러 근거는 [validateDryRun] KDoc). */
    private fun hasUpdatePermission(
        actor: ActorId,
        projectKey: String,
    ): Boolean = permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Project(projectKey))

    /**
     * dry-run 에서 [cmd.comments]/[cmd.worklogs] 가 실제 실행([applyComments]/[applyWorklogs]) 시 낼
     * best-effort 경고를 미리 산출한다 (PR3, ★C3).
     *
     * 이 헬퍼가 남기는 경고는 어떤 경우에도 [IssueImportResult.FORBIDDEN] 하드 실패로 이어지지 않는다 —
     * [rowTriggersUpdate] 에 절대 엮지 않는다(CONCERN-A 재발 방지, [warnStatusIfNeeded] 와 동일 원칙).
     * 이슈가 아직 생성되지 않은 시점이라 UPDATE 권한은 [hasUpdatePermission](Project 스코프, §UPDATE 미러)로
     * 예측한다. 권한이 없으면 [applyComments]/[applyWorklogs] 가 전량 스킵할 것이므로 집약 경고 1건만
     * 남기고, 있으면 [cmd] 값만으로 순수 계산 가능한 timeSpent≤0/author 미매칭/startedAt 부재를
     * 미리 경고한다(부수 효과 없음 — 실제 create/createImported 호출은 하지 않는다).
     */
    private fun warnCommentsWorklogsIfNeeded(
        cmd: IssueImportCommand,
        resolution: FieldResolution,
        actor: ActorId,
        warnings: MutableList<String>,
    ) {
        val hasUpdate = hasUpdatePermission(actor, cmd.projectKey)
        warnCommentsPreview(cmd, resolution, hasUpdate, warnings)
        warnWorklogsPreview(cmd, resolution, hasUpdate, warnings)
    }

    /** [warnCommentsWorklogsIfNeeded] 의 댓글 부분 — [applyComments] 가 낼 경고를 미리 예측한다. */
    private fun warnCommentsPreview(
        cmd: IssueImportCommand,
        resolution: FieldResolution,
        hasUpdate: Boolean,
        warnings: MutableList<String>,
    ) {
        if (cmd.comments.isEmpty()) return
        if (!hasUpdate) {
            warnings += "댓글 ${cmd.comments.size}건은 권한이 없어 건너뛰어질 수 있습니다."
            return
        }
        val unmatchedCount = cmd.comments.count { isAuthorUnmatched(it.authorEmail, resolution.resolvedEmails) }
        if (unmatchedCount > 0) {
            warnings += "댓글 ${unmatchedCount}건 작성자 이메일이 매칭되지 않아 요청자로 대체될 수 있습니다."
        }
    }

    /** [warnCommentsWorklogsIfNeeded] 의 worklog 부분 — [applyWorklogs] 가 낼 경고를 미리 예측한다. */
    private fun warnWorklogsPreview(
        cmd: IssueImportCommand,
        resolution: FieldResolution,
        hasUpdate: Boolean,
        warnings: MutableList<String>,
    ) {
        if (cmd.worklogs.isEmpty()) return
        if (!hasUpdate) {
            warnings += "워크로그 ${cmd.worklogs.size}건은 권한이 없어 건너뛰어질 수 있습니다."
            return
        }
        val invalidCount = cmd.worklogs.count { it.timeSpentSeconds <= 0 }
        if (invalidCount > 0) {
            warnings += "워크로그 ${invalidCount}건 소요 시간이 0 이하라 건너뛰어질 수 있습니다."
        }
        val unmatchedCount = cmd.worklogs.count { isAuthorUnmatched(it.authorEmail, resolution.resolvedEmails) }
        if (unmatchedCount > 0) {
            warnings += "워크로그 ${unmatchedCount}건 작성자 이메일이 매칭되지 않아 요청자로 대체될 수 있습니다."
        }
        val missingStartedAtCount = cmd.worklogs.count { it.startedAt == null }
        if (missingStartedAtCount > 0) {
            warnings += "워크로그 ${missingStartedAtCount}건 시작 시각이 없어 import 실행 시각으로 대체될 수 있습니다."
        }
    }

    /**
     * 실제 이슈 생성 + 후속 필드 설정을 같은 트랜잭션에서 수행한다.
     *
     * 1. [resolveFields] — 이메일→담당자/리포터, 이름→타입/컴포넌트/버전 매핑(미매칭 시 권한 있으면 자동생성).
     * 2. [IssueApplicationService.createIssue] — CREATE_ISSUE 권한 게이트 포함(권한 위임).
     * 3. [applyPriorityAndLabelsIfPresent] — priority/labels 지정 시 updateIssue.
     * 4. [applyAssigneeIfPresent] — assignee 매칭 시 changeAssignee.
     * 5. [applyVersionLinks] — affects/fix 버전이 매칭·자동생성됐으면 링크.
     * 6. [applyStatusIfPresent] — [cmd.statusName] 있으면 상태 반영(best-effort).
     * 7. [applyComments] — [cmd.comments] 를 [CommentApplicationService.create] 로 위임(best-effort, PR3).
     * 8. [applyWorklogs] — [cmd.worklogs] 를 [WorklogService.createImported] 로 위임(best-effort, PR3).
     *
     * 3~8 중 어느 하나라도 예외를 던지면 [importIssue] 의 catch 블록이 트랜잭션 전체를
     * rollback-only 로 표시하므로, 이미 삽입된 이슈(2)까지 함께 롤백된다(행 원자성).
     * 7·8 은 UPDATE 권한/timeSpent≤0 을 호출 전에 사전체크해 스킵으로 강등하므로(★2, [applyComments]/
     * [applyWorklogs] KDoc 참조) 정상 경로에서는 이 예외를 던지지 않는다 — 그럼에도 예상외 예외가
     * 발생하면 이 안전망(행 원자성)이 여전히 이슈까지 롤백해 부분 반영을 막는다.
     * OCC 버전은 각 단계의 반환값으로 계속 스레딩한다(currentVersion). 7·8 은 issues.version 을
     * 증가시키지 않으므로(worklog 롤업 no-bump 원칙, [WorklogService] KDoc) currentVersion 스레딩과 무관하다.
     *
     * @param cmd 처리할 import 커맨드.
     * @param actor 생성 행위자(=requesterUserId).
     * @param projectId 대상 프로젝트 내부 식별자.
     * @return 생성된 이슈 키를 포함한 [IssueImportResult.success].
     */
    private fun executeImport(
        cmd: IssueImportCommand,
        actor: ActorId,
        projectId: UUID,
    ): IssueImportResult {
        val warnings = mutableListOf<String>()
        val resolution = resolveFields(cmd, projectId, actor, warnings)

        val created =
            issueApplicationService.createIssue(
                actor = actor,
                request =
                    CreateIssueRequest(
                        projectKey = cmd.projectKey,
                        summary = cmd.summary,
                        reporterId = ActorId(resolution.reporterId),
                        typeId = resolution.typeId,
                        description = cmd.description,
                        componentIds = resolution.componentIds,
                    ),
            )

        var currentVersion = created.version
        currentVersion = applyPriorityAndLabelsIfPresent(cmd, actor, created.key, currentVersion)
        currentVersion = applyAssigneeIfPresent(resolution, actor, created.key, currentVersion)
        currentVersion = applyVersionLinks(resolution, actor, created.key, currentVersion)
        currentVersion = applyStatusIfPresent(cmd, actor, created.key, currentVersion, warnings)
        applyComments(cmd, resolution, actor, created.key, warnings)
        applyWorklogs(cmd, resolution, actor, created.key, warnings)

        log.info("issue_imported key={} actor={}", created.key.value, actor.value)
        return IssueImportResult.success(created.key.value, warnings)
    }

    /**
     * priority 또는 labels 가 지정됐으면 [IssueApplicationService.updateIssue] 로 반영한다.
     *
     * @return 반영했으면 갱신된 OCC 버전, 아니면 [currentVersion] 그대로.
     */
    private fun applyPriorityAndLabelsIfPresent(
        cmd: IssueImportCommand,
        actor: ActorId,
        key: IssueKey,
        currentVersion: Long,
    ): Long {
        if (cmd.priority == null && cmd.labels.isEmpty()) return currentVersion
        val updated =
            issueApplicationService.updateIssue(
                actor = actor,
                key = key,
                request =
                    UpdateIssueRequest(
                        summary = null,
                        expectedVersion = currentVersion,
                        priority = cmd.priority,
                        labels = cmd.labels.ifEmpty { null },
                    ),
            )
        return updated.version
    }

    /**
     * [resolution.assigneeId] 가 매칭됐으면 [IssueApplicationService.changeAssignee] 로 반영한다.
     *
     * @return 반영했으면 갱신된 OCC 버전, 아니면 [currentVersion] 그대로.
     */
    private fun applyAssigneeIfPresent(
        resolution: FieldResolution,
        actor: ActorId,
        key: IssueKey,
        currentVersion: Long,
    ): Long {
        val assigneeId = resolution.assigneeId ?: return currentVersion
        val updated =
            issueApplicationService.changeAssignee(
                actor = actor,
                key = key,
                request = AppChangeAssigneeRequest(assigneeId = assigneeId, expectedVersion = currentVersion),
            )
        return updated.version
    }

    /**
     * affects/fix 버전이 매칭·자동생성됐으면 각각 [IssueApplicationService.changeAffectsVersions]/
     * [IssueApplicationService.changeFixVersions] 로 반영한다. 빈 목록이면 해당 호출을 스킵한다.
     *
     * @return 반영된 만큼 순차 갱신된 OCC 버전(둘 다 빈 목록이면 [currentVersion] 그대로).
     */
    private fun applyVersionLinks(
        resolution: FieldResolution,
        actor: ActorId,
        key: IssueKey,
        currentVersion: Long,
    ): Long {
        var version = currentVersion
        if (resolution.affectsVersionIds.isNotEmpty()) {
            version =
                issueApplicationService.changeAffectsVersions(
                    actor = actor,
                    key = key,
                    request =
                        AppChangeVersionsRequest(
                            versionIds = resolution.affectsVersionIds,
                            expectedVersion = version,
                        ),
                ).version
        }
        if (resolution.fixVersionIds.isNotEmpty()) {
            version =
                issueApplicationService.changeFixVersions(
                    actor = actor,
                    key = key,
                    request =
                        AppChangeVersionsRequest(
                            versionIds = resolution.fixVersionIds,
                            expectedVersion = version,
                        ),
                ).version
        }
        return version
    }

    /**
     * [cmd.statusName] 이 있으면 [IssueImportStatusService.applyImportedStatus] 로 상태를 반영한다.
     *
     * best-effort — [ImportStatusOutcome.NoMatch]/[ImportStatusOutcome.NoPermission] 은 예외 없이
     * [warnings] 에 경고만 남기고 이슈는 시작 상태를 유지한다. [ImportStatusOutcome.NoOp] 은 경고
     * 대상이 아니다([IssueImportStatusService] KDoc 참조).
     *
     * @return [ImportStatusOutcome.Applied] 면 새 OCC 버전, 그 외에는 [currentVersion] 그대로.
     */
    private fun applyStatusIfPresent(
        cmd: IssueImportCommand,
        actor: ActorId,
        key: IssueKey,
        currentVersion: Long,
        warnings: MutableList<String>,
    ): Long {
        val statusName = cmd.statusName ?: return currentVersion
        val outcome = issueImportStatusService.applyImportedStatus(actor, key, statusName, currentVersion)
        return when (outcome) {
            is ImportStatusOutcome.Applied -> outcome.version
            ImportStatusOutcome.NoOp -> currentVersion
            ImportStatusOutcome.NoMatch -> {
                warnings += "상태 '$statusName' 을(를) 찾을 수 없어 건너뛰었습니다."
                currentVersion
            }
            ImportStatusOutcome.NoPermission -> {
                warnings += "상태 변경 권한이 없어 건너뛰었습니다."
                currentVersion
            }
        }
    }

    /**
     * [cmd.comments] 를 [CommentApplicationService.create] 로 위임한다 — best-effort (PR3, ★2).
     *
     * UPDATE 권한이 없으면 [CommentApplicationService.create] 를 아예 호출하지 않고(throw 0, 참여
     * 트랜잭션 오염 0 — 클래스 KDoc ★2) 집약 경고 1건만 남긴다. 권한이 있으면 각 댓글의
     * [ImportComment.authorEmail] 을 [resolution.resolvedEmails] 로 해석해([resolveAuthorId], 미매칭
     * 시 [cmd.requesterUserId] 폴백) create 를 호출하고, 미매칭 건수를 집약해 경고 1건으로 남긴다
     * ([warnCommentsPreview] 와 동일 카테고리 — dry-run/실행 경고 정합).
     *
     * [ImportComment.createdAt] 을 create 의 createdAt 인자로 그대로 전달해 원본(Jira 등) 작성 시각을
     * 보존한다(null 이면 [CommentApplicationService.create] 가 import 실행 시각으로 폴백) — worklog
     * `createImported` 의 startedAt 보존과 대칭.
     *
     * body 빈 문자열은 comments.body NOT NULL 을 만족하므로 throw 하지 않는다 — 스킵하지 않고 그대로
     * 생성한다(★2 KDoc "잔여 throw 집합" 참조, 스킵은 선택적 품질 개선이라 이 PR 범위 밖).
     *
     * @param cmd import 커맨드([ImportComment] 목록 출처).
     * @param resolution [resolveFields] 결과(author 이메일 배치 해석 [FieldResolution.resolvedEmails] 포함).
     * @param actor UPDATE 권한 판정 및 create 호출 actor(=[cmd.requesterUserId]).
     * @param key 방금 생성된 이슈 키 — 직전 createIssue 로 존재가 보장되므로 404 없음(★2).
     * @param warnings 집약 경고를 추가할 목록(호출자 소유, 누적).
     */
    private fun applyComments(
        cmd: IssueImportCommand,
        resolution: FieldResolution,
        actor: ActorId,
        key: IssueKey,
        warnings: MutableList<String>,
    ) {
        if (cmd.comments.isEmpty()) return
        if (!hasIssueUpdatePermission(actor, key)) {
            warnings += "댓글 ${cmd.comments.size}건은 권한이 없어 건너뛰었습니다."
            return
        }
        var unmatchedAuthorCount = 0
        for (importComment in cmd.comments) {
            if (isAuthorUnmatched(importComment.authorEmail, resolution.resolvedEmails)) unmatchedAuthorCount++
            val authorId = resolveAuthorId(importComment.authorEmail, resolution.resolvedEmails, cmd.requesterUserId)
            commentApplicationService.create(actor, key, importComment.body, ActorId(authorId), importComment.createdAt)
        }
        if (unmatchedAuthorCount > 0) {
            warnings += "댓글 ${unmatchedAuthorCount}건 작성자 이메일이 매칭되지 않아 요청자로 대체했습니다."
        }
    }

    /**
     * [cmd.worklogs] 를 [WorklogService.createImported] 로 위임한다 — best-effort (PR3, ★2).
     *
     * UPDATE 권한이 없으면 [WorklogService.createImported] 를 아예 호출하지 않고 집약 경고 1건만
     * 남긴다([applyComments] 와 동일 근거). 권한이 있으면 [applyWorklogItem] 으로 항목별 사전체크
     * (timeSpent≤0 스킵)와 author/startedAt 해석을 수행하고, 그 결과를 [warnWorklogOutcomes] 로
     * 집약 경고로 변환한다.
     *
     * @param cmd import 커맨드([ImportWorklog] 목록 출처).
     * @param resolution [resolveFields] 결과(author 이메일 배치 해석 포함).
     * @param actor UPDATE 권한 판정 및 createImported 호출 actor(=[cmd.requesterUserId]).
     * @param key 방금 생성된 이슈 키 — 직전 createIssue 로 존재가 보장되므로 404 없음(★2).
     * @param warnings 집약 경고를 추가할 목록(호출자 소유, 누적).
     */
    private fun applyWorklogs(
        cmd: IssueImportCommand,
        resolution: FieldResolution,
        actor: ActorId,
        key: IssueKey,
        warnings: MutableList<String>,
    ) {
        if (cmd.worklogs.isEmpty()) return
        if (!hasIssueUpdatePermission(actor, key)) {
            warnings += "워크로그 ${cmd.worklogs.size}건은 권한이 없어 건너뛰었습니다."
            return
        }
        val outcomes = cmd.worklogs.map { applyWorklogItem(it, resolution, actor, cmd.requesterUserId, key) }
        warnWorklogOutcomes(outcomes, warnings)
    }

    /**
     * worklog 1건의 사전체크 + 생성을 수행한다([applyWorklogs] 루프 본체).
     *
     * timeSpentSeconds≤0 이면 worklogs.time_spent_seconds CHECK(>0, 23514) 를 사전 회피하기 위해
     * [WorklogService.createImported] 를 호출하지 않고 스킵한다(★2 잔여 throw 집합 #2). startedAt 이
     * null 이면 [Instant.now] 로 대체한다(★2 잔여 throw 집합 #3 — worklogs.started_at NOT NULL 회피,
     * [ImportWorklog.startedAt] KDoc "null 이면 구현체가 import 실행 시각을 사용한다"). 두 사전체크 모두
     * throw 없이 [WorklogApplyOutcome] 으로 결과만 보고한다.
     *
     * @return 스킵/대체 여부를 담은 [WorklogApplyOutcome] — [warnWorklogOutcomes] 가 집약 경고로 변환한다.
     */
    private fun applyWorklogItem(
        importWorklog: ImportWorklog,
        resolution: FieldResolution,
        actor: ActorId,
        requesterUserId: UUID,
        key: IssueKey,
    ): WorklogApplyOutcome {
        if (importWorklog.timeSpentSeconds <= 0) {
            return WorklogApplyOutcome(invalidTimeSpent = true, unmatchedAuthor = false, missingStartedAt = false)
        }
        val unmatched = isAuthorUnmatched(importWorklog.authorEmail, resolution.resolvedEmails)
        val authorId = resolveAuthorId(importWorklog.authorEmail, resolution.resolvedEmails, requesterUserId)
        val missingStartedAt = importWorklog.startedAt == null
        worklogService.createImported(
            actor = actor,
            issueKey = key,
            authorId = ActorId(authorId),
            timeSpentSeconds = importWorklog.timeSpentSeconds,
            startedAt = importWorklog.startedAt ?: Instant.now(clock),
            comment = importWorklog.comment,
        )
        return WorklogApplyOutcome(
            invalidTimeSpent = false,
            unmatchedAuthor = unmatched,
            missingStartedAt = missingStartedAt,
        )
    }

    /**
     * [applyWorklogItem] 결과 목록을 유형별로 집계해 경고 1건씩으로 강등한다(best-effort 집약 —
     * "댓글 5건 권한 없어 스킵" 처럼 건별이 아닌 유형별 1줄, Maxi 확정).
     * [warnWorklogsPreview] 와 동일 카테고리·집계 방식(dry-run/실행 경고 정합).
     */
    private fun warnWorklogOutcomes(
        outcomes: List<WorklogApplyOutcome>,
        warnings: MutableList<String>,
    ) {
        val invalidCount = outcomes.count { it.invalidTimeSpent }
        if (invalidCount > 0) {
            warnings += "워크로그 ${invalidCount}건 소요 시간이 0 이하라 건너뛰었습니다."
        }
        val unmatchedCount = outcomes.count { it.unmatchedAuthor }
        if (unmatchedCount > 0) {
            warnings += "워크로그 ${unmatchedCount}건 작성자 이메일이 매칭되지 않아 요청자로 대체했습니다."
        }
        val missingStartedAtCount = outcomes.count { it.missingStartedAt }
        if (missingStartedAtCount > 0) {
            warnings += "워크로그 ${missingStartedAtCount}건 시작 시각이 없어 import 실행 시각으로 대체했습니다."
        }
    }

    /**
     * worklog 1건 사전체크·생성 결과([applyWorklogItem]) — [warnWorklogOutcomes] 집계 입력.
     *
     * @property invalidTimeSpent timeSpentSeconds≤0 이라 생성을 스킵했으면 true.
     * @property unmatchedAuthor authorEmail 이 지정됐지만 매칭 실패해 requester 로 폴백했으면 true
     *   ([invalidTimeSpent]=true 인 항목은 생성 자체를 스킵하므로 항상 false).
     * @property missingStartedAt startedAt 이 null 이라 import 실행 시각으로 대체했으면 true
     *   ([invalidTimeSpent]=true 인 항목은 항상 false).
     */
    private data class WorklogApplyOutcome(
        val invalidTimeSpent: Boolean,
        val unmatchedAuthor: Boolean,
        val missingStartedAt: Boolean,
    )

    /**
     * [key] 스코프로 EDIT_ISSUE(UPDATE) 권한을 실제 확인한다 — 댓글/worklog 실행 사전체크 전용(★2).
     *
     * [hasUpdatePermission] 의 dry-run 전용 Project 스코프 예측과 달리, 이 시점엔 이슈가 실제로
     * 존재하므로([applyComments]/[applyWorklogs] 는 createIssue 직후에만 호출된다) [CommentApplicationService.create]/
     * [WorklogService.createImported] 내부 checkPermission 과 동일한 [IssueScope.Issue] 로 확인해야
     * 사전체크와 실제 판정이 어긋나지 않는다.
     */
    private fun hasIssueUpdatePermission(
        actor: ActorId,
        key: IssueKey,
    ): Boolean = permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(key.value))

    /**
     * 댓글/worklog author 이메일을 실제 식별자로 해석한다 — 미매칭 시 [requesterUserId] 로 폴백한다
     * ([ImportComment.authorEmail]/[ImportWorklog.authorEmail] KDoc, [resolveFields] 의 reporter 폴백과 동일 규칙).
     */
    private fun resolveAuthorId(
        authorEmail: String?,
        resolvedEmails: Map<String, UUID>,
        requesterUserId: UUID,
    ): UUID = authorEmail?.lowercase()?.let { resolvedEmails[it] } ?: requesterUserId

    /** [authorEmail] 이 지정됐지만 [resolvedEmails] 에 매칭되지 않았는지 여부([resolveAuthorId] 의 폴백 발생 조건과 동일). */
    private fun isAuthorUnmatched(
        authorEmail: String?,
        resolvedEmails: Map<String, UUID>,
    ): Boolean = authorEmail != null && resolvedEmails[authorEmail.lowercase()] == null

    /**
     * import 커맨드의 이메일/이름 필드를 실제 식별자로 해석한 결과.
     *
     * @property reporterId 리포터 사용자 UUID. 이메일 미매칭 시 requesterUserId 로 폴백된 값.
     * @property assigneeId 담당자 사용자 UUID. 이메일 미매칭 시 null(미할당).
     * @property typeId 매칭된 이슈 타입 식별자. typeName 미지정/미매칭 시 null(Task 폴백).
     * @property componentIds 매칭·자동생성된 컴포넌트 UUID 목록. 미매칭+생성권한없음 이름은 제외된다.
     * @property affectsVersionIds 매칭·자동생성된 "영향받는 버전" UUID 목록.
     * @property fixVersionIds 매칭·자동생성된 "수정 예정 버전" UUID 목록.
     * @property resolvedEmails [resolveFields] 가 한 번에 배치 해석한 `lower(email) -> UUID` 전체 맵
     *   (reporter/assignee 뿐 아니라 댓글/worklog author 이메일도 포함, PR3). [applyComments]/
     *   [applyWorklogs]/[warnCommentsPreview]/[warnWorklogsPreview] 가 [resolveAuthorId]/[isAuthorUnmatched]
     *   로 재사용해 author 이메일마다 개별 조회를 반복하지 않는다.
     */
    private data class FieldResolution(
        val reporterId: UUID,
        val assigneeId: UUID?,
        val typeId: IssueTypeId?,
        val componentIds: List<UUID>,
        val affectsVersionIds: List<UUID>,
        val fixVersionIds: List<UUID>,
        val resolvedEmails: Map<String, UUID>,
    )

    /**
     * [cmd] 의 이메일/이름 필드를 실제 식별자로 일괄 해석한다.
     *
     * - reporterEmail/assigneeEmail/댓글·worklog authorEmail: [userLookupPort.resolveByEmails] 1회
     *   배치 호출로 해석([FieldResolution.resolvedEmails] 로 전체 맵을 보존해 author 해석에 재사용, PR3).
     * - typeName: [resolveTypeId] — 활성 타입 이름 대소문자 무시 매칭.
     * - componentNames/affectsVersionNames/fixVersionNames: [resolveComponentIds]/[resolveVersionIds]
     *   — 프로젝트 활성 이름 대소문자 무시 매칭, 미매칭 시 권한 있으면 자동생성([cmd.dryRun] 이면
     *   권한만 확인하고 실제 생성은 호출하지 않는다).
     *
     * @param cmd 해석 대상 import 커맨드.
     * @param projectId 컴포넌트/버전 조회 범위가 되는 프로젝트 내부 식별자.
     * @param actor 컴포넌트/버전 자동생성 권한 판정 대상 행위자.
     * @param warnings 미매칭 typeName/componentNames/버전이름 발생 시 추가되는 경고 목록(호출자 소유, 누적).
     * @return 해석된 [FieldResolution].
     */
    private fun resolveFields(
        cmd: IssueImportCommand,
        projectId: UUID,
        actor: ActorId,
        warnings: MutableList<String>,
    ): FieldResolution {
        val emailsToResolve =
            (
                listOfNotNull(cmd.reporterEmail, cmd.assigneeEmail) +
                    cmd.comments.mapNotNull { it.authorEmail } +
                    cmd.worklogs.mapNotNull { it.authorEmail }
            ).map { it.lowercase() }.toSet()
        val resolvedEmails =
            if (emailsToResolve.isEmpty()) emptyMap() else userLookupPort.resolveByEmails(emailsToResolve)

        val reporterId = cmd.reporterEmail?.lowercase()?.let { resolvedEmails[it] } ?: cmd.requesterUserId
        val assigneeId = cmd.assigneeEmail?.lowercase()?.let { resolvedEmails[it] }

        val typeId = resolveTypeId(cmd.typeName, warnings)
        val componentIds = resolveComponentIds(cmd.componentNames, projectId, actor, cmd, warnings)
        val affectsVersionIds = resolveVersionIds(cmd.affectsVersionNames, projectId, actor, cmd, warnings)
        val fixVersionIds = resolveVersionIds(cmd.fixVersionNames, projectId, actor, cmd, warnings)

        return FieldResolution(
            reporterId,
            assigneeId,
            typeId,
            componentIds,
            affectsVersionIds,
            fixVersionIds,
            resolvedEmails,
        )
    }

    /**
     * typeName 을 활성 이슈 타입 id 로 해석한다(대소문자 무시).
     *
     * 미지정([typeName]=null)이면 매핑 없이 null 반환 — [IssueApplicationService.createIssue] 가
     * task 타입으로 자동 폴백하며, 이 경우 경고를 남기지 않는다(사용자가 명시하지 않았으므로).
     * 지정했지만 매칭되는 활성 타입이 없으면 [warnings] 에 경고를 추가하고 null 반환 —
     * 마찬가지로 task 폴백되지만 경고로 사용자에게 알린다.
     *
     * @param typeName 매칭할 이슈 유형 이름. null 이면 무매핑.
     * @param warnings 미매칭 시 경고를 추가할 목록(호출자 소유, 누적).
     * @return 매칭된 [IssueTypeId], 없으면 null(Task 폴백).
     */
    private fun resolveTypeId(
        typeName: String?,
        warnings: MutableList<String>,
    ): IssueTypeId? {
        if (typeName == null) return null
        val matchedId =
            issueTypeRepository.findAll().firstOrNull { it.name.equals(typeName, ignoreCase = true) }?.id
        if (matchedId == null) {
            warnings += "이슈 유형 '$typeName' 을(를) 찾을 수 없어 기본 Task 로 생성했습니다."
        }
        return matchedId
    }

    /**
     * componentNames 를 프로젝트 활성 컴포넌트 id 목록으로 해석한다(대소문자 무시).
     *
     * 미매칭 이름은 [resolveMissingComponent] 로 자동생성을 시도한다(권한 사전 체크 — 클래스 KDoc §3).
     *
     * @param names 매칭할 컴포넌트 이름 목록.
     * @param projectId 조회 범위가 되는 프로젝트 내부 식별자.
     * @param actor 자동생성 권한 판정 대상 행위자.
     * @param cmd 자동생성 시 필요한 projectKey/dryRun 을 담은 원본 커맨드.
     * @param warnings 미매칭+생성불가 시 경고를 추가할 목록(호출자 소유, 누적).
     * @return 매칭·자동생성된 컴포넌트 UUID 목록. 순서는 [names] 순서를 따른다.
     */
    private fun resolveComponentIds(
        names: List<String>,
        projectId: UUID,
        actor: ActorId,
        cmd: IssueImportCommand,
        warnings: MutableList<String>,
    ): List<UUID> {
        if (names.isEmpty()) return emptyList()
        val active = componentRepository.findByProject(projectId)
        val ids = mutableListOf<UUID>()
        // 한 셀 내 중복 이름(대소문자 무시)은 de-dup — 미매칭 이름을 같은 tx 에서 두 번 생성 시도해
        // partial-unique 23505 로 행 전체가 실패하는 것을 방지(NIT-1). active 는 루프 전 1회만 조회.
        for (name in names.distinctBy { it.lowercase() }) {
            val matchedId = active.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            if (matchedId != null) {
                ids += matchedId
            } else {
                resolveMissingComponent(name, projectId, actor, cmd, warnings)?.let { ids += it }
            }
        }
        return ids
    }

    /**
     * 미매칭 컴포넌트 이름 1건의 자동생성을 시도한다.
     *
     * CREATE 권한이 없으면 [ComponentApplicationService.create] 를 아예 호출하지 않고 경고만
     * 남긴다(★ 최우선 제약 — 클래스 KDoc §3, 참여 트랜잭션 오염 방지). [cmd.dryRun] 이면 권한
     * 확인까지만 수행하고 실제 생성은 하지 않는다(부수 효과 없음).
     *
     * @return 자동생성된 컴포넌트 UUID. 권한 없음 또는 dryRun 이면 null.
     */
    @Suppress("ReturnCount") // guard-clause early return 3개(권한없음·dryRun·생성완료) — DEVELOPMENT.md §2.3
    private fun resolveMissingComponent(
        name: String,
        projectId: UUID,
        actor: ActorId,
        cmd: IssueImportCommand,
        warnings: MutableList<String>,
    ): UUID? {
        val hasCreatePermission =
            componentPermissionResolver.hasPermission(actor.value, ComponentPermission.CREATE, projectId)
        if (!hasCreatePermission) {
            warnings += "컴포넌트 '$name' 을(를) 찾을 수 없고 생성 권한이 없어 건너뛰었습니다."
            return null
        }
        if (cmd.dryRun) return null
        val created = componentApplicationService.create(actor.value, cmd.projectKey, name, null, null)
        warnings += "컴포넌트 '$name' 을(를) 찾을 수 없어 자동 생성했습니다."
        return checkNotNull(created.id) { "component create returned null id: name=$name" }
    }

    /**
     * fix/affects 버전 이름 목록을 프로젝트 활성 버전 id 목록으로 해석한다(대소문자 무시).
     *
     * 미매칭 이름은 [resolveMissingVersion] 로 자동생성을 시도한다 — [resolveComponentIds] 와 동형.
     *
     * @param names 매칭할 버전 이름 목록.
     * @param projectId 조회 범위가 되는 프로젝트 내부 식별자.
     * @param actor 자동생성 권한 판정 대상 행위자.
     * @param cmd 자동생성 시 필요한 projectKey/dryRun 을 담은 원본 커맨드.
     * @param warnings 미매칭+생성불가 시 경고를 추가할 목록(호출자 소유, 누적).
     * @return 매칭·자동생성(또는 dry-run 대체값)된 버전 UUID 목록. 순서는 [names] 순서를 따른다.
     */
    private fun resolveVersionIds(
        names: List<String>,
        projectId: UUID,
        actor: ActorId,
        cmd: IssueImportCommand,
        warnings: MutableList<String>,
    ): List<UUID> {
        if (names.isEmpty()) return emptyList()
        val active = versionRepository.findByProject(projectId)
        val ids = mutableListOf<UUID>()
        // 한 셀 내 중복 이름(대소문자 무시)은 de-dup — [resolveComponentIds] 와 동형(NIT-1, 23505 방지).
        for (name in names.distinctBy { it.lowercase() }) {
            val matchedId = active.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id
            if (matchedId != null) {
                ids += matchedId
            } else {
                resolveMissingVersion(name, projectId, actor, cmd, warnings)?.let { ids += it }
            }
        }
        return ids
    }

    /**
     * 미매칭 버전 이름 1건의 자동생성을 시도한다. [resolveMissingComponent] 와 동형(★ 최우선 제약).
     *
     * [cmd.dryRun] 이고 CREATE 권한이 있으면 실제 생성 없이 [DRY_RUN_VERSION_PLACEHOLDER] 로
     * 대체한다 — [rowTriggersUpdate] 가 "이 행이 changeFixVersions/changeAffectsVersions 를
     * 유발하는가" 를 정확히 미리 판정하려면(실제 실행 시 최소 1개 버전이 링크돼 UPDATE 권한이
     * 필요해지는 상황을) dry-run 목록도 비어있지 않게 유지해야 하기 때문이다(§UPDATE 미러,
     * CONCERN C1 과 동일 근거). 이 센티널 값은 dry-run 경로 밖으로 전달되지 않는다.
     *
     * @return 자동생성된(또는 dry-run 대체) 버전 UUID. 권한 없으면 null.
     */
    @Suppress("ReturnCount") // guard-clause early return 3개(권한없음·dryRun 대체·생성완료) — DEVELOPMENT.md §2.3
    private fun resolveMissingVersion(
        name: String,
        projectId: UUID,
        actor: ActorId,
        cmd: IssueImportCommand,
        warnings: MutableList<String>,
    ): UUID? {
        val hasCreatePermission =
            versionPermissionResolver.hasPermission(actor.value, VersionPermission.CREATE, projectId)
        if (!hasCreatePermission) {
            warnings += "버전 '$name' 을(를) 찾을 수 없고 생성 권한이 없어 건너뛰었습니다."
            return null
        }
        if (cmd.dryRun) return DRY_RUN_VERSION_PLACEHOLDER
        val created = versionApplicationService.create(actor.value, cmd.projectKey, name, null, null, null)
        warnings += "버전 '$name' 을(를) 찾을 수 없어 자동 생성했습니다."
        return checkNotNull(created.id) { "version create returned null id: name=$name" }
    }

    /**
     * 도메인/검증 예외를 [IssueImportResult.Failure] 로 변환한다.
     *
     * BC 격리 — issue-tracking BC 내부 예외만 직접 타입 매칭한다.
     * project-workflow 등 타 BC 예외는 [IssueApplicationService.createIssue] 내부에서 이미
     * [IssueWorkflowNotConfiguredException] 등 issue-tracking BC 예외로 변환된 상태로 전파된다.
     *
     * @param cmd 실패한 import 커맨드 — 로그 컨텍스트용.
     * @param e 캐치된 런타임 예외.
     * @return 사유 코드가 매핑된 [IssueImportResult.Failure].
     */
    private fun toFailure(
        cmd: IssueImportCommand,
        e: RuntimeException,
    ): IssueImportResult {
        val reasonCode =
            when (e) {
                is IssueAccessDeniedException -> IssueImportResult.FORBIDDEN
                is IssueProjectNotFoundException -> IssueImportResult.NOT_FOUND
                is IssueTypeNotFoundException -> IssueImportResult.TYPE_NOT_FOUND
                is IssueWorkflowNotConfiguredException -> IssueImportResult.WORKFLOW_NOT_CONFIGURED
                is IllegalArgumentException -> IssueImportResult.VALIDATION
                else -> IssueImportResult.UNKNOWN
            }
        log.warn(
            "issue_import_failed projectKey={} reasonCode={} message={}",
            cmd.projectKey,
            reasonCode,
            e.message,
        )
        return IssueImportResult.failure(reasonCode, e.message)
    }

    companion object {
        /** dryRun 성공 시 [IssueImportResult.Success.issueKey] 자리에 채우는 마커 — 실제 이슈 생성 없음. */
        const val DRY_RUN_MARKER: String = "dry-run-ok"

        /**
         * dry-run 시 미매칭+생성권한있는 버전 이름을 대체하는 nil UUID 센티널([resolveMissingVersion] 참조).
         *
         * 실제 [VersionApplicationService.create] 를 호출하지 않으므로 진짜 UUID 가 없다. "행이
         * changeFixVersions/changeAffectsVersions 를 유발하는가" 판정에서 목록이 비어있지 않음을
         * 표시하는 용도로만 쓰이며, dry-run 경로는 이 값을 실제 링크 호출에 전달하지 않는다.
         */
        private val DRY_RUN_VERSION_PLACEHOLDER: UUID = UUID(0L, 0L)
    }
}
