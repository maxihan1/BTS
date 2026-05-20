// ArchUnit SPI 경계 룰 — spi 패키지 Spring 비결합 + adapter.spring 외 Spring Security AP 직접 import 금지

package com.atlas.bts.identity.architecture

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * BTS SPI 패키지 경계 ArchUnit 룰.
 *
 * 룰 1: `..spi..` 패키지는 Spring Web / Spring Security 의존 금지.
 *   - 허용: stereotype, context.annotation, beans.factory.annotation (Bean 메타 어노테이션).
 *   - 금지: spring.web, spring.security, spring.data 등 인프라 패키지.
 *   - 이유: 도메인 SPI는 프레임워크 비결합이어야 이식성과 단위 테스트 용이성이 보장됨.
 *
 * 룰 2: `..adapter.spring..` 외 패키지는 Spring Security AuthenticationProvider 직접 import 금지.
 *   - 이유: Spring Security와 BTS 도메인 AuthenticationProvider의 명명 충돌 격리
 *     (ADR: docs/decisions/2026-05-20-authentication-provider-spi-naming.md).
 */
@AnalyzeClasses(
    packages = ["com.atlas.bts.identity"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
@Suppress("PropertyName", "VariableNaming")
class SpiBoundaryArchTest {
    @ArchTest
    val `spi package must not import Spring Web or Security`: ArchRule =
        noClasses()
            .that().resideInAPackage("..spi..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework.web..",
                "org.springframework.security..",
                "org.springframework.data..",
            )
            .because("BTS 도메인 SPI는 Spring Web/Security/Data에 비결합이어야 한다 (ADR spi-naming)")

    @ArchTest
    val `Spring AuthenticationProvider import only allowed in adapter spring package`: ArchRule =
        noClasses()
            .that().resideOutsideOfPackage("..adapter.spring..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.security.authentication.AuthenticationProvider")
            .because("Spring Security AuthenticationProvider는 adapter.spring 패키지에서만 import 가능 (ADR spi-naming)")
}
