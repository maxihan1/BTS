// ImportUserMappingRepository 통합 테스트 (FR-IM-02 PR-B)
// 검증 범위: saveAll → findByJobId 라운드트립(target_user_id NULL 포함) · 재확정 전량 대체(멱등) · 빈 매핑 정리

package com.bts.search.imports.mapping.repository

import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.jooq.tables.references.IMPORT_JOBS
import com.bts.search.jooq.tables.references.IMPORT_USER_MAPPINGS
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ImportUserMappingRepository 통합 테스트.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-pgmq 위에서
 * Flyway V600~V607 마이그레이션 체인 적용 후 Repository 동작을 검증한다.
 * 테스트 DSLContext 는 Spring 없이 수동 구성되므로 [SearchPersistenceTestBase] 의 [dsl] 을 재사용한다.
 *
 * ## 검증 범위
 *
 * - saveAll → findByJobId 라운드트립 (source_identifier → target_user_id? Map 일치).
 *   특히 target_user_id NULL(미매핑=폴백 의미)이 실 insert 경로로 저장·재조회되는지 확인한다 (F1 누수 방지).
 * - saveAll 재호출: 이전 매핑 전량 대체 (delete-then-insert 멱등)
 * - saveAll 동일 매핑 재호출: 결과 불변 (멱등)
 * - findByJobId: 매핑 없는 job 은 빈 Map
 * - saveAll 빈 매핑: 기존 매핑 전량 제거
 *
 * import_user_mappings.import_job_id 는 import_jobs(id) FK 라, 매핑 저장 전 부모 job 을 먼저 시드한다.
 */
class ImportUserMappingRepositoryTest : SearchPersistenceTestBase() {
    private val repo get() = ImportUserMappingRepository(dsl)

    @AfterEach
    fun clean() {
        dsl.execute("DELETE FROM import_user_mappings")
        dsl.execute("DELETE FROM import_jobs")
    }

    /**
     * import_user_mappings.import_job_id FK 충족을 위해 부모 import_jobs 행을 먼저 삽입한다.
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
    fun `saveAll 후 findByJobId 로 조회 - target_user_id NULL 포함 라운드트립`() {
        val jobId = seedParentJob()
        val alice = UUID.randomUUID()
        val mappings: Map<String, UUID?> =
            mapOf(
                "alice@corp" to alice,
                "unknown@corp" to null,
            )

        repo.saveAll(jobId, mappings)

        val loaded = repo.findByJobId(jobId)
        assertThat(loaded).isEqualTo(mappings)
        assertThat(loaded).containsKey("unknown@corp")
        assertThat(loaded["unknown@corp"]).isNull()
    }

    @Test
    fun `findByJobId 는 NULL target_user_id 를 실제 DB 행에서 그대로 읽는다`() {
        val jobId = seedParentJob()
        repo.saveAll(jobId, mapOf("unknown@corp" to null))

        // 실 insert 경로 확인 — DB 에 1행 존재하며 target_user_id 는 NULL
        val rowCount =
            dsl.selectCount()
                .from(IMPORT_USER_MAPPINGS)
                .where(IMPORT_USER_MAPPINGS.IMPORT_JOB_ID.eq(jobId.value))
                .fetchOne(0, Int::class.java)
        assertThat(rowCount).isEqualTo(1)
        assertThat(repo.findByJobId(jobId)["unknown@corp"]).isNull()
    }

    @Test
    fun `saveAll 재호출 시 이전 매핑을 전량 대체 - 멱등`() {
        val jobId = seedParentJob()
        val bob = UUID.randomUUID()
        repo.saveAll(jobId, mapOf("alice@corp" to UUID.randomUUID(), "old@corp" to null))

        repo.saveAll(jobId, mapOf("bob@corp" to bob))

        assertThat(repo.findByJobId(jobId)).isEqualTo(mapOf<String, UUID?>("bob@corp" to bob))
    }

    @Test
    fun `saveAll 동일 매핑 재호출은 결과 불변 - 멱등`() {
        val jobId = seedParentJob()
        val mappings: Map<String, UUID?> = mapOf("alice@corp" to UUID.randomUUID())
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
        repo.saveAll(jobId, mapOf("alice@corp" to UUID.randomUUID()))

        repo.saveAll(jobId, emptyMap())

        assertThat(repo.findByJobId(jobId)).isEmpty()
    }
}
