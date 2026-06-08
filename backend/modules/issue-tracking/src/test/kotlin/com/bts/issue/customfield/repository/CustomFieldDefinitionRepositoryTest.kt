// CustomFieldDefinitionRepository 통합테스트 — CRUD + 옵션 동반 + 소프트 삭제 + 유니크 위반
package com.bts.issue.customfield.repository

import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.domain.CustomFieldOption
import com.bts.issue.customfield.domain.FieldType
import com.bts.issue.repository.IssueTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * [CustomFieldDefinitionRepository] Testcontainers 통합 테스트.
 *
 * Testcontainers PostgreSQL singleton + Flyway V015 마이그레이션 적용 상태에서 실행된다.
 * [IssueTestcontainersBase] 를 상속하여 컨테이너 공유 + testProjectId 픽스처를 재사용한다.
 *
 * 테스트 케이스 목록.
 * - T1: save 후 findByProjectAndKey 로 활성 정의 조회.
 * - T2: save — 선택형 정의(옵션 동반 저장/조회).
 * - T3: findActiveByProject — display_order 오름차순 정렬.
 * - T4: findActiveByProject — 소프트 삭제된 정의 제외.
 * - T5: softDelete 후 findByProjectAndKey null 반환.
 * - T6: 부분 유니크 인덱스 위반 시 [DataAccessException] 전파.
 * - T7: softDelete 후 동일 key 재생성 허용.
 * - T8: 다른 프로젝트 정의는 조회되지 않아야 한다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CustomFieldDefinitionRepositoryTest : IssueTestcontainersBase() {
    private lateinit var repo: CustomFieldDefinitionRepository

    @BeforeEach
    fun setUp() {
        repo = CustomFieldDefinitionRepository(dsl)
        dsl.execute("DELETE FROM custom_field_options")
        dsl.execute("DELETE FROM custom_field_definitions")
    }

    // ── T1. save + findByProjectAndKey ──────────────────────────────────────────

    @Test
    @Order(1)
    fun `save 후 findByProjectAndKey 로 활성 정의가 조회되어야 한다`() {
        val definition =
            CustomFieldDefinition.create(
                projectId = testProjectId,
                key = "salary_impact",
                name = "급여 영향도",
                fieldType = FieldType.SHORT_TEXT,
            )

        val saved = repo.save(definition)

        assertThat(saved.id).isNotNull()
        assertThat(saved.key).isEqualTo("salary_impact")
        assertThat(saved.name).isEqualTo("급여 영향도")
        assertThat(saved.fieldType).isEqualTo(FieldType.SHORT_TEXT)
        assertThat(saved.required).isFalse()
        assertThat(saved.displayOrder).isEqualTo(0)
        assertThat(saved.options).isEmpty()

        val found = repo.findByProjectAndKey(testProjectId, "salary_impact")
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(saved.id)
        assertThat(found.name).isEqualTo("급여 영향도")
    }

    // ── T2. 선택형 정의 — 옵션 동반 저장/조회 ──────────────────────────────────

    @Test
    @Order(2)
    fun `선택형 정의는 옵션과 함께 저장되고 함께 조회되어야 한다`() {
        val options =
            listOf(
                CustomFieldOption(value = "low", label = "낮음", displayOrder = 0),
                CustomFieldOption(value = "high", label = "높음", displayOrder = 1),
            )
        val definition =
            CustomFieldDefinition.create(
                projectId = testProjectId,
                key = "priority_level",
                name = "우선순위",
                fieldType = FieldType.SINGLE_SELECT,
                options = options,
            )

        val saved = repo.save(definition)

        assertThat(saved.options).hasSize(2)
        assertThat(saved.options.map { it.value }).containsExactlyInAnyOrder("low", "high")

        val found = repo.findByProjectAndKey(testProjectId, "priority_level")
        assertThat(found).isNotNull()
        assertThat(found!!.options).hasSize(2)
        assertThat(found.options.sortedBy { it.displayOrder }.map { it.value })
            .containsExactly("low", "high")
    }

    // ── T3. findActiveByProject — display_order 정렬 ───────────────────────────

    @Test
    @Order(3)
    fun `findActiveByProject 는 활성 정의를 display_order 오름차순으로 반환해야 한다`() {
        repo.save(
            CustomFieldDefinition.create(
                projectId = testProjectId,
                key = "field_c",
                name = "필드C",
                fieldType = FieldType.NUMBER,
                displayOrder = 30,
            ),
        )
        repo.save(
            CustomFieldDefinition.create(
                projectId = testProjectId,
                key = "field_a",
                name = "필드A",
                fieldType = FieldType.SHORT_TEXT,
                displayOrder = 10,
            ),
        )
        repo.save(
            CustomFieldDefinition.create(
                projectId = testProjectId,
                key = "field_b",
                name = "필드B",
                fieldType = FieldType.CHECKBOX,
                displayOrder = 20,
            ),
        )

        val result = repo.findActiveByProject(testProjectId)

        assertThat(result).hasSize(3)
        assertThat(result.map { it.key }).containsExactly("field_a", "field_b", "field_c")
    }

    // ── T4. findActiveByProject — 소프트 삭제된 정의 제외 ──────────────────────

    @Test
    @Order(4)
    fun `findActiveByProject 는 소프트 삭제된 정의를 포함하지 않아야 한다`() {
        val active =
            repo.save(
                CustomFieldDefinition.create(
                    projectId = testProjectId,
                    key = "active_field",
                    name = "활성 필드",
                    fieldType = FieldType.SHORT_TEXT,
                    displayOrder = 0,
                ),
            )
        val toDelete =
            repo.save(
                CustomFieldDefinition.create(
                    projectId = testProjectId,
                    key = "deleted_field",
                    name = "삭제될 필드",
                    fieldType = FieldType.SHORT_TEXT,
                    displayOrder = 1,
                ),
            )

        repo.softDelete(toDelete.id!!, testProjectId)

        val result = repo.findActiveByProject(testProjectId)
        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(active.id)
    }

    // ── T5. softDelete 후 findByProjectAndKey null ──────────────────────────────

    @Test
    @Order(5)
    fun `softDelete 후 findByProjectAndKey 는 null 을 반환해야 한다`() {
        val saved =
            repo.save(
                CustomFieldDefinition.create(
                    projectId = testProjectId,
                    key = "to_delete",
                    name = "삭제 대상",
                    fieldType = FieldType.SHORT_TEXT,
                ),
            )

        repo.softDelete(saved.id!!, testProjectId)

        val found = repo.findByProjectAndKey(testProjectId, "to_delete")
        assertThat(found).isNull()
    }

    // ── T6. 부분 유니크 위반 — DataAccessException 전파 ────────────────────────

    @Test
    @Order(6)
    fun `같은 프로젝트 같은 key 의 활성 정의를 재저장하면 DataAccessException 이 발생해야 한다`() {
        repo.save(
            CustomFieldDefinition.create(
                projectId = testProjectId,
                key = "dup_key",
                name = "중복 필드",
                fieldType = FieldType.SHORT_TEXT,
            ),
        )

        assertThatThrownBy {
            repo.save(
                CustomFieldDefinition.create(
                    projectId = testProjectId,
                    key = "dup_key",
                    name = "중복 필드2",
                    fieldType = FieldType.SHORT_TEXT,
                ),
            )
        }.isInstanceOf(DataAccessException::class.java)
    }

    // ── T7. softDelete 후 동일 key 재생성 허용 ──────────────────────────────────

    @Test
    @Order(7)
    fun `softDelete 후 동일 key 로 재생성이 허용되어야 한다`() {
        val first =
            repo.save(
                CustomFieldDefinition.create(
                    projectId = testProjectId,
                    key = "reborn_key",
                    name = "재생성 필드",
                    fieldType = FieldType.SHORT_TEXT,
                ),
            )
        repo.softDelete(first.id!!, testProjectId)

        val second =
            repo.save(
                CustomFieldDefinition.create(
                    projectId = testProjectId,
                    key = "reborn_key",
                    name = "재생성 필드2",
                    fieldType = FieldType.SHORT_TEXT,
                ),
            )

        assertThat(second.id).isNotNull()
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(second.key).isEqualTo("reborn_key")
    }

    // ── T8. 다른 프로젝트 정의는 조회 안 됨 ────────────────────────────────────

    @Test
    @Order(8)
    fun `findActiveByProject 는 다른 프로젝트의 정의를 포함하지 않아야 한다`() {
        val otherProjectId: UUID =
            dsl.resultQuery(
                "INSERT INTO projects (key, name) VALUES (?, ?)" +
                    " ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                "TPRJCF2",
                "Test Project CF2",
            ).fetchOne()?.get(0) as UUID

        repo.save(
            CustomFieldDefinition.create(
                projectId = testProjectId,
                key = "mine",
                name = "내 필드",
                fieldType = FieldType.SHORT_TEXT,
            ),
        )
        repo.save(
            CustomFieldDefinition.create(
                projectId = otherProjectId,
                key = "other",
                name = "남의 필드",
                fieldType = FieldType.SHORT_TEXT,
            ),
        )

        val result = repo.findActiveByProject(testProjectId)
        assertThat(result).hasSize(1)
        assertThat(result[0].key).isEqualTo("mine")
    }
}
