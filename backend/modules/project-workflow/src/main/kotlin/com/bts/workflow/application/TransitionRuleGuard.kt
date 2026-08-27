// 전환 규칙(validator · post-action)이 저장되기 전에 지나는 관문 한 벌 — 편집 API 와 발행이 공유한다

package com.bts.workflow.application

import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.validator.isEditable
import org.springframework.stereotype.Component

/**
 * 전환 규칙이 `workflow_validators` · `workflow_post_actions` 에 앉기 전에 지나는 관문.
 *
 * ### 왜 한 벌이어야 하나
 * 그 두 표에 쓰는 경로가 **둘**이다 — 전용 편집 API(`ValidatorAdminService` ·
 * `PostActionAdminService`)와 발행([DraftRuleWriter]). 관문이 앞쪽에만 있으면 「초안 저장 →
 * 발행」 두 번으로 통째로 우회된다. 그것이 PR #411 리뷰가 BLOCKER 로 잡은 결함이고, 사본을
 * 두 벌 두면 다음에 관문이 하나 늘 때 같은 우회가 다시 살아난다.
 *
 * ### 세 관문
 * 1. **팩토리 dry-run** — 미지원 type 과 필수 config 키 누락을 거른다. 이것이 없으면 오타 하나가
 *    저장 200 · 발행 200 을 통과한 뒤 **그 전환을 처음 시도한 이슈**에서야 터진다.
 * 2. **`CALL_WEBHOOK` url 스킴** — `http://` · `https://` 만 받는다. 없으면 `file://` ·
 *    `gopher://` · 링크-로컬 주소가 그대로 앉고, 그 행은 전환 실행 때 후속 BC 가 디스패치한다.
 * 3. **`isEditable` 허용목록** — 명시 등재된 validator 만 통과한다(fail-closed).
 *    `CustomExpressionValidator` 가 빠진 근거는 `expression/SpelEvaluator` 의 「일반 사용자가
 *    API 를 통해 임의 표현식을 전달하는 경로를 **절대로 만들지 않는다**」다.
 *
 * ### 거절 사유를 왜 나눠 던지나
 * 호출부마다 응답 계약이 다르다 — 편집 API 는 「미지원」과 「편집 불가」에 서로 다른 에러 코드를
 * 쓰고 화면이 그것으로 안내를 가른다. 사유를 [TransitionRuleRejected.Reason] 으로 실어 보내면
 * 판정은 한 벌로 두고 표현만 호출부가 정할 수 있다.
 */
@Component
class TransitionRuleGuard(
    private val validatorFactory: WorkflowValidatorFactory,
    private val postActionFactory: WorkflowPostActionFactory,
) {
    /**
     * validator 하나가 저장 가능한지 본다.
     *
     * @throws TransitionRuleRejected 미지원 type · config 누락 · 편집 불가 타입일 때
     */
    fun checkValidator(
        type: String,
        config: Map<String, Any?>,
    ) {
        val instance =
            try {
                validatorFactory.create(type, config)
            } catch (ex: IllegalArgumentException) {
                throw TransitionRuleRejected(
                    TransitionRuleRejected.Reason.UNSUPPORTED,
                    ex.message ?: "validator '$type' 을 만들 수 없다",
                    ex,
                )
            }

        if (!isEditable(instance)) {
            throw TransitionRuleRejected(
                TransitionRuleRejected.Reason.NOT_EDITABLE,
                "validator '$type' 은 이 API 로 편집할 수 없는 타입이다",
            )
        }
    }

    /**
     * post-action 하나가 저장 가능한지 본다.
     *
     * @throws TransitionRuleRejected 웹훅 스킴이 어긋나거나 미지원 type · config 누락일 때
     */
    fun checkPostAction(
        type: String,
        config: Map<String, Any?>,
    ) {
        if (type == CALL_WEBHOOK) {
            val url = config["url"] as? String ?: ""
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw TransitionRuleRejected(
                    TransitionRuleRejected.Reason.UNSUPPORTED,
                    "CALL_WEBHOOK url 은 http:// 또는 https:// 로 시작해야 합니다: '$url'",
                )
            }
        }

        try {
            postActionFactory.create(type, config)
        } catch (ex: IllegalArgumentException) {
            throw TransitionRuleRejected(
                TransitionRuleRejected.Reason.UNSUPPORTED,
                ex.message ?: "post-action '$type' 을 만들 수 없다",
                ex,
            )
        }
    }

    private companion object {
        /** `PostActionAdminService` 와 같은 문자열이다 — 팩토리 `when` 분기의 값이 정본. */
        const val CALL_WEBHOOK = "CALL_WEBHOOK"
    }
}

/**
 * 관문이 규칙 하나를 거절했다.
 *
 * 프레임워크 예외가 아니라 **판정 결과**다. 호출부가 [reason] 을 보고 자기 계약의 에러 코드로
 * 옮긴다 — 편집 API 는 400 두 종류로, 발행은 400 하나로 접는다.
 *
 * @property reason 거절 사유. 표현을 호출부가 정하기 위한 축이다.
 */
class TransitionRuleRejected(
    val reason: Reason,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** 거절 사유. */
    enum class Reason {
        /** 팩토리가 만들 수 없다 — 미지원 type 이거나 필수 config 키가 없다. */
        UNSUPPORTED,

        /** 만들 수는 있으나 이 경로로 쓰기가 허용되지 않은 타입이다. */
        NOT_EDITABLE,
    }
}
