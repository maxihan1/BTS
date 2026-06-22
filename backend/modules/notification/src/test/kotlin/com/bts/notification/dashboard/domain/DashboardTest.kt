// Dashboard Aggregate 도메인 단위 테스트 — 불변식(이름 검증·visibility 정규화·OCC·layout 크기) 검증

package com.bts.notification.dashboard.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID

class DashboardTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-06-22T00:00:00Z")
    val laterNow: Instant = Instant.parse("2026-06-22T01:00:00Z")
    val ownerId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val userId2: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val userId3: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")

    fun buildDashboard(
        name: String = "내 대시보드",
        visibility: DashboardVisibility = DashboardVisibility.PRIVATE,
        layout: String = "[]",
        sharedUserIds: Set<UUID> = emptySet(),
    ): Dashboard =
        Dashboard.create(
            ownerId = ownerId,
            name = name,
            description = null,
            visibility = visibility,
            layout = layout,
            sharedUserIds = sharedUserIds,
            now = fixedNow,
        )

    // ── 이름 불변식 ─────────────────────────────────────────────────────────────

    describe("Dashboard.create — 이름 불변식") {
        it("빈 문자열 name 은 예외를 던진다") {
            shouldThrow<DashboardDomainException> {
                buildDashboard(name = "")
            }
        }

        it("공백만 있는 name 은 예외를 던진다") {
            shouldThrow<DashboardDomainException> {
                buildDashboard(name = "   ")
            }
        }

        it("200자 이하 name 은 허용된다") {
            val name = "a".repeat(200)
            val dashboard = buildDashboard(name = name)
            dashboard.name shouldBe name
        }

        it("201자 name 은 예외를 던진다") {
            shouldThrow<DashboardDomainException> {
                buildDashboard(name = "a".repeat(201))
            }
        }
    }

    // ── layout 크기 불변식 (C4) ─────────────────────────────────────────────────

    describe("Dashboard.create — layout 크기 불변식") {
        it("layout 이 64KB 이하이면 허용된다") {
            val layout = "[" + "\"x\"".repeat(8000) + "]"
            val dashboard = buildDashboard(layout = layout)
            dashboard.layout shouldBe layout
        }

        it("layout 이 64KB 초과이면 예외를 던진다") {
            val largeLayout = "a".repeat(65537)
            shouldThrow<DashboardDomainException> {
                buildDashboard(layout = largeLayout)
            }
        }
    }

    // ── visibility 정규화 — PRIVATE/ORG 시 shares 비워짐 ─────────────────────────

    describe("Dashboard.create — visibility 정규화") {
        it("visibility=PRIVATE 이면 sharedUserIds 가 있어도 빈 집합으로 정규화된다") {
            val dashboard = buildDashboard(
                visibility = DashboardVisibility.PRIVATE,
                sharedUserIds = setOf(userId2),
            )
            dashboard.sharedUserIds shouldBe emptySet()
        }

        it("visibility=ORG 이면 sharedUserIds 가 있어도 빈 집합으로 정규화된다") {
            val dashboard = buildDashboard(
                visibility = DashboardVisibility.ORG,
                sharedUserIds = setOf(userId2),
            )
            dashboard.sharedUserIds shouldBe emptySet()
        }

        it("visibility=TEAM 이면 sharedUserIds 가 유지된다") {
            val dashboard = buildDashboard(
                visibility = DashboardVisibility.TEAM,
                sharedUserIds = setOf(userId2),
            )
            dashboard.sharedUserIds shouldBe setOf(userId2)
        }
    }

    // ── owner 자동 제거 + 중복 dedup ─────────────────────────────────────────────

    describe("Dashboard.create — sharedUserIds 정규화") {
        it("sharedUserIds 에 ownerId 가 포함되면 정규화로 제거된다") {
            val dashboard = buildDashboard(
                visibility = DashboardVisibility.TEAM,
                sharedUserIds = setOf(ownerId, userId2),
            )
            dashboard.sharedUserIds shouldBe setOf(userId2)
        }

        it("중복된 userId 는 Set 으로 dedup 된다 (Set 입력이므로 중복 자체 없음 보장)") {
            val dashboard = buildDashboard(
                visibility = DashboardVisibility.TEAM,
                sharedUserIds = setOf(userId2, userId3),
            )
            dashboard.sharedUserIds shouldBe setOf(userId2, userId3)
        }
    }

    // ── sharedUserIds cap 초과 ──────────────────────────────────────────────────

    describe("Dashboard.create — sharedUserIds 개수 상한") {
        it("sharedUserIds 가 200개 초과이면 예외를 던진다") {
            val ids = (1..201).map { UUID.randomUUID() }.toSet()
            shouldThrow<DashboardDomainException> {
                buildDashboard(
                    visibility = DashboardVisibility.TEAM,
                    sharedUserIds = ids,
                )
            }
        }

        it("sharedUserIds 가 200개이면 허용된다") {
            val ids = (1..200).map { UUID.randomUUID() }.toSet()
            val dashboard = buildDashboard(
                visibility = DashboardVisibility.TEAM,
                sharedUserIds = ids,
            )
            dashboard.sharedUserIds.size shouldBe 200
        }
    }

    // ── applyPatch — version 증가 + updatedAt 갱신 ──────────────────────────────

    describe("Dashboard.applyPatch — OCC version 증가 + updatedAt 갱신") {
        it("applyPatch 후 version 이 1 증가한다") {
            val original = buildDashboard()
            val patched = original.applyPatch(
                name = null,
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                now = laterNow,
            )
            patched.version shouldBe original.version + 1
        }

        it("applyPatch 후 updatedAt 이 now 로 갱신된다") {
            val original = buildDashboard()
            val patched = original.applyPatch(
                name = null,
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                now = laterNow,
            )
            patched.updatedAt shouldBe laterNow
        }

        it("applyPatch 는 원본 Dashboard 를 변경하지 않는다 — 불변 copy 반환") {
            val original = buildDashboard()
            val patched = original.applyPatch(
                name = null,
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                now = laterNow,
            )
            patched shouldNotBe original
            original.version shouldBe 0
            original.updatedAt shouldBe fixedNow
        }

        it("name 변경 시 새 name 이 적용된다") {
            val original = buildDashboard()
            val patched = original.applyPatch(
                name = "바뀐 이름",
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                now = laterNow,
            )
            patched.name shouldBe "바뀐 이름"
        }

        it("applyPatch 에 빈 name 이 주어지면 예외를 던진다") {
            val original = buildDashboard()
            shouldThrow<DashboardDomainException> {
                original.applyPatch(
                    name = "",
                    description = null,
                    visibility = null,
                    layout = null,
                    sharedUserIds = null,
                    now = laterNow,
                )
            }
        }

        it("visibility=PRIVATE 로 변경 시 기존 shares 가 빈 집합으로 정규화된다") {
            val original = buildDashboard(
                visibility = DashboardVisibility.TEAM,
                sharedUserIds = setOf(userId2),
            )
            val patched = original.applyPatch(
                name = null,
                description = null,
                visibility = DashboardVisibility.PRIVATE,
                layout = null,
                sharedUserIds = null,
                now = laterNow,
            )
            patched.sharedUserIds shouldBe emptySet()
        }
    }
})
