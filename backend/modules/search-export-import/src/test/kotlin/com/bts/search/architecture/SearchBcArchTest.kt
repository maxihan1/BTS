// search-export-import BC 아키텍처 격리 규칙 검증 — ArchUnit 6룰
// (cross-BC 직접 import 금지 5종 + jOOQ 직접 의존 금지 + vacuous 방어 카운트)

package com.bts.search.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * search-export-import BC 아키텍처 규칙 검증.
 *
 * ArchUnit(아키텍처 규칙을 코드로 작성하고 자동 검증하는 라이브러리)을 사용해
 * 다음 6개 규칙을 테스트 시점에 강제한다.
 *
 * - 룰 1 (BC 격리 — issue-tracking 직접 import 금지) — [mustNotImportIssueTracking]
 * - 룰 2 (BC 격리 — project-workflow 직접 import 금지) — [mustNotImportProjectWorkflow]
 * - 룰 3 (BC 격리 — identity-access 직접 import 금지) — [mustNotImportIdentityAccess]
 * - 룰 4 (BC 격리 — agile-planning 직접 import 금지) — [mustNotImportAgilePlanning]
 * - 룰 5 (BC 격리 — notification 직접 import 금지) — [mustNotImportNotification]
 * - 룰 6 (타 BC jOOQ 직접 의존 금지 — 자체 com.bts.search.jooq는 허용) — [mustNotDependOnJooqInternals]
 * - 룰 7 (@Transactional + @Service/@Component 동반) — [transactionalClassesMustBeServiceOrComponent]
 *
 * ### 통신 허용 채널
 * search-export-import BC는 shared-kernel 포트(`com.bts.shared.*`)를 통해서만
 * 다른 BC와 통신한다. 타 BC 내부 패키지를 직접 import하는 것은 BC 경계 위반이다.
 *
 * ### jOOQ 의존 규칙
 * FR-SR-03부터 search는 자체 테이블(saved_filters)·자체 jOOQ(`com.bts.search.jooq`)를 보유하므로
 * 자체 jooq 의존은 허용한다. 단 타 BC의 jooq(예: issue-tracking `com.bts.issue.jooq`) 직접 의존은
 * BC 경계 위반이다 — AST → jOOQ Condition 변환은 issue-tracking 어댑터(IssueSearchAdapter)가 담당하고,
 * search BC는 shared-kernel `IssueSearchPort`만 호출한다(ADR 2026-06-25-fr-sr-02-aql-parser-and-bc).
 *
 * ### 패키지 prefix 주의사항
 * identity-access BC는 다른 BC와 패키지 prefix가 다르다.
 * - issue-tracking: `com.bts.issue..`
 * - project-workflow: `com.bts.workflow..`
 * - identity-access: `com.atlas.bts.identity..` (다른 BC와 prefix 다름)
 * - agile-planning: `com.bts.agileplanning..`
 * - notification: `com.bts.notification..`
 */
class SearchBcArchTest {
    /** search-export-import BC 클래스 파일 전체 (테스트 클래스 제외). */
    private val importedClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.bts.search")
    }

    /**
     * 룰 1 — search BC는 issue-tracking 내부 패키지를 직접 import하지 않는다.
     *
     * 이슈 데이터 조회는 shared-kernel 포트([com.bts.shared.search.IssueSearchPort])를
     * 통해서만 가능하며, `com.bts.issue.*` 패키지를 직접 import하는 것은 BC 경계 위반이다.
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계처럼 모듈에 프로덕션 클래스가 없을 때
     * ArchUnit이 vacuous PASS 에러를 내지 않고 통과하도록 허용한다.
     */
    @Test
    fun mustNotImportIssueTracking() {
        bcIsolationRule(
            targetPackage = "com.bts.issue..",
            bcName = "issue-tracking",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 2 — search BC는 project-workflow 내부 패키지를 직접 import하지 않는다.
     *
     * `com.bts.workflow.*` 패키지를 직접 import하는 것은 BC 경계 위반이다.
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun mustNotImportProjectWorkflow() {
        bcIsolationRule(
            targetPackage = "com.bts.workflow..",
            bcName = "project-workflow",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 3 — search BC는 identity-access 내부 패키지를 직접 import하지 않는다.
     *
     * `com.atlas.bts.identity.*` 패키지를 직접 import하는 것은 BC 경계 위반이다.
     * identity-access는 다른 BC(`com.bts.*`)와 달리 `com.atlas.bts.identity..` prefix를 사용한다.
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun mustNotImportIdentityAccess() {
        bcIsolationRule(
            targetPackage = "com.atlas.bts.identity..",
            bcName = "identity-access",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 4 — search BC는 agile-planning 내부 패키지를 직접 import하지 않는다.
     *
     * `com.bts.agileplanning.*` 패키지를 직접 import하는 것은 BC 경계 위반이다.
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun mustNotImportAgilePlanning() {
        bcIsolationRule(
            targetPackage = "com.bts.agileplanning..",
            bcName = "agile-planning",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 5 — search BC는 notification 내부 패키지를 직접 import하지 않는다.
     *
     * `com.bts.notification.*` 패키지를 직접 import하는 것은 BC 경계 위반이다.
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun mustNotImportNotification() {
        bcIsolationRule(
            targetPackage = "com.bts.notification..",
            bcName = "notification",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 6 — search BC는 **타 BC의** `..jooq..` 패키지를 직접 의존하지 않는다.
     *
     * FR-SR-03(저장된 필터)부터 search-export-import는 자체 DB 테이블(saved_filters)과
     * 자체 jOOQ 생성 코드(`com.bts.search.jooq`)를 보유한다. 따라서 **자체 jooq 의존은 허용**한다.
     * 그러나 타 BC의 jooq(issue-tracking `com.bts.issue.jooq` 등)에 직접 의존하는 것은
     * 여전히 BC 경계 위반이다 — AQL의 AST → jOOQ Condition 변환은 issue-tracking 어댑터가 전담하고,
     * search BC는 shared-kernel `IssueSearchPort`만 호출한다(ADR 2026-06-25 §D2).
     *
     * 구현 — `..jooq..` 중 `com.bts.search.jooq..`(자체)를 제외한 패키지 의존을 금지한다.
     *
     * [allowEmptyShould] true — vacuous 오류 방지.
     */
    @Test
    fun mustNotDependOnJooqInternals() {
        // `com.bts..jooq..` = 타 BC 생성 jooq(com.bts.issue.jooq 등). org.jooq 라이브러리와
        // 자체 com.bts.search.jooq는 매칭/제외되어 허용된다.
        noClasses()
            .that().resideInAPackage("com.bts.search..")
            .should().dependOnClassesThat(
                resideInAnyPackage("com.bts..jooq..")
                    .and(resideOutsideOfPackages("com.bts.search.jooq..")),
            ).because(
                "search-export-import BC는 자체 jooq(com.bts.search.jooq)와 jOOQ 라이브러리(org.jooq)만 사용한다. " +
                    "타 BC의 생성 jOOQ(예: com.bts.issue.jooq) 직접 의존은 금지 — " +
                    "AST → jOOQ 변환은 issue-tracking IssueSearchAdapter가 담당하고 " +
                    "search BC는 shared-kernel IssueSearchPort만 호출한다 " +
                    "(ADR 2026-06-25-fr-sr-02-aql-parser-and-bc §D2).",
            ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 7 — `@Transactional` 메서드를 보유한 구체 클래스(interface 제외)는
     * `@Service` 또는 `@Component` 중 하나를 반드시 보유해야 한다.
     *
     * 근거. Spring AOP는 Bean으로 등록된 클래스에만 트랜잭션 어드바이스를 적용한다.
     * `@Transactional`만 있고 `@Service`/`@Component`가 없으면 트랜잭션이 무음 실패한다.
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun transactionalClassesMustBeServiceOrComponent() {
        annotationRule.allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 8 (vacuous 방어) — search BC에 실제로 스캔된 프로덕션 클래스가 1개 이상이어야 한다.
     *
     * 이 단언이 없으면 룰 1~7의 noClasses().that()...should() 형태의 룰들이
     * 스캔 대상이 비어 있을 때 아무것도 검사하지 않고 조용히 통과하는 vacuous PASS 상태가 된다.
     * (메모리 archunit-vacuous-rule-silent-pass)
     *
     * SearchModuleConfig가 추가된 이후 importedClasses에는 1개 이상의 클래스가 포함되어야 한다.
     * 이 단언이 통과한다는 것 자체가 위의 룰들이 vacuous가 아님을 보증한다.
     */
    @Test
    fun searchProductionClassCountIsAtLeastOne() {
        val minimumCount = 1
        val actualCount: Int = importedClasses.size
        assertThat(actualCount)
            .`as`(
                "search-export-import BC 프로덕션 클래스 수(%d개)가 %d 이상이어야 함 " +
                    "— 이 룰이 vacuous PASS가 아님을 보증하는 카운트 가드",
                actualCount,
                minimumCount,
            )
            .isGreaterThanOrEqualTo(minimumCount)

        // SearchModuleConfig가 실제로 스캔됐는지 명시 단언.
        // 다른 클래스만 있어도 size>=1을 통과하던 vacuous green을 차단한다.
        val configPresent =
            importedClasses.any { it.name == "com.bts.search.SearchModuleConfig" }
        assertThat(configPresent)
            .`as`(
                "SearchModuleConfig가 importedClasses에 존재해야 함 " +
                    "— search 모듈 설정 클래스가 실제로 스캔됨을 보증하는 명시 가드",
            )
            .isTrue()
    }

    companion object {
        /**
         * BC 격리 룰 팩토리 — search BC → [targetPackage] 직접 의존 금지.
         *
         * search-export-import는 자체 jOOQ 코드가 없으므로 jOOQ 화이트리스트 처리가 불필요하다.
         *
         * @param targetPackage 금지 대상 패키지 (예. `"com.bts.issue.."`)
         * @param bcName 에러 메시지에 포함할 BC 이름 (예. `"issue-tracking"`)
         */
        private fun bcIsolationRule(
            targetPackage: String,
            bcName: String,
        ): ArchRule =
            noClasses()
                .that()
                .resideInAPackage("com.bts.search..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage(targetPackage)
                .because(
                    "search-export-import BC는 $bcName BC를 직접 import할 수 없습니다. " +
                        "shared-kernel 포트(IssueSearchPort 등)를 통해서만 통신해야 합니다.",
                )

        /**
         * 룰 7 DSL — `@Transactional` 메서드를 보유한 구체 클래스는 Bean 어노테이션 필수.
         *
         * [DescribedPredicate]로 "메서드에 `@Transactional`이 붙어 있음" 조건을 표현하고
         * [classes().that().containAnyMethodsThat()]에 전달한다.
         */
        private val annotationRule: ArchRule by lazy {
            val hasTransactionalMethod: DescribedPredicate<JavaMethod> =
                DescribedPredicate.describe("annotated with @Transactional") { method ->
                    method.isAnnotatedWith(
                        org.springframework.transaction.annotation.Transactional::class.java,
                    )
                }

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
                        " — 모두 @Component의 stereotype specialization) " +
                        "이어야 트랜잭션 AOP가 적용됩니다.",
                )
        }
    }
}
