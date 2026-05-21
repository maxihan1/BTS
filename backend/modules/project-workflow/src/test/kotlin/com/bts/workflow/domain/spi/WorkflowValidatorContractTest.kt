// WorkflowValidator interface 계약 검증 — type/validate 시그니처 + ValidatorResult sealed

package com.bts.workflow.domain.spi

import com.bts.workflow.domain.dto.TransitionContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties

/**
 * WorkflowValidator SPI 계약을 reflection으로 검증한다.
 *
 * Spring 컨텍스트 없이 순수 Kotlin reflection만 사용한다.
 * 검증 항목.
 * 1. WorkflowValidator 인터페이스가 존재한다.
 * 2. interface 가 sealed 가 아니다 (외부 BC 구현 허용 SPI).
 * 3. `type: String` val 프로퍼티를 가진다.
 * 4. `validate(ctx: TransitionContext): ValidatorResult` 메서드를 가진다.
 * 5. ValidatorResult 가 sealed interface 다.
 * 6. ValidatorResult.Pass (data object) 와 ValidatorResult.Fail(field, reason) (data class) 이 존재한다.
 */
class WorkflowValidatorContractTest {
    private val validatorClass: KClass<WorkflowValidator> = WorkflowValidator::class

    @Test
    fun `WorkflowValidator 는 interface 다`() {
        assertThat(validatorClass.java.isInterface).isTrue()
    }

    @Test
    fun `WorkflowValidator 는 sealed 가 아니다 — 외부 BC 구현 허용 SPI`() {
        assertThat(validatorClass.isSealed).isFalse()
    }

    @Test
    fun `WorkflowValidator 는 type String val 프로퍼티를 가진다`() {
        val typeProp = validatorClass.memberProperties.find { it.name == "type" }
        assertThat(typeProp).isNotNull()
        assertThat(typeProp!!.returnType.classifier).isEqualTo(String::class)
    }

    @Test
    fun `WorkflowValidator 는 validate TransitionContext 파라미터를 가진 메서드를 가진다`() {
        val validateFn = validatorClass.memberFunctions.find { it.name == "validate" }
        assertThat(validateFn).isNotNull()

        // 파라미터: this(receiver) + ctx(TransitionContext) = 2개
        val params = validateFn!!.parameters
        assertThat(params).hasSizeGreaterThanOrEqualTo(2)

        val ctxParam = params.find { it.type.classifier == TransitionContext::class }
        assertThat(ctxParam).isNotNull()
    }

    @Test
    fun `validate 반환 타입은 ValidatorResult 다`() {
        val validateFn = validatorClass.memberFunctions.find { it.name == "validate" }
        assertThat(validateFn).isNotNull()
        assertThat(validateFn!!.returnType.classifier).isEqualTo(ValidatorResult::class)
    }

    @Test
    fun `ValidatorResult 는 sealed interface 다`() {
        val resultClass = ValidatorResult::class
        assertThat(resultClass.java.isInterface).isTrue()
        assertThat(resultClass.isSealed).isTrue()
    }

    @Test
    fun `ValidatorResult 에 Pass 서브타입이 존재한다`() {
        val subclasses = ValidatorResult::class.sealedSubclasses
        val passType = subclasses.find { it.simpleName == "Pass" }
        assertThat(passType).isNotNull()
    }

    @Test
    fun `ValidatorResult 에 Fail 서브타입이 존재하며 field 와 reason 프로퍼티를 가진다`() {
        val subclasses = ValidatorResult::class.sealedSubclasses
        val failType = subclasses.find { it.simpleName == "Fail" }
        assertThat(failType).isNotNull()

        val failProps = failType!!.memberProperties.map { it.name }
        assertThat(failProps).contains("field", "reason")
    }

    @Test
    fun `ValidatorResult Fail 의 field 는 nullable String 이다`() {
        val failType = ValidatorResult::class.sealedSubclasses.find { it.simpleName == "Fail" }
        assertThat(failType).isNotNull()

        val fieldProp = failType!!.memberProperties.find { it.name == "field" }
        assertThat(fieldProp).isNotNull()
        assertThat(fieldProp!!.returnType.isMarkedNullable).isTrue()
        assertThat(fieldProp.returnType.classifier).isEqualTo(String::class)
    }

    @Test
    fun `ValidatorResult Fail 의 reason 은 non-null String 이다`() {
        val failType = ValidatorResult::class.sealedSubclasses.find { it.simpleName == "Fail" }
        assertThat(failType).isNotNull()

        val reasonProp = failType!!.memberProperties.find { it.name == "reason" }
        assertThat(reasonProp).isNotNull()
        assertThat(reasonProp!!.returnType.isMarkedNullable).isFalse()
        assertThat(reasonProp.returnType.classifier).isEqualTo(String::class)
    }
}
