// MappingValidator 단위 테스트 — SUMMARY_NOT_MAPPED·DUPLICATE_TARGET·UNKNOWN_TARGET·UNKNOWN_SOURCE·SOURCE_FIELD_IGNORED (FR-IM-02 PR-A Task 5)

package com.bts.search.imports.mapping

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * [MappingValidator] 도메인 단위 테스트.
 *
 * 검증 범위.
 * - summary 대상에 매핑된 소스가 없으면 error `SUMMARY_NOT_MAPPED`
 * - 둘 이상의 소스가 같은 target(IGNORE 제외)을 가리키면 error `DUPLICATE_TARGET`
 * - 카탈로그·IGNORE 밖의 target 키를 쓰면 error `UNKNOWN_TARGET`
 * - 감지된 sourceFields 밖의 소스 필드를 매핑하면 error `UNKNOWN_SOURCE`
 * - 감지됐으나 미매핑(또는 IGNORE)인 소스 필드는 warning `SOURCE_FIELD_IGNORED`
 * - 정상 매핑은 valid=true, errors 없음
 */
class MappingValidatorTest : DescribeSpec({

    // ── SUMMARY_NOT_MAPPED ──────────────────────────────────────────────────────

    describe("MappingValidator — summary 미매핑") {
        it("summary 대상에 매핑된 소스가 없으면 SUMMARY_NOT_MAPPED error 를 반환한다") {
            val result =
                MappingValidator.validate(
                    sourceFields = listOf("Title", "Desc"),
                    fieldMappings = mapOf("Title" to "description", "Desc" to "priority"),
                )

            result.valid shouldBe false
            result.errors.map { it.code } shouldBe listOf(MappingValidator.SUMMARY_NOT_MAPPED)
        }
    }

    // ── DUPLICATE_TARGET ────────────────────────────────────────────────────────

    describe("MappingValidator — 중복 target") {
        it("둘 이상의 소스가 같은 target 을 가리키면 DUPLICATE_TARGET error 를 반환한다") {
            val result =
                MappingValidator.validate(
                    sourceFields = listOf("Title", "Subject"),
                    fieldMappings = mapOf("Title" to "summary", "Subject" to "summary"),
                )

            result.valid shouldBe false
            result.errors.map { it.code } shouldBe listOf(MappingValidator.DUPLICATE_TARGET)
        }

        it("IGNORE 로 중복되는 소스는 DUPLICATE_TARGET 을 유발하지 않는다") {
            val result =
                MappingValidator.validate(
                    sourceFields = listOf("Title", "Extra1", "Extra2"),
                    fieldMappings =
                        mapOf(
                            "Title" to "summary",
                            "Extra1" to TargetField.IGNORE_KEY,
                            "Extra2" to TargetField.IGNORE_KEY,
                        ),
                )

            result.valid shouldBe true
            result.errors.shouldBeEmpty()
        }
    }

    // ── UNKNOWN_TARGET ──────────────────────────────────────────────────────────

    describe("MappingValidator — 미지 target 키") {
        it("카탈로그·IGNORE 밖의 target 키는 UNKNOWN_TARGET error 를 반환한다") {
            val result =
                MappingValidator.validate(
                    sourceFields = listOf("Title", "Weird"),
                    fieldMappings = mapOf("Title" to "summary", "Weird" to "garbage"),
                )

            result.valid shouldBe false
            result.errors.map { it.code } shouldBe listOf(MappingValidator.UNKNOWN_TARGET)
        }
    }

    // ── UNKNOWN_SOURCE ──────────────────────────────────────────────────────────

    describe("MappingValidator — 미지 소스 필드") {
        it("감지된 sourceFields 밖의 소스 필드를 매핑하면 UNKNOWN_SOURCE error 를 반환한다") {
            val result =
                MappingValidator.validate(
                    sourceFields = listOf("Title"),
                    fieldMappings = mapOf("Title" to "summary", "GhostField" to "description"),
                )

            result.valid shouldBe false
            result.errors.map { it.code } shouldBe listOf(MappingValidator.UNKNOWN_SOURCE)
        }
    }

    // ── SOURCE_FIELD_IGNORED (warning) ─────────────────────────────────────────

    describe("MappingValidator — 미매핑/IGNORE 소스 필드 warning") {
        it("감지됐으나 매핑에 없는 소스 필드는 SOURCE_FIELD_IGNORED warning 을 반환한다") {
            val result =
                MappingValidator.validate(
                    sourceFields = listOf("Title", "Unmapped"),
                    fieldMappings = mapOf("Title" to "summary"),
                )

            result.valid shouldBe true
            result.errors.shouldBeEmpty()
            result.warnings shouldHaveSize 1
            result.warnings[0].code shouldBe MappingValidator.SOURCE_FIELD_IGNORED
            result.warnings[0].field shouldBe "Unmapped"
        }

        it("IGNORE 로 명시 매핑된 소스 필드도 SOURCE_FIELD_IGNORED warning 을 반환한다") {
            val result =
                MappingValidator.validate(
                    sourceFields = listOf("Title", "Junk"),
                    fieldMappings = mapOf("Title" to "summary", "Junk" to TargetField.IGNORE_KEY),
                )

            result.valid shouldBe true
            result.warnings shouldHaveSize 1
            result.warnings[0].code shouldBe MappingValidator.SOURCE_FIELD_IGNORED
            result.warnings[0].field shouldBe "Junk"
        }
    }

    // ── 정상 매핑 ────────────────────────────────────────────────────────────────

    describe("MappingValidator — 정상 매핑") {
        it("summary 매핑 + 나머지 필드가 모두 유효하면 valid=true, errors 없음") {
            val result =
                MappingValidator.validate(
                    sourceFields = listOf("Title", "Body", "Owner"),
                    fieldMappings =
                        mapOf(
                            "Title" to "summary",
                            "Body" to "description",
                            "Owner" to "assignee",
                        ),
                )

            result.valid shouldBe true
            result.errors.shouldBeEmpty()
            result.warnings.shouldBeEmpty()
        }
    }
})
