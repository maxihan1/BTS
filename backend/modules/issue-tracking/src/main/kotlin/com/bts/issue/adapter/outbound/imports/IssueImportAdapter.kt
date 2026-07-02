// IssueImportPort 구현 — CSV/JSON import 행 1건을 issue-tracking 생성 유스케이스로 위임하는 cross-BC 쓰기 어댑터

package com.bts.issue.adapter.outbound.imports

import com.bts.issue.application.AppChangeAssigneeRequest
import com.bts.issue.application.AppChangeVersionsRequest
import com.bts.issue.application.CreateIssueRequest
import com.bts.issue.application.ImportStatusOutcome
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueImportStatusService
import com.bts.issue.application.UpdateIssueRequest
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
     * 않음) FORBIDDEN 하드 실패로 미러하지 않는다.
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
        warnStatusPermissionIfNeeded(cmd, actor, warnings)
        return IssueImportResult.success(DRY_RUN_MARKER, warnings)
    }

    /**
     * [cmd.statusName] 이 지정됐는데 TRANSITION 권한이 없으면 dry-run 경고를 추가한다.
     *
     * [IssueImportStatusService.applyImportedStatus] 는 TRANSITION 권한이 없어도 예외를 던지지 않고
     * [ImportStatusOutcome.NoPermission] 로 best-effort 강등하므로(클래스 KDoc §4), 이 미리보기도
     * FORBIDDEN 실패가 아닌 경고로만 남긴다 — 실제 실행 결과와 어긋나지 않는다.
     */
    private fun warnStatusPermissionIfNeeded(
        cmd: IssueImportCommand,
        actor: ActorId,
        warnings: MutableList<String>,
    ) {
        if (cmd.statusName != null && !hasTransitionPermission(actor, cmd.projectKey)) {
            warnings += "상태 변경 권한이 없어 '${cmd.statusName}' 적용이 건너뛰어질 수 있습니다."
        }
    }

    /** 프로젝트 스코프로 TRANSITION 권한을 미리 확인한다(§UPDATE 미러와 동일 근거 — [validateDryRun] KDoc). */
    private fun hasTransitionPermission(
        actor: ActorId,
        projectKey: String,
    ): Boolean = permissionResolver.hasPermission(actor.value, IssuePermission.TRANSITION, IssueScope.Project(projectKey))

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
     * 실제 이슈 생성 + 후속 필드 설정을 같은 트랜잭션에서 수행한다.
     *
     * 1. [resolveFields] — 이메일→담당자/리포터, 이름→타입/컴포넌트/버전 매핑(미매칭 시 권한 있으면 자동생성).
     * 2. [IssueApplicationService.createIssue] — CREATE_ISSUE 권한 게이트 포함(권한 위임).
     * 3. [applyPriorityAndLabelsIfPresent] — priority/labels 지정 시 updateIssue.
     * 4. [applyAssigneeIfPresent] — assignee 매칭 시 changeAssignee.
     * 5. [applyVersionLinks] — affects/fix 버전이 매칭·자동생성됐으면 링크.
     * 6. [applyStatusIfPresent] — [cmd.statusName] 있으면 상태 반영(best-effort).
     *
     * 3~6 중 어느 하나라도 예외를 던지면 [importIssue] 의 catch 블록이 트랜잭션 전체를
     * rollback-only 로 표시하므로, 이미 삽입된 이슈(2)까지 함께 롤백된다(행 원자성).
     * OCC 버전은 각 단계의 반환값으로 계속 스레딩한다(currentVersion).
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
                    request = AppChangeVersionsRequest(versionIds = resolution.affectsVersionIds, expectedVersion = version),
                ).version
        }
        if (resolution.fixVersionIds.isNotEmpty()) {
            version =
                issueApplicationService.changeFixVersions(
                    actor = actor,
                    key = key,
                    request = AppChangeVersionsRequest(versionIds = resolution.fixVersionIds, expectedVersion = version),
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
        return when (val outcome = issueImportStatusService.applyImportedStatus(actor, key, statusName, currentVersion)) {
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
     * import 커맨드의 이메일/이름 필드를 실제 식별자로 해석한 결과.
     *
     * @property reporterId 리포터 사용자 UUID. 이메일 미매칭 시 requesterUserId 로 폴백된 값.
     * @property assigneeId 담당자 사용자 UUID. 이메일 미매칭 시 null(미할당).
     * @property typeId 매칭된 이슈 타입 식별자. typeName 미지정/미매칭 시 null(Task 폴백).
     * @property componentIds 매칭·자동생성된 컴포넌트 UUID 목록. 미매칭+생성권한없음 이름은 제외된다.
     * @property affectsVersionIds 매칭·자동생성된 "영향받는 버전" UUID 목록.
     * @property fixVersionIds 매칭·자동생성된 "수정 예정 버전" UUID 목록.
     */
    private data class FieldResolution(
        val reporterId: UUID,
        val assigneeId: UUID?,
        val typeId: IssueTypeId?,
        val componentIds: List<UUID>,
        val affectsVersionIds: List<UUID>,
        val fixVersionIds: List<UUID>,
    )

    /**
     * [cmd] 의 이메일/이름 필드를 실제 식별자로 일괄 해석한다.
     *
     * - reporterEmail/assigneeEmail: [userLookupPort.resolveByEmails] 1회 배치 호출로 해석.
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
            listOfNotNull(cmd.reporterEmail, cmd.assigneeEmail)
                .map { it.lowercase() }
                .toSet()
        val resolvedEmails =
            if (emailsToResolve.isEmpty()) emptyMap() else userLookupPort.resolveByEmails(emailsToResolve)

        val reporterId = cmd.reporterEmail?.lowercase()?.let { resolvedEmails[it] } ?: cmd.requesterUserId
        val assigneeId = cmd.assigneeEmail?.lowercase()?.let { resolvedEmails[it] }

        val typeId = resolveTypeId(cmd.typeName, warnings)
        val componentIds = resolveComponentIds(cmd.componentNames, projectId, actor, cmd, warnings)
        val affectsVersionIds = resolveVersionIds(cmd.affectsVersionNames, projectId, actor, cmd, warnings)
        val fixVersionIds = resolveVersionIds(cmd.fixVersionNames, projectId, actor, cmd, warnings)

        return FieldResolution(reporterId, assigneeId, typeId, componentIds, affectsVersionIds, fixVersionIds)
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
        for (name in names) {
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
        for (name in names) {
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
