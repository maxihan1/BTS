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
     * - `com.atlas.bts.identity.issuesecurity..` — 보안 등급 도메인/레포지토리 내부 (FR-HS-01 T4 추가)
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
                    "com.atlas.bts.identity.issuesecurity..",
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

    /**
     * 룰 3 — `IssueChangeHistoryRepository.findByIssue` 는 프로덕션에서 호출하지 않는다.
     *
     * ## 무엇을 막는가
     * 이력 읽기 경로는 셋인데 **삭제된 댓글 본문 마스킹은 둘에만** 있다.
     *
     * | 읽기 경로 | 마스킹 |
     * |---|---|
     * | `IssueChangelogService` offset 모드 | ✅ `maskDeletedCommentBodies` |
     * | `IssueChangelogService` cursor 모드 | ✅ |
     * | `IssueChangeHistoryRepository.findByIssue` | ❌ 없음 |
     *
     * FR-CO-02 D7 이 마스킹을 **조회 시점 정책**으로 정했으므로, 새 기능이 이 메서드를 호출하는
     * 순간 삭제된 댓글 본문이 그대로 응답에 실린다. 마스킹을 안 하면 "부적절한 내용으로 수정한 뒤
     * 삭제" 로 삭제가 무력화된다.
     *
     * ## 왜 마스킹을 이 메서드에 하나 더 붙이지 않았나
     * 계층 역전(history repo → comment repo)이 생기고, **다음 읽기 메서드가 추가되면 같은 실수가
     * 반복된다**. 가드를 만들고 생산 지점 일부에만 주입하는 상태가 정확히 지금 문제다
     * ([[mutation-site-count-equals-verified-scope]]). 마스킹 지점을 늘리는 대신 **프로덕션에서
     * 이 메서드로 들어오는 경로 자체를 0 으로 유지**한다.
     *
     * ## 현재 상태 (2026-07-27 실측)
     * 프로덕션 호출자 **0건** — 호출 지점은 전부 테스트다. 즉 이 룰은 지금 즉시 green 이며,
     * 그 green 은 "위반이 없다" 는 뜻이지 "룰이 동작한다" 는 뜻이 아니다. 아래 비-공허 테스트가
     * 대상 클래스를 실제로 import 했는지 별도로 확인한다([[archunit-vacuous-rule-silent-pass]]).
     *
     * 이 메서드가 정말 필요해지면 `findByIssuePaged(issueId, limit, 0)` 가 상위집합이다.
     */
    @Test
    fun findByIssueMustNotBeCalledFromProduction() {
        val rule =
            noClasses()
                .that().resideInAPackage("com.bts.issue..")
                .and().resideOutsideOfPackage("com.bts.issue.history..")
                .should().callMethodWhere(
                    com.tngtech.archunit.base.DescribedPredicate.describe(
                        "IssueChangeHistoryRepository.findByIssue 호출",
                    ) { call: com.tngtech.archunit.core.domain.JavaMethodCall ->
                        call.targetOwner.name.endsWith("IssueChangeHistoryRepository") &&
                            call.target.name == "findByIssue"
                    },
                )
                .because(
                    "findByIssue 는 삭제된 댓글 본문 마스킹을 하지 않는다. " +
                        "프로덕션에서 쓰면 삭제된 댓글 본문이 이력 응답에 그대로 실린다 " +
                        "(FR-CO-02 D7 조회 시점 마스킹 정책). " +
                        "필요하면 findByIssuePaged(issueId, limit, 0) 를 쓸 것.",
                )

        rule.check(importedClasses)
    }

    /**
     * 위 룰이 **공허하지 않은지** 확인한다.
     *
     * ArchUnit 은 대상 클래스 집합이 비면 조용히 통과한다. `IssueChangeHistoryRepository` 가
     * import 범위 밖으로 나가면(패키지 이동 등) 룰 3 은 영원히 green 이 되면서 아무것도 막지 않는다.
     */
    @Test
    fun findByIssueRuleTargetIsActuallyImported() {
        val targetExists =
            importedClasses.any { it.name.endsWith("IssueChangeHistoryRepository") }
        assert(targetExists) {
            "IssueChangeHistoryRepository 가 import 범위에 없다 — 룰 3 이 공허하게 통과한다. " +
                "패키지가 이동했는지 확인하라."
        }

        val callerCandidates = importedClasses.count { it.packageName.startsWith("com.bts.issue") }
        assert(callerCandidates > 50) {
            "import 된 프로덕션 클래스가 $callerCandidates 개뿐이다 — importer 설정이 고장났을 수 있다."
        }
    }
}
