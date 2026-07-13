// 조립 앱 컨텍스트 로드 검증 — 9개 BC 전체 빈이 prod 프로파일로 하나의 컨텍스트에 결선·부팅되는지 확인

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * 전체 조립 컨텍스트가 **prod 프로파일**로 로드되는지 확인한다.
 *
 * 조립 앱은 prod 산출물이다 — 권한 resolver 가 `@Profile("prod")`(실제) ↔ `@Profile("!prod")`(스텁)로
 * 배타 설계라, prod 프로파일이라야 실제 구현 하나만 활성화돼 충돌이 없다.
 *
 * 사전 조건: dev postgres(`docker compose -f infra/docker-compose.dev.yml up -d postgres`, 5433) 기동.
 * prod 필수 시크릿(issuer URI·RSA 키)은 [props] 가 런타임 생성/주입한다(실제 시크릿 미커밋).
 *
 * 통과 시 = 빈 충돌·설정 누락·cross-BC 미배선·보안 체인 순서 문제 없음.
 */
@SpringBootTest
@ActiveProfiles("prod")
class BtsApplicationContextTest {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `조립 컨텍스트가 prod 프로파일로 로드된다`() {
        // contextLoads — 컨텍스트 초기화 자체가 검증. 실패 시 예외로 표면화.
    }

    @Test
    fun `automation 워커 빈이 조립 컨텍스트에 결선된다`() {
        // FullyQualifiedAnnotationBeanNameGenerator → 빈 이름 = FQN 클래스명.
        // automation 이 build 의존 + 스캔에 포함돼야만 이 빈이 존재한다.
        assertThat(context.containsBean("com.bts.automation.worker.AutomationExecutionWorker")).isTrue()
    }

    @Test
    fun `IssueSnapshotPort prod 어댑터가 조립 컨텍스트에 결선된다(fail-closed 회귀 가드, FR-AT-03)`() {
        // automation ActionExecutor 는 IssueSnapshotPort 를 non-null 로 요구한다(조건 게이트,
        // [[crossbc-resolver-nullable-fail-open]] 회귀 방지 — shared-kernel IssueSnapshotPort KDoc 참조).
        // issue-tracking 의 AutomationIssueSnapshotAdapter(@Profile("prod"))가 빠지면 이 빈이 사라져
        // ActionExecutor 주입이 NoSuchBeanDefinitionException 으로 컨텍스트 부팅 자체를 막아야 한다
        // (silent no-op 금지). 컨텍스트가 이미 로드에 성공했다는 사실 자체가 주입 충족을 증명하지만,
        // 이 단언은 그 충족이 "우연한 다른 빈"이 아니라 의도한 prod 어댑터임을 이름으로 고정한다.
        assertThat(
            context.containsBean("com.bts.issue.adapter.outbound.automation.AutomationIssueSnapshotAdapter"),
        ).isTrue()
    }

    companion object {
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
        }
    }
}
