// Import 값 매핑 Repository — import_value_mappings 테이블 jOOQ DSL 접근 (FR-IM-02 PR-C)

package com.bts.search.imports.mapping.repository

import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.mapping.ValueTargetField
import com.bts.search.jooq.tables.pojos.ImportValueMappings
import com.bts.search.jooq.tables.references.IMPORT_VALUE_MAPPINGS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

/**
 * Import 값 매핑 Repository.
 *
 * jOOQ DSLContext 를 통해 import_value_mappings 테이블(V608)에 접근한다.
 * 한 Import 작업의 확정 값 매핑을 ((대상 필드, 소스 값) → 대상 값)으로 다행 저장/조회한다.
 * 대상 필드는 [ValueTargetField](상태/유형/우선순위), 소스 값은 CSV/JSON 원문(예: "open"), 대상 값은
 * 매핑된 BTS 대상 값(예: "OPEN") 이다.
 *
 * [ImportUserMappingRepository] 를 미러한 구조다. 단 값 형태가 다르다 — 이 Repository 의
 * target_value 는 **NOT NULL** 이다(V608). ImportUserMappingRepository 의 target_user_id 는 미매핑을
 * NULL 로 명시 저장하는 반면, 값 매핑 행은 항상 매핑이 확정된 상태로만 저장되며 미해결(unmapped) 값은
 * 행 자체를 만들지 않는다(V608 마이그레이션 주석 참조) — **PR-B 와의 NULL 비대칭**에 주의한다.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5, NEVER-3).
 * 모든 public 쓰기 메서드에 @Transactional 을, 조회에 @Transactional(readOnly=true) 를 명시한다.
 *
 * import_value_mappings 는 import_jobs 종속 하위 테이블이라 소프트삭제(deleted_at)가 없다.
 * 부모 job 하드삭제 시 FK ON DELETE CASCADE(V608)로 동반 삭제된다. 이 Repository 는 직접 하드삭제하지 않는다.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점.
 */
@Repository
class ImportValueMappingRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 한 Import 작업의 확정 값 매핑을 저장한다 (delete-then-insert 멱등).
     *
     * 1. [jobId] 의 기존 매핑 행을 전부 DELETE 한다.
     * 2. [mappings] 가 비어 있으면 삭제만 수행하고 리턴한다 (매핑 전체 제거).
     * 3. [mappings] 의 각 항목을 batchInsert 로 한 번에 INSERT 한다. target_field 는 [ValueTargetField.name]
     *    문자열로, target_value 는 NOT NULL 이라 그대로 바인딩한다.
     *
     * **멱등 의도** — 매핑 재확정(사용자가 UI 에서 다시 저장) 시 이전 매핑을 전량 대체한다.
     * 동일 매핑 재저장은 결과 불변, 다른 매핑 저장은 완전 교체가 되어 재실행 안전하다.
     * @Transactional 로 DELETE + batchInsert 가 원자적으로 커밋된다.
     *
     * @param jobId 매핑을 저장할 부모 Import 작업 식별자 (import_jobs FK).
     * @param mappings (대상 필드, 소스 값) → 대상 값 매핑. 빈 Map 이면 기존 매핑 전량 제거.
     */
    @Transactional
    fun saveAll(
        jobId: ImportJobId,
        mappings: Map<Pair<ValueTargetField, String>, String>,
    ) {
        log.debug("saveAll importJobId={}, count={}", jobId.value, mappings.size)

        dsl.deleteFrom(IMPORT_VALUE_MAPPINGS)
            .where(IMPORT_VALUE_MAPPINGS.IMPORT_JOB_ID.eq(jobId.value))
            .execute()

        if (mappings.isEmpty()) return

        val records =
            mappings.map { (key, targetValue) ->
                val (targetField, sourceValue) = key
                dsl.newRecord(IMPORT_VALUE_MAPPINGS).also { rec ->
                    rec.set(IMPORT_VALUE_MAPPINGS.IMPORT_JOB_ID, jobId.value)
                    rec.set(IMPORT_VALUE_MAPPINGS.TARGET_FIELD, targetField.name)
                    rec.set(IMPORT_VALUE_MAPPINGS.SOURCE_VALUE, sourceValue)
                    rec.set(IMPORT_VALUE_MAPPINGS.TARGET_VALUE, targetValue)
                }
            }
        dsl.batchInsert(records).execute()
    }

    /**
     * 한 Import 작업의 확정 값 매핑을 조회한다.
     *
     * `WHERE import_job_id = ?` 조건으로 해당 job 의 매핑 행을 읽어
     * (대상 필드, 소스 값) → 대상 값 Map 으로 변환한다.
     * (import_job_id, target_field, source_value) 복합 PK 라 (대상 필드, 소스 값) 조합이 job 범위에서
     * 유일하므로 Map key 충돌이 없다.
     *
     * `fetchInto`로 jOOQ 생성 POJO([ImportValueMappings])에 매핑한다. 이 POJO 는 3 컬럼 모두 NOT NULL(V608)을
     * 그대로 반영해 Kotlin 타입도 전부 non-null 이다 — PR-B ImportUserMappingRepository 의 POJO(target_user_id
     * nullable)와 달리 `?: error` 방어 코드가 불필요하다.
     *
     * @param jobId 조회할 부모 Import 작업 식별자.
     * @return (대상 필드, 소스 값) → 대상 값 매핑. 매핑이 없으면 빈 Map.
     */
    @Transactional(readOnly = true)
    fun findByJobId(jobId: ImportJobId): Map<Pair<ValueTargetField, String>, String> =
        dsl.selectFrom(IMPORT_VALUE_MAPPINGS)
            .where(IMPORT_VALUE_MAPPINGS.IMPORT_JOB_ID.eq(jobId.value))
            .fetchInto(ImportValueMappings::class.java)
            .associate { pojo ->
                (ValueTargetField.valueOf(pojo.targetField) to pojo.sourceValue) to pojo.targetValue
            }
}
