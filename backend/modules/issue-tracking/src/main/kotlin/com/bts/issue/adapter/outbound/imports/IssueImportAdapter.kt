// IssueImportPort 구현 — CSV/JSON import 행 1건을 issue-tracking 생성 유스케이스로 위임하는 cross-BC 쓰기 어댑터

package com.bts.issue.adapter.outbound.imports

import com.bts.issue.application.AppChangeAssigneeRequest
import com.bts.issue.application.AppChangeVersionsRequest
import com.bts.issue.application.CreateIssueRequest
import com.bts.issue.application.ImportStatusOutcome
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueImportStatusService
import com.bts.issue.application.UpdateIssueRequest
import com.bts.issue.attachment.MinioStorageException
import com.bts.issue.attachment.application.AttachmentInfectedException
import com.bts.issue.attachment.application.AttachmentScanUnavailableException
import com.bts.issue.attachment.application.IssueAttachmentService
import com.bts.issue.attachment.application.UnsupportedAttachmentTypeException
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.component.application.ComponentApplicationService
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.history.IssueChangeGroup
import com.bts.issue.history.IssueChangeItem
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.application.VersionApplicationService
import com.bts.issue.version.repository.VersionRepository
import com.bts.issue.worklog.application.WorklogService
import com.bts.shared.issue.ImportAttachment
import com.bts.shared.issue.ImportAttachmentSource
import com.bts.shared.issue.ImportChangeGroup
import com.bts.shared.issue.ImportChangeItem
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
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLConnection
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
 * 6. **첨부/이력 동반 생성 — 사전 체크 + 행 원자성 (PR4, ★3)** — [applyAttachments]/[applyChangelog]
 *    도 5와 동일하게 UPDATE 권한을 [hasIssueUpdatePermission] 으로 먼저 확인해 권한 없으면 전량
 *    스킵 + 집약 경고만 남긴다. 아래 두 함정을 eng-review 에서 BLOCKER 로 지정해 반영했다.
 *    - **BLOCKER-1(스트림 close)** — [IssueAttachmentService.upload] 의 `input` 은 호출자 close
 *      책임([IssueAttachmentService] KDoc). [ImportAttachmentSource.open] 이 반환한 스트림을
 *      [applyAttachmentItem] 이 반드시 `.use { }` 로 닫는다 — 안 닫으면 대량 import 에서 FD/inflater
 *      누수(FR-AC-01 MinIO 스트림 누수 회귀 동형).
 *    - **BLOCKER-2(insert throw 사전체크)** — [com.bts.issue.attachment.repository.AttachmentRepository.insert]
 *      는 `@Transactional`(REQUIRED)이라 이 메서드의 참여 트랜잭션에 합류한다. 긴 filename
 *      (issue_attachments.filename VARCHAR(500) 초과) 이나 긴 contentType(content_type VARCHAR(100)
 *      초과)로 insert 가 throw 하면 catch 해도 rollback-only 가 이미 세팅돼 무력하다 — 즉 "첨부 하나
 *      건너뛰기"가 아니라 **행 전체(이슈+댓글+worklog+다른 첨부)가 함께 롤백**된다. 따라서
 *      [applyAttachmentItem] 은 upload 호출 **이전에** filename/contentType 길이를 사전 판정해
 *      초과 시 스킵+경고로 강등한다(길이는 insert 이전에 이미 알 수 있으므로 throw 자체가 발생하지
 *      않는다 — best-effort 로 "강등"하는 게 아니라 **애초에 throw 를 유발하지 않는** 설계).
 *      이 사전체크 이후에도 [applyAttachmentItem] 이 잡는 잔여 throw 집합은 **권한(방어적,
 *      실제로는 5와 동일하게 사전 확인됨)·MIME 거부·바이러스 스캔 미가용/감염·MinIO put 실패**
 *      뿐이며, 이 목록 밖의 예외(예: 예상외 insert 실패)는 잡지 않고 그대로 전파해 [importIssue]
 *      의 catch 블록이 행 전체를 롤백하도록 둔다(행 원자성 안전망, S31/[FaultInjectingWorklogService]
 *      동형 — `IssueImportAdapterTest` S44 참조).
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
 * ### LargeClass — 분리 실익 없음 (PR4)
 *
 * PR1~PR4 가 같은 포트 구현체에 코어/컴포넌트·버전/댓글·worklog/첨부·이력 6단계를 누적한 결과다.
 * 각 단계가 동일 사전체크+best-effort 강등 패턴(apply 계열 함수 + 항목별 apply 함수 + warn 계열
 * 집약 함수)을 공유해 별도 협력자 클래스로 쪼개도 응집도 이득이 없다(모두 같은 [IssueImportCommand]
 * 1행·같은 트랜잭션 경계를 공유) — [IssueImportAdapterTest] 의 동일 판단(`@Suppress("LargeClass")`)과 정합.
 *
 * @see IssueImportPort
 * @see IssueApplicationService
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
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
    private val attachmentService: IssueAttachmentService,
    private val historyRecorder: IssueHistoryRecorder,
    private val clock: Clock = Clock.systemUTC(),
) : IssueImportPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 파싱된 import 행 1건으로 이슈 생성을 요청한다(하위호환 1-arg — 첨부 소스 없음).
     *
     * [IssueImportPort] 의 1-arg default 구현(`importIssue(cmd, null)` 위임, CONCERN-6)을 그대로
     * 써도 **기능적으로는** 동일하지만, 이 어댑터는 명시적으로 override 하고 별도로 `@Transactional`
     * 을 붙인다 — Kotlin 인터페이스 default 메서드를 통한 위임은 Spring AOP self-invocation
     * 함정과 동형이다. reflection 기반 join point 호출([org.springframework.aop.support.AopUtils]
     * `invokeJoinpointUsingReflection`)이 **raw target 인스턴스**에서 메서드를 실행하므로, default
     * 구현 내부의 `importIssue(cmd, null)` 호출이 CGLIB 프록시를 다시 거치지 않고 raw target 을
     * 직접 호출한다 — 이 자체는 REQUIRED 전파라 무해하지만(이미 활성 트랜잭션에 합류),
     * **진입점인 1-arg 메서드 자체**가 `@Transactional` 프록시 인터셉션을 받지 못하면 애초에
     * 트랜잭션이 시작되지 않는다(메모리 transaction-self-invocation-requires-new 동형 — 실제로
     * `TestConfig` 의 `@EnableTransactionManagement(proxyTargetClass=true)` 하에서 1-arg 진입 시
     * `TransactionAspectSupport.currentTransactionStatus()` 가 `NoTransactionException` 을 던지는
     * 것으로 실증됨, `IssueImportAdapterTest` 무회귀 검증 중 발견). 따라서 이 메서드에 직접
     * `@Transactional` 을 선언해 1-arg 진입점도 확실히 프록시 인터셉션을 받도록 한다.
     *
     * @param cmd 이슈 생성 커맨드.
     * @return 생성 결과. [importIssue] 2-arg 오버로드(첨부 소스=null)에 위임한 결과와 동일하다.
     */
    @Transactional
    override fun importIssue(cmd: IssueImportCommand): IssueImportResult = importIssue(cmd, null)

    /**
     * 파싱된 import 행 1건으로 이슈 생성을 요청한다(첨부 소스 포함, PR4 주 메서드).
     *
     * 흐름.
     * 1. summary 공백 검증 — 즉시 [IssueImportResult.VALIDATION] 실패 반환(부수 효과 없음).
     * 2. 프로젝트 존재 확인 — 없으면 [IssueImportResult.NOT_FOUND] (부수 효과 없음).
     * 3. [cmd.dryRun] 이면 [validateDryRun](첨부 소스 미사용 — zip 미오픈), 아니면 [executeImport] 위임.
     * 4. 위 3에서 발생한 도메인/검증 예외는 트랜잭션을 rollback-only 로 표시한 뒤
     *    [toFailure] 로 결과 객체 변환 — 도메인 예외가 호출자(cross-BC)까지 누출되지 않는다.
     *
     * 이 메서드도 [importIssue](1-arg)와 동일 이유로 `@Transactional` 을 직접 선언한다 — 외부
     * 호출자가 2-arg 를 직접 호출하는 경로(예: search-export-import 워커)는 물론, 1-arg 진입 후
     * 내부 위임되는 경로에서도 REQUIRED 전파로 이미 활성화된 트랜잭션에 합류한다.
     *
     * @param cmd 이슈 생성 커맨드.
     * @param attachments 첨부 파일 바이너리 조회 포트. null 이면 [cmd.attachments] 가 있어도 전량
     *   스킵된다([applyAttachmentItem] 이 매 항목 [ImportAttachmentSource.open] 호출 전에 null 을
     *   확인 — "원본 없음"과 동일하게 처리, 별도 예외 없음).
     * @return 생성 결과. dryRun 이면 실제 생성 없이 검증 결과만 반환.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    @Transactional
    override fun importIssue(
        cmd: IssueImportCommand,
        attachments: ImportAttachmentSource?,
    ): IssueImportResult {
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
                executeImport(cmd, actor, projectId, attachments)
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
        warnAttachmentsIfNeeded(cmd, actor, warnings)
        warnChangelogIfNeeded(cmd, actor, warnings)
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

    /**
     * [warnCommentsWorklogsIfNeeded] 의 worklog 부분 — [applyWorklogs] 가 낼 경고를 미리 예측한다.
     *
     * unmatchedCount/missingStartedAtCount 는 실제로 생성될 worklog([created], timeSpentSeconds>0)만
     * 대상으로 집계한다 — [applyWorklogItem] 이 timeSpentSeconds≤0 인 항목은 생성 전에 스킵하며
     * unmatchedAuthor/missingStartedAt 을 애초에 판정하지 않기 때문이다([WorklogApplyOutcome] KDoc,
     * 코드리뷰 CONCERN C-1). invalidCount(소요 시간 0 이하)만 전체([cmd.worklogs]) 기준으로 유지한다.
     */
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
        val created = cmd.worklogs.filter { it.timeSpentSeconds > 0 }
        val unmatchedCount = created.count { isAuthorUnmatched(it.authorEmail, resolution.resolvedEmails) }
        if (unmatchedCount > 0) {
            warnings += "워크로그 ${unmatchedCount}건 작성자 이메일이 매칭되지 않아 요청자로 대체될 수 있습니다."
        }
        val missingStartedAtCount = created.count { it.startedAt == null }
        if (missingStartedAtCount > 0) {
            warnings += "워크로그 ${missingStartedAtCount}건 시작 시각이 없어 import 실행 시각으로 대체될 수 있습니다."
        }
    }

    /**
     * dry-run 에서 [cmd.attachments] 가 실제 실행([applyAttachments]) 시 낼 best-effort 경고를
     * 미리 산출한다(PR4, ★3). [warnCommentsWorklogsIfNeeded] 와 동일 원칙 — 이 헬퍼의 경고는
     * [rowTriggersUpdate] 하드 FORBIDDEN 판정에 절대 엮이지 않는다(CONCERN-A 재발 방지).
     *
     * dry-run 은 zip 을 열지 않으므로(실제 스캔·업로드 불가) 권한과 filename 유효성(빈 값/길이 초과,
     * BLOCKER-2 사전체크와 동일 기준)만 미리보기하고, MIME/스캔/저장 실패는 미리 판정하지 않는다.
     */
    private fun warnAttachmentsIfNeeded(
        cmd: IssueImportCommand,
        actor: ActorId,
        warnings: MutableList<String>,
    ) {
        if (cmd.attachments.isEmpty()) return
        if (!hasUpdatePermission(actor, cmd.projectKey)) {
            warnings += "첨부 ${cmd.attachments.size}건은 권한이 없어 건너뛰어질 수 있습니다."
            return
        }
        val invalidCount =
            cmd.attachments.count { it.filename.isBlank() || it.filename.length > MAX_ATTACHMENT_FILENAME_LENGTH }
        if (invalidCount > 0) {
            warnings += "첨부 ${invalidCount}건은 파일명 값이 유효하지 않아 건너뛰어질 수 있습니다."
        }
    }

    /**
     * dry-run 에서 [cmd.changelog] 가 실제 실행([applyChangelog]) 시 낼 best-effort 경고를
     * 미리 산출한다(PR4, ★3) — [warnAttachmentsIfNeeded] 와 동일 원칙(CONCERN-A 재발 방지).
     *
     * zip 을 열지 않는 dry-run 특성과 무관하게 occurredAt 은 이미 파싱된 [ImportChangeGroup.occurredAt]
     * 값이므로(널이면 파싱 실패) 이 검사는 실제 실행과 100% 동일 기준이다.
     */
    private fun warnChangelogIfNeeded(
        cmd: IssueImportCommand,
        actor: ActorId,
        warnings: MutableList<String>,
    ) {
        if (cmd.changelog.isEmpty()) return
        if (!hasUpdatePermission(actor, cmd.projectKey)) {
            warnings += "변경 이력 ${cmd.changelog.size}건은 권한이 없어 건너뛰어질 수 있습니다."
            return
        }
        val missingTimeCount = cmd.changelog.count { it.occurredAt == null }
        if (missingTimeCount > 0) {
            warnings += "변경 이력 ${missingTimeCount}건은 발생 시각을 확인할 수 없어 건너뛰어질 수 있습니다."
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
     * 9. [applyAttachments] — [cmd.attachments] 를 [IssueAttachmentService.upload] 로 위임
     *    (best-effort, PR4). [attachmentSource] 가 제공한 스트림을 조회한다.
     * 10. [applyChangelog] — [cmd.changelog] 를 [IssueHistoryRecorder.recordImported] 로 위임
     *     (best-effort, PR4).
     *
     * 3~10 중 어느 하나라도 예외를 던지면 [importIssue] 의 catch 블록이 트랜잭션 전체를
     * rollback-only 로 표시하므로, 이미 삽입된 이슈(2)까지 함께 롤백된다(행 원자성).
     * 7~10 은 각자 사전체크(UPDATE 권한, worklog timeSpent≤0, 첨부 filename/contentType 길이 —
     * ★2/★3 KDoc)로 호출 전에 스킵을 강등하므로 정상 경로에서는 이 예외를 던지지 않는다 —
     * 그럼에도 예상외 예외가 발생하면 이 안전망(행 원자성)이 여전히 이슈까지 롤백해 부분 반영을 막는다.
     * OCC 버전은 각 단계의 반환값으로 계속 스레딩한다(currentVersion). 7~10 은 issues.version 을
     * 증가시키지 않으므로(worklog 롤업 no-bump 원칙, [WorklogService] KDoc) currentVersion 스레딩과 무관하다.
     *
     * @param cmd 처리할 import 커맨드.
     * @param actor 생성 행위자(=requesterUserId).
     * @param projectId 대상 프로젝트 내부 식별자.
     * @param attachmentSource 첨부 파일 바이너리 조회 포트. null 이면 첨부 전량 스킵.
     * @return 생성된 이슈 키를 포함한 [IssueImportResult.success].
     */
    private fun executeImport(
        cmd: IssueImportCommand,
        actor: ActorId,
        projectId: UUID,
        attachmentSource: ImportAttachmentSource?,
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
        applyAttachments(cmd, resolution, actor, created.key, attachmentSource, warnings)
        applyChangelog(cmd, resolution, actor, created.id.value, created.key, warnings)

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

    // ── 첨부 동반 생성 (PR4, ★3) ─────────────────────────────────────────────────

    /**
     * [cmd.attachments] 를 [IssueAttachmentService.upload] 로 위임한다 — best-effort (PR4, ★3).
     *
     * [applyComments]/[applyWorklogs] 와 동일하게 UPDATE 권한이 없으면 전량 스킵 + 집약 경고만
     * 남긴다. 권한이 있으면 [applyAttachmentItem] 으로 항목별 사전체크(길이·MIME·스캔·저장)와
     * upload 를 수행하고, 그 결과를 [warnAttachmentOutcomes] 로 집약 경고로 변환한다.
     *
     * @param cmd import 커맨드([ImportAttachment] 목록 출처).
     * @param resolution [resolveFields] 결과(author 이메일 배치 해석 포함).
     * @param actor UPDATE 권한 판정 및 upload 호출 actor(=[cmd.requesterUserId]).
     * @param key 방금 생성된 이슈 키 — 직전 createIssue 로 존재가 보장되므로 404 없음(★2 동형).
     * @param attachmentSource 첨부 바이너리 조회 포트. null 이면 모든 항목이 NOT_FOUND 로 스킵된다.
     * @param warnings 집약 경고를 추가할 목록(호출자 소유, 누적).
     */
    private fun applyAttachments(
        cmd: IssueImportCommand,
        resolution: FieldResolution,
        actor: ActorId,
        key: IssueKey,
        attachmentSource: ImportAttachmentSource?,
        warnings: MutableList<String>,
    ) {
        if (cmd.attachments.isEmpty()) return
        if (!hasIssueUpdatePermission(actor, key)) {
            warnings += "첨부 ${cmd.attachments.size}건은 권한이 없어 건너뛰었습니다."
            return
        }
        val outcomes =
            cmd.attachments.map { applyAttachmentItem(it, cmd, resolution, actor, key, attachmentSource) }
        warnAttachmentOutcomes(outcomes, warnings)
    }

    /**
     * 첨부 1건의 사전체크 + upload 를 수행한다([applyAttachments] 루프 본체).
     *
     * 순서. (1) filename/contentType 길이 사전체크(BLOCKER-2, [IssueImportAdapter] 클래스 KDoc ★3
     * 참조 — insert throw 를 애초에 유발하지 않기 위함) → (2) [attachmentSource] 로 스트림 조회
     * (null 이거나 zip 내 미발견이면 NOT_FOUND) → (3) [InputStream.use] 로 스트림을 반드시 닫으며
     * upload(BLOCKER-1, FD 누수 방지).
     *
     * upload 가 던질 수 있는 예외 중 [UnsupportedAttachmentTypeException]/[AttachmentInfectedException]/
     * [AttachmentScanUnavailableException]/[MinioStorageException] 은 모두 insert **이전** 단계에서
     * 발생하므로(클래스 KDoc ★3) 여기서 잡아 best-effort 로 강등해도 참여 트랜잭션이 오염되지 않는다.
     * [IssueAccessDeniedException] 도 방어적으로 잡는다([applyAttachments] 의 사전 UPDATE 권한 확인과
     * 이 시점의 [IssueAttachmentService.upload] 내부 권한 확인이 동일 스코프이므로 정상 경로에서는
     * 발생하지 않지만, 목록 형태로 남겨 두는 것이 ★3 "잔여 throw 집합"의 완전성을 보장한다).
     * 이 목록 밖의 예외(즉 insert 자체의 예상외 실패)는 잡지 않고 그대로 전파한다 — 행 원자성 안전망이
     * 이슈까지 롤백한다(`IssueImportAdapterTest` S44).
     *
     * @return 스킵 사유([AttachmentSkipReason]), 성공했으면 null.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount") // 잔여 throw 집합(★3) 개별 catch + guard-clause return 4개(§2.3)
    private fun applyAttachmentItem(
        importAttachment: ImportAttachment,
        cmd: IssueImportCommand,
        resolution: FieldResolution,
        actor: ActorId,
        key: IssueKey,
        attachmentSource: ImportAttachmentSource?,
    ): AttachmentSkipReason? {
        if (importAttachment.filename.isBlank() || importAttachment.filename.length > MAX_ATTACHMENT_FILENAME_LENGTH) {
            return AttachmentSkipReason.INVALID_FILENAME
        }
        val contentType = resolveAttachmentContentType(importAttachment)
        if (contentType.length > MAX_ATTACHMENT_CONTENT_TYPE_LENGTH) {
            return AttachmentSkipReason.CONTENT_TYPE_TOO_LONG
        }
        val stream =
            attachmentSource?.open(importAttachment.filename, cmd.sourceKey) ?: return AttachmentSkipReason.NOT_FOUND
        val uploaderId = resolveAuthorId(importAttachment.authorEmail, resolution.resolvedEmails, cmd.requesterUserId)
        return try {
            stream.use { uploadAttachment(it, importAttachment, contentType, actor, key, uploaderId) }
            null
        } catch (e: UnsupportedAttachmentTypeException) {
            log.warn(
                "import_attachment_unsupported_type filename={} contentType={} cause={}",
                importAttachment.filename,
                contentType,
                e.message,
            )
            AttachmentSkipReason.UNSUPPORTED_TYPE
        } catch (e: AttachmentInfectedException) {
            log.warn("import_attachment_infected filename={} cause={}", importAttachment.filename, e.message)
            AttachmentSkipReason.INFECTED
        } catch (e: AttachmentScanUnavailableException) {
            log.warn("import_attachment_scan_unavailable filename={} cause={}", importAttachment.filename, e.message)
            AttachmentSkipReason.SCAN_UNAVAILABLE
        } catch (e: MinioStorageException) {
            log.warn("import_attachment_storage_failure filename={} cause={}", importAttachment.filename, e.message)
            AttachmentSkipReason.STORAGE_FAILURE
        } catch (e: IssueAccessDeniedException) {
            log.warn("import_attachment_permission_denied filename={} cause={}", importAttachment.filename, e.message)
            AttachmentSkipReason.PERMISSION_DENIED
        }
    }

    /**
     * [importAttachment.mimeType] 이 없으면 파일명 확장자로 MIME 을 유추하고, 그마저 실패하면
     * [DEFAULT_ATTACHMENT_CONTENT_TYPE] 으로 폴백한다([com.bts.shared.issue.ImportAttachment.mimeType] KDoc).
     */
    private fun resolveAttachmentContentType(importAttachment: ImportAttachment): String =
        importAttachment.mimeType
            ?: URLConnection.guessContentTypeFromName(importAttachment.filename)
            ?: DEFAULT_ATTACHMENT_CONTENT_TYPE

    /**
     * 사전체크·스트림 조회를 통과한 첨부 1건을 실제 업로드한다.
     *
     * [importAttachment.createdAt]/[uploaderId] 를 그대로 전달해 원본(Jira 등) 업로드 시각·업로더를
     * 보존한다(Task 2, [IssueAttachmentService.upload] createdAt/uploadedBy 주입 파라미터).
     */
    @Suppress("LongParameterList")
    private fun uploadAttachment(
        stream: InputStream,
        importAttachment: ImportAttachment,
        contentType: String,
        actor: ActorId,
        key: IssueKey,
        uploaderId: UUID,
    ) {
        val (resolvedStream, sizeBytes) = resolveAttachmentBytes(stream, importAttachment.sizeBytes)
        attachmentService.upload(
            actor = actor,
            issueKey = key,
            filename = importAttachment.filename,
            contentType = contentType,
            sizeBytes = sizeBytes,
            input = resolvedStream,
            createdAt = importAttachment.createdAt,
            uploadedBy = uploaderId,
        )
    }

    /**
     * [providedSizeBytes] 가 있으면(원본 메타 보존) 그대로 사용한다. 없으면(원본 메타 누락) 스트림을
     * 1회 전량 읽어 실제 바이트 수를 계산한다 — [AttachmentStoragePort.put] 에 정확한 Content-Length
     * 를 전달해야 하므로(부정확한 값은 MinIO put 실패/손상으로 이어질 수 있음) "미상"을 그대로
     * 흘려보낼 수 없다. 이 경로만 스트림 전체를 메모리에 적재하는 트레이드오프를 감수한다 — Jira
     * export 는 통상 `fields.attachment[].size` 를 포함하므로 일반 경로는 스트리밍을 유지한다.
     */
    private fun resolveAttachmentBytes(
        stream: InputStream,
        providedSizeBytes: Long?,
    ): Pair<InputStream, Long> {
        if (providedSizeBytes != null) return stream to providedSizeBytes
        val bytes = stream.readBytes()
        return ByteArrayInputStream(bytes) to bytes.size.toLong()
    }

    /**
     * [applyAttachmentItem] 결과 목록을 유형별로 집계해 경고 1건씩으로 강등한다
     * ([warnWorklogOutcomes] 와 동일한 best-effort 집약 방식, Maxi 확정).
     */
    private fun warnAttachmentOutcomes(
        outcomes: List<AttachmentSkipReason?>,
        warnings: MutableList<String>,
    ) {
        warnAttachmentReasonIfPresent(outcomes, AttachmentSkipReason.INVALID_FILENAME, "파일명이 비어있거나 너무 길어", warnings)
        warnAttachmentReasonIfPresent(outcomes, AttachmentSkipReason.CONTENT_TYPE_TOO_LONG, "파일 형식 값이 너무 길어", warnings)
        warnAttachmentReasonIfPresent(outcomes, AttachmentSkipReason.NOT_FOUND, "원본 파일을 찾을 수 없어", warnings)
        warnAttachmentReasonIfPresent(outcomes, AttachmentSkipReason.UNSUPPORTED_TYPE, "허용되지 않는 파일 형식이라", warnings)
        warnAttachmentReasonIfPresent(outcomes, AttachmentSkipReason.INFECTED, "바이러스 스캔에서 감염이 탐지되어", warnings)
        warnAttachmentReasonIfPresent(outcomes, AttachmentSkipReason.SCAN_UNAVAILABLE, "바이러스 스캔을 수행할 수 없어", warnings)
        warnAttachmentReasonIfPresent(outcomes, AttachmentSkipReason.STORAGE_FAILURE, "저장소 오류로", warnings)
        warnAttachmentReasonIfPresent(outcomes, AttachmentSkipReason.PERMISSION_DENIED, "권한이 없어", warnings)
    }

    /** [warnAttachmentOutcomes] 의 사유별 집계 1줄 — 사유가 0건이면 경고를 남기지 않는다. */
    private fun warnAttachmentReasonIfPresent(
        outcomes: List<AttachmentSkipReason?>,
        reason: AttachmentSkipReason,
        reasonPhrase: String,
        warnings: MutableList<String>,
    ) {
        val count = outcomes.count { it == reason }
        if (count > 0) {
            warnings += "첨부 ${count}건은 $reasonPhrase 건너뛰었습니다."
        }
    }

    /**
     * 첨부 1건 스킵 사유([applyAttachmentItem]) — [warnAttachmentOutcomes] 집계 입력.
     * insert 이전 단계에서만 발생하는 사유만 포함한다(★3 "잔여 throw 집합") — insert 자체의
     * 예상외 실패는 이 enum 에 없으며 [applyAttachmentItem] 이 잡지 않고 그대로 전파한다.
     */
    private enum class AttachmentSkipReason {
        INVALID_FILENAME,
        CONTENT_TYPE_TOO_LONG,
        NOT_FOUND,
        UNSUPPORTED_TYPE,
        INFECTED,
        SCAN_UNAVAILABLE,
        STORAGE_FAILURE,
        PERMISSION_DENIED,
    }

    // ── 변경 이력(changelog) 동반 재생 (PR4, ★3) ────────────────────────────────

    /**
     * [cmd.changelog] 를 [IssueHistoryRecorder.recordImported] 로 위임한다 — best-effort (PR4, ★3).
     *
     * [applyAttachments] 와 동일하게 UPDATE 권한이 없으면 전량 스킵 + 집약 경고만 남긴다. 권한이
     * 있으면 [capChangelogGroups] 로 이슈당 상한(1000)을 적용하고, 각 그룹을 [applyChangelogGroup]
     * 으로 처리한 뒤 [warnChangelogOutcomes] 로 집약 경고를 남긴다.
     *
     * [IssueHistoryRecorder.recordImported] 자체는 권한 확인을 하지 않는다([IssueHistoryRecorder] KDoc
     * "detector/resolver 미경유") — 그래서 이 사전 UPDATE 권한 체크가 유일한 게이트다.
     *
     * @param cmd import 커맨드([ImportChangeGroup] 목록 출처).
     * @param resolution [resolveFields] 결과(author 이메일 배치 해석 포함).
     * @param actor UPDATE 권한 판정 actor(=[cmd.requesterUserId]).
     * @param issueId 방금 생성된 이슈의 내부 UUID([IssueChangeGroup.issueId] 대상).
     * @param key 방금 생성된 이슈 키([IssueChangeGroup.issueKey] 대상 — 새로 발급된 BTS 키).
     * @param warnings 집약 경고를 추가할 목록(호출자 소유, 누적).
     */
    private fun applyChangelog(
        cmd: IssueImportCommand,
        resolution: FieldResolution,
        actor: ActorId,
        issueId: UUID,
        key: IssueKey,
        warnings: MutableList<String>,
    ) {
        if (cmd.changelog.isEmpty()) return
        if (!hasIssueUpdatePermission(actor, key)) {
            warnings += "변경 이력 ${cmd.changelog.size}건은 권한이 없어 건너뛰었습니다."
            return
        }
        val (groups, cappedCount) = capChangelogGroups(cmd.changelog)
        var skippedTimeCount = 0
        var unmappedFieldCount = 0
        for (importGroup in groups) {
            val outcome = applyChangelogGroup(importGroup, resolution, issueId, key)
            skippedTimeCount += outcome.skippedTime
            unmappedFieldCount += outcome.unmappedFields
        }
        warnChangelogOutcomes(skippedTimeCount, unmappedFieldCount, cappedCount, warnings)
    }

    /**
     * 이력 그룹 1건을 처리한다([applyChangelog] 루프 본체).
     *
     * occurredAt(이미 파싱된 [ImportChangeGroup.occurredAt])이 null 이면 그룹 전체를 스킵한다
     * (Maxi 결정 — comment/worklog/첨부의 "시각 없으면 import 실행 시각으로 대체" 폴백과 달리,
     * 재생 이력은 원본 발생 시각 없이는 감사 가치가 없어 대체하지 않고 스킵한다). items 를
     * [mapChangelogItems] 로 BTS 필드로 매핑하고, 매핑된 항목이 하나도 없으면(전 item 미매핑)
     * 그룹 자체를 기록하지 않는다. author 이메일이 매칭되지 않으면 **actorId=null** 로 저장한다
     * (comment/worklog/첨부와 달리 requester 로 폴백하지 않음 — Maxi 결정, 원본 author 를 requester
     * 로 위장 기록하면 감사 이력이 부정확해지기 때문).
     */
    @Suppress("ReturnCount") // guard-clause early return 3개(occurredAt없음·전부미매핑·기록완료) — DEVELOPMENT.md §2.3
    private fun applyChangelogGroup(
        importGroup: ImportChangeGroup,
        resolution: FieldResolution,
        issueId: UUID,
        key: IssueKey,
    ): ChangelogGroupOutcome {
        val occurredAt = importGroup.occurredAt ?: return ChangelogGroupOutcome(skippedTime = 1, unmappedFields = 0)
        var unmappedFieldCount = 0
        val mappedItems = mapChangelogItems(importGroup.items) { unmappedFieldCount++ }
        if (mappedItems.isEmpty()) return ChangelogGroupOutcome(skippedTime = 0, unmappedFields = unmappedFieldCount)
        val actorId = importGroup.authorEmail?.lowercase()?.let { resolution.resolvedEmails[it] }
        historyRecorder.recordImported(
            IssueChangeGroup(
                issueId = issueId,
                issueKey = key.value,
                actorId = actorId,
                items = mappedItems,
                createdAt = occurredAt,
            ),
        )
        return ChangelogGroupOutcome(skippedTime = 0, unmappedFields = unmappedFieldCount)
    }

    /**
     * [items] 를 [CHANGELOG_FIELD_MAP] 으로 BTS 필드에 매핑한다. 매핑되지 않는 field 는
     * [onUnmapped] 콜백으로 집계만 하고 결과에서 제외한다(스킵) — fromValue/toValue 는 Jira
     * 원본 표시 문자열을 그대로 보존한다(BTS 라벨 resolver 미적용, plan Maxi 결정 §1).
     */
    private fun mapChangelogItems(
        items: List<ImportChangeItem>,
        onUnmapped: () -> Unit,
    ): List<IssueChangeItem> =
        items.mapNotNull { item ->
            val mappedField = CHANGELOG_FIELD_MAP[item.field]
            if (mappedField == null) {
                onUnmapped()
                null
            } else {
                IssueChangeItem(field = mappedField, fromValue = item.fromValue, toValue = item.toValue)
            }
        }

    /**
     * [groups] 가 [MAX_CHANGELOG_GROUPS] 를 초과하면 앞에서부터 상한만큼만 반환하고 초과분
     * 개수를 함께 반환한다. 초과하지 않으면 초과분=0.
     */
    private fun capChangelogGroups(groups: List<ImportChangeGroup>): Pair<List<ImportChangeGroup>, Int> {
        if (groups.size <= MAX_CHANGELOG_GROUPS) return groups to 0
        return groups.take(MAX_CHANGELOG_GROUPS) to (groups.size - MAX_CHANGELOG_GROUPS)
    }

    /** [applyChangelog] 루프 결과를 유형별로 집계해 경고로 강등한다([warnWorklogOutcomes] 와 동형). */
    private fun warnChangelogOutcomes(
        skippedTimeCount: Int,
        unmappedFieldCount: Int,
        cappedCount: Int,
        warnings: MutableList<String>,
    ) {
        if (skippedTimeCount > 0) {
            warnings += "변경 이력 ${skippedTimeCount}건은 발생 시각을 확인할 수 없어 건너뛰었습니다."
        }
        if (unmappedFieldCount > 0) {
            warnings += "변경 이력 항목 ${unmappedFieldCount}건은 매핑되지 않는 필드라 건너뛰었습니다."
        }
        if (cappedCount > 0) {
            warnings += "변경 이력 ${cappedCount}건은 이슈당 상한(${MAX_CHANGELOG_GROUPS}건)을 초과해 건너뛰었습니다."
        }
    }

    /**
     * 이력 그룹 1건 처리 결과([applyChangelogGroup]) — [applyChangelog] 가 [warnChangelogOutcomes] 로
     * 집계하기 위해 누적하는 카운트 쌍.
     *
     * @property skippedTime occurredAt 이 null 이라 그룹 전체를 스킵했으면 1, 아니면 0.
     * @property unmappedFields 이 그룹 내에서 [CHANGELOG_FIELD_MAP] 에 없어 스킵된 item 개수.
     */
    private data class ChangelogGroupOutcome(val skippedTime: Int, val unmappedFields: Int)

    /**
     * [key] 스코프로 EDIT_ISSUE(UPDATE) 권한을 실제 확인한다 — 댓글/worklog/첨부/이력 실행
     * 사전체크 공용(★2/★3).
     *
     * [hasUpdatePermission] 의 dry-run 전용 Project 스코프 예측과 달리, 이 시점엔 이슈가 실제로
     * 존재하므로([applyComments]/[applyWorklogs]/[applyAttachments]/[applyChangelog] 는 createIssue
     * 직후에만 호출된다) [CommentApplicationService.create]/[WorklogService.createImported]/
     * [IssueAttachmentService.upload] 내부 checkPermission 과 동일한 [IssueScope.Issue] 로 확인해야
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
     *   (reporter/assignee 뿐 아니라 댓글/worklog/첨부/이력 author 이메일도 포함, PR3/PR4). [applyComments]/
     *   [applyWorklogs]/[applyAttachmentItem]/[applyChangelogGroup]/[warnCommentsPreview]/[warnWorklogsPreview]
     *   가 [resolveAuthorId]/[isAuthorUnmatched] 로 재사용해 author 이메일마다 개별 조회를 반복하지 않는다.
     *   단, [applyChangelogGroup] 은 미매칭 시 [resolveAuthorId] 의 requester 폴백을 쓰지 않고 이
     *   맵을 직접 조회해 actorId=null 로 남긴다(Maxi 결정 — 비대칭 폴백, [applyChangelogGroup] KDoc).
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
     * - reporterEmail/assigneeEmail/댓글·worklog·첨부·이력 authorEmail: [userLookupPort.resolveByEmails]
     *   1회 배치 호출로 해석([FieldResolution.resolvedEmails] 로 전체 맵을 보존해 author 해석에 재사용,
     *   PR3/PR4).
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
                    cmd.worklogs.mapNotNull { it.authorEmail } +
                    cmd.attachments.mapNotNull { it.authorEmail } +
                    cmd.changelog.mapNotNull { it.authorEmail }
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

        /**
         * issue_attachments.filename VARCHAR(500) 상한(V023) — 초과 시 insert 가 22001(value too long)
         * 로 throw 한다(BLOCKER-2). [applyAttachmentItem] 이 upload 호출 이전에 사전체크해 애초에
         * throw 를 유발하지 않는다.
         */
        private const val MAX_ATTACHMENT_FILENAME_LENGTH = 500

        /** issue_attachments.content_type VARCHAR(100) 상한(V023) — [MAX_ATTACHMENT_FILENAME_LENGTH] 와 동일 근거. */
        private const val MAX_ATTACHMENT_CONTENT_TYPE_LENGTH = 100

        /** [ImportAttachment.mimeType] 미지정 + 확장자 유추도 실패한 첨부의 기본 MIME. */
        private const val DEFAULT_ATTACHMENT_CONTENT_TYPE = "application/octet-stream"

        /** 이슈당 import 변경 이력 그룹 상한(Maxi 결정, plan Task 9) — 초과분은 스킵 + 경고. */
        private const val MAX_CHANGELOG_GROUPS = 1000

        /**
         * Jira changelog raw field → BTS [IssueChangeItem.field] 매핑 테이블(Maxi 결정, plan Task 9
         * "구현 요지"). [IssueChangeDetector.SCALAR_FIELD_EXTRACTORS] 가 쓰는 BTS 필드명과 정합시켰다
         * (status/priority/assignee/summary/description/resolution 은 이름이 같고, issuetype→type/
         * Fix Version→fixVersions/Version→affectsVersions/duedate→dueDate/Epic Link→epic 은 개명).
         * 여기 없는 field(예: Jira custom field)는 [mapChangelogItems] 가 스킵 + 경고로 강등한다.
         */
        private val CHANGELOG_FIELD_MAP: Map<String, String> =
            mapOf(
                "status" to "status",
                "priority" to "priority",
                "assignee" to "assignee",
                "summary" to "summary",
                "description" to "description",
                "resolution" to "resolution",
                "issuetype" to "type",
                "labels" to "labels",
                "Component" to "components",
                "Fix Version" to "fixVersions",
                "Version" to "affectsVersions",
                "duedate" to "dueDate",
                "Epic Link" to "epic",
            )
    }
}
