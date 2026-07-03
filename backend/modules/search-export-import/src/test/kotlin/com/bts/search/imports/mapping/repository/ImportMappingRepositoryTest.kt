// ImportMappingRepository 통합 테스트 (FR-IM-02)
// 검증 범위: saveAll → findByJobId 라운드트립 · 재확정 시 전량 대체(멱등) · 빈 매핑 정리

package com.bts.search.imports.mapping.repository

import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.jooq.tables.references.IMPORT_JOBS
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ImportMappingRepository 통합 테스트.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-pgmq 위에서
 * Flyway V600~V606 마이그레이션 체인 적용 후 Repository 동작을 검증한다.
 * 테스트 DSLContext 는 Spring 없이 수동 구성되므로 [SearchPersistenceTestBase] 의 [dsl] 을 재사용한다.
 *
 * ## 검증 범위
 *
 * - saveAll → findByJobId 라운드트립 (source_field → target_field Map 일치)
 * - saveAll 재호출: 이전 매핑 전량 대체 (delete-then-insert 멱등)
 * - saveAll 동일 매핑 재호출: 결과 불변 (멱등)
 * - findByJobId: 매핑 없는 job 은 빈 Map
 * - saveAll 빈 매핑: 기존 매핑 전량 제거
 *
 * import_mappings.import_job_id 는 import_jobs(id) FK 라, 매핑 저장 전 부모 job 을 먼저 시드한다.
 */
class ImportMappingRepositoryTest : SearchPersistenceTestBase() {
    private val repo get() = ImportMappingRepository(dsl)

    @AfterEach
    fun clean() {
        dsl.execute("DELETE FROM import_mappings")
        dsl.execute("DELETE FROM import_jobs")
    }

    /**
     * import_mappings.import_job_id FK 충족을 위해 부모 import_jobs 행을 먼저 삽입한다.
     *
     * dry_run/status/progress/succeeded_rows/failed_rows/created_at 은 DB DEFAULT 로 남기고,
     * NOT NULL 이며 DEFAULT 없는 컬럼만 채운다.
     */
    private fun seedParentJob(id: ImportJobId = ImportJobId(UUID.randomUUID())): ImportJobId {
        dsl.insertInto(IMPORT_JOBS)
            .set(IMPORT_JOBS.ID, id.value)
            .set(IMPORT_JOBS.PROJECT_KEY, "ATLAS")
            .set(IMPORT_JOBS.FORMAT, "CSV")
            .set(IMPORT_JOBS.SOURCE_OBJECT_KEY, "imports/raw/${UUID.randomUUID()}.csv")
            .set(IMPORT_JOBS.REQUESTER_USER_ID, UUID.randomUUID())
            .execute()
        return id
    }

    @Test
    fun `saveAll 후 findByJobId 로 조회 - 매핑 라운드트립`() {
        val jobId = seedParentJob()
        val mappings = mapOf("Summary" to "summary", "설명" to "description", "Status" to "IGNORE")

        repo.saveAll(jobId, mappings)

        assertThat(repo.findByJobId(jobId)).isEqualTo(mappings)
    }

    @Test
    fun `saveAll 재호출 시 이전 매핑을 전량 대체 - 멱등`() {
        val jobId = seedParentJob()
        repo.saveAll(jobId, mapOf("Summary" to "summary", "Old" to "IGNORE"))

        repo.saveAll(jobId, mapOf("Title" to "summary"))

        assertThat(repo.findByJobId(jobId)).isEqualTo(mapOf("Title" to "summary"))
    }

    @Test
    fun `saveAll 동일 매핑 재호출은 결과 불변 - 멱등`() {
        val jobId = seedParentJob()
        val mappings = mapOf("Summary" to "summary")
        repo.saveAll(jobId, mappings)

        repo.saveAll(jobId, mappings)

        assertThat(repo.findByJobId(jobId)).isEqualTo(mappings)
    }

    @Test
    fun `findByJobId 는 매핑 없는 job 에 대해 빈 Map 반환`() {
        val jobId = seedParentJob()

        assertThat(repo.findByJobId(jobId)).isEmpty()
    }

    @Test
    fun `saveAll 빈 매핑은 기존 매핑을 모두 제거`() {
        val jobId = seedParentJob()
        repo.saveAll(jobId, mapOf("Summary" to "summary"))

        repo.saveAll(jobId, emptyMap())

        assertThat(repo.findByJobId(jobId)).isEmpty()
    }
}
