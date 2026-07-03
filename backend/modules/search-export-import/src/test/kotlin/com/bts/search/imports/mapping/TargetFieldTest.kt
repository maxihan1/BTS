// TargetField 열거형 — 대상 필드 카탈로그 11종·required/multi 속성·fromKey 해석 테스트 (FR-IM-02 PR-A Task 2)

package com.bts.search.imports.mapping

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * [TargetField] 도메인 단위 테스트.
 *
 * 검증 범위.
 * - 카탈로그 11종 정의 (summary·description·type·priority·reporter·assignee·labels·component·status·
 *   fixVersion·affectsVersion)
 * - summary 는 required=true, 그 외는 required=false
 * - labels/component/fixVersion/affectsVersion 는 multi=true, 그 외는 multi=false
 * - [TargetField.fromKey] 로 키 문자열을 카탈로그 항목으로 해석
 * - `IGNORE` 인식 ([TargetField.isIgnoreKey]) — 카탈로그 11종에는 포함되지 않는 별도 센티널
 * - 미지 키는 [TargetField.fromKey] 가 null
 */
class TargetFieldTest : DescribeSpec({

    // ── 카탈로그 11종 ────────────────────────────────────────────────────────────

    describe("TargetField — 카탈로그 11종 정의") {
        it("11종 모두 정의된다") {
            TargetField.entries.size shouldBe 11
        }

        it("11개 키가 모두 카탈로그에 존재한다") {
            val expectedKeys =
                setOf(
                    "summary",
                    "description",
                    "type",
                    "priority",
                    "reporter",
                    "assignee",
                    "labels",
                    "component",
                    "status",
                    "fixVersion",
                    "affectsVersion",
                )
            TargetField.entries.map { it.key }.toSet() shouldBe expectedKeys
        }
    }

    // ── required 속성 ────────────────────────────────────────────────────────────

    describe("TargetField.required") {
        it("summary 는 required=true") {
            TargetField.SUMMARY.required shouldBe true
        }

        it("summary 를 제외한 나머지는 모두 required=false") {
            TargetField.entries.filter { it != TargetField.SUMMARY }
                .forEach { field -> field.required shouldBe false }
        }
    }

    // ── multi 속성 ───────────────────────────────────────────────────────────────

    describe("TargetField.multi") {
        it("labels/component/fixVersion/affectsVersion 는 multi=true") {
            TargetField.LABELS.multi shouldBe true
            TargetField.COMPONENT.multi shouldBe true
            TargetField.FIX_VERSION.multi shouldBe true
            TargetField.AFFECTS_VERSION.multi shouldBe true
        }

        it("나머지 필드는 모두 multi=false") {
            val multiFields =
                setOf(
                    TargetField.LABELS,
                    TargetField.COMPONENT,
                    TargetField.FIX_VERSION,
                    TargetField.AFFECTS_VERSION,
                )
            TargetField.entries.filter { it !in multiFields }
                .forEach { field -> field.multi shouldBe false }
        }
    }

    // ── fromKey 해석 ─────────────────────────────────────────────────────────────

    describe("TargetField.fromKey") {
        it("summary 키는 TargetField.SUMMARY 로 해석된다") {
            TargetField.fromKey("summary") shouldBe TargetField.SUMMARY
        }

        it("카탈로그 11개 키 모두 자기 자신으로 왕복 해석된다") {
            TargetField.entries.forEach { field ->
                TargetField.fromKey(field.key) shouldBe field
            }
        }

        it("미지 키(garbage)는 null 을 반환한다") {
            TargetField.fromKey("garbage").shouldBeNull()
        }
    }

    // ── IGNORE 센티널 ────────────────────────────────────────────────────────────

    describe("TargetField — IGNORE 센티널") {
        it("IGNORE 는 isIgnoreKey 로 인식된다") {
            TargetField.isIgnoreKey(TargetField.IGNORE_KEY) shouldBe true
            TargetField.isIgnoreKey("IGNORE") shouldBe true
        }

        it("IGNORE 는 카탈로그 11종에 포함되지 않는다 — fromKey 는 null") {
            TargetField.fromKey(TargetField.IGNORE_KEY).shouldBeNull()
        }

        it("일반 키는 isIgnoreKey 가 false 다") {
            TargetField.isIgnoreKey("summary") shouldBe false
        }
    }

    // ── shouldNotBeNull 가드 (fromKey 해석 결과가 null 이 아님을 명시적으로 확인) ──────

    describe("TargetField.fromKey — 해석 결과 non-null 가드") {
        it("summary 해석 결과는 non-null 이다") {
            TargetField.fromKey("summary").shouldNotBeNull()
        }
    }
})
