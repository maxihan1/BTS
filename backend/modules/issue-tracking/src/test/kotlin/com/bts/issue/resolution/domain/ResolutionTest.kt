// Resolution Aggregate Root 단위 테스트 — factory invariants + 표준 5종 key/isStandard 검증
package com.bts.issue.resolution.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * [Resolution] Aggregate Root 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 대상.
 * - name 빈 문자열 또는 공백만인 경우 [IllegalArgumentException] 발생
 * - [Resolution.create] 로 생성 시 id = null, deletedAt = null
 * - 표준 5종 상수(FIXED/WONT_FIX/DUPLICATE/CANNOT_REPRODUCE/DONE) 존재 및 isStandard = true
 * - 각 표준 상수의 key 가 B1 seed 와 정확히 일치
 * - displayOrder 가 seed 순서와 일치 (1~5)
 */
class ResolutionTest : DescribeSpec({

    describe("Resolution.create — factory invariants") {

        context("유효한 입력") {
            it("key/name 입력 시 Resolution 인스턴스를 반환한다") {
                val resolution =
                    Resolution.create(
                        key = "fixed",
                        name = "Fixed",
                    )

                resolution.key shouldBe "fixed"
                resolution.name shouldBe "Fixed"
            }

            it("id 는 null 이다 (DB 저장 전 PK 미확정 상태)") {
                val resolution =
                    Resolution.create(
                        key = "fixed",
                        name = "Fixed",
                    )

                resolution.id.shouldBeNull()
            }

            it("isStandard 기본값은 false 다") {
                val resolution =
                    Resolution.create(
                        key = "custom",
                        name = "Custom",
                    )

                resolution.isStandard shouldBe false
            }

            it("isStandard = true 로 명시 가능하다") {
                val resolution =
                    Resolution.create(
                        key = "fixed",
                        name = "Fixed",
                        isStandard = true,
                    )

                resolution.isStandard shouldBe true
            }

            it("description 은 null 허용이다") {
                val resolution =
                    Resolution.create(
                        key = "fixed",
                        name = "Fixed",
                        description = null,
                    )

                resolution.description.shouldBeNull()
            }

            it("deletedAt 은 null 이다 (활성 상태)") {
                val resolution =
                    Resolution.create(
                        key = "fixed",
                        name = "Fixed",
                    )

                resolution.deletedAt.shouldBeNull()
            }
        }

        context("name 유효성 검증") {
            it("name 이 빈 문자열이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Resolution.create(
                        key = "fixed",
                        name = "",
                    )
                }
            }

            it("name 이 공백 전용 문자열이면 IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    Resolution.create(
                        key = "fixed",
                        name = "   ",
                    )
                }
            }
        }
    }

    describe("Resolution 표준 5종 — key 검증 (B1 seed 일치)") {

        it("FIXED 의 key 는 'fixed' 이다") {
            Resolution.FIXED.key shouldBe "fixed"
        }

        it("WONT_FIX 의 key 는 'wontfix' 이다") {
            Resolution.WONT_FIX.key shouldBe "wontfix"
        }

        it("DUPLICATE 의 key 는 'duplicate' 이다") {
            Resolution.DUPLICATE.key shouldBe "duplicate"
        }

        it("CANNOT_REPRODUCE 의 key 는 'cannotreproduce' 이다") {
            Resolution.CANNOT_REPRODUCE.key shouldBe "cannotreproduce"
        }

        it("DONE 의 key 는 'done' 이다") {
            Resolution.DONE.key shouldBe "done"
        }
    }

    describe("Resolution 표준 5종 — isStandard 검증") {

        it("표준 5종은 모두 isStandard = true 다") {
            val standards =
                listOf(
                    Resolution.FIXED,
                    Resolution.WONT_FIX,
                    Resolution.DUPLICATE,
                    Resolution.CANNOT_REPRODUCE,
                    Resolution.DONE,
                )

            standards.forEach { it.isStandard shouldBe true }
        }
    }

    describe("Resolution 표준 5종 — displayOrder 검증 (B1 seed 일치)") {

        it("FIXED 의 displayOrder 는 1 이다") {
            Resolution.FIXED.displayOrder shouldBe 1
        }

        it("WONT_FIX 의 displayOrder 는 2 이다") {
            Resolution.WONT_FIX.displayOrder shouldBe 2
        }

        it("DUPLICATE 의 displayOrder 는 3 이다") {
            Resolution.DUPLICATE.displayOrder shouldBe 3
        }

        it("CANNOT_REPRODUCE 의 displayOrder 는 4 이다") {
            Resolution.CANNOT_REPRODUCE.displayOrder shouldBe 4
        }

        it("DONE 의 displayOrder 는 5 이다") {
            Resolution.DONE.displayOrder shouldBe 5
        }
    }
})
