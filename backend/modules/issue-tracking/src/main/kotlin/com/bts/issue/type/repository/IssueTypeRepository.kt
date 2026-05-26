// IssueTypeRepository — issue_types 테이블 read-only jOOQ Repository (5 표준 seed 조회, CRUD 후속 FR-IS-02)

package com.bts.issue.type.repository

import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.domain.IssueTypeId
import com.bts.issue.type.domain.IssueTypeKey
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL.field
import org.jooq.impl.DSL.name
import org.jooq.impl.DSL.table
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime

/**
 * 이슈 타입 Repository (read-only).
 *
 * jOOQ DSLContext 를 통해 `issue_types` 테이블에 접근한다.
 * V003 마이그레이션이 5 표준 타입 (epic/story/task/subtask/bug) 을 seed 로 INSERT 한다.
 *
 * ## PR scope (read-only)
 *
 * 본 PR (FR-WF-02) 에서는 표준 5종 read 만 제공한다.
 * CRUD (save / update / delete) 와 커스텀 IssueType 생성은 후속 FR-IS-02 PR scope.
 *
 * ## 설계 결정
 *
 * - 활성 필터. `WHERE deleted_at IS NULL` (DATA.md §3 소프트 삭제).
 * - jOOQ DSL.table/DSL.field 동적 참조 (jOOQ codegen V003 인식 시점에 generated 참조로 전환 가능).
 * - WorkflowSchemeRepository / SchemeIssueTypeMappingRepository 와 동일 패턴.
 *
 * @see com.bts.issue.type.domain.IssueType
 * @see com.bts.issue.type.domain.IssueTypeKey
 * @see com.bts.issue.type.domain.IssueTypeId
 */
@Repository
class IssueTypeRepository(
    private val dsl: DSLContext,
) {
    private val issueTypes = table(name("issue_types"))
    private val idField = field(name("id"), Long::class.java)
    private val keyField = field(name("key"), String::class.java)
    private val nameField = field(name("name"), String::class.java)
    private val descriptionField = field(name("description"), String::class.java)
    private val iconNameField = field(name("icon_name"), String::class.java)
    private val isStandardField = field(name("is_standard"), Boolean::class.java)
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
        dsl.select(idField, keyField, nameField, descriptionField, iconNameField, isStandardField, createdAtField, updatedAtField, deletedAtField)
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
        dsl.select(idField, keyField, nameField, descriptionField, iconNameField, isStandardField, createdAtField, updatedAtField, deletedAtField)
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
        dsl.select(idField, keyField, nameField, descriptionField, iconNameField, isStandardField, createdAtField, updatedAtField, deletedAtField)
            .from(issueTypes)
            .where(idField.eq(id.value))
            .and(deletedAtField.isNull)
            .fetchOne()
            ?.let(::toIssueType)

    private fun toIssueType(record: Record): IssueType =
        IssueType(
            id = IssueTypeId(record.get(idField)),
            key = IssueTypeKey(record.get(keyField)),
            name = record.get(nameField),
            description = record.get(descriptionField),
            iconName = record.get(iconNameField),
            isStandard = record.get(isStandardField),
            createdAt = record.get(createdAtField).toInstant(),
            updatedAt = record.get(updatedAtField).toInstant(),
            deletedAt = record.get(deletedAtField)?.toInstant(),
        )
}
