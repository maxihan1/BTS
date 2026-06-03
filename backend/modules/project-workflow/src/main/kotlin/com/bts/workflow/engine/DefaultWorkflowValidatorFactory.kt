// WorkflowValidatorFactory production 구현 — type 분기로 4종 validator 인스턴스 생성

package com.bts.workflow.engine

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.spi.WorkflowValidator
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.validator.CustomExpressionValidator
import com.bts.workflow.validator.NotStatusCategoryValidator
import com.bts.workflow.validator.PermissionValidator
import com.bts.workflow.validator.RequiredFieldValidator
import com.bts.workflow.validator.ValidatorScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [WorkflowValidatorFactory] 의 production 구현체.
 *
 * YAML 워크플로우 정의의 `validators[].type` 값에 따라 적절한 [WorkflowValidator] 인스턴스를 생성한다.
 *
 * ## 지원 type
 * - `"RequiredField"` — [RequiredFieldValidator]. config["field"] 필수.
 * - `"permission-check"` — [PermissionValidator]. config["permission"] 필수. config["scope"] 선택(기본 ISSUE).
 * - `"not-status-category"` — [NotStatusCategoryValidator]. config["category"] 필수 ([StateCategory] 이름).
 * - `"CustomExpression"` — [CustomExpressionValidator]. config["expression"] 필수.
 *
 * ## prod 프로파일 주의
 * prod 프로파일은 [PermissionResolver] 빈 부재(AlwaysAllowPermissionResolver 는 @Profile("!prod") 전용).
 * FR-PM-04 에서 IdentityAccessPermissionResolver(@Profile("prod")) 구현 필요.
 * 현 단계 검증은 test-assembled(!prod) 환경 기준이다.
 *
 * @param permissionResolver 권한 평가 outbound port.
 *   !prod 환경에서 [com.bts.workflow.adapter.AlwaysAllowPermissionResolver] 가 주입된다.
 * @param spelEvaluator SpEL 표현식 평가기. [WorkflowEngineConfig] 에서 @Bean 으로 등록된다.
 */
@Component
class DefaultWorkflowValidatorFactory(
    private val permissionResolver: PermissionResolver,
    private val spelEvaluator: SpelEvaluator,
) : WorkflowValidatorFactory {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * type 과 config 를 받아 [WorkflowValidator] 인스턴스를 반환한다.
     *
     * @param type YAML 워크플로우 정의의 `validators[].type` 값.
     *   지원 값: "RequiredField", "permission-check", "not-status-category", "CustomExpression".
     * @param config YAML 워크플로우 정의의 `validators[].config` 값.
     * @return 생성된 [WorkflowValidator] 인스턴스.
     * @throws IllegalArgumentException 지원하지 않는 type 이거나 필수 config 키가 누락된 경우.
     */
    override fun create(
        type: String,
        config: Map<String, Any?>,
    ): WorkflowValidator {
        log.debug("DefaultWorkflowValidatorFactory.create: type={}", type)
        return when (type) {
            "RequiredField" -> createRequiredField(config)
            "permission-check" -> createPermissionCheck(config)
            "not-status-category" -> createNotStatusCategory(config)
            "CustomExpression" -> createCustomExpression(config)
            else -> throw IllegalArgumentException("지원하지 않는 validator type: '$type'")
        }
    }

    private fun createRequiredField(config: Map<String, Any?>): RequiredFieldValidator {
        val field = requireConfigString(config, "field")
        return RequiredFieldValidator(field = field)
    }

    private fun createPermissionCheck(config: Map<String, Any?>): PermissionValidator {
        val permission = requireConfigString(config, "permission")
        val scope = requireConfigEnumOrDefault(config, "scope", ValidatorScope::valueOf, ValidatorScope.ISSUE)
        return PermissionValidator(resolver = permissionResolver, permission = permission, scope = scope)
    }

    private fun createNotStatusCategory(config: Map<String, Any?>): NotStatusCategoryValidator {
        val categoryName = requireConfigString(config, "category")
        val forbidden =
            runCatching { StateCategory.valueOf(categoryName) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "not-status-category config['category'] 에 알 수 없는 StateCategory: '$categoryName'",
                    )
                }
        return NotStatusCategoryValidator(forbidden = forbidden)
    }

    private fun createCustomExpression(config: Map<String, Any?>): CustomExpressionValidator {
        val expression = requireConfigString(config, "expression")
        return CustomExpressionValidator(evaluator = spelEvaluator, expression = expression)
    }

    /**
     * config 에서 키에 해당하는 String 값을 추출한다.
     *
     * @param config validator config 맵.
     * @param key 추출할 키 이름.
     * @return 키에 해당하는 String 값.
     * @throws IllegalArgumentException 키가 없거나 값이 String 이 아닌 경우.
     */
    private fun requireConfigString(
        config: Map<String, Any?>,
        key: String,
    ): String {
        val value =
            config[key]
                ?: throw IllegalArgumentException("validator config 에 필수 키 '$key' 가 없습니다")
        return value as? String
            ?: throw IllegalArgumentException("validator config['$key'] 는 String 이어야 합니다: $value")
    }

    /**
     * config 에서 키에 해당하는 선택 Enum 값을 추출한다. 키가 없으면 [default] 를 반환한다.
     *
     * @param config validator config 맵.
     * @param key 추출할 키 이름.
     * @param valueOf Enum 이름 문자열을 해당 Enum 값으로 변환하는 함수.
     * @param default 키가 없을 때 반환할 기본값.
     * @return Enum 값 또는 기본값.
     * @throws IllegalArgumentException 값이 String 이 아니거나 알 수 없는 Enum 이름인 경우.
     */
    private fun <T : Enum<T>> requireConfigEnumOrDefault(
        config: Map<String, Any?>,
        key: String,
        valueOf: (String) -> T,
        default: T,
    ): T {
        val raw = config[key] ?: return default
        val name =
            raw as? String
                ?: throw IllegalArgumentException("validator config['$key'] 는 String 이어야 합니다: $raw")
        return runCatching { valueOf(name) }
            .getOrElse {
                throw IllegalArgumentException("validator config['$key'] 에 알 수 없는 값: '$name'")
            }
    }
}
