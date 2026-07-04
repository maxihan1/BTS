// ImportMappingService 사용자 매핑 확장 단위 테스트 — collectUsers(작성자 식별자 수집+추천) + confirm
// 사용자 매핑 검증/저장(정규화 중복·미실재 대상·null 폴백) (FR-IM-02 PR-B Task 5)
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
 * [ImportMappingService] 의 [ImportMappingService.collectUsers] + `confirm` 사용자 매핑 확장 단위 테스트.
 *
 * [ImportMappingServiceTest] 와 동일하게 [ImportJobRepository]/[ImportMappingRepository]/
 * [ImportJobEnqueuePublisher]/[ImportObjectStoragePort]/[UserLookupPort]/[ImportUserMappingRepository]
 * 를 MockK 로 격리하고, [ImportRowParser] 는 실 인스턴스를 사용해 예측 가능한 CSV/JSON 바이트로부터
 * 실제 파싱 결과를 얻는다.
 *
 * 검증 항목.
 * - collectUsers: (a) 404, (b) CSV 필드매핑 무효 시 전량 스캔 전에 422(C1 — storage.get 1회만),
 *   (c) CSV 정상 매핑 시 distinct 정규화 식별자 + 추천 사용자/표시명, (d) JSON 은 필드매핑 검증 스킵 후
 *   바로 전량 스캔, (e) 식별자 0건이면 조회 스킵.
 * - confirm(userMappings): (f) 정규화형 중복 sourceIdentifier → 422(사전 side-effect 없음),
 *   (g) non-null targetUserId 미실재 → 422, (h) 정상 확정 — 필드매핑 저장 뒤·enqueue 앞에 사용자매핑
 *   저장(null 폴백 허용), (i) userMappings 생략 시 빈 맵 저장(하위호환).
 */
class ImportMappingServiceUserTest {
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
    private val aliceId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val daveId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
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

    // ── collectUsers ────────────────────────────────────────────────────────

    @Test
    fun `collectUsers throws 404 when job not found or not owned`() {
        val jobId = ImportJobId(UUID.randomUUID())
        every { importJobRepository.findByIdForRequester(jobId, otherActor) } returns null

        assertThrows<ResponseStatusException> {
            service.collectUsers(jobId, otherActor, mapOf("Title" to TargetField.SUMMARY.key))
        }
        verify(exactly = 0) { storage.get(any()) }
    }

    @Test
    fun `collectUsers throws ImportMappingInvalidException without full scan when CSV field mapping is invalid`() {
        val job = makeJob(format = "CSV")
        val csv = "Title,Reporter\nBug A,alice@corp.com\n"
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } answers { ByteArrayInputStream(csv.toByteArray()) }

        val ex =
            assertThrows<ImportMappingInvalidException> {
                // summary 대상이 매핑에서 빠져 있다 — 무효 매핑.
                service.collectUsers(job.id, actor, mapOf("Reporter" to TargetField.REPORTER.key))
            }

        assertThat(ex.errors.map { it.code }).contains(MappingValidator.SUMMARY_NOT_MAPPED)
        // 헤더 재읽기(검증)만 1회 — 무효 판정 후 전량 스캔은 실행되지 않는다(C1, ImportParseException→500 회피).
        verify(exactly = 1) { storage.get(job.sourceObjectKey) }
        verify(exactly = 0) { userLookupPort.resolveByEmails(any()) }
    }

    @Test
    fun `collectUsers collects distinct normalized identifiers from CSV and returns suggested user id and display name`() {
        val job = makeJob(format = "CSV")
        val fieldMappings =
            mapOf(
                "Title" to TargetField.SUMMARY.key,
                "Reporter" to TargetField.REPORTER.key,
                "Assignee" to TargetField.ASSIGNEE.key,
            )
        // 대소문자만 다른 Reporter(Alice@Corp.com / alice@corp.com) 는 정규화 후 하나로 합쳐져야 한다.
        val csv = "Title,Reporter,Assignee\nBug A,Alice@Corp.com,bob@corp.com\nBug B,alice@corp.com,\n"
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } answers { ByteArrayInputStream(csv.toByteArray()) }
        every { userLookupPort.resolveByEmails(setOf("alice@corp.com", "bob@corp.com")) } returns
            mapOf("alice@corp.com" to aliceId)
        every { userLookupPort.findDisplayNamesByIds(setOf(aliceId)) } returns mapOf(aliceId to "Alice Kim")

        val result = service.collectUsers(job.id, actor, fieldMappings)

        assertThat(result.users).containsExactly(
            UserCollectionEntry("alice@corp.com", aliceId, "Alice Kim"),
            UserCollectionEntry("bob@corp.com", null, null),
        )
        // 헤더 검증 1회 + 전량 스캔 1회 = 총 2회.
        verify(exactly = 2) { storage.get(job.sourceObjectKey) }
    }

    @Test
    fun `collectUsers skips field mapping validation and scans JSON directly for identifiers`() {
        val job = makeJob(format = "JSON")
        val json =
            """{"issues":[{"fields":{"summary":"Bug","reporter":{"emailAddress":"Carol@Corp.com"},""" +
                """"assignee":{"emailAddress":"dave@corp.com"}}}]}"""
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } answers { ByteArrayInputStream(json.toByteArray()) }
        every { userLookupPort.resolveByEmails(setOf("carol@corp.com", "dave@corp.com")) } returns
            mapOf("dave@corp.com" to daveId)
        every { userLookupPort.findDisplayNamesByIds(setOf(daveId)) } returns mapOf(daveId to "Dave Lee")

        val result = service.collectUsers(job.id, actor, mapOf("garbage" to "whatever"))

        assertThat(result.users).containsExactly(
            UserCollectionEntry("carol@corp.com", null, null),
            UserCollectionEntry("dave@corp.com", daveId, "Dave Lee"),
        )
        // JSON 은 필드매핑 검증에 storage 를 읽지 않으므로 전량 스캔 1회만 발생한다.
        verify(exactly = 1) { storage.get(job.sourceObjectKey) }
    }

    @Test
    fun `collectUsers returns empty result and skips lookups when no author identifiers are found`() {
        val job = makeJob(format = "CSV")
        val fieldMappings = mapOf("Title" to TargetField.SUMMARY.key)
        val csv = "Title\nBug A\n"
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { storage.get(job.sourceObjectKey) } answers { ByteArrayInputStream(csv.toByteArray()) }

        val result = service.collectUsers(job.id, actor, fieldMappings)

        assertThat(result.users).isEmpty()
        verify(exactly = 0) { userLookupPort.resolveByEmails(any()) }
        verify(exactly = 0) { userLookupPort.findDisplayNamesByIds(any()) }
    }

    // ── confirm — 사용자 매핑 검증 실패 시 side effect 없음 ─────────────────────

    @Test
    fun `confirm throws ImportUserMappingInvalidException for duplicate normalized source identifiers`() {
        val job = makeJob(format = "JSON")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        // 대상 사용자 실재 검증은 중복 검증과 독립적으로(단축 없이) 함께 수행되므로, 이 케이스에서
        // 중복 오류만 순수하게 관찰하려면 두 targetUserId 모두 실재하는 것으로 stub 한다.
        every { userLookupPort.findDisplayNamesByIds(setOf(aliceId, daveId)) } returns
            mapOf(aliceId to "Alice Kim", daveId to "Dave Lee")
        val userMappings = listOf("Alice@Corp.com" to aliceId, "alice@corp.com" to daveId)

        val ex =
            assertThrows<ImportUserMappingInvalidException> {
                service.confirm(job.id, actor, mapOf("x" to "summary"), dryRun = false, userMappings = userMappings)
            }

        assertThat(ex.errors.map { it.code }).contains(ImportUserMappingInvalidException.DUPLICATE_SOURCE_IDENTIFIER)
        verify(exactly = 0) { importJobRepository.transitionToPending(any(), any()) }
        verify(exactly = 0) { importMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { importUserMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    @Test
    fun `confirm throws ImportUserMappingInvalidException when non-null targetUserId does not exist`() {
        val job = makeJob(format = "JSON")
        val unknownUserId = UUID.fromString("00000000-0000-0000-0000-0000000000ff")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { userLookupPort.findDisplayNamesByIds(setOf(unknownUserId)) } returns emptyMap()
        val userMappings = listOf("carol@corp.com" to unknownUserId)

        val ex =
            assertThrows<ImportUserMappingInvalidException> {
                service.confirm(job.id, actor, mapOf("x" to "summary"), dryRun = false, userMappings = userMappings)
            }

        assertThat(ex.errors.map { it.code }).contains(ImportUserMappingInvalidException.TARGET_USER_NOT_FOUND)
        verify(exactly = 0) { importJobRepository.transitionToPending(any(), any()) }
        verify(exactly = 0) { importMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { importUserMappingRepository.saveAll(any(), any()) }
        verify(exactly = 0) { enqueuePublisher.enqueue(any()) }
    }

    // ── confirm — 정상 확정: 필드매핑 저장 뒤·enqueue 앞에 사용자매핑 저장 ────────

    @Test
    fun `confirm saves normalized user mappings after field mapping and before enqueue, allowing null targetUserId as fallback`() {
        val job = makeJob(format = "JSON")
        val fieldMappings = mapOf("x" to "summary")
        val expectedNormalized = mapOf("alice@corp.com" to aliceId, "carol@corp.com" to null)
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { importJobRepository.transitionToPending(job.id, false) } returns true
        every { userLookupPort.findDisplayNamesByIds(setOf(aliceId)) } returns mapOf(aliceId to "Alice Kim")
        justRun { importMappingRepository.saveAll(job.id, fieldMappings) }
        justRun { importUserMappingRepository.saveAll(job.id, expectedNormalized) }
        justRun { enqueuePublisher.enqueue(job.id) }
        val userMappings = listOf("Alice@Corp.com" to aliceId, "carol@corp.com" to null)

        val result = service.confirm(job.id, actor, fieldMappings, dryRun = false, userMappings = userMappings)

        assertThat(result.status).isEqualTo(ImportJobStatus.PENDING)
        verifyOrder {
            importMappingRepository.saveAll(job.id, fieldMappings)
            importUserMappingRepository.saveAll(job.id, expectedNormalized)
            enqueuePublisher.enqueue(job.id)
        }
    }

    @Test
    fun `confirm without userMappings saves empty user mapping map for backward compatibility`() {
        val job = makeJob(format = "JSON")
        val fieldMappings = mapOf("x" to "summary")
        every { importJobRepository.findByIdForRequester(job.id, actor) } returns job
        every { importJobRepository.transitionToPending(job.id, false) } returns true
        justRun { importMappingRepository.saveAll(job.id, fieldMappings) }
        justRun { importUserMappingRepository.saveAll(job.id, emptyMap()) }
        justRun { enqueuePublisher.enqueue(job.id) }

        val result = service.confirm(job.id, actor, fieldMappings, dryRun = false)

        assertThat(result.status).isEqualTo(ImportJobStatus.PENDING)
        verify(exactly = 1) { importUserMappingRepository.saveAll(job.id, emptyMap()) }
    }
}
