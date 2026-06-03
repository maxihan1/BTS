// WorkflowPostActionFactory production 구현 — type 분기로 5종 PostAction 인스턴스 생성

package com.bts.workflow.engine

import com.bts.workflow.domain.spi.WorkflowPostAction
import com.bts.workflow.postaction.AddWatcherPostAction
import com.bts.workflow.postaction.CallWebhookPostAction
import com.bts.workflow.postaction.NotifyPostAction
import com.bts.workflow.postaction.RunAutomationPostAction
import com.bts.workflow.postaction.SetFieldPostAction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [WorkflowPostActionFactory] 의 production 구현체.
 *
 * YAML 워크플로우 정의의 `post_actions[].type` 값에 따라 적절한 [WorkflowPostAction] 인스턴스를 생성한다.
 * 이 팩토리는 인스턴스 생성(계획 수립)만 담당하며, PostAction 의 실제 적용(필드 저장, 이벤트 발행)은
 * 호출자 BC 가 수행한다 — GAP-2 제약에 따름.
 *
 * ## 지원 type
 * - `"SET_FIELD"` — [SetFieldPostAction]. config["field"] 필수, config["value"] 선택(null 허용).
 * - `"NOTIFY"` — [NotifyPostAction]. config["channel"], config["recipients"] 필수.
 * - `"ADD_WATCHER"` — [AddWatcherPostAction]. config["watcher"] 필수.
 * - `"RUN_AUTOMATION"` — [RunAutomationPostAction]. config["automationKey"] 필수.
 * - `"CALL_WEBHOOK"` — [CallWebhookPostAction]. config["url"], config["method"] 필수.
 */
@Component
class DefaultWorkflowPostActionFactory : WorkflowPostActionFactory {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * type 과 config 를 받아 [WorkflowPostAction] 인스턴스를 반환한다.
     *
     * @param type YAML 워크플로우 정의의 `post_actions[].type` 값 (SCREAMING_SNAKE_CASE).
     *   지원 값: "SET_FIELD", "NOTIFY", "ADD_WATCHER", "RUN_AUTOMATION", "CALL_WEBHOOK".
     * @param config YAML 워크플로우 정의의 `post_actions[].config` 값.
     * @return 생성된 [WorkflowPostAction] 인스턴스.
     * @throws IllegalArgumentException 지원하지 않는 type 이거나 필수 config 키가 누락된 경우.
     */
    override fun create(
        type: String,
        config: Map<String, Any?>,
    ): WorkflowPostAction {
        log.debug("DefaultWorkflowPostActionFactory.create: type={}", type)
        return when (type) {
            "SET_FIELD" -> createSetField(config)
            "NOTIFY" -> createNotify(config)
            "ADD_WATCHER" -> createAddWatcher(config)
            "RUN_AUTOMATION" -> createRunAutomation(config)
            "CALL_WEBHOOK" -> createCallWebhook(config)
            else -> throw IllegalArgumentException("지원하지 않는 PostAction type: '$type'")
        }
    }

    private fun createSetField(config: Map<String, Any?>): SetFieldPostAction {
        val field = requireConfigString(config, "field")
        val value = config["value"]
        return SetFieldPostAction(field = field, value = value)
    }

    private fun createNotify(config: Map<String, Any?>): NotifyPostAction {
        val channel = requireConfigString(config, "channel")
        val recipients = requireConfigString(config, "recipients")
        return NotifyPostAction(channel = channel, recipients = recipients)
    }

    private fun createAddWatcher(config: Map<String, Any?>): AddWatcherPostAction {
        val watcher = requireConfigString(config, "watcher")
        return AddWatcherPostAction(watcher = watcher)
    }

    private fun createRunAutomation(config: Map<String, Any?>): RunAutomationPostAction {
        val automationKey = requireConfigString(config, "automationKey")
        return RunAutomationPostAction(automationKey = automationKey)
    }

    private fun createCallWebhook(config: Map<String, Any?>): CallWebhookPostAction {
        val url = requireConfigString(config, "url")
        val method = requireConfigString(config, "method")
        return CallWebhookPostAction(url = url, method = method)
    }

    /**
     * config 에서 키에 해당하는 String 값을 추출한다.
     *
     * [DefaultWorkflowValidatorFactory] 의 동일 헬퍼와 구조가 같다.
     * 현재는 각 팩토리가 자체 private 헬퍼를 보유한다.
     * 두 팩토리를 공통 상위 클래스로 추출하는 것은 스코프 확대이므로 지양한다.
     *
     * @param config PostAction config 맵.
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
                ?: throw IllegalArgumentException("PostAction config 에 필수 키 '$key' 가 없습니다")
        return value as? String
            ?: throw IllegalArgumentException("PostAction config['$key'] 는 String 이어야 합니다: $value")
    }
}
