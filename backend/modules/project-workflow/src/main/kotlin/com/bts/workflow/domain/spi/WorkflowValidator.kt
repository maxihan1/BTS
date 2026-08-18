// 워크플로우 Validator SPI — 전환 가능 검증 (사전 게이트)

package com.bts.workflow.domain.spi

import com.bts.workflow.domain.dto.TransitionContext

/**
 * Validator 가 평가되는 시점을 구분하는 열거형.
 *
 * AVAILABILITY 는 전환 목록 조회 단계에서 평가되는 게이트다. 사용자에게 어떤 전환 버튼을
 * 노출할지 결정할 때 사용한다.
 *
 * EXECUTION 은 실제 전환을 실행하는 단계에서만 평가되는 게이트다. 전환 화면(Jira 의
 * transition screen)에서 필수 입력을 강제하는 시맨틱에 해당한다. 목록 조회 시에는
 * 평가하지 않으므로 버튼은 보이되, 실행 시점에 조건이 충족되지 않으면 전환이 차단된다.
 */
enum class ValidatorPhase {
    /**
     * 전환 목록 조회 시 평가. availableTransitions 게이트.
     */
    AVAILABILITY,

    /**
     * 전환 실행 시 평가. transition 실행 게이트. Jira transition screen 시맨틱.
     */
    EXECUTION,
}

/**
 * 워크플로우 전환(transition) 허용 여부를 판정하는 SPI(Service Provider Interface).
 *
 * SPI 란 외부 구현체가 플러그인처럼 꽂힐 수 있는 인터페이스다. 예를 들어 특정 필드 값 조건,
 * 권한 검사 등을 각자 구현해 등록한다. 이 인터페이스는 sealed 가 아니므로 다른 BC(바운디드
 * 컨텍스트)가 자체 구현체를 추가할 수 있다. Java 9+ 모듈 시스템 대신 Spring Bean 등록 방식으로
 * 디스커버리한다.
 *
 * 참조. FR-WF-01, SDD §07, ADR 2026-05-21.
 */
interface WorkflowValidator {
    /**
     * 이 Validator 의 고유 식별자. 예: "field-required", "permission-check".
     * YAML 워크플로우 정의의 `validators[].type` 값과 매칭된다.
     */
    val type: String

    /**
     * 이 Validator 가 평가되는 시점. 기본값은 [ValidatorPhase.AVAILABILITY].
     *
     * 기본값을 AVAILABILITY 로 설정해 기존 구현체와 다른 BC 의 익명 object 가
     * 무수정으로 컴파일되도록 한다. EXECUTION 페이즈가 필요한 구현체만 override 한다.
     */
    val phase: ValidatorPhase get() = ValidatorPhase.AVAILABILITY

    /**
     * 전환 허용 여부를 판정한다.
     *
     * @param ctx 전환 요청에 대한 컨텍스트 정보 (이슈 상태, 실행자, 필드 등).
     * @return [ValidatorResult.Pass] 또는 [ValidatorResult.Fail].
     */
    fun validate(ctx: TransitionContext): ValidatorResult
}

/**
 * [WorkflowValidator.validate] 의 반환값. sealed interface 로 선언해 가능한 결과를 Pass/Fail 두
 * 가지로 완전히 열거(exhaustive)한다. when 식에서 else 분기 없이 컴파일러가 완전성을 보장한다.
 */
sealed interface ValidatorResult {
    /**
     * 전환을 허용한다.
     */
    data object Pass : ValidatorResult

    /**
     * 전환을 거부한다.
     *
     * @param field 문제가 된 필드 이름. 특정 필드에 국한되지 않는 경우 null.
     * @param reason 사람이 읽을 수 있는 거부 사유.
     */
    data class Fail(val field: String?, val reason: String) : ValidatorResult
}
