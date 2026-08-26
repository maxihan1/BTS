// project-workflow BC 아키텍처 규칙 테스트 — ArchUnit 5룰 (BC 격리 + @Transactional 경계 2종)

package com.bts.workflow.archunit

import com.bts.workflow.transition.TransitionRuleRepository
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * project-workflow BC 아키텍처 규칙 검증.
 *
 * ArchUnit (아키텍처 규칙을 코드로 작성하고 자동 검증하는 라이브러리) 을 사용해
 * 다음 5개 규칙을 테스트 시점에 강제한다.
 *
 * - 룰 1 (`@Transactional` + `@Service` 동반) — [transactionalClassesMustBeServiceOrComponent]
 * - 룰 2 (identity-access 직접 import 금지) — [mustNotImportIdentityAccess]
 * - 룰 3 (issue-tracking 직접 import 금지) — [mustNotImportIssueTracking]
 * - 룰 4 (automation 직접 import 금지) — [mustNotImportAutomation]
 * - 룰 5 (전환 규칙 리포지토리는 CRUD 확장점을 `override` + `@Transactional`) —
 *   [transitionRuleRepositoriesMustOverrideCrudWithTransactional]
 *
 * ### 룰 1 과 룰 5 는 같은 위험의 **반대 방향**이다
 * 룰 1 은 「`@Transactional` 이 있는데 Bean 이 아니다」를, 룰 5 는 「Bean 인데 `@Transactional` 이
 * 붙을 자리가 없다」를 잡는다. 룰 1 만으로는 상속만 하고 `override` 를 빠뜨린 리포지토리가
 * **아무 위반 없이 초록**이다 — 그 클래스에는 `@Transactional` 메서드가 아예 없기 때문이다.
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
     *
     * ### IssueTypeRef 허용 근거 (FR-WF-02 D6 Task 1)
     *
     * `IssueTypeRef` 는 shared-kernel(`com.bts.shared.issue`) 패키지에 위치하므로
     * `com.bts.issue..` 금지 패키지 매칭 대상이 아니다. ArchUnit 룰 상 자동 통과.
     * 이 주석은 의도를 명시하기 위한 것이며 실제 룰 동작에는 영향 없다.
     *
     * - `IssueTypeLookupPort` — project-workflow 내부(`com.bts.workflow.scheme.application.port`)
     *   에 선언된 outbound interface 로, issue-tracking 이 구현을 제공한다.
     *   issue-tracking 에서 이를 import 하면 IssueBcArchTest 금지 목록에 해당 패키지가 없으므로 룰 통과.
     *
     * 결정 근거: docs/adr/2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup.md
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

    /**
     * 룰 5 — [TransitionRuleRepository] 를 상속한 클래스는 기반 클래스의 CRUD 확장점을
     * **자기 클래스에** `override` 로 선언하고, 그 전부에 `@Transactional` 이 붙어 있어야 한다.
     *
     * 근거. kotlin-spring(all-open)은 `@Repository` 가 붙은 **그 클래스**의 멤버만 열고 상위를
     * 거슬러 열지 않는다. 상속만 하고 `override` 를 빠뜨리면 Spring CGLIB 프록시가 그 메서드를
     * 재정의하지 못해 **트랜잭션이 조용히 사라진다**. 기반 클래스 KDoc 이 이 위험을 문장으로
     * 적어 두었으나 그것을 지키는 검사는 0건이었다.
     *
     * 무음인 이유. 빠뜨려도 컴파일이 통과하고, 룰 1 은 「`@Transactional` 메서드가 있으면
     * stereotype 필수」라는 **반대 방향**이라 메서드가 아예 없는 클래스를 그냥 지나친다.
     * 통합 테스트도 초록이다 — 커밋이 성공하는지가 아니라 값이 맞는지만 보기 때문이다.
     * 세 번째 전환 규칙 테이블을 나중에 얹으며 `override` 를 빠뜨리면 전부 초록인 채
     * 트랜잭션 경계만 사라진다.
     *
     * ### 확장점 목록을 손으로 들지 않는다
     * 「검사할 메서드 4개」를 여기 적으면 그것이 기반 클래스와 갈리는 두 번째 목록이 되고,
     * 두 목록은 서로를 검사하지 않는다. 기반 클래스의 **public + non-final(= `open`)** 선언
     * 메서드를 실행 시점에 읽어 확장점으로 삼는다 — 기반에 CRUD 를 하나 더 얹으면 하위 클래스의
     * `override` 의무도 자동으로 따라온다.
     *
     * ### 비-공허 짝
     * 상속 클래스가 0개면 이 룰은 아무것도 안 지키면서 초록이다. 그래서 룰을 걸기 **전에**
     * 대상이 실재하는지 먼저 잰다.
     */
    @Test
    fun transitionRuleRepositoriesMustOverrideCrudWithTransactional() {
        val subclasses =
            importedClasses.filter {
                it.isAssignableTo(TransitionRuleRepository::class.java) &&
                    it.name != TransitionRuleRepository::class.java.name
            }
        assertThat(subclasses)
            .describedAs(
                "TransitionRuleRepository 를 상속한 클래스를 0개 찾았다 — 룰 5 가 공허하게 통과한다. " +
                    "기반 클래스를 지웠거나 패키지를 옮겼다면 이 룰도 함께 정리할 것",
            )
            .isNotEmpty()

        transitionRuleOverrideRule.check(importedClasses)
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

        /**
         * 룰 5 DSL — [TransitionRuleRepository] 하위 클래스는 CRUD 확장점을 전부 재정의해야 한다.
         *
         * 기반 클래스 자신은 대상에서 뺀다. 추상 기반은 Bean 이 아니라서 어드바이스가 붙지 않고,
         * 거기에 `@Transactional` 을 달면 룰 1 위반이다 (경계는 구체 클래스가 갖는다).
         */
        private val transitionRuleOverrideRule: ArchRule by lazy {
            classes()
                .that()
                .areAssignableTo(TransitionRuleRepository::class.java)
                .and()
                .doNotHaveFullyQualifiedName(TransitionRuleRepository::class.java.name)
                .should(overrideEveryExtensionPointWithTransactional())
                .because(
                    "kotlin-spring(all-open)은 @Repository 가 붙은 그 클래스의 멤버만 엽니다. " +
                        "기반 CRUD 를 상속만 하면 CGLIB 프록시가 재정의하지 못해 " +
                        "@Transactional 이 무음 실패합니다",
                )
        }

        /**
         * 메서드 동일성 판정 키 — 이름 + 파라미터 타입.
         *
         * 이름만 보면 인자가 다른 오버로드를 `override` 로 오인한다.
         */
        private fun signatureOf(method: JavaMethod): String =
            method.rawParameterTypes.joinToString(", ", "${method.name}(", ")") { it.simpleName }

        /**
         * 하위 클래스가 재정의할 수 있는 기반 메서드 = public + non-final + non-static.
         *
         * 컴파일러가 만든 합성·브리지 메서드는 사람이 `override` 로 적을 수 있는 대상이 아니므로 뺀다.
         */
        private fun isExtensionPoint(method: JavaMethod): Boolean =
            JavaModifier.PUBLIC in method.modifiers &&
                JavaModifier.FINAL !in method.modifiers &&
                JavaModifier.STATIC !in method.modifiers &&
                JavaModifier.SYNTHETIC !in method.modifiers &&
                JavaModifier.BRIDGE !in method.modifiers

        /**
         * 기반 클래스의 확장점을 하위 클래스가 전부 `override` 했고 그 전부에 `@Transactional` 이
         * 붙었는지 본다. 확장점 목록은 상속 사슬에서 기반 클래스를 찾아 **실행 시점에** 읽는다.
         */
        private fun overrideEveryExtensionPointWithTransactional(): ArchCondition<JavaClass> =
            object : ArchCondition<JavaClass>(
                "TransitionRuleRepository 의 CRUD 확장점을 전부 override 하고 @Transactional 을 붙인다",
            ) {
                override fun check(
                    item: JavaClass,
                    events: ConditionEvents,
                ) {
                    val base =
                        item.allRawSuperclasses.firstOrNull {
                            it.name == TransitionRuleRepository::class.java.name
                        }
                    if (base == null) {
                        events.add(
                            SimpleConditionEvent.violated(
                                item,
                                "${item.simpleName}: 상속 사슬에서 TransitionRuleRepository 를 찾지 못했습니다",
                            ),
                        )
                        return
                    }

                    // 비-공허 짝 그 둘째 — 확장점을 0개 뽑으면 아래 루프가 한 번도 돌지 않아
                    // 위반이 0건이 되고, 룰은 아무것도 안 지키면서 초록이 된다.
                    val extensionPoints = base.methods.filter(::isExtensionPoint)
                    if (extensionPoints.isEmpty()) {
                        events.add(
                            SimpleConditionEvent.violated(
                                item,
                                "${base.simpleName} 에서 재정의 대상 메서드를 0개 뽑았습니다 — " +
                                    "기반 CRUD 가 public + non-final 인지 확인하세요. 이 룰이 공허하게 통과합니다",
                            ),
                        )
                        return
                    }

                    val declared = item.methods.associateBy(::signatureOf)
                    for (extensionPoint in extensionPoints) {
                        val signature = signatureOf(extensionPoint)
                        val override = declared[signature]
                        val message =
                            when {
                                override == null ->
                                    "${item.simpleName}.$signature 가 없습니다 — 기반 구현을 상속만 하면 " +
                                        "CGLIB 프록시가 재정의하지 못해 @Transactional 이 무음 실패합니다. " +
                                        "override + @Transactional 로 super 에 위임하세요"

                                !override.isAnnotatedWith(
                                    org.springframework.transaction.annotation.Transactional::class.java,
                                ) ->
                                    "${item.simpleName}.$signature 에 @Transactional 이 없습니다 — " +
                                        "트랜잭션 경계는 기반 클래스가 아니라 이 구체 클래스가 가져야 합니다"

                                else -> null
                            }
                        events.add(
                            if (message == null) {
                                SimpleConditionEvent.satisfied(item, "${item.simpleName}.$signature 정상")
                            } else {
                                SimpleConditionEvent.violated(item, message)
                            },
                        )
                    }
                }
            }
    }
}
