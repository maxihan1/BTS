// IssueKey VO 의 형식 검증 + projectPrefix / number 접근자 + companion factory 테스트
package com.bts.issue.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class IssueKeyTest : DescribeSpec({

    describe("IssueKey 형식 검증") {

        context("유효한 키") {
            it("ATLAS-1 은 accepted") {
                val key = IssueKey("ATLAS-1")
                key.value shouldBe "ATLAS-1"
                key.projectPrefix shouldBe "ATLAS"
                key.number shouldBe 1L
            }

            it("ATLAS-100 은 accepted") {
                val key = IssueKey("ATLAS-100")
                key.value shouldBe "ATLAS-100"
                key.projectPrefix shouldBe "ATLAS"
                key.number shouldBe 100L
            }

            it("AB-1 은 accepted (최소 2자 prefix)") {
                val key = IssueKey("AB-1")
                key.value shouldBe "AB-1"
                key.projectPrefix shouldBe "AB"
                key.number shouldBe 1L
            }
        }

        context("무효한 키 — IllegalArgumentException") {
            it("atlas-1 은 rejected (소문자 prefix)") {
                shouldThrow<IllegalArgumentException> {
                    IssueKey("atlas-1")
                }
            }

            it("ATLAS-0 은 rejected (number 0 불가)") {
                shouldThrow<IllegalArgumentException> {
                    IssueKey("ATLAS-0")
                }
            }

            it("-1 은 rejected (prefix 부재)") {
                shouldThrow<IllegalArgumentException> {
                    IssueKey("-1")
                }
            }

            it("A-1 은 rejected (1자 prefix, 최소 2자 필요)") {
                shouldThrow<IllegalArgumentException> {
                    IssueKey("A-1")
                }
            }

            it("ABCDEFGHIJK-1 은 rejected (11자 prefix, max 10)") {
                shouldThrow<IllegalArgumentException> {
                    IssueKey("ABCDEFGHIJK-1")
                }
            }
        }
    }

    describe("IssueKey.of companion factory") {
        it("IssueKey.of(\"ATLAS\", 5L).value == \"ATLAS-5\"") {
            val key = IssueKey.of("ATLAS", 5L)
            key.value shouldBe "ATLAS-5"
        }
    }
})
