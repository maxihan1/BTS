// 제목·본문·댓글 길이 제약이 도메인·REST 각 층에서 같은 값·같은 경계인지 강제하는 판별식

package com.bts.issue.domain

import com.bts.issue.adapter.inbound.rest.CloneIssueRequest
import com.bts.issue.adapter.inbound.rest.CreateIssueRequest
import com.bts.issue.adapter.inbound.rest.UpdateIssueRequest
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.shared.issue.IssueTypeId
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * 텍스트 길이 제약 층간 정합 판별식 — [IssueLabelConstraintsAlignmentTest] 와 같은 사고, 다른 필드.
 *
 * ## 무엇을 막나
 *
 * 착수 시점(2026-09-04) `summary` 상한이 **네 곳**에 사본으로 있었고 실제로 어긋나 있었다.
 * DB `VARCHAR(255)` · 도메인 `require(<=255)` · 클론 DTO 255 인데 생성·수정 DTO 만 **200** 이었다.
 *
 * 결과는 「클론으로는 255자 제목을 만들 수 있는데, 그 이슈를 열어 저장하면 400」이라는 비대칭이다.
 * 사용자는 자기가 만들지도 않은 제목 때문에 수정이 막힌다.
 *
 * ★**숫자를 여기 다시 적지 않는다.** 전부 [IssueTextConstraints] 에서 파생시킨다 —
 * 적는 순간 이 파일이 또 하나의 사본이 된다.
 */
class IssueTextConstraintsAlignmentTest : DescribeSpec({

    val summaryMax = IssueTextConstraints.SUMMARY_MAX
    val descriptionMax = IssueTextConstraints.DESCRIPTION_MAX
    val commentMax = IssueTextConstraints.COMMENT_BODY_MAX

    /** 길이 n 짜리 문자열. */
    fun text(n: Int) = "a".repeat(n)

    /** `@field:Size(max)` 선언값을 읽는다 — 없으면 제약이 통째로 사라진 것이라 즉시 실패한다. */
    fun declaredMax(
        clazz: Class<*>,
        field: String,
    ): Int {
        val size =
            clazz.getDeclaredField(field).getAnnotation(Size::class.java)
                ?: error("${clazz.simpleName}.$field 에 @field:Size 가 없다 — 길이 상한이 사라졌다.")
        return size.max
    }

    /** 제목만 바꾼 최소 도메인 생성 — 나머지 인자는 검증에 무관한 유효값이다. */
    fun createIssue(summary: String) =
        Issue.create(
            id = IssueId(UUID.randomUUID()),
            key = IssueKey.of("PROJ", 1L),
            projectId = UUID.randomUUID(),
            typeId = IssueTypeId(1L),
            summary = summary,
            reporterId = ActorId(UUID.randomUUID()),
            currentStateKey = "open",
        )

    describe("summary — 도메인·생성·수정·클론 네 층이 같은 상한을 본다") {

        it("도메인이 상한을 허용하고 +1 을 거부한다") {
            shouldNotThrowAny { createIssue(text(summaryMax)) }
            shouldThrow<IllegalArgumentException> { createIssue(text(summaryMax + 1)) }
        }

        listOf(
            "CreateIssueRequest" to CreateIssueRequest::class.java,
            "UpdateIssueRequest" to UpdateIssueRequest::class.java,
        ).forEach { (name, clazz) ->
            it("$name.summary 의 @Size(max) 가 도메인 상한과 같다") {
                declaredMax(clazz, "summary") shouldBe summaryMax
            }
        }

        it("CloneIssueRequest.summaryOverride 도 같은 상한을 본다") {
            // 이 필드만 혼자 255 였다 — 다른 셋이 200 이던 시절의 비대칭 근원지다.
            declaredMax(CloneIssueRequest::class.java, "summaryOverride") shouldBe summaryMax
        }
    }

    describe("description — 생성·수정이 같은 상한을 본다") {

        it("UpdateIssueRequest.description 의 @Size(max) 가 상수와 같다") {
            declaredMax(UpdateIssueRequest::class.java, "description") shouldBe descriptionMax
        }

        it("CreateIssueRequest.description 의 @Size(max) 가 상수와 같다") {
            // 착수 시점에는 생성 DTO 에 **@Size 자체가 없었다** — 수정 경로만 65535 를 막고 있었다.
            // 상한 없는 입력이 DB TEXT 로 그대로 들어가면 수정 시점에 400 이 나 편집이 막힌다.
            declaredMax(CreateIssueRequest::class.java, "description") shouldBe descriptionMax
        }
    }

    describe("descriptionHtml — 본문과 같은 상한을 본다") {

        it("UpdateIssueRequest.descriptionHtml 의 @Size(max) 가 description 과 같다") {
            // 두 필드는 같은 본문의 두 표현이다. 상한이 갈리면 에디터로는 저장되는데
            // 마크다운 경로로는 거부되는(또는 그 반대) 입력이 생긴다.
            declaredMax(UpdateIssueRequest::class.java, "descriptionHtml") shouldBe descriptionMax
        }
    }

    describe("comment — 서비스 상한이 상수와 같다") {

        it("CommentApplicationService.MAX_BODY_LENGTH 가 상수와 같다") {
            CommentApplicationService.MAX_BODY_LENGTH shouldBe commentMax
        }
    }

    describe("비-공허 짝") {

        it("상한들이 0 이 아니다 (경계 단언이 공허하지 않다)") {
            (summaryMax > 0) shouldBe true
            (descriptionMax > 0) shouldBe true
            (commentMax > 0) shouldBe true
        }

        it("정상 길이 입력은 그대로 통과한다 (판정이 전부 거부로 죽어 있지 않다)") {
            shouldNotThrowAny { createIssue("평범한 제목") }
        }

        it("summary 상한이 DB 컬럼 길이(255)와 같다 — 마이그레이션 없이 넘길 수 없는 경계") {
            // DB VARCHAR(255) 를 넘기려면 마이그레이션이 필요하다. 이 단언은 상수만 올리고
            // 마이그레이션을 빠뜨리는 것을 막는다 — 그 경우 도메인은 통과시키고 DB 가 거부해
            // 사용자 입력 오류가 500 이 된다.
            summaryMax shouldBe 255
        }
    }
})
