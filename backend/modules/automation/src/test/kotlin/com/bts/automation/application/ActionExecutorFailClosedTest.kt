// ActionExecutor fail-closed 주입 회귀 가드 — IssueMutationPort 는 non-null 로 요구돼야 한다 (FR-AT-02 C4)

package com.bts.automation.application

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.full.primaryConstructor

/**
 * [ActionExecutor] fail-closed 주입 계약 회귀 가드 (FR-AT-02 코드리뷰 C4).
 *
 * cross-BC 포트 [com.bts.shared.issue.IssueMutationPort] 는 prod 어댑터(issue-tracking
 * `@Profile("prod")` 구현)가 조립되지 않으면 컨텍스트 부팅이 `NoSuchBeanDefinitionException` 으로
 * 실패해야 한다(silent no-op drop 없음). 이 fail-closed 는 생성자 파라미터가 **non-null** 이어야만
 * 성립한다 — nullable + `?: return` 은 prod fail-open 트랩이다([[crossbc-resolver-nullable-fail-open]]).
 *
 * 이 테스트는 향후 누군가 포트 주입을 nullable 로 바꿔 fail-open 으로 되돌리는 회귀를 리플렉션
 * 수준에서 차단한다(현 시점엔 이미 non-null 이므로 통과 — 계약을 잠그는 가드다). prod 조립 자체는
 * 별도 후속(전역 조립 트랙, ADR C4)이며, 이 가드는 그 조립이 도착했을 때 fail-closed 계약이
 * 유지됨을 보장한다.
 */
class ActionExecutorFailClosedTest : StringSpec({
    "ActionExecutor 의 issueMutationPort 생성자 파라미터는 non-null 이어야 한다(fail-closed)" {
        val param =
            ActionExecutor::class.primaryConstructor!!
                .parameters
                .single { it.name == "issueMutationPort" }

        param.type.isMarkedNullable shouldBe false
    }
})
