// IssueTemplateRepository Testcontainers 통합 테스트 — CRUD + 소프트 삭제 + 활성 필터 + 유니크 위반
package com.bts.issue.template.repository

import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.template.domain.IssueTemplate
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
 * [IssueTemplateRepository] Testcontainers 통합 테스트.
 *
 * Testcontainers PostgreSQL singleton + Flyway V019 마이그레이션 적용 상태에서 실행된다.
 * [IssueTestcontainersBase] 를 상속하여 컨테이너 공유 + testProjectId 픽스처를 재사용한다.
 *
 * 테스트 케이스 목록.
 * - T1: insert 후 findById 로 활성 템플릿 조회.
 * - T2: findByProject — 활성 템플릿만 반환.
 * - T3: update — name/content 갱신 후 findById 로 확인.
 * - T4: softDelete 후 findById null 반환.
 * - T5: findByProject — 소프트 삭제된 템플릿 제외.
 * - T6: 부분 유니크 위반 시 [DataAccessException] 전파.
 * - T7: softDelete 후 동일 (project, type) 재생성 허용.
 * - T8: existsActive — 활성 존재 true, 삭제 후 false.
 * - T9: findActiveContentByProjectAndType — 활성 content 반환, 없으면 null.
 * - T10: findByProject — 다른 프로젝트 템플릿 포함하지 않음.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueTemplateRepositoryIntegrationTest : IssueTestcontainersBase() {
    private lateinit var repo: IssueTemplateRepository

    /** Flyway 시드로 생성된 'task' 이슈 타입 id. bootstrap 이후 로드됨. */
    private var testIssueTypeId: Long = 0L

    @BeforeEach
    fun setUp() {
        repo = IssueTemplateRepository(dsl)
        dsl.execute("DELETE FROM issue_templates")
        testIssueTypeId =
            dsl
                .resultQuery("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .fetchOne()
                ?.get(0, Long::class.java)
                ?: error("issue_types 'task' 행 없음 — Flyway 시드 누락")
    }

    // ── T1. insert + findById ────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `insert 후 findById 로 활성 템플릿이 조회되어야 한다`() {
        val template =
            IssueTemplate.create(
                projectId = testProjectId,
                issueTypeId = testIssueTypeId,
                name = "버그 리포트 템플릿",
                content = "## 재현 절차\n\n## 기대 결과",
            )

        val inserted = repo.insert(template)

        assertThat(inserted.id).isEqualTo(template.id)
        assertThat(inserted.projectId).isEqualTo(testProjectId)
        assertThat(inserted.issueTypeId).isEqualTo(testIssueTypeId)
        assertThat(inserted.name).isEqualTo("버그 리포트 템플릿")
        assertThat(inserted.content).isEqualTo("## 재현 절차\n\n## 기대 결과")
        assertThat(inserted.deletedAt).isNull()

        val found = repo.findById(template.id)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(template.id)
        assertThat(found.name).isEqualTo("버그 리포트 템플릿")
    }

    // ── T2. findByProject — 활성만 반환 ─────────────────────────────────────

    @Test
    @Order(2)
    fun `findByProject 는 활성 템플릿만 반환해야 한다`() {
        repo.insert(
            IssueTemplate.create(
                projectId = testProjectId,
                issueTypeId = testIssueTypeId,
                name = "작업 템플릿",
                content = "## 목적",
            ),
        )

        val result = repo.findByProject(testProjectId)

        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("작업 템플릿")
    }

    // ── T3. update — name/content 갱신 ──────────────────────────────────────

    @Test
    @Order(3)
    fun `update 후 findById 는 갱신된 name 과 content 를 반환해야 한다`() {
        val inserted =
            repo.insert(
                IssueTemplate.create(
                    projectId = testProjectId,
                    issueTypeId = testIssueTypeId,
                    name = "초기 이름",
                    content = "초기 내용",
                ),
            )

        repo.update(
            id = inserted.id,
            name = "변경된 이름",
            content = "변경된 내용",
        )

        val found = repo.findById(inserted.id)
        assertThat(found).isNotNull()
        assertThat(found!!.name).isEqualTo("변경된 이름")
        assertThat(found.content).isEqualTo("변경된 내용")
    }

    // ── T4. softDelete 후 findById null ─────────────────────────────────────

    @Test
    @Order(4)
    fun `softDelete 후 findById 는 null 을 반환해야 한다`() {
        val inserted =
            repo.insert(
                IssueTemplate.create(
                    projectId = testProjectId,
                    issueTypeId = testIssueTypeId,
                    name = "삭제 대상",
                    content = "내용",
                ),
            )

        repo.softDelete(inserted.id)

        val found = repo.findById(inserted.id)
        assertThat(found).isNull()
    }

    // ── T5. findByProject — 소프트 삭제된 템플릿 제외 ───────────────────────

    @Test
    @Order(5)
    fun `findByProject 는 소프트 삭제된 템플릿을 포함하지 않아야 한다`() {
        val active =
            repo.insert(
                IssueTemplate.create(
                    projectId = testProjectId,
                    issueTypeId = testIssueTypeId,
                    name = "활성 템플릿",
                    content = "활성 내용",
                ),
            )
        // 동일 (project, type) 중복을 피하기 위해 먼저 소프트 삭제
        repo.softDelete(active.id)

        // 삭제 후 동일 조합으로 새로 삽입
        val toDelete =
            repo.insert(
                IssueTemplate.create(
                    projectId = testProjectId,
                    issueTypeId = testIssueTypeId,
                    name = "삭제될 템플릿",
                    content = "삭제될 내용",
                ),
            )
        repo.softDelete(toDelete.id)

        val result = repo.findByProject(testProjectId)
        assertThat(result).isEmpty()
    }

    // ── T6. 부분 유니크 위반 — DataAccessException 전파 ────────────────────

    @Test
    @Order(6)
    fun `같은 프로젝트 같은 issueTypeId 의 활성 템플릿을 재삽입하면 DataAccessException 이 발생해야 한다`() {
        repo.insert(
            IssueTemplate.create(
                projectId = testProjectId,
                issueTypeId = testIssueTypeId,
                name = "첫 번째 템플릿",
                content = "내용1",
            ),
        )

        assertThatThrownBy {
            repo.insert(
                IssueTemplate.create(
                    projectId = testProjectId,
                    issueTypeId = testIssueTypeId,
                    name = "두 번째 템플릿",
                    content = "내용2",
                ),
            )
        }.isInstanceOf(DataAccessException::class.java)
    }

    // ── T7. softDelete 후 동일 (project, type) 재생성 허용 ──────────────────

    @Test
    @Order(7)
    fun `softDelete 후 동일 project 와 issueTypeId 로 재생성이 허용되어야 한다`() {
        val first =
            repo.insert(
                IssueTemplate.create(
                    projectId = testProjectId,
                    issueTypeId = testIssueTypeId,
                    name = "초기 템플릿",
                    content = "초기 내용",
                ),
            )
        repo.softDelete(first.id)

        val second =
            repo.insert(
                IssueTemplate.create(
                    projectId = testProjectId,
                    issueTypeId = testIssueTypeId,
                    name = "재생성 템플릿",
                    content = "재생성 내용",
                ),
            )

        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(second.name).isEqualTo("재생성 템플릿")
    }

    // ── T8. existsActive ────────────────────────────────────────────────────

    @Test
    @Order(8)
    fun `existsActive 는 활성 템플릿이 있으면 true, 소프트 삭제 후에는 false 를 반환해야 한다`() {
        assertThat(repo.existsActive(testProjectId, testIssueTypeId)).isFalse()

        val inserted =
            repo.insert(
                IssueTemplate.create(
                    projectId = testProjectId,
                    issueTypeId = testIssueTypeId,
                    name = "존재 확인 템플릿",
                    content = "내용",
                ),
            )

        assertThat(repo.existsActive(testProjectId, testIssueTypeId)).isTrue()

        repo.softDelete(inserted.id)

        assertThat(repo.existsActive(testProjectId, testIssueTypeId)).isFalse()
    }

    // ── T9. findActiveContentByProjectAndType ────────────────────────────────

    @Test
    @Order(9)
    fun `findActiveContentByProjectAndType 는 활성 content 를 반환하고 없으면 null 을 반환해야 한다`() {
        assertThat(repo.findActiveContentByProjectAndType(testProjectId, testIssueTypeId)).isNull()

        val inserted =
            repo.insert(
                IssueTemplate.create(
                    projectId = testProjectId,
                    issueTypeId = testIssueTypeId,
                    name = "안전망 템플릿",
                    content = "## 안전망 내용",
                ),
            )

        assertThat(repo.findActiveContentByProjectAndType(testProjectId, testIssueTypeId))
            .isEqualTo("## 안전망 내용")

        repo.softDelete(inserted.id)

        assertThat(repo.findActiveContentByProjectAndType(testProjectId, testIssueTypeId)).isNull()
    }

    // ── T10. findByProject — 다른 프로젝트 미포함 ───────────────────────────

    @Test
    @Order(10)
    fun `findByProject 는 다른 프로젝트의 템플릿을 포함하지 않아야 한다`() {
        val otherProjectId: UUID =
            dsl
                .resultQuery(
                    "INSERT INTO projects (key, name) VALUES (?, ?)" +
                        " ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                    "TPRJTM2",
                    "Test Project TM2",
                ).fetchOne()
                ?.get(0) as UUID

        repo.insert(
            IssueTemplate.create(
                projectId = testProjectId,
                issueTypeId = testIssueTypeId,
                name = "내 프로젝트 템플릿",
                content = "내 내용",
            ),
        )
        repo.insert(
            IssueTemplate.create(
                projectId = otherProjectId,
                issueTypeId = testIssueTypeId,
                name = "다른 프로젝트 템플릿",
                content = "다른 내용",
            ),
        )

        val result = repo.findByProject(testProjectId)
        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("내 프로젝트 템플릿")
    }
}
