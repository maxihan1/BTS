// prod 조립 HTTP 테스트 공통 베이스 — 실 Tomcat(RANDOM_PORT) + prod 프로파일로 서블릿 필터체인까지 실제로 태운다

package com.bts.app

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * prod 프로파일 + 실 Tomcat(RANDOM_PORT) 조립 컨텍스트에서 HTTP 계층(서블릿 필터체인 포함)까지 실제로
 * 검증해야 하는 테스트들의 공통 베이스 (FR-AT-07 PR-A §A-5).
 *
 * ## 왜 MOCK 이 아니라 RANDOM_PORT 인가
 * `@SpringBootTest`의 기본(MOCK) 웹환경은 `DispatcherServlet`을 직접 호출만 할 뿐 실 서블릿 컨테이너가
 * 없다. permitAll·CSRF-ignore 같은 Spring Security **필터체인** 동작은 `MockMvc`로 검증하면 서블릿을
 * 우회해 실제로는 안 통하는 것이 통과하는 **가짜 그린**이 될 수 있다
 * ([[multipart-default-limit-app-policy-false-green]] — 서블릿 기본 상한이 MockMvc 에서는 통과하고
 * prod 에서 터진 실사고). `RANDOM_PORT`는 실 Tomcat 을 임의 포트에 바인딩해 [TestRestTemplate]이 진짜
 * HTTP 요청을 보내게 하므로 필터체인을 우회할 방법이 없다.
 *
 * ## 왜 prod 프로파일인가
 * 조립 앱은 prod 산출물이다. 권한 resolver 등 여러 컴포넌트가 `@Profile("prod")`(실제) ↔
 * `@Profile("!prod")`(스텁)로 배타 설계돼 있어, prod 프로파일이라야 실제 구현 하나만 활성화돼 충돌이
 * 없다 ([[identity-access-prod-randomport-boot-recipe]]).
 *
 * ## 사전 조건
 * dev postgres 기동 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433).
 * `spring.datasource.url` 기본값이 `jdbc:postgresql://localhost:5433/bts`(app 모듈 `application.yml`)이라
 * 이 베이스는 Testcontainers 를 관리하지 않는다(기존 [BtsApplicationContextTest]와 동일 전제).
 *
 * ## ★ `webEnvironment`는 컨텍스트 캐시 키의 일부다
 * Spring 은 `@SpringBootTest` 설정(웹환경·프로퍼티 등)이 다르면 별개의 `MergedContextConfiguration`으로
 * 판단해 컨텍스트를 **새로 부팅**한다. 이 베이스와 다른 `webEnvironment`를 쓰는 조립 테스트가 같은
 * `:modules:app:test` JVM 안에 함께 있으면, 9-BC prod 컨텍스트가 **부팅 2회**로 중복되고
 * `@Scheduled` 워커(automation 실행 워커 등)도 2벌이 동일 5433 dev postgres 의 pgmq 큐를 **동시
 * 폴링**하게 된다. 따라서 같은 JVM 의 다른 prod 조립 HTTP 테스트는 **반드시 이 베이스를 상속**해
 * 컨텍스트를 공유해야 한다.
 *
 * ## ★ abstract — 자체 실행 대상이 아니다
 * 이 클래스는 `@Test`를 갖지 않는다. `--tests ProdAssemblyHttpTestBase*`로 Gradle 을 돌리면 매칭되는
 * 테스트가 0건이라 "No tests found"로 빌드가 실패한다. 이 베이스는 하위 클래스([BtsApplicationContextTest]
 * 등)를 통해서만 실행된다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
abstract class ProdAssemblyHttpTestBase {
    @Autowired
    protected lateinit var rest: TestRestTemplate

    companion object {
        /**
         * slack 서명검증 테스트 전용 알려진 secret — 실 시크릿이 아니다. `SlackSignatureVerifier`가
         * `v0:{timestamp}:{rawBody}`를 이 값으로 HMAC-SHA256 서명해 `bts.slack.signing-secret`
         * 프로퍼티(이 값)와 대조하므로, 하위 클래스가 이 상수로 유효 서명을 직접 계산해 필터체인 통과를
         * 양성 증명할 수 있다(FR-AT-07 PR-A §A-5 — "401이 아님"이라는 음성 단언 대신).
         */
        const val TEST_SLACK_SIGNING_SECRET = "test-slack-signing-secret-not-a-real-secret"

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            // 로컬 검증용 RSA 테스트 키 생성 (PKCS#8 PEM, 실제 시크릿 아님) → 임시 파일 경로 주입.
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
            val pem = "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----\n"
            val pemFile = Files.createTempFile("bts-jwt-test", ".pem")
            Files.writeString(pemFile, pem)

            registry.add("bts.auth.issuer-uri") { "http://localhost:8080" }
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFile.toAbsolutePath().toString() }
            registry.add("bts.slack.signing-secret") { TEST_SLACK_SIGNING_SECRET }
        }
    }
}
