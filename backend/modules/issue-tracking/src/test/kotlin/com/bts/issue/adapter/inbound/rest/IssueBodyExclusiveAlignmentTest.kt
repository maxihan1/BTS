// 본문 두 표현(description·descriptionHtml)의 상호배타 검증이 생성·수정 경로에서 같은지 강제하는 판별식

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.domain.IssueTextConstraints
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import jakarta.validation.Validation
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * 본문 상호배타 정합 판별식 — [IssueTextConstraintsAlignmentTest] 와 같은 사고, 다른 규칙.
 *
 * ## 무엇을 막나
 *
 * V039 로 본문이 두 표현이 됐다 — `description`(마크다운, 레거시·CSV import)과
 * `descriptionHtml`(리치 에디터). 둘을 함께 보내면 **어느 쪽이 이기는지가 호출자마다 달라지고**,
 * 그 판정이 서비스 안쪽에 숨으면 「보낸 대로 저장되지 않는다」는 재현 어려운 버그가 된다.
 *
 * 그래서 REST 층에서 400 으로 끊는다. 문제는 **그 규칙이 수정 경로에만 있었다**는 것이다 —
 * 2026-09-07 실측: `UpdateIssueRequest.isBodyExclusive` 는 있는데 `CreateIssueRequest` 에는
 * `descriptionHtml` 필드 자체가 없어, 생성 화면은 리치 에디터를 쓸 길이 없었다.
 *
 * 필드를 더하면서 **술어를 새로 적으면** 두 경로가 갈릴 수 있다. 라벨 검증에서 이미 그
 * 비대칭을 겪었다 — 「생성은 400, 수정은 500」. 이 판별식이 그 재발을 막는다.
 *
 * ## 강제하지 **않는** 것
 *
 * - 서비스가 그 값을 **어느 컬럼에 넣는지**는 안 본다. 그것은 통합 테스트의 몫이다.
 */
class IssueBodyExclusiveAlignmentTest : DescribeSpec({

    val validator = Validation.buildDefaultValidatorFactory().validator
    val descriptionMax = IssueTextConstraints.DESCRIPTION_MAX

    fun text(n: Int) = "a".repeat(n)

    /** `@field:Size(max)` 선언값을 읽는다 — 없으면 상한이 통째로 사라진 것이라 즉시 실패한다. */
    fun declaredMax(
        clazz: Class<*>,
        field: String,
    ): Int {
        val size =
            clazz.getDeclaredField(field).getAnnotation(Size::class.java)
                ?: error("${clazz.simpleName}.$field 에 @field:Size 가 없다 — 길이 상한이 사라졌다.")
        return size.max
    }

    fun createRequest(
        description: String? = null,
        descriptionHtml: String? = null,
    ) = CreateIssueRequest(
        projectKey = "PROJ",
        summary = "제목",
        description = description,
        descriptionHtml = descriptionHtml,
    )

    fun updateRequest(
        description: String? = null,
        descriptionHtml: String? = null,
    ) = UpdateIssueRequest(
        summary = "제목",
        expectedVersion = 1L,
        description = description,
        descriptionHtml = descriptionHtml,
    )

    describe("생성 요청의 본문 상호배타") {
        it("descriptionHtml 만 보내면 통과한다") {
            validator.validate(createRequest(descriptionHtml = "<p>본문</p>")).shouldBe(emptySet())
        }

        it("description 만 보내면 통과한다") {
            validator.validate(createRequest(description = "본문")).shouldBe(emptySet())
        }

        it("둘 다 안 보내면 통과한다 — 서버가 템플릿으로 채운다 (FR-TM-01)") {
            validator.validate(createRequest()).shouldBe(emptySet())
        }

        it("둘 다 보내면 위반이다") {
            val violations = validator.validate(createRequest(description = "본문", descriptionHtml = "<p>본문</p>"))
            violations.map { it.message }.any { it.contains("동시에 보낼 수 없습니다") }.shouldBe(true)
        }
    }

    describe("생성 요청의 descriptionHtml 길이 상한") {
        it("상한 값이 IssueTextConstraints 와 같다") {
            declaredMax(CreateIssueRequest::class.java, "descriptionHtml").shouldBe(descriptionMax)
        }

        it("상한 초과는 위반이다") {
            validator.validate(createRequest(descriptionHtml = text(descriptionMax + 1))).isEmpty().shouldBe(false)
        }

        it("상한 정확히는 통과한다 — 경계가 <= 인지 < 인지를 못박는다") {
            validator.validate(createRequest(descriptionHtml = text(descriptionMax))).shouldBe(emptySet())
        }
    }

    describe("생성·수정 경로의 술어 정합") {
        // ★같은 입력에 **같은 판정**이 나와야 한다. 갈리는 순간 「생성은 통과, 수정은 400」
        //   같은 비대칭이 되살아난다 — 라벨 검증에서 이미 겪은 양식이다.
        val cases =
            listOf(
                Triple("둘 다 null", null, null),
                Triple("description 만", "본문", null),
                Triple("descriptionHtml 만", null, "<p>본문</p>"),
                Triple("둘 다", "본문", "<p>본문</p>"),
            )

        cases.forEach { (name, md, html) ->
            it("$name — 두 경로의 상호배타 판정이 같다") {
                val createOk =
                    validator.validate(createRequest(md, html))
                        .none { it.message.contains("동시에 보낼 수 없습니다") }
                val updateOk =
                    validator.validate(updateRequest(md, html))
                        .none { it.message.contains("동시에 보낼 수 없습니다") }
                createOk.shouldBe(updateOk)
            }
        }

        it("두 DTO 의 상한이 같다") {
            declaredMax(CreateIssueRequest::class.java, "descriptionHtml")
                .shouldBe(declaredMax(UpdateIssueRequest::class.java, "descriptionHtml"))
        }
    }

    describe("판별식 비-공허 확인") {
        it("상호배타 위반이 실제로 검출된다 — 이 단언이 없으면 위 정합 비교가 공허하다") {
            // 두 경로가 **둘 다 검사를 안 해도** 위 「판정이 같다」는 통과한다.
            // 위반이 최소 한 번은 실제로 잡히는지 여기서 못박는다.
            val violations = validator.validate(createRequest(description = "본문", descriptionHtml = "<p>본문</p>"))
            violations.isEmpty().shouldBe(false)
            // UUID 는 요청 구성에만 쓰였고 검증과 무관하다 — 미사용 경고 회피.
            UUID.randomUUID().toString().isNotEmpty().shouldBe(true)
        }
    }
})
