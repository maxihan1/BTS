// 비동기 Export 작업 end-to-end 통합 테스트 — Tembo PostgreSQL + MinIO Testcontainers (FR-EX-02 Task 11)

package com.bts.search.export.job

import com.bts.search.export.job.application.DefaultExportSerializerFactory
import com.bts.search.export.job.application.ExportJobProcessor
import com.bts.search.export.job.application.ExportJobService
import com.bts.search.export.job.application.ExportSerializerFactory
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.export.job.event.ExportJobEnqueuePublisher
import com.bts.search.export.job.repository.ExportJobRepository
import com.bts.search.export.job.storage.MinioExportStorageAdapter
import com.bts.search.export.job.storage.MinioExportStorageConfig
import com.bts.search.export.job.storage.MinioExportStorageException
import com.bts.search.export.job.worker.ExportJobCleanupWorker
import com.bts.search.export.job.worker.ExportJobWorker
import com.bts.search.jooq.tables.references.EXPORT_JOBS
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import com.bts.search.web.SearchErrorCodes
import com.bts.search.web.dto.ExportRequest
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import io.minio.MinioClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.testcontainers.containers.MinIOContainer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * ExportJob 비동기 파이프라인 end-to-end 통합 테스트.
 *
 * ## 컨테이너 구성
 * - **PostgreSQL**: [SearchPersistenceTestBase] — quay.io/tembo/pg16-pgmq (Flyway V602 포함)
 * - **MinIO**: [MinIOContainer] — JVM-singleton, minio/minio
 *
 * ## 조립 범위
 * Spring 컨텍스트 없이 직접 인스턴스화한다. @Transactional 어노테이션은 프록시 미적용으로 무시되며
 * 모든 SQL은 auto-commit 모드로 실행된다. 이는 ExportJobRepositoryTest 와 동일한 패턴이다.
 *
 * 조립 대상: [ExportJobRepository], [ExportJobEnqueuePublisher], [MinioExportStorageAdapter],
 * [DefaultExportSerializerFactory], [ExportJobProcessor], [ExportJobService],
 * [ExportJobWorker], [ExportJobCleanupWorker].
 *
 * ## Fake IssueSearchPort
 * search-export-import 모듈에 실 IssueSearchAdapter 가 없으므로 (FR-EX-01 ADR §B1 vacuous 회피),
 * [FakeIssueSearchPort] 를 사용한다.
 * visibility 보안 술어의 데이터 레벨 검증은 issue-tracking 모듈의 IssueSearchAdapterTest 가 담당한다.
 * 본 테스트는 [com.bts.shared.search.IssueSearchQuery.viewerUserId] 에
 * [com.bts.search.export.job.domain.ExportJob.requesterUserId] 가 구조적으로 전달되는지만 확인한다.
 *
 * ## 검증 시나리오
 * - (a) 정상 흐름: 10,500건 submit → pollAndProcess → COMPLETED + MinIO 객체 + CSV 행 수
 * - (b) cleanup: COMPLETED job expires_at 과거 → cleanupExpired → DB·MinIO 삭제
 * - (c) 상한 초과: total=100,001 → FAILED + SEARCH_EXPORT_LIMIT_EXCEEDED
 * - (d) at-least-once 멱등: stale RUNNING 재청 → COMPLETED (TOCTOU 방어)
 */
@Suppress("LongMethod")
class ExportJobEndToEndIntegrationTest : SearchPersistenceTestBase() {

    companion object {
        private val log = LoggerFactory.getLogger(ExportJobEndToEndIntegrationTest::class.java)

        /** JVM-singleton MinIO 컨테이너. start() 는 class 로드 시 1회만 실행된다. */
        @JvmStatic
        val minio: MinIOContainer =
            MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z")
                .apply { start() }

        private const val TEST_BUCKET = "bts-exports"

        /** 시나리오A 시드 건수 — PAGE_SIZE(100) * 105 = 10,500 */
        @Suppress("MagicNumber")
        private const val SEED_COUNT_A = 10_500

        /** 시나리오B 시드 건수 — 최소값으로 처리 속도 최적화 */
        private const val SEED_COUNT_B = 5

        /** 시나리오D 시드 건수 */
        private const val SEED_COUNT_D = 10

        /** 시나리오C 상한 초과 total (MAX_ROWS=100,000 초과) */
        @Suppress("MagicNumber")
        private const val OVERRIDE_TOTAL_C = 100_001L

        /** 시나리오D stale 임계(600s) + 100s 초과 — 반드시 STALE_RUNNING_THRESHOLD_SECONDS 를 넘어야 한다 */
        @Suppress("MagicNumber")
        private const val STALE_PAST_SECONDS = 700L

        /** 시나리오B expires_at 과거 설정 분 수 */
        private const val PAST_EXPIRES_MINUTES = 5L

        /** 테스트 프로젝트 키 — ExportRequest.projectKey 패턴([A-Za-z0-9-]+) 준수 */
        private const val TEST_PROJECT_KEY = "ATLAS"

        /** createFakeHits 에서 사용하는 우선순위 값 */
        @Suppress("MagicNumber")
        private const val FAKE_PRIORITY = 3
    }

    // ── MinIO 컴포넌트 (lazy — 컨테이너 시작 후 초기화 보장) ────────────────────

    private val minioProperties: MinioExportStorageConfig.Properties by lazy {
        MinioExportStorageConfig.Properties(
            endpoint = minio.s3URL,
            accessKey = minio.userName,
            secretKey = minio.password,
            bucket = TEST_BUCKET,
        )
    }

    private val minioClient: MinioClient by lazy {
        MinioClient.builder()
            .endpoint(minioProperties.endpoint)
            .credentials(minioProperties.accessKey, minioProperties.secretKey)
            .build()
    }

    private val storage: MinioExportStorageAdapter by lazy {
        MinioExportStorageAdapter(minioClient, minioProperties)
    }

    // ── DB 컴포넌트 (lazy — SearchPersistenceTestBase.bootstrap() 후 dsl 보장) ─

    private val repo: ExportJobRepository by lazy { ExportJobRepository(dsl) }
    private val publisher: ExportJobEnqueuePublisher by lazy { ExportJobEnqueuePublisher(dsl) }

    // ── 직렬화 + Fake 검색 포트 ──────────────────────────────────────────────────

    /** 실 직렬화 수행 — 단위 테스트와 달리 CSV/XLSX 직렬화 경로 전체를 커버한다. */
    private val serializerFactory: ExportSerializerFactory = DefaultExportSerializerFactory()

    /**
     * 가변 상태 Fake IssueSearchPort.
     * 단일 인스턴스를 재사용하고 @AfterEach 에서 reset() 한다.
     * processor 가 lazy 초기화 시 이 인스턴스를 캡처하므로 상태 변경이 반영된다.
     */
    private val fakeSearchPort: FakeIssueSearchPort = FakeIssueSearchPort()

    // ── 서비스 컴포넌트 (lazy — repo, storage, fakeSearchPort 의존) ──────────────

    private val processor: ExportJobProcessor by lazy {
        ExportJobProcessor(fakeSearchPort, repo, storage, serializerFactory)
    }
    private val service: ExportJobService by lazy { ExportJobService(repo, publisher) }
    private val worker: ExportJobWorker by lazy { ExportJobWorker(dsl, repo, processor) }
    private val cleanupWorker: ExportJobCleanupWorker by lazy { ExportJobCleanupWorker(repo, storage) }

    /** MinIO 버킷이 없으면 생성한다. SearchPersistenceTestBase.bootstrap() 이후 실행된다. */
    @BeforeAll
    fun setupMinIO() {
        storage.ensureBucket()
    }

    @AfterEach
    fun cleanupAfterEach() {
        dsl.execute("DELETE FROM export_jobs")
        runCatching {
            dsl.execute("SELECT pgmq.purge_queue(?)", ExportJobWorker.QUEUE_NAME)
        }.onFailure { e ->
            log.warn(
                "pgmq 큐 정리 실패 — 잔류 메시지 가능 queue={} error={}",
                ExportJobWorker.QUEUE_NAME,
                e.message,
            )
        }
        fakeSearchPort.reset()
    }

    // ── (a) 정상 흐름 ─────────────────────────────────────────────────────────────

    @Test
    fun `시나리오A - 10500건 제출 처리 후 COMPLETED MinIO 객체 존재 CSV 행 수 일치`() {
        fakeSearchPort.setHits(createFakeHits(SEED_COUNT_A))
        val requesterUserId = UUID.randomUUID()

        val jobId = service.submit(
            ExportRequest(projectKey = TEST_PROJECT_KEY, query = "status = open"),
            requesterUserId,
        )
        worker.pollAndProcess()

        val job = repo.findByIdForRequester(jobId, requesterUserId)
            ?: error("시나리오A: job not found after pollAndProcess jobId=${jobId.value}")
        assertThat(job.status).isEqualTo(ExportJobStatus.COMPLETED)
        assertThat(job.downloadReady).isTrue()
        assertThat(job.rowCount).isEqualTo(SEED_COUNT_A.toLong())

        // viewerUserId 가 requesterUserId 로 전달됐는지 확인 — visibility 구조적 위임 증명
        assertThat(fakeSearchPort.capturedViewerUserId).isEqualTo(requesterUserId)

        // MinIO 객체 존재 확인 + CSV 행 수 파싱
        val objectKey = job.resultObjectKey
            ?: error("시나리오A: COMPLETED job must have resultObjectKey")
        val csvBytes = storage.openStream(objectKey).use { it.readBytes() }
        assertThat(countCsvDataRows(csvBytes)).isEqualTo(SEED_COUNT_A)
    }

    // ── (b) cleanup ───────────────────────────────────────────────────────────────

    @Test
    fun `시나리오B - COMPLETED job의 expires_at를 과거로 설정 후 cleanupExpired 실행하면 DB와 MinIO 모두 삭제된다`() {
        fakeSearchPort.setHits(createFakeHits(SEED_COUNT_B))
        val requesterUserId = UUID.randomUUID()

        val jobId = service.submit(
            ExportRequest(projectKey = TEST_PROJECT_KEY, query = "status = open"),
            requesterUserId,
        )
        worker.pollAndProcess()

        val completedJob = repo.findByIdForRequester(jobId, requesterUserId)
            ?: error("시나리오B: job not found after pollAndProcess jobId=${jobId.value}")
        assertThat(completedJob.status).isEqualTo(ExportJobStatus.COMPLETED)
        val objectKey = completedJob.resultObjectKey
            ?: error("시나리오B: COMPLETED job must have resultObjectKey")

        // expires_at 를 과거로 직접 UPDATE — TTL 만료 시뮬레이션
        dsl.update(EXPORT_JOBS)
            .set(EXPORT_JOBS.EXPIRES_AT, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(PAST_EXPIRES_MINUTES))
            .where(EXPORT_JOBS.ID.eq(jobId.value))
            .execute()

        cleanupWorker.cleanupExpired()

        // DB 행 삭제 확인
        assertThat(repo.findByIdForRequester(jobId, requesterUserId)).isNull()

        // MinIO 객체 삭제 확인
        assertThatThrownBy { storage.openStream(objectKey) }
            .isInstanceOf(MinioExportStorageException::class.java)
    }

    // ── (c) 상한 초과 ─────────────────────────────────────────────────────────────

    @Test
    fun `시나리오C - 검색 결과 100001건으로 상한 초과 시 FAILED errorCode SEARCH_EXPORT_LIMIT_EXCEEDED MinIO 객체 없음`() {
        fakeSearchPort.setOverrideTotal(OVERRIDE_TOTAL_C)
        val requesterUserId = UUID.randomUUID()

        val jobId = service.submit(
            ExportRequest(projectKey = TEST_PROJECT_KEY, query = "status = open"),
            requesterUserId,
        )
        worker.pollAndProcess()

        val job = repo.findByIdForRequester(jobId, requesterUserId)
            ?: error("시나리오C: job not found after pollAndProcess jobId=${jobId.value}")
        assertThat(job.status).isEqualTo(ExportJobStatus.FAILED)
        assertThat(job.errorCode).isEqualTo(SearchErrorCodes.SEARCH_EXPORT_LIMIT_EXCEEDED)
        assertThat(job.resultObjectKey).isNull()
    }

    // ── (d) at-least-once 멱등 (stale RUNNING 재청) ───────────────────────────────

    @Test
    fun `시나리오D - stale RUNNING 작업이 claimForRun으로 재청되어 COMPLETED로 완료된다`() {
        fakeSearchPort.setHits(createFakeHits(SEED_COUNT_D))
        val requesterUserId = UUID.randomUUID()

        // submit → PENDING + pgmq 메시지 enqueue
        val jobId = service.submit(
            ExportRequest(projectKey = TEST_PROJECT_KEY, query = "status = open"),
            requesterUserId,
        )

        // stale RUNNING 시뮬레이션 — 임계(600s) 를 초과한 과거 시각으로 직접 UPDATE
        // 크래시로 방치된 RUNNING 작업 재현 (교훈: advisory-lock-bigint-TOCTOU)
        dsl.update(EXPORT_JOBS)
            .set(EXPORT_JOBS.STATUS, ExportJobStatus.RUNNING.name)
            .set(EXPORT_JOBS.STARTED_AT, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(STALE_PAST_SECONDS))
            .where(EXPORT_JOBS.ID.eq(jobId.value))
            .execute()

        // pgmq 메시지는 여전히 큐에 있음 — pollAndProcess 가 stale RUNNING 을 재청
        worker.pollAndProcess()

        // 최종 COMPLETED 확인 — stale RUNNING 이 정상 재처리됨
        val job = repo.findByIdForRequester(jobId, requesterUserId)
            ?: error("시나리오D: job not found after stale RUNNING reprocess jobId=${jobId.value}")
        assertThat(job.status).isEqualTo(ExportJobStatus.COMPLETED)
        assertThat(job.downloadReady).isTrue()
        assertThat(job.rowCount).isEqualTo(SEED_COUNT_D.toLong())

        // 결과 파일이 MinIO 에 존재 (덮어쓰기 완료)
        val objectKey = job.resultObjectKey
            ?: error("시나리오D: COMPLETED job must have resultObjectKey")
        assertThatCode { storage.openStream(objectKey).close() }
            .doesNotThrowAnyException()
    }

    // ── private 헬퍼 ──────────────────────────────────────────────────────────────

    /**
     * 지정된 건수만큼 [IssueSearchHit] 목록을 생성한다.
     * 실제 이슈 DB 데이터 없이 E2E 직렬화 경로를 커버하기 위한 인메모리 시드다.
     */
    private fun createFakeHits(count: Int): List<IssueSearchHit> =
        (1..count).map { i ->
            IssueSearchHit(
                key = "$TEST_PROJECT_KEY-$i",
                summary = "이슈 $i",
                typeKey = "task",
                currentStateKey = "open",
                assigneeId = null,
                priority = FAKE_PRIORITY,
                priorityName = "Medium",
                projectKey = TEST_PROJECT_KEY,
                updatedAt = Instant.now(),
            )
        }

    /**
     * CSV 바이트 배열에서 데이터 행 수를 계산한다.
     *
     * UTF-8 BOM(EF BB BF) 을 제거하고 CRLF 로 분리 후 빈 줄을 제외한다.
     * 헤더 행 1줄을 빼서 실제 데이터 행 수를 반환한다.
     */
    @Suppress("MagicNumber")
    private fun countCsvDataRows(csvBytes: ByteArray): Int {
        // UTF-8 BOM(EF BB BF) 바이트 레벨 검사 — 리터럴 BOM 문자 소스 삽입 회피
        val hasBom =
            csvBytes.size >= 3 &&
                csvBytes[0] == 0xEF.toByte() &&
                csvBytes[1] == 0xBB.toByte() &&
                csvBytes[2] == 0xBF.toByte()
        val contentBytes = if (hasBom) csvBytes.drop(3).toByteArray() else csvBytes
        val csvText = contentBytes.toString(Charsets.UTF_8)
        val nonEmptyLines = csvText.split("\r\n").filter { it.isNotEmpty() }
        return (nonEmptyLines.size - 1).coerceAtLeast(0)
    }

}

/**
 * 메모리 페이지네이션 기반 Fake [IssueSearchPort].
 *
 * ## 사용 의도
 * search-export-import 모듈에 실 IssueSearchAdapter 가 없으므로 (FR-EX-01 ADR §B1 vacuous 회피)
 * 이 Fake 가 [IssueSearchPort] 계약만 제공한다.
 *
 * ## visibility 비재증명 근거
 * [IssueSearchPort] 구현체(issue-tracking 어댑터)가 visibility 보안 술어를 SQL 수준에서 적용한다.
 * 이 Fake 는 visibility 없이 전체 시드를 반환하므로,
 * visibility 데이터 레벨 검증은 issue-tracking 모듈의 IssueSearchAdapterTest 가 담당한다.
 * 본 E2E 테스트는 [ExportJobProcessor] 가 [com.bts.shared.search.IssueSearchQuery.viewerUserId] 에
 * [com.bts.search.export.job.domain.ExportJob.requesterUserId] 를 구조적으로 전달하는지만 확인한다.
 */
private class FakeIssueSearchPort : IssueSearchPort {
    private var hits: List<IssueSearchHit> = emptyList()
    private var overrideTotal: Long? = null

    /** 마지막으로 [search] 에 전달된 viewerUserId. 보안 구조 검증에 사용한다. */
    var capturedViewerUserId: UUID? = null

    /** 시드 이슈 목록을 설정하고 overrideTotal 을 초기화한다. */
    fun setHits(newHits: List<IssueSearchHit>) {
        hits = newHits
        overrideTotal = null
    }

    /** total 을 강제 지정한다 (상한 초과 시나리오 전용). hits 는 emptyList() 유지. */
    fun setOverrideTotal(total: Long) {
        overrideTotal = total
    }

    /** 테스트 격리를 위해 상태를 초기화한다. @AfterEach 에서 호출된다. */
    fun reset() {
        hits = emptyList()
        overrideTotal = null
        capturedViewerUserId = null
    }

    override fun search(query: IssueSearchQuery): IssueSearchPage {
        capturedViewerUserId = query.viewerUserId
        val total = overrideTotal ?: hits.size.toLong()
        val fromIdx = query.page * query.size
        val toIdx = minOf(fromIdx + query.size, hits.size)
        val pageItems = if (fromIdx >= hits.size) emptyList() else hits.subList(fromIdx, toIdx)
        return IssueSearchPage(items = pageItems, total = total, page = query.page, size = query.size)
    }
}
