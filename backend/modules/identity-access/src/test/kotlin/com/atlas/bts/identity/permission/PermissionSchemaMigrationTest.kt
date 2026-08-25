// V008 마이그레이션 검증 — 기본 권한 스킴 시드 + 매트릭스 5행 + project_permission_scheme 테이블 존재 확인

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.support.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Flyway V001~V008 마이그레이션 자동 적용 후 권한 스킴 테이블 + 기본 시드를 검증한다.
 *
 * ## 검증 항목
 * - role_permissions JOIN permission_schemes WHERE is_default = TRUE 의 행 수 = 5 (ADMIN 3 + MEMBER 2)
 * - permission_schemes 중 is_default = TRUE 인 행 = 1 (부분 유니크 인덱스 보장)
 * - project_permission_scheme 테이블 존재 (시드 없음, 모두 기본 스킴 fallback)
 *
 * ProjectMembershipRepositoryIntegrationTest 의 @JdbcTest + @DynamicPropertySource 패턴을 복제.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PermissionSchemaMigrationTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            r.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            r.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
        }
    }

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Test
    fun `기본 스킴과 매트릭스가 시드된다`() {
        // role_permissions × permission_schemes(is_default=TRUE) JOIN 결과 14행 기대
        // PROJECT_ADMIN(10행): CREATE_ISSUE, EDIT_ISSUE, DELETE_ISSUE,
        //   MANAGE_COMPONENTS, MANAGE_VERSIONS, MANAGE_WORKFLOW, BROWSE_PROJECT, VIEW_ISSUE,
        //   SET_ISSUE_SECURITY, MANAGE_CUSTOM_FIELDS
        // MEMBER(4행): CREATE_ISSUE, EDIT_ISSUE, BROWSE_PROJECT, VIEW_ISSUE
        // (V009 — FR-PM-03이 PROJECT_ADMIN에 MANAGE_COMPONENTS/MANAGE_VERSIONS 2행 추가)
        // (V013 — FR-PM-04가 PROJECT_ADMIN에 MANAGE_WORKFLOW 1행 추가)
        // (V014 — FR-PM-05가 양 역할에 BROWSE_PROJECT/VIEW_ISSUE 4행 추가)
        // (V016 — FR-PM-06이 PROJECT_ADMIN에 SET_ISSUE_SECURITY 1행 추가)
        // (V017 — FR-IS-10이 PROJECT_ADMIN에 MANAGE_CUSTOM_FIELDS 1행 추가)
        // (V018 — FR-PM-07이 PROJECT_ADMIN에 MANAGE_FIELD_PERMISSIONS 1행 추가)
        // (V024 — FR-TM-01이 PROJECT_ADMIN에 MANAGE_TEMPLATES 1행 추가)
        // (V035 — FR-AT-01이 PROJECT_ADMIN에 MANAGE_AUTOMATION 1행 추가)
        val count =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(count).isEqualTo(17)
    }

    @Test
    fun `기본 스킴 PROJECT_ADMIN이 MANAGE_FIELD_PERMISSIONS를 보유한다`() {
        // V018 — FR-PM-07: 필드 수준 권한 규칙 관리(MANAGE_FIELD_PERMISSIONS) 권한의 정본 코드.
        // 기본 스킴(00000000-…-001) PROJECT_ADMIN 역할에만 1행 시드 (MEMBER 제외, 행정 성격).
        val adminCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'PROJECT_ADMIN'
              AND rp.permission_code = 'MANAGE_FIELD_PERMISSIONS'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(adminCount).isEqualTo(1)

        // MEMBER 역할에는 MANAGE_FIELD_PERMISSIONS 가 없어야 한다 (보수적 시드)
        val memberCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'MEMBER'
              AND rp.permission_code = 'MANAGE_FIELD_PERMISSIONS'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(memberCount).isEqualTo(0)
    }

    @Test
    fun `기본 스킴 PROJECT_ADMIN이 MANAGE_CUSTOM_FIELDS를 보유한다`() {
        // V017 — FR-IS-10: 커스텀 필드 정의 관리(MANAGE_CUSTOM_FIELDS) 권한의 정본 코드.
        // 기본 스킴(00000000-…-001) PROJECT_ADMIN 역할에만 1행 시드 (MEMBER 제외, 행정 성격).
        val adminCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'PROJECT_ADMIN'
              AND rp.permission_code = 'MANAGE_CUSTOM_FIELDS'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(adminCount).isEqualTo(1)

        // MEMBER 역할에는 MANAGE_CUSTOM_FIELDS 가 없어야 한다 (보수적 시드)
        val memberCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'MEMBER'
              AND rp.permission_code = 'MANAGE_CUSTOM_FIELDS'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(memberCount).isEqualTo(0)
    }

    @Test
    fun `기본 스킴 PROJECT_ADMIN이 MANAGE_TEMPLATES를 보유한다`() {
        // V024 — FR-TM-01: 이슈 템플릿 정의 관리(MANAGE_TEMPLATES) 권한의 정본 코드.
        // 기본 스킴(00000000-…-001) PROJECT_ADMIN 역할에만 1행 시드 (MEMBER 제외, 행정 성격).
        val adminCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'PROJECT_ADMIN'
              AND rp.permission_code = 'MANAGE_TEMPLATES'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(adminCount).isEqualTo(1)

        // MEMBER 역할에는 MANAGE_TEMPLATES 가 없어야 한다 (보수적 시드)
        val memberCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'MEMBER'
              AND rp.permission_code = 'MANAGE_TEMPLATES'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(memberCount).isEqualTo(0)
    }

    @Test
    fun `기본 스킴 PROJECT_ADMIN이 SET_ISSUE_SECURITY를 보유한다`() {
        // V016 — FR-PM-06: 이슈 보안 등급 지정(SET_ISSUE_SECURITY) 권한의 정본 코드.
        // 기본 스킴(00000000-…-001) PROJECT_ADMIN 역할에만 1행 시드 (MEMBER 제외).
        val adminCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'PROJECT_ADMIN'
              AND rp.permission_code = 'SET_ISSUE_SECURITY'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(adminCount).isEqualTo(1)

        // MEMBER 역할에는 SET_ISSUE_SECURITY 가 없어야 한다 (보수적 시드)
        val memberCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'MEMBER'
              AND rp.permission_code = 'SET_ISSUE_SECURITY'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(memberCount).isEqualTo(0)
    }

    @Test
    fun `기본 스킴 PROJECT_ADMIN이 MANAGE_WORKFLOW를 보유한다`() {
        // V013 — FR-PM-04: 워크플로우 스킴 배정(ASSIGN_SCHEME/Project) 권한 판정의 정본 코드.
        // 기본 스킴(00000000-…-001) PROJECT_ADMIN 역할에 MANAGE_WORKFLOW(SDD 12.3) 1행이 시드되어야 한다.
        val count =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'PROJECT_ADMIN'
              AND rp.permission_code = 'MANAGE_WORKFLOW'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `기본 스킴 PROJECT_ADMIN이 MANAGE_AUTOMATION를 보유한다`() {
        // V035 — FR-AT-01: 자동화 규칙 관리(MANAGE_AUTOMATION) 권한의 정본 코드 (SDD 12.3).
        // 기본 스킴(00000000-…-001) PROJECT_ADMIN 역할에만 1행 시드 (MEMBER 제외, 프로젝트 행정 성격).
        val adminCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'PROJECT_ADMIN'
              AND rp.permission_code = 'MANAGE_AUTOMATION'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(adminCount).isEqualTo(1)

        // MEMBER 역할에는 MANAGE_AUTOMATION 이 없어야 한다 (보수적 시드)
        val memberCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role = 'MEMBER'
              AND rp.permission_code = 'MANAGE_AUTOMATION'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(memberCount).isEqualTo(0)
    }

    @Test
    fun `기본 스킴이 BROWSE_PROJECT VIEW_ISSUE를 양 역할에 보유한다`() {
        // V014 — FR-PM-05: 프로젝트 탐색(BROWSE_PROJECT) · 이슈 열람(VIEW_ISSUE) 권한의 정본 코드.
        // 기본 스킴(00000000-…-001) PROJECT_ADMIN · MEMBER 양 역할에 각각 2행씩 = 4행이 시드되어야 한다.
        val count =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM role_permissions rp
            JOIN permission_schemes ps ON rp.scheme_id = ps.id
            WHERE ps.is_default = TRUE
              AND rp.role IN ('PROJECT_ADMIN', 'MEMBER')
              AND rp.permission_code IN ('BROWSE_PROJECT', 'VIEW_ISSUE')
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(count).isEqualTo(4)
    }

    @Test
    fun `기본 스킴은 단 하나만 존재한다`() {
        // uq_permission_schemes_default 부분 유니크 인덱스로 보장
        val defaultCount =
            jdbc.queryForObject(
                "SELECT count(*) FROM permission_schemes WHERE is_default = TRUE",
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(defaultCount).isEqualTo(1)
    }

    /**
     * 권한 스킴은 마이그레이션 종료 시점에 **총 1개**여야 한다 — 기본 스킴 하나뿐.
     *
     * ## 이 단언이 지키는 것
     * `role_permissions` 시드 마이그레이션 9종(V008 · V009 · V013 · V014 · V016 · V017 · V018 ·
     * V024 · V035)은 **전부 기본 스킴 UUID `...0001` 한 곳에만** 권한을 넣는다(scheme_id 리터럴
     * 18개가 모두 동일). 프로덕션 코드에는 `permission_schemes` / `project_permission_scheme` 에
     * INSERT/UPDATE 하는 경로가 **하나도 없다**(마이그레이션과 테스트 픽스처가 전부).
     * 그래서 "비-기본 스킴에 매핑된 프로젝트는 MANAGE_WORKFLOW 가 없어 워크플로우 스킴을 배정할 수
     * 없다"는 결함은 **도달 불가**다 — 비-기본 스킴 자체를 만들 수 없기 때문이다.
     *
     * ## 이 단언이 깨지면 해야 할 일
     * 누군가 두 번째 스킴을 시드/생성하는 순간 그 도달 불가 전제가 무너진다. 그때는 **이 테스트만
     * 고치지 말고**, 위 9종 시드가 새 스킴에도 적용되도록 마이그레이션을 함께 추가해야 한다
     * (그러지 않으면 새 스킴을 쓰는 프로젝트의 PROJECT_ADMIN 이 권한을 통째로 잃는다).
     *
     * 기존 `기본 스킴은 단 하나만 존재한다` 는 `is_default = TRUE` 행만 세므로 **비-기본 스킴이
     * 늘어나는 것을 못 잡는다**. 그 사각을 이 단언이 막는다.
     */
    @Test
    fun `권한 스킴은 기본 스킴 하나뿐이다 — 비-기본 스킴은 생성 경로가 없다`() {
        val schemeCount =
            jdbc.queryForObject(
                "SELECT count(*) FROM permission_schemes",
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(schemeCount).isEqualTo(1)
    }

    @Test
    fun `project_permission_scheme 테이블이 존재한다`() {
        // 시드는 없고 테이블만 존재 — 미매핑 프로젝트는 기본 스킴 fallback 동작
        val tableCount =
            jdbc.queryForObject(
                """
            SELECT count(*)
            FROM information_schema.tables
            WHERE table_schema = 'public'
              AND table_name = 'project_permission_scheme'
            """,
                mapOf<String, Any>(),
                Int::class.java,
            )
        assertThat(tableCount).isEqualTo(1)
    }
}
