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
 * - JSON: `issues[].key`→sourceKey, `fields.attachment[]`→첨부, `changelog.histories[]`(중첩
 *   `items[]`)→변경이력 — CSV 는 세 필드 모두 미지원(JSON 전용, sourceKey=null·목록=emptyList)
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

    // ── JSON: 댓글/worklog (FR-IM-01 PR3 Task 6) ─────────────────────────────

    describe("ImportRowParser.parseJson — 댓글/worklog") {
        it("fields.comment.comments[] 의 author.emailAddress/body/created 를 순서대로 추출한다") {
            val json =
                """
                {
                  "issues": [
                    {
                      "fields": {
                        "summary": "Imported issue",
                        "comment": {
                          "comments": [
                            {
                              "author": { "emailAddress": "bob@corp.com" },
                              "body": "First comment",
                              "created": "2024-01-15T10:00:00.000+0000"
                            },
                            {
                              "author": { "emailAddress": "alice@corp.com" },
                              "body": "Second comment",
                              "created": "2024-01-16T11:00:00.000+0000"
                            }
                          ]
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()

            val rows = parseJsonRows(json)

            rows shouldHaveSize 1
            rows[0].comments shouldHaveSize 2
            rows[0].comments[0].body shouldBe "First comment"
            rows[0].comments[0].authorEmail shouldBe "bob@corp.com"
            rows[0].comments[0].createdAt shouldBe "2024-01-15T10:00:00.000+0000"
            rows[0].comments[1].body shouldBe "Second comment"
            rows[0].comments[1].authorEmail shouldBe "alice@corp.com"
        }

        it("fields.comment 가 없으면 comments=emptyList") {
            val json = """{ "issues": [ { "fields": { "summary": "one" } } ] }"""

            val rows = parseJsonRows(json)

            rows[0].comments shouldContainExactly emptyList()
        }

        it("fields.worklog.worklogs[] 의 author.emailAddress/timeSpentSeconds/started/comment 를 추출한다") {
            val json =
                """
                {
                  "issues": [
                    {
                      "fields": {
                        "summary": "Imported issue",
                        "worklog": {
                          "worklogs": [
                            {
                              "author": { "emailAddress": "bob@corp.com" },
                              "timeSpentSeconds": 3600,
                              "started": "2024-01-15T09:00:00.000+0000",
                              "comment": "Investigated bug"
                            }
                          ]
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()

            val rows = parseJsonRows(json)

            rows shouldHaveSize 1
            rows[0].worklogs shouldHaveSize 1
            val worklog = rows[0].worklogs[0]
            worklog.timeSpentSeconds shouldBe 3600
            worklog.startedAt shouldBe "2024-01-15T09:00:00.000+0000"
            worklog.authorEmail shouldBe "bob@corp.com"
            worklog.comment shouldBe "Investigated bug"
        }

        it("fields.worklog 가 없으면 worklogs=emptyList") {
            val json = """{ "issues": [ { "fields": { "summary": "one" } } ] }"""

            val rows = parseJsonRows(json)

            rows[0].worklogs shouldContainExactly emptyList()
        }
    }

    // ── CSV: 댓글 (FR-IM-01 PR3 Task 6, ★C2 회귀) ─────────────────────────────

    describe("ImportRowParser.parseCsv — 댓글") {
        it("동명 Comment 컬럼 2개 → 댓글 2건(putIfAbsent 유실 회귀 방지, eng-review C2)") {
            val csv =
                "Summary,Comment,Comment\r\n" +
                    "Task A,\"2024-01-10;bob@corp.com;First comment\"," +
                    "\"2024-01-11;alice@corp.com;Second comment\"\r\n"

            val rows = parseCsvRows(csv)

            rows shouldHaveSize 1
            rows[0].comments shouldHaveSize 2
            rows[0].comments[0].createdAt shouldBe "2024-01-10"
            rows[0].comments[0].authorEmail shouldBe "bob@corp.com"
            rows[0].comments[0].body shouldBe "First comment"
            rows[0].comments[1].createdAt shouldBe "2024-01-11"
            rows[0].comments[1].authorEmail shouldBe "alice@corp.com"
            rows[0].comments[1].body shouldBe "Second comment"
        }

        it("comment 셀은 date;author;body 를 세미콜론 limit=3 으로 분해해 본문 내부 세미콜론을 보존한다") {
            val csv =
                "Summary,Comment\r\n" +
                    "Task A,\"2024-01-10;bob@corp.com;Body; with; semicolons\"\r\n"

            val rows = parseCsvRows(csv)

            rows[0].comments shouldHaveSize 1
            rows[0].comments[0].body shouldBe "Body; with; semicolons"
        }

        it("comment 셀이 3파트 미만이면 전체를 body 로, author/created 는 null 로 폴백한다") {
            val csv =
                "Summary,Comment\r\n" +
                    "Task A,\"just a plain comment without delimiters\"\r\n"

            val rows = parseCsvRows(csv)

            rows[0].comments shouldHaveSize 1
            rows[0].comments[0].body shouldBe "just a plain comment without delimiters"
            rows[0].comments[0].authorEmail.shouldBeNull()
            rows[0].comments[0].createdAt.shouldBeNull()
        }

        it("comment 셀이 비어있으면 해당 컬럼은 무시한다(빈 셀 무시, PR1 동형)") {
            val csv =
                "Summary,Comment,Comment\r\n" +
                    "Task A,\"2024-01-10;bob@corp.com;Only one comment\",\r\n"

            val rows = parseCsvRows(csv)

            rows[0].comments shouldHaveSize 1
        }

        it("worklog 는 CSV 에서 미지원 — 항상 emptyList") {
            val csv =
                "Summary,Comment\r\n" +
                    "Task A,\"2024-01-10;bob@corp.com;Comment\"\r\n"

            val rows = parseCsvRows(csv)

            rows[0].worklogs shouldContainExactly emptyList()
        }
    }

    // ── JSON: sourceKey/첨부/변경이력 (FR-IM-01 PR4 Task 4) ──────────────────────

    describe("ImportRowParser.parseJson — sourceKey/첨부/변경이력") {
        it("issues[].key 를 sourceKey 로 추출한다") {
            val json = """{ "issues": [ { "key": "JIRA-42", "fields": { "summary": "one" } } ] }"""

            val rows = parseJsonRows(json)

            rows[0].sourceKey shouldBe "JIRA-42"
        }

        it("key 가 없으면 sourceKey=null") {
            val json = """{ "issues": [ { "fields": { "summary": "one" } } ] }"""

            val rows = parseJsonRows(json)

            rows[0].sourceKey.shouldBeNull()
        }

        it("fields.attachment[] 의 filename/author.emailAddress/created/mimeType/size 를 순서대로 추출한다") {
            val json =
                """
                {
                  "issues": [
                    {
                      "key": "JIRA-1",
                      "fields": {
                        "summary": "Imported issue",
                        "attachment": [
                          {
                            "filename": "screenshot.png",
                            "author": { "emailAddress": "bob@corp.com" },
                            "created": "2024-01-15T10:00:00.000+0000",
                            "mimeType": "image/png",
                            "size": 20480
                          },
                          {
                            "filename": "log.txt",
                            "author": { "emailAddress": "alice@corp.com" },
                            "created": "2024-01-16T11:00:00.000+0000",
                            "mimeType": "text/plain",
                            "size": 1024
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val rows = parseJsonRows(json)

            rows shouldHaveSize 1
            rows[0].attachments shouldHaveSize 2
            val first = rows[0].attachments[0]
            first.filename shouldBe "screenshot.png"
            first.authorEmail shouldBe "bob@corp.com"
            first.created shouldBe "2024-01-15T10:00:00.000+0000"
            first.mimeType shouldBe "image/png"
            first.sizeBytes shouldBe 20480L
            rows[0].attachments[1].filename shouldBe "log.txt"
            rows[0].attachments[1].authorEmail shouldBe "alice@corp.com"
        }

        it("fields.attachment 가 없으면 attachments=emptyList") {
            val json = """{ "issues": [ { "fields": { "summary": "one" } } ] }"""

            val rows = parseJsonRows(json)

            rows[0].attachments shouldContainExactly emptyList()
        }

        it(
            "changelog.histories[] 의 author.emailAddress/created 와 중첩 items[] " +
                "(field/fromString/toString) 을 추출한다",
        ) {
            val json =
                """
                {
                  "issues": [
                    {
                      "key": "JIRA-1",
                      "fields": { "summary": "Imported issue" },
                      "changelog": {
                        "histories": [
                          {
                            "author": { "emailAddress": "bob@corp.com" },
                            "created": "2024-01-15T10:00:00.000+0000",
                            "items": [
                              { "field": "status", "fromString": "To Do", "toString": "In Progress" },
                              { "field": "assignee", "fromString": null, "toString": "alice@corp.com" }
                            ]
                          }
                        ]
                      }
                    }
                  ]
                }
                """.trimIndent()

            val rows = parseJsonRows(json)

            rows shouldHaveSize 1
            rows[0].changelog shouldHaveSize 1
            val group = rows[0].changelog[0]
            group.authorEmail shouldBe "bob@corp.com"
            group.created shouldBe "2024-01-15T10:00:00.000+0000"
            group.items shouldHaveSize 2
            group.items[0].field shouldBe "status"
            group.items[0].fromValue shouldBe "To Do"
            group.items[0].toValue shouldBe "In Progress"
            group.items[1].field shouldBe "assignee"
            group.items[1].fromValue.shouldBeNull()
            group.items[1].toValue shouldBe "alice@corp.com"
        }

        it("changelog 가 없으면 changelog=emptyList") {
            val json = """{ "issues": [ { "fields": { "summary": "one" } } ] }"""

            val rows = parseJsonRows(json)

            rows[0].changelog shouldContainExactly emptyList()
        }
    }

    // ── CSV: sourceKey/첨부/변경이력 (JSON 전용, FR-IM-01 PR4 Task 4) ────────────

    describe("ImportRowParser.parseCsv — sourceKey/첨부/변경이력") {
        it("CSV 는 sourceKey=null, attachments/changelog=emptyList 를 반환한다(JSON 전용)") {
            val csv = "Summary\r\na\r\n"

            val rows = parseCsvRows(csv)

            rows[0].sourceKey.shouldBeNull()
            rows[0].attachments shouldContainExactly emptyList()
            rows[0].changelog shouldContainExactly emptyList()
        }
    }

    // ── CSV: 매핑 기반 파싱 (FR-IM-02 PR-A Task 3) ────────────────────────────

    describe("ImportRowParser.parseCsv — fieldMapping (매핑 모드)") {
        fun parseMappedCsvRows(
            text: String,
            fieldMapping: Map<String, String>,
        ): List<ParsedImportRow> {
            val rows = mutableListOf<ParsedImportRow>()
            parser.parseCsv(stream(text), fieldMapping) { rows.add(it) }
            return rows
        }

        it("임의 헤더 CSV + fieldMapping 이 있으면 매핑대로 파싱한다") {
            val csv = "제목,설명\r\n버그 수정,긴급 처리 필요\r\n"
            val fieldMapping = mapOf("제목" to "summary", "설명" to "description")

            val rows = parseMappedCsvRows(csv, fieldMapping)

            rows shouldHaveSize 1
            rows[0].summary shouldBe "버그 수정"
            rows[0].description shouldBe "긴급 처리 필요"
        }

        it("fieldMapping 을 넘기지 않으면(2-인자) canonical 동작이 그대로 유지된다(회귀)") {
            val csv = "Summary,Description\r\nCanonical works,Body\r\n"

            val rows = parseCsvRows(csv)

            rows shouldHaveSize 1
            rows[0].summary shouldBe "Canonical works"
            rows[0].description shouldBe "Body"
        }

        it("매핑 모드에서는 리터럴 summary 헤더가 없어도 throw 없이 파싱된다") {
            val csv = "제목\r\n제목만 있음\r\n"
            val fieldMapping = mapOf("제목" to "summary")

            val rows = parseMappedCsvRows(csv, fieldMapping)

            rows shouldHaveSize 1
            rows[0].summary shouldBe "제목만 있음"
        }

        it("매핑 모드에서도 동명 Comment 컬럼 다중 수집이 유지된다(카탈로그에 댓글 항목 없음)") {
            val csv =
                "제목,Comment,Comment\r\n" +
                    "Task A,\"2024-01-10;bob@corp.com;First comment\"," +
                    "\"2024-01-11;alice@corp.com;Second comment\"\r\n"
            val fieldMapping = mapOf("제목" to "summary")

            val rows = parseMappedCsvRows(csv, fieldMapping)

            rows shouldHaveSize 1
            rows[0].comments shouldHaveSize 2
            rows[0].comments[0].authorEmail shouldBe "bob@corp.com"
            rows[0].comments[1].authorEmail shouldBe "alice@corp.com"
        }
    }

    // ── CSV: 헤더 + 샘플 미리보기 (FR-IM-02 PR-A Task 3, analyze 단계) ───────────

    describe("ImportRowParser.readHeaderAndSample") {
        it("헤더 + 최대 5행만 읽고 조기중단한다(이후 손상된 데이터가 있어도 무시)") {
            val header = "Summary,Priority\r\n"
            val validRows = (1..10).joinToString("") { "Row $it,Medium\r\n" }
            val corruptedTail = "\"unterminated"
            val csv = header + validRows + corruptedTail

            val sample = parser.readHeaderAndSample(stream(csv))

            sample.headers shouldContainExactly listOf("Summary", "Priority")
            sample.sampleRows shouldHaveSize 5
            sample.sampleRows[0] shouldContainExactly listOf("Row 1", "Medium")
            sample.sampleRows[4] shouldContainExactly listOf("Row 5", "Medium")
        }
    }
}) {
    private companion object {
        const val LARGE_ROW_COUNT = 5_000
    }
}
