// CustomFieldDefinition Aggregate Root 단위 테스트 — FieldType 10종 + factory invariants 검증
package com.bts.issue.customfield.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * [CustomFieldDefinition] Aggregate Root 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 대상.
 * - [FieldType] 10종 열거 + [FieldType.isSelectType] 헬퍼
 * - [CustomFieldDefinition.create] factory — key/name/options 불변식
 * - [CustomFieldOption] 불변 데이터 클래스
 * - 선택형 타입(isSelectType)에 옵션 없으면 [InvalidFieldDefinitionException] 던짐
 * - 비선택형 타입에 options 전달 시 정상 생성
 */
class CustomFieldDefinitionTest : DescribeSpec({

    val projectId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val sampleOption = CustomFieldOption(value = "option_a", label = "옵션 A", displayOrder = 1)

    // ── FieldType 열거 + isSelectType ─────────────────────────────────────────

    describe("FieldType — 10종 열거 + isSelectType") {

        it("SHORT_TEXT 는 isSelectType = false") {
            FieldType.SHORT_TEXT.isSelectType.shouldBeFalse()
        }

        it("LONG_TEXT 는 isSelectType = false") {
            FieldType.LONG_TEXT.isSelectType.shouldBeFalse()
        }

        it("NUMBER 는 isSelectType = false") {
            FieldType.NUMBER.isSelectType.shouldBeFalse()
        }

        it("DATE 는 isSelectType = false") {
            FieldType.DATE.isSelectType.shouldBeFalse()
        }

        it("DATETIME 는 isSelectType = false") {
            FieldType.DATETIME.isSelectType.shouldBeFalse()
        }

        it("SINGLE_SELECT 는 isSelectType = true") {
            FieldType.SINGLE_SELECT.isSelectType.shouldBeTrue()
        }

        it("MULTI_SELECT 는 isSelectType = true") {
            FieldType.MULTI_SELECT.isSelectType.shouldBeTrue()
        }

        it("CHECKBOX 는 isSelectType = false") {
            FieldType.CHECKBOX.isSelectType.shouldBeFalse()
        }

        it("RADIO 는 isSelectType = true") {
            FieldType.RADIO.isSelectType.shouldBeTrue()
        }

        it("URL 는 isSelectType = false") {
            FieldType.URL.isSelectType.shouldBeFalse()
        }

        it("FieldType 열거값이 정확히 10종이다") {
            FieldType.entries.size shouldBe 10
        }
    }

    // ── CustomFieldDefinition.create — 정상 케이스 ───────────────────────────

    describe("CustomFieldDefinition.create — 정상 케이스") {

        context("비선택형 타입") {
            it("SHORT_TEXT 로 옵션 없이 생성하면 인스턴스를 반환한다") {
                val definition =
                    CustomFieldDefinition.create(
                        projectId = projectId,
                        key = "summary_text",
                        name = "요약 텍스트",
                        fieldType = FieldType.SHORT_TEXT,
                    )

                definition.projectId shouldBe projectId
                definition.key shouldBe "summary_text"
                definition.name shouldBe "요약 텍스트"
                definition.fieldType shouldBe FieldType.SHORT_TEXT
            }

            it("id 는 null 이다 (DB 저장 전)") {
                val definition =
                    CustomFieldDefinition.create(
                        projectId = projectId,
                        key = "my_field",
                        name = "내 필드",
                        fieldType = FieldType.NUMBER,
                    )

                definition.id.shouldBeNull()
            }

            it("required 기본값은 false 이다") {
                val definition =
                    CustomFieldDefinition.create(
                        projectId = projectId,
                        key = "opt_field",
                        name = "선택 필드",
                        fieldType = FieldType.DATE,
                    )

                definition.required shouldBe false
            }

            it("options 기본값은 빈 리스트다") {
                val definition =
                    CustomFieldDefinition.create(
                        projectId = projectId,
                        key = "no_opts",
                        name = "옵션 없음",
                        fieldType = FieldType.LONG_TEXT,
                    )

                definition.options.shouldBeEmpty()
            }

            it("required = true 로 생성할 수 있다") {
                val definition =
                    CustomFieldDefinition.create(
                        projectId = projectId,
                        key = "req_field",
                        name = "필수 필드",
                        fieldType = FieldType.SHORT_TEXT,
                        required = true,
                    )

                definition.required shouldBe true
            }

            it("displayOrder 를 지정할 수 있다") {
                val definition =
                    CustomFieldDefinition.create(
                        projectId = projectId,
                        key = "ordered_field",
                        name = "순서 있는 필드",
                        fieldType = FieldType.URL,
                        displayOrder = 5,
                    )

                definition.displayOrder shouldBe 5
            }
        }

        context("선택형 타입 — 옵션 있음") {
            it("SINGLE_SELECT + 옵션 1건으로 생성하면 인스턴스를 반환한다") {
                val definition =
                    CustomFieldDefinition.create(
                        projectId = projectId,
                        key = "priority_level",
                        name = "우선순위",
                        fieldType = FieldType.SINGLE_SELECT,
                        options = listOf(sampleOption),
                    )

                definition.fieldType shouldBe FieldType.SINGLE_SELECT
                definition.options.size shouldBe 1
            }

            it("MULTI_SELECT + 옵션 2건으로 생성하면 옵션 2건을 포함한다") {
                val opts =
                    listOf(
                        CustomFieldOption(value = "a", label = "A", displayOrder = 1),
                        CustomFieldOption(value = "b", label = "B", displayOrder = 2),
                    )
                val definition =
                    CustomFieldDefinition.create(
                        projectId = projectId,
                        key = "tags_field",
                        name = "태그",
                        fieldType = FieldType.MULTI_SELECT,
                        options = opts,
                    )

                definition.options.size shouldBe 2
            }

            it("RADIO + 옵션 있으면 생성된다") {
                val definition =
                    CustomFieldDefinition.create(
                        projectId = projectId,
                        key = "yesno_field",
                        name = "예/아니오",
                        fieldType = FieldType.RADIO,
                        options =
                            listOf(
                                CustomFieldOption(value = "yes", label = "예", displayOrder = 1),
                                CustomFieldOption(value = "no", label = "아니오", displayOrder = 2),
                            ),
                    )

                definition.options.size shouldBe 2
            }
        }
    }

    // ── CustomFieldDefinition.create — key 불변식 ────────────────────────────

    describe("CustomFieldDefinition.create — key 불변식") {

        it("key 가 URL-safe 소문자(영문자 시작, 영숫자+언더스코어)면 정상 생성") {
            val definition =
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "my_field_01",
                    name = "테스트",
                    fieldType = FieldType.SHORT_TEXT,
                )

            definition.key shouldBe "my_field_01"
        }

        it("key 에 대문자가 포함되면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "MyField",
                    name = "테스트",
                    fieldType = FieldType.SHORT_TEXT,
                )
            }
        }

        it("key 가 빈 문자열이면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "",
                    name = "테스트",
                    fieldType = FieldType.SHORT_TEXT,
                )
            }
        }

        it("key 에 하이픈이 포함되면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "my-field",
                    name = "테스트",
                    fieldType = FieldType.SHORT_TEXT,
                )
            }
        }

        it("key 가 숫자로 시작하면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "1field",
                    name = "테스트",
                    fieldType = FieldType.SHORT_TEXT,
                )
            }
        }

        it("key 에 공백이 포함되면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "my field",
                    name = "테스트",
                    fieldType = FieldType.SHORT_TEXT,
                )
            }
        }
    }

    // ── CustomFieldDefinition.create — name 불변식 ───────────────────────────

    describe("CustomFieldDefinition.create — name 불변식") {

        it("name 이 빈 문자열이면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "valid_key",
                    name = "",
                    fieldType = FieldType.SHORT_TEXT,
                )
            }
        }

        it("name 이 공백-only 이면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "valid_key",
                    name = "   ",
                    fieldType = FieldType.SHORT_TEXT,
                )
            }
        }
    }

    // ── 선택형 타입 + 옵션 없음 불변식 ───────────────────────────────────────

    describe("CustomFieldDefinition.create — 선택형 + 빈 옵션 불변식") {

        it("SINGLE_SELECT 에 옵션 없으면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "empty_select",
                    name = "빈 선택",
                    fieldType = FieldType.SINGLE_SELECT,
                    options = emptyList(),
                )
            }
        }

        it("MULTI_SELECT 에 옵션 없으면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "empty_multi",
                    name = "빈 다중선택",
                    fieldType = FieldType.MULTI_SELECT,
                )
            }
        }

        it("RADIO 에 옵션 없으면 InvalidFieldDefinitionException 을 던진다") {
            shouldThrow<InvalidFieldDefinitionException> {
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "empty_radio",
                    name = "빈 라디오",
                    fieldType = FieldType.RADIO,
                )
            }
        }

        it("CHECKBOX 는 isSelectType = false 이므로 옵션 없어도 정상 생성한다") {
            val definition =
                CustomFieldDefinition.create(
                    projectId = projectId,
                    key = "cb_field",
                    name = "체크박스",
                    fieldType = FieldType.CHECKBOX,
                )

            definition.fieldType shouldBe FieldType.CHECKBOX
        }
    }

    // ── CustomFieldOption 불변 구조 ───────────────────────────────────────────

    describe("CustomFieldOption — 불변 데이터 클래스") {

        it("value/label/displayOrder 가 그대로 보존된다") {
            val option = CustomFieldOption(value = "low", label = "낮음", displayOrder = 3)

            option.value shouldBe "low"
            option.label shouldBe "낮음"
            option.displayOrder shouldBe 3
        }
    }
})
