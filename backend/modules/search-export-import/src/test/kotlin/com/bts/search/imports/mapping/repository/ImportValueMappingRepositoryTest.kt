// ImportValueMappingRepository 통합 테스트 (FR-IM-02 PR-C)
// 검증 범위: saveAll → findByJobId 라운드트립(target_value NOT NULL) · 재확정 전량 대체(멱등) · 빈 매핑 정리

package com.bts.search.imports.mapping.repository

import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.mapping.ValueTargetField
import com.bts.search.jooq.tables.references.IMPORT_JOBS
import com.bts.search.jooq.tables.references.IMPORT_VALUE_MAPPINGS
import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ImportValueMappingRepository 통합 테스트.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-pgmq 위에서
 * Flyway V600~V608 마이그레이션 체인 적용 후 Repository 동작을 검증한다.
 * 테스트 DSLContext 는 Spring 없이 수동 구성되므로 [SearchPersistenceTestBase] 의 [dsl] 을 재사용한다.
 *
 * ## 검증 범위
 *
 * - saveAll → findByJobId 라운드트립 ((ValueTargetField, sourceValue) → targetValue Map 일치).
 *   실 insert 경로로 저장 후 재조회해 rowCount·값을 단언한다 (F1 round-trip — mock/raw-seed 은폐 금지).
 * - saveAll 재호출: 이전 매핑 전량 대체 (delete-then-insert 멱등)
 * - saveAll 동일 매핑 재호출: 결과 불변 (멱등)
 * - findByJobId: 매핑 없는 job 은 빈 Map
 * - saveAll 빈 매핑: 기존 매핑 전량 제거
 * - target_field 컬럼이 enum 이름 문자열(STATUS/TYPE/PRIORITY)로 저장되는지 확인
 *
 * import_value_mappings.import_job_id 는 import_jobs(id) FK 라, 매핑 저장 전 부모 job 을 먼저 시드한다.
 */
class ImportValueMappingRepositoryTest : SearchPersistenceTestBase() {
    private val repo get() = ImportValueMappingRepository(dsl)

    @AfterEach
    fun clean() {
        dsl.execute("DELETE FROM import_value_mappings")
        dsl.execute("DELETE FROM import_jobs")
    }

    /**
     * import_value_mappings.import_job_id FK 충족을 위해 부모 import_jobs 행을 먼저 삽입한다.
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
    fun `saveAll 후 findByJobId 로 조회 - 3종 필드 라운드트립`() {
        val jobId = seedParentJob()
        val mappings: Map<Pair<ValueTargetField, String>, String> =
            mapOf(
                (ValueTargetField.STATUS to "open") to "OPEN",
                (ValueTargetField.TYPE to "bug") to "BUG",
                (ValueTargetField.PRIORITY to "high") to "HIGH",
            )

        repo.saveAll(jobId, mappings)

        val loaded = repo.findByJobId(jobId)
        assertThat(loaded).isEqualTo(mappings)
    }

    @Test
    fun `findByJobId 는 실제 DB 행에서 target_value 를 그대로 읽는다`() {
        val jobId = seedParentJob()
        repo.saveAll(jobId, mapOf((ValueTargetField.STATUS to "open") to "OPEN"))

        // 실 insert 경로 확인 — DB 에 1행 존재하며 target_field/target_value 가 기대한 그대로
        val rowCount =
            dsl.selectCount()
                .from(IMPORT_VALUE_MAPPINGS)
                .where(IMPORT_VALUE_MAPPINGS.IMPORT_JOB_ID.eq(jobId.value))
                .fetchOne(0, Int::class.java)
        assertThat(rowCount).isEqualTo(1)

        val row =
            dsl.select(IMPORT_VALUE_MAPPINGS.TARGET_FIELD, IMPORT_VALUE_MAPPINGS.SOURCE_VALUE, IMPORT_VALUE_MAPPINGS.TARGET_VALUE)
                .from(IMPORT_VALUE_MAPPINGS)
                .where(IMPORT_VALUE_MAPPINGS.IMPORT_JOB_ID.eq(jobId.value))
                .fetchOne()
                ?: error("expected exactly one row")
        assertThat(row.get(IMPORT_VALUE_MAPPINGS.TARGET_FIELD)).isEqualTo("STATUS")
        assertThat(row.get(IMPORT_VALUE_MAPPINGS.SOURCE_VALUE)).isEqualTo("open")
        assertThat(row.get(IMPORT_VALUE_MAPPINGS.TARGET_VALUE)).isEqualTo("OPEN")

        assertThat(repo.findByJobId(jobId)[ValueTargetField.STATUS to "open"]).isEqualTo("OPEN")
    }

    @Test
    fun `saveAll 재호출 시 이전 매핑을 전량 대체 - 멱등`() {
        val jobId = seedParentJob()
        repo.saveAll(
            jobId,
            mapOf(
                (ValueTargetField.STATUS to "open") to "OPEN",
                (ValueTargetField.TYPE to "bug") to "BUG",
            ),
        )

        repo.saveAll(jobId, mapOf((ValueTargetField.PRIORITY to "high") to "HIGH"))

        assertThat(repo.findByJobId(jobId))
            .isEqualTo(mapOf<Pair<ValueTargetField, String>, String>((ValueTargetField.PRIORITY to "high") to "HIGH"))
    }

    @Test
    fun `saveAll 동일 매핑 재호출은 결과 불변 - 멱등`() {
        val jobId = seedParentJob()
        val mappings: Map<Pair<ValueTargetField, String>, String> = mapOf((ValueTargetField.STATUS to "open") to "OPEN")
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
        repo.saveAll(jobId, mapOf((ValueTargetField.STATUS to "open") to "OPEN"))

        repo.saveAll(jobId, emptyMap())

        assertThat(repo.findByJobId(jobId)).isEmpty()
    }
}
