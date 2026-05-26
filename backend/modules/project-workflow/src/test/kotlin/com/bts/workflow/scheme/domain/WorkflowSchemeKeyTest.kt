// WorkflowSchemeKey VO 의 REGEX 검증 테스트 — 유효 케이스 통과 + 위반 케이스 거부
package com.bts.workflow.scheme.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class WorkflowSchemeKeyTest : DescribeSpec({

    describe("WorkflowSchemeKey 형식 검증") {

        context("유효한 키") {
            it("software-scheme 은 accepted") {
                val key = WorkflowSchemeKey("software-scheme")
                key.value shouldBe "software-scheme"
            }

            it("ab 는 accepted (최소 2자, 소문자 시작 + 1자 이상)") {
                val key = WorkflowSchemeKey("ab")
                key.value shouldBe "ab"
            }

            it("a1-b2-c3 은 accepted (숫자 + 하이픈 혼합)") {
                val key = WorkflowSchemeKey("a1-b2-c3")
                key.value shouldBe "a1-b2-c3"
            }

            it("30자 키는 accepted (최대 길이 경계)") {
                val key = WorkflowSchemeKey("a" + "b".repeat(29))
                key.value.length shouldBe 30
            }
        }

        context("무효한 키 — IllegalArgumentException") {
            it("Software 는 rejected (대문자 시작)") {
                shouldThrow<IllegalArgumentException> {
                    WorkflowSchemeKey("Software")
                }
            }

            it("0scheme 은 rejected (숫자 시작)") {
                shouldThrow<IllegalArgumentException> {
                    WorkflowSchemeKey("0scheme")
                }
            }

            it("31자 키는 rejected (30자 초과)") {
                shouldThrow<IllegalArgumentException> {
                    WorkflowSchemeKey("a" + "b".repeat(30))
                }
            }

            it("a 는 rejected (1자, 최소 2자 필요)") {
                shouldThrow<IllegalArgumentException> {
                    WorkflowSchemeKey("a")
                }
            }

            it("UPPER-CASE 는 rejected (대문자 포함)") {
                shouldThrow<IllegalArgumentException> {
                    WorkflowSchemeKey("UPPER-CASE")
                }
            }

            it("빈 문자열은 rejected") {
                shouldThrow<IllegalArgumentException> {
                    WorkflowSchemeKey("")
                }
            }
        }
    }
})
