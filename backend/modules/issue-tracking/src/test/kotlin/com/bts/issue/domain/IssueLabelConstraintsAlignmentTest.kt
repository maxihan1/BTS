// 라벨 제약이 도메인·REST 생성·REST 수정 세 층에서 같은 값·같은 경계인지 강제하는 판별식

package com.bts.issue.domain

import com.bts.issue.adapter.inbound.rest.CreateIssueRequest
import com.bts.issue.adapter.inbound.rest.UpdateIssueRequest
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Size

/**
 * 라벨 제약 3층 정합 판별식.
 *
 * ## 무엇을 막나
 *
 * `LABEL_MAX_LENGTH = 50` 과 `LABEL_MAX_COUNT = 20` 이 **세 곳**에 사본으로 있었다 —
 * 도메인 `Issue.kt`(파일 private) · `CreateIssueRequest` companion · `UpdateIssueRequest` companion.
 * 한 곳만 바꾸면 나머지 둘이 조용히 어긋난다.
 *
 * 어긋나는 방향에 따라 결과가 다르고 **둘 다 사용자가 원인을 알 수 없다.**
 *
 * | 어긋남 | 결과 |
 * |---|---|
 * | REST 만 키움 | REST 통과 → 도메인 `require` → `IllegalArgumentException` 핸들러 부재 → **500** |
 * | REST 만 줄임 | 도메인이 허용하는 기존 데이터를 REST 가 400 으로 막아 **수정이 영영 실패** |
 *
 * ## 두 종류의 단언이 섞여 있다 (의도)
 *
 * - **구조 단언** — 사본이 실제로 사라졌는가. 봉합 전에는 red 였고, 사본을 되살리면 다시 red 가 된다.
 *   이게 이 파일의 red 동인이다.
 * - **경계 회귀핀** — 세 층이 같은 경계에서 같은 판정을 하는가. 봉합 전에도 값이 우연히 같아
 *   초록이었다(그래서 결함이 잠복했다). 값을 바꿔도 세 층이 함께 움직이는지를 앞으로 지킨다.
 *
 * ★**숫자를 여기 다시 적지 않는다.** 전부 [IssueLabelConstraints] 에서 파생시킨다 —
 * 적는 순간 이 파일이 네 번째 사본이 된다.
 */
class IssueLabelConstraintsAlignmentTest : DescribeSpec({

    val maxLength = IssueLabelConstraints.MAX_LENGTH
    val maxCount = IssueLabelConstraints.MAX_COUNT

    /** 길이 n 짜리 라벨. */
    fun label(n: Int) = "a".repeat(n)

    /** 서로 다른 라벨 n 개. */
    fun labels(n: Int) = (1..n).map { "label-$it" }

    describe("구조 — 사본이 존재하지 않는다 (red 동인)") {

        // `const val` 은 companion 이 private 이어도 담는 클래스의 static 필드로 emit 된다.
        // 그래서 사본이 남아 있으면 여기서 그대로 보인다.
        listOf(
            "CreateIssueRequest" to CreateIssueRequest::class.java,
            "UpdateIssueRequest" to UpdateIssueRequest::class.java,
        ).forEach { (name, clazz) ->
            it("$name 이 라벨 상한 상수를 자기 사본으로 갖지 않는다") {
                val copies = clazz.declaredFields.map { it.name }.filter { it.startsWith("LABEL_MAX") }
                copies shouldContainExactly emptyList()
            }
        }
    }

    describe("경계 — 세 층이 같은 지점에서 갈린다 (회귀핀)") {

        context("라벨 개별 길이") {
            it("도메인은 최대 길이를 허용하고 +1 을 거부한다") {
                shouldNotThrowAny { Issue.normalizeLabels(listOf(label(maxLength))) }
                shouldThrow<IllegalArgumentException> { Issue.normalizeLabels(listOf(label(maxLength + 1))) }
            }

            it("REST 생성 검증이 도메인과 같은 지점에서 갈린다") {
                createWith(listOf(label(maxLength))).isLabelsValid shouldBe true
                createWith(listOf(label(maxLength + 1))).isLabelsValid shouldBe false
            }

            it("REST 수정 검증이 도메인과 같은 지점에서 갈린다") {
                updateWith(listOf(label(maxLength))).isLabelsValid shouldBe true
                updateWith(listOf(label(maxLength + 1))).isLabelsValid shouldBe false
            }

            it("생성과 수정의 판정이 모든 경계 후보에서 서로 일치한다") {
                // 「생성은 400, 수정은 500」 비대칭이 되살아나는 것을 막는 직접 단언.
                for (n in listOf(0, 1, maxLength - 1, maxLength, maxLength + 1, maxLength * 2)) {
                    val value = listOf(label(n))
                    createWith(value).isLabelsValid shouldBe updateWith(value).isLabelsValid
                }
            }
        }

        context("라벨 개수") {
            it("도메인은 최대 개수를 허용하고 +1 을 거부한다") {
                shouldNotThrowAny { Issue.normalizeLabels(labels(maxCount)) }
                shouldThrow<IllegalArgumentException> { Issue.normalizeLabels(labels(maxCount + 1)) }
            }

            // 개수 상한은 `@field:Size` 가 막는다 — 파생 속성이 아니라 애노테이션 값이므로
            // 리플렉션으로 「선언된 상한」을 직접 읽어 도메인 상수와 대조한다.
            listOf(
                "CreateIssueRequest" to CreateIssueRequest::class.java,
                "UpdateIssueRequest" to UpdateIssueRequest::class.java,
            ).forEach { (name, clazz) ->
                it("$name 의 @Size(max) 가 도메인 개수 상한과 같다") {
                    val size =
                        clazz.getDeclaredField("labels").getAnnotation(Size::class.java)
                            ?: error("$name.labels 에 @field:Size 가 없다 — 개수 상한이 통째로 사라졌다.")
                    size.max shouldBe maxCount
                }
            }
        }
    }

    describe("문구 — 사용자에게 보이는 숫자가 상수와 어긋나지 않는다") {
        // 애노테이션 message 는 컴파일 상수 문자열이라 상수를 직접 못 박는다.
        // 값을 바꾸면 문구가 조용히 거짓이 되므로 여기서 대조한다.
        listOf(
            "CreateIssueRequest" to CreateIssueRequest::class.java,
            "UpdateIssueRequest" to UpdateIssueRequest::class.java,
        ).forEach { (name, clazz) ->
            it("$name 의 라벨 길이 오류 문구가 실제 상한을 말한다") {
                val message =
                    clazz.getDeclaredMethod("isLabelsValid").getAnnotation(AssertTrue::class.java)?.message
                        ?: error("$name.isLabelsValid 에 @get:AssertTrue 가 없다 — 길이 검증이 사라졌다.")
                if (!message.contains(maxLength.toString())) {
                    error(
                        "$name 의 문구가 상한($maxLength)과 어긋난다 — 사용자가 틀린 숫자를 안내받는다.\n" +
                            "  실제 문구: \"$message\"",
                    )
                }
            }
        }
    }

    describe("비-공허 짝") {
        it("정상 입력은 그대로 통과한다 (판정이 전부 거부로 죽어 있지 않다)") {
            val ok = listOf("bug", "urgent")
            Issue.normalizeLabels(ok) shouldContainExactly ok
            createWith(ok).isLabelsValid shouldBe true
            updateWith(ok).isLabelsValid shouldBe true
        }

        it("상한 값이 0 이 아니다 (경계 단언이 공허하지 않다)") {
            (maxLength > 0) shouldBe true
            (maxCount > 0) shouldBe true
        }
    }
})

/** 라벨만 바꾼 최소 생성 요청. */
private fun createWith(labels: List<String>) =
    CreateIssueRequest(projectKey = "BTS", summary = "테스트", labels = labels)

/** 라벨만 바꾼 최소 수정 요청. `expectedVersion` 은 낙관락용 필수 인자라 임의값을 넣는다. */
private fun updateWith(labels: List<String>) = UpdateIssueRequest(expectedVersion = 1L, labels = labels)
