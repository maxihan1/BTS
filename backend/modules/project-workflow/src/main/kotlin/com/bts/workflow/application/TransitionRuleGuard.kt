// 전환 규칙(validator · post-action)이 저장되기 전에 지나는 관문 한 벌 — 편집 API 와 발행이 공유한다

package com.bts.workflow.application

import com.bts.workflow.domain.exception.TransitionConflictException
import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.repository.WorkflowDraftRepository
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
 * ### 네 관문
 * 1. **팩토리 dry-run** — 미지원 type 과 필수 config 키 누락을 거른다. 이것이 없으면 오타 하나가
 *    저장 200 · 발행 200 을 통과한 뒤 **그 전환을 처음 시도한 이슈**에서야 터진다.
 * 2. **`CALL_WEBHOOK` url 스킴** — `http://` · `https://` 만 받는다. 없으면 `file://` ·
 *    `gopher://` · 링크-로컬 주소가 그대로 앉고, 그 행은 전환 실행 때 후속 BC 가 디스패치한다.
 * 3. **`isEditable` 허용목록** — 명시 등재된 validator 만 통과한다(fail-closed).
 *    `CustomExpressionValidator` 가 빠진 근거는 `expression/SpelEvaluator` 의 「일반 사용자가
 *    API 를 통해 임의 표현식을 전달하는 경로를 **절대로 만들지 않는다**」다.
 * 4. **실행되는 post-action 허용목록** — 전환 때 실제로 소비하는 곳이 있는 type 만 받는다(WF-03-1).
 *    `SET_FIELD` 의 `fieldChanges` 는 issue-tracking 이 적용하지 않고, `NOTIFY` · `ADD_WATCHER` ·
 *    `RUN_AUTOMATION` 의 이벤트는 웹훅 워커가 지원 타입이 아니라며 버린다. 받아 두면 저장 200 뒤
 *    아무 일도 일어나지 않는다. 넷을 실제로 돌리는 일은 BC 별 FR 후보다
 *    (`docs/specs/2026-10-20-workflow-jira-parity-gap.md` §6-5) — 소비처가 생기면 여기 한 줄을 더한다.
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
     * @throws TransitionRuleRejected 웹훅 스킴이 어긋나거나 미지원 type · config 누락 · 실행하는 곳이 없는 type 일 때
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

        if (type !in RUNTIME_CONSUMED_POST_ACTIONS) {
            throw TransitionRuleRejected(
                TransitionRuleRejected.Reason.UNSUPPORTED,
                "post-action '$type' 은 아직 전환 때 실행하는 곳이 없어 저장할 수 없다 — 지금은 $CALL_WEBHOOK 만 동작한다",
            )
        }
    }

    private companion object {
        /** `PostActionAdminService` 와 같은 문자열이다 — 팩토리 `when` 분기의 값이 정본. */
        const val CALL_WEBHOOK = "CALL_WEBHOOK"

        /** 전환 때 실제로 소비되는 post-action — `WebhookDispatchWorker` 가 `WebhookRequested` 만 읽는다. */
        val RUNTIME_CONSUMED_POST_ACTIONS = setOf(CALL_WEBHOOK)
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

/**
 * 초안이 열린 워크플로우의 전환 규칙 편집을 거절한다 — 편집 API(`ValidatorAdminService` ·
 * `PostActionAdminService`) 전용 관문이다 (WF-08-1).
 *
 * 발행은 전환을 전량 교체하고 규칙을 **초안의 것으로** 다시 심는다([DraftRuleWriter]). 초안이 열린
 * 동안 라이브 규칙을 고치면 다음 발행이 그 편집을 말없이 지운다. 규칙 CRUD 가 `workflows.version`
 * 을 올리지 않아 발행의 `base_version` 검사로도 잡히지 않으므로, 쓰는 순간에 막는다.
 *
 * @throws TransitionConflictException 초안이 열려 있을 때(409).
 */
internal fun WorkflowDraftRepository.requireNoOpenDraft(workflowKey: String) {
    if (existsForLiveWorkflow(workflowKey)) {
        throw TransitionConflictException(
            workflowKey,
            "초안이 열려 있어 전환 규칙을 고칠 수 없습니다. 초안을 발행하거나 폐기한 뒤 다시 시도해 주세요.",
        )
    }
}
