// ImportMappingService 단위 테스트 — 소유확인/AWAITING_MAPPING 상태검사/CAS 우선 트랜잭션(TOCTOU 방지) (FR-IM-02 Task 7)
package com.bts.search.imports.mapping

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.mapping.repository.ImportMappingRepository
import com.bts.search.imports.mapping.repository.ImportUserMappingRepository
import com.bts.search.imports.parse.ImportRowParser
import com.bts.shared.user.UserLookupPort
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

/**
 * [ImportMappingService] 단위 테스트.
 *
 * [ImportJobRepository]/[ImportMappingRepository]/[ImportJobEnqueuePublisher]/[ImportObjectStoragePort] 는
 * MockK 로 격리한다. [ImportRowParser] 는 DB/네트워크 I/O 없는 순수 파서라 실 인스턴스를 사용해
 * 예측 가능한 CSV 바이트로부터 실제 헤더 파싱 결과를 얻는다. [TransactionTemplate] 은 MockK 로
 * 대체하되 `execute` 호출 시 전달된 콜백을 즉시 동기 실행하도록 stub 한다([ImportJobServiceTest]
 * 와 동일 패턴).
 *
 * 검증 항목.
 * - validate: (a) 타인/미존재 → 404, (b) AWAITING_MAPPING 아니면 상태충돌, (c) CSV 정상 검증,
 *   (d) JSON 은 헤더 재읽기 없이 스킵(항상 valid), (e) 검증 실패(SUMMARY_NOT_MAPPED) 결과 반환.
 * - confirm: (f) 404/사전 상태충돌/검증실패(422) 시 side effect(CAS·saveAll·enqueue) 없음,
 *   (g) 정상 확정 — CAS 성공 후 saveAll+enqueue 각 1회, PENDING 반환,
 *   (h) **핵심(CONCERN-3) — 사전확인은 통과했지만 실제 CAS(transitionToPending)가 실패하는
 *   TOCTOU/반복confirm 상황에서는 409 로 전환되고 saveAll/enqueue 는 단 한 번도 실행되지 않는다**
 *   (중복 enqueue 0 을 verify(exactly=1)/verify(exactly=0) 으로 명시 단언),
 *   (i) **dryrun-fix — [ImportMappingService.confirm] 의 `dryRun` 파라미터가
 *   [ImportJobRepository.transitionToPending] 호출에 그대로 전달되어 영속됨을 mockk `verify` 로
 *   단언한다.** T7 구현 당시 `dryRun` 이 감사 로깅에만 쓰이고 영속되지 않아 워커가 confirm 의 dryRun
 *   선택을 무시하는 silent bug 가 있었다(더 이상 재발 금지).
 */
class ImportMappingServiceTest {
    private val importMappingRepository: ImportMappingRepository = mockk()
    private val importJobRepository: ImportJobRepository = mockk()
    private val enqueuePublisher: ImportJobEnqueuePublisher = mockk()
    private val storage: ImportObjectStoragePort = mockk()
    private val transactionTemplate: TransactionTemplate = mockk()
    private val userLookupPort: UserLookupPort = mockk()
    private val importUserMappingRepository: ImportUserMappingRepository = mockk()
    private val parser = ImportRowParser()

    private lateinit var service: ImportMappingService

    private val actor: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherActor: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val projectKey = "PROJ"

    @BeforeEach
    fun setUp() {
        service =
            ImportMappingService(
                importMappingRepository,
                importJobRepository,
                enqueuePublisher,
                storage,
                transactionTemplate,
                userLookupPort,
                importUserMappingRepository,
                parser,
            )
        every { transactionTemplate.execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(mockk<TransactionStatus>(relaxed = true))
        }
        // confirm 은 userMappings 를 생략하면 항상 빈 맵을 저장한다(하위호환) — 이 파일의 기존
        // confirm 테스트는 모두 userMappings 를 생략하므로 공통으로 stub 한다.
        justRun { importUserMappingRepository.saveAll(any(), any()) }
    }

    private fun makeJob(
        id: ImportJobId = ImportJobId(UUID.randomUUID()),
        format: String = "CSV",
        status: ImportJobStatus = ImportJobStatus.AWAITING_MAPPING,
        requesterUserId: UUID = actor,
    ): ImportJob =
        ImportJob(
            id = id,
            projectKey = projectKey,
            format = format,
            sourceObjectKey = "$projectKey/${id.value}.${format.lowercase()}",
            dryRun = false,
            requesterUserId = requesterUserId,
            status = status,
            progress = 0,
            totalRows = null,
            succeededRows = 0,
            failedRows = 0,
            errorCode = null,
            errorLogObjectKey = null,
            expiresAt = Instant.parse("2024-03-16T10:30:45Z"),
            createdAt = Instant.parse("2024-03-15T10:30:45Z"),
            startedAt = null,
            completedAt = null,
        )

    private fun csvStream(content: String = "Title,Desc\nHello,World\n"): ByteArrayInputStream {
        return ByteArrayInputStream(content.toByteArray())
    }

    // ── validate ────────────────────────────────────────────────────────────

    @Test
    fun `validate throws 404 when job not found or not owned`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { importJobRepository.findByIdForRequester(jobId, otherActor) } returns null

        val ex =
            assertThrows<ResponseStatusException> {
                service.validate(jobId, otherActor, mapOf("Title" to "summary"))
            }

        assertThat(ex.statusCode.value()).isEqualTo(404)
        verify(exactly = 0) { storage.get(any()) }
    }

    @Test
    fun `validate throws state conflict when job is not AWAITING_MAPPING`() {
        val job = makeJob(status = ImportJobStatus.PENDING)
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job

        assertThrows<ImportMappingStateConflictException> {
            service.validate(job.id, actor, mapOf("Title" to "summary"))
        }
        verify(exactly = 0) { storage.get(any()) }
    }

    @Test
    fun `validate returns valid result for well-formed CSV field mapping`() {
        val job = makeJob(format = "CSV")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } returns csvStream()

        val result = service.validate(job.id, actor, mapOf("Title" to "summary", "Desc" to "description"))

        assertThat(result.valid).isTrue()
        assertThat(result.errors).isEmpty()
        verify(exactly = 1) { storage.get(job.sourceObjectKey) }
    }

    @Test
    fun `validate returns SUMMARY_NOT_MAPPED error when summary target missing`() {
        val job = makeJob(format = "CSV")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } returns csvStream()

        val result = service.validate(job.id, actor, mapOf("Desc" to "description"))

        assertThat(result.valid).isFalse()
        assertThat(result.errors.map { it.code }).contains(MappingValidator.SUMMARY_NOT_MAPPED)
    }

    @Test
    fun `validate skips field mapping validation and returns valid for JSON format without reading storage`() {
        val job = makeJob(format = "JSON")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job

        val result = service.validate(job.id, actor, mapOf("anything" to "garbage"))

        assertThat(result.valid).isTrue()
        assertThat(result.errors).isEmpty()
        assertThat(result.warnings).isEmpty()
        verify(exactly = 0) { storage.get(any()) }
    }

    // ── confirm — 소유/상태/검증 실패 시 side effect 없음 ──────────────────────

    @Test
    fun `confirm throws 404 when job not found or not owned`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { importJobRepository.findByIdForRequester(jobId, otherActor) } returns null

        assertThrows<ResponseStatusException> {
            service.confirm(jobId, otherActor, mapOf("Title" to "summary"), dryRun = false)
        }
        verify(exactly = 0) { importJobRepository.transitionToPending(any(), any()) }
        verify(exactly = 0) { importMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { importUserMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    @Test
    fun `confirm throws state conflict when job is not AWAITING_MAPPING at pre-check`() {
        val job = makeJob(status = ImportJobStatus.PENDING)
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job

        assertThrows<ImportMappingStateConflictException> {
            service.confirm(job.id, actor, mapOf("Title" to "summary"), dryRun = false)
        }
        verify(exactly = 0) { importJobRepository.transitionToPending(any(), any()) }
        verify(exactly = 0) { importMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { importUserMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    @Test
    fun `confirm throws ImportMappingInvalidException and performs no side effects when validation fails`() {
        val job = makeJob(format = "CSV")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } returns csvStream()

        val ex =
            assertThrows<ImportMappingInvalidException> {
                service.confirm(job.id, actor, mapOf("Desc" to "description"), dryRun = false)
            }

        assertThat(ex.errors.map { it.code }).contains(MappingValidator.SUMMARY_NOT_MAPPED)
        verify(exactly = 0) { importJobRepository.transitionToPending(any(), any()) }
        verify(exactly = 0) { importMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { importUserMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    // ── confirm — 정상 확정 ─────────────────────────────────────────────────

    @Test
    fun `confirm transitions to PENDING and saves mapping and enqueues exactly once on success`() {
        val job = makeJob(format = "CSV")
        val mapping = mapOf("Title" to "summary", "Desc" to "description")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } returns csvStream()
        every { importJobRepository.transitionToPending(job.id, false) } returns true
        justRun { importMappingRepository.saveAll(job.id, mapping) }
        justRun { enqueuePublisher.enqueue(job.id) }

        val result = service.confirm(job.id, actor, mapping, dryRun = false)

        assertThat(result.status).isEqualTo(ImportJobStatus.PENDING)
        assertThat(result.expiresAt).isNull()
        verify(exactly = 1) { importJobRepository.transitionToPending(job.id, false) }
        verify(exactly = 1) { importMappingRepository.saveAll(job.id, mapping) }
        // userMappings 를 생략하면(하위호환) 빈 맵을 저장한다.
        verify(exactly = 1) { importUserMappingRepository.saveAll(job.id, emptyMap()) }
        verify(exactly = 1) { enqueuePublisher.enqueue(job.id) }
    }

    // ── confirm — dryrun-fix: dryRun 파라미터가 transitionToPending 에 영속 전달 ─

    @Test
    fun `confirm with dryRun=true propagates dryRun to transitionToPending and reflects it in the returned snapshot`() {
        // T7 구현 당시 confirm 의 dryRun 파라미터는 감사 로깅에만 쓰이고 영속되지 않아, 워커가 확정된
        // dryRun 선택을 무시하고 dry-run 매핑 Import 를 실제로 실행하는 silent bug 가 있었다.
        // transitionToPending(jobId, dryRun) 호출로 CAS 전이와 같은 UPDATE 에서 dry_run 을 확정해야 한다.
        val job = makeJob(format = "CSV")
        val mapping = mapOf("Title" to "summary", "Desc" to "description")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } returns csvStream()
        every { importJobRepository.transitionToPending(job.id, true) } returns true
        justRun { importMappingRepository.saveAll(job.id, mapping) }
        justRun { enqueuePublisher.enqueue(job.id) }

        val result = service.confirm(job.id, actor, mapping, dryRun = true)

        assertThat(result.dryRun).isTrue()
        verify(exactly = 1) { importJobRepository.transitionToPending(job.id, true) }
    }

    // ── confirm — 핵심(CONCERN-3): CAS 실패 시 중복 enqueue 차단(TOCTOU) ───────

    @Test
    fun `confirm throws state conflict and skips saveAll and enqueue when CAS fails after pre-check passes`() {
        // pre-check(findByIdForRequester)는 AWAITING_MAPPING을 반환해 통과하지만, 트랜잭션 내부의
        // 실제 CAS(transitionToPending)는 false를 반환한다 — 반복/동시 confirm 요청이거나 pre-check와
        // CAS 사이에 상태가 바뀐 TOCTOU 상황을 mock으로 재현한다. CAS가 saveAll/enqueue보다 먼저
        // 실행되어야만 이 케이스에서 매핑 덮어씀·중복 enqueue가 발생하지 않는다.
        val job = makeJob(format = "CSV")
        val mapping = mapOf("Title" to "summary")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } returns csvStream()
        every { importJobRepository.transitionToPending(job.id, false) } returns false

        assertThrows<ImportMappingStateConflictException> {
            service.confirm(job.id, actor, mapping, dryRun = false)
        }

        verify(exactly = 1) { importJobRepository.transitionToPending(job.id, false) }
        verify(exactly = 0) { importMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { importUserMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }
}
