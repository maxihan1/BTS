// Import 사용자 매핑 Repository — import_user_mappings 테이블 jOOQ DSL 접근 (FR-IM-02 PR-B)

package com.bts.search.imports.mapping.repository

import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.jooq.tables.references.IMPORT_USER_MAPPINGS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Import 사용자 매핑 Repository.
 *
 * jOOQ DSLContext 를 통해 import_user_mappings 테이블(V607)에 접근한다.
 * 한 Import 작업의 확정 사용자 매핑을 (source_identifier → target_user_id?) 다행으로 저장/조회한다.
 * source_identifier 는 소스(CSV/JSON)의 작성자 식별자 원문, target_user_id 는 매핑된 BTS 사용자 UUID 이다.
 *
 * **target_user_id NULL = 미매핑(폴백 의미)** — 소스 식별자에 대응하는 BTS 사용자를 찾지 못했음을
 * 명시적으로 기록한다. 프로세서(후속 task)가 findByJobId 로 로드해 행별로 해석하며,
 * NULL 이면 요청자 등 폴백 주체로 대체한다. 따라서 NULL 은 정당한 저장 값이며 저장/조회 모두 보존해야 한다.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5, NEVER-3).
 * 모든 public 쓰기 메서드에 @Transactional 을, 조회에 @Transactional(readOnly=true) 를 명시한다.
 *
 * import_user_mappings 는 import_jobs 종속 하위 테이블이라 소프트삭제(deleted_at)가 없다.
 * 부모 job 하드삭제 시 FK ON DELETE CASCADE(V607)로 동반 삭제된다. 이 Repository 는 직접 하드삭제하지 않는다.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점.
 */
@Repository
class ImportUserMappingRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 한 Import 작업의 확정 사용자 매핑을 저장한다 (delete-then-insert 멱등).
     *
     * 1. [jobId] 의 기존 매핑 행을 전부 DELETE 한다.
     * 2. [mappings] 가 비어 있으면 삭제만 수행하고 리턴한다 (매핑 전체 제거).
     * 3. [mappings] 의 각 항목을 batchInsert 로 한 번에 INSERT 한다. target_user_id 는 NULL 이면 NULL 그대로 바인딩한다.
     *
     * **멱등 의도** — 매핑 재확정(사용자가 UI 에서 다시 저장) 시 이전 매핑을 전량 대체한다.
     * 동일 매핑 재저장은 결과 불변, 다른 매핑 저장은 완전 교체가 되어 재실행 안전하다.
     * @Transactional 로 DELETE + batchInsert 가 원자적으로 커밋된다.
     *
     * @param jobId 매핑을 저장할 부모 Import 작업 식별자 (import_jobs FK).
     * @param mappings source_identifier → target_user_id? 매핑. 값이 NULL 이면 미매핑(폴백)으로 저장한다.
     *   빈 Map 이면 기존 매핑 전량 제거.
     */
    @Transactional
    fun saveAll(
        jobId: ImportJobId,
        mappings: Map<String, UUID?>,
    ) {
        log.debug("saveAll importJobId={}, count={}", jobId.value, mappings.size)

        dsl.deleteFrom(IMPORT_USER_MAPPINGS)
            .where(IMPORT_USER_MAPPINGS.IMPORT_JOB_ID.eq(jobId.value))
            .execute()

        if (mappings.isEmpty()) return

        val records =
            mappings.map { (sourceIdentifier, targetUserId) ->
                dsl.newRecord(IMPORT_USER_MAPPINGS).also { rec ->
                    rec.set(IMPORT_USER_MAPPINGS.IMPORT_JOB_ID, jobId.value)
                    rec.set(IMPORT_USER_MAPPINGS.SOURCE_IDENTIFIER, sourceIdentifier)
                    // target_user_id 는 nullable — NULL(미매핑)을 그대로 바인딩한다.
                    rec.set(IMPORT_USER_MAPPINGS.TARGET_USER_ID, targetUserId)
                }
            }
        dsl.batchInsert(records).execute()
    }

    /**
     * 한 Import 작업의 확정 사용자 매핑을 조회한다.
     *
     * `WHERE import_job_id = ?` 조건으로 해당 job 의 매핑 행을 읽어 source_identifier → target_user_id? Map 으로 변환한다.
     * (import_job_id, source_identifier) 복합 PK 라 source_identifier 가 job 범위에서 유일하므로 Map key 충돌이 없다.
     *
     * target_user_id 는 nullable 이다. NULL(미매핑=폴백)을 그대로 Map 값으로 보존한다 —
     * NULL 에서 크래시하지 않는다(source_identifier 만 non-null 이라 방어적 검증 대상).
     *
     * @param jobId 조회할 부모 Import 작업 식별자.
     * @return source_identifier → target_user_id? 매핑. 매핑이 없으면 빈 Map. 값이 NULL 이면 미매핑.
     */
    @Transactional(readOnly = true)
    fun findByJobId(jobId: ImportJobId): Map<String, UUID?> =
        dsl.select(IMPORT_USER_MAPPINGS.SOURCE_IDENTIFIER, IMPORT_USER_MAPPINGS.TARGET_USER_ID)
            .from(IMPORT_USER_MAPPINGS)
            .where(IMPORT_USER_MAPPINGS.IMPORT_JOB_ID.eq(jobId.value))
            .fetch()
            .associate { record ->
                val sourceIdentifier =
                    record.get(IMPORT_USER_MAPPINGS.SOURCE_IDENTIFIER)
                        ?: error("import_user_mappings.source_identifier must not be null")
                // target_user_id 는 정당한 NULL(미매핑)을 가질 수 있어 그대로 보존한다.
                sourceIdentifier to record.get(IMPORT_USER_MAPPINGS.TARGET_USER_ID)
            }
}
