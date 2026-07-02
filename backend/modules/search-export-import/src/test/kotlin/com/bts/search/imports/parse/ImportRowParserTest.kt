// ImportRowParser 단위 테스트 — CSV/JSON 코어 필드 추출, Priority 매핑, 정화, 스트리밍 (FR-IM-01 PR1 Task 5)

package com.bts.search.imports.parse

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * [ImportRowParser] 단위 테스트.
 *
 * 검증 범위.
 * - CSV: 대소문자 무시 헤더 인식, RFC 4180 따옴표(콤마/개행 내포), labels 콤마/세미콜론 분리,
 *   rowNumber 부여, 헤더만/빈파일 → 0행, 필수 컬럼(Summary) 부재/따옴표 미종결 → [ImportParseException]
 * - JSON: `issues[].fields` 하위 추출, reporter/assignee `emailAddress`, `components[].name`,
 *   빈 `issues` 배열 → 0행, 구조 파손 → [ImportParseException]
 * - Priority 이름/숫자(1..5) → 정규화 이름 매핑, 범위밖/미인식 → null
 * - NUL/제어문자 정화 (CSV/JSON 공통)
 * - 대량 입력에서도 콜백 기반으로 전체 리스트를 만들지 않고 처리(구조적 스트리밍 보장)
 */
class ImportRowParserTest : DescribeSpec({

    val parser = ImportRowParser()

    fun stream(text: String): InputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    fun parseCsvRows(text: String): List<ParsedImportRow> {
        val rows = mutableListOf<ParsedImportRow>()
        parser.parseCsv(stream(text)) { rows.add(it) }
        return rows
    }

    fun parseJsonRows(text: String): List<ParsedImportRow> {
        val rows = mutableListOf<ParsedImportRow>()
        parser.parseJson(stream(text)) { rows.add(it) }
        return rows
    }

    // ── CSV: 코어 필드 추출 ───────────────────────────────────────────────────

    describe("ImportRowParser.parseCsv") {
        it("대소문자 무시 헤더 + 따옴표 내 콤마/개행 + labels 콤마 분리 → 정확한 ParsedImportRow, rowNumber=1 부여") {
            val csv =
                "SUMMARY,description,ISSUE TYPE,priority,REPORTER,assignee,LABELS,component\r\n" +
                    "\"Fix bug, urgent\",\"Multi-line\ndescription\",Bug,Highest," +
                    "bob@corp.com,alice@corp.com,\"backend,urgent\",Core\r\n"

            val rows = parseCsvRows(csv)

            rows shouldHaveSize 1
            val row = rows[0]
            row.rowNumber shouldBe 1
            row.summary shouldBe "Fix bug, urgent"
            row.description shouldBe "Multi-line\ndescription"
            row.typeName shouldBe "Bug"
            row.priorityName shouldBe "Highest"
            row.reporterEmail shouldBe "bob@corp.com"
            row.assigneeEmail shouldBe "alice@corp.com"
            row.labels shouldContainExactly listOf("backend", "urgent")
            row.componentNames shouldContainExactly listOf("Core")
        }

        it("labels 는 세미콜론으로도 분리하고, 여러 데이터 행에 순차 rowNumber 를 부여한다") {
            val csv =
                "Summary,Labels\r\n" +
                    "First,a;b;c\r\n" +
                    "Second,\r\n"

            val rows = parseCsvRows(csv)

            rows shouldHaveSize 2
            rows[0].rowNumber shouldBe 1
            rows[0].labels shouldContainExactly listOf("a", "b", "c")
            rows[1].rowNumber shouldBe 2
            rows[1].labels shouldContainExactly emptyList()
        }

        it("헤더만 있는 CSV → 0행") {
            parseCsvRows("Summary,Description\r\n") shouldHaveSize 0
        }

        it("완전히 빈 파일 → 0행") {
            parseCsvRows("") shouldHaveSize 0
        }

        it("필수 컬럼 Summary 가 헤더에 없으면 ImportParseException") {
            shouldThrow<ImportParseException> {
                parseCsvRows("Foo,Bar\r\nx,y\r\n")
            }
        }

        it("따옴표가 닫히지 않은 채 파일이 끝나면 ImportParseException") {
            shouldThrow<ImportParseException> {
                parseCsvRows("Summary\r\n\"unterminated")
            }
        }

        it("NUL/제어문자를 필드 값에서 제거한다") {
            val nul = 0.toChar()
            val bel = 7.toChar()
            val csv = "Summary\r\nBad${nul}Summary${bel}Here\r\n"

            val rows = parseCsvRows(csv)

            rows[0].summary shouldBe "BadSummaryHere"
        }

        it("대량 행 입력도 콜백으로 한 행씩 전달한다(전체 List 반환 없음)") {
            val header = "Summary,Priority\r\n"
            val body = (1..LARGE_ROW_COUNT).joinToString("") { "Row $it,Medium\r\n" }

            var count = 0
            parser.parseCsv(stream(header + body)) { row ->
                count++
                row.rowNumber shouldBe count
            }

            count shouldBe LARGE_ROW_COUNT
        }
    }

    // ── CSV: Priority 이름/숫자 매핑 ─────────────────────────────────────────────

    describe("ImportRowParser.parseCsv — Priority 매핑") {
        it("이름(대소문자 무시)과 숫자 문자열을 정규화된 이름으로 매핑한다") {
            val csv =
                "Summary,Priority\r\n" +
                    "a,highest\r\n" +
                    "b,High\r\n" +
                    "c,3\r\n" +
                    "d,LOWEST\r\n"

            val rows = parseCsvRows(csv)

            rows.map { it.priorityName } shouldContainExactly listOf("Highest", "High", "Medium", "Lowest")
        }

        it("범위밖 숫자 또는 미인식 이름, 빈 값은 null 로 무시한다") {
            val csv =
                "Summary,Priority\r\n" +
                    "a,0\r\n" +
                    "b,6\r\n" +
                    "c,urgent\r\n" +
                    "d,\r\n"

            val rows = parseCsvRows(csv)

            rows shouldHaveSize 4
            rows.forEach { it.priorityName.shouldBeNull() }
        }
    }

    // ── CSV: status/버전 (FR-IM-01 PR2 Task 2) ───────────────────────────────

    describe("ImportRowParser.parseCsv — status/버전") {
        it("헤더에 status/fix version/affects version 이 있으면(대소문자 무시) 추출한다 — 버전은 콤마/세미콜론 다중값 분리") {
            val csv =
                "Summary,Status,Fix Version,Affects Version\r\n" +
                    "a,In Progress,\"1.0,1.1\",2.0;2.1\r\n"

            val rows = parseCsvRows(csv)

            rows shouldHaveSize 1
            rows[0].statusName shouldBe "In Progress"
            rows[0].fixVersionNames shouldContainExactly listOf("1.0", "1.1")
            rows[0].affectsVersionNames shouldContainExactly listOf("2.0", "2.1")
        }

        it("status/버전 컬럼이 헤더에 없으면 statusName=null, 버전 목록=emptyList") {
            val csv = "Summary\r\na\r\n"

            val rows = parseCsvRows(csv)

            rows[0].statusName.shouldBeNull()
            rows[0].fixVersionNames shouldContainExactly emptyList()
            rows[0].affectsVersionNames shouldContainExactly emptyList()
        }
    }

    // ── JSON: 코어 필드 추출 ──────────────────────────────────────────────────

    describe("ImportRowParser.parseJson") {
        it("issues[].fields 하위에서 코어 필드를 추출한다 — reporter/assignee emailAddress, components[].name") {
            val json =
                """
                {
                  "issues": [
                    {
                      "key": "JIRA-1",
                      "fields": {
                        "summary": "Imported issue",
                        "description": "Body text",
                        "issuetype": { "name": "Story" },
                        "priority": { "name": "High" },
                        "reporter": { "emailAddress": "bob@corp.com" },
                        "assignee": { "emailAddress": "alice@corp.com" },
                        "labels": ["backend", "urgent"],
                        "components": [ { "name": "Core" }, { "name": "API" } ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val rows = parseJsonRows(json)

            rows shouldHaveSize 1
            val row = rows[0]
            row.rowNumber shouldBe 1
            row.summary shouldBe "Imported issue"
            row.description shouldBe "Body text"
            row.typeName shouldBe "Story"
            row.priorityName shouldBe "High"
            row.reporterEmail shouldBe "bob@corp.com"
            row.assigneeEmail shouldBe "alice@corp.com"
            row.labels shouldContainExactly listOf("backend", "urgent")
            row.componentNames shouldContainExactly listOf("Core", "API")
        }

        it("issues 배열이 비어있으면 0행") {
            parseJsonRows("""{ "issues": [] }""") shouldHaveSize 0
        }

        it("issues 배열이 없으면 ImportParseException") {
            shouldThrow<ImportParseException> {
                parseJsonRows("""{ "foo": "bar" }""")
            }
        }

        it("파싱 불가능한(구조가 깨진) JSON → ImportParseException") {
            shouldThrow<ImportParseException> {
                parseJsonRows("{ \"issues\": [ { \"fields\": ")
            }
        }

        it("NUL/제어문자를 필드 값에서 제거한다") {
            val json = "{ \"issues\": [ { \"fields\": { \"summary\": \"Bad\\u0000Summary\\u0007Here\" } } ] }"

            val rows = parseJsonRows(json)

            rows[0].summary shouldBe "BadSummaryHere"
        }

        it("여러 이슈에 순차 rowNumber 를 부여한다") {
            val json =
                """
                { "issues": [
                    { "fields": { "summary": "one" } },
                    { "fields": { "summary": "two" } }
                ] }
                """.trimIndent()

            val rows = parseJsonRows(json)

            rows.map { it.rowNumber } shouldContainExactly listOf(1, 2)
        }
    }

    // ── JSON: status/버전 (FR-IM-01 PR2 Task 2) ──────────────────────────────

    describe("ImportRowParser.parseJson — status/버전") {
        it("fields.status.name/fixVersions[].name/versions[].name 을 추출한다") {
            val json =
                """
                {
                  "issues": [
                    {
                      "fields": {
                        "summary": "Imported issue",
                        "status": { "name": "In Progress" },
                        "fixVersions": [ { "name": "1.0" }, { "name": "1.1" } ],
                        "versions": [ { "name": "2.0" } ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val rows = parseJsonRows(json)

            rows shouldHaveSize 1
            rows[0].statusName shouldBe "In Progress"
            rows[0].fixVersionNames shouldContainExactly listOf("1.0", "1.1")
            rows[0].affectsVersionNames shouldContainExactly listOf("2.0")
        }

        it("status/fixVersions/versions 필드가 없으면 statusName=null, 버전 목록=emptyList") {
            val json = """{ "issues": [ { "fields": { "summary": "one" } } ] }"""

            val rows = parseJsonRows(json)

            rows[0].statusName.shouldBeNull()
            rows[0].fixVersionNames shouldContainExactly emptyList()
            rows[0].affectsVersionNames shouldContainExactly emptyList()
        }
    }
}) {
    private companion object {
        const val LARGE_ROW_COUNT = 5_000
    }
}
