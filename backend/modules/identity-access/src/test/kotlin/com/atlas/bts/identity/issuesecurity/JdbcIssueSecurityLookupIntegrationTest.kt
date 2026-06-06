// JdbcIssueSecurityLookup 통합테스트 — cross-BC issues 테이블 read-only 조회 (FR-PM-06 PR-B Task 7)

package com.atlas.bts.identity.issuesecurity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * [JdbcIssueSecurityLookup] 통합테스트 (FR-PM-06 PR-B Task 7).
 *
 * ## 목적
 * identity-access BC 가 issue-tracking 소유의 `issues` 테이블을 read-only 로 조회해
 * 이슈의 보안 판정 컨텍스트(security_level_id / reporter_id / assignee_id)를 가져오는
 * cross-BC 포트 구현체를 실제 PostgreSQL 로 검증한다.
 *
 * ## 테스트 환경
 * - `@JdbcTest` — DataSource + JdbcTemplate 슬라이스만 로드([JdbcProjectDirectoryIntegrationTest] 동형).
 * - Testcontainers PostgreSQL 16.
 * - `issues` 테이블은 issue-tracking 소유라 identity-access Flyway 에 없으므로 setUp() 에서 직접 생성한다.
 *   수동 DDL 은 인라인 대신 공유 리소스(`issuesecurity/issues_lookup_schema.sql`)로 추출했다(C3).
 *   의존 컬럼(key/security_level_id/reporter_id/assignee_id/deleted_at)만 최소로 만든다(ADR D2).
 *
 * ## 스키마 drift 한계 (C3)
 * identity-access 는 issue-tracking 에 의존하지 않으므로(ADR D2) 실 issues Flyway 를 가져올 수 없다.
 * [lookup이 의존하는 컬럼이 모두 존재하고 타입이 일치한다]() 테스트가 수동 스키마(리소스 SQL)와
 * lookup SQL 의 정합은 잡지만, issue-tracking 의 실제 issues 스키마와의 drift 는 cross-module 의존
 * 부재로 자동 폐쇄가 불가하다([com.atlas.bts.identity.project.JdbcProjectDirectoryIntegrationTest]의
 * 수동 projects 스키마와 동형 한계). issues DDL 변경 시 리소스 SQL 을 수동으로 맞춰야 한다(파일 내 경고 주석).
 *
 * ## 검증 시나리오
 * | 케이스 | 조건 | 기대값 |
 * |---|---|---|
 * | 등급 지정 + 담당자 있음 | 활성 이슈, 모든 컬럼 채움 | (levelId, reporterId, assigneeId) 정확 |
 * | 등급 미지정(공개) | security_level_id IS NULL | securityLevelId == null |
 * | 담당자 미할당 | assignee_id IS NULL | assigneeId == null |
 * | 소프트삭제 | deleted_at 설정됨 | null(이슈 없음 취급) |
 * | 미존재 키 | 해당 key 행 없음 | null |
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcIssueSecurityLookup::class)
@Testcontainers
class JdbcIssueSecurityLookupIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            // issues 테이블만 직접 생성하므로 identity-access Flyway 는 끈다(스키마 충돌 회피).
            r.add("spring.flyway.enabled") { "false" }
        }
    }

    @Autowired
    private lateinit var lookup: JdbcIssueSecurityLookup

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // issues 테이블은 issue-tracking 소유 — identity-access Flyway 에 없으므로 직접 생성.
        // 수동 DDL 은 공유 리소스로 추출했다(C3 — drift 경고 주석을 한 곳에 모음).
        jdbc.jdbcTemplate.execute(loadSchemaSql())
        jdbc.update("DELETE FROM issues", emptyMap<String, Any>())
    }

    /**
     * lookup 이 의존하는 컬럼이 모두 존재하고 타입이 일치하는지 검증한다(C3 — 수동 스키마 drift 방어).
     *
     * 수동 `issues` 스키마(리소스 SQL)가 [JdbcIssueSecurityLookup] 가 읽는 컬럼과 어긋나면 즉시 fail 한다.
     * issue-tracking 의 실 issues 스키마와의 drift 는 cross-module 의존 부재로 자동 폐쇄 불가하다(KDoc 한계 참조).
     */
    @Test
    fun `lookup이 의존하는 컬럼이 모두 존재하고 타입이 일치한다`() {
        val expectedTypes =
            mapOf(
                "key" to "character varying",
                "reporter_id" to "uuid",
                "assignee_id" to "uuid",
                "security_level_id" to "uuid",
                "deleted_at" to "timestamp with time zone",
            )

        val actualTypes =
            jdbc.query(
                """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_name = 'issues'
                  AND column_name IN (:columns)
                """.trimIndent(),
                mapOf("columns" to expectedTypes.keys),
            ) { rs, _ -> rs.getString("column_name") to rs.getString("data_type") }
                .toMap()

        assertThat(actualTypes).containsAllEntriesOf(expectedTypes)
    }

    @Test
    fun `lookup은 등급-보고자-담당자가 모두 채워진 이슈를 정확히 반환한다`() {
        val levelId = UUID.randomUUID()
        val reporterId = UUID.randomUUID()
        val assigneeId = UUID.randomUUID()
        insertIssue("BTS-1", reporterId, assigneeId, levelId, deleted = false)

        val ctx = lookup.lookup("BTS-1")

        assertThat(ctx).isNotNull()
        assertThat(ctx!!.securityLevelId).isEqualTo(levelId)
        assertThat(ctx.reporterId).isEqualTo(reporterId)
        assertThat(ctx.assigneeId).isEqualTo(assigneeId)
    }

    @Test
    fun `등급 미지정 이슈는 securityLevelId가 null이다 (공개)`() {
        val reporterId = UUID.randomUUID()
        insertIssue("BTS-2", reporterId, assigneeId = UUID.randomUUID(), levelId = null, deleted = false)

        val ctx = lookup.lookup("BTS-2")

        assertThat(ctx).isNotNull()
        assertThat(ctx!!.securityLevelId).isNull()
    }

    @Test
    fun `담당자 미할당 이슈는 assigneeId가 null이다`() {
        val reporterId = UUID.randomUUID()
        insertIssue("BTS-3", reporterId, assigneeId = null, levelId = UUID.randomUUID(), deleted = false)

        val ctx = lookup.lookup("BTS-3")

        assertThat(ctx).isNotNull()
        assertThat(ctx!!.assigneeId).isNull()
    }

    @Test
    fun `소프트삭제된 이슈는 null을 반환한다`() {
        insertIssue("BTS-4", UUID.randomUUID(), assigneeId = null, levelId = null, deleted = true)

        assertThat(lookup.lookup("BTS-4")).isNull()
    }

    @Test
    fun `존재하지 않는 이슈 키는 null을 반환한다`() {
        assertThat(lookup.lookup("BTS-999")).isNull()
    }

    /**
     * 공유 리소스에서 수동 issues 스키마 DDL 을 읽는다(C3 — 인라인 대신 단일 출처).
     *
     * 리소스가 없으면 테스트 클래스패스 구성 문제이므로 명시 메시지로 실패시킨다.
     */
    private fun loadSchemaSql(): String =
        requireNotNull(javaClass.getResource("/issuesecurity/issues_lookup_schema.sql")) {
            "테스트 리소스 issuesecurity/issues_lookup_schema.sql 를 찾을 수 없습니다."
        }.readText()

    private fun insertIssue(
        key: String,
        reporterId: UUID,
        assigneeId: UUID?,
        levelId: UUID?,
        deleted: Boolean,
    ) {
        // deleted_at 도 파라미터 바인딩으로 처리(문자열 결합 금지). 소프트삭제는 과거 시각으로 둔다.
        jdbc.update(
            """
            INSERT INTO issues (key, reporter_id, assignee_id, security_level_id, deleted_at)
            VALUES (:key, :reporterId, :assigneeId, :levelId, :deletedAt)
            """.trimIndent(),
            mapOf(
                "key" to key,
                "reporterId" to reporterId,
                "assigneeId" to assigneeId,
                "levelId" to levelId,
                "deletedAt" to if (deleted) java.sql.Timestamp.from(java.time.Instant.now()) else null,
            ),
        )
    }
}
