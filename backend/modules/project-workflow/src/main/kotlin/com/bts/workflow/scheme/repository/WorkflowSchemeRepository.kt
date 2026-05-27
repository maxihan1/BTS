// WorkflowSchemeRepository — workflow_schemes 테이블 jOOQ CRUD (save/findByKey/softDelete/findAll)

package com.bts.workflow.scheme.repository

import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import org.jooq.DSLContext
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.table
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 워크플로우 스킴 Repository.
 *
 * jOOQ DSLContext 를 통해 `workflow_schemes` 테이블에 접근한다.
 * V004 마이그레이션 이후 jOOQ codegen 이 `WorkflowSchemes` 테이블 클래스를 생성하면
 * generated 참조로 대체 예정 (REFACTOR 단계에서 codegen 트리거 포함).
 *
 * ## 설계 결정
 *
 * - softDelete: `deleted_at` 컬럼을 `NOW()` 로 SET. 물리 삭제 없음 (DATA.md §3).
 * - findByKey / findAll: `WHERE deleted_at IS NULL` 필터로 활성 스킴만 반환.
 * - save: `RETURNING id` 로 DB 생성 id 를 즉시 반환 — `WorkflowSchemeId` 할당.
 *
 * @param dsl jOOQ DSL 컨텍스트 (SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점).
 */
@Repository
@Suppress("PropertyName", "VariableNaming") // jOOQ 필드 상수 — SQL 컬럼명 매칭 (UPPER_SNAKE_CASE). codegen 도입 시 typed table 로 교체 예정.
class WorkflowSchemeRepository(private val dsl: DSLContext) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 테이블 / 컬럼 참조 ────────────────────────────────────────────────────────
    // jOOQ codegen 이 workflow_schemes 클래스를 생성하기 전까지 DSL.table/field 로 참조.
    // REFACTOR 단계에서 V004 포함 codegen 재실행 후 generated 참조로 교체.
    private val WORKFLOW_SCHEMES = table(name("workflow_schemes"))
    private val ID = field(name("id"), Long::class.java)
    private val KEY = field(name("key"), String::class.java)
    private val NAME = field(name("name"), String::class.java)
    private val DESCRIPTION = field(name("description"), String::class.java)
    private val IS_DEFAULT = field(name("is_default"), Boolean::class.java)
    private val CREATED_AT = field(name("created_at"), OffsetDateTime::class.java)
    private val UPDATED_AT = field(name("updated_at"), OffsetDateTime::class.java)
    private val DELETED_AT = field(name("deleted_at"), OffsetDateTime::class.java)

    /**
     * WorkflowScheme 을 INSERT 하고 DB 생성 id 가 할당된 새 인스턴스를 반환한다.
     *
     * `RETURNING id` 로 BIGSERIAL 자동 증가 id 를 즉시 획득한다.
     *
     * @param scheme 저장할 스킴. [WorkflowScheme.id] 는 null 이어야 한다 (신규 저장).
     * @return [WorkflowSchemeId] 가 할당된 인스턴스.
     */
    @Transactional
    fun save(scheme: WorkflowScheme): WorkflowScheme {
        log.debug("workflow scheme save: key={}", scheme.key.value)

        val record =
            dsl
                .insertInto(WORKFLOW_SCHEMES)
                .columns(KEY, NAME, DESCRIPTION, IS_DEFAULT, CREATED_AT, UPDATED_AT, DELETED_AT)
                .values(
                    scheme.key.value,
                    scheme.name,
                    scheme.description,
                    scheme.isDefault,
                    scheme.createdAt.toOffsetDateTime(),
                    scheme.updatedAt.toOffsetDateTime(),
                    scheme.deletedAt?.toOffsetDateTime(),
                )
                .returning(ID, KEY, NAME, DESCRIPTION, IS_DEFAULT, CREATED_AT, UPDATED_AT, DELETED_AT)
                .fetchOne() ?: error("workflow_schemes INSERT 후 RETURNING 결과 없음 — key=${scheme.key.value}")

        return record.toWorkflowScheme()
    }

    /**
     * id 로 활성 WorkflowScheme 을 조회한다.
     *
     * `WHERE id = :id AND deleted_at IS NULL` 필터 적용.
     * T21 findAssignedScheme D10 auto-assign 흐름에서 saveAssignment 후 scheme 재조회에 사용한다.
     *
     * @param id 조회할 스킴 식별자.
     * @return 존재하는 활성 스킴, 부재 또는 soft-delete 된 경우 null.
     */
    @Transactional(readOnly = true)
    fun findById(id: WorkflowSchemeId): WorkflowScheme? {
        val record =
            dsl
                .selectFrom(WORKFLOW_SCHEMES)
                .where(ID.eq(id.value).and(DELETED_AT.isNull))
                .fetchOne()

        return record?.toWorkflowScheme()
    }

    /**
     * key 로 활성 WorkflowScheme 을 조회한다.
     *
     * `WHERE key = :key AND deleted_at IS NULL` 필터 적용.
     *
     * @param key 조회할 스킴 키.
     * @return 존재하는 활성 스킴, 부재 또는 soft-delete 된 경우 null.
     */
    @Transactional(readOnly = true)
    fun findByKey(key: WorkflowSchemeKey): WorkflowScheme? {
        val record =
            dsl
                .selectFrom(WORKFLOW_SCHEMES)
                .where(KEY.eq(key.value).and(DELETED_AT.isNull))
                .fetchOne()

        return record?.toWorkflowScheme()
    }

    /**
     * 모든 활성 WorkflowScheme 목록을 반환한다.
     *
     * `WHERE deleted_at IS NULL` 필터 적용. soft-delete 된 스킴은 제외.
     *
     * @return 활성 스킴 목록. 비어 있을 수 있음.
     */
    @Transactional(readOnly = true)
    fun findAll(): List<WorkflowScheme> =
        dsl
            .selectFrom(WORKFLOW_SCHEMES)
            .where(DELETED_AT.isNull)
            .fetch()
            .map { it.toWorkflowScheme() }

    /**
     * 스킴 필드를 UPDATE 한다.
     *
     * `UPDATE workflow_schemes SET name=?, description=?, is_default=?, updated_at=NOW() WHERE id=?` 실행.
     * key 는 immutable 이므로 변경 대상에서 제외한다 (EC-4 D11 표준 스킴 필드 잠금은 Application Service 계층에서 적용).
     *
     * @param scheme 갱신할 스킴. [WorkflowScheme.id] 는 non-null 이어야 한다.
     * @return 갱신된 스킴 인스턴스.
     */
    @Transactional
    fun update(scheme: WorkflowScheme): WorkflowScheme {
        val id = requireNotNull(scheme.id) { "WorkflowScheme.id must not be null for update" }
        log.debug("workflow scheme update: id={} key={}", id.value, scheme.key.value)

        val now = OffsetDateTime.now(ZoneOffset.UTC)
        dsl
            .update(WORKFLOW_SCHEMES)
            .set(NAME, scheme.name)
            .set(DESCRIPTION, scheme.description)
            .set(IS_DEFAULT, scheme.isDefault)
            .set(UPDATED_AT, now)
            .where(ID.eq(id.value))
            .execute()

        return WorkflowScheme.reconstruct(
            id = id,
            key = scheme.key,
            name = scheme.name,
            description = scheme.description,
            isDefault = scheme.isDefault,
            createdAt = scheme.createdAt,
            updatedAt = now.toInstant(),
            deletedAt = scheme.deletedAt,
        )
    }

    /**
     * id 에 해당하는 스킴을 soft-delete 한다.
     *
     * `UPDATE workflow_schemes SET deleted_at = NOW() WHERE id = :id` 실행.
     * 물리 삭제 없음 (DATA.md §3).
     *
     * @param id soft-delete 할 스킴 식별자.
     */
    @Transactional
    fun softDelete(id: WorkflowSchemeId) {
        log.debug("workflow scheme softDelete: id={}", id.value)

        dsl
            .update(WORKFLOW_SCHEMES)
            .set(DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(ID.eq(id.value))
            .execute()
    }

    // ── 내부 변환 ──────────────────────────────────────────────────────────────

    /**
     * jOOQ Record → WorkflowScheme 도메인 객체 변환.
     *
     * WorkflowScheme 생성자가 private 이므로 리플렉션 없이 복원하기 위해
     * 별도 companion factory `reconstruct` 를 사용한다.
     */
    private fun org.jooq.Record.toWorkflowScheme(): WorkflowScheme =
        WorkflowScheme.reconstruct(
            id = WorkflowSchemeId(this[ID]!!),
            key = WorkflowSchemeKey(this[KEY]!!),
            name = this[NAME]!!,
            description = this[DESCRIPTION],
            isDefault = this[IS_DEFAULT]!!,
            createdAt = this[CREATED_AT]!!.toInstant(),
            updatedAt = this[UPDATED_AT]!!.toInstant(),
            deletedAt = this[DELETED_AT]?.toInstant(),
        )

    // ── Instant ↔ OffsetDateTime 변환 헬퍼 ──────────────────────────────────

    private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
}
