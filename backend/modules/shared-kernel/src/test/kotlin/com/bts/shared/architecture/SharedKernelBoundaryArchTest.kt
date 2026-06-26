// shared-kernel 역참조 금지 ArchUnit 가드 — BC 패키지 순환 의존 재발을 빌드 시점에 영구 차단

package com.bts.shared.architecture

import com.bts.shared.membership.GroupMembershipPort
import com.bts.shared.membership.ProjectMembershipPort
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * shared-kernel 경계 ArchUnit 룰.
 *
 * shared-kernel 은 BTS 전 모듈이 공유하는 중립 커널이다.
 * 어떤 BC(Bounded Context) 패키지도 역참조하면 순환 의존이 재발하므로
 * 아래 패키지에 대한 의존을 빌드 시점에 강제로 차단한다.
 *
 * 금지 대상.
 * - `com.bts.issue..`         — issue-tracking BC 내부 패키지
 * - `com.bts.workflow..`      — project-workflow BC 내부 패키지
 * - `com.atlas.bts.identity..` — identity-access BC 내부 패키지
 *
 * 주의: `com.bts.shared.issue..` 와 `com.bts.shared.workflow..` 는
 * shared-kernel 자체 패키지이므로 금지 대상에서 제외한다.
 *
 * 이 테스트가 미래에 실패한다면 누군가 shared-kernel 에서 위 패키지를
 * import 했다는 뜻이다. 해당 import 를 제거하거나 공유 타입을 shared-kernel 로
 * 이동시켜 해결한다.
 */
class SharedKernelBoundaryArchTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("com.bts.shared")

    @Test
    fun `shared-kernel must not depend on com_bts_issue package`() {
        noClasses()
            .that().resideInAPackage("com.bts.shared..")
            .should().dependOnClassesThat().resideInAPackage("com.bts.issue..")
            .because(
                "shared-kernel 은 중립 공유 커널이므로 issue-tracking BC 를 역참조하면 순환 의존이 재발한다. " +
                    "ADR: docs/decisions/2026-05-27-shared-kernel-extraction.md",
            )
            .check(classes)
    }

    @Test
    fun `shared-kernel must not depend on com_bts_workflow package`() {
        noClasses()
            .that().resideInAPackage("com.bts.shared..")
            .should().dependOnClassesThat().resideInAPackage("com.bts.workflow..")
            .because(
                "shared-kernel 은 중립 공유 커널이므로 project-workflow BC 를 역참조하면 순환 의존이 재발한다. " +
                    "ADR: docs/decisions/2026-05-27-shared-kernel-extraction.md",
            )
            .check(classes)
    }

    @Test
    fun `shared-kernel must not depend on com_atlas_bts_identity package`() {
        noClasses()
            .that().resideInAPackage("com.bts.shared..")
            .should().dependOnClassesThat().resideInAPackage("com.atlas.bts.identity..")
            .because(
                "shared-kernel 은 중립 공유 커널이므로 identity-access BC 를 역참조하면 안 된다. " +
                    "ADR: docs/decisions/2026-05-27-shared-kernel-extraction.md",
            )
            .check(classes)
    }

    @Test
    fun `shared-kernel must not depend on com_bts_search package`() {
        noClasses()
            .that().resideInAPackage("com.bts.shared..")
            .should().dependOnClassesThat().resideInAPackage("com.bts.search..")
            .because(
                "shared-kernel 은 중립 공유 커널이므로 search-export-import BC 를 역참조하면 순환 의존이 재발한다. " +
                    "ADR: docs/decisions/2026-05-27-shared-kernel-extraction.md",
            )
            .check(classes)
    }

    @Test
    fun `shared-kernel must not depend on com_bts_agileplanning package`() {
        noClasses()
            .that().resideInAPackage("com.bts.shared..")
            .should().dependOnClassesThat().resideInAPackage("com.bts.agileplanning..")
            .because(
                "shared-kernel 은 중립 공유 커널이므로 agile-planning BC 를 역참조하면 순환 의존이 재발한다. " +
                    "ADR: docs/decisions/2026-05-27-shared-kernel-extraction.md",
            )
            .check(classes)
    }

    @Test
    fun `shared-kernel must not depend on com_bts_notification package`() {
        noClasses()
            .that().resideInAPackage("com.bts.shared..")
            .should().dependOnClassesThat().resideInAPackage("com.bts.notification..")
            .because(
                "shared-kernel 은 중립 공유 커널이므로 notification BC 를 역참조하면 순환 의존이 재발한다. " +
                    "ADR: docs/decisions/2026-05-27-shared-kernel-extraction.md",
            )
            .check(classes)
    }

    /**
     * [GroupMembershipPort] 와 [ProjectMembershipPort] 는 인터페이스이어야 한다.
     *
     * fail-closed 원칙 — default 구현이 없으므로 소비 BC 에서 Bean 을 등록하지 않으면
     * 부팅 자체가 실패해 공유 누출이 원천 차단된다.
     *
     * 원시 타입 전용 — `com.bts.shared.membership..` 패키지가 BC 도메인 타입을 참조하면
     * `membership package must not reference BC domain types` 룰이 빌드를 차단한다.
     */
    @Test
    fun `GroupMembershipPort 와 ProjectMembershipPort 는 인터페이스이어야 한다`() {
        assertThat(GroupMembershipPort::class.java.isInterface)
            .`as`("GroupMembershipPort must be an interface — fail-closed, no default allowed")
            .isTrue()
        assertThat(ProjectMembershipPort::class.java.isInterface)
            .`as`("ProjectMembershipPort must be an interface — fail-closed, no default allowed")
            .isTrue()
    }

    /**
     * `com.bts.shared.membership` 패키지는 BC 도메인 타입을 직접 참조하면 안 된다.
     *
     * 원시 타입(UUID/String/Set) 전용 계약을 ArchUnit 으로 빌드 시점에 강제한다.
     * 위반 시 순환 의존 또는 가시성 공유 누출이 재발한다.
     */
    @Test
    fun `membership package must not reference BC domain types - primitive types only`() {
        noClasses()
            .that().resideInAPackage("com.bts.shared.membership..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.bts.issue..",
                "com.bts.workflow..",
                "com.atlas.bts.identity..",
                "com.bts.search..",
                "com.bts.agileplanning..",
                "com.bts.notification..",
            )
            .because(
                "com.bts.shared.membership 포트는 원시 타입(UUID/String/Set)만 사용해야 한다. " +
                    "BC 도메인 타입을 참조하면 순환 의존 또는 공유 가시성 누출이 재발한다.",
            )
            .check(classes)
    }
}
