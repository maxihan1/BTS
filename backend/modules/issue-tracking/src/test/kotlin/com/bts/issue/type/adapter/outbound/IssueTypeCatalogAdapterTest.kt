// IssueTypeCatalogAdapter 통합 테스트 — 전역 이슈타입 카탈로그 listTypes() 시드 2건 반환 + 빈 DB 빈 리스트 검증

package com.bts.issue.type.adapter.outbound

import com.bts.issue.testsupport.SharedPostgres
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.issue.IssueTypeRef
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager

/**
 * [IssueTypeCatalogAdapter] 통합 테스트.
 *
 * FR-IM-02 PR-C — Import 값 매핑 위저드가 프로젝트 이슈타입 전체 목록을 얻기 위해 사용하는
 * `com.bts.shared.issue.IssueTypeCatalog` SPI 의 issue-tracking 구현체를 검증한다.
 *
 * 검증 범위.
 * - listTypes(): 이슈타입 2개 시드 후 IssueTypeRef(key, name) 2건 반환
 * - listTypes(): 빈 DB (issue_types 전체 삭제) → 빈 리스트 반환
 *
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL + Flyway + jOOQ DSL 직접 구성.
 * JVM singleton container — [com.bts.issue.type.repository.IssueTypeRepositoryCrudTest] 와 동일 패턴.
 *
 * ## 이슈 타입 = 전역 스코프
 *
 * issue_types 테이블에는 project 컬럼이 없다(전역). V003 seed 로 5 표준 타입
 * (epic/story/task/subtask/bug)이 이미 존재하므로, 각 테스트는 [cleanIssueTypes] 에서
 * issue_types 테이블 전체를 삭제해 독립적인 시나리오를 구성한다 — 이 테스트 클래스 안에서
 * issues/issue_templates 등 FK 종속 row 를 만들지 않으므로 전체 삭제가 안전하다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueTypeCatalogAdapterTest {
    companion object {
        /** SharedPostgres 템플릿을 복제한 클래스 전용 DB 접속 정보 */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()
    }

    lateinit var dsl: DSLContext
    lateinit var repository: IssueTypeRepository
    lateinit var adapter: IssueTypeCatalogAdapter

    private var bootstrapped = false

    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        repository = IssueTypeRepository(dsl)
        adapter = IssueTypeCatalogAdapter(repository)

        bootstrapped = true
    }

    @BeforeEach
    fun cleanIssueTypes() {
        // 각 테스트 독립성 — issue_types 전체 삭제 (전역 스코프, project 컬럼 없음).
        // 이 테스트 클래스는 issues/issue_templates 등 FK 종속 row 를 만들지 않으므로 안전하다.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_types")
            }
        }
    }

    // ── listTypes ────────────────────────────────────────────────────────────────

    @Test
    fun `listTypes - 이슈타입 2개 시드 후 IssueTypeRef 2건 반환`() {
        repository.insert(IssueType.create(key = IssueTypeKey("feature"), name = "Feature"))
        repository.insert(IssueType.create(key = IssueTypeKey("chore"), name = "Chore"))

        val result = adapter.listTypes()

        assertThat(result).hasSize(2)
        assertThat(result).containsExactlyInAnyOrder(
            IssueTypeRef(key = "feature", name = "Feature"),
            IssueTypeRef(key = "chore", name = "Chore"),
        )
    }

    @Test
    fun `listTypes - 빈 DB 에서는 빈 리스트를 반환한다`() {
        val result = adapter.listTypes()

        assertThat(result).isEmpty()
    }
}
