// LDAP + PostgreSQL Testcontainers 기반 통합 테스트 베이스 클래스

package com.atlas.bts.identity.provider.ldap

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.utility.MountableFile
import java.nio.file.Paths
import java.time.Duration

/**
 * LDAP (osixia/openldap) + PostgreSQL Testcontainers 공통 기반.
 * 상속 클래스는 @SpringBootTest + @Testcontainers 를 추가해야 한다.
 *
 * osixia/openldap:1.5.0 선정 이유 — ADR: docs/decisions/2026-05-20-ldap-testcontainers-image.md
 */
abstract class LdapTestcontainersBase {

    companion object {
        private val ldifPath: String = run {
            val gradleDir = System.getProperty("user.dir")
            val candidates = listOf(
                Paths.get(gradleDir, "infra/ldap/seed.ldif"),
                Paths.get(gradleDir, "../infra/ldap/seed.ldif"),
                Paths.get(gradleDir, "../../infra/ldap/seed.ldif"),
                Paths.get(gradleDir, "../../../infra/ldap/seed.ldif"),
            )
            candidates.firstOrNull { it.toFile().exists() }?.toAbsolutePath()?.toString()
                ?: error("seed.ldif 파일을 찾을 수 없음. candidates: $candidates")
        }

        @Container
        @JvmStatic
        val openldap: GenericContainer<*> = GenericContainer("osixia/openldap:1.5.0")
            .withEnv("LDAP_ORGANISATION", "BTS")
            .withEnv("LDAP_DOMAIN", "example.org")
            .withEnv("LDAP_ADMIN_PASSWORD", "adminpassword")
            .withEnv("LDAP_READONLY_USER", "false")
            .withCopyFileToContainer(
                MountableFile.forHostPath(ldifPath),
                "/container/service/slapd/assets/config/bootstrap/ldif/50-bootstrap.ldif",
            )
            .withCommand("--copy-service --loglevel debug")
            .withExposedPorts(389)
            .waitingFor(
                Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)),
            )

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("bts_test")
            .withUsername("bts")
            .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun containerProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
            r.add("spring.ldap.urls") { "ldap://${openldap.host}:${openldap.firstMappedPort}" }
            r.add("spring.ldap.base") { "dc=example,dc=org" }
            r.add("spring.ldap.username") { "cn=admin,dc=example,dc=org" }
            r.add("spring.ldap.password") { "adminpassword" }
            // BTS_LDAP_BIND_PASSWORD_INTEGRATION env var 로 bind password 제공
            r.add("BTS_LDAP_BIND_PASSWORD_INTEGRATION") { "adminpassword" }
        }
    }
}
