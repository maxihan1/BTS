// project-workflow BC 아키텍처 규칙 테스트 — ArchUnit 4룰 (BC 격리 + @Transactional/@Service 동반)

package com.bts.workflow.archunit

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * project-workflow BC 아키텍처 규칙 검증.
 *
 * ArchUnit (아키텍처 규칙을 코드로 작성하고 자동 검증하는 라이브러리) 을 사용해
 * 다음 4개 규칙을 테스트 시점에 강제한다.
 *
 * - 룰 1 (@Transactional + @Service 동반) — [transactionalClassesMustBeServiceOrComponent]
 * - 룰 2 (identity-access import 금지) — [mustNotImportIdentityAccess]
 * - 룰 3 (issue-tracking import 금지) — [mustNotImportIssueTracking]
 * - 룰 4 (automation import 금지) — [mustNotImportAutomation]
 *
 * ### 화이트리스트
 * `*.jooq.tables.*` 패키지는 jOOQ 코드 생성 결과물이므로 BC 경계 룰에서 제외한다.
 * (GAP-7 / learning #91 — ADR 기록된 결정)
 *
 * ### interface 처리
 * `@Transactional` 이 붙은 interface 는 Spring AOP 호환성 목적의 계약 선언이므로
 * 룰 1 대상에서 제외한다.
 */
class ProjectWorkflowArchitectureTest {

    /** project-workflow BC 클래스 파일 전체 (테스트 클래스 제외). */
    private val importedClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.bts.workflow")
    }

    /**
     * 룰 1 — `@Transactional` 메서드를 보유한 클래스(interface 제외)는
     * `@Service` 또는 `@Component` 중 하나를 반드시 보유해야 한다.
     *
     * 근거. Spring AOP 는 Bean 으로 등록된 클래스에만 트랜잭션 어드바이스를 적용한다.
     * `@Transactional` 만 있고 `@Service`/`@Component` 가 없으면 트랜잭션이 무음 실패한다.
     */
    @Test
    fun transactionalClassesMustBeServiceOrComponent() {
        val rule = classes()
            .that()
            .areNotInterfaces()
            .and()
            .containAnyMethodsThat()
            .areAnnotatedWith(org.springframework.transaction.annotation.Transactional::class.java)
            .should()
            .beAnnotatedWith(org.springframework.stereotype.Service::class.java)
            .orShould()
            .beAnnotatedWith(org.springframework.stereotype.Component::class.java)
            .because("@Transactional 메서드를 갖는 구체 클래스는 Spring Bean(@Service 또는 @Component)이어야 트랜잭션 AOP가 적용됩니다")

        rule.check(importedClasses)
    }

    /**
     * 룰 2 — project-workflow 패키지가 identity-access BC 내부 패키지를 직접 import하지 않는다.
     *
     * 허용 예외. `com.bts.identityaccess.jooq.tables.*` (jOOQ 생성 코드) — 단 현재 미존재.
     */
    @Test
    fun mustNotImportIdentityAccess() {
        val rule = noClasses()
            .that()
            .resideInAPackage("com.bts.workflow..")
            .and()
            .haveSimpleNameNotContaining("Jooq") // jOOQ 생성 클래스 화이트리스트 (타입명 기반 방어)
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bts.identityaccess..")
            .because("project-workflow BC는 identity-access BC를 직접 import할 수 없습니다. 이벤트(pgmq)나 공개 API를 통해서만 통신해야 합니다")

        rule.check(importedClasses)
    }

    /**
     * 룰 3 — project-workflow 패키지가 issue-tracking BC 내부 패키지를 직접 import하지 않는다.
     *
     * 허용 예외. `com.bts.issuetracking.jooq.tables.*` (jOOQ 생성 코드) — 단 현재 미존재.
     */
    @Test
    fun mustNotImportIssueTracking() {
        val rule = noClasses()
            .that()
            .resideInAPackage("com.bts.workflow..")
            .and()
            .haveSimpleNameNotContaining("Jooq")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bts.issuetracking..")
            .because("project-workflow BC는 issue-tracking BC를 직접 import할 수 없습니다. 이벤트(pgmq)나 공개 API를 통해서만 통신해야 합니다")

        rule.check(importedClasses)
    }

    /**
     * 룰 4 — project-workflow 패키지가 automation BC 내부 패키지를 직접 import하지 않는다.
     *
     * 허용 예외. `com.bts.automation.jooq.tables.*` (jOOQ 생성 코드) — 단 현재 미존재.
     */
    @Test
    fun mustNotImportAutomation() {
        val rule = noClasses()
            .that()
            .resideInAPackage("com.bts.workflow..")
            .and()
            .haveSimpleNameNotContaining("Jooq")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.bts.automation..")
            .because("project-workflow BC는 automation BC를 직접 import할 수 없습니다. 이벤트(pgmq)나 공개 API를 통해서만 통신해야 합니다")

        rule.check(importedClasses)
    }
}
