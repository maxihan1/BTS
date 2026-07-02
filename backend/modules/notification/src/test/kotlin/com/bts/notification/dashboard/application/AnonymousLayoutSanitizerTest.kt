// AnonymousLayoutSanitizer 익명 뷰 layout 정화 단위 테스트 — fail-closed 화이트리스트 시나리오

package com.bts.notification.dashboard.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [AnonymousLayoutSanitizer.sanitize] 의 fail-closed 화이트리스트 규칙을 검증한다.
 *
 * [com.bts.notification.dashboard.domain.GadgetCategory.STATIC] (text_widget/link_list) 만
 * 원본 그대로 통과시키고, 그 외(데이터 가젯·미지 타입·gadgetType 없는 legacy)는 config 를 제거한
 * requiresAuth=true 플레이스홀더로 치환한다.
 */
class AnonymousLayoutSanitizerTest {
    private val mapper = ObjectMapper()

    /** SAN-1. text_widget(STATIC) 항목은 config 를 포함해 원본 그대로 유지된다. */
    @Test
    fun `text_widget STATIC 항목은 config 포함 원본 그대로 유지된다`() {
        val layout =
            """
            [{"i":"a","x":0,"y":0,"w":2,"h":2,"gadgetType":"text_widget","config":{"markdown":"hello"}}]
            """.trimIndent()

        val result = AnonymousLayoutSanitizer.sanitize(layout)

        assertThat(mapper.readTree(result)).isEqualTo(mapper.readTree(layout))
    }

    /** SAN-2. link_list(STATIC) 항목은 config 를 포함해 원본 그대로 유지된다. */
    @Test
    fun `link_list STATIC 항목은 config 포함 원본 그대로 유지된다`() {
        val layout =
            """
            [{"i":"b","x":1,"y":1,"w":3,"h":2,"gadgetType":"link_list",
            "config":{"links":[{"label":"BTS","url":"https://example.com"}]}}]
            """.trimIndent()

        val result = AnonymousLayoutSanitizer.sanitize(layout)

        assertThat(mapper.readTree(result)).isEqualTo(mapper.readTree(layout))
    }

    /** SAN-3. 데이터 가젯(assigned_to_me)은 config 를 제거하고 requiresAuth=true 플레이스홀더로 치환된다. */
    @Test
    fun `assigned_to_me 데이터 가젯은 config 제거 후 requiresAuth true 플레이스홀더로 치환된다`() {
        val layout =
            """
            [{"i":"c","x":2,"y":2,"w":4,"h":3,"gadgetType":"assigned_to_me","config":{"projectKey":"BTS"}}]
            """.trimIndent()

        val result = AnonymousLayoutSanitizer.sanitize(layout)
        val item = mapper.readTree(result).single()

        assertThat(item.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("i", "x", "y", "w", "h", "gadgetType", "requiresAuth")
        assertThat(item.get("requiresAuth").asBoolean()).isTrue()
        assertThat(item.get("gadgetType").asText()).isEqualTo("assigned_to_me")
        assertThat(item.has("config")).isFalse()
        // config 값(projectKey="BTS") 이 결과 어디에도 누출되지 않는다.
        assertThat(result).doesNotContain("projectKey")
    }

    /** SAN-4. 차트 가젯(pie_chart)도 동일하게 config 제거 후 플레이스홀더로 치환된다. */
    @Test
    fun `pie_chart 데이터 가젯은 config 제거 후 requiresAuth true 플레이스홀더로 치환된다`() {
        val layout =
            """
            [{"i":"d","x":0,"y":4,"w":4,"h":3,"gadgetType":"pie_chart","config":{"field":"status"}}]
            """.trimIndent()

        val result = AnonymousLayoutSanitizer.sanitize(layout)
        val item = mapper.readTree(result).single()

        assertThat(item.has("config")).isFalse()
        assertThat(item.get("requiresAuth").asBoolean()).isTrue()
    }

    /** SAN-5. gadgetType 미지정(legacy 타일)도 위치필드만 + requiresAuth=true 로 방출된다(config 없어도 fail-closed). */
    @Test
    fun `gadgetType 미지정 legacy 타일도 위치필드만 requiresAuth true 로 방출된다`() {
        val layout = """[{"i":"e","x":3,"y":0,"w":1,"h":1}]"""

        val result = AnonymousLayoutSanitizer.sanitize(layout)
        val item = mapper.readTree(result).single()

        assertThat(item.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("i", "x", "y", "w", "h", "requiresAuth")
        assertThat(item.get("requiresAuth").asBoolean()).isTrue()
    }

    /**
     * SAN-9. gadgetType 이 없는 legacy 항목이 config(aql/filterId)를 실었으면
     * config 를 제거하고 위치필드만 + requiresAuth=true 로 방출한다(legacy fail-closed 강화).
     *
     * gadgetType 이 없다는 이유로 원본을 통과시키면 legacy 항목에 실린 config(데이터 쿼리)가
     * 익명 뷰어에게 그대로 새어나간다. STATIC 화이트리스트에 걸리지 않는 legacy 도 반드시 좁힌다.
     */
    @Test
    fun `gadgetType 없는 legacy config 항목은 config 제거 후 위치필드만 requiresAuth true 로 방출된다`() {
        val layout =
            """
            [{"i":"g","x":1,"y":2,"w":3,"h":4,"config":{"aql":"project = BTS","filterId":"42"}}]
            """.trimIndent()

        val result = AnonymousLayoutSanitizer.sanitize(layout)
        val item = mapper.readTree(result).single()

        assertThat(item.fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("i", "x", "y", "w", "h", "requiresAuth")
        assertThat(item.get("requiresAuth").asBoolean()).isTrue()
        assertThat(item.has("config")).isFalse()
        assertThat(item.has("gadgetType")).isFalse()
        // config 값(aql/filterId) 이 결과 어디에도 누출되지 않는다.
        assertThat(result).doesNotContain("aql")
        assertThat(result).doesNotContain("filterId")
    }

    /** SAN-6. 카탈로그 밖 알 수 없는 gadgetType 은 fail-closed 로 플레이스홀더로 치환되며 통과하지 않는다. */
    @Test
    fun `알 수 없는 gadgetType 은 fail-closed 로 플레이스홀더로 치환된다`() {
        val layout =
            """
            [{"i":"f","x":0,"y":0,"w":2,"h":2,"gadgetType":"totally_unknown_type",
            "config":{"secret":"leak-me"}}]
            """.trimIndent()

        val result = AnonymousLayoutSanitizer.sanitize(layout)
        val item = mapper.readTree(result).single()

        assertThat(item.has("config")).isFalse()
        assertThat(item.get("requiresAuth").asBoolean()).isTrue()
        assertThat(result).doesNotContain("leak-me")
    }

    /** SAN-7. 빈 배열 layout 은 빈 배열을 반환한다. */
    @Test
    fun `빈 배열 layout 은 빈 배열을 반환한다`() {
        val result = AnonymousLayoutSanitizer.sanitize("[]")

        assertThat(mapper.readTree(result)).isEqualTo(mapper.readTree("[]"))
    }

    /**
     * SAN-8. 정화 결과에는 어떤 항목에도 대시보드 메타(ownerId/version)가 섞이지 않는다.
     *
     * sanitize 는 layout 배열만 입력받아 항목 단위로만 정화하므로,
     * Dashboard Aggregate 의 ownerId/version 등은 애초에 함수 시그니처에 존재하지 않는다.
     * STATIC/데이터/legacy/미지 타입을 섞은 layout 을 정화해도
     * 출력 어디에도 "ownerId"·"version" 문자열이 나타나지 않음을 확인한다.
     */
    @Test
    fun `정화 결과 어디에도 ownerId version 등 대시보드 메타가 섞이지 않는다`() {
        val layout =
            """
            [
              {"i":"a","x":0,"y":0,"w":2,"h":2,"gadgetType":"text_widget","config":{"markdown":"hi"}},
              {"i":"b","x":2,"y":0,"w":2,"h":2,"gadgetType":"assigned_to_me","config":{"projectKey":"BTS"}},
              {"i":"c","x":4,"y":0,"w":1,"h":1},
              {"i":"d","x":0,"y":2,"w":2,"h":2,"gadgetType":"unknown_gadget","config":{"foo":"bar"}}
            ]
            """.trimIndent()

        val result = AnonymousLayoutSanitizer.sanitize(layout)

        assertThat(result).doesNotContain("ownerId")
        assertThat(result).doesNotContain("\"version\"")
    }
}
