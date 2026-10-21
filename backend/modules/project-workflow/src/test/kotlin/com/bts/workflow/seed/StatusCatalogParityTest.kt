// workflow_states 와 전역 상태 카탈로그가 갈라지면 red 를 내는 대조 판별식 (스펙 C4)

package com.bts.workflow.seed

import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.testsupport.SharedPostgres
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.sql.DriverManager

/**
 * 시드 직후 `workflow_states` 와 `workflow_statuses ⨝ statuses` 가 **완전히 같은지** 본다.
 *
 * ### 왜 있나
 * Maxi 결정 D1(2026-08-19)로 읽기 경로 전환을 PR 3 으로 미뤘다. 그래서 이 PR 동안 새 두 테이블은
 * **write-only** 다 — 아무도 안 읽는 데이터는 틀려도 조용하다(`two-lists-never-check-each-other`).
 * 이 판별식이 그 사각을 막는 유일한 장치다.
 *
 * ### 뮤테이션으로 확인한 것 (2026-08-19)
 * GREEN 을 커밋한 뒤 `YamlSeedService.insertStatusForWorkflow` 의 `display_order` 를 `+ 1` 로
 * 어긋나게 만들고 이 테스트가 red 가 되는 것을 1회 확인한 다음 원복했다. 판정이 실제로 값을 보고
 * 있음을 그렇게 증명했다 — 통과만 보고 믿지 않는다.
 *
 * 워크플로우 키·건수를 하드코딩하지 않는다. DB 에 있는 것을 **전수 열거**해 비교한다.
 */
class StatusCatalogParityTest {
    companion object {
        val postgres = SharedPostgres.freshDatabase()

        @BeforeAll
        @JvmStatic
        fun setup() {
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            YamlSeedService(
                dsl,
                DefaultResourceLoader(),
                mockk(relaxed = true),
                mockk(relaxed = true),
                SchemeIssueTypeMappingRepository(dsl),
            ).seedAll()
        }
    }

    /** (워크플로우 키, 상태 키) → (이름, 카테고리, 표시순서). */
    private fun readRows(sql: String): Map<Pair<String, String>, Triple<String, String, Int>> {
        val rows = mutableMapOf<Pair<String, String>, Triple<String, String, Int>>()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val rs = conn.createStatement().executeQuery(sql)
            while (rs.next()) {
                rows[rs.getString(1) to rs.getString(2)] =
                    Triple(rs.getString(3), rs.getString(4), rs.getInt(5))
            }
        }
        return rows
    }

    @Test
    fun `workflow_states 와 전역 상태 카탈로그가 워크플로우 전수에서 일치한다`() {
        val legacy =
            readRows(
                "SELECT w.key, ws.key, ws.name, ws.category, ws.display_order" +
                    "  FROM workflow_states ws" +
                    "  JOIN workflows w ON w.id = ws.workflow_id",
            )
        val catalog =
            readRows(
                "SELECT w.key, s.key, s.name, s.category, wst.display_order" +
                    "  FROM workflow_statuses wst" +
                    "  JOIN workflows w ON w.id = wst.workflow_id" +
                    "  JOIN statuses s ON s.id = wst.status_id",
            )

        assertThat(legacy).isNotEmpty()
        assertThat(catalog.keys)
            .describedAs(
                "카탈로그에만 있는 (워크플로우, 상태) %s · workflow_states 에만 있는 %s",
                catalog.keys - legacy.keys,
                legacy.keys - catalog.keys,
            )
            .isEqualTo(legacy.keys)
        assertThat(catalog)
            .describedAs("이름·카테고리·표시순서가 어긋난 항목이 있다")
            .isEqualTo(legacy)
    }
}
