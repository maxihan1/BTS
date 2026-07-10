// automation BC 도메인 예외 계층 — sealed 베이스 + triggerConfig/룰 불변식 위반 서브타입

package com.bts.automation.domain

/**
 * automation BC 에서 발생하는 모든 도메인 예외의 베이스.
 *
 * sealed 로 선언하여 when 식에서 컴파일러가 완전성(exhaustiveness)을 보장한다.
 * RuntimeException 을 상속하므로 Spring `@Transactional` 롤백 트리거 대상이다.
 * HTTP 레이어에서의 상태 코드 매핑은 이 BC 의 exception handler(Task 6 scope) 책임이다.
 *
 * @param message 위반 내용을 설명하는 메시지
 */
sealed class AutomationDomainException(message: String) : RuntimeException(message)

/**
 * triggerConfig 가 triggerType 이 요구하는 형식에 맞지 않을 때.
 *
 * 대상 존재/권한은 검증하지 않는다(형식만 검증 — favorites/가젯 카탈로그 선례, ADR D1
 * §트리거 도메인 모델). HTTP 400 으로 매핑된다.
 *
 * @param message 위반 내용을 설명하는 메시지
 */
class TriggerConfigInvalidException(message: String) : AutomationDomainException(message)

/**
 * [AutomationRule] 자체 필드(projectKey·name 등) 불변식 위반 시.
 *
 * HTTP 400 으로 매핑된다.
 *
 * @param message 위반 내용을 설명하는 메시지
 */
class AutomationRuleInvalidException(message: String) : AutomationDomainException(message)
