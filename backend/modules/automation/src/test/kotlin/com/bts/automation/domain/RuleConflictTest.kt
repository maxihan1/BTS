// ConflictType 4종 + RuleConflict 값 객체 + ruleIds 정렬 정규화 단위 테스트 (FR-AT-04 Task 1)

package com.bts.automation.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class RuleConflictTest : DescribeSpec({

    describe("ConflictType — enum 4종") {
        it("4종 모두 정의된다") {
            ConflictType.entries.size shouldBe 4
        }

        it("CYCLE/FIELD_CONFLICT/PRIORITY_AMBIGUITY/PERMISSION_MISSING 을 포함한다") {
            ConflictType.entries.toSet() shouldBe
                setOf(
                    ConflictType.CYCLE,
                    ConflictType.FIELD_CONFLICT,
                    ConflictType.PRIORITY_AMBIGUITY,
                    ConflictType.PERMISSION_MISSING,
                )
        }
    }

    describe("ConflictSeverity") {
        it("WARNING 값이 존재한다") {
            ConflictSeverity.WARNING shouldBe ConflictSeverity.valueOf("WARNING")
        }
    }

    describe("RuleConflict — 생성 + 필드 접근") {
        it("type/severity/ruleIds/detail 을 그대로 보관한다") {
            val ruleA = UUID.randomUUID()
            val ruleB = UUID.randomUUID()

            val conflict =
                RuleConflict(
                    type = ConflictType.CYCLE,
                    severity = ConflictSeverity.WARNING,
                    ruleIds = listOf(ruleA, ruleB),
                    detail = "규칙 A와 B가 서로 priority 변경을 유발해 무한 루프가 될 수 있습니다.",
                )

            conflict.type shouldBe ConflictType.CYCLE
            conflict.severity shouldBe ConflictSeverity.WARNING
            conflict.ruleIds shouldBe listOf(ruleA, ruleB)
            conflict.detail shouldBe "규칙 A와 B가 서로 priority 변경을 유발해 무한 루프가 될 수 있습니다."
        }
    }

    describe("RuleConflict.of — ruleIds 정렬 정규화") {
        it("입력 순서와 무관하게 ruleIds 를 정렬된 순서로 보관한다") {
            // UUID 자연 순서(Comparable<UUID>)는 mostSigBits/leastSigBits 를 부호 있는 long 으로
            // 비교한다 — 16진 문자열 사전순과 다르다(최상위 니블이 8 이상이면 음수로 취급된다).
            // 두 UUID 모두 최상위 니블을 8 미만으로 고정해 부호 반전 없이 문자열 순서와 일치시킨다.
            val smaller = UUID.fromString("10000000-0000-0000-0000-000000000000")
            val larger = UUID.fromString("20000000-0000-0000-0000-000000000000")

            val conflict =
                RuleConflict.of(
                    type = ConflictType.CYCLE,
                    ruleIds = listOf(larger, smaller),
                    detail = "사이클 감지",
                )

            conflict.ruleIds shouldBe listOf(smaller, larger)
        }

        it("severity 기본값은 WARNING 이다") {
            val ruleId = UUID.randomUUID()

            val conflict =
                RuleConflict.of(type = ConflictType.FIELD_CONFLICT, ruleIds = listOf(ruleId), detail = "필드 충돌")

            conflict.severity shouldBe ConflictSeverity.WARNING
        }

        it("동일 ruleId 가 중복 입력돼도 정렬만 보장하고(dedup 은 상위 호출자 책임) 갯수는 유지한다") {
            val ruleA = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val ruleB = UUID.fromString("00000000-0000-0000-0000-000000000002")

            val conflict =
                RuleConflict.of(
                    type = ConflictType.CYCLE,
                    ruleIds = listOf(ruleB, ruleA, ruleB),
                    detail = "사이클",
                )

            conflict.ruleIds shouldBe listOf(ruleA, ruleB, ruleB)
        }
    }
})
