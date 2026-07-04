// ImportMappingService 값매핑 확장 단위 테스트 — collectValues(상태/유형/우선순위 소스값 수집+자동추천) +
// confirm 값매핑 검증/저장(FR7 필드별 비대칭·C1 canonical 치환·E6 중복·CAS 실패 시 미저장) (FR-IM-02 PR-C Task 5)
package com.bts.search.imports.mapping

import com.bts.search.imports.job.domain.ImportJob
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.job.domain.ImportJobStatus
import com.bts.search.imports.job.event.ImportJobEnqueuePublisher
import com.bts.search.imports.job.repository.ImportJobRepository
import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.search.imports.mapping.repository.ImportMappingRepository
import com.bts.search.imports.mapping.repository.ImportUserMappingRepository
import com.bts.search.imports.mapping.repository.ImportValueMappingRepository
import com.bts.search.imports.parse.ImportRowParser
import com.bts.shared.issue.IssueTypeCatalog
import com.bts.shared.issue.IssueTypeRef
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import io.mockk.Called
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
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
 * [ImportMappingService] 의 [ImportMappingService.collectValues] + `confirm` 값매핑 확장 단위 테스트
 * (FR-IM-02 PR-C Task 5).
 *
 * [ImportMappingServiceUserTest] 와 동일하게 [ImportJobRepository]/[ImportMappingRepository]/
 * [ImportJobEnqueuePublisher]/[ImportObjectStoragePort]/[IssueTypeCatalog]/[WorkflowStateCatalog]/
 * [ImportValueMappingRepository] 를 MockK 로 격리하고, [ImportRowParser] 는 실 인스턴스를 사용해
 * 예측 가능한 CSV 바이트로부터 실제 파싱 결과를 얻는다.
 *
 * 검증 항목.
 * - collectValues: (a) 404, (b) CSV 필드매핑 무효 시 전량 스캔 전에 422(C1 — storage.get 1회만,
 *   워크플로우/이슈타입 카탈로그 조회 0회), (c) CSV 정상 매핑 시 상태/유형/우선순위별 distinct
 *   정규화 소스값 + 정규화 정확일치 자동추천(카탈로그에 없으면 null).
 * - confirm(valueMappings): (d) 정상 확정 — 사용자 매핑 저장 뒤·enqueue 앞에 값 매핑 저장, TYPE/PRIORITY
 *   는 canonical 정확형으로 치환(C1), STATUS 는 원본 그대로 저장(관대, FR7),
 *   (e) CAS 실패(TOCTOU) 시 값 매핑 저장 0회, (f) TYPE 대상 값이 카탈로그에 없으면 422(FR7 엄격),
 *   (g) PRIORITY 대상 값이 canonical 5 가 아니면 422(FR7 엄격), (h) 정규화 후 (대상 필드, 소스 값) 중복
 *   이면 대상 값이 달라도 422(E6, 사전 side-effect 없음).
 */
class ImportMappingServiceValueTest {
    private val importMappingRepository: ImportMappingRepository = mockk()
    private val importJobRepository: ImportJobRepository = mockk()
    private val enqueuePublisher: ImportJobEnqueuePublisher = mockk()
    private val storage: ImportObjectStoragePort = mockk()
    private val transactionTemplate: TransactionTemplate = mockk()
    private val userLookupPort: UserLookupPort = mockk()
    private val importUserMappingRepository: ImportUserMappingRepository = mockk()
    private val issueTypeCatalog: IssueTypeCatalog = mockk()
    private val workflowStateCatalog: WorkflowStateCatalog = mockk()
    private val importValueMappingRepository: ImportValueMappingRepository = mockk()
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
                issueTypeCatalog,
                workflowStateCatalog,
                importValueMappingRepository,
                parser,
            )
        every { transactionTemplate.execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(mockk<TransactionStatus>(relaxed = true))
        }
        // confirm 은 userMappings 를 생략하면 항상 빈 맵을 저장한다(하위호환, ImportMappingServiceTest 와
        // 동일 이유) — 이 파일의 confirm 테스트는 모두 userMappings 를 생략하므로 공통으로 stub 한다.
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

    // ── collectValues ───────────────────────────────────────────────────────

    @Test
    fun `collectValues throws 404 when job not found or not owned`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { importJobRepository.findByIdForRequester(jobId, otherActor) } returns null

        assertThrows<ResponseStatusException> {
            service.collectValues(jobId, otherActor, mapOf("Title" to TargetField.SUMMARY.key))
        }
        verify(exactly = 0) { storage.get(any()) }
    }

    @Test
    fun `collectValues throws ImportMappingInvalidException without full scan when CSV field mapping is invalid`() {
        val job = makeJob(format = "CSV")
        val csv = "Title,Status\nBug A,Open\n"
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } answers { ByteArrayInputStream(csv.toByteArray()) }

        val ex =
            assertThrows<ImportMappingInvalidException> {
                // summary 대상이 매핑에서 빠져 있다 — 무효 매핑.
                service.collectValues(job.id, actor, mapOf("Status" to TargetField.STATUS.key))
            }

        assertThat(ex.errors.map { it.code }).contains(MappingValidator.SUMMARY_NOT_MAPPED)
        // 헤더 재읽기(검증)만 1회 — 무효 판정 후 전량 스캔은 실행되지 않는다(C1).
        verify(exactly = 1) { storage.get(job.sourceObjectKey) }
        // wasNot Called 사용 — any() 매처는 ProjectKey/IssueTypeKey(검증 init 블록 있는 value class) 자리에서
        // mockk 가 더미 값으로 시그니처를 생성하다 init 이 터진다(교훈 fr-is-06-handoff-mockk-detekt-traps).
        verify { workflowStateCatalog wasNot Called }
        verify { issueTypeCatalog wasNot Called }
    }

    @Test
    fun `collectValues collects distinct normalized source values per field and returns exact-match suggestions`() {
        val job = makeJob(format = "CSV")
        val fieldMappings =
            mapOf(
                "Title" to TargetField.SUMMARY.key,
                "Status" to TargetField.STATUS.key,
                "Type" to TargetField.TYPE.key,
                "Priority" to TargetField.PRIORITY.key,
            )
        // Status "Open"/"open" 은 정규화 후 하나로 합쳐진다. Type 은 카탈로그에 없는 값("unknown-type")도
        // 포함해 자동추천 실패(null) 케이스를 함께 검증한다.
        val csv =
            "Title,Status,Type,Priority\n" +
                "Bug A,Open,Bug,medium\n" +
                "Bug B,open,unknown-type,Medium\n"
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } answers { ByteArrayInputStream(csv.toByteArray()) }
        every { workflowStateCatalog.listStates(ProjectKey.of(projectKey), issueTypeKey = null) } returns
            listOf(WorkflowStateView(key = "open", name = "Open"))
        every { issueTypeCatalog.listTypes() } returns listOf(IssueTypeRef(key = "bug", name = "Bug"))

        val result = service.collectValues(job.id, actor, fieldMappings)

        assertThat(result.values.getValue(ValueTargetField.STATUS))
            .containsExactly(ValueCollectionEntry("open", "Open"))
        assertThat(result.values.getValue(ValueTargetField.TYPE))
            .containsExactly(
                ValueCollectionEntry("bug", "Bug"),
                ValueCollectionEntry("unknown-type", null),
            )
        assertThat(result.values.getValue(ValueTargetField.PRIORITY))
            .containsExactly(ValueCollectionEntry("medium", "Medium"))
        // 헤더 검증 1회 + 전량 스캔 1회 = 총 2회.
        verify(exactly = 2) { storage.get(job.sourceObjectKey) }
        verify(exactly = 1) { workflowStateCatalog.listStates(ProjectKey.of(projectKey), issueTypeKey = null) }
        verify(exactly = 1) { issueTypeCatalog.listTypes() }
    }

    // ── confirm — 값 매핑 정상 확정: C1 canonical 치환 + FR7 STATUS 관대 ─────────

    @Test
    fun `confirm saves canonical value mappings after user mapping and before enqueue`() {
        val job = makeJob(format = "JSON")
        val fieldMappings = mapOf("x" to TargetField.SUMMARY.key)
        val valueMappings =
            listOf(
                Triple(ValueTargetField.PRIORITY, "medium", "medium"),
                Triple(ValueTargetField.TYPE, "bg", "bug"),
                Triple(ValueTargetField.STATUS, "unresolved", "Custom Open"),
            )
        val expectedNormalized =
            mapOf(
                (ValueTargetField.PRIORITY to "medium") to "Medium",
                (ValueTargetField.TYPE to "bg") to "Bug",
                (ValueTargetField.STATUS to "unresolved") to "Custom Open",
            )
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { importJobRepository.transitionToPending(job.id, false) } returns true
        every { issueTypeCatalog.listTypes() } returns listOf(IssueTypeRef(key = "bug", name = "Bug"))
        justRun { importMappingRepository.saveAll(job.id, fieldMappings) }
        justRun { importValueMappingRepository.saveAll(job.id, expectedNormalized) }
        justRun { enqueuePublisher.enqueue(job.id) }

        val result =
            service.confirm(job.id, actor, fieldMappings, dryRun = false, valueMappings = valueMappings)

        assertThat(result.status).isEqualTo(ImportJobStatus.PENDING)
        verifyOrder {
            importMappingRepository.saveAll(job.id, fieldMappings)
            importUserMappingRepository.saveAll(job.id, emptyMap())
            importValueMappingRepository.saveAll(job.id, expectedNormalized)
            enqueuePublisher.enqueue(job.id)
        }
    }

    @Test
    fun `confirm without valueMappings saves empty value mapping map for backward compatibility`() {
        val job = makeJob(format = "JSON")
        val fieldMappings = mapOf("x" to TargetField.SUMMARY.key)
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { importJobRepository.transitionToPending(job.id, false) } returns true
        justRun { importMappingRepository.saveAll(job.id, fieldMappings) }
        justRun { importValueMappingRepository.saveAll(job.id, emptyMap()) }
        justRun { enqueuePublisher.enqueue(job.id) }

        val result = service.confirm(job.id, actor, fieldMappings, dryRun = false)

        assertThat(result.status).isEqualTo(ImportJobStatus.PENDING)
        verify(exactly = 1) { importValueMappingRepository.saveAll(job.id, emptyMap()) }
        verify(exactly = 0) { issueTypeCatalog.listTypes() }
    }

    // ── confirm — 핵심(CONCERN-3 미러): CAS 실패 시 값 매핑 저장 0회(TOCTOU) ──────

    @Test
    fun `confirm throws state conflict and skips value mapping save when CAS fails after pre-check passes`() {
        val job = makeJob(format = "JSON")
        val fieldMappings = mapOf("x" to TargetField.SUMMARY.key)
        val valueMappings = listOf(Triple(ValueTargetField.STATUS, "open", "Open"))
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { importJobRepository.transitionToPending(job.id, false) } returns false

        assertThrows<ImportMappingStateConflictException> {
            service.confirm(job.id, actor, fieldMappings, dryRun = false, valueMappings = valueMappings)
        }

        verify(exactly = 1) { importJobRepository.transitionToPending(job.id, false) }
        verify(exactly = 0) { importMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { importUserMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { importValueMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    // ── confirm — FR7 필드별 비대칭: TYPE/PRIORITY 는 엄격, STATUS 는 관대 ───────

    @Test
    fun `confirm throws ImportValueMappingInvalidException when TYPE target value is not in catalog`() {
        val job = makeJob(format = "JSON")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { issueTypeCatalog.listTypes() } returns listOf(IssueTypeRef(key = "bug", name = "Bug"))
        val valueMappings = listOf(Triple(ValueTargetField.TYPE, "x", "doesnotexist"))

        val ex =
            assertThrows<ImportValueMappingInvalidException> {
                service.confirm(job.id, actor, mapOf("x" to "summary"), dryRun = false, valueMappings = valueMappings)
            }

        assertThat(ex.errors.map { it.code }).contains(ImportValueMappingInvalidException.TARGET_VALUE_NOT_FOUND)
        verify(exactly = 0) { importJobRepository.transitionToPending(any(), any()) }
        verify(exactly = 0) { importValueMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    @Test
    fun `confirm throws ImportValueMappingInvalidException when PRIORITY target value is not one of canonical five`() {
        val job = makeJob(format = "JSON")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        val valueMappings = listOf(Triple(ValueTargetField.PRIORITY, "x", "urgent"))

        val ex =
            assertThrows<ImportValueMappingInvalidException> {
                service.confirm(job.id, actor, mapOf("x" to "summary"), dryRun = false, valueMappings = valueMappings)
            }

        assertThat(ex.errors.map { it.code }).contains(ImportValueMappingInvalidException.TARGET_VALUE_NOT_FOUND)
        verify(exactly = 0) { importJobRepository.transitionToPending(any(), any()) }
        verify(exactly = 0) { importValueMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    // ── confirm — E6 중복: 정규화 후 (대상 필드, 소스 값) 중복이면 대상 값이 달라도 422 ──

    @Test
    fun `confirm throws ImportValueMappingInvalidException for duplicate value mapping regardless of target value`() {
        val job = makeJob(format = "JSON")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        // "Open"/"open" 은 정규화(trim+lowercase) 후 동일한 소스 값이다 — STATUS 는 대상 값이 서로
        // 달라도(A vs B) 중복으로 판정되어야 한다.
        val valueMappings =
            listOf(
                Triple(ValueTargetField.STATUS, "Open", "A"),
                Triple(ValueTargetField.STATUS, "open", "B"),
            )

        val ex =
            assertThrows<ImportValueMappingInvalidException> {
                service.confirm(job.id, actor, mapOf("x" to "summary"), dryRun = false, valueMappings = valueMappings)
            }

        assertThat(ex.errors.map { it.code }).contains(ImportValueMappingInvalidException.DUPLICATE_VALUE_MAPPING)
        verify(exactly = 0) { importJobRepository.transitionToPending(any(), any()) }
        verify(exactly = 0) { importValueMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }
}
