// 익명 공유 뷰용 layout 정화기 — 정적 가젯(STATIC)만 원본 통과시키는 fail-closed 화이트리스트

package com.bts.notification.dashboard.application

import com.bts.notification.dashboard.domain.GadgetCategory
import com.bts.notification.dashboard.domain.GadgetType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory

/**
 * 익명(비로그인) 대시보드 공유 뷰에서 layout 을 정화하는 순수 함수 유틸리티.
 *
 * 익명 뷰어는 인증 세션이 없어 이슈/차트 등 데이터를 조회할 권한이 없다.
 * 따라서 [GadgetCategory.STATIC] (text_widget, link_list) 가젯만 config 를 포함해
 * 원본 그대로 통과시키고, 그 외 모든 가젯은 config 를 제거한 플레이스홀더로 치환한다.
 *
 * 카탈로그에 없는(알 수 없는) gadgetType 도 안전하지 않은 것으로 간주해 플레이스홀더로 치환한다 —
 * "모르면 차단(fail-closed)"이 "모르면 통과(fail-open)"보다 항상 안전하다.
 * 향후 카탈로그에 새 가젯 타입이 추가돼도, 명시적으로 STATIC 으로 분류하지 않는 한
 * 이 화이트리스트에서 자동으로 차단된다.
 */
object AnonymousLayoutSanitizer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()

    private const val EMPTY_LAYOUT = "[]"
    private const val FIELD_GADGET_TYPE = "gadgetType"
    private const val FIELD_REQUIRES_AUTH = "requiresAuth"

    /** 플레이스홀더에 유지하는 위치 필드 — 그리드 배치 정보만, config 는 절대 포함하지 않는다. */
    private val POSITION_FIELDS = listOf("i", "x", "y", "w", "h")

    /**
     * layout JSON 배열을 익명 뷰용으로 정화한다.
     *
     * 항목별 판정.
     * - `gadgetType` 필드가 없거나 null 이면 legacy 타일로 간주해 원본 그대로 유지한다.
     * - `gadgetType` 이 [GadgetType.fromKey] 로 조회되고 category 가 [GadgetCategory.STATIC] 이면
     *   config 를 포함한 원본 그대로 유지한다.
     * - 그 외(데이터 가젯이거나 카탈로그 밖 미지 타입)는 [POSITION_FIELDS] + gadgetType 만 남기고
     *   `requiresAuth: true` 를 추가한 플레이스홀더로 치환한다(config 제거).
     *
     * 어떤 입력에도 예외를 던지지 않는 total 함수다. layout 은 [com.bts.notification.dashboard.domain.Dashboard]
     * 도메인 팩토리에서 이미 검증된 값이 정상 경로로 들어오지만, 방어적으로 파싱 실패 시
     * 데이터 누출을 막기 위해 빈 배열을 반환한다(fail-closed).
     *
     * @param layoutJson 정화할 layout JSON 배열 문자열
     * @return 정화된 layout JSON 배열 문자열. 파싱 실패 시 `"[]"`.
     */
    @Suppress("TooGenericExceptionCaught") // total 함수 보장 — 파싱 실패도 예외 전파 없이 빈 배열로 fail-closed
    fun sanitize(layoutJson: String): String =
        try {
            val root = mapper.readTree(layoutJson)
            if (root == null || !root.isArray) {
                log.warn("layout 정화 실패 — JSON 배열이 아님, 빈 배열로 대체")
                EMPTY_LAYOUT
            } else {
                val sanitized = mapper.createArrayNode()
                root.forEach { item -> sanitized.add(sanitizeItem(item)) }
                mapper.writeValueAsString(sanitized)
            }
        } catch (e: Exception) {
            log.warn("layout 정화 중 예외 발생 — 빈 배열로 대체: {}", e.javaClass.simpleName)
            EMPTY_LAYOUT
        }

    /** layout 배열의 단일 항목을 화이트리스트 규칙에 따라 정화한다. */
    private fun sanitizeItem(item: JsonNode): JsonNode {
        val gadgetTypeNode = item.get(FIELD_GADGET_TYPE)
        val gadgetType = gadgetTypeNode?.takeUnless { it.isNull }?.let { GadgetType.fromKey(it.asText()) }
        return when {
            // legacy 타일(gadgetType 미지정) — config 가 없으므로 정화 대상 아님
            gadgetTypeNode == null || gadgetTypeNode.isNull -> item
            gadgetType?.category == GadgetCategory.STATIC -> item
            // 데이터 가젯 또는 카탈로그 밖 미지 타입 — fail-closed 플레이스홀더로 치환
            else -> placeholder(item, gadgetTypeNode)
        }
    }

    /** 위치 필드 + gadgetType 만 유지하고 config 를 제거한 플레이스홀더를 만든다. */
    private fun placeholder(
        item: JsonNode,
        gadgetTypeNode: JsonNode,
    ): ObjectNode {
        val node = mapper.createObjectNode()
        POSITION_FIELDS.forEach { field ->
            item.get(field)?.let { node.set<JsonNode>(field, it) }
        }
        node.set<JsonNode>(FIELD_GADGET_TYPE, gadgetTypeNode)
        node.put(FIELD_REQUIRES_AUTH, true)
        return node
    }
}
