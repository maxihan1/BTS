// issue-tracking BC 아키텍처 규칙 테스트 — Rule 1 BC 격리 + Rule 2 jOOQ 화이트리스트

package com.bts.issue.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * issue-tracking BC 아키텍처 규칙 검증.
 *
 * ArchUnit (아키텍처 규칙을 코드로 작성하고 자동 검증하는 라이브러리) 을 사용해
 * 다음 2개 규칙을 테스트 시점에 강제한다.
 *
 * - 룰 1 (BC 격리) — [issueBcMustNotDependOnForbiddenWorkflowPackages]
 * - 룰 2 (jOOQ 화이트리스트) — [jooqGeneratedMustOnlyBeUsedInRepositoryLayer]
 *
 * ### 룰 1 — project-workflow 허용 패키지
 * `com.bts.workflow.port.inbound..` 과 `com.bts.workflow.domain.dto..` 만 허용.
 * 그 외 project-workflow 내부 패키지 (domain.exception, domain.spi, application, infrastructure,
 * adapter, engine, repository) 는 직접 import 금지.
 * 근거. CLAUDE.md §BC 격리 + ADR 2026-05-21-workflow-bc-cross-bc-port +
 * ADR 2026-05-26-workflow-transition-port-result-sealed.
 *
 * ### 룰 2 — jOOQ 화이트리스트
 * `com.bts.issue.jooq.generated..` (jOOQ 코드 생성 결과물) 는 `com.bts.issue.repository..` 에서만
 * import 가능하다. application / domain / adapter / event / port / web 등 다른 레이어에서
 * jOOQ 생성 코드를 직접 참조하면 hexagonal 경계(도메인 독립성) 를 침범한다.
 * 근거. ADR 2026-05-21-workflow-bc-cross-bc-port 와 동일 정신 (포트-어댑터 경계 준수).
 *
 * ### 클래스 로더 캐시
 * [importedClasses] 는 lazy 로 한 번만 import 한 뒤 두 룰에서 공유한다.
 * `ImportOption.DoNotIncludeTests()` 를 적용해 테스트 클래스 자신의 패키지를 검사 대상에서 제외한다.
 */
class IssueBcArchTest {
    /**
     * issue-tracking BC production 클래스 파일 전체 (테스트 클래스 제외).
     *
     * lazy 로 초기화해 두 테스트 메서드 모두 동일 ClassFileImporter 결과를 재사용한다.
     * 이를 통해 JVM 내 ClassFileImporter 호출이 1회로 줄어 NFR-1 (< 2s per 룰) 에 기여한다.
     */
    private val importedClasses by lazy {
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.bts.issue")
    }

    /**
     * 룰 1 — issue-tracking BC 는 project-workflow 의 허용 패키지만 참조한다.
     *
     * **허용 패키지.**
     * - `com.bts.workflow.port.inbound..` — WorkflowTransitionPort (인바운드 포트 계약)
     * - `com.bts.workflow.domain.dto..` — TransitionRequest / TransitionPlan / TransitionResult (데이터 전달 객체)
     *
     * **금지 패키지 (위반 시 fail).**
     * - `com.bts.workflow.domain.exception..` — WorkflowValidatorFailureException 등 내부 예외
     * - `com.bts.workflow.domain.spi..` — SPI 내부 인터페이스
     * - `com.bts.workflow.application..` — 애플리케이션 서비스 내부
     * - `com.bts.workflow.infrastructure..` — 인프라 어댑터 내부
     * - `com.bts.workflow.adapter..` — 어댑터 내부
     * - `com.bts.workflow.engine..` — 워크플로우 엔진 내부
     * - `com.bts.workflow.repository..` — 리포지토리 내부
     *
     * 위반 시 fail 메시지에 어느 파일이 어느 클래스를 import 했는지 명시된다.
     * 수정 방법. ADR 2026-05-21-workflow-bc-cross-bc-port 와
     * ADR 2026-05-26-workflow-transition-port-result-sealed 참조.
     *
     * jOOQ 생성 코드 (`com.bts.issue.jooq..`) 는 검사 대상 제외
     * (self-eng-review P5 보강 — jOOQ 코드 생성 결과물은 BC 경계 룰 적용 대상 아님).
     */
    @Test
    fun issueBcMustNotDependOnForbiddenWorkflowPackages() {
        val rule =
            noClasses()
                .that().resideInAPackage("com.bts.issue..")
                .and().resideOutsideOfPackage("com.bts.issue.jooq..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "com.bts.workflow.domain.exception..",
                    "com.bts.workflow.domain.spi..",
                    "com.bts.workflow.application..",
                    "com.bts.workflow.infrastructure..",
                    "com.bts.workflow.adapter..",
                    "com.bts.workflow.engine..",
                    "com.bts.workflow.repository..",
                )
                .because(
                    "issue-tracking BC 는 project-workflow 의 port.inbound + domain.dto 만 허용 " +
                        "(CLAUDE.md §BC 격리 + ADR workflow-bc-cross-bc-port + " +
                        "ADR workflow-transition-port-result-sealed). " +
                        "jOOQ generated 코드 (com.bts.issue.jooq..) 는 검사 대상 제외 (self-eng-review P5 보강).",
                )

        rule.check(importedClasses)
    }

    /**
     * 룰 2 — jOOQ 생성 코드는 repository 레이어에서만 접촉한다.
     *
     * `com.bts.issue.jooq.generated..` 는 DB 테이블/컬럼을 Kotlin 타입으로 표현한 생성 코드다.
     * 이를 repository 외 레이어에서 직접 참조하면 도메인/애플리케이션 로직이 DB 스키마에 결합된다.
     *
     * **금지 대상 레이어 (위반 시 fail).**
     * - `com.bts.issue.application..` — 애플리케이션 서비스
     * - `com.bts.issue.domain..` — 도메인 모델
     * - `com.bts.issue.adapter..` — 인바운드/아웃바운드 어댑터
     * - `com.bts.issue.event..` — 도메인 이벤트
     * - `com.bts.issue.port..` — 포트 계약
     * - `com.bts.issue.web..` — 웹 레이어 (미래 확장 포함)
     *
     * **허용.** `com.bts.issue.repository..` 만.
     *
     * 위반 시 fail 메시지에 어느 파일이 jOOQ generated 클래스를 import 했는지 명시된다.
     * 수정 방법. jOOQ generated 코드 참조를 IssueRepository 를 통하도록 리팩토링.
     * 근거. ADR 2026-05-21-workflow-bc-cross-bc-port 와 동일 hexagonal 경계 정신.
     */
    @Test
    fun jooqGeneratedMustOnlyBeUsedInRepositoryLayer() {
        val rule =
            noClasses()
                .that().resideInAnyPackage(
                    "com.bts.issue.application..",
                    "com.bts.issue.domain..",
                    "com.bts.issue.adapter..",
                    "com.bts.issue.event..",
                    "com.bts.issue.port..",
                    "com.bts.issue.web..",
                )
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.bts.issue.jooq.generated..")
                .because(
                    "jOOQ generated 코드는 repository layer 만 접촉 가능 " +
                        "(hexagonal 경계, ADR workflow-bc-cross-bc-port 와 동일 정신).",
                )

        rule.check(importedClasses)
    }
}
