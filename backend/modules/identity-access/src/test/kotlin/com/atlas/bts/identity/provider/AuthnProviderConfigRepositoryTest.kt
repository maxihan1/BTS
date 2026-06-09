// AuthnProviderConfigRepository 통합 테스트 — Testcontainers PostgreSQL + Flyway V001~V019 적용 (FR-AU-06)

package com.atlas.bts.identity.provider

import com.atlas.bts.identity.spi.ProviderType
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
 * AuthnProviderConfigRepository 통합 테스트 (FR-AU-06).
 * @JdbcTest + Testcontainers PostgreSQL + Flyway V001~V019 자동 적용.
 *
 * authn_providers 의 enabled/sort_order 조회 규약을 검증한다.
 * - isEnabled: row 미등록 type 은 기본 활성(true), enabled=false row 가 등록되면 false.
 * - listEnabledByTypes: 주어진 type 중 enabled=true 인 것만 sort_order 오름차순으로 반환.
 *
 * LOCAL/LDAP 는 V001~V019 어디에서도 authn_providers 에 seed 되지 않으므로
 * 각 테스트가 직접 INSERT 로 제어한다. SAML/OIDC seed(V010/V011)와 격리하기 위해
 * 검증 대상 type 을 명시적으로 한정한다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuthnProviderConfigRepository::class)
@Testcontainers
class AuthnProviderConfigRepositoryTest {
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
    private lateinit var repo: AuthnProviderConfigRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeEach
    fun setUp() {
        // SAML/OIDC seed 는 남기되, 본 테스트가 제어하는 LOCAL/LDAP row 만 정리한다.
        jdbc.update(
            "DELETE FROM authn_providers WHERE type IN ('LOCAL', 'LDAP')",
            emptyMap<String, Any>(),
        )
    }

    private fun insertProvider(
        type: ProviderType,
        enabled: Boolean,
        sortOrder: Int,
    ) {
        jdbc.update(
            """
            INSERT INTO authn_providers (id, type, name, config, enabled, sort_order)
            VALUES (:id, :type, :name, '{}'::jsonb, :enabled, :sortOrder)
            """.trimIndent(),
            mapOf(
                "id" to UUID.randomUUID(),
                "type" to type.name,
                "name" to "Test ${type.name} ${UUID.randomUUID()}",
                "enabled" to enabled,
                "sortOrder" to sortOrder,
            ),
        )
    }

    @Test
    fun `isEnabled — LOCAL row 가 없으면 기본 활성(true)`() {
        assertThat(repo.isEnabled(ProviderType.LOCAL)).isTrue()
    }

    @Test
    fun `isEnabled — LDAP enabled=false row 가 있으면 false`() {
        insertProvider(ProviderType.LDAP, enabled = false, sortOrder = 0)

        assertThat(repo.isEnabled(ProviderType.LDAP)).isFalse()
    }

    @Test
    fun `isEnabled — LDAP enabled=true row 가 있으면 true`() {
        insertProvider(ProviderType.LDAP, enabled = true, sortOrder = 0)

        assertThat(repo.isEnabled(ProviderType.LDAP)).isTrue()
    }

    @Test
    fun `isEnabled — 같은 type 에 enabled=true 와 enabled=false row 가 공존하면 fail-safe 로 false`() {
        // 운영자가 같은 type 에 활성/비활성 row 를 둘 다 등록한 모순 상태.
        // fail-safe: 끄려는 의도가 하나라도 있으면 비활성. LIMIT 1 순서에 의존하지 않고 결정적이어야 한다.
        insertProvider(ProviderType.LDAP, enabled = true, sortOrder = 0)
        insertProvider(ProviderType.LDAP, enabled = false, sortOrder = 1)

        assertThat(repo.isEnabled(ProviderType.LDAP)).isFalse()
    }

    @Test
    fun `listEnabledByTypes — enabled=true 인 type 만 sort_order 오름차순으로 반환`() {
        insertProvider(ProviderType.LDAP, enabled = true, sortOrder = 10)
        insertProvider(ProviderType.LOCAL, enabled = true, sortOrder = 5)

        val result = repo.listEnabledByTypes(listOf(ProviderType.LOCAL, ProviderType.LDAP))

        assertThat(result).containsExactly(
            ProviderType.LOCAL to 5,
            ProviderType.LDAP to 10,
        )
    }

    @Test
    fun `listEnabledByTypes — disabled row 는 제외하고 미등록 type 도 제외`() {
        insertProvider(ProviderType.LDAP, enabled = false, sortOrder = 0)
        // LOCAL 은 INSERT 하지 않음 — 미등록 type 은 목록에서 빠진다.

        val result = repo.listEnabledByTypes(listOf(ProviderType.LOCAL, ProviderType.LDAP))

        assertThat(result).isEmpty()
    }

    // ── findByIds (FR-AU-08 계정 연결 — link 의 provider 정보 표시용) ───────────

    @Test
    fun `findByIds — 빈 ids 는 빈 맵`() {
        assertThat(repo.findByIds(emptyList())).isEmpty()
    }

    @Test
    fun `findByIds — 주어진 id 의 id name type enabled 를 맵으로 반환`() {
        val ldapId = insertProviderReturningId(ProviderType.LDAP, enabled = true, sortOrder = 0)
        val localId = insertProviderReturningId(ProviderType.LOCAL, enabled = false, sortOrder = 1)

        val result = repo.findByIds(listOf(ldapId, localId))

        assertThat(result.keys).containsExactlyInAnyOrder(ldapId, localId)
        assertThat(result[ldapId]!!.type).isEqualTo(ProviderType.LDAP)
        assertThat(result[ldapId]!!.enabled).isTrue()
        assertThat(result[localId]!!.type).isEqualTo(ProviderType.LOCAL)
        assertThat(result[localId]!!.enabled).isFalse()
    }

    @Test
    fun `findByIds — 존재하지 않는 id 는 결과에서 빠진다`() {
        val ldapId = insertProviderReturningId(ProviderType.LDAP, enabled = true, sortOrder = 0)
        val missing = UUID.randomUUID()

        val result = repo.findByIds(listOf(ldapId, missing))

        assertThat(result.keys).containsExactly(ldapId)
    }

    private fun insertProviderReturningId(
        type: ProviderType,
        enabled: Boolean,
        sortOrder: Int,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO authn_providers (id, type, name, config, enabled, sort_order)
            VALUES (:id, :type, :name, '{}'::jsonb, :enabled, :sortOrder)
            """.trimIndent(),
            mapOf(
                "id" to id,
                "type" to type.name,
                "name" to "Test ${type.name} $id",
                "enabled" to enabled,
                "sortOrder" to sortOrder,
            ),
        )
        return id
    }
}
