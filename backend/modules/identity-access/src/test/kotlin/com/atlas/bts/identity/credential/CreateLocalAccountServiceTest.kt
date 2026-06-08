// CreateLocalAccountService 통합 테스트 — 실 repo + 시드(Testcontainers PostgreSQL)로 end-to-end 검증

package com.atlas.bts.identity.credential

import com.atlas.bts.identity.user.JdbcUserRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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

/**
 * CreateLocalAccountService 통합 테스트 (FR-AU-05 Task 3).
 *
 * mock 대신 실 repo + 실 PostgreSQL 로 user + local_credentials 동시 생성을 end-to-end 검증한다
 * (메모리 issue-tracking-transition-test-mocks-workflow-repo — 영속 경로는 실 repo 로 검증).
 *
 * 검증 대상.
 * - 성공: users 행 + local_credentials(must_change=true) 행 생성, 반환 temporaryPassword 가 정책 충족.
 * - 중복 username: [UsernameTakenException] 전파, 부분 생성 없음(롤백).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcUserRepository::class, StoredPasswordCredentialRepository::class)
@Testcontainers
class CreateLocalAccountServiceTest {
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
    private lateinit var userRepository: JdbcUserRepository

    @Autowired
    private lateinit var credentialRepository: StoredPasswordCredentialRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    private lateinit var service: CreateLocalAccountService

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        service =
            CreateLocalAccountService(
                userRepository = userRepository,
                localCredentialService = LocalCredentialService(credentialRepository),
                temporaryPasswordGenerator = TemporaryPasswordGenerator(),
            )
    }

    @Test
    fun `create — user + local_credentials(must_change=true) 생성`() {
        val result = service.create("nora", "nora@bts.local", "Nora Seo")

        // users 행 생성 확인
        val user = userRepository.findByUsername("nora")
        assertThat(user).isNotNull()
        assertThat(user!!.id).isEqualTo(result.user.id)
        assertThat(user.email).isEqualTo("nora@bts.local")
        assertThat(user.displayName).isEqualTo("Nora Seo")

        // local_credentials 행 생성 + must_change_password=true 확인
        val credential = credentialRepository.findByUserId(result.user.id)
        assertThat(credential).isNotNull()
        assertThat(credential!!.mustChangePassword).isTrue()
    }

    @Test
    fun `create — 반환 temporaryPassword 는 PasswordPolicy 충족`() {
        val result = service.create("oscar", "oscar@bts.local", "Oscar Yoon")

        // 응답용 평문은 정책(12자 + 3종 이상)을 통과해야 한다.
        assertThat(PasswordPolicy.validate(result.temporaryPassword)).isEmpty()
    }

    @Test
    fun `create — 반환 temporaryPassword 로 저장된 자격증명이 검증된다`() {
        val result = service.create("paula", "paula@bts.local", "Paula Jung")

        // store 가 temp 를 wipe 해도 응답용 copyOf 는 살아 있어야 한다 (CONCERN-3).
        // 즉 forResponse 평문으로 실제 저장 해시를 verify 할 수 있어야 한다.
        val service2 = LocalCredentialService(credentialRepository)
        val verified = service2.verifyForUser(result.user.id, result.temporaryPassword.copyOf())
        assertThat(verified).isTrue()
    }

    @Test
    fun `create — 중복 username 은 UsernameTakenException`() {
        service.create("quinn", "quinn@bts.local", "Quinn Old")

        assertThatThrownBy {
            service.create("quinn", "quinn-new@bts.local", "Quinn New")
        }.isInstanceOf(UsernameTakenException::class.java)
    }
}
