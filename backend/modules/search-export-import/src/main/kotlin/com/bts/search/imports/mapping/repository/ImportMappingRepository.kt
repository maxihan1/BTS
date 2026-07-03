// Import 필드 매핑 Repository — import_mappings 테이블 jOOQ DSL 접근 (FR-IM-02)

package com.bts.search.imports.mapping.repository

import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.jooq.tables.references.IMPORT_MAPPINGS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Import 필드 매핑 Repository.
 *
 * jOOQ DSLContext 를 통해 import_mappings 테이블(V606)에 접근한다.
 * 한 Import 작업의 확정 매핑을 (source_field → target_field) 다행으로 저장/조회한다.
 * source_field 는 CSV 헤더/JSON 키 원문, target_field 는 BTS 대상 필드 key 또는 IGNORE 센티널이다.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5, NEVER-3).
 * 모든 public 쓰기 메서드에 @Transactional 을, 조회에 @Transactional(readOnly=true) 를 명시한다.
 *
 * import_mappings 는 import_jobs 종속 하위 테이블이라 소프트삭제(deleted_at)가 없다.
 * 부모 job 하드삭제 시 FK ON DELETE CASCADE(V606)로 동반 삭제된다. 이 Repository 는 직접 하드삭제하지 않는다.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점.
 */
@Repository
class ImportMappingRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 한 Import 작업의 확정 매핑을 저장한다 (delete-then-insert 멱등).
     *
     * 1. [jobId] 의 기존 매핑 행을 전부 DELETE 한다.
     * 2. [mappings] 가 비어 있으면 삭제만 수행하고 리턴한다 (매핑 전체 제거).
     * 3. [mappings] 의 각 항목을 batchInsert 로 한 번에 INSERT 한다.
     *
     * **멱등 의도** — 매핑 재확정(사용자가 UI 에서 다시 저장) 시 이전 매핑을 전량 대체한다.
     * 동일 매핑 재저장은 결과 불변, 다른 매핑 저장은 완전 교체가 되어 재실행 안전하다.
     * @Transactional 로 DELETE + batchInsert 가 원자적으로 커밋된다.
     *
     * @param jobId 매핑을 저장할 부모 Import 작업 식별자 (import_jobs FK).
     * @param mappings source_field → target_field 매핑. 빈 Map 이면 기존 매핑 전량 제거.
     */
    @Transactional
    fun saveAll(
        jobId: ImportJobId,
        mappings: Map<String, String>,
    ) {
        log.debug("saveAll importJobId={}, count={}", jobId.value, mappings.size)

        dsl.deleteFrom(IMPORT_MAPPINGS)
            .where(IMPORT_MAPPINGS.IMPORT_JOB_ID.eq(jobId.value))
            .execute()

        if (mappings.isEmpty()) return

        val records =
            mappings.map { (sourceField, targetField) ->
                dsl.newRecord(IMPORT_MAPPINGS).also { rec ->
                    rec.set(IMPORT_MAPPINGS.IMPORT_JOB_ID, jobId.value)
                    rec.set(IMPORT_MAPPINGS.SOURCE_FIELD, sourceField)
                    rec.set(IMPORT_MAPPINGS.TARGET_FIELD, targetField)
                }
            }
        dsl.batchInsert(records).execute()
    }

    /**
     * 한 Import 작업의 확정 매핑을 조회한다.
     *
     * `WHERE import_job_id = ?` 조건으로 해당 job 의 매핑 행을 읽어 source_field → target_field Map 으로 변환한다.
     * (import_job_id, source_field) 복합 PK 라 source_field 가 job 범위에서 유일하므로 Map key 충돌이 없다.
     *
     * @param jobId 조회할 부모 Import 작업 식별자.
     * @return source_field → target_field 매핑. 매핑이 없으면 빈 Map.
     */
    @Transactional(readOnly = true)
    fun findByJobId(jobId: ImportJobId): Map<String, String> =
        dsl.select(IMPORT_MAPPINGS.SOURCE_FIELD, IMPORT_MAPPINGS.TARGET_FIELD)
            .from(IMPORT_MAPPINGS)
            .where(IMPORT_MAPPINGS.IMPORT_JOB_ID.eq(jobId.value))
            .fetch()
            .associate { record ->
                val sourceField =
                    record.get(IMPORT_MAPPINGS.SOURCE_FIELD)
                        ?: error("import_mappings.source_field must not be null")
                val targetField =
                    record.get(IMPORT_MAPPINGS.TARGET_FIELD)
                        ?: error("import_mappings.target_field must not be null")
                sourceField to targetField
            }
}
