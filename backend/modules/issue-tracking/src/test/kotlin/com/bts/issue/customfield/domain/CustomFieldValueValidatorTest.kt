// CustomFieldValueValidator 단위 테스트 — 10종 타입별 검증 + E1~E4/E6 엣지 케이스
package com.bts.issue.customfield.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import java.util.UUID

/**
 * [CustomFieldValueValidator] 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 대상.
 * - E1: 미정의 키 포함 → [CustomFieldValidationException] 던짐 (silent drop 금지)
 * - E2: required 필드 누락/null → [CustomFieldValidationException] 던짐
 * - E3: 선택형 타입에 정의되지 않은 옵션 값 → [CustomFieldValidationException] 던짐
 * - E4: fieldType 과 안 맞는 JSON 타입 → [CustomFieldValidationException] 던짐
 * - 타입별 형식 검증: SHORT_TEXT·LONG_TEXT 길이, NUMBER 유한수, DATE YYYY-MM-DD,
 *   DATETIME ISO 8601, URL http(s), CHECKBOX boolean, MULTI_SELECT 다중 옵션 값
 * - 유효한 값은 예외 없이 통과한다
 */
class CustomFieldValueValidatorTest : DescribeSpec({

    val projectId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val validator = CustomFieldValueValidator()

    // ── 헬퍼: 정의 빌더 ──────────────────────────────────────────────────────

    fun defOf(
        key: String,
        fieldType: FieldType,
        required: Boolean = false,
        options: List<CustomFieldOption> = emptyList(),
    ) = CustomFieldDefinition(
        id = null,
        projectId = projectId,
        key = key,
        name = key,
        fieldType = fieldType,
        required = required,
        displayOrder = 0,
        options = options,
    )

    val colorOptions =
        listOf(
            CustomFieldOption(value = "red", label = "빨강", displayOrder = 1),
            CustomFieldOption(value = "blue", label = "파랑", displayOrder = 2),
        )

    // ── E1: 미정의 키 거부 ────────────────────────────────────────────────────

    describe("E1 — 미정의 키 거부") {

        it("정의에 없는 키가 values 에 있으면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("name_field", FieldType.SHORT_TEXT))
            val values = mapOf("name_field" to "홍길동", "unknown_key" to "값")

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, values)
                }
            ex.message shouldContain "unknown_key"
        }

        it("정의 목록이 비어 있는데 values 가 있으면 CustomFieldValidationException 을 던진다") {
            shouldThrow<CustomFieldValidationException> {
                validator.validate(emptyList(), mapOf("any_key" to "값"))
            }
        }

        it("정의에 없는 키가 없으면 예외 없이 통과한다") {
            val definitions = listOf(defOf("valid_key", FieldType.SHORT_TEXT))
            validator.validate(definitions, mapOf("valid_key" to "값"))
        }
    }

    // ── E2: required 누락/null 거부 ───────────────────────────────────────────

    describe("E2 — required 필드 누락/null 거부") {

        it("required 필드가 values 에 없으면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("req_field", FieldType.SHORT_TEXT, required = true))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, emptyMap())
                }
            ex.message shouldContain "req_field"
        }

        it("required 필드 값이 null 이면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("req_field", FieldType.SHORT_TEXT, required = true))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, mapOf("req_field" to null))
                }
            ex.message shouldContain "req_field"
        }

        it("optional 필드가 없으면 예외 없이 통과한다") {
            val definitions = listOf(defOf("opt_field", FieldType.SHORT_TEXT, required = false))
            validator.validate(definitions, emptyMap())
        }

        it("optional 필드 값이 null 이면 예외 없이 통과한다") {
            val definitions = listOf(defOf("opt_field", FieldType.SHORT_TEXT, required = false))
            validator.validate(definitions, mapOf("opt_field" to null))
        }
    }

    // ── SHORT_TEXT 검증 ───────────────────────────────────────────────────────

    describe("SHORT_TEXT — 길이 ≤ 255") {

        it("255자 문자열은 통과한다") {
            val definitions = listOf(defOf("short", FieldType.SHORT_TEXT))
            validator.validate(definitions, mapOf("short" to "a".repeat(255)))
        }

        it("256자 문자열이면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("short", FieldType.SHORT_TEXT))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, mapOf("short" to "a".repeat(256)))
                }
            ex.message shouldContain "short"
        }

        it("String 이 아닌 값이면 CustomFieldValidationException 을 던진다 (E4)") {
            val definitions = listOf(defOf("short", FieldType.SHORT_TEXT))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("short" to 42))
            }
        }
    }

    // ── LONG_TEXT 검증 ────────────────────────────────────────────────────────

    describe("LONG_TEXT — 길이 ≤ 32768") {

        it("32768자 문자열은 통과한다") {
            val definitions = listOf(defOf("long", FieldType.LONG_TEXT))
            validator.validate(definitions, mapOf("long" to "a".repeat(32768)))
        }

        it("32769자 문자열이면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("long", FieldType.LONG_TEXT))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, mapOf("long" to "a".repeat(32769)))
                }
            ex.message shouldContain "long"
        }

        it("String 이 아닌 값이면 CustomFieldValidationException 을 던진다 (E4)") {
            val definitions = listOf(defOf("long", FieldType.LONG_TEXT))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("long" to true))
            }
        }
    }

    // ── NUMBER 검증 ───────────────────────────────────────────────────────────

    describe("NUMBER — 유한수") {

        it("정수 값은 통과한다") {
            val definitions = listOf(defOf("num", FieldType.NUMBER))
            validator.validate(definitions, mapOf("num" to 42))
        }

        it("Double 유한수 값은 통과한다") {
            val definitions = listOf(defOf("num", FieldType.NUMBER))
            validator.validate(definitions, mapOf("num" to 3.14))
        }

        it("문자열 값이면 CustomFieldValidationException 을 던진다 (E4)") {
            val definitions = listOf(defOf("num", FieldType.NUMBER))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("num" to "not-a-number"))
            }
        }

        it("Double.POSITIVE_INFINITY 이면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("num", FieldType.NUMBER))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("num" to Double.POSITIVE_INFINITY))
            }
        }

        it("Double.NaN 이면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("num", FieldType.NUMBER))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("num" to Double.NaN))
            }
        }
    }

    // ── DATE 검증 ─────────────────────────────────────────────────────────────

    describe("DATE — YYYY-MM-DD 형식") {

        it("2024-03-15 는 통과한다") {
            val definitions = listOf(defOf("dt", FieldType.DATE))
            validator.validate(definitions, mapOf("dt" to "2024-03-15"))
        }

        it("형식이 틀리면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("dt", FieldType.DATE))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, mapOf("dt" to "20240315"))
                }
            ex.message shouldContain "dt"
        }

        it("2024/03/15 형식이면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("dt", FieldType.DATE))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("dt" to "2024/03/15"))
            }
        }

        it("String 이 아닌 값이면 CustomFieldValidationException 을 던진다 (E4)") {
            val definitions = listOf(defOf("dt", FieldType.DATE))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("dt" to 20240315))
            }
        }
    }

    // ── DATETIME 검증 ─────────────────────────────────────────────────────────

    describe("DATETIME — ISO 8601 형식") {

        it("2024-03-15T10:30:00Z 는 통과한다") {
            val definitions = listOf(defOf("dtime", FieldType.DATETIME))
            validator.validate(definitions, mapOf("dtime" to "2024-03-15T10:30:00Z"))
        }

        it("2024-03-15T10:30:00+09:00 는 통과한다") {
            val definitions = listOf(defOf("dtime", FieldType.DATETIME))
            validator.validate(definitions, mapOf("dtime" to "2024-03-15T10:30:00+09:00"))
        }

        it("형식이 틀리면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("dtime", FieldType.DATETIME))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, mapOf("dtime" to "2024-03-15 10:30:00"))
                }
            ex.message shouldContain "dtime"
        }

        it("날짜만 있고 시간이 없으면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("dtime", FieldType.DATETIME))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("dtime" to "2024-03-15"))
            }
        }
    }

    // ── SINGLE_SELECT 검증 (E3) ───────────────────────────────────────────────

    describe("SINGLE_SELECT — 정의된 option.value 중 하나") {

        it("정의된 옵션 값이면 통과한다") {
            val definitions = listOf(defOf("color", FieldType.SINGLE_SELECT, options = colorOptions))
            validator.validate(definitions, mapOf("color" to "red"))
        }

        it("정의되지 않은 옵션 값이면 CustomFieldValidationException 을 던진다 (E3)") {
            val definitions = listOf(defOf("color", FieldType.SINGLE_SELECT, options = colorOptions))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, mapOf("color" to "green"))
                }
            ex.message shouldContain "color"
        }

        it("String 이 아닌 값이면 CustomFieldValidationException 을 던진다 (E4)") {
            val definitions = listOf(defOf("color", FieldType.SINGLE_SELECT, options = colorOptions))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("color" to 1))
            }
        }
    }

    // ── MULTI_SELECT 검증 (E3) ────────────────────────────────────────────────

    describe("MULTI_SELECT — 모두 정의된 option.value") {

        it("모두 정의된 옵션 값 리스트면 통과한다") {
            val definitions = listOf(defOf("colors", FieldType.MULTI_SELECT, options = colorOptions))
            validator.validate(definitions, mapOf("colors" to listOf("red", "blue")))
        }

        it("하나라도 정의되지 않은 값이 있으면 CustomFieldValidationException 을 던진다 (E3)") {
            val definitions = listOf(defOf("colors", FieldType.MULTI_SELECT, options = colorOptions))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, mapOf("colors" to listOf("red", "green")))
                }
            ex.message shouldContain "colors"
        }

        it("List 가 아닌 값이면 CustomFieldValidationException 을 던진다 (E4)") {
            val definitions = listOf(defOf("colors", FieldType.MULTI_SELECT, options = colorOptions))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("colors" to "red"))
            }
        }

        it("빈 리스트는 통과한다 (선택 0건 허용)") {
            val definitions = listOf(defOf("colors", FieldType.MULTI_SELECT, options = colorOptions))
            validator.validate(definitions, mapOf("colors" to emptyList<String>()))
        }
    }

    // ── CHECKBOX 검증 ─────────────────────────────────────────────────────────

    describe("CHECKBOX — Boolean 타입") {

        it("true 는 통과한다") {
            val definitions = listOf(defOf("agreed", FieldType.CHECKBOX))
            validator.validate(definitions, mapOf("agreed" to true))
        }

        it("false 는 통과한다") {
            val definitions = listOf(defOf("agreed", FieldType.CHECKBOX))
            validator.validate(definitions, mapOf("agreed" to false))
        }

        it("String 이면 CustomFieldValidationException 을 던진다 (E4)") {
            val definitions = listOf(defOf("agreed", FieldType.CHECKBOX))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("agreed" to "true"))
            }
        }

        it("숫자이면 CustomFieldValidationException 을 던진다 (E4)") {
            val definitions = listOf(defOf("agreed", FieldType.CHECKBOX))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("agreed" to 1))
            }
        }
    }

    // ── RADIO 검증 (E3) ───────────────────────────────────────────────────────

    describe("RADIO — 정의된 option.value 중 하나") {

        it("정의된 옵션 값이면 통과한다") {
            val definitions = listOf(defOf("color_radio", FieldType.RADIO, options = colorOptions))
            validator.validate(definitions, mapOf("color_radio" to "blue"))
        }

        it("정의되지 않은 옵션 값이면 CustomFieldValidationException 을 던진다 (E3)") {
            val definitions = listOf(defOf("color_radio", FieldType.RADIO, options = colorOptions))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, mapOf("color_radio" to "yellow"))
                }
            ex.message shouldContain "color_radio"
        }
    }

    // ── URL 검증 ─────────────────────────────────────────────────────────────

    describe("URL — http(s) 형식") {

        it("https:// URL 은 통과한다") {
            val definitions = listOf(defOf("link", FieldType.URL))
            validator.validate(definitions, mapOf("link" to "https://example.com"))
        }

        it("http:// URL 은 통과한다") {
            val definitions = listOf(defOf("link", FieldType.URL))
            validator.validate(definitions, mapOf("link" to "http://example.com/path?q=1"))
        }

        it("ftp:// URL 이면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("link", FieldType.URL))

            val ex =
                shouldThrow<CustomFieldValidationException> {
                    validator.validate(definitions, mapOf("link" to "ftp://example.com"))
                }
            ex.message shouldContain "link"
        }

        it("스킴 없이 도메인만 있으면 CustomFieldValidationException 을 던진다") {
            val definitions = listOf(defOf("link", FieldType.URL))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("link" to "example.com"))
            }
        }

        it("String 이 아닌 값이면 CustomFieldValidationException 을 던진다 (E4)") {
            val definitions = listOf(defOf("link", FieldType.URL))

            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, mapOf("link" to 12345))
            }
        }
    }

    // ── 복합 케이스 ───────────────────────────────────────────────────────────

    describe("복합 케이스 — 여러 필드 동시 검증") {

        it("여러 필드 모두 유효하면 예외 없이 통과한다") {
            val definitions =
                listOf(
                    defOf("title", FieldType.SHORT_TEXT, required = true),
                    defOf("count", FieldType.NUMBER),
                    defOf("priority", FieldType.SINGLE_SELECT, options = colorOptions),
                )
            val values =
                mapOf(
                    "title" to "테스트 이슈",
                    "count" to 5,
                    "priority" to "red",
                )
            validator.validate(definitions, values)
        }

        it("여러 필드 중 하나라도 위반이 있으면 CustomFieldValidationException 을 던진다") {
            val definitions =
                listOf(
                    defOf("title", FieldType.SHORT_TEXT, required = true),
                    defOf("count", FieldType.NUMBER),
                )
            val values =
                mapOf(
                    "title" to "유효한 제목",
                    "unknown_extra" to "값",
                )
            shouldThrow<CustomFieldValidationException> {
                validator.validate(definitions, values)
            }
        }
    }
})
