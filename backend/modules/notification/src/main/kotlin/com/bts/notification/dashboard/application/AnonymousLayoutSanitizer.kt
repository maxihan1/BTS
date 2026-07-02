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
 * 원본 그대로 통과시키고(유일한 화이트리스트), 그 외 모든 항목은 config 를 제거한 플레이스홀더로 치환한다.
 *
 * "그 외 모든 항목" 은 데이터 가젯 + 카탈로그 밖 미지 타입 + gadgetType 이 없거나 null 인 legacy 타일을
 * 모두 포함한다. gadgetType 이 없다는 이유로 legacy 를 원본 통과시키면 그 항목에 실린 config(예: aql/filterId)가
 * 익명 뷰어에게 새어나가므로, legacy 도 STATIC 화이트리스트에 걸리지 않는 한 반드시 좁힌다.
 *
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
     * - `gadgetType` 이 [GadgetType.fromKey] 로 조회되고 category 가 [GadgetCategory.STATIC] 이면
     *   config 를 포함한 원본 그대로 유지한다(유일한 화이트리스트).
     * - 그 외 전부 — 데이터 가젯 · 카탈로그 밖 미지 타입 · `gadgetType` 이 없거나 null 인 legacy 항목 —
     *   [POSITION_FIELDS] 만 남기고 `requiresAuth: true` 를 추가한 플레이스홀더로 치환한다(config 제거).
     *   gadgetType 이 있으면 플레이스홀더에도 유지하고, legacy 처럼 없으면 gadgetType 없이 방출한다.
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
        val gadgetTypeNode = item.get(FIELD_GADGET_TYPE)?.takeUnless { it.isNull }
        val gadgetType = gadgetTypeNode?.let { GadgetType.fromKey(it.asText()) }
        return if (gadgetType?.category == GadgetCategory.STATIC) {
            // 유일한 화이트리스트 — STATIC 가젯만 config 포함 원본 통과
            item
        } else {
            // 데이터 가젯 · 카탈로그 밖 미지 타입 · gadgetType 없는 legacy — fail-closed 플레이스홀더로 치환
            placeholder(item, gadgetTypeNode)
        }
    }

    /**
     * 위치 필드(+ gadgetType 이 있으면 그것)만 유지하고 config 를 제거한 플레이스홀더를 만든다.
     * legacy 항목처럼 gadgetType 이 없으면(null) gadgetType 필드 없이 위치 필드 + requiresAuth 만 방출한다.
     */
    private fun placeholder(
        item: JsonNode,
        gadgetTypeNode: JsonNode?,
    ): ObjectNode {
        val node = mapper.createObjectNode()
        POSITION_FIELDS.forEach { field ->
            item.get(field)?.let { node.set<JsonNode>(field, it) }
        }
        gadgetTypeNode?.let { node.set<JsonNode>(FIELD_GADGET_TYPE, it) }
        node.put(FIELD_REQUIRES_AUTH, true)
        return node
    }
}
