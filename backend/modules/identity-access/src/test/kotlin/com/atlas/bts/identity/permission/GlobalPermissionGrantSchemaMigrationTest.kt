// V036 마이그레이션 검증 — global_permission_grants 테이블·CHECK 제약·멱등 UNIQUE 확인

package com.atlas.bts.identity.permission

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * V036 마이그레이션 검증 — global_permission_grants 의 DB 제약을 검증한다 (FR-PM-10).
 *
 * ## 검증 항목
 * - grantee_type CHECK — USER/GROUP 외 값 거부 (ADR D-4 다형 참조의 종류 축)
 * - UNIQUE (permission, grantee_type, grantee_id) — 같은 grantee 중복 부여 차단 (ADR D-1)
 * - permission CHECK — 권한코드 오타 거부 (ADR D-1, 사용자 입력을 받는 유일한 권한코드 컬럼)
 *
 * PermissionSchemaMigrationTest 의 @JdbcTest + Testcontainers + @DynamicPropertySource 패턴을 복제.
 *
 * ## 여기에 없는 것
 * 기본 스킴 매트릭스 카운트 단언은 **의도적으로 두지 않는다**. PermissionSchemaMigrationTest 가
 * 이미 갖고 있으며, 복제하면 V036 과 무관한 V008~V035 의 성질을 재단언하는 데다
 * 하드코딩 카운트 가드가 2곳이 되어 다음 권한코드 추가 때 무관한 이름 뒤에 숨는다.
 *
 * ## granted_by 를 모든 INSERT 가 채우는 이유
 * granted_by 는 NOT NULL(ADR D-5)이다. 생략하면 CHECK 위반이 아니라 NOT NULL 위반으로
 * 예외가 나서, 대상 CHECK 를 지워도 테스트가 통과하는 vacuous 가드가 된다.
 * 각 테스트가 겨냥한 제약 **하나만** 위반시키는 것이 판별자다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class GlobalPermissionGrantSchemaMigrationTest {
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
            r.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Test
    fun `grantee_type 은 USER 와 GROUP 만 허용한다`() {
        // grantee_type 만 위반값('ROLE')이고 나머지 컬럼은 전부 유효하다 —
        // 예외가 나온다면 원인은 grantee_type CHECK 하나뿐이다.
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO global_permission_grants (permission, grantee_type, grantee_id, granted_by) " +
                    "VALUES ('CREATE_PROJECT', 'ROLE', :id, :by)",
                mapOf("id" to UUID.randomUUID(), "by" to UUID.randomUUID()),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 (permission, grantee) 중복 부여는 UNIQUE 로 차단된다`() {
        val granteeId = UUID.randomUUID()
        val sql =
            "INSERT INTO global_permission_grants (permission, grantee_type, grantee_id, granted_by) " +
                "VALUES ('CREATE_PROJECT', 'USER', :id, :by)"
        jdbc.update(sql, mapOf("id" to granteeId, "by" to UUID.randomUUID()))

        // 두 번째 부여는 granted_by 만 다르다. UNIQUE 가 (permission, grantee_type, grantee_id)
        // 3컬럼이라는 것이 판별자 — granted_by 가 UNIQUE 에 섞여 있다면 이 INSERT 는 통과해 버린다.
        assertThatThrownBy { jdbc.update(sql, mapOf("id" to granteeId, "by" to UUID.randomUUID())) }
            .isInstanceOf(DuplicateKeyException::class.java)
    }

    @Test
    fun `permission 은 CREATE_PROJECT 만 허용한다`() {
        // ADR D-1 — 이 테이블은 권한코드를 사용자 입력(REST 바디)으로 받는 유일한 곳이다.
        // 오타는 fail-closed 라 사고는 안 나지만 아무도 원인을 모르는 쓰레기 grant 를 남긴다. DB 가 막는다.
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO global_permission_grants (permission, grantee_type, grantee_id, granted_by) " +
                    "VALUES ('CREATE_PROJET', 'USER', :id, :by)",
                mapOf("id" to UUID.randomUUID(), "by" to UUID.randomUUID()),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
