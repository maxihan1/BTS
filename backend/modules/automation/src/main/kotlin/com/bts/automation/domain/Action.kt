// 자동화 액션 다형성 — SetField/Assign/AddComment/CallWebhook (FR-AT-02, ADR D1)

package com.bts.automation.domain

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

/**
 * 자동화 룰이 발화 시 실행하는 액션의 다형성 표현.
 *
 * `action_config` JSON 문자열([ActionType] 별 형식)을 [fromJson] 으로 파싱한 결과다. 대상 존재/권한
 * (예: assigneeId 가 실제 프로젝트 멤버인지, field 가 실존 이슈 필드인지)은 검증하지 않는다 — 실행
 * 시점 issue-tracking 위임([TriggerConfig] 선례 동형).
 *
 * 실제 파싱/검증 로직은 이 파일의 private 최상위 함수([parseSetField] 등)에 위임한다 — companion object
 * 를 얇게 유지해 detekt `TooManyFunctions` 를 회피하면서, 파싱 세부는 외부에 노출하지 않는다.
 *
 * - [SetFieldAction] — 이슈 필드 값 설정
 * - [AssignAction] — 담당자 지정/해제
 * - [AddCommentAction] — 댓글 추가
 * - [CallWebhookAction] — 아웃바운드 웹훅 호출
 */
sealed class Action {
    /**
     * 이슈 필드 값을 설정하는 액션.
     *
     * @property field 설정할 이슈 필드명. 빈 문자열 불가
     * @property value 설정할 값(임의 JSON). 필드별 타입 검증은 실행 시점 issue-tracking 위임
     */
    data class SetFieldAction(val field: String, val value: JsonNode) : Action()

    /**
     * 담당자를 지정하거나 해제하는 액션.
     *
     * @property assigneeId 지정할 담당자 ID. `null` 이면 담당자 해제
     */
    data class AssignAction(val assigneeId: UUID?) : Action()

    /**
     * 댓글을 추가하는 액션.
     *
     * @property body 댓글 본문. 빈 문자열(공백만 포함) 불가. `{{템플릿}}` 치환은 실행 시점 책임(형식
     *   검증 범위 밖)
     */
    data class AddCommentAction(val body: String) : Action()

    /**
     * 아웃바운드 웹훅을 호출하는 액션.
     *
     * @property url 호출할 URL. http(s) 스킴만 허용. 빈 문자열 불가
     * @property method HTTP 메서드. 기본값 [DEFAULT_METHOD]
     * @property headers 요청 헤더. 기본값 빈 맵
     * @property body 요청 본문. 기본값 [DEFAULT_BODY]. SSRF 검증은 실행 시점 책임(형식 검증 범위 밖)
     */
    data class CallWebhookAction(
        val url: String,
        val method: String = DEFAULT_METHOD,
        val headers: Map<String, String> = emptyMap(),
        val body: String = DEFAULT_BODY,
    ) : Action()

    companion object {
        /** [CallWebhookAction.method] 기본값. */
        const val DEFAULT_METHOD: String = "POST"

        /** [CallWebhookAction.body] 기본값. */
        const val DEFAULT_BODY: String = ""

        /**
         * `actionType` 에 맞는 형식으로 `configJson` 을 파싱해 [Action] 인스턴스를 생성한다.
         *
         * @param actionType 파싱 기준이 되는 액션 타입
         * @param configJson 파싱할 action_config JSON 문자열
         * @return 형식 검증을 통과한 타입별 [Action] 인스턴스
         * @throws ActionConfigInvalidException 형식을 위반한 경우
         */
        fun fromJson(
            actionType: ActionType,
            configJson: String,
        ): Action {
            val node = parseJsonObject(configJson)
            return when (actionType) {
                ActionType.SET_FIELD -> parseSetField(node)
                ActionType.ASSIGN -> parseAssign(node)
                ActionType.ADD_COMMENT -> parseAddComment(node)
                ActionType.CALL_WEBHOOK -> parseCallWebhook(node)
            }
        }
    }
}

private const val SCHEME_HTTP = "http"
private const val SCHEME_HTTPS = "https"

private const val FIELD_FIELD = "field"
private const val FIELD_VALUE = "value"
private const val FIELD_ASSIGNEE_ID = "assigneeId"
private const val FIELD_BODY = "body"
private const val FIELD_URL = "url"
private const val FIELD_METHOD = "method"
private const val FIELD_HEADERS = "headers"

private const val MSG_INVALID_JSON = "action_config는 유효한 JSON 객체여야 합니다."
private const val MSG_SET_FIELD_FIELD_REQUIRED = "SET_FIELD 액션은 비어있지 않은 field 문자열이 필요합니다."
private const val MSG_SET_FIELD_VALUE_REQUIRED = "SET_FIELD 액션은 value 필드가 필요합니다."
private const val MSG_ASSIGN_ASSIGNEE_ID_REQUIRED = "ASSIGN 액션은 assigneeId 필드가 필요합니다."
private const val MSG_ASSIGN_ASSIGNEE_ID_INVALID = "ASSIGN 액션의 assigneeId는 uuid 문자열 또는 null이어야 합니다."
private const val MSG_ADD_COMMENT_BODY_REQUIRED = "ADD_COMMENT 액션은 비어있지 않은 body 문자열이 필요합니다."
private const val MSG_CALL_WEBHOOK_URL_REQUIRED = "CALL_WEBHOOK 액션은 비어있지 않은 url 문자열이 필요합니다."
private const val MSG_CALL_WEBHOOK_METHOD_INVALID = "CALL_WEBHOOK 액션의 method는 비어있지 않은 문자열이어야 합니다."
private const val MSG_CALL_WEBHOOK_HEADERS_INVALID = "CALL_WEBHOOK 액션의 headers는 JSON 객체여야 합니다."
private const val MSG_CALL_WEBHOOK_HEADERS_VALUE_INVALID = "CALL_WEBHOOK 액션의 headers 값은 문자열이어야 합니다."
private const val MSG_CALL_WEBHOOK_BODY_INVALID = "CALL_WEBHOOK 액션의 body는 문자열이어야 합니다."

private val objectMapper = ObjectMapper()

/** `configJson` 을 파싱해 JSON 객체 노드를 반환한다. 빈 문자열/파싱 불가/객체가 아니면 예외. */
private fun parseJsonObject(configJson: String): JsonNode {
    fun readTree(): JsonNode =
        try {
            objectMapper.readTree(configJson)
        } catch (e: JsonParseException) {
            throw ActionConfigInvalidException(MSG_INVALID_JSON, e)
        }

    if (configJson.isBlank()) {
        throw ActionConfigInvalidException(MSG_INVALID_JSON)
    }
    val node = readTree()
    if (!node.isObject) {
        throw ActionConfigInvalidException(MSG_INVALID_JSON)
    }
    return node
}

/** SET_FIELD 액션 config `{field, value}` 를 파싱한다. */
private fun parseSetField(node: JsonNode): Action.SetFieldAction {
    val field = requireNonBlankText(node, FIELD_FIELD, MSG_SET_FIELD_FIELD_REQUIRED)
    val value = node.get(FIELD_VALUE) ?: throw ActionConfigInvalidException(MSG_SET_FIELD_VALUE_REQUIRED)
    return Action.SetFieldAction(field = field, value = value)
}

/** ASSIGN 액션 config `{assigneeId}` 를 파싱한다. `assigneeId` 는 uuid 문자열 또는 null. */
private fun parseAssign(node: JsonNode): Action.AssignAction {
    fun parseUuid(text: String): UUID =
        try {
            UUID.fromString(text)
        } catch (e: IllegalArgumentException) {
            throw ActionConfigInvalidException(MSG_ASSIGN_ASSIGNEE_ID_INVALID, e)
        }

    val assigneeNode =
        node.get(FIELD_ASSIGNEE_ID) ?: throw ActionConfigInvalidException(MSG_ASSIGN_ASSIGNEE_ID_REQUIRED)
    if (assigneeNode.isNull) {
        return Action.AssignAction(assigneeId = null)
    }
    if (!assigneeNode.isTextual) {
        throw ActionConfigInvalidException(MSG_ASSIGN_ASSIGNEE_ID_INVALID)
    }
    return Action.AssignAction(assigneeId = parseUuid(assigneeNode.asText()))
}

/** ADD_COMMENT 액션 config `{body}` 를 파싱한다. `body` 는 비어있지 않은 문자열. */
private fun parseAddComment(node: JsonNode): Action.AddCommentAction {
    val body = requireNonBlankText(node, FIELD_BODY, MSG_ADD_COMMENT_BODY_REQUIRED)
    return Action.AddCommentAction(body = body)
}

/** CALL_WEBHOOK 액션 config `{url, method?, headers?, body?}` 를 파싱한다. */
private fun parseCallWebhook(node: JsonNode): Action.CallWebhookAction {
    val url = requireNonBlankText(node, FIELD_URL, MSG_CALL_WEBHOOK_URL_REQUIRED)
    validateUrlScheme(url)
    return Action.CallWebhookAction(
        url = url,
        method = parseOptionalMethod(node),
        headers = parseOptionalHeaders(node),
        body = parseOptionalBody(node),
    )
}

private fun validateUrlScheme(url: String) {
    val uri =
        try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw ActionConfigInvalidException("url을 파싱할 수 없습니다: $url", e)
        }
    val scheme = uri.scheme?.lowercase()
    if (scheme != SCHEME_HTTP && scheme != SCHEME_HTTPS) {
        throw ActionConfigInvalidException("url은 http(s) 스킴이어야 합니다: $url")
    }
}

private fun parseOptionalMethod(node: JsonNode): String {
    val methodNode = node.get(FIELD_METHOD)
    if (methodNode == null || methodNode.isNull) {
        return Action.DEFAULT_METHOD
    }
    if (!methodNode.isTextual || methodNode.asText().isBlank()) {
        throw ActionConfigInvalidException(MSG_CALL_WEBHOOK_METHOD_INVALID)
    }
    return methodNode.asText()
}

private fun parseOptionalBody(node: JsonNode): String {
    val bodyNode = node.get(FIELD_BODY)
    if (bodyNode == null || bodyNode.isNull) {
        return Action.DEFAULT_BODY
    }
    if (!bodyNode.isTextual) {
        throw ActionConfigInvalidException(MSG_CALL_WEBHOOK_BODY_INVALID)
    }
    return bodyNode.asText()
}

private fun parseOptionalHeaders(node: JsonNode): Map<String, String> {
    val headersNode = node.get(FIELD_HEADERS)
    if (headersNode == null || headersNode.isNull) {
        return emptyMap()
    }
    if (!headersNode.isObject) {
        throw ActionConfigInvalidException(MSG_CALL_WEBHOOK_HEADERS_INVALID)
    }
    return headersNode.fields().asSequence().associate { (key, value) ->
        if (!value.isTextual) {
            throw ActionConfigInvalidException(MSG_CALL_WEBHOOK_HEADERS_VALUE_INVALID)
        }
        key to value.asText()
    }
}

private fun requireNonBlankText(
    node: JsonNode,
    field: String,
    errorMessage: String,
): String {
    val fieldNode = node.get(field)
    if (fieldNode == null || !fieldNode.isTextual || fieldNode.asText().isBlank()) {
        throw ActionConfigInvalidException(errorMessage)
    }
    return fieldNode.asText()
}
