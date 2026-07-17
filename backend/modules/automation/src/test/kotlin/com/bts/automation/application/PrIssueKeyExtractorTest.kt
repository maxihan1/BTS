// PrIssueKeyExtractor.extract — PR 제목/본문에서 "Closes PROJ-42" 형태 이슈 키 추출 단위 테스트 (FR-AT-07 PR-C)

package com.bts.automation.application

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class PrIssueKeyExtractorTest : DescribeSpec({

    describe("extract — 키워드 변형 커버") {
        it("Closes PROJ-42 를 추출한다") {
            PrIssueKeyExtractor.extract("Closes PROJ-42", null) shouldBe listOf("PROJ-42")
        }

        it("Fixes PROJ-42 를 추출한다") {
            PrIssueKeyExtractor.extract("Fixes PROJ-42", null) shouldBe listOf("PROJ-42")
        }

        it("Resolved PROJ-42 를 추출한다") {
            PrIssueKeyExtractor.extract("Resolved PROJ-42", null) shouldBe listOf("PROJ-42")
        }
    }

    describe("extract — 키워드 없는 맨 이슈키는 미추출") {
        it("키워드 없이 PROJ-42 만 있으면 추출하지 않는다") {
            PrIssueKeyExtractor.extract("PROJ-42 관련 작업", null) shouldBe emptyList()
        }
    }

    describe("extract — 뒤 단어 경계 부정 탐색") {
        it("Closes PROJ-42x 는 오추출 없이 빈 목록이다") {
            PrIssueKeyExtractor.extract("Closes PROJ-42x", null) shouldBe emptyList()
        }

        it("Closes PROJ-420 은 PROJ-420 이지 PROJ-42 가 아니다") {
            PrIssueKeyExtractor.extract("Closes PROJ-420", null) shouldBe listOf("PROJ-420")
        }
    }

    describe("extract — 키워드는 대소문자 무시, 이슈키는 대문자 고정") {
        it("소문자 closes proj-42 는 미추출이다(키는 대문자여야 함)") {
            PrIssueKeyExtractor.extract("closes proj-42", null) shouldBe emptyList()
        }
    }

    describe("extract — 제목 + 본문 양쪽 스캔") {
        it("제목과 본문에 각각 언급된 이슈키를 모두 추출한다") {
            val result = PrIssueKeyExtractor.extract("Closes PROJ-1", "본문에서 fixes PROJ-2 도 언급")

            result shouldBe listOf("PROJ-1", "PROJ-2")
        }
    }

    describe("extract — distinct") {
        it("같은 키가 제목/본문에 여러 번 언급돼도 1건으로 중복 제거한다") {
            val result = PrIssueKeyExtractor.extract("Closes PROJ-42", "Fixes PROJ-42 again")

            result shouldBe listOf("PROJ-42")
        }
    }

    describe("extract — null/빈 문자열 안전") {
        it("title/body 모두 null 이면 빈 목록이다") {
            PrIssueKeyExtractor.extract(null, null) shouldBe emptyList()
        }

        it("title/body 모두 빈 문자열이면 빈 목록이다") {
            PrIssueKeyExtractor.extract("", "") shouldBe emptyList()
        }
    }
})
