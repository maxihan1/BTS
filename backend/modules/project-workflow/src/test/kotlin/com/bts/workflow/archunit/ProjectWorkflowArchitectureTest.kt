// project-workflow BC 아키텍처 규칙 테스트 — ArchUnit 4룰 (BC 격리 + @Transactional/@Service 동반)

package com.bts.workflow.archunit

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * project-workflow BC 아키텍처 규칙 검증.
 *
 * ArchUnit (아키텍처 규칙을 코드로 작성하고 자동 검증하는 라이브러리) 을 사용해
 * 다음 4개 규칙을 테스트 시점에 강제한다.
 *
 * - 룰 1 (`@Transactional` + `@Service` 동반) — [transactionalClassesMustBeServiceOrComponent]
 * - 룰 2 (identity-access 직접 import 금지) — [mustNotImportIdentityAccess]
 * - 룰 3 (issue-tracking 직접 import 금지) — [mustNotImportIssueTracking]
 * - 룰 4 (automation 직접 import 금지) — [mustNotImportAutomation]
 *
 * ### 화이트리스트 — jOOQ 생성 코드
 * `*.jooq.tables.*` 패키지는 jOOQ 코드 생성 결과물이므로 BC 경계 룰(룰 2~4)에서 제외한다.
 * BC 격리 위반이 아닌 DB 스키마 공유 목적의 코드 생성 산출물이다.
 * (GAP-7 / learning #91 — ADR 기록된 결정)
 *
 * ### interface 처리
 * `@Transactional` 이 붙은 interface 는 Spring AOP 호환성 목적의 계약 선언이다.
 * Spring AOP 는 구체 클래스(Bean)에만 어드바이스를 적용하므로 룰 1 대상에서 제외한다.
 */
class ProjectWorkflowArchitectureTest {
    /** project-workflow BC 클래스 파일 전체 (테스트 클래스 제외). */
    private val importedClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.bts.workflow")
    }

    /**
     * 룰 1 — `@Transactional` 메서드를 보유한 구체 클래스(interface 제외)는
     * `@Service` 또는 `@Component` 중 하나를 반드시 보유해야 한다.
     *
     * 근거. Spring AOP 는 Bean 으로 등록된 클래스에만 트랜잭션 어드바이스를 적용한다.
     * `@Transactional` 만 있고 `@Service`/`@Component` 가 없으면 트랜잭션이 무음 실패한다.
     */
    @Test
    fun transactionalClassesMustBeServiceOrComponent() {
        annotationRule.check(importedClasses)
    }

    /**
     * 룰 2 — project-workflow 패키지가 identity-access BC 내부 패키지를 직접 import하지 않는다.
     *
     * 허용 예외. `com.atlas.bts.identity.*.jooq.tables.*` (jOOQ 생성 코드).
     */
    @Test
    fun mustNotImportIdentityAccess() {
        bcIsolationRule(targetPackage = "com.atlas.bts.identity..", bcName = "identity-access").check(importedClasses)
    }

    /**
     * 룰 3 — project-workflow 패키지가 issue-tracking BC 내부 패키지를 직접 import하지 않는다.
     *
     * IssueTypeId / IssueTypeKey 는 PR #25 에서 shared-kernel(`com.bts.shared.issue`)로 이동되어
     * 더 이상 issue-tracking 직접 import 가 아니다. issue-tracking ↔ project-workflow 순환을
     * 회피하기 위해 예외 없이 전면 금지한다.
     */
    @Test
    fun mustNotImportIssueTracking() {
        bcIsolationRuleWithAllowedClasses(
            targetPackage = "com.bts.issue..",
            bcName = "issue-tracking",
            allowedClassNames = emptySet(),
        ).check(importedClasses)
    }

    /**
     * 룰 4 — project-workflow 패키지가 automation BC 내부 패키지를 직접 import하지 않는다.
     *
     * 허용 예외. `com.bts.automation.jooq.tables.*` (jOOQ 생성 코드).
     */
    @Test
    fun mustNotImportAutomation() {
        bcIsolationRule(targetPackage = "com.bts.automation..", bcName = "automation").check(importedClasses)
    }

    companion object {
        /**
         * 룰 1 DSL — `@Transactional` 메서드를 보유한 구체 클래스는 Bean 어노테이션 필수.
         *
         * [DescribedPredicate] 로 "메서드에 `@Transactional` 이 붙어 있음" 조건을 표현하고
         * [classes().that().containAnyMethodsThat()][com.tngtech.archunit.lang.syntax.elements.ClassesThat.containAnyMethodsThat]
         * 에 전달한다.
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
                        "(@Service/@Component/@Repository/@Controller/@RestController — 모두 @Component 의 stereotype specialization) " +
                        "이어야 트랜잭션 AOP가 적용됩니다",
                )
        }

        /**
         * BC 격리 룰 팩토리 — project-workflow → [targetPackage] 직접 의존 금지.
         *
         * jOOQ 생성 패키지(`*.jooq.tables.*`)를 화이트리스트 처리하기 위해
         * 대상 클래스 조건에 `resideOutsideOfPackage("..jooq.tables..")` 를 추가한다.
         *
         * @param targetPackage 금지 대상 패키지 (예. `"com.bts.identityaccess.."`)
         * @param bcName 에러 메시지에 포함할 BC 이름 (예. `"identity-access"`)
         */
        private fun bcIsolationRule(
            targetPackage: String,
            bcName: String,
        ): ArchRule =
            noClasses()
                .that()
                .resideInAPackage("com.bts.workflow..")
                .and()
                .resideOutsideOfPackage("com.bts.workflow..jooq.tables..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(targetPackage)
                .because(
                    "project-workflow BC는 $bcName BC를 직접 import할 수 없습니다. " +
                        "이벤트(pgmq)나 공개 API를 통해서만 통신해야 합니다",
                )

        /**
         * BC 격리 룰 팩토리 (허용 클래스 목록 포함).
         *
         * [allowedClassNames] 에 포함된 클래스는 이 BC 가 cross-BC 계약 타입으로 허용한 공개 API 이다.
         * 예: `IssueTypeId`, `IssueTypeKey` — WorkflowResolver port 계약 및 Repository JOIN 에서 사용.
         *
         * @param targetPackage 금지 대상 패키지 (예. `"com.bts.issue.."`)
         * @param bcName 에러 메시지에 포함할 BC 이름 (예. `"issue-tracking"`)
         * @param allowedClassNames 예외 허용 클래스 전체 이름 목록.
         */
        private fun bcIsolationRuleWithAllowedClasses(
            targetPackage: String,
            bcName: String,
            allowedClassNames: Set<String>,
        ): ArchRule =
            noClasses()
                .that()
                .resideInAPackage("com.bts.workflow..")
                .and()
                .resideOutsideOfPackage("com.bts.workflow..jooq.tables..")
                .should()
                .dependOnClassesThat(
                    DescribedPredicate.describe<JavaClass>("reside in $targetPackage and are not in the allow-list") { javaClass ->
                        javaClass.packageName.let { pkg ->
                            pkg.startsWith(targetPackage.removeSuffix("..")) &&
                                javaClass.name !in allowedClassNames
                        }
                    },
                )
                .because(
                    "project-workflow BC는 $bcName BC를 직접 import할 수 없습니다. " +
                        "이벤트(pgmq)나 공개 API를 통해서만 통신해야 합니다. " +
                        "예외 허용 타입: $allowedClassNames",
                )
    }
}
