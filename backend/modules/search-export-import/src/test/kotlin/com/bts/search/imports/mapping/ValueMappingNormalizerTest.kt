// ValueMappingNormalizer 단위 테스트 — normalize + 3필드 distinct 수집·dedup (FR-IM-02 PR-C Task 3)

package com.bts.search.imports.mapping

import com.bts.search.imports.parse.ParsedImportRow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * [ValueMappingNormalizer] 단위 테스트.
 *
 * 검증 범위.
 * - [ValueMappingNormalizer.normalize] — trim + lowercase
 * - [ValueMappingNormalizer.collectValues] — statusName/typeName/priorityName 3필드를
 *   [ValueTargetField] 별로 distinct 정규화 수집
 * - null/blank 소스값은 수집에서 제외
 * - 대소문자만 다른 값은 정규화 후 1건으로 dedup
 */
class ValueMappingNormalizerTest : DescribeSpec({

    // ── normalize ────────────────────────────────────────────────────────────────

    describe("ValueMappingNormalizer.normalize") {
        it("앞뒤 공백을 제거하고 소문자로 변환한다") {
            ValueMappingNormalizer.normalize("  In Progress ") shouldBe "in progress"
        }
    }

    // ── collectValues — status/type/priority 3필드 distinct 수집 ─────────────────

    describe("ValueMappingNormalizer.collectValues — 3필드 distinct 수집") {
        it("statusName/typeName/priorityName 을 필드별로 정규화 수집한다") {
            val rows =
                listOf(
                    baseRow(statusName = "In Progress", typeName = "Bug", priorityName = "High"),
                    baseRow(statusName = "Done", typeName = "Task", priorityName = "Low"),
                )

            ValueMappingNormalizer.collectValues(rows) shouldBe
                mapOf(
                    ValueTargetField.STATUS to setOf("in progress", "done"),
                    ValueTargetField.TYPE to setOf("bug", "task"),
                    ValueTargetField.PRIORITY to setOf("high", "low"),
                )
        }
    }

    // ── null/blank 소스값 제외 ───────────────────────────────────────────────────

    describe("ValueMappingNormalizer.collectValues — null/blank 소스값 제외") {
        it("statusName/typeName/priorityName 이 null 이면 결과에서 제외된다") {
            val rows = listOf(baseRow(statusName = null, typeName = null, priorityName = null))

            ValueMappingNormalizer.collectValues(rows) shouldBe
                mapOf(
                    ValueTargetField.STATUS to emptySet(),
                    ValueTargetField.TYPE to emptySet(),
                    ValueTargetField.PRIORITY to emptySet(),
                )
        }

        it("공백 문자열 값은 제외된다") {
            val rows = listOf(baseRow(statusName = "   ", typeName = "Bug", priorityName = null))

            ValueMappingNormalizer.collectValues(rows) shouldBe
                mapOf(
                    ValueTargetField.STATUS to emptySet(),
                    ValueTargetField.TYPE to setOf("bug"),
                    ValueTargetField.PRIORITY to emptySet(),
                )
        }
    }

    // ── 대소문자 변형 dedup ──────────────────────────────────────────────────────

    describe("ValueMappingNormalizer.collectValues — 대소문자 변형 dedup") {
        it("대소문자만 다른 값은 정규화 후 1건으로 합쳐진다") {
            val rows =
                listOf(
                    baseRow(statusName = "In Progress", typeName = null, priorityName = null),
                    baseRow(statusName = "in progress", typeName = null, priorityName = null),
                    baseRow(statusName = "IN PROGRESS", typeName = null, priorityName = null),
                )

            ValueMappingNormalizer.collectValues(rows)[ValueTargetField.STATUS] shouldBe
                setOf("in progress")
        }
    }
})

/**
 * 테스트 전용 [ParsedImportRow] 빌더. 코어 필드는 최소값으로 고정하고 status/type/priority 필드만
 * 파라미터로 받는다.
 */
private fun baseRow(
    statusName: String?,
    typeName: String?,
    priorityName: String?,
): ParsedImportRow =
    ParsedImportRow(
        rowNumber = 1,
        summary = "테스트 이슈",
        description = null,
        typeName = typeName,
        priorityName = priorityName,
        reporterEmail = null,
        assigneeEmail = null,
        labels = emptyList(),
        componentNames = emptyList(),
        statusName = statusName,
    )
