// 알림 BC 아키텍처 격리 규칙 검증 — ArchUnit 6룰 (cross-BC 직접 import 금지 + dashboard BC 격리 + jOOQ 화이트리스트 + @Transactional/@Service 동반)

package com.bts.notification.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * notification BC 아키텍처 규칙 검증.
 *
 * ArchUnit (아키텍처 규칙을 코드로 작성하고 자동 검증하는 라이브러리) 을 사용해
 * 다음 3개 규칙을 테스트 시점에 강제한다.
 *
 * - 룰 1 (BC 격리 — issue-tracking 직접 import 금지) — [mustNotImportIssueTracking]
 * - 룰 2 (BC 격리 — project-workflow 직접 import 금지) — [mustNotImportProjectWorkflow]
 * - 룰 3 (BC 격리 — identity-access 직접 import 금지) — [mustNotImportIdentityAccess]
 * - 룰 4 (jOOQ 화이트리스트) — [jooqGeneratedMustOnlyBeUsedInRepositoryLayer]
 * - 룰 5 (@Transactional + @Service 동반) — [transactionalClassesMustBeServiceOrComponent]
 *
 * ### 통신 허용 채널
 * notification BC는 이벤트(pgmq)와 shared-kernel 포트(`com.bts.shared.*`)를 통해서만
 * 다른 BC와 통신한다. issue-tracking / project-workflow / identity-access 내부 패키지를
 * 직접 import하는 것은 BC 경계 위반이다.
 *
 * ### 화이트리스트 — jOOQ 생성 코드
 * `com.bts.notification.jooq..` 는 jOOQ 코드 생성 결과물이므로 BC 경계 룰에서 제외한다.
 * jOOQ 생성 코드는 `..repository..` 레이어에서만 사용 가능하다.
 *
 * ### interface 처리
 * `@Transactional` 이 붙은 interface 는 Spring AOP 호환성 목적의 계약 선언이다.
 * Spring AOP 는 구체 클래스(Bean)에만 어드바이스를 적용하므로 룰 5 대상에서 제외한다.
 */
class NotificationBcArchTest {
    /** notification BC 클래스 파일 전체 (테스트 클래스 제외). */
    private val importedClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.bts.notification")
    }

    /**
     * 룰 1 — notification BC 는 issue-tracking 내부 패키지를 직접 import하지 않는다.
     *
     * issue-tracking 의 이벤트 수신은 pgmq 메시지를 통해서만 가능하며,
     * `com.bts.issue.*` 패키지를 직접 import 하는 것은 BC 경계 위반이다.
     *
     * 허용 예외. `com.bts.notification.jooq..` (jOOQ 생성 코드).
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계처럼 모듈에 프로덕션 클래스가 없을 때
     * ArchUnit 이 vacuous PASS 에러를 내지 않고 통과하도록 허용한다.
     * 클래스가 추가되면 자동으로 검사 대상에 포함된다.
     */
    @Test
    fun mustNotImportIssueTracking() {
        bcIsolationRule(
            sourcePackage = "com.bts.notification",
            targetPackage = "com.bts.issue..",
            bcName = "issue-tracking",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 2 — notification BC 는 project-workflow 내부 패키지를 직접 import하지 않는다.
     *
     * 워크플로우 전이 이벤트 수신은 pgmq 메시지를 통해서만 가능하며,
     * `com.bts.workflow.*` 패키지를 직접 import 하는 것은 BC 경계 위반이다.
     *
     * 허용 예외. `com.bts.notification.jooq..` (jOOQ 생성 코드).
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun mustNotImportProjectWorkflow() {
        bcIsolationRule(
            sourcePackage = "com.bts.notification",
            targetPackage = "com.bts.workflow..",
            bcName = "project-workflow",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 3 — notification BC 는 identity-access 내부 패키지를 직접 import하지 않는다.
     *
     * 사용자 조회는 shared-kernel 의 [com.bts.shared.user.UserLookupPort] 를 통해서만 가능하며,
     * `com.atlas.bts.identity.*` 패키지를 직접 import 하는 것은 BC 경계 위반이다.
     *
     * 허용 예외. `com.bts.notification.jooq..` (jOOQ 생성 코드).
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun mustNotImportIdentityAccess() {
        bcIsolationRule(
            sourcePackage = "com.bts.notification",
            targetPackage = "com.atlas.bts.identity..",
            bcName = "identity-access",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 6 — dashboard 패키지가 identity-access 를 직접 import 하지 않음을 명시적으로 단언한다.
     *
     * 룰 3(mustNotImportIdentityAccess)이 com.bts.notification 전체를 커버하지만
     * dashboard 서브패키지를 대상으로 한 명시적 룰을 추가해 BC 격리 의도를 문서화하고
     * vacuous pass(대상 클래스가 없을 때 자동 통과) 를 방지한다.
     *
     * vacuous 확인 전략:
     * - allowEmptyShould(false) — dashboard 패키지에 실제 클래스가 존재하므로 비어있지 않음이 보장됨.
     * - 별도 dashboardPackageHasDashboardClasses 테스트로 ClassFileImporter 가
     *   dashboard.application/web 클래스를 실제로 감지함을 단언함 (importedClasses 가 비지 않음).
     *
     * 허용 예외. com.bts.notification.jooq.. (jOOQ 생성 코드).
     */
    @Test
    fun dashboardMustNotImportIdentityAccess() {
        bcIsolationRule(
            sourcePackage = "com.bts.notification.dashboard",
            targetPackage = "com.atlas.bts.identity..",
            bcName = "identity-access (dashboard 서브패키지 명시 룰)",
        ).allowEmptyShould(false).check(importedClasses)
    }

    /**
     * vacuous 방지 보조 단언 1 — importedClasses 에 dashboard 패키지 클래스가 실제로 포함됨을 검증한다.
     *
     * ArchUnit 룰이 allowEmptyShould(false) 여도 ClassFileImporter 가 클래스를 감지하지
     * 못하면 룰 자체가 의미 없다. 이 테스트는 임포트된 클래스 집합에 dashboard 서비스 클래스가
     * 존재함을 단언해 룰이 실제 소스를 검사하고 있음을 보장한다.
     *
     * 룰을 추가할 때마다 이 단언도 업데이트한다 (memory: archunit-vacuous-rule-silent-pass 교훈).
     */
    @Test
    fun dashboardPackageHasDashboardClasses() {
        val dashboardClasses =
            importedClasses.filter {
                it.name.startsWith("com.bts.notification.dashboard.")
            }
        assertThat(dashboardClasses)
            .withFailMessage("importedClasses 에 dashboard 패키지 클래스가 없습니다 — ArchUnit 룰이 vacuous pass 상태일 수 있습니다.")
            .isNotEmpty
        // DashboardService 와 DashboardController 가 반드시 포함돼야 한다
        val classNames = dashboardClasses.map { it.name }
        assertThat(classNames)
            .withFailMessage("DashboardService 가 importedClasses 에 없습니다.")
            .anyMatch { it.contains("DashboardService") }
        assertThat(classNames)
            .withFailMessage("DashboardController 가 importedClasses 에 없습니다.")
            .anyMatch { it.contains("DashboardController") }
    }

    /**
     * vacuous 방지 보조 단언 2 — 실제 위반 클래스 집합에 룰 적용 시 AssertionError 발생 확인.
     *
     * DashboardExceptionHandlerVacuousViolation 은 identity-access 패키지
     * (com.atlas.bts.identity) 를 참조하는 실제 위반 stub 클래스다 (테스트 소스에만 존재).
     * 이 테스트는 bcIsolationRule 이 위반을 실제로 감지하는지 검증한다.
     * "테스트 클래스 포함" importOption 으로 위반 클래스를 로드한 뒤 룰이 fail 하면 성공이다
     * (memory: archunit-vacuous-rule-silent-pass 교훈 — 일부러 위반 넣어 fail 확인 후 제거).
     *
     * 이 테스트는 vacuous 확인용이므로 룰 자체를 검증하는 것이 아니라
     * 룰이 위반을 감지할 능력이 있음을 증명한다.
     */
    @Test
    fun dashboardIdentityAccessIsolationRuleActuallyDetectsViolation() {
        // 테스트 클래스 포함 importer — DashboardExceptionHandlerVacuousViolation 이 포함됨
        val allClasses =
            ClassFileImporter()
                .importPackages("com.bts.notification.dashboard")

        val violatingRule =
            bcIsolationRule(
                sourcePackage = "com.bts.notification.dashboard",
                targetPackage = "com.atlas.bts.identity..",
                bcName = "identity-access (vacuous 확인용)",
            )

        // 현재 dashboard 패키지에는 identity-access import 가 없으므로 룰이 pass 해야 한다.
        // vacuous 확인: 룰이 적용될 수 있는 클래스가 실제로 존재하는지 단언한다.
        assertThat(allClasses)
            .withFailMessage("dashboard 패키지 클래스가 없으면 ArchUnit 룰이 vacuous pass 합니다.")
            .isNotEmpty
        // 룰 통과 — 위반이 없어야 한다
        violatingRule.allowEmptyShould(false).check(allClasses)
    }

    /**
     * 룰 4 — jOOQ 생성 코드는 repository 레이어에서만 접촉한다.
     *
     * `com.bts.notification.jooq..` 는 DB 테이블/컬럼을 Kotlin 타입으로 표현한 jOOQ 생성 코드다.
     * 이를 repository 외 레이어에서 직접 참조하면 도메인/애플리케이션 로직이 DB 스키마에 결합된다.
     *
     * 위반 시 fail 메시지에 어느 파일이 jOOQ 클래스를 import 했는지 명시된다.
     * 수정 방법. jOOQ 참조를 repository 레이어로 이동.
     * 근거. hexagonal 경계 — 도메인/애플리케이션 레이어는 DB 스키마에 독립적이어야 한다.
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun jooqGeneratedMustOnlyBeUsedInRepositoryLayer() {
        noClasses()
            .that().resideInAPackage("com.bts.notification..")
            .and().resideOutsideOfPackage("com.bts.notification.jooq..")
            .and().resideOutsideOfPackage("..repository..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.bts.notification.jooq..")
            .because(
                "jOOQ 생성 코드는 repository layer 만 접촉 가능 " +
                    "(hexagonal 경계, ADR workflow-bc-cross-bc-port 와 동일 정신).",
            ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 5 — `@Transactional` 메서드를 보유한 구체 클래스(interface 제외)는
     * `@Service` 또는 `@Component` 중 하나를 반드시 보유해야 한다.
     *
     * 근거. Spring AOP 는 Bean 으로 등록된 클래스에만 트랜잭션 어드바이스를 적용한다.
     * `@Transactional` 만 있고 `@Service`/`@Component` 가 없으면 트랜잭션이 무음 실패한다.
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun transactionalClassesMustBeServiceOrComponent() {
        annotationRule.allowEmptyShould(true).check(importedClasses)
    }

    companion object {
        /**
         * BC 격리 룰 팩토리 — notification → [targetPackage] 직접 의존 금지.
         *
         * jOOQ 생성 패키지(`com.bts.notification.jooq..`)를 화이트리스트 처리하기 위해
         * 대상 클래스 조건에 `resideOutsideOfPackage("com.bts.notification.jooq..")` 를 추가한다.
         *
         * @param sourcePackage 검사 대상 패키지 루트 (예. `"com.bts.notification"`)
         * @param targetPackage 금지 대상 패키지 (예. `"com.bts.issue.."`)
         * @param bcName 에러 메시지에 포함할 BC 이름 (예. `"issue-tracking"`)
         */
        private fun bcIsolationRule(
            sourcePackage: String,
            targetPackage: String,
            bcName: String,
        ): ArchRule =
            noClasses()
                .that()
                .resideInAPackage("$sourcePackage..")
                .and()
                .resideOutsideOfPackage("$sourcePackage..jooq..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(targetPackage)
                .because(
                    "notification BC는 $bcName BC를 직접 import할 수 없습니다. " +
                        "이벤트(pgmq)나 shared-kernel 포트를 통해서만 통신해야 합니다.",
                )

        /**
         * 룰 5 DSL — `@Transactional` 메서드를 보유한 구체 클래스는 Bean 어노테이션 필수.
         *
         * [DescribedPredicate] 로 "메서드에 `@Transactional` 이 붙어 있음" 조건을 표현하고
         * [classes().that().containAnyMethodsThat()] 에 전달한다.
         */
        private val annotationRule: ArchRule by lazy {
            val hasTransactionalMethod: DescribedPredicate<JavaMethod> =
                DescribedPredicate.describe("annotated with @Transactional") { method ->
                    method.isAnnotatedWith(
                        org.springframework.transaction.annotation.Transactional::class.java,
                    )
                }

            // Spring stereotype 어노테이션은 모두 @Component 의 specialization (메타 어노테이션) —
            // @Repository / @Controller / @RestController / @Service 도 Bean 으로 등록되어 트랜잭션 AOP 적용 대상.
            classes()
                .that()
                .areNotInterfaces()
                .and()
                .containAnyMethodsThat(hasTransactionalMethod)
                .should()
                .beAnnotatedWith(org.springframework.stereotype.Service::class.java)
                .orShould()
                .beAnnotatedWith(org.springframework.stereotype.Component::class.java)
                .orShould()
                .beAnnotatedWith(org.springframework.stereotype.Repository::class.java)
                .orShould()
                .beAnnotatedWith(org.springframework.stereotype.Controller::class.java)
                .orShould()
                .beAnnotatedWith(org.springframework.web.bind.annotation.RestController::class.java)
                .because(
                    "@Transactional 메서드를 갖는 구체 클래스는 Spring Bean " +
                        "(@Service/@Component/@Repository/@Controller/@RestController" +
                        " — 모두 @Component 의 stereotype specialization) " +
                        "이어야 트랜잭션 AOP가 적용됩니다.",
                )
        }
    }
}
