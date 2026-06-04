// Keycloak(SAML IdP) + PostgreSQL Testcontainers 공통 기반 — FR-AU-03 SAML SSO end-to-end 통합 테스트

package com.atlas.bts.identity.integration

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.time.Duration

/**
 * Keycloak(SAML IdP) + PostgreSQL Testcontainers 공통 기반 (FR-AU-03 Task 7).
 *
 * 상속 클래스는 @SpringBootTest 를 추가해야 한다.
 *
 * ## 이미지 선정
 * - Keycloak `quay.io/keycloak/keycloak:25.0` — 기존 인프라(`infra/docker-compose.dev.yml`)와 동일 버전.
 *   Testcontainers 전용 모듈(dasniko/testcontainers-keycloak) 을 새 의존성으로 추가하지 않고,
 *   기존 OpenLDAP 통합 테스트가 쓰는 [GenericContainer] 패턴을 그대로 따른다(의존성 표면 최소화).
 * - PostgreSQL `postgres:16-alpine` — 다른 통합 테스트와 동일.
 *
 * ## SAML realm import
 * Keycloak 은 `--import-realm` 으로 부팅 시 `/opt/keycloak/data/import/` 의 realm JSON 을 자동 임포트한다.
 * 테스트 전용 realm(`saml-test`)은 `src/test/resources/keycloak/saml-test-realm.json` 에 둔다
 * (dev `infra/keycloak/realm-bts.json` 손상 금지 — 테스트 realm 은 모듈 test 리소스에 격리).
 *
 * ## Testcontainers singleton pattern (stale port 회귀 방지)
 * `@Container` annotation(클래스 단위 라이프사이클) 대신 `.apply { start() }` 로 JVM 단위 라이프사이클을
 * 사용한다. 여러 자식 통합 테스트가 같은 ApplicationContext cache key 를 공유할 때, 클래스 단위
 * 라이프사이클은 첫 클래스 종료 시 container stop → 두 번째 클래스 재실행 시 stopped container 의
 * stale port 로 연결 시도 → 실패한다. JVM 종료 시 Testcontainers Ryuk 가 컨테이너를 자동 정리한다
 * (orphan 방지).
 *
 * ## Keycloak 부팅 모드
 * 개발/테스트 전용 `start-dev` 모드 — 영속 DB 없이 in-memory H2 로 부팅한다(테스트 격리·속도).
 */
@Suppress("UtilityClassWithPublicConstructor")
abstract class KeycloakSamlTestcontainersBase {
    companion object {
        /** 테스트 전용 SAML realm 이름 — realm import JSON 의 `realm` 필드와 일치해야 한다. */
        const val SAML_REALM = "saml-test"

        /** SP(BTS) registrationId — saml_idp_configs.registration_id + ACS URL 경로에 사용. */
        const val REGISTRATION_ID = "bts-keycloak"

        /** Keycloak HTTP 포트(컨테이너 내부) — start-dev 기본값. */
        private const val KEYCLOAK_HTTP_PORT = 8080

        /** Keycloak 관리자 계정 — realm/descriptor 접근용(테스트 전용 고정값). */
        const val KEYCLOAK_ADMIN_USER = "admin"
        const val KEYCLOAK_ADMIN_PASSWORD = "admin"

        /** realm import JSON 의 테스트 사용자(realm JSON 과 일치). */
        const val SAML_TEST_USERNAME = "alice"
        const val SAML_TEST_PASSWORD = "Test1234!"

        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        @JvmStatic
        val keycloak: GenericContainer<*> =
            GenericContainer("quay.io/keycloak/keycloak:25.0")
                .withExposedPorts(KEYCLOAK_HTTP_PORT)
                .withEnv("KEYCLOAK_ADMIN", KEYCLOAK_ADMIN_USER)
                .withEnv("KEYCLOAK_ADMIN_PASSWORD", KEYCLOAK_ADMIN_PASSWORD)
                .withCopyFileToContainer(
                    MountableFile.forClasspathResource("keycloak/saml-test-realm.json"),
                    "/opt/keycloak/data/import/saml-test-realm.json",
                )
                .withCommand("start-dev", "--import-realm", "--http-port=$KEYCLOAK_HTTP_PORT")
                // realm 이 임포트되면 SAML descriptor 엔드포인트가 200 을 반환한다 — 부팅 완료 신호.
                .waitingFor(
                    Wait.forHttp("/realms/$SAML_REALM/protocol/saml/descriptor")
                        .forStatusCode(HTTP_OK)
                        .withStartupTimeout(Duration.ofMinutes(STARTUP_TIMEOUT_MINUTES)),
                )
                .apply { start() }

        /** Keycloak 외부 접근 base URL (host:mappedPort). */
        @JvmStatic
        fun keycloakBaseUrl(): String = "http://${keycloak.host}:${keycloak.getMappedPort(KEYCLOAK_HTTP_PORT)}"

        /** 테스트 realm 의 SAML IdP descriptor(메타데이터) URL — EntityID/SSO URL/서명 인증서 추출용. */
        @JvmStatic
        fun samlDescriptorUrl(): String = "${keycloakBaseUrl()}/realms/$SAML_REALM/protocol/saml/descriptor"

        @DynamicPropertySource
        @JvmStatic
        fun containerProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
        }

        /** Keycloak SAML descriptor 엔드포인트가 realm import 완료 시 반환하는 HTTP 상태코드. */
        private const val HTTP_OK = 200

        /** Keycloak 부팅 + realm import 최대 대기(분) — 콜드 부팅 여유. */
        private const val STARTUP_TIMEOUT_MINUTES = 3L
    }
}
