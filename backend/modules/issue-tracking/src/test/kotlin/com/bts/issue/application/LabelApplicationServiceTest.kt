// LabelApplicationService 라벨 자동완성 유스케이스 단위 테스트 (FR-IS-09 B1 fix)

package com.bts.issue.application

import com.bts.issue.repository.IssueRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/**
 * FR-IS-09 B1 fix — LabelApplicationService.completeLabels 단위 테스트.
 *
 * 권한 가드가 제거되었으므로 resolver 관련 케이스는 포함하지 않는다.
 *
 * 검증 항목.
 * - q 정규화: null/빈 문자열/공백 → trim 후 "" 로 정규화하여 repo.findLabelsByPrefix("", 10) 전달.
 * - q 정규화: 앞뒤 공백 제거 후 prefix 로 위임 (예. "  bac " → "bac").
 * - 권한 통과: repo 결과 그대로 반환.
 * - LIMIT: 항상 10을 명시 전달.
 *
 * ADR: docs/adr/2026-06-04-issue-label-freeform-tag-model.md §권한 — 인증 사용자 공통 접근, FR-PM-05 위임.
 */
class LabelApplicationServiceTest : DescribeSpec({

    val repo = mockk<IssueRepository>()

    val sut = LabelApplicationService(repo = repo)

    beforeEach {
        clearMocks(repo, answers = false)
    }

    // ── q 정규화 ───────────────────────────────────────────────────────────────

    describe("q 정규화") {

        context("q=\"  bac \" → trim 후 \"bac\" 로 repo 위임") {
            beforeEach {
                every { repo.findLabelsByPrefix("bac", 10) } returns listOf("backend", "backlog")
            }

            it("repo.findLabelsByPrefix(\"bac\", 10) 가 호출된다") {
                sut.completeLabels("  bac ")
                verify(exactly = 1) { repo.findLabelsByPrefix("bac", 10) }
            }

            it("repo 결과를 그대로 반환한다") {
                val result = sut.completeLabels("  bac ")
                result shouldBe listOf("backend", "backlog")
            }
        }

        context("q=null → repo.findLabelsByPrefix(\"\", 10) 전달 (전체 인기순)") {
            beforeEach {
                every { repo.findLabelsByPrefix("", 10) } returns listOf("bug", "frontend")
            }

            it("repo.findLabelsByPrefix(\"\", 10) 가 호출된다") {
                sut.completeLabels(null)
                verify(exactly = 1) { repo.findLabelsByPrefix("", 10) }
            }
        }

        context("q=\"\" → repo.findLabelsByPrefix(\"\", 10) 전달") {
            beforeEach {
                every { repo.findLabelsByPrefix("", 10) } returns emptyList()
            }

            it("repo.findLabelsByPrefix(\"\", 10) 가 호출된다") {
                sut.completeLabels("")
                verify(exactly = 1) { repo.findLabelsByPrefix("", 10) }
            }
        }

        context("q=\"   \" (공백만) → repo.findLabelsByPrefix(\"\", 10) 전달") {
            beforeEach {
                every { repo.findLabelsByPrefix("", 10) } returns emptyList()
            }

            it("repo.findLabelsByPrefix(\"\", 10) 가 호출된다") {
                sut.completeLabels("   ")
                verify(exactly = 1) { repo.findLabelsByPrefix("", 10) }
            }
        }
    }

    // ── LIMIT 상수 ─────────────────────────────────────────────────────────────

    describe("LIMIT 상수 — 항상 10 전달") {

        context("repo 호출 시 limit=10 명시") {
            beforeEach {
                every { repo.findLabelsByPrefix("fe", 10) } returns listOf("frontend")
            }

            it("limit=10 으로 호출된다 (repository 기본값 20 과 무관)") {
                sut.completeLabels("fe")
                verify(exactly = 1) { repo.findLabelsByPrefix("fe", 10) }
            }
        }
    }

    // ── 반환값 위임 ────────────────────────────────────────────────────────────

    describe("repo 결과 위임") {

        context("repo 가 빈 목록 반환") {
            beforeEach {
                every { repo.findLabelsByPrefix("xyz", 10) } returns emptyList()
            }

            it("빈 목록을 반환한다") {
                val result = sut.completeLabels("xyz")
                result shouldBe emptyList()
            }
        }

        context("repo 가 최대 10개 반환") {
            val tenLabels = (1..10).map { "label-$it" }
            beforeEach {
                every { repo.findLabelsByPrefix("l", 10) } returns tenLabels
            }

            it("repo 결과 10개를 그대로 반환한다") {
                val result = sut.completeLabels("l")
                result shouldBe tenLabels
            }
        }
    }
})
