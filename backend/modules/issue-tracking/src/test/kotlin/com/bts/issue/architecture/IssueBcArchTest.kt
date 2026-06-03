// issue-tracking BC 아키텍처 규칙 테스트 — Rule 1 BC 격리(workflow + identity) + Rule 2 jOOQ 화이트리스트

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
 * - 룰 1b (BC 격리) — [issueBcMustNotDependOnIdentityAccessInternals]
 * - 룰 2 (jOOQ 화이트리스트) — [jooqGeneratedMustOnlyBeUsedInRepositoryLayer]
 *
 * ### 룰 1 — project-workflow 의존 제거 (PR #25 com.bts.shared.workflow 이동)
 * PR #25 (workflow-spi-extraction) 에서 전이 포트/DTO 가 `com.bts.shared.workflow` 로 이동.
 * issue-tracking 은 project-workflow 를 gradle 의존으로 선언하지 않으며,
 * 전이 관련 타입은 `com.bts.shared.workflow` 모듈에서 가져온다.
 * 따라서 `com.bts.workflow.*` 패키지 전체가 이슈 BC 에서 사용 불가여야 한다.
 * 근거. CLAUDE.md §BC 격리 + ADR 2026-05-21-workflow-bc-cross-bc-port +
 * ADR 2026-05-26-workflow-transition-port-result-sealed.
 *
 * ### 룰 1b — identity-access 내부 직접 참조 금지 (FR-IS-03 Task 3)
 * 사용자 존재 확인은 [com.bts.shared.user.UserLookupPort] (shared-kernel) 를 통해서만 접근한다.
 * issue-tracking 은 identity-access 모듈을 gradle 의존으로 선언하지 않으며,
 * `com.atlas.bts.identity.*` 패키지를 직접 import 해서는 안 된다.
 * 근거. ADR 2026-06-01-issue-assignee-user-lookup-port + CLAUDE.md §BC 격리.
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
     * 룰 1 — issue-tracking BC 는 com.bts.workflow.* 전체를 직접 참조하지 않는다.
     *
     * PR #25 (workflow-spi-extraction) 에서 전이 포트/DTO 가 `com.bts.shared.workflow` 로 이동.
     * issue-tracking gradle 의존에 project-workflow 모듈이 없으므로 `com.bts.workflow.*` 전체가
     * 컴파일 불가. 이 룰은 부정 규칙으로 금지 패키지 접근이 없음을 추가 보증한다.
     *
     * **금지 패키지 (위반 시 fail) — com.bts.workflow.* 내부 전체.**
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
     * 룰 1b — issue-tracking BC 는 identity-access 내부 패키지를 직접 참조하지 않는다.
     *
     * 사용자 존재 확인은 [com.bts.shared.user.UserLookupPort] (shared-kernel) 를 경유해야 하며,
     * `com.atlas.bts.identity.*` 패키지를 직접 import 하는 것은 BC 경계 위반이다.
     * 현재 위반 0건 — 이 룰은 미래 회귀를 방지하는 가드로 작동한다.
     *
     * **금지 패키지 (위반 시 fail).**
     * - `com.atlas.bts.identity.user..` — UserRepository, User 엔티티 등 내부 사용자 도메인
     * - `com.atlas.bts.identity.credential..` — 인증 자격증명 내부
     * - `com.atlas.bts.identity.session..` — 세션/토큰 내부
     * - `com.atlas.bts.identity.provider..` — 인증 공급자 내부
     * - `com.atlas.bts.identity.jwt..` — JWT 발급 내부
     * - `com.atlas.bts.identity.pat..` — PAT 내부
     * - `com.atlas.bts.identity.web..` — identity 웹 레이어 내부
     * - `com.atlas.bts.identity.application..` — identity 애플리케이션 서비스 내부
     *
     * 근거. ADR 2026-06-01-issue-assignee-user-lookup-port + CLAUDE.md §BC 격리.
     */
    @Test
    fun issueBcMustNotDependOnIdentityAccessInternals() {
        val rule =
            noClasses()
                .that().resideInAPackage("com.bts.issue..")
                .and().resideOutsideOfPackage("com.bts.issue.jooq..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                    "com.atlas.bts.identity.user..",
                    "com.atlas.bts.identity.credential..",
                    "com.atlas.bts.identity.session..",
                    "com.atlas.bts.identity.provider..",
                    "com.atlas.bts.identity.jwt..",
                    "com.atlas.bts.identity.pat..",
                    "com.atlas.bts.identity.web..",
                    "com.atlas.bts.identity.application..",
                    "com.atlas.bts.identity.permission..",
                )
                .because(
                    "issue-tracking BC 는 identity-access 내부를 직접 참조할 수 없다. " +
                        "사용자 존재 확인은 com.bts.shared.user.UserLookupPort (shared-kernel) 를 경유해야 한다 " +
                        "(ADR 2026-06-01-issue-assignee-user-lookup-port + CLAUDE.md §BC 격리).",
                )

        rule.check(importedClasses)
    }

    /**
     * 룰 2 — jOOQ 생성 코드는 repository 레이어에서만 접촉한다.
     *
     * `com.bts.issue.jooq..` 는 DB 테이블/컬럼을 Kotlin 타입으로 표현한 jOOQ 생성 코드다
     * (실제 생성 패키지: `com.bts.issue.jooq.tables..`, `.references..`, `.keys..` 등).
     * 이를 repository 외 레이어에서 직접 참조하면 도메인/애플리케이션 로직이 DB 스키마에 결합된다.
     *
     * **금지 대상.** `com.bts.issue..` 전 패키지에서 jOOQ 직접 참조 금지.
     * 단, jOOQ 생성 코드 자체(`com.bts.issue.jooq..`)와 모든 repository 패키지
     * (`..repository..` — 예: `com.bts.issue.repository`, `com.bts.issue.component.repository`)는 제외.
     * 서브패키지 레이어(예: `com.bts.issue.component.application`)까지 포함한다.
     *
     * 위반 시 fail 메시지에 어느 파일이 jOOQ 클래스를 import 했는지 명시된다.
     * 수정 방법. jOOQ 참조를 repository 레이어(예: ProjectLookupRepository, ComponentRepository)로 이동.
     * 근거. ADR 2026-05-21-workflow-bc-cross-bc-port 와 동일 hexagonal 경계 정신.
     *
     * 주의(2026-06-02). 기존 룰은 대상 패키지가 `com.bts.issue.jooq.generated..`(실제 경로와 불일치)
     * + `.that()` 레이어 목록이 `com.bts.issue.application..`만 열거해 서브패키지를 못 봐, 사실상
     * 아무것도 검사하지 못했다(FR-CM-01 코드리뷰 NIT-2). 패키지 경로 + 검사 범위를 모두 정정한다.
     */
    @Test
    fun jooqGeneratedMustOnlyBeUsedInRepositoryLayer() {
        val rule =
            noClasses()
                .that().resideInAPackage("com.bts.issue..")
                .and().resideOutsideOfPackage("com.bts.issue.jooq..")
                .and().resideOutsideOfPackage("..repository..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.bts.issue.jooq..")
                .because(
                    "jOOQ 생성 코드는 repository layer 만 접촉 가능 " +
                        "(hexagonal 경계, ADR workflow-bc-cross-bc-port 와 동일 정신).",
                )

        rule.check(importedClasses)
    }
}
