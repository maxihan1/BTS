// 즐겨찾기 도메인 단위 테스트 — FavoriteTargetType 파싱·Favorite 형식 검증 불변식

package com.bts.notification.favorite.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.UUID

class FavoriteTest : DescribeSpec({

    val userId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val validTargetId: String = "PROJ-123"

    // ── FavoriteTargetType.from ─────────────────────────────────────────────────

    describe("FavoriteTargetType.from") {
        it("ISSUE 는 FavoriteTargetType.ISSUE 를 반환한다") {
            FavoriteTargetType.from("ISSUE") shouldBe FavoriteTargetType.ISSUE
        }

        it("FILTER 는 FavoriteTargetType.FILTER 를 반환한다") {
            FavoriteTargetType.from("FILTER") shouldBe FavoriteTargetType.FILTER
        }

        it("DASHBOARD 는 FavoriteTargetType.DASHBOARD 를 반환한다") {
            FavoriteTargetType.from("DASHBOARD") shouldBe FavoriteTargetType.DASHBOARD
        }

        it("PROJECT 는 FavoriteTargetType.PROJECT 를 반환한다") {
            FavoriteTargetType.from("PROJECT") shouldBe FavoriteTargetType.PROJECT
        }

        it("소문자 issue 는 FavoriteDomainException 을 던진다") {
            shouldThrow<FavoriteDomainException> {
                FavoriteTargetType.from("issue")
            }
        }

        it("빈 문자열은 FavoriteDomainException 을 던진다") {
            shouldThrow<FavoriteDomainException> {
                FavoriteTargetType.from("")
            }
        }

        it("UNKNOWN 은 FavoriteDomainException 을 던진다") {
            shouldThrow<FavoriteDomainException> {
                FavoriteTargetType.from("UNKNOWN")
            }
        }

        it("공백 포함 문자열은 FavoriteDomainException 을 던진다") {
            shouldThrow<FavoriteDomainException> {
                FavoriteTargetType.from(" ISSUE")
            }
        }
    }

    // ── Favorite.create — targetId 불변식 ───────────────────────────────────────

    describe("Favorite.create — targetId 불변식") {
        it("정상 입력이면 id·createdAt 이 자동 부여된 Favorite 를 반환한다") {
            val fav = Favorite.create(
                userId = userId,
                targetType = FavoriteTargetType.ISSUE,
                targetId = validTargetId,
            )
            fav.id shouldNotBe null
            fav.userId shouldBe userId
            fav.targetType shouldBe FavoriteTargetType.ISSUE
            fav.targetId shouldBe validTargetId
            fav.createdAt shouldNotBe null
        }

        it("빈 문자열 targetId 는 FavoriteDomainException 을 던진다") {
            shouldThrow<FavoriteDomainException> {
                Favorite.create(
                    userId = userId,
                    targetType = FavoriteTargetType.ISSUE,
                    targetId = "",
                )
            }
        }

        it("공백만 있는 targetId 는 FavoriteDomainException 을 던진다") {
            shouldThrow<FavoriteDomainException> {
                Favorite.create(
                    userId = userId,
                    targetType = FavoriteTargetType.ISSUE,
                    targetId = "   ",
                )
            }
        }

        it("255자 targetId 는 허용된다") {
            val targetId = "a".repeat(255)
            val fav = Favorite.create(
                userId = userId,
                targetType = FavoriteTargetType.DASHBOARD,
                targetId = targetId,
            )
            fav.targetId shouldBe targetId
        }

        it("256자 targetId 는 FavoriteDomainException 을 던진다") {
            shouldThrow<FavoriteDomainException> {
                Favorite.create(
                    userId = userId,
                    targetType = FavoriteTargetType.DASHBOARD,
                    targetId = "a".repeat(256),
                )
            }
        }
    }

    // ── Favorite.create — FILTER 타입 ───────────────────────────────────────────

    describe("Favorite.create — FILTER 타입") {
        it("FILTER 타입으로 Favorite 를 생성할 수 있다") {
            val fav = Favorite.create(
                userId = userId,
                targetType = FavoriteTargetType.FILTER,
                targetId = "filter-uuid-001",
            )
            fav.targetType shouldBe FavoriteTargetType.FILTER
        }
    }
})
