// LabelApplicationService 라벨 자동완성 유스케이스 단위 테스트 (FR-IS-09 Task 2)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

/**
 * FR-IS-09 Task 2 — LabelApplicationService.completeLabels 단위 테스트.
 *
 * 검증 항목.
 * - q 정규화: null/빈 문자열/공백 → trim 후 "" 로 정규화하여 repo.findLabelsByPrefix("", 10) 전달.
 * - q 정규화: 앞뒤 공백 제거 후 prefix 로 위임 (예. "  bac " → "bac").
 * - 권한 거부: VIEW + Global 권한 없으면 IssueAccessDeniedException, repo 미호출.
 * - 권한 통과: repo 결과 그대로 반환.
 * - LIMIT: 항상 10을 명시 전달.
 *
 * ADR: docs/adr/2026-06-04-issue-label-freeform-tag-model.md — 글로벌 스코프 사유.
 */
class LabelApplicationServiceTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val permissionResolver = mockk<IssuePermissionResolver>()

    val sut = LabelApplicationService(repo = repo, permissionResolver = permissionResolver)

    val actor = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

    fun stubPermissionGranted() {
        every {
            permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Global)
        } returns true
    }

    fun stubPermissionDenied() {
        every {
            permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Global)
        } returns false
    }

    beforeEach {
        clearMocks(repo, permissionResolver, answers = false)
    }

    // ── 권한 가드 ──────────────────────────────────────────────────────────────

    describe("권한 가드") {

        context("VIEW + Global 권한 없는 액터 → IssueAccessDeniedException, repo 미호출") {
            beforeEach { stubPermissionDenied() }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.completeLabels(actor, "bug")
                }
            }

            it("repo.findLabelsByPrefix 가 전혀 호출되지 않는다") {
                runCatching { sut.completeLabels(actor, "bug") }
                verify(exactly = 0) { repo.findLabelsByPrefix(any(), any()) }
            }
        }
    }

    // ── q 정규화 ───────────────────────────────────────────────────────────────

    describe("q 정규화") {

        context("q=\"  bac \" → trim 후 \"bac\" 로 repo 위임") {
            beforeEach {
                stubPermissionGranted()
                every { repo.findLabelsByPrefix("bac", 10) } returns listOf("backend", "backlog")
            }

            it("repo.findLabelsByPrefix(\"bac\", 10) 가 호출된다") {
                sut.completeLabels(actor, "  bac ")
                verify(exactly = 1) { repo.findLabelsByPrefix("bac", 10) }
            }

            it("repo 결과를 그대로 반환한다") {
                val result = sut.completeLabels(actor, "  bac ")
                result shouldBe listOf("backend", "backlog")
            }
        }

        context("q=null → repo.findLabelsByPrefix(\"\", 10) 전달 (전체 인기순)") {
            beforeEach {
                stubPermissionGranted()
                every { repo.findLabelsByPrefix("", 10) } returns listOf("bug", "frontend")
            }

            it("repo.findLabelsByPrefix(\"\", 10) 가 호출된다") {
                sut.completeLabels(actor, null)
                verify(exactly = 1) { repo.findLabelsByPrefix("", 10) }
            }
        }

        context("q=\"\" → repo.findLabelsByPrefix(\"\", 10) 전달") {
            beforeEach {
                stubPermissionGranted()
                every { repo.findLabelsByPrefix("", 10) } returns emptyList()
            }

            it("repo.findLabelsByPrefix(\"\", 10) 가 호출된다") {
                sut.completeLabels(actor, "")
                verify(exactly = 1) { repo.findLabelsByPrefix("", 10) }
            }
        }

        context("q=\"   \" (공백만) → repo.findLabelsByPrefix(\"\", 10) 전달") {
            beforeEach {
                stubPermissionGranted()
                every { repo.findLabelsByPrefix("", 10) } returns emptyList()
            }

            it("repo.findLabelsByPrefix(\"\", 10) 가 호출된다") {
                sut.completeLabels(actor, "   ")
                verify(exactly = 1) { repo.findLabelsByPrefix("", 10) }
            }
        }
    }

    // ── LIMIT 상수 ─────────────────────────────────────────────────────────────

    describe("LIMIT 상수 — 항상 10 전달") {

        context("권한 통과 후 repo 호출 시 limit=10 명시") {
            beforeEach {
                stubPermissionGranted()
                every { repo.findLabelsByPrefix("fe", 10) } returns listOf("frontend")
            }

            it("limit=10 으로 호출된다 (repository 기본값 20 과 무관)") {
                sut.completeLabels(actor, "fe")
                verify(exactly = 1) { repo.findLabelsByPrefix("fe", 10) }
            }
        }
    }

    // ── 반환값 위임 ────────────────────────────────────────────────────────────

    describe("repo 결과 위임") {

        context("repo 가 빈 목록 반환") {
            beforeEach {
                stubPermissionGranted()
                every { repo.findLabelsByPrefix("xyz", 10) } returns emptyList()
            }

            it("빈 목록을 반환한다") {
                val result = sut.completeLabels(actor, "xyz")
                result shouldBe emptyList()
            }
        }

        context("repo 가 최대 10개 반환") {
            val tenLabels = (1..10).map { "label-$it" }
            beforeEach {
                stubPermissionGranted()
                every { repo.findLabelsByPrefix("l", 10) } returns tenLabels
            }

            it("repo 결과 10개를 그대로 반환한다") {
                val result = sut.completeLabels(actor, "l")
                result shouldBe tenLabels
            }
        }
    }
})
