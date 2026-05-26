// IssueTypeKey VO 의 형식 검증 — REGEX ^[a-z][a-z0-9-]{1,29}$ URL-safe + 30자컷 테스트
package com.bts.issue.type.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class IssueTypeKeyTest : DescribeSpec({

    describe("IssueTypeKey 형식 검증") {

        context("유효한 키") {
            it("bug 는 accepted") {
                val key = IssueTypeKey("bug")
                key.value shouldBe "bug"
            }

            it("subtask 는 accepted") {
                val key = IssueTypeKey("subtask")
                key.value shouldBe "subtask"
            }

            it("ab 는 accepted (최소 2자 — 소문자 시작 + 1자 이상 후속)") {
                val key = IssueTypeKey("ab")
                key.value shouldBe "ab"
            }

            it("a1 은 accepted (소문자 시작 + 숫자 후속)") {
                val key = IssueTypeKey("a1")
                key.value shouldBe "a1"
            }

            it("task-item 은 accepted (하이픈 허용)") {
                val key = IssueTypeKey("task-item")
                key.value shouldBe "task-item"
            }

            it("30자는 accepted (최대 길이)") {
                // 소문자 1자 시작 + 29자 후속 = 총 30자
                val key = IssueTypeKey("a" + "b".repeat(29))
                key.value.length shouldBe 30
            }
        }

        context("무효한 키 — IllegalArgumentException") {
            it("Bug 는 rejected (대문자 시작)") {
                shouldThrow<IllegalArgumentException> {
                    IssueTypeKey("Bug")
                }
            }

            it("1bug 는 rejected (숫자로 시작)") {
                shouldThrow<IllegalArgumentException> {
                    IssueTypeKey("1bug")
                }
            }

            it("a 는 rejected (1자 — 후속 문자 최소 1자 필요)") {
                shouldThrow<IllegalArgumentException> {
                    IssueTypeKey("a")
                }
            }

            it("31자는 rejected (30자 초과)") {
                // 소문자 1자 시작 + 30자 후속 = 총 31자
                shouldThrow<IllegalArgumentException> {
                    IssueTypeKey("a" + "b".repeat(30))
                }
            }

            it("BUG 는 rejected (전체 대문자)") {
                shouldThrow<IllegalArgumentException> {
                    IssueTypeKey("BUG")
                }
            }

            it("빈 문자열은 rejected") {
                shouldThrow<IllegalArgumentException> {
                    IssueTypeKey("")
                }
            }

            it("-bug 는 rejected (하이픈 시작)") {
                shouldThrow<IllegalArgumentException> {
                    IssueTypeKey("-bug")
                }
            }
        }
    }
})
