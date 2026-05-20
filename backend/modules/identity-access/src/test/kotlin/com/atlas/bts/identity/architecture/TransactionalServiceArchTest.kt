// ArchUnit 룰 — @Transactional 메서드 보유 클래스의 Spring 빈 등록 강제 (PR #6 learning #1 회귀 가드)

package com.atlas.bts.identity.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes

private const val TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional"
private const val SERVICE = "org.springframework.stereotype.Service"
private const val COMPONENT = "org.springframework.stereotype.Component"
private const val REPOSITORY = "org.springframework.stereotype.Repository"
private const val CONFIGURATION = "org.springframework.context.annotation.Configuration"

/**
 * PR #6 learning #1 회귀 가드 — @Transactional 메서드 보유 클래스 Spring 빈 등록 강제.
 *
 * ## 배경 (PR #6 learning #1)
 * `@Transactional`은 Spring AOP 프록시를 통해 동작한다. Spring AOP 프록시는 Spring 컨테이너가
 * 빈으로 등록한 객체에만 적용된다. 따라서 `@Transactional` 메서드를 보유하면서 Spring 빈
 * 어노테이션(`@Service`, `@Component`, `@Repository`, `@Configuration`)이 없는 클래스는
 * 트랜잭션이 무효화되어 데이터 정합성 문제가 무음으로 발생할 수 있다.
 *
 * ## 룰 요약
 * `@Transactional`이 붙은 메서드를 1개 이상 보유한 클래스는
 * `@Service` / `@Component` / `@Repository` / `@Configuration` 중 하나로 선언되어야 한다.
 *
 * ## 위반 시 조치
 * 위반 클래스에 해당 역할에 맞는 Spring 스테레오타입 어노테이션을 추가할 것.
 * - 비즈니스 로직 클래스 → `@Service`
 * - 데이터 접근 클래스 → `@Repository`
 * - 일반 인프라 컴포넌트 → `@Component`
 * - 설정/빈 팩토리 → `@Configuration`
 */
@AnalyzeClasses(
    packages = ["com.atlas.bts.identity"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
@Suppress("PropertyName", "VariableNaming")
class TransactionalServiceArchTest {
    /**
     * `CanBeAnnotated.Predicates.annotatedWith(String)` 은 `DescribedPredicate<CanBeAnnotated>` 를 반환한다.
     * `JavaMethod`는 `CanBeAnnotated`의 하위 타입이므로 `.forSubtype()` 으로 `DescribedPredicate<JavaMethod>`로 좁힌다.
     * `containAnyMethodsThat(DescribedPredicate<JavaMethod>)` 에 타입 안전하게 전달된다.
     */
    @Suppress("UNCHECKED_CAST")
    private val isTransactionalMethod: DescribedPredicate<JavaMethod> =
        CanBeAnnotated.Predicates.annotatedWith(TRANSACTIONAL) as DescribedPredicate<JavaMethod>

    @ArchTest
    val `classes with Transactional methods must be Spring beans`: ArchRule =
        classes()
            .that().containAnyMethodsThat(isTransactionalMethod)
            .should().beAnnotatedWith(SERVICE)
            .orShould().beAnnotatedWith(COMPONENT)
            .orShould().beAnnotatedWith(REPOSITORY)
            .orShould().beAnnotatedWith(CONFIGURATION)
            .because(
                "PR #6 learning #1: @Transactional은 Spring AOP 프록시를 통해 동작한다. " +
                    "Spring 빈으로 등록되지 않은 클래스는 프록시가 생성되지 않아 @Transactional이 무효화된다. " +
                    "@Service / @Component / @Repository / @Configuration 중 하나를 클래스에 추가할 것.",
            )
}
