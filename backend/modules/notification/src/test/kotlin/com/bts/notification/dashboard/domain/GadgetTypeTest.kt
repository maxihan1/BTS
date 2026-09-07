// GadgetType 열거형 — 12종 가젯 카탈로그·config 형식 검증·단일 출처 불변식 테스트

package com.bts.notification.dashboard.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class GadgetTypeTest : DescribeSpec({

    val mapper = ObjectMapper()

    fun json(value: String): JsonNode = mapper.readTree(value)

    // ── enum 12종 + category·enabled ──────────────────────────────────────────

    describe("GadgetType — enum 12종 정의") {
        it("12종 모두 정의된다") {
            GadgetType.entries.size shouldBe 12
        }

        it("category 가 정확히 매핑된다") {
            GadgetType.ASSIGNED_TO_ME.category shouldBe GadgetCategory.ISSUE
            GadgetType.RECENTLY_CREATED.category shouldBe GadgetCategory.ISSUE
            GadgetType.FILTER_RESULT.category shouldBe GadgetCategory.ISSUE
            GadgetType.ISSUE_COUNT.category shouldBe GadgetCategory.ISSUE
            GadgetType.TEXT_WIDGET.category shouldBe GadgetCategory.STATIC
            GadgetType.LINK_LIST.category shouldBe GadgetCategory.STATIC
            GadgetType.PIE_CHART.category shouldBe GadgetCategory.CHART
            GadgetType.BAR_CHART.category shouldBe GadgetCategory.CHART
            GadgetType.CREATED_VS_RESOLVED.category shouldBe GadgetCategory.CHART
            GadgetType.SPRINT_BURNDOWN.category shouldBe GadgetCategory.CHART
            GadgetType.ACTIVITY_STREAM.category shouldBe GadgetCategory.ACTIVITY
            GadgetType.COMMENTS_RECENT.category shouldBe GadgetCategory.ACTIVITY
        }

        it("10종 enabled=true, 남은 2종은 백엔드 API 가 없어 false") {
            // MVP 6종
            GadgetType.ASSIGNED_TO_ME.enabled shouldBe true
            GadgetType.RECENTLY_CREATED.enabled shouldBe true
            GadgetType.FILTER_RESULT.enabled shouldBe true
            GadgetType.ISSUE_COUNT.enabled shouldBe true
            GadgetType.TEXT_WIDGET.enabled shouldBe true
            GadgetType.LINK_LIST.enabled shouldBe true

            // 이 PR 이 켠 4종 — 전부 기존 BC API 를 프론트가 직접 부른다(ADR D2). 백엔드 신규 0.
            GadgetType.PIE_CHART.enabled shouldBe true
            GadgetType.BAR_CHART.enabled shouldBe true
            GadgetType.SPRINT_BURNDOWN.enabled shouldBe true
            GadgetType.ACTIVITY_STREAM.enabled shouldBe true

            // ★남은 2종은 여전히 false 다. issue-tracking BC 에 엔드포인트를 신설해야 하고
            //   그것은 「한 PR = 한 BC」로 다음 PR 범위다. 여기서 켜면 사용자가 고를 수는 있는데
            //   데이터가 없는 가젯이 된다.
            GadgetType.CREATED_VS_RESOLVED.enabled shouldBe false
            GadgetType.COMMENTS_RECENT.enabled shouldBe false
        }

        it("★enabled=true 개수가 정확히 10 이다 (개수와 점단언이 서로를 검사한다)") {
            // 위 점단언만 있으면 새 타입이 추가되며 켜져도 이 파일이 모른다.
            // 개수만 있으면 어느 것이 켜졌는지 모른다. 둘을 함께 둔다.
            GadgetType.entries.count { it.enabled } shouldBe 10
            GadgetType.entries.size shouldBe 12
        }
    }

    // ── text_widget ────────────────────────────────────────────────────────────

    describe("GadgetType.TEXT_WIDGET.validateConfig") {
        it("markdown 1자는 통과한다") {
            GadgetType.TEXT_WIDGET.validateConfig(json("""{"markdown":"a"}"""))
        }

        it("markdown 정확히 10000자는 통과한다") {
            val content = "a".repeat(10000)
            GadgetType.TEXT_WIDGET.validateConfig(json("""{"markdown":"$content"}"""))
        }

        it("markdown 10001자는 위반한다") {
            val content = "a".repeat(10001)
            shouldThrow<DashboardDomainException> {
                GadgetType.TEXT_WIDGET.validateConfig(json("""{"markdown":"$content"}"""))
            }
        }

        it("markdown 누락 시 위반한다") {
            shouldThrow<DashboardDomainException> {
                GadgetType.TEXT_WIDGET.validateConfig(json("{}"))
            }
        }

        it("validateConfig(null) — markdown 누락으로 위반한다 (C1 null=빈객체 동등)") {
            shouldThrow<DashboardDomainException> {
                GadgetType.TEXT_WIDGET.validateConfig(null)
            }
        }

        it("validateConfig 빈 ObjectNode — markdown 누락으로 위반한다 (C1 null=빈객체 동등)") {
            shouldThrow<DashboardDomainException> {
                GadgetType.TEXT_WIDGET.validateConfig(mapper.createObjectNode())
            }
        }
    }

    // ── link_list ──────────────────────────────────────────────────────────────

    describe("GadgetType.LINK_LIST.validateConfig") {
        fun makeLinks(
            count: Int,
            label: String = "항목",
            url: String = "https://example.com",
        ): JsonNode {
            val items =
                (1..count.coerceAtLeast(0)).joinToString(",") {
                    """{"label":"$label","url":"$url"}"""
                }
            return json("""{"links":[$items]}""")
        }

        fun emptyLinks(): JsonNode = json("""{"links":[]}""")

        it("links 1개는 통과한다") {
            GadgetType.LINK_LIST.validateConfig(makeLinks(1))
        }

        it("links 20개는 통과한다") {
            GadgetType.LINK_LIST.validateConfig(makeLinks(20))
        }

        it("links 0개는 위반한다") {
            shouldThrow<DashboardDomainException> {
                GadgetType.LINK_LIST.validateConfig(emptyLinks())
            }
        }

        it("links 21개는 위반한다") {
            shouldThrow<DashboardDomainException> {
                GadgetType.LINK_LIST.validateConfig(makeLinks(21))
            }
        }

        it("label 101자는 위반한다") {
            val longLabel = "a".repeat(101)
            shouldThrow<DashboardDomainException> {
                GadgetType.LINK_LIST.validateConfig(makeLinks(1, label = longLabel))
            }
        }

        it("url javascript:alert(1)는 위반한다 (EC6)") {
            shouldThrow<DashboardDomainException> {
                GadgetType.LINK_LIST.validateConfig(makeLinks(1, url = "javascript:alert(1)"))
            }
        }

        it("url http://example.com는 통과한다") {
            GadgetType.LINK_LIST.validateConfig(makeLinks(1, url = "http://example.com"))
        }

        it("url https://example.com는 통과한다") {
            GadgetType.LINK_LIST.validateConfig(makeLinks(1, url = "https://example.com"))
        }

        it("url 대문자 스킴 HTTPS://example.com 은 통과한다 (RFC 스킴 대소문자 무관)") {
            GadgetType.LINK_LIST.validateConfig(makeLinks(1, url = "HTTPS://example.com"))
        }

        it("url 대문자 스킴 HTTP://example.com 은 통과한다 (RFC 스킴 대소문자 무관)") {
            GadgetType.LINK_LIST.validateConfig(makeLinks(1, url = "HTTP://example.com"))
        }
    }

    // ── assigned_to_me ────────────────────────────────────────────────────────

    describe("GadgetType.ASSIGNED_TO_ME.validateConfig") {
        it("projectKey 100자는 통과한다") {
            val key = "a".repeat(100)
            GadgetType.ASSIGNED_TO_ME.validateConfig(json("""{"projectKey":"$key"}"""))
        }

        it("projectKey 101자는 위반한다") {
            val longKey = "a".repeat(101)
            shouldThrow<DashboardDomainException> {
                GadgetType.ASSIGNED_TO_ME.validateConfig(json("""{"projectKey":"$longKey"}"""))
            }
        }
    }

    // ── filter_result ──────────────────────────────────────────────────────────

    describe("GadgetType.FILTER_RESULT.validateConfig") {
        val validUuid = "00000000-0000-0000-0000-000000000001"

        it("filterId 만 있으면 통과한다") {
            GadgetType.FILTER_RESULT.validateConfig(json("""{"filterId":"$validUuid"}"""))
        }

        it("aql 만 있으면 통과한다") {
            GadgetType.FILTER_RESULT.validateConfig(json("""{"aql":"project = BTS"}"""))
        }

        it("filterId 와 aql 둘 다 있으면 통과한다") {
            GadgetType.FILTER_RESULT.validateConfig(json("""{"filterId":"$validUuid","aql":"project = BTS"}"""))
        }

        it("filterId 와 aql 둘 다 없으면 위반한다 (EC13)") {
            shouldThrow<DashboardDomainException> {
                GadgetType.FILTER_RESULT.validateConfig(json("{}"))
            }
        }

        it("filterId 비-UUID 문자열이면 위반한다") {
            shouldThrow<DashboardDomainException> {
                GadgetType.FILTER_RESULT.validateConfig(json("""{"filterId":"not-a-uuid"}"""))
            }
        }

        it("aql 2001자는 위반한다") {
            val longAql = "a".repeat(2001)
            shouldThrow<DashboardDomainException> {
                GadgetType.FILTER_RESULT.validateConfig(json("""{"aql":"$longAql"}"""))
            }
        }
    }

    // ── issue_count ────────────────────────────────────────────────────────────

    describe("GadgetType.ISSUE_COUNT.validateConfig") {
        val validUuid = "00000000-0000-0000-0000-000000000001"

        it("filterId 만 있으면 통과한다") {
            GadgetType.ISSUE_COUNT.validateConfig(json("""{"filterId":"$validUuid"}"""))
        }

        it("aql 만 있으면 통과한다") {
            GadgetType.ISSUE_COUNT.validateConfig(json("""{"aql":"project = BTS"}"""))
        }

        it("filterId 와 aql 둘 다 없으면 위반한다 (EC13)") {
            shouldThrow<DashboardDomainException> {
                GadgetType.ISSUE_COUNT.validateConfig(json("{}"))
            }
        }
    }

    // ── pie_chart / bar_chart ──────────────────────────────────────────────────
    //
    // ★스코프는 projectKey 다(X-JD-1). Jira 실물은 space **또는** filter 를 받지만
    //   이 PR 은 프로젝트 스코프만 구현하고 필터 스코프를 이연했다. 그래서 filterId·aql 은
    //   필드 집합에서 **제거**됐고, 값을 줘도 EC5 로 무시된다 — 아래가 그것을 증명한다.

    describe("GadgetType.PIE_CHART.validateConfig") {
        it("projectKey + field=status 는 통과한다") {
            GadgetType.PIE_CHART.validateConfig(json("""{"projectKey":"BTS","field":"status"}"""))
        }

        it("★projectKey 누락은 위반한다 (X-JD-1 — 스코프가 프로젝트다)") {
            shouldThrow<DashboardDomainException> {
                GadgetType.PIE_CHART.validateConfig(json("""{"field":"status"}"""))
            }
        }

        it("field=labels 는 위반한다 (EC9 enum 밖)") {
            shouldThrow<DashboardDomainException> {
                GadgetType.PIE_CHART.validateConfig(json("""{"projectKey":"BTS","field":"labels"}"""))
            }
        }

        it("field 누락은 위반한다") {
            shouldThrow<DashboardDomainException> {
                GadgetType.PIE_CHART.validateConfig(json("""{"projectKey":"BTS"}"""))
            }
        }

        it("★filterId 를 줘도 통과한다 — 필드가 제거돼 EC5 로 무시된다") {
            // 값이 UUID 형식이 아닌데도 통과해야 한다. 필드가 남아 있으면 여기서 throw 한다.
            GadgetType.PIE_CHART.validateConfig(
                json("""{"projectKey":"BTS","field":"status","filterId":"not-a-uuid"}"""),
            )
        }

        it("★aql 을 줘도 통과한다 — 필드가 제거돼 EC5 로 무시된다") {
            GadgetType.PIE_CHART.validateConfig(
                json("""{"projectKey":"BTS","field":"status","aql":"project = BTS"}"""),
            )
        }
    }

    describe("GadgetType.BAR_CHART.validateConfig") {
        it("projectKey + field=status 는 통과한다") {
            GadgetType.BAR_CHART.validateConfig(json("""{"projectKey":"BTS","field":"status"}"""))
        }

        it("★projectKey 누락은 위반한다 (X-JD-1)") {
            shouldThrow<DashboardDomainException> {
                GadgetType.BAR_CHART.validateConfig(json("""{"field":"status"}"""))
            }
        }

        it("field=labels 는 위반한다 (EC9 enum 밖)") {
            shouldThrow<DashboardDomainException> {
                GadgetType.BAR_CHART.validateConfig(json("""{"projectKey":"BTS","field":"labels"}"""))
            }
        }

        it("field 누락은 위반한다") {
            shouldThrow<DashboardDomainException> {
                GadgetType.BAR_CHART.validateConfig(json("""{"projectKey":"BTS"}"""))
            }
        }

        it("★bar_chart 는 pie_chart 와 필드 집합이 같다 — 한 곳만 고치는 실수를 막는다") {
            // 둘은 같은 데이터를 다른 마크로 그린다. 필드가 갈리면 한쪽 가젯만 저장이 거부되고
            // 그 이유가 화면에 드러나지 않는다.
            GadgetType.BAR_CHART.configFields.map { it.key }.toSet() shouldBe
                GadgetType.PIE_CHART.configFields.map { it.key }.toSet()
        }
    }

    // ── sprint_burndown ────────────────────────────────────────────────────────
    //
    // ★M-3 — 가젯은 boardId 를 받고 활성 스프린트를 프론트가 자동으로 찾는다
    //   (BoardDetail.activeSprint). sprintId 를 직접 받으면 스프린트가 넘어갈 때마다
    //   사용자가 가젯을 고쳐야 하고, 끝난 스프린트의 번다운이 그대로 박힌다.

    describe("GadgetType.SPRINT_BURNDOWN.validateConfig") {
        it("boardId(UUID) 는 통과한다") {
            GadgetType.SPRINT_BURNDOWN.validateConfig(
                json("""{"boardId":"11111111-2222-3333-4444-555555555555"}"""),
            )
        }

        it("★boardId 누락은 위반한다 — 보드가 없으면 활성 스프린트를 찾을 수 없다") {
            shouldThrow<DashboardDomainException> {
                GadgetType.SPRINT_BURNDOWN.validateConfig(json("{}"))
            }
        }

        it("boardId 가 UUID 형식이 아니면 위반한다") {
            shouldThrow<DashboardDomainException> {
                GadgetType.SPRINT_BURNDOWN.validateConfig(json("""{"boardId":"not-a-uuid"}"""))
            }
        }

        it("★sprintId 를 줘도 통과한다 — 필드가 제거돼 EC5 로 무시된다") {
            GadgetType.SPRINT_BURNDOWN.validateConfig(
                json("""{"boardId":"11111111-2222-3333-4444-555555555555","sprintId":"not-a-uuid"}"""),
            )
        }
    }

    // ── fromKey 대소문자 엄격 ─────────────────────────────────────────────────────

    describe("GadgetType.fromKey") {
        it("소문자 snake_case 키는 정확히 일치한다") {
            GadgetType.fromKey("assigned_to_me") shouldBe GadgetType.ASSIGNED_TO_ME
            GadgetType.fromKey("filter_result") shouldBe GadgetType.FILTER_RESULT
        }

        it("Issue_Count — 대소문자 혼합이면 null 을 반환한다 (EC4 엄격)") {
            GadgetType.fromKey("Issue_Count").shouldBeNull()
        }

        it("존재하지 않는 키면 null 을 반환한다") {
            GadgetType.fromKey("nonexistent").shouldBeNull()
        }
    }

    // ── 알 수 없는 config 키 무시 (EC5) ─────────────────────────────────────────

    describe("GadgetType.validateConfig — 알 수 없는 config 키") {
        it("optional-only 타입 assigned_to_me 에 알 수 없는 키가 있으면 통과한다 (EC5)") {
            GadgetType.ASSIGNED_TO_ME.validateConfig(json("""{"foo":1}"""))
        }
    }

    // ── catalog() 단일 출처 불변식 ──────────────────────────────────────────────

    describe("GadgetType.catalog") {
        it("12 엔트리를 반환한다") {
            GadgetType.catalog().size shouldBe 12
        }

        it("enabled=true 엔트리가 정확히 10개다") {
            // ★enum 쪽 카운트(위 「정확히 10」)와 catalog() 쪽 카운트는 **다른 축**이다.
            //   catalog() 가 enabled 를 잘못 매핑하면 enum 은 맞는데 응답만 틀릴 수 있다.
            //   그래서 둘 다 둔다 — 하나를 지우면 그 갈림이 안 보인다.
            GadgetType.catalog().count { it.enabled } shouldBe 10
        }

        it("text_widget 엔트리에 markdown configField(required=true)가 포함된다 — 단일 출처 불변식") {
            val textWidgetEntry = GadgetType.catalog().first { it.type == "text_widget" }
            val markdownField = textWidgetEntry.configFields.find { it.key == "markdown" }
            markdownField.shouldNotBeNull()
            markdownField.required shouldBe true
        }

        it("각 엔트리의 configFields 가 해당 GadgetType 의 configFields 와 일치한다 — 단일 출처") {
            GadgetType.entries.forEach { gadgetType ->
                val entry = GadgetType.catalog().first { it.type == gadgetType.key }
                entry.configFields shouldBe gadgetType.configFields
            }
        }

        it("filter_result catalog 엔트리의 requireAtLeastOne 은 listOf(listOf(\"filterId\",\"aql\")) 이다") {
            val entry = GadgetType.catalog().first { it.type == "filter_result" }
            entry.requireAtLeastOne shouldBe listOf(listOf("filterId", "aql"))
        }

        it("issue_count catalog 엔트리의 requireAtLeastOne 은 listOf(listOf(\"filterId\",\"aql\")) 이다") {
            val entry = GadgetType.catalog().first { it.type == "issue_count" }
            entry.requireAtLeastOne shouldBe listOf(listOf("filterId", "aql"))
        }

        it("교차필드 규칙이 없는 타입(text_widget)의 requireAtLeastOne 은 emptyList() 이다") {
            val entry = GadgetType.catalog().first { it.type == "text_widget" }
            entry.requireAtLeastOne shouldBe emptyList()
        }

        it("assigned_to_me catalog 엔트리에 projectKey(STRING, required=false, maxLength 100) configField가 포함된다") {
            val entry = GadgetType.catalog().first { it.type == "assigned_to_me" }
            val projectKeyField = entry.configFields.find { it.key == "projectKey" }
            projectKeyField.shouldNotBeNull()
            projectKeyField.type shouldBe FieldType.STRING
            projectKeyField.required shouldBe false
            projectKeyField.maxLength shouldBe 100
        }
    }
})
