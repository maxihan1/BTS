// shared-kernel 역참조 금지 ArchUnit 가드 — BC 패키지 순환 의존 재발을 빌드 시점에 영구 차단

package com.bts.shared.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * shared-kernel 경계 ArchUnit 룰.
 *
 * shared-kernel 은 BTS 전 모듈이 공유하는 중립 커널이다.
 * 어떤 BC(Bounded Context) 패키지도 역참조하면 순환 의존이 재발하므로
 * 아래 패키지에 대한 의존을 빌드 시점에 강제로 차단한다.
 *
 * 금지 대상.
 * - `com.bts.issue..`    — issue-tracking BC 내부 패키지
 * - `com.bts.workflow..` — project-workflow BC 내부 패키지
 * - `com.atlas.bts..`    — identity-access BC 내부 패키지
 *
 * 이 테스트가 미래에 실패한다면 누군가 shared-kernel 에서 위 패키지를
 * import 했다는 뜻이다. 해당 import 를 제거하거나 공유 타입을 shared-kernel 로
 * 이동시켜 해결한다.
 */
@AnalyzeClasses(
    packages = ["com.bts.shared"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
@Suppress("PropertyName", "VariableNaming")
class SharedKernelBoundaryArchTest {
    @ArchTest
    val `shared-kernel must not depend on any BC package`: ArchRule =
        noClasses()
            .that().resideInAPackage("com.bts.shared..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "com.bts.issue..",
                "com.bts.workflow..",
                "com.atlas.bts..",
            )
            .because(
                "shared-kernel 은 중립 공유 커널이므로 어떤 BC 도 역참조하면 안 된다 " +
                    "(순환 의존 재발 차단 — workflow-spi-extraction NFR2)",
            )
}
