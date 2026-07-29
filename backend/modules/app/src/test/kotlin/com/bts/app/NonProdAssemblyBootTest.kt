// 조립 앱이 기본(비-prod) 프로파일에서도 부팅되는지 + 프로파일 의존 포트가 정확히 1개씩 결선되는지 검증

package com.bts.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 조립 컨텍스트에서 **프로파일에 따라 구현이 갈리는 포트**의 계약.
 *
 * 각 타입은 어느 프로파일에서든 **활성 빈이 정확히 1개**여야 한다. 0개면 소비자 주입이 깨지고(부팅 실패),
 * 2개면 `NoUniqueBeanDefinitionException` 으로 역시 부팅이 깨진다. 이 목록이 곧 봉합 범위이며,
 * 새 항목이 발견되면 여기에 추가하는 것이 가드 확장의 유일한 지점이다.
 *
 * 목록 근거는 `docs/specs/2026-07-29-assembly-nonprod-bean-wiring.md` FR-A/FR-B/FR-C.
 */
internal object AssemblyPortContract {
    /** 봉합 대상 9종 — 비-prod 조립에서 중복(6) 또는 부재(3) 였던 타입. */
    val SEALED: List<Class<*>> =
        listOf(
            com.bts.shared.permission.IssuePermissionResolver::class.java,
            com.bts.shared.permission.ComponentPermissionResolver::class.java,
            com.bts.shared.permission.CustomFieldPermissionResolver::class.java,
            com.bts.shared.permission.TemplatePermissionResolver::class.java,
            com.bts.shared.permission.VersionPermissionResolver::class.java,
            com.bts.shared.permission.SystemPermissionResolver::class.java,
            com.bts.shared.permission.AutomationPermissionResolver::class.java,
            com.bts.shared.issue.IssueMutationPort::class.java,
            com.bts.shared.issue.IssueSnapshotPort::class.java,
        )

    /**
     * 배제 **금지** 2종 (스펙 FR-B).
     *
     * issue-tracking 의 `AlwaysAllow*`/`NonProdAllow*` 는 8개인데 중복인 것은 6개뿐이다.
     * 아래 두 타입은 그 8개 중 **자기 타입의 유일한 비-prod 구현**이라, 패턴으로 싸잡아 배제하면
     * 곧바로 새 "빈 부재" 가 된다 — 봉합이 새 결함을 만드는 양식. 이 단언이 그 실수를 잡는다.
     */
    val MUST_NOT_EXCLUDE: List<Class<*>> =
        listOf(
            com.bts.shared.permission.FieldPermissionResolver::class.java,
            com.bts.shared.permission.IssueSecurityDirectory::class.java,
        )

    val ALL: List<Class<*>> = SEALED + MUST_NOT_EXCLUDE

    /** 활성 빈이 정확히 1개인지 단언한다. 실패 시 **발견된 빈 이름 전량**을 메시지에 담는다. */
    fun assertExactlyOneEach(
        context: ApplicationContext,
        profileLabel: String,
    ) {
        ALL.forEach { type ->
            val names = context.getBeanNamesForType(type)
            assertThat(names.toList())
                .describedAs("[$profileLabel] ${type.name} 활성 빈 — 정확히 1개여야 한다")
                .hasSize(1)
        }
    }
}

/**
 * 조립 앱을 **기본(비-prod) 프로파일**로 실제 부팅해 회귀를 막는다.
 *
 * ## 왜 이 테스트가 따로 필요한가
 * 기존 조립 테스트는 전부 [ProdAssemblyHttpTestBase] 를 상속해 `@ActiveProfiles("prod")` 로 고정돼 있다.
 * 즉 **비-prod 조립 부팅에는 계약도 가드도 없었다** — 각 BC 의 `@Profile("!prod")` 스텁이 "각자 독립
 * `@SpringBootApplication`" 전제로 설계됐는데 배포 조립 모듈 `app` 이 생기며 그 전제가 깨졌고, prod 는
 * 멀쩡한 채 **비-prod 에서만** 부팅이 죽었다(빈 중복 6종 + 빈 부재 3종).
 * ADR `2026-07-11-automation-prod-assembly` §fail-closed 의 "미충족 의존 0" 선언도 본문 그대로
 * "조립+prod 컨텍스트에서" 로 한정돼 있었다.
 *
 * ## ★ 반드시 별도 JVM 에서 돌 것 — `@Tag("nonprod-assembly")`
 * `webEnvironment` 와 `@ActiveProfiles` 는 **컨텍스트 캐시 키의 일부**다([ProdAssemblyHttpTestBase] KDoc).
 * 이 테스트가 prod 조립 테스트와 같은 JVM 에 있으면 9-BC 컨텍스트가 **두 벌** 뜨고, `@Scheduled` 워커도
 * 두 벌이 동일 5433 dev postgres 의 pgmq 큐를 **동시 폴링**한다. 그래서 `build.gradle.kts` 가
 * 이 태그를 기본 `test` 태스크에서 제외하고 전용 `nonProdAssemblyTest` 태스크(별도 JVM)로만 실행한다.
 *
 * ## ★ dev 앱을 띄운 채 실행하지 말 것
 * 비-prod 조립이 부팅되면 automation `@Scheduled` 워커 4종이 **처음으로** 살아난다(그전엔 부팅 자체가
 * 실패해 한 번도 없던 상태다). `bootRun` 과 이 테스트를 동시에 돌리면 같은 `q_automation_events` 를
 * 두 벌이 폴링한다. 룰이 0건이면 무해 delete 지만(ADR 배포 런북), 큐 단언이 있는 테스트를 flaky 로 만든다.
 *
 * ## 왜 `RANDOM_PORT` 인가
 * prod 가드와 **같은 충실도**로 맞춘다. `NONE` 이면 서블릿·시큐리티 자동설정이 backoff 되어 웹 계층
 * 결함을 그냥 통과한다. 이 테스트는 전용 태스크의 유일한 컨텍스트라 실 Tomcat 을 태워도 이중 부팅이 없다.
 *
 * ## 사전 조건
 * dev postgres 기동 — `docker compose -f infra/docker-compose.dev.yml up -d postgres` (5433).
 * [ProdAssemblyHttpTestBase] 와 동일하게 Testcontainers 를 관리하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("nonprod-assembly")
class NonProdAssemblyBootTest {
    @Autowired
    private lateinit var context: ApplicationContext

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            // 비-prod 는 DevMemoryKeyProvider 를 쓰므로 PEM 경로가 필요 없다(prod 전용 @Bean).
            registry.add("bts.auth.issuer-uri") { "http://localhost:8080" }
            registry.add("bts.slack.signing-secret") { ProdAssemblyHttpTestBase.TEST_SLACK_SIGNING_SECRET }
            registry.add("bts.automation-encryption.key") { ProdAssemblyHttpTestBase.TEST_AUTOMATION_ENCRYPTION_KEY }
            registry.add("bts.automation-encryption.salt") { ProdAssemblyHttpTestBase.TEST_AUTOMATION_ENCRYPTION_SALT }
        }
    }

    @Test
    fun `조립 컨텍스트가 기본(비-prod) 프로파일로 부팅된다`() {
        // contextLoads — 컨텍스트 초기화 자체가 검증. 실패 시 예외로 표면화.
        assertThat(context.environment.activeProfiles).doesNotContain("prod")
    }

    @Test
    fun `프로파일 의존 포트 11종이 비-prod 조립에서 각각 정확히 1개다`() {
        // 컨텍스트 로드 성공만으로 끝내면 공허하다 — 이 단언이 봉합 범위를 이름 단위로 고정한다.
        // 중복이면 2개, 부재면 0개로 잡히고, 배제 금지 2종을 실수로 빼도 0개로 잡힌다.
        AssemblyPortContract.assertExactlyOneEach(context, "비-prod")
    }
}
