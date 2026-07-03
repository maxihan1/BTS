// 비동기 CSV/JSON Import 접수(accept)+분석(analyze) 서비스 — 검증 공유 + MinIO put(tx 밖) + persist(enqueue는 accept만)

package com.bts.search.imports.job.application

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.mapping.TargetField
import com.bts.search.imports.parse.ImportRowParser
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
 * ## 분석 흐름 — [analyze] (FR-IM-02 PR-A Task 6)
 *
 * `analyze → map → run` 2 단계 매핑 UI 흐름의 1 단계. [accept] 와 검증 로직은 완전히 동일하지만
 * ([validateCoreAndAuthorize] 로 공유 — 두 진입점 모두 CREATE_ISSUE 권한 fail-fast 를 반드시 거친다,
 * 보안 회귀 방지), 다음 두 가지가 다르다.
 *
 * 1. 영속되는 [ImportJob] 의 status 가 PENDING 이 아니라 [ImportJobStatus.AWAITING_MAPPING] 이고,
 *    [ImportJobEnqueuePublisher.enqueue] 를 호출하지 않는다 — 사용자가 소스 필드 ↔ 대상 필드 매핑을
 *    확정([ImportJobRepository.transitionToPending], 매핑 확정 API 는 이 Task 의 책임 범위 밖)해야
 *    비로소 PENDING 으로 전이하고 워커 처리가 시작된다.
 * 2. [ImportJob.expiresAt] 을 [ABANDON_TTL_SECONDS] 후로 설정해, 매핑을 확정하지 않고 방치된 job 을
 *    cleanup 워커([com.bts.search.imports.job.worker.ImportJobCleanupWorker])가 정리할 수 있게 한다.
 *    매핑을 확정하면 이 만료 시각은 NULL 로 해제된다([ImportJobRepository.transitionToPending] KDoc).
 *
 * 원본을 MinIO 에 저장한 뒤 [detectSourceFieldsAndSample] 로 매핑 UI 가 보여줄 소스 필드 목록과
 * 미리보기 샘플 행을 감지한다.
 * - **CSV** — 방금 저장한 오브젝트를 [storage] 에서 다시 열어(`storage.get`) [parser] 로 헤더 +
 *   최대 5 개 샘플 행만 읽는다([ImportRowParser.readHeaderAndSample], 조기 중단). [storage.put] 이
 *   이미 소비한 원본 [ImportAnalyzeCommand.inputStream] 을 재사용하지 않고 저장된 오브젝트를
 *   재조회하는 이유 — 임의 [java.io.InputStream] 이 mark/reset 을 지원한다는 보장이 없어, 이미
 *   신뢰할 수 있는 조회 경로([getErrorLog] 가 쓰는 [storage].get)를 재사용하는 편이 더 견고하다.
 * - **JSON** — Jira REST export 는 항상 고정된 canonical 필드 구조([com.bts.search.imports.parse.ImportRowParser.parseJson])
 *   라 사용자가 자유롭게 매핑할 소스 헤더가 없다. [TargetField.entries] 의 key 목록을 그대로
 *   sourceFields 로 반환해 프론트엔드가 매핑 UI 단계를 스킵할 수 있게 한다 — sampleRows 는 항상 빈 목록.
 *
 * 반환하는 [ImportAnalysisResult] 는 대상 필드 카탈로그([TargetField.entries])를 포함하지 않는다 —
 * 요청과 무관한 정적 값이라 컨트롤러(PR-A Task 8)가 별도로 직렬화한다.
 *
 * ## 첨부 zip — [putAttachmentsZipIfPresent] (PR4 Task 7)
 *
 * `POST /api/v1/imports` 는 매니페스트(`file`)와 별개로 첨부 zip(`attachmentsZip`, optional)을
 * 두 번째 multipart part 로 받을 수 있다. **JSON import 이고 zip 이 첨부된 경우에만**
 * `{projectKey}/{jobId}-attachments.zip` 키로 MinIO 에 저장하고 [ImportJob.attachmentsObjectKey] 에 기록한다.
 * **CSV import 는 zip 을 무시한다**(attachmentsObjectKey=null) — CSV 포맷에는 첨부 매핑 개념이 없으므로
 * 클라이언트가 실수로 zip 을 함께 올려도 조용히 무시해 하위호환을 유지한다. zip 은 원본 파일과 마찬가지로
 * URL 을 따라가지 않고 클라이언트가 올린 바이트를 그대로 MinIO 에 적재할 뿐이라 SSRF 표면을 추가하지 않는다.
 * 실제 zip 내부 압축 해제·첨부 매핑은 워커([com.bts.search.imports.job.worker.ImportJobWorker], PR4 후속 Task)
 * 책임이다 — 이 서비스는 원본 zip 을 안전하게 보관하는 것까지만 담당한다.
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
 * @param storage Import 원본/에러로그/첨부 zip 오브젝트 스토리지 포트.
 * @param enqueuePublisher pgmq 큐에 작업 ID 를 발행하는 아웃바운드 어댑터.
 * @param permissionResolver cross-BC 이슈 권한 판정 포트. CREATE_ISSUE fail-fast 판정에 사용.
 * @param transactionTemplate persist+enqueue 구간의 프로그래밍 방식 트랜잭션 경계.
 * @param clock createdAt 결정용 시계. search 모듈에 Clock 빈이 없으므로 기본값 [Clock.systemUTC] 사용.
 * @param attachmentsZipMaxSizeBytes 첨부 zip 업로드 허용 최대 크기(바이트). `bts.import.attachments-zip.max-size`
 *   프로퍼티로 오버라이드 가능. 기본값 [DEFAULT_ATTACHMENTS_ZIP_MAX_SIZE_BYTES](500MB) — SpEL 기본값 문자열과
 *   Kotlin 기본 인자가 이중 정의되므로 값 변경 시 둘 다 동기화해야 한다(Spring 이 직접 생성자를 호출하는
 *   실 빈 경로에서는 SpEL 기본값이, 테스트가 인자를 생략하고 수동 호출하는 경로에서는 Kotlin 기본 인자가 적용된다).
 * @param parser [analyze] CSV 헤더/샘플 감지용 스트리밍 파서. [ImportRowParser] 는 Spring 빈으로 등록되어
 *   있지 않으므로([ImportJobProcessor] 의 동일 Clock/parser 기본값 패턴과 동일하게) 기본값으로 직접
 *   인스턴스화한다.
 */
@Service
@Suppress("LongParameterList", "TooManyFunctions")
class ImportJobService(
    private val repository: ImportJobRepository,
    private val storage: ImportObjectStoragePort,
    private val enqueuePublisher: ImportJobEnqueuePublisher,
    private val permissionResolver: IssuePermissionResolver,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
    @Value("\${bts.import.attachments-zip.max-size:524288000}")
    private val attachmentsZipMaxSizeBytes: Long = DEFAULT_ATTACHMENTS_ZIP_MAX_SIZE_BYTES,
    private val parser: ImportRowParser = ImportRowParser(),
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
        val attachmentsObjectKey = putAttachmentsZipIfPresent(command, jobId, normalizedFormat)

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
                attachmentsObjectKey = attachmentsObjectKey,
            )

        transactionTemplate.execute {
            repository.insert(job)
            enqueuePublisher.enqueue(jobId)
        }

        log.info(
            "import_job_accepted jobId={} projectKey={} format={} dryRun={} filename={} " +
                "attachmentsObjectKey={} actor={}",
            jobId.value,
            command.projectKey,
            normalizedFormat,
            command.dryRun,
            command.filename,
            attachmentsObjectKey,
            command.requesterUserId,
        )
        return job
    }

    /**
     * Import 파일을 분석해 매핑 UI 진입에 필요한 소스 필드 목록과 미리보기 샘플을 반환한다.
     *
     * 클래스 KDoc §분석 흐름 참조. [accept] 와 동일한 공통 검증([validateCoreAndAuthorize])을 거친 뒤
     * 원본 파일을 MinIO 에 업로드하고 [ImportJobStatus.AWAITING_MAPPING] 상태의 [ImportJob] 을
     * 영속한다 — [accept] 와 달리 pgmq enqueue 는 하지 않는다(매핑 확정 전에는 워커가 처리하지 않는다).
     *
     * @param command 분석 요청 커맨드. [ImportAnalyzeCommand] 참조.
     * @return 분석 결과 [ImportAnalysisResult] — status=AWAITING_MAPPING 인 job + sourceFields + sampleRows.
     * @throws ResponseStatusException(400) projectKey 가 공백인 경우.
     * @throws ImportAccessDeniedException 요청자가 대상 프로젝트에 CREATE_ISSUE 권한이 없는 경우.
     * @throws ImportFileTooLargeException 파일 크기가 [MAX_FILE_SIZE_BYTES] 를 초과하는 경우.
     * @throws ImportUnsupportedFormatException format 이 CSV/JSON 이 아닌 경우.
     */
    fun analyze(command: ImportAnalyzeCommand): ImportAnalysisResult {
        val normalizedFormat =
            validateCoreAndAuthorize(
                projectKey = command.projectKey,
                requesterUserId = command.requesterUserId,
                sizeBytes = command.sizeBytes,
                format = command.format,
            )

        val jobId = ImportJobId(UUID.randomUUID())
        val objectKey = buildSourceObjectKey(command.projectKey, jobId, normalizedFormat)
        storage.put(
            objectKey,
            command.inputStream,
            command.sizeBytes,
            resolveContentType(normalizedFormat, command.contentType),
        )
        val (sourceFields, sampleRows) = detectSourceFieldsAndSample(objectKey, normalizedFormat)

        val job =
            ImportJob(
                id = jobId,
                projectKey = command.projectKey,
                format = normalizedFormat,
                sourceObjectKey = objectKey,
                dryRun = false,
                requesterUserId = command.requesterUserId,
                status = ImportJobStatus.AWAITING_MAPPING,
                progress = 0,
                totalRows = null,
                succeededRows = 0,
                failedRows = 0,
                errorCode = null,
                errorLogObjectKey = null,
                expiresAt = clock.instant().plusSeconds(ABANDON_TTL_SECONDS),
                createdAt = clock.instant(),
                startedAt = null,
                completedAt = null,
                attachmentsObjectKey = null,
            )

        transactionTemplate.execute {
            repository.insert(job)
        }

        log.info(
            "import_job_analyzed jobId={} projectKey={} format={} filename={} sourceFieldCount={} actor={}",
            jobId.value,
            command.projectKey,
            normalizedFormat,
            command.filename,
            sourceFields.size,
            command.requesterUserId,
        )
        return ImportAnalysisResult(job = job, sourceFields = sourceFields, sampleRows = sampleRows)
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
     * [accept] 전용 검증 — 공통 검증([validateCoreAndAuthorize])에 첨부 zip 크기 검증을 더한다.
     *
     * @return 대문자로 정규화된 format 문자열 (`"CSV"` 또는 `"JSON"`).
     * @throws ResponseStatusException(400) projectKey 가 공백인 경우.
     * @throws ImportAccessDeniedException CREATE_ISSUE 권한이 없는 경우.
     * @throws ImportFileTooLargeException 파일(또는 첨부 zip) 크기 초과 시.
     * @throws ImportUnsupportedFormatException 미지원 format 시.
     */
    private fun validateAndAuthorize(command: ImportAcceptCommand): String {
        val normalizedFormat =
            validateCoreAndAuthorize(
                projectKey = command.projectKey,
                requesterUserId = command.requesterUserId,
                sizeBytes = command.sizeBytes,
                format = command.format,
            )

        // CSV 는 zip 을 무시하므로(§첨부 zip) 크기 검증도 JSON 에서만 수행한다 — CSV+zip 조합은
        // zip 자체를 아예 건드리지 않아야 하위호환 시나리오(zip 무시)가 일관된다.
        val zipSizeBytes = command.attachmentsZipSizeBytes
        if (normalizedFormat == FORMAT_JSON && zipSizeBytes != null && zipSizeBytes > attachmentsZipMaxSizeBytes) {
            log.warn(
                "import_accept_attachments_zip_too_large projectKey={} sizeBytes={} actor={}",
                command.projectKey,
                zipSizeBytes,
                command.requesterUserId,
            )
            throw ImportFileTooLargeException(zipSizeBytes, attachmentsZipMaxSizeBytes)
        }
        return normalizedFormat
    }

    /**
     * projectKey 공백·CREATE_ISSUE 권한·파일 크기·format 을 순서대로 검증한다([accept]/[analyze] 공유).
     *
     * 두 진입점(파일 접수 [accept], 매핑 분석 [analyze])이 완전히 동일한 검증 순서·예외를 거치도록
     * 이 메서드 하나로 통합했다 — 검증 로직이 진입점별로 갈라지면 한쪽에서 권한 검증이 누락되는
     * 보안 회귀가 발생할 수 있다.
     *
     * @return 대문자로 정규화된 format 문자열 (`"CSV"` 또는 `"JSON"`).
     * @throws ResponseStatusException(400) projectKey 가 공백인 경우.
     * @throws ImportAccessDeniedException CREATE_ISSUE 권한이 없는 경우.
     * @throws ImportFileTooLargeException 파일 크기 초과 시.
     * @throws ImportUnsupportedFormatException 미지원 format 시.
     */
    @Suppress("ThrowsCount")
    private fun validateCoreAndAuthorize(
        projectKey: String,
        requesterUserId: UUID,
        sizeBytes: Long,
        format: String,
    ): String {
        if (projectKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectKey는 필수입니다.")
        }

        val allowed =
            permissionResolver.hasPermission(
                requesterUserId,
                IssuePermission.CREATE,
                IssueScope.Project(projectKey),
            )
        if (!allowed) {
            log.warn(
                "import_validate_forbidden projectKey={} actor={}",
                projectKey,
                requesterUserId,
            )
            throw ImportAccessDeniedException(
                "actor has no CREATE_ISSUE permission for project: $projectKey",
            )
        }

        if (sizeBytes > MAX_FILE_SIZE_BYTES) {
            log.warn(
                "import_validate_file_too_large projectKey={} sizeBytes={} actor={}",
                projectKey,
                sizeBytes,
                requesterUserId,
            )
            throw ImportFileTooLargeException(sizeBytes, MAX_FILE_SIZE_BYTES)
        }

        val normalizedFormat = format.trim().uppercase()
        if (normalizedFormat !in SUPPORTED_FORMATS) {
            log.warn(
                "import_validate_unsupported_format projectKey={} format={} actor={}",
                projectKey,
                format,
                requesterUserId,
            )
            throw ImportUnsupportedFormatException(format)
        }
        return normalizedFormat
    }

    /**
     * FR-IM-02 매핑 UI 진입에 필요한 소스 필드 목록과 미리보기 샘플 행을 감지한다(클래스 KDoc §분석 흐름).
     *
     * @param objectKey [analyze] 가 방금 MinIO 에 저장한 원본 오브젝트 키.
     * @param normalizedFormat [validateCoreAndAuthorize] 가 정규화한 format(`"CSV"`/`"JSON"`).
     * @return sourceFields(소스 필드 이름 목록) + sampleRows(미리보기 샘플 행, 각 행은 셀 목록) 쌍.
     */
    private fun detectSourceFieldsAndSample(
        objectKey: String,
        normalizedFormat: String,
    ): Pair<List<String>, List<List<String>>> {
        if (normalizedFormat == FORMAT_JSON) {
            return JSON_CANONICAL_SOURCE_FIELDS to emptyList()
        }
        val headerSample =
            storage.get(objectKey).use { stream -> parser.readHeaderAndSample(stream, ANALYZE_SAMPLE_SIZE) }
        return headerSample.headers to headerSample.sampleRows
    }

    /** `{projectKey}/{jobId}.{ext}` 형식의 원본 오브젝트 키를 생성한다([ImportObjectStoragePort] KDoc §오브젝트 키 패턴). */
    private fun buildSourceObjectKey(
        projectKey: String,
        jobId: ImportJobId,
        normalizedFormat: String,
    ): String = "$projectKey/${jobId.value}.${normalizedFormat.lowercase()}"

    /**
     * JSON import 이고 첨부 zip 이 있는 경우에만 zip 을 MinIO 에 저장하고 오브젝트 키를 반환한다.
     *
     * CSV import 이거나 zip 미첨부면 저장 없이 null 을 반환한다 — [ImportJob.attachmentsObjectKey] 도
     * null 로 남아 하위호환(zip 미지원 클라이언트/CSV import)을 보장한다(클래스 KDoc §첨부 zip 참조).
     *
     * @param command 접수 커맨드. [ImportAcceptCommand.attachmentsZipInputStream]/
     *   [ImportAcceptCommand.attachmentsZipSizeBytes] 참조.
     * @param jobId 이번 접수에서 발급된 작업 식별자 — 오브젝트 키 생성에 사용.
     * @param normalizedFormat [validateAndAuthorize] 가 정규화한 format(`"CSV"`/`"JSON"`).
     * @return 저장한 zip 오브젝트 키. 저장하지 않았으면 null.
     */
    private fun putAttachmentsZipIfPresent(
        command: ImportAcceptCommand,
        jobId: ImportJobId,
        normalizedFormat: String,
    ): String? {
        val zipStream = command.attachmentsZipInputStream ?: return null

        if (normalizedFormat != FORMAT_JSON) {
            log.info(
                "import_attachments_zip_ignored reason=non_json_format projectKey={} jobId={} format={}",
                command.projectKey,
                jobId.value,
                normalizedFormat,
            )
        }

        return if (normalizedFormat == FORMAT_JSON) {
            val objectKey = buildAttachmentsZipObjectKey(command.projectKey, jobId)
            storage.put(objectKey, zipStream, command.attachmentsZipSizeBytes ?: 0L, ATTACHMENTS_ZIP_CONTENT_TYPE)
            objectKey
        } else {
            null
        }
    }

    /** `{projectKey}/{jobId}-attachments.zip` 형식의 첨부 zip 오브젝트 키를 생성한다. */
    private fun buildAttachmentsZipObjectKey(
        projectKey: String,
        jobId: ImportJobId,
    ): String = "$projectKey/${jobId.value}$ATTACHMENTS_ZIP_KEY_SUFFIX"

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

        /**
         * 첨부 zip 업로드 허용 최대 크기(바이트) 기본값 — 500MB.
         * `bts.import.attachments-zip.max-size` 프로퍼티로 오버라이드 가능(생성자 KDoc 참조).
         */
        @Suppress("MagicNumber")
        const val DEFAULT_ATTACHMENTS_ZIP_MAX_SIZE_BYTES: Long = 500L * 1024 * 1024

        private val SUPPORTED_FORMATS = setOf("CSV", "JSON")
        private const val FORMAT_JSON = "JSON"
        private const val DEFAULT_CSV_CONTENT_TYPE = "text/csv; charset=UTF-8"
        private const val DEFAULT_JSON_CONTENT_TYPE = "application/json"

        /** 첨부 zip MinIO 저장 시 고정 Content-Type — 클라이언트가 보낸 Content-Type 헤더를 신뢰하지 않는다. */
        private const val ATTACHMENTS_ZIP_CONTENT_TYPE = "application/zip"

        /** [buildAttachmentsZipObjectKey] 오브젝트 키 접미사. */
        private const val ATTACHMENTS_ZIP_KEY_SUFFIX = "-attachments.zip"

        /**
         * 매핑 미확정 상태([ImportJobStatus.AWAITING_MAPPING])로 방치된 job 을 정리하는 기준 TTL(초) — 24시간.
         *
         * [ImportJobProcessor.RESULT_TTL_SECONDS] 선례와 동일한 값을 사용한다. 매핑 UI 세션(사용자가
         * 파일을 올리고 필드를 매핑해 확정하는 데 걸리는 시간)보다 충분히 길게 잡아, 정상적으로 매핑을
         * 진행 중인 사용자의 job 이 조기 삭제되지 않도록 한다. 확정 시([ImportJobRepository.transitionToPending])
         * expiresAt 은 NULL 로 해제되어 이 TTL cleanup 대상에서 제외된다.
         */
        @Suppress("MagicNumber")
        const val ABANDON_TTL_SECONDS: Long = 24 * 60 * 60

        /**
         * [analyze] 단계에서 미리보기로 노출할 최대 CSV 샘플 행 수.
         * [com.bts.search.imports.parse.ImportRowParser.readHeaderAndSample] 기본값과 동일하게 5.
         */
        @Suppress("MagicNumber")
        private const val ANALYZE_SAMPLE_SIZE = 5

        /**
         * JSON import 의 canonical sourceFields([analyze] §JSON 분기).
         *
         * Jira REST export 는 항상 고정된 canonical 필드 구조를 가지므로 사용자가 자유롭게 매핑할
         * 소스 헤더가 없다. [TargetField.entries] 의 key 를 그대로 사용해 프론트엔드가 매핑 UI 단계를
         * 스킵할 수 있게 한다.
         */
        private val JSON_CANONICAL_SOURCE_FIELDS: List<String> = TargetField.entries.map { it.key }
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
 * @property attachmentsZipInputStream 첨부 zip 바이트 스트림(PR4 Task 7). `attachmentsZip` part 미첨부 시 null.
 *   호출자가 close 책임. null 이면 [ImportJobService] 가 zip 저장을 완전히 건너뛴다.
 * @property attachmentsZipSizeBytes 첨부 zip 크기(바이트). [attachmentsZipInputStream] 이 null 이 아닐 때만 의미 있다.
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
    val attachmentsZipInputStream: InputStream? = null,
    val attachmentsZipSizeBytes: Long? = null,
)

/**
 * Import 파일 분석(analyze) 커맨드(FR-IM-02 PR-A Task 6) — 매핑 UI 진입 전 헤더/샘플 감지 단계.
 *
 * [ImportAcceptCommand] 와 달리 dryRun/첨부 zip 필드가 없다. analyze 로 생성된 job 은 항상
 * dryRun=false 로 고정되고([ImportJobService.analyze] 참조), 첨부 zip 업로드는 매핑 확정 이후의
 * 접수([ImportJobService.accept]) 단계 몫이다.
 *
 * @property projectKey Import 대상 프로젝트 키.
 * @property format 업로드 파일 형식 문자열(대소문자 무관, `"CSV"` 또는 `"JSON"`으로 정규화된다).
 * @property filename 원본 파일명(로깅용 — 오브젝트 키에는 사용하지 않는다).
 * @property contentType 업로드 파일의 MIME 타입. null/공백이면 format 기반 기본값을 사용한다.
 * @property sizeBytes 업로드 파일 크기(바이트).
 * @property inputStream 업로드 바이트 스트림. 호출자가 close 책임.
 * @property requesterUserId 분석을 요청한 사용자 UUID.
 */
data class ImportAnalyzeCommand(
    val projectKey: String,
    val format: String,
    val filename: String,
    val contentType: String?,
    val sizeBytes: Long,
    val inputStream: InputStream,
    val requesterUserId: UUID,
)

/**
 * [ImportJobService.analyze] 반환 결과 — 접수된 [ImportJob](status=AWAITING_MAPPING) + 매핑 UI 가
 * 노출할 소스 필드 목록 + 미리보기 샘플 행.
 *
 * 대상 필드 카탈로그([TargetField.entries])는 요청과 무관한 정적 값이라 이 결과에 포함하지 않는다 —
 * 컨트롤러(PR-A Task 8)가 별도로 직렬화한다.
 *
 * @property job 분석 단계에서 생성된 [ImportJob](status=AWAITING_MAPPING).
 * @property sourceFields CSV 헤더 셀 목록(원본 순서) 또는 JSON canonical [TargetField.key] 고정 목록.
 * @property sampleRows CSV 미리보기 샘플 행(각 행은 셀 목록, 최대 5 건). JSON 은 항상 빈 목록.
 */
data class ImportAnalysisResult(
    val job: ImportJob,
    val sourceFields: List<String>,
    val sampleRows: List<List<String>>,
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
