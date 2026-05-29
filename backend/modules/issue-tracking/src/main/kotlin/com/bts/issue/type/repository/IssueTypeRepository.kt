// IssueTypeRepository — issue_types 테이블 jOOQ Repository (read + CRUD, FR-IS-02)

package com.bts.issue.type.repository

import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.ISSUE_TYPES
import com.bts.issue.type.domain.IssueType
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.table
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 이슈 타입 Repository.
 *
 * jOOQ DSLContext 를 통해 `issue_types` 테이블에 접근한다.
 * V003 마이그레이션이 5 표준 타입 (epic/story/task/subtask/bug) 을 seed 로 INSERT 한다.
 * V005 마이그레이션이 hierarchy_level 컬럼과 부분 unique index (ux_issue_types_key_active) 를 추가한다.
 *
 * ## 설계 결정
 *
 * - 활성 필터. `WHERE deleted_at IS NULL` (DATA.md §3 소프트 삭제).
 * - jOOQ DSL.table/DSL.field 동적 참조. issue_types 테이블은 generated 참조 미사용 패턴 유지.
 * - issues 테이블 접근은 jOOQ generated references (ISSUES) 사용 — IssueRepository.kt 와 동일.
 * - 소프트 삭제 후 동일 key 재INSERT 허용 — ux_issue_types_key_active 부분 unique (WHERE deleted_at IS NULL).
 * - countIssuesByTypeId: 스칼라 서브쿼리 방식 — 다중 JOIN + count cartesian product 방지 (learnings PR#31).
 * - reassignIssues: 단일 jOOQ UPDATE — 이슈별 루프 금지.
 *
 * @see com.bts.issue.type.domain.IssueType
 * @see com.bts.shared.issue.IssueTypeKey
 * @see com.bts.shared.issue.IssueTypeId
 */
@Repository
class IssueTypeRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val issueTypes = table(name("issue_types"))
    private val idField = field(name("id"), Long::class.java)
    private val keyField = field(name("key"), String::class.java)
    private val nameField = field(name("name"), String::class.java)
    private val descriptionField = field(name("description"), String::class.java)
    private val iconNameField = field(name("icon_name"), String::class.java)
    private val isStandardField = field(name("is_standard"), Boolean::class.java)
    private val hierarchyLevelField = field(name("hierarchy_level"), Int::class.java)
    private val createdAtField = field(name("created_at"), OffsetDateTime::class.java)
    private val updatedAtField = field(name("updated_at"), OffsetDateTime::class.java)
    private val deletedAtField = field(name("deleted_at"), OffsetDateTime::class.java)

    /**
     * 활성 이슈 타입 목록을 반환한다.
     *
     * `deleted_at IS NULL` 인 row 만 반환한다.
     * V003 seed 직후에는 5 표준 타입이 반환된다.
     *
     * @return 활성 [IssueType] 리스트. 비어있을 수 있다.
     */
    @Transactional(readOnly = true)
    fun findAll(): List<IssueType> =
        dsl.select(
            idField,
            keyField,
            nameField,
            descriptionField,
            iconNameField,
            isStandardField,
            hierarchyLevelField,
            createdAtField,
            updatedAtField,
            deletedAtField,
        )
            .from(issueTypes)
            .where(deletedAtField.isNull)
            .fetch()
            .map(::toIssueType)

    /**
     * 키로 활성 이슈 타입을 조회한다.
     *
     * @param key 조회할 이슈 타입 키. 예: `IssueTypeKey("epic")`.
     * @return 매칭되는 활성 [IssueType], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findByKey(key: IssueTypeKey): IssueType? =
        dsl.select(
            idField,
            keyField,
            nameField,
            descriptionField,
            iconNameField,
            isStandardField,
            hierarchyLevelField,
            createdAtField,
            updatedAtField,
            deletedAtField,
        )
            .from(issueTypes)
            .where(keyField.eq(key.value))
            .and(deletedAtField.isNull)
            .fetchOne()
            ?.let(::toIssueType)

    /**
     * id 로 활성 이슈 타입을 조회한다.
     *
     * @param id 조회할 이슈 타입 id.
     * @return 매칭되는 활성 [IssueType], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findById(id: IssueTypeId): IssueType? =
        dsl.select(
            idField,
            keyField,
            nameField,
            descriptionField,
            iconNameField,
            isStandardField,
            hierarchyLevelField,
            createdAtField,
            updatedAtField,
            deletedAtField,
        )
            .from(issueTypes)
            .where(idField.eq(id.value))
            .and(deletedAtField.isNull)
            .fetchOne()
            ?.let(::toIssueType)

    // ── CRUD ──────────────────────────────────────────────────────────────────────

    /**
     * 새 이슈 타입을 삽입하고 DB 생성 id 를 포함한 [IssueType] 을 반환한다.
     *
     * 부분 unique index 전제 — ux_issue_types_key_active (WHERE deleted_at IS NULL).
     * soft-delete 된 동일 key 는 재INSERT 허용된다.
     *
     * @param issueType DB 저장 전 [IssueType]. id 는 null 이어야 한다.
     * @return DB 생성 id 를 포함한 [IssueType].
     */
    @Transactional
    fun insert(issueType: IssueType): IssueType {
        log.debug("Inserting IssueType key={}", issueType.key.value)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        // generated ISSUE_TYPES reference 사용 — 동적 DSL.table() 은 RETURNING 절 미지원
        val record =
            dsl.insertInto(ISSUE_TYPES)
                .set(ISSUE_TYPES.KEY, issueType.key.value)
                .set(ISSUE_TYPES.NAME, issueType.name)
                .set(ISSUE_TYPES.DESCRIPTION, issueType.description)
                .set(ISSUE_TYPES.ICON_NAME, issueType.iconName)
                .set(ISSUE_TYPES.IS_STANDARD, issueType.isStandard)
                .set(ISSUE_TYPES.HIERARCHY_LEVEL, issueType.hierarchyLevel)
                .set(ISSUE_TYPES.CREATED_AT, now)
                .set(ISSUE_TYPES.UPDATED_AT, now)
                .returning()
                .fetchOne()
                ?: error("insert returning() returned null for key=${issueType.key.value}")
        return toIssueTypeFromRecord(record)
    }

    /**
     * 이슈 타입의 변경 가능 필드를 업데이트한다.
     *
     * 변경 대상. name / description / iconName / hierarchyLevel / updatedAt.
     * key / isStandard / createdAt 은 불변 필드이므로 업데이트하지 않는다.
     *
     * @param issueType 업데이트할 [IssueType]. id 가 null 이면 아무 행도 변경되지 않는다.
     */
    @Transactional
    fun update(issueType: IssueType) {
        val id = issueType.id ?: return
        log.debug("Updating IssueType id={}", id.value)
        dsl.update(issueTypes)
            .set(nameField, issueType.name)
            .set(descriptionField, issueType.description)
            .set(iconNameField, issueType.iconName)
            .set(hierarchyLevelField, issueType.hierarchyLevel)
            .set(updatedAtField, OffsetDateTime.now(ZoneOffset.UTC))
            .where(idField.eq(id.value))
            .and(deletedAtField.isNull)
            .execute()
    }

    /**
     * 이슈 타입을 소프트 삭제한다.
     *
     * deleted_at 를 현재 UTC 시각으로 설정한다.
     * 이미 삭제된 이슈 타입은 영향 행 0 반환.
     * 물리 삭제(DELETE) 금지 — DATA.md §3.
     *
     * @param id 삭제할 이슈 타입 id.
     */
    @Transactional
    fun softDelete(id: IssueTypeId) {
        log.debug("Soft-deleting IssueType id={}", id.value)
        dsl.update(issueTypes)
            .set(deletedAtField, OffsetDateTime.now(ZoneOffset.UTC))
            .where(idField.eq(id.value))
            .and(deletedAtField.isNull)
            .execute()
    }

    /**
     * 특정 이슈 타입에 속한 활성 이슈 수를 스칼라로 반환한다.
     *
     * 스칼라 selectCount — 다중 LEFT JOIN + count 는 cartesian product 위험 (learnings PR#31).
     * deleted_at IS NULL 조건으로 소프트 삭제된 이슈는 제외한다.
     *
     * @param typeId 조회할 이슈 타입 id (issues.type_id FK).
     * @return 활성 이슈 수. 없으면 0.
     */
    @Transactional(readOnly = true)
    fun countIssuesByTypeId(typeId: Long): Long =
        dsl.selectCount()
            .from(ISSUES)
            .where(ISSUES.TYPE_ID.eq(typeId))
            .and(ISSUES.DELETED_AT.isNull)
            .fetchOne(0, Long::class.java)
            ?: 0L

    /**
     * 특정 이슈 타입에 속한 활성 이슈들을 다른 타입으로 일괄 재할당한다.
     *
     * 단일 jOOQ UPDATE — 이슈별 루프 금지 (명세 §GREEN).
     * version 을 1 증가시켜 낙관락 충돌 감지가 가능하도록 한다 — IssueRepository.kt 선례.
     * deleted_at IS NULL 조건으로 소프트 삭제된 이슈는 변경하지 않는다.
     *
     * @param fromTypeId 변경 전 이슈 타입 id.
     * @param toTypeId 변경 후 이슈 타입 id.
     * @return 변경된 이슈 수.
     */
    @Transactional
    fun reassignIssues(
        fromTypeId: Long,
        toTypeId: Long,
    ): Long {
        log.debug("Reassigning issues fromTypeId={} toTypeId={}", fromTypeId, toTypeId)
        return dsl.update(ISSUES)
            .set(ISSUES.TYPE_ID, toTypeId)
            .set(ISSUES.VERSION, ISSUES.VERSION.plus(1))
            .where(ISSUES.TYPE_ID.eq(fromTypeId))
            .and(ISSUES.DELETED_AT.isNull)
            .execute()
            .toLong()
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    /**
     * 동적 DSL.field 참조로 읽은 [Record] 를 [IssueType] 으로 변환한다.
     *
     * findAll / findByKey / findById 의 SELECT 결과 변환에 사용한다.
     */
    private fun toIssueType(record: Record): IssueType =
        IssueType(
            id = IssueTypeId(record.get(idField)),
            key = IssueTypeKey(record.get(keyField)),
            name = record.get(nameField),
            description = record.get(descriptionField),
            iconName = record.get(iconNameField),
            isStandard = record.get(isStandardField),
            hierarchyLevel = record.get(hierarchyLevelField),
            createdAt = record.get(createdAtField).toInstant(),
            updatedAt = record.get(updatedAtField).toInstant(),
            deletedAt = record.get(deletedAtField)?.toInstant(),
        )

    /**
     * generated IssueTypesRecord 를 [IssueType] 으로 변환한다.
     *
     * insert RETURNING 결과 변환에 사용한다.
     * 동적 DSL.table() 은 RETURNING 절을 지원하지 않으므로 generated reference 로 INSERT 하고
     * 이 함수로 결과를 변환한다.
     */
    private fun toIssueTypeFromRecord(record: com.bts.issue.jooq.tables.records.IssueTypesRecord): IssueType =
        IssueType(
            id = IssueTypeId(record.id ?: error("issue_types.id must not be null after insert")),
            key = IssueTypeKey(record.key ?: error("issue_types.key must not be null")),
            name = record.name ?: error("issue_types.name must not be null"),
            description = record.description,
            iconName = record.iconName,
            isStandard = record.isStandard ?: false,
            hierarchyLevel = record.hierarchyLevel ?: 0,
            createdAt = (record.createdAt ?: error("issue_types.created_at must not be null")).toInstant(),
            updatedAt = (record.updatedAt ?: error("issue_types.updated_at must not be null")).toInstant(),
            deletedAt = record.deletedAt?.toInstant(),
        )
}
