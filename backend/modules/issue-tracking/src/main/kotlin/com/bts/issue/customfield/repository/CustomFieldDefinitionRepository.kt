// 커스텀 필드 정의 + 선택지 Repository — custom_field_definitions/custom_field_options 테이블 jOOQ DSL 접근
package com.bts.issue.customfield.repository

import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.domain.CustomFieldOption
import com.bts.issue.customfield.domain.FieldType
import com.bts.issue.jooq.tables.records.CustomFieldDefinitionsRecord
import com.bts.issue.jooq.tables.references.CUSTOM_FIELD_DEFINITIONS
import com.bts.issue.jooq.tables.references.CUSTOM_FIELD_OPTIONS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 커스텀 필드 정의 + 선택지 Repository.
 *
 * jOOQ DSLContext 를 통해 `custom_field_definitions` 및 `custom_field_options` 테이블에 접근한다.
 * 모든 public 메서드는 `@Transactional` 을 명시한다 (DATA.md §6, DEVELOPMENT.md §절대규칙).
 *
 * 선택형([FieldType.isSelectType] = true) 정의는 저장 시 옵션을 동일 트랜잭션 내에서 함께 삽입한다.
 * 부분 유니크 인덱스 위반(`ux_custom_field_definitions_project_key_active`) 은 그대로 전파한다.
 * 중복키 예외([com.bts.issue.customfield.domain.DuplicateCustomFieldKeyException])로의 변환은
 * ApplicationService 책임이다.
 *
 * - [save] — 새 정의(+ 선택지)를 삽입하고 DB 생성 id 가 채워진 [CustomFieldDefinition] 반환.
 * - [findByProjectAndKey] — 프로젝트 + key + 활성(deleted_at IS NULL) 조건 단건 조회.
 * - [findActiveByProject] — 프로젝트 소속 활성 정의를 display_order 오름차순으로 조회(옵션 포함).
 * - [softDelete] — deleted_at 를 현재 UTC 시각으로 설정. 물리 삭제 금지 (DATA.md §3).
 */
@Repository
class CustomFieldDefinitionRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 커스텀 필드 정의를 삽입하고 DB 생성 id 가 채워진 [CustomFieldDefinition] 을 반환한다.
     *
     * 선택형 타입([FieldType.isSelectType] = true)인 경우 [CustomFieldDefinition.options] 를
     * `custom_field_options` 에 동일 트랜잭션 내에서 함께 삽입한다.
     *
     * 부분 유니크 인덱스 전제 —
     * `ux_custom_field_definitions_project_key_active (project_id, key) WHERE deleted_at IS NULL`.
     * 소프트 삭제된 동일 key 는 재삽입 허용된다.
     *
     * @param definition 저장할 [CustomFieldDefinition]. [CustomFieldDefinition.id] 는 null 이어야 한다.
     * @return DB 생성 id 와 timestamps 가 채워진 [CustomFieldDefinition].
     * @throws org.springframework.dao.DataAccessException 부분 유니크 인덱스 위반 시.
     */
    @Transactional
    fun save(definition: CustomFieldDefinition): CustomFieldDefinition {
        log.debug(
            "Inserting custom field definition key={} projectId={}",
            definition.key,
            definition.projectId,
        )
        val now = OffsetDateTime.now(ZoneOffset.UTC)

        val record =
            dsl.insertInto(CUSTOM_FIELD_DEFINITIONS)
                .set(CUSTOM_FIELD_DEFINITIONS.PROJECT_ID, definition.projectId)
                .set(CUSTOM_FIELD_DEFINITIONS.KEY, definition.key)
                .set(CUSTOM_FIELD_DEFINITIONS.NAME, definition.name)
                .set(CUSTOM_FIELD_DEFINITIONS.DESCRIPTION, null as String?)
                .set(CUSTOM_FIELD_DEFINITIONS.FIELD_TYPE, definition.fieldType.name)
                .set(CUSTOM_FIELD_DEFINITIONS.REQUIRED, definition.required)
                .set(CUSTOM_FIELD_DEFINITIONS.DISPLAY_ORDER, definition.displayOrder)
                .set(CUSTOM_FIELD_DEFINITIONS.CREATED_AT, now)
                .set(CUSTOM_FIELD_DEFINITIONS.UPDATED_AT, now)
                .returning()
                .fetchOne()
                ?: error("insert returning() returned null for key=${definition.key}")

        val fieldId = record.id ?: error("custom_field_definitions.id must not be null after insert")

        val savedOptions =
            if (definition.options.isNotEmpty()) {
                saveOptions(fieldId, definition.options)
            } else {
                emptyList()
            }

        return toDefinition(
            id = fieldId,
            projectId = record.projectId ?: error("project_id must not be null after insert"),
            key = record.key ?: error("key must not be null after insert"),
            name = record.name ?: error("name must not be null after insert"),
            fieldType = FieldType.valueOf(record.fieldType ?: error("field_type must not be null after insert")),
            required = record.required ?: false,
            displayOrder = record.displayOrder ?: 0,
            options = savedOptions,
        )
    }

    /**
     * 필드 정의 UUID + 프로젝트 UUID 조건으로 활성(`deleted_at IS NULL`) 정의를 조회한다.
     * 옵션도 함께 로드된다.
     *
     * @param id 조회할 필드 정의 UUID.
     * @param projectId 소속 프로젝트 UUID.
     * @return 활성 [CustomFieldDefinition], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findById(
        id: UUID,
        projectId: UUID,
    ): CustomFieldDefinition? =
        dsl.selectFrom(CUSTOM_FIELD_DEFINITIONS)
            .where(CUSTOM_FIELD_DEFINITIONS.ID.eq(id))
            .and(CUSTOM_FIELD_DEFINITIONS.PROJECT_ID.eq(projectId))
            .and(CUSTOM_FIELD_DEFINITIONS.DELETED_AT.isNull)
            .fetchOne()
            ?.let { record -> recordToDefinition(record) }

    /**
     * 기존 커스텀 필드 정의를 갱신한다.
     *
     * name / required / display_order 만 변경 가능하다.
     * fieldType 과 key 는 생성 후 불변 — [ImmutableFieldTypeChangeException] 검증은 ApplicationService 책임.
     *
     * @param definition 갱신할 [CustomFieldDefinition]. [CustomFieldDefinition.id] 가 non-null 이어야 한다.
     * @return 갱신된 [CustomFieldDefinition].
     */
    @Transactional
    fun update(definition: CustomFieldDefinition): CustomFieldDefinition {
        val id = definition.id ?: error("update requires non-null id")
        log.debug("Updating custom field definition id={} projectId={}", id, definition.projectId)

        dsl.update(CUSTOM_FIELD_DEFINITIONS)
            .set(CUSTOM_FIELD_DEFINITIONS.NAME, definition.name)
            .set(CUSTOM_FIELD_DEFINITIONS.REQUIRED, definition.required)
            .set(CUSTOM_FIELD_DEFINITIONS.DISPLAY_ORDER, definition.displayOrder)
            .set(CUSTOM_FIELD_DEFINITIONS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(CUSTOM_FIELD_DEFINITIONS.ID.eq(id))
            .and(CUSTOM_FIELD_DEFINITIONS.PROJECT_ID.eq(definition.projectId))
            .and(CUSTOM_FIELD_DEFINITIONS.DELETED_AT.isNull)
            .execute()

        return definition
    }

    /**
     * 프로젝트 + key + 활성(`deleted_at IS NULL`) 조건으로 단건 정의를 조회한다.
     * 옵션도 함께 로드된다.
     *
     * @param projectId 소속 프로젝트 UUID.
     * @param key 조회할 필드 key.
     * @return 활성 [CustomFieldDefinition], 없으면 null.
     */
    @Transactional(readOnly = true)
    fun findByProjectAndKey(
        projectId: UUID,
        key: String,
    ): CustomFieldDefinition? =
        dsl.selectFrom(CUSTOM_FIELD_DEFINITIONS)
            .where(CUSTOM_FIELD_DEFINITIONS.PROJECT_ID.eq(projectId))
            .and(CUSTOM_FIELD_DEFINITIONS.KEY.eq(key))
            .and(CUSTOM_FIELD_DEFINITIONS.DELETED_AT.isNull)
            .fetchOne()
            ?.let { record -> recordToDefinition(record) }

    /**
     * 프로젝트 소속 활성 정의를 [CustomFieldDefinition.displayOrder] 오름차순으로 반환한다.
     *
     * `deleted_at IS NULL` 필터 자동 적용. 각 정의에 옵션이 함께 로드된다.
     * 다중 LEFT JOIN + count 대신 정의 조회 후 옵션은 IN 절 스칼라 로드로 처리하여
     * cartesian product 를 방지한다 (메모리: jooq-cartesian-product-leftjoin-count).
     *
     * @param projectId 조회할 프로젝트 UUID.
     * @return 활성 [CustomFieldDefinition] 리스트. 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun findActiveByProject(projectId: UUID): List<CustomFieldDefinition> {
        val records =
            dsl.selectFrom(CUSTOM_FIELD_DEFINITIONS)
                .where(CUSTOM_FIELD_DEFINITIONS.PROJECT_ID.eq(projectId))
                .and(CUSTOM_FIELD_DEFINITIONS.DELETED_AT.isNull)
                .orderBy(CUSTOM_FIELD_DEFINITIONS.DISPLAY_ORDER.asc())
                .fetch()

        if (records.isEmpty()) return emptyList()

        val fieldIds = records.mapNotNull { it.id }
        val optionsByFieldId = loadOptionsByFieldIds(fieldIds)

        return records.mapNotNull { record ->
            val fieldId = record.id ?: return@mapNotNull null
            val pid = record.projectId ?: error("custom_field_definitions.project_id must not be null")
            val key = record.key ?: error("custom_field_definitions.key must not be null")
            val name = record.name ?: error("custom_field_definitions.name must not be null")
            val rawType = record.fieldType ?: error("custom_field_definitions.field_type must not be null")
            toDefinition(
                id = fieldId,
                projectId = pid,
                key = key,
                name = name,
                fieldType = FieldType.valueOf(rawType),
                required = record.required ?: false,
                displayOrder = record.displayOrder ?: 0,
                options = optionsByFieldId[fieldId] ?: emptyList(),
            )
        }
    }

    /**
     * 커스텀 필드 정의를 소프트 삭제한다.
     *
     * `deleted_at` 를 현재 UTC 시각으로 설정한다.
     * 이미 삭제된 정의는 영향 행 0 반환 (멱등 처리).
     * 물리 삭제(DELETE) 금지 — DATA.md §3.
     *
     * @param id 삭제할 필드 정의 UUID.
     * @param projectId 소속 프로젝트 UUID. 소속이 다르면 아무 행도 변경되지 않는다.
     */
    @Transactional
    fun softDelete(
        id: UUID,
        projectId: UUID,
    ) {
        log.debug("Soft-deleting custom field definition id={} projectId={}", id, projectId)
        dsl.update(CUSTOM_FIELD_DEFINITIONS)
            .set(CUSTOM_FIELD_DEFINITIONS.DELETED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .where(CUSTOM_FIELD_DEFINITIONS.ID.eq(id))
            .and(CUSTOM_FIELD_DEFINITIONS.PROJECT_ID.eq(projectId))
            .and(CUSTOM_FIELD_DEFINITIONS.DELETED_AT.isNull)
            .execute()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 선택지 목록을 `custom_field_options` 에 삽입하고 저장된 [CustomFieldOption] 리스트를 반환한다.
     *
     * @param fieldId 소속 필드 정의 UUID.
     * @param options 저장할 선택지 목록.
     * @return 저장 완료된 [CustomFieldOption] 리스트(id 포함하지 않음 — 도메인 값 객체 동형).
     */
    private fun saveOptions(
        fieldId: UUID,
        options: List<CustomFieldOption>,
    ): List<CustomFieldOption> {
        options.forEach { option ->
            dsl.insertInto(CUSTOM_FIELD_OPTIONS)
                .set(CUSTOM_FIELD_OPTIONS.FIELD_ID, fieldId)
                .set(CUSTOM_FIELD_OPTIONS.VALUE, option.value)
                .set(CUSTOM_FIELD_OPTIONS.LABEL, option.label)
                .set(CUSTOM_FIELD_OPTIONS.DISPLAY_ORDER, option.displayOrder)
                .execute()
        }
        return options
    }

    /**
     * 단일 필드 정의에 속한 선택지를 display_order 오름차순으로 로드한다.
     *
     * @param fieldId 조회 대상 필드 정의 UUID.
     * @return [CustomFieldOption] 리스트. 없으면 빈 리스트.
     */
    private fun loadOptions(fieldId: UUID): List<CustomFieldOption> =
        dsl.selectFrom(CUSTOM_FIELD_OPTIONS)
            .where(CUSTOM_FIELD_OPTIONS.FIELD_ID.eq(fieldId))
            .orderBy(CUSTOM_FIELD_OPTIONS.DISPLAY_ORDER.asc())
            .fetch()
            .map { r ->
                CustomFieldOption(
                    value = r.value ?: error("custom_field_options.value must not be null"),
                    label = r.label ?: error("custom_field_options.label must not be null"),
                    displayOrder = r.displayOrder ?: 0,
                )
            }

    /**
     * 여러 필드 정의에 속한 선택지를 IN 절로 일괄 로드하고 fieldId 별로 그룹핑하여 반환한다.
     *
     * 다중 LEFT JOIN 대신 IN 절 일괄 조회로 cartesian product 를 방지한다.
     *
     * @param fieldIds 조회할 필드 정의 UUID 목록.
     * @return fieldId → [CustomFieldOption] 리스트 맵.
     */
    private fun loadOptionsByFieldIds(fieldIds: List<UUID>): Map<UUID, List<CustomFieldOption>> {
        if (fieldIds.isEmpty()) return emptyMap()

        return dsl.selectFrom(CUSTOM_FIELD_OPTIONS)
            .where(CUSTOM_FIELD_OPTIONS.FIELD_ID.`in`(fieldIds))
            .orderBy(CUSTOM_FIELD_OPTIONS.DISPLAY_ORDER.asc())
            .fetch()
            .groupBy(
                { r -> r.fieldId ?: error("custom_field_options.field_id must not be null") },
                { r ->
                    CustomFieldOption(
                        value = r.value ?: error("custom_field_options.value must not be null"),
                        label = r.label ?: error("custom_field_options.label must not be null"),
                        displayOrder = r.displayOrder ?: 0,
                    )
                },
            )
    }

    /**
     * DB 컬럼 값을 [CustomFieldDefinition] 도메인 객체로 조립한다.
     */
    @Suppress("LongParameterList") // DB 컬럼 매핑 팩토리 — 컬럼 수에 대응하여 분리 불가
    private fun toDefinition(
        id: UUID,
        projectId: UUID,
        key: String,
        name: String,
        fieldType: FieldType,
        required: Boolean,
        displayOrder: Int,
        options: List<CustomFieldOption>,
    ): CustomFieldDefinition =
        CustomFieldDefinition(
            id = id,
            projectId = projectId,
            key = key,
            name = name,
            fieldType = fieldType,
            required = required,
            displayOrder = displayOrder,
            options = options,
        )

    /**
     * jOOQ [com.bts.issue.jooq.tables.records.CustomFieldDefinitionsRecord] 를
     * [CustomFieldDefinition] 도메인 객체로 변환한다.
     *
     * NOT NULL 컬럼(id, project_id, key, name, field_type)이 null 이면 DB 정합 이상이므로
     * `error()` 로 빠른 실패를 유도한다.
     * 옵션은 [loadOptions] 를 통해 함께 로드한다.
     *
     * @param record 변환할 jOOQ 레코드.
     * @return 변환된 [CustomFieldDefinition].
     */
    private fun recordToDefinition(record: CustomFieldDefinitionsRecord): CustomFieldDefinition {
        val fieldId = record.id ?: error("custom_field_definitions.id must not be null")
        val options = loadOptions(fieldId)
        return toDefinition(
            id = fieldId,
            projectId = record.projectId ?: error("project_id must not be null"),
            key = record.key ?: error("key must not be null"),
            name = record.name ?: error("name must not be null"),
            fieldType = FieldType.valueOf(record.fieldType ?: error("field_type must not be null")),
            required = record.required ?: false,
            displayOrder = record.displayOrder ?: 0,
            options = options,
        )
    }
}
