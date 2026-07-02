// 비동기 CSV/JSON Import 작업 접수 서비스 — 권한 fail-fast + 크기/형식 검증 + MinIO put(tx 밖) + persist/enqueue(단일 tx)

package com.bts.search.imports.job.application

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.io.InputStream
import java.time.Clock
import java.util.UUID

/**
 * 비동기 CSV/JSON Import 작업 접수 서비스 (FR-IM-01 PR1 Task 11).
 *
 * `export` BC [com.bts.search.export.job.application.ExportJobService] 를 구조적으로 미러하되,
 * Export 와 달리 업로드 파일(MinIO put) I/O 가 있어 트랜잭션 경계를 분리했다(아래 §트랜잭션 경계 참조).
 *
 * ## 접수 흐름 — [accept]
 *
 * 1. projectKey 공백 검증.
 * 2. **권한 fail-fast(CONCERN #4 / FR12) — CREATE 기반 coarse 게이트** — [permissionResolver] 로
 *    요청자가 대상 프로젝트에 CREATE_ISSUE 권한이 있는지만 확인한다. 없으면 파일 저장·job 생성
 *    **이전**에 즉시 [ImportAccessDeniedException] 을 던진다. cross-BC 권한 판정은 shared-kernel
 *    [IssuePermissionResolver] 포트(권한코드 + resolver 경유)만 사용한다 — role 직접 조회 금지
 *    (교훈 crossbc-permission-resolver-not-role-lookup).
 *
 *    이 접수 게이트는 "이 사용자가 대상 프로젝트에서 이슈를 하나도 못 만들면 파일 업로드
 *    이전에 빠르게 거부한다"는 최소 게이트일 뿐, **행별 UPDATE(EDIT_ISSUE) 권한은 여기서
 *    검증하지 않는다** — priority/labels/assignee 등 update 를 유발하는 필드가 있는 행의
 *    UPDATE 권한 판정은 dryRun 미리보기([com.bts.issue.adapter.outbound.imports.IssueImportAdapter]
 *    §UPDATE 미러, 코드리뷰 CONCERN C1)와 실제 처리(행별 executeImport)에서 각각 이뤄진다.
 *    접수 단계에 UPDATE 까지 강제하면, update 를 유발하는 필드가 전혀 없는 파일(CREATE 만 필요)을
 *    올리려는 CREATE-only 사용자를 부당하게 거부하게 되므로 의도적으로 강제하지 않는다.
 * 3. 파일 크기 검증 — [MAX_FILE_SIZE_BYTES] 초과 시 [ImportFileTooLargeException].
 * 4. format 검증 — CSV/JSON 이외는 [ImportUnsupportedFormatException].
 * 5. MinIO put(원본 업로드, 트랜잭션 밖) → [ImportJobRepository.insert] + [ImportJobEnqueuePublisher.enqueue]
 *    (단일 트랜잭션) 순으로 실행한다.
 *
 * ## 트랜잭션 경계 — MinIO put 은 트랜잭션 밖, persist+enqueue 만 원자적
 *
 * [ImportJobEnqueuePublisher.enqueue] 는 [org.springframework.transaction.annotation.Propagation.MANDATORY]
 * 이므로 [ImportJobRepository.insert] 와 반드시 같은 트랜잭션 안에서 호출돼야 한다(outbox 패턴,
 * DATA.md §7.2). 그러나 이 메서드 전체를 `@Transactional` 로 감싸면 업로드 파일(최대 [MAX_FILE_SIZE_BYTES])을
 * MinIO 에 올리는 I/O 시간 동안 DB 커넥션을 점유하게 된다(FR-AC-01 "100MB I/O @Transactional 밖" 패턴,
 * [com.bts.issue.attachment.application.IssueAttachmentService] CONCERN-A 동형).
 *
 * 이 딜레마를 해결하기 위해 [transactionTemplate] 으로 persist+enqueue 구간만 감싼다.
 * 같은 클래스 안에서 `@Transactional` private 메서드를 호출하면 Spring AOP 프록시를 우회해
 * 트랜잭션이 무력화되는 self-invocation 함정(교훈 transaction-self-invocation-requires-new)이
 * 있으므로, 별도 Bean 을 추가하는 대신 [TransactionTemplate] 을 직접 주입해 프로그래밍 방식으로
 * 트랜잭션 경계를 연다 — [com.bts.issue.adapter.outbound.board.IssueTransitionAdapter] 의 동일 패턴을
 * 그대로 재사용한다.
 *
 * ## getForRequester / getErrorLog — @Transactional 미부여 이유
 *
 * [getForRequester] 는 `@Transactional(readOnly = true)` 로 감쌌다(단일 repo 조회, I/O 없음).
 * [getErrorLog] 는 repo 조회 + MinIO get(I/O) 조합이라 [IssueAttachmentService.download] 와
 * 동일하게 `@Transactional` 을 의도적으로 생략한다 — repo 메서드 자체가 이미 자기충족적
 * 트랜잭션을 갖고 있고, MinIO 스트림 open 동안 DB 커넥션을 점유하지 않기 위함이다.
 *
 * @param repository Import 작업 영속 저장소.
 * @param storage Import 원본/에러로그 오브젝트 스토리지 포트.
 * @param enqueuePublisher pgmq 큐에 작업 ID 를 발행하는 아웃바운드 어댑터.
 * @param permissionResolver cross-BC 이슈 권한 판정 포트. CREATE_ISSUE fail-fast 판정에 사용.
 * @param transactionTemplate persist+enqueue 구간의 프로그래밍 방식 트랜잭션 경계.
 * @param clock createdAt 결정용 시계. search 모듈에 Clock 빈이 없으므로 기본값 [Clock.systemUTC] 사용.
 */
@Service
class ImportJobService(
    private val repository: ImportJobRepository,
    private val storage: ImportObjectStoragePort,
    private val enqueuePublisher: ImportJobEnqueuePublisher,
    private val permissionResolver: IssuePermissionResolver,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Import 작업을 접수한다.
     *
     * 클래스 KDoc §접수 흐름 참조. 정상 경로에서는 원본 파일을 MinIO 에 업로드한 뒤
     * PENDING 상태의 [ImportJob] 을 영속하고 pgmq 큐에 enqueue 한다.
     *
     * @param command 접수 요청 커맨드. [ImportAcceptCommand] 참조.
     * @return 접수되어 영속된 [ImportJob] (status=PENDING).
     * @throws ResponseStatusException(400) projectKey 가 공백인 경우.
     * @throws ImportAccessDeniedException 요청자가 대상 프로젝트에 CREATE_ISSUE 권한이 없는 경우.
     * @throws ImportFileTooLargeException 파일 크기가 [MAX_FILE_SIZE_BYTES] 를 초과하는 경우.
     * @throws ImportUnsupportedFormatException format 이 CSV/JSON 이 아닌 경우.
     */
    fun accept(command: ImportAcceptCommand): ImportJob {
        val normalizedFormat = validateAndAuthorize(command)

        val jobId = ImportJobId(UUID.randomUUID())
        val objectKey = buildSourceObjectKey(command.projectKey, jobId, normalizedFormat)
        storage.put(
            objectKey,
            command.inputStream,
            command.sizeBytes,
            resolveContentType(normalizedFormat, command.contentType),
        )

        val job =
            ImportJob(
                id = jobId,
                projectKey = command.projectKey,
                format = normalizedFormat,
                sourceObjectKey = objectKey,
                dryRun = command.dryRun,
                requesterUserId = command.requesterUserId,
                status = ImportJobStatus.PENDING,
                progress = 0,
                totalRows = null,
                succeededRows = 0,
                failedRows = 0,
                errorCode = null,
                errorLogObjectKey = null,
                expiresAt = null,
                createdAt = clock.instant(),
                startedAt = null,
                completedAt = null,
            )

        transactionTemplate.execute {
            repository.insert(job)
            enqueuePublisher.enqueue(jobId)
        }

        log.info(
            "import_job_accepted jobId={} projectKey={} format={} dryRun={} filename={} actor={}",
            jobId.value,
            command.projectKey,
            normalizedFormat,
            command.dryRun,
            command.filename,
            command.requesterUserId,
        )
        return job
    }

    /**
     * 요청자 소유의 Import 작업을 조회한다.
     *
     * 타인 소유 작업은 null 을 반환해 존재 자체를 은닉한다 — 컨트롤러가 404 로 변환한다.
     *
     * @param jobId 조회할 작업 식별자.
     * @param requesterUserId 요청자 UUID.
     * @return 소유한 [ImportJob]. 없거나 소유권 없으면 null.
     */
    @Transactional(readOnly = true)
    fun getForRequester(
        jobId: ImportJobId,
        requesterUserId: UUID,
    ): ImportJob? = repository.findByIdForRequester(jobId, requesterUserId)

    /**
     * 요청자 소유의 Import 작업 실패행 에러 로그 스트림을 반환한다.
     *
     * 소유권 없음/미존재/에러 로그 미준비(완료 전 또는 실패행 없음) 는 모두 404 로 처리해
     * 존재 자체를 은닉한다.
     *
     * @param jobId 조회할 작업 식별자.
     * @param requesterUserId 요청자 UUID.
     * @return 작업 메타데이터 + 에러 로그 바이트 스트림 [ImportErrorLogResult].
     * @throws ResponseStatusException(404) 미존재, 타인 소유, 또는 에러 로그 미준비 시.
     */
    fun getErrorLog(
        jobId: ImportJobId,
        requesterUserId: UUID,
    ): ImportErrorLogResult {
        val job =
            repository.findByIdForRequester(jobId, requesterUserId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Import 작업을 찾을 수 없습니다.")
        val objectKey =
            job.errorLogObjectKey.takeIf { job.errorLogReady }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "에러 로그가 아직 준비되지 않았습니다.")
        return ImportErrorLogResult(job = job, stream = storage.get(objectKey))
    }

    // ── private helpers ──────────────────────────────────────────────────────────

    /**
     * projectKey 공백·CREATE_ISSUE 권한·파일 크기·format 을 순서대로 검증한다.
     *
     * @return 대문자로 정규화된 format 문자열 (`"CSV"` 또는 `"JSON"`).
     * @throws ResponseStatusException(400) projectKey 가 공백인 경우.
     * @throws ImportAccessDeniedException CREATE_ISSUE 권한이 없는 경우.
     * @throws ImportFileTooLargeException 파일 크기 초과 시.
     * @throws ImportUnsupportedFormatException 미지원 format 시.
     */
    @Suppress("ThrowsCount")
    private fun validateAndAuthorize(command: ImportAcceptCommand): String {
        if (command.projectKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectKey는 필수입니다.")
        }

        val allowed =
            permissionResolver.hasPermission(
                command.requesterUserId,
                IssuePermission.CREATE,
                IssueScope.Project(command.projectKey),
            )
        if (!allowed) {
            log.warn(
                "import_accept_forbidden projectKey={} actor={}",
                command.projectKey,
                command.requesterUserId,
            )
            throw ImportAccessDeniedException(
                "actor has no CREATE_ISSUE permission for project: ${command.projectKey}",
            )
        }

        if (command.sizeBytes > MAX_FILE_SIZE_BYTES) {
            log.warn(
                "import_accept_file_too_large projectKey={} sizeBytes={} actor={}",
                command.projectKey,
                command.sizeBytes,
                command.requesterUserId,
            )
            throw ImportFileTooLargeException(command.sizeBytes, MAX_FILE_SIZE_BYTES)
        }

        val normalizedFormat = command.format.trim().uppercase()
        if (normalizedFormat !in SUPPORTED_FORMATS) {
            log.warn(
                "import_accept_unsupported_format projectKey={} format={} actor={}",
                command.projectKey,
                command.format,
                command.requesterUserId,
            )
            throw ImportUnsupportedFormatException(command.format)
        }
        return normalizedFormat
    }

    /** `{projectKey}/{jobId}.{ext}` 형식의 원본 오브젝트 키를 생성한다([ImportObjectStoragePort] KDoc §오브젝트 키 패턴). */
    private fun buildSourceObjectKey(
        projectKey: String,
        jobId: ImportJobId,
        normalizedFormat: String,
    ): String = "$projectKey/${jobId.value}.${normalizedFormat.lowercase()}"

    /** 업로드된 파일의 contentType 이 없거나 공백이면 format 기반 기본값으로 대체한다. */
    private fun resolveContentType(
        normalizedFormat: String,
        contentType: String?,
    ): String {
        if (!contentType.isNullOrBlank()) return contentType
        return if (normalizedFormat == FORMAT_JSON) DEFAULT_JSON_CONTENT_TYPE else DEFAULT_CSV_CONTENT_TYPE
    }

    companion object {
        /** 업로드 허용 최대 파일 크기(바이트) — 50MB. */
        @Suppress("MagicNumber")
        const val MAX_FILE_SIZE_BYTES: Long = 50L * 1024 * 1024

        private val SUPPORTED_FORMATS = setOf("CSV", "JSON")
        private const val FORMAT_JSON = "JSON"
        private const val DEFAULT_CSV_CONTENT_TYPE = "text/csv; charset=UTF-8"
        private const val DEFAULT_JSON_CONTENT_TYPE = "application/json"
    }
}

/**
 * Import 작업 접수 커맨드.
 *
 * `IssueImportCommand`([com.bts.shared.issue.IssueImportCommand]) 선례와 동일하게, 위치 인자
 * 다중 파라미터 대신 커맨드 객체를 채택해 [ImportJobService.accept] 시그니처를 안정적으로 유지한다.
 *
 * @property projectKey Import 대상 프로젝트 키.
 * @property format 업로드 파일 형식 문자열(대소문자 무관, `"CSV"` 또는 `"JSON"`으로 정규화된다).
 * @property dryRun 검증 전용 실행 여부. true 면 실제 이슈를 생성하지 않는다.
 * @property filename 원본 파일명(로깅용 — 오브젝트 키에는 사용하지 않는다).
 * @property contentType 업로드 파일의 MIME 타입. null/공백이면 format 기반 기본값을 사용한다.
 * @property sizeBytes 업로드 파일 크기(바이트).
 * @property inputStream 업로드 바이트 스트림. 호출자가 close 책임.
 * @property requesterUserId 접수를 요청한 사용자 UUID.
 */
data class ImportAcceptCommand(
    val projectKey: String,
    val format: String,
    val dryRun: Boolean,
    val filename: String,
    val contentType: String?,
    val sizeBytes: Long,
    val inputStream: InputStream,
    val requesterUserId: UUID,
)

/**
 * [ImportJobService.getErrorLog] 반환 결과 — 작업 메타데이터 + 에러 로그 바이트 스트림.
 *
 * `issue-tracking` [com.bts.issue.attachment.application.AttachmentDownloadResult] 동형 패턴.
 * [stream] 은 호출자(컨트롤러)가 close 해야 한다.
 *
 * @property job 조회된 Import 작업 도메인 객체.
 * @property stream 에러 로그 CSV 바이트 스트림.
 */
data class ImportErrorLogResult(
    val job: ImportJob,
    val stream: InputStream,
)

/** Import 접수 시 CREATE_ISSUE 권한이 없을 때 던지는 예외 — [ImportExceptionHandler] 가 403 으로 변환한다. */
class ImportAccessDeniedException(message: String) : RuntimeException(message)

/**
 * 업로드 파일 크기가 [ImportJobService.MAX_FILE_SIZE_BYTES] 를 초과했을 때 던지는 예외.
 *
 * [ImportExceptionHandler] 가 413 으로 변환한다.
 *
 * @property sizeBytes 실제 업로드 파일 크기(바이트).
 * @property maxBytes 허용 상한(바이트).
 */
class ImportFileTooLargeException(val sizeBytes: Long, val maxBytes: Long) :
    RuntimeException("파일 크기(${sizeBytes}B)가 상한(${maxBytes}B)을 초과했습니다.")

/**
 * 업로드 파일 format 이 CSV/JSON 이 아닐 때 던지는 예외.
 *
 * [ImportExceptionHandler] 가 400 IMPORT_UNSUPPORTED_FORMAT 으로 변환한다.
 *
 * @property format 요청된(미지원) format 원본 문자열.
 */
class ImportUnsupportedFormatException(val format: String) :
    RuntimeException("지원하지 않는 파일 형식입니다: $format (CSV 또는 JSON만 허용)")
