// dev 시드를 부팅 완료 직후 1회 실행하는 러너 — prod 격리 2층(빈 등록 + 런타임 단언)

package com.bts.app

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * [NonProdDevSeeder] 를 **부팅이 끝난 직후 1회** 실행한다.
 *
 * ## ★ 실행 시점을 옮기지 말 것 — `ApplicationRunner` 여야 한다
 * `FlywayAssemblyConfig.assemblyFlywayMigrator():45-65` 가 `InitializingBean.afterPropertiesSet()` 에서
 * 마이그레이션을 **즉시** 수행한다 = 컨텍스트 refresh **중** 완료. [ApplicationRunner] 는 refresh **후** 실행이라
 * 테이블이 반드시 존재한다. 이 클래스를 `@PostConstruct` 나 `InitializingBean` 으로 옮기면
 * **테이블이 생기기 전에 시드가 돌아 조용히 깨진다**. 회귀는 M5 뮤테이션이 잡는다.
 *
 * ## prod 격리 — 목록은 [ALLOWED_PROFILES] 하나뿐 (drift 방지)
 * `@Profile("!prod")` 같은 **부정**은 앞으로 생기는 모든 프로파일(`staging`·`demo`…)을 자동 포함하는
 * **fail-open** 이다. 허용목록은 fail-closed 라 모르는 프로파일에서는 애초에 빈이 등록되지 않는다.
 *
 * `default` 가 목록에 반드시 있어야 한다 — 조립 앱의 실제 기본 부팅은 **무프로파일**이고
 * (`application-dev.yml` 은 `dev` 활성 시에만 병합된다) Spring 은 그 상태를 `default` 프로파일로 표현한다.
 *
 * L1(빈 등록 `@Profile`)과 L2(아래 런타임 단언)가 **같은 상수 문자열**을 쓰도록 묶어, 한쪽만 고치는 drift 를 막는다.
 *
 * ## ★ 두 실패를 반대로 처리한다 (D9)
 * - **prod 격리 위반** → 예외를 던져 **기동을 실패**시킨다. 조용히 `return` 하면 음성 테스트가
 *   빈이 있든 없든 통과해 **공허**해진다 (learning `negative-guard-needs-body-discriminator`).
 * - **시드 자체 실패**(DB 제약 위반 등) → ERROR 로그만 남기고 **부팅을 계속**한다. dev 편의 기능이
 *   부팅을 막으면 #321 이 연 「비-prod 에서 켜진다」를 되돌리는 회귀가 된다.
 *
 * catch 는 [NonProdDevSeeder] 의 `@Transactional` 경계 **밖**이라 실패분은 정상 롤백된 뒤 로그만 남는다.
 */
@Component
@Profile(NonProdDevSeedRunner.PROFILE_EXPRESSION)
class NonProdDevSeedRunner(
    private val seeder: DevSeeder,
    private val environment: Environment,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(NonProdDevSeedRunner::class.java)

    override fun run(args: ApplicationArguments) {
        assertNotProdContext()
        runCatching { seeder.seed() }
            .onSuccess { log.info("dev seed: completed (profiles={})", environment.activeProfiles.toList()) }
            .onFailure { ex ->
                // 부팅은 계속한다 (D9). 원인을 남기지 않으면 "로그인이 안 된다" 를 진단할 수 없다.
                log.error("dev seed: FAILED — 로그인 계정이 없을 수 있다. 부팅은 계속한다.", ex)
            }
    }

    /**
     * 활성 프로파일이 [ALLOWED_PROFILES] 밖이면 **기동을 실패**시킨다.
     *
     * `@Profile` 이 이미 걸러 주지만, 이 단언은 그 표현식이 잘못 편집됐을 때의 2층이다.
     * 무프로파일(활성 0개)은 Spring 의 `default` 프로파일 상태이므로 통과시킨다.
     */
    private fun assertNotProdContext() {
        val active = environment.activeProfiles.toSet()
        val disallowed = active - ALLOWED_PROFILES
        check(disallowed.isEmpty()) {
            "dev 시드는 $ALLOWED_PROFILES 에서만 실행된다 — 허용되지 않은 활성 프로파일: $disallowed"
        }
    }

    companion object {
        /** L1(`@Profile`)이 쓰는 표현식. [ALLOWED_PROFILES] 와 항상 같은 집합이어야 한다. */
        const val PROFILE_EXPRESSION = "default | dev | local"

        /** L2(런타임 단언)가 쓰는 집합. [PROFILE_EXPRESSION] 과 항상 같은 집합이어야 한다. */
        val ALLOWED_PROFILES = setOf("default", "dev", "local")
    }
}
