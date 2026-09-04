// agile-planning BC 아키텍처 격리 규칙 검증 — ArchUnit 7룰
// (cross-BC 직접 import 금지 + jOOQ 화이트리스트 + @Transactional/@Service 동반 + vacuous 방어 카운트)

package com.bts.agileplanning.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * agile-planning BC 아키텍처 규칙 검증.
 *
 * ArchUnit(아키텍처 규칙을 코드로 작성하고 자동 검증하는 라이브러리)을 사용해
 * 다음 6개 규칙을 테스트 시점에 강제한다(룰 6 은 비-공허 카운트 가드).
 *
 * - 룰 1 (BC 격리 — issue-tracking 직접 import 금지) — [mustNotImportIssueTracking]
 * - 룰 2 (BC 격리 — project-workflow 직접 import 금지) — [mustNotImportProjectWorkflow]
 * - 룰 3 (BC 격리 — identity-access 직접 import 금지) — [mustNotImportIdentityAccess]
 * - 룰 4 (jOOQ 화이트리스트 — repository 레이어만 접촉) — [jooqGeneratedMustOnlyBeUsedInRepositoryLayer]
 * - 룰 5 (@Transactional + @Service/@Component 동반) — [transactionalClassesMustBeServiceOrComponent]
 * - 룰 7 (계층 역전 금지 — repository → application 의존 금지) — [repositoryLayerMustNotDependOnApplicationLayer]
 *
 * ### 통신 허용 채널
 * agile-planning BC는 이벤트(pgmq)와 shared-kernel 포트(`com.bts.shared.*`)를 통해서만
 * 다른 BC와 통신한다. issue-tracking / project-workflow / identity-access 내부 패키지를
 * 직접 import하는 것은 BC 경계 위반이다.
 *
 * ### 패키지 prefix 주의사항
 * identity-access BC는 다른 BC와 패키지 prefix가 다르다.
 * - issue-tracking: `com.bts.issue..`
 * - project-workflow: `com.bts.workflow..`
 * - identity-access: `com.atlas.bts.identity..` (다른 BC와 prefix 다름)
 *
 * ### 화이트리스트 — jOOQ 생성 코드
 * `com.bts.agileplanning.jooq..`는 jOOQ 코드 생성 결과물이므로 BC 경계 룰에서 제외한다.
 * jOOQ 생성 코드는 `..repository..` 레이어에서만 사용 가능하다.
 *
 * ### interface 처리
 * `@Transactional`이 붙은 interface는 Spring AOP 호환성 목적의 계약 선언이다.
 * Spring AOP는 구체 클래스(Bean)에만 어드바이스를 적용하므로 룰 5 대상에서 제외한다.
 */
class AgilePlanningBcArchTest {
    /** agile-planning BC 클래스 파일 전체 (테스트 클래스 제외). */
    private val importedClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.bts.agileplanning")
    }

    /**
     * 룰 1 — agile-planning BC는 issue-tracking 내부 패키지를 직접 import하지 않는다.
     *
     * issue-tracking과의 통신은 shared-kernel 포트([com.bts.shared.board.BoardIssueLookupPort],
     * [com.bts.shared.board.IssueTransitionPort])를 통해서만 가능하며,
     * `com.bts.issue.*` 패키지를 직접 import하는 것은 BC 경계 위반이다.
     *
     * 허용 예외. `com.bts.agileplanning.jooq..` (jOOQ 생성 코드).
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계처럼 모듈에 프로덕션 클래스가 없을 때
     * ArchUnit이 vacuous PASS 에러를 내지 않고 통과하도록 허용한다.
     * 클래스가 추가되면 자동으로 검사 대상에 포함된다.
     */
    @Test
    fun mustNotImportIssueTracking() {
        bcIsolationRule(
            sourcePackage = "com.bts.agileplanning",
            targetPackage = "com.bts.issue..",
            bcName = "issue-tracking",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 2 — agile-planning BC는 project-workflow 내부 패키지를 직접 import하지 않는다.
     *
     * 워크플로우 상태 조회는 shared-kernel 포트([com.bts.shared.workflow.WorkflowStateCatalog])를
     * 통해서만 가능하며, `com.bts.workflow.*` 패키지를 직접 import하는 것은 BC 경계 위반이다.
     *
     * 허용 예외. `com.bts.agileplanning.jooq..` (jOOQ 생성 코드).
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun mustNotImportProjectWorkflow() {
        bcIsolationRule(
            sourcePackage = "com.bts.agileplanning",
            targetPackage = "com.bts.workflow..",
            bcName = "project-workflow",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 3 — agile-planning BC는 identity-access 내부 패키지를 직접 import하지 않는다.
     *
     * 사용자/권한 조회는 shared-kernel 포트를 통해서만 가능하며,
     * `com.atlas.bts.identity.*` 패키지를 직접 import하는 것은 BC 경계 위반이다.
     * identity-access는 다른 BC(`com.bts.*`)와 달리 `com.atlas.bts.identity..` prefix를 사용한다.
     *
     * 허용 예외. `com.bts.agileplanning.jooq..` (jOOQ 생성 코드).
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun mustNotImportIdentityAccess() {
        bcIsolationRule(
            sourcePackage = "com.bts.agileplanning",
            targetPackage = "com.atlas.bts.identity..",
            bcName = "identity-access",
        ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 4 — jOOQ 생성 코드는 repository 레이어에서만 접촉한다.
     *
     * `com.bts.agileplanning.jooq..`는 DB 테이블/컬럼을 Kotlin 타입으로 표현한 jOOQ 생성 코드다.
     * 이를 repository 외 레이어에서 직접 참조하면 도메인/애플리케이션 로직이 DB 스키마에 결합된다.
     *
     * 위반 시 fail 메시지에 어느 파일이 jOOQ 클래스를 import했는지 명시된다.
     * 수정 방법. jOOQ 참조를 repository 레이어로 이동.
     * 근거. hexagonal 경계 — 도메인/애플리케이션 레이어는 DB 스키마에 독립적이어야 한다.
     *
     * [allowEmptyShould] true — 초기 부트스트랩 단계에서 빈 모듈 vacuous 오류 방지.
     */
    @Test
    fun jooqGeneratedMustOnlyBeUsedInRepositoryLayer() {
        noClasses()
            .that().resideInAPackage("com.bts.agileplanning..")
            .and().resideOutsideOfPackage("com.bts.agileplanning.jooq..")
            .and().resideOutsideOfPackage("..repository..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.bts.agileplanning.jooq..")
            .because(
                "jOOQ 생성 코드는 repository layer만 접촉 가능 " +
                    "(hexagonal 경계, BC 격리 정책과 동일 정신).",
            ).allowEmptyShould(true).check(importedClasses)
    }

    /**
     * 룰 5 — `@Transactional` 메서드를 보유한 구체 클래스(interface 제외)는
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
     * 룰 6 (vacuous 방어) — agile-planning BC에 실제로 스캔된 프로덕션 클래스가 1개 이상이어야 한다.
     *
     * 이 단언이 없으면 룰 1~5의 noClasses().that()...should() 형태의 룰들이
     * 스캔 대상이 비어 있을 때 아무것도 검사하지 않고 조용히 통과하는 vacuous PASS 상태가 된다.
     * (메모리 archunit-vacuous-rule-silent-pass)
     *
     * Sprint 클래스들(SprintApplicationService, SprintRepository 등)이 추가된 현재
     * importedClasses에는 여러 클래스가 포함되어야 한다.
     * 이 단언이 통과한다는 것 자체가 위의 룰들이 vacuous가 아님을 보증한다.
     *
     * RED 확인 방법. minimumCount를 실제 클래스 수보다 큰 값으로 설정하면 실패한다.
     * GREEN 상태. minimumCount=1로 설정해 Sprint 클래스 추가 이후 영구 통과를 보증한다.
     *
     * codereview 체크포인트 — cross-BC 권한 resolver 우회 금지.
     * ArchUnit으로 강제하기 어려운 패턴이므로 코드 리뷰 시 수동 확인 필요.
     * agile-planning BC가 타 BC의 권한 모델(멤버십 role 등)을 직접 조회하면 안 된다.
     * 반드시 IssuePermissionResolver 같은 shared-kernel 포트를 통해 권한을 위임해야 한다.
     * (메모리 crossbc-permission-resolver-not-role-lookup)
     */
    @Test
    fun agileProductionClassCountIsAtLeastOne() {
        // Sprint 클래스들이 추가된 이후로는 항상 1 이상이어야 한다.
        // 이 단언이 통과 = 룰 1~5가 vacuous PASS가 아님을 보증한다.
        val minimumCount = 1
        val actualCount: Int = importedClasses.size
        assertThat(actualCount)
            .`as`(
                "agile-planning BC 프로덕션 클래스 수(%d개)가 %d 이상이어야 함 " +
                    "— 이 룰이 vacuous PASS가 아님을 보증하는 카운트 가드",
                actualCount,
                minimumCount,
            )
            .isGreaterThanOrEqualTo(minimumCount)

        // SprintApplicationService 가 실제로 스캔됐는지 명시 단언.
        // Board* 클래스만 있어도 size>=1을 통과하던 vacuous green을 차단한다.
        // (codereview NIT #4 — agileProductionClassCountIsAtLeastOne 강화)
        val sprintServicePresent =
            importedClasses.any { it.name == "com.bts.agileplanning.application.SprintApplicationService" }
        assertThat(sprintServicePresent)
            .`as`(
                "SprintApplicationService가 importedClasses에 존재해야 함 " +
                    "— Sprint 클래스가 실제로 스캔됨을 보증하는 명시 가드",
            )
            .isTrue()
    }

    /**
     * 룰 7 — repository 레이어는 application 레이어에 의존하지 않는다.
     *
     * 계층 방향은 web → application → domain ← repository 다. repository 가 application 을
     * import 하면 **인프라가 애플리케이션을 끌어안는 역전**이 되고, 그 뒤로는 「리포지토리만 쓰는
     * 배치·이벤트 소비자」가 애플리케이션 서비스까지 끌고 와야 컴파일된다.
     *
     * 실제로 부채 177 에서 [com.bts.agileplanning.repository.BoardRepository] 가
     * `application.WipLimitChange` 를 import 한 채 머지 직전까지 갔다(코드리뷰 CONCERNS C4).
     * 룰 4 가 jOOQ 축만 보고 있어 **이 축에는 판별자가 아예 없었다** — 되돌려 놓아도 전부 초록이었다.
     * 처방은 그 타입을 `domain` 으로 내리고 이 룰을 세우는 것이다.
     *
     * 공유가 필요한 타입은 `domain` 에 둔다 — 양쪽이 아래를 함께 보는 것은 계층 역전과 다르다.
     */
    @Test
    fun repositoryLayerMustNotDependOnApplicationLayer() {
        // 비-공허 짝 — repository 패키지가 비면 아래 noClasses 룰은 아무것도 검사하지 않고 통과한다.
        val repositoryClassCount =
            importedClasses.count { it.packageName.startsWith("com.bts.agileplanning.repository") }
        assertThat(repositoryClassCount)
            .`as`("repository 레이어 클래스가 1개 이상이어야 이 룰이 공허하지 않다")
            .isGreaterThanOrEqualTo(1)

        noClasses()
            .that().resideInAPackage("com.bts.agileplanning.repository..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.bts.agileplanning.application..")
            .because(
                "계층 역전 금지 — repository 는 domain 만 본다. " +
                    "양쪽이 공유할 타입은 application 이 아니라 domain 에 둔다.",
            ).check(importedClasses)
    }

    companion object {
        /**
         * BC 격리 룰 팩토리 — agile-planning → [targetPackage] 직접 의존 금지.
         *
         * jOOQ 생성 패키지(`com.bts.agileplanning.jooq..`)를 화이트리스트 처리하기 위해
         * 대상 클래스 조건에 `resideOutsideOfPackage("com.bts.agileplanning.jooq..")` 를 추가한다.
         *
         * @param sourcePackage 검사 대상 패키지 루트 (예. `"com.bts.agileplanning"`)
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
                    "agile-planning BC는 $bcName BC를 직접 import할 수 없습니다. " +
                        "이벤트(pgmq)나 shared-kernel 포트를 통해서만 통신해야 합니다.",
                )

        /**
         * 룰 5 DSL — `@Transactional` 메서드를 보유한 구체 클래스는 Bean 어노테이션 필수.
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

            // Spring stereotype 어노테이션은 모두 @Component의 specialization(메타 어노테이션) —
            // @Repository / @Controller / @RestController / @Service도 Bean으로 등록되어 트랜잭션 AOP 적용 대상.
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
