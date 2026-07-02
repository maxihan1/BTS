// Jira 호환 CSV/JSON import 파일을 코어 필드 행(ParsedImportRow)으로 변환하는 스트리밍 파서
package com.bts.search.imports.parse

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Jira 호환 CSV(RFC 4180) / JSON(REST export) import 파일을 [ParsedImportRow] 로 변환하는 파서.
 *
 * ### 스트리밍 설계 (10만 행 규모 OOM 방지, plan Task 5 CONCERN #2)
 *
 * [parseCsv]/[parseJson] 모두 결과를 `List` 로 반환하지 않고 [onRow] 콜백으로 행 1건씩 전달한다.
 * - CSV — [BufferedReader] 로 한 줄씩 읽는다. 단, 따옴표로 묶인 필드 내부의 개행은 논리적으로
 *   하나의 행에 속하므로, 닫히지 않은 따옴표가 있는 동안만 다음 물리 줄을 이어붙인다
 *   ([readLogicalLine]) — 버퍼에는 진행 중인 논리 행 하나만 남고 파일 전체를 적재하지 않는다.
 * - JSON — Jackson **스트리밍 토큰 API**([JsonParser])로 `issues` 배열까지 토큰 단위로 내려간
 *   뒤, 배열 원소(이슈 1건) 하나만 [ObjectMapper.readTree] 로 부분 트리화해 처리하고 즉시
 *   버린다([parseIssuesArray]). 배열 전체를 `readTree`/`readValue` 로 한 번에 적재하지 않는다.
 *
 * ### 값 정규화
 *
 * 모든 텍스트 값은 [sanitizeControlChars] 로 NUL/제어문자(탭·개행·CR 제외)를 제거한다.
 * Priority 는 [normalizePriorityName] 로 이름(대소문자 무시)·숫자(1..5) 어느 입력이든 정규화된
 * 이름으로 통일하고, 범위 밖/미인식 값은 조용히 무시(null)한다.
 *
 * ### 파일 구조 오류
 *
 * 헤더 행이 없거나 필수 컬럼(Summary)이 없는 CSV, `issues` 배열이 없거나 문법이 깨진 JSON은
 * [ImportParseException] 을 던진다. 개별 행의 값 오류(예: summary 누락)는 예외가 아니라
 * [ParsedImportRow] 의 null 필드로 표현된다 — 행 단위 성공/실패 판정은 다음 단계(Task 8) 책임이다.
 */
@Suppress("TooManyFunctions") // CSV+JSON 스트리밍 파서를 파일 3종(허용 목록) 제약 안에서 한 클래스로 구성
class ImportRowParser {
    // ── CSV ──────────────────────────────────────────────────────────────────

    /**
     * Jira 호환 CSV(RFC 4180)를 파싱해 행마다 [onRow] 를 호출한다.
     *
     * 헤더 행으로 컬럼을 인식한다(대소문자 무시). 완전히 빈 입력이거나 헤더만 있으면 [onRow] 를
     * 한 번도 호출하지 않는다. 헤더에 필수 컬럼(Summary)이 없거나 따옴표가 닫히지 않은 채 파일이
     * 끝나면 [ImportParseException] 을 던진다.
     *
     * @param input CSV 원본 스트림(UTF-8). 닫기는 호출자 책임.
     * @param onRow 파싱된 행 1건을 전달받는 콜백. 데이터 행 순서대로, 1-기준 rowNumber 와 함께 호출된다.
     * @throws ImportParseException 헤더에 Summary 컬럼이 없거나 CSV 구조가 깨졌을 때.
     */
    fun parseCsv(
        input: InputStream,
        onRow: (ParsedImportRow) -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val headerLine = readLogicalLine(reader) ?: return
        val columnIndex = buildColumnIndex(parseCsvLine(headerLine))
        if (columnIndex[HEADER_SUMMARY] == null) {
            throw ImportParseException("CSV 헤더에 필수 컬럼 Summary 가 없습니다")
        }
        var rowNumber = 0
        generateSequence { readLogicalLine(reader) }
            .filterNot { it.isBlank() }
            .forEach { line ->
                rowNumber++
                onRow(buildCsvRow(rowNumber, columnIndex, parseCsvLine(line)))
            }
    }

    /**
     * 논리적 CSV 행(줄) 하나를 읽는다.
     *
     * 물리적 줄에 포함된 큰따옴표 개수가 홀수이면(=닫히지 않은 따옴표 필드) 다음 물리 줄을
     * `\n` 으로 이어붙여 짝수가 될 때까지 반복한다 — 따옴표로 묶인 필드 내부의 개행을 보존하면서도
     * 진행 중인 행 하나만 버퍼링해 파일 전체를 메모리에 올리지 않는다.
     *
     * @return 논리적 행 문자열. 더 읽을 내용이 없으면 null.
     * @throws ImportParseException 따옴표가 닫히지 않은 채 입력이 끝났을 때.
     */
    private fun readLogicalLine(reader: BufferedReader): String? {
        var line = reader.readLine() ?: return null
        while (countQuotes(line) % 2 != 0) {
            val next = reader.readLine() ?: throw ImportParseException("CSV 따옴표가 닫히지 않은 채 파일이 끝났습니다")
            line += "\n$next"
        }
        return line
    }

    private fun countQuotes(text: String): Int = text.count { it == '"' }

    /**
     * 논리적 CSV 행 문자열 하나를 셀 목록으로 분리한다(RFC 4180 역방향 — [CsvExportWriter] 대응).
     *
     * 큰따옴표로 감싼 셀 내부의 쉼표/개행은 구분자로 취급하지 않고, `""` 는 이스케이프된 큰따옴표
     * 1개로 복원한다.
     */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                inQuotes && ch == '"' -> inQuotes = false
                inQuotes -> current.append(ch)
                ch == '"' -> inQuotes = true
                ch == CSV_DELIMITER -> {
                    fields.add(current.toString())
                    current.clear()
                }
                else -> current.append(ch)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }

    /** 헤더 셀 목록을 `trim + lowercase` 키 → 컬럼 위치 맵으로 변환한다(대소문자 무시 인식). */
    private fun buildColumnIndex(headers: List<String>): Map<String, Int> {
        val index = mutableMapOf<String, Int>()
        headers.forEachIndexed { position, header ->
            val key = header.trim().lowercase()
            if (key.isNotEmpty()) index.putIfAbsent(key, position)
        }
        return index
    }

    /** 컬럼 인덱스 + 셀 목록으로 [ParsedImportRow] 1건을 조립한다. */
    private fun buildCsvRow(
        rowNumber: Int,
        columnIndex: Map<String, Int>,
        cells: List<String>,
    ): ParsedImportRow {
        fun cell(header: String): String? {
            val position = columnIndex[header]
            val raw = if (position != null && position < cells.size) cells[position] else null
            return raw?.let { sanitizeControlChars(it).trim().ifEmpty { null } }
        }
        return ParsedImportRow(
            rowNumber = rowNumber,
            summary = cell(HEADER_SUMMARY),
            description = cell(HEADER_DESCRIPTION),
            typeName = cell(HEADER_ISSUE_TYPE),
            priorityName = normalizePriorityName(cell(HEADER_PRIORITY)),
            reporterEmail = cell(HEADER_REPORTER),
            assigneeEmail = cell(HEADER_ASSIGNEE),
            labels = splitMultiValue(cell(HEADER_LABELS)),
            componentNames = splitMultiValue(cell(HEADER_COMPONENT)),
            statusName = cell(HEADER_STATUS),
            fixVersionNames = splitMultiValue(cell(HEADER_FIX_VERSION)),
            affectsVersionNames = splitMultiValue(cell(HEADER_AFFECTS_VERSION)),
        )
    }

    // ── JSON ─────────────────────────────────────────────────────────────────

    /**
     * Jira REST export 호환 JSON(`{ "issues": [ { "fields": {...} } ] }`)을 파싱해
     * 배열 원소(이슈)마다 [onRow] 를 호출한다.
     *
     * `issues` 배열까지 스트리밍 토큰으로 내려간 뒤, 원소 하나씩만 부분 트리화해 처리한다
     * (클래스 KDoc §스트리밍 설계). `issues` 배열이 비어 있으면 [onRow] 를 호출하지 않는다.
     *
     * @param input JSON 원본 스트림(UTF-8). 닫기는 호출자 책임(Jackson 파서 종료 시 함께 닫힘).
     * @param onRow 파싱된 행 1건을 전달받는 콜백. 배열 순서대로 1-기준 rowNumber 와 함께 호출된다.
     * @throws ImportParseException JSON 구문이 깨졌거나 `issues` 배열이 없을 때.
     */
    fun parseJson(
        input: InputStream,
        onRow: (ParsedImportRow) -> Unit,
    ) {
        val parser =
            try {
                JSON_FACTORY.createParser(input)
            } catch (e: IOException) {
                throw ImportParseException("JSON 파서를 생성할 수 없습니다", e)
            }
        try {
            parseJsonIssues(parser, onRow)
        } catch (e: JsonParseException) {
            throw ImportParseException("JSON 파싱에 실패했습니다", e)
        } finally {
            parser.close()
        }
    }

    private fun parseJsonIssues(
        parser: JsonParser,
        onRow: (ParsedImportRow) -> Unit,
    ) {
        if (nextTokenOrThrow(parser) != JsonToken.START_OBJECT) {
            throw ImportParseException("JSON 최상위 요소는 객체여야 합니다")
        }
        var issuesFound = false
        while (nextTokenOrThrow(parser) != JsonToken.END_OBJECT) {
            val fieldName = parser.currentName()
            parser.nextToken()
            if (fieldName == FIELD_ISSUES && parser.currentToken() == JsonToken.START_ARRAY) {
                issuesFound = true
                parseIssuesArray(parser, onRow)
            } else {
                parser.skipChildren()
            }
        }
        if (!issuesFound) throw ImportParseException("JSON 에 issues 배열이 없습니다")
    }

    /** `issues` 배열 원소를 하나씩 부분 트리화해 [onRow] 로 넘긴다(스트리밍 — 배열 전체 미적재). */
    private fun parseIssuesArray(
        parser: JsonParser,
        onRow: (ParsedImportRow) -> Unit,
    ) {
        var rowNumber = 0
        while (nextTokenOrThrow(parser) != JsonToken.END_ARRAY) {
            rowNumber++
            onRow(buildJsonRow(rowNumber, OBJECT_MAPPER.readTree(parser)))
        }
    }

    private fun nextTokenOrThrow(parser: JsonParser): JsonToken {
        return parser.nextToken() ?: throw ImportParseException("JSON 이 예기치 않게 종료되었습니다")
    }

    /** 이슈 1건의 JSON 서브트리에서 `fields` 하위 코어 필드를 추출해 [ParsedImportRow] 를 조립한다. */
    private fun buildJsonRow(
        rowNumber: Int,
        issueNode: JsonNode,
    ): ParsedImportRow {
        val fields = issueNode.path(FIELD_FIELDS)
        return ParsedImportRow(
            rowNumber = rowNumber,
            summary = textOf(fields, FIELD_SUMMARY),
            description = textOf(fields, FIELD_DESCRIPTION),
            typeName = textOf(fields.path(FIELD_ISSUETYPE), FIELD_NAME),
            priorityName = normalizePriorityName(textOf(fields.path(FIELD_PRIORITY), FIELD_NAME)),
            reporterEmail = textOf(fields.path(FIELD_REPORTER), FIELD_EMAIL_ADDRESS),
            assigneeEmail = textOf(fields.path(FIELD_ASSIGNEE), FIELD_EMAIL_ADDRESS),
            labels = textArrayOf(fields.path(FIELD_LABELS)),
            componentNames = textArrayOf(fields.path(FIELD_COMPONENTS), FIELD_NAME),
            statusName = textOf(fields.path(FIELD_STATUS), FIELD_NAME),
            fixVersionNames = textArrayOf(fields.path(FIELD_FIX_VERSIONS), FIELD_NAME),
            affectsVersionNames = textArrayOf(fields.path(FIELD_VERSIONS), FIELD_NAME),
        )
    }

    private fun sanitizeText(node: JsonNode): String? {
        if (!node.isTextual) return null
        return sanitizeControlChars(node.asText()).trim().ifEmpty { null }
    }

    private fun textOf(
        node: JsonNode,
        field: String,
    ): String? = sanitizeText(node.path(field))

    /** JSON 배열 노드를 문자열 목록으로 변환한다. [nameField] 가 주어지면 원소 객체의 해당 필드값을 사용한다. */
    private fun textArrayOf(
        node: JsonNode,
        nameField: String? = null,
    ): List<String> {
        if (!node.isArray) return emptyList()
        return node.mapNotNull { element ->
            if (nameField != null) textOf(element, nameField) else sanitizeText(element)
        }
    }

    // ── 공통: 라벨/컴포넌트 분리, Priority 매핑, 정화 ─────────────────────────────

    /** 콤마 또는 세미콜론으로 값을 분리한다(라벨/컴포넌트 다중값 셀 공통 처리). */
    private fun splitMultiValue(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return raw.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Priority 원본 값(이름 또는 숫자 문자열)을 정규화된 이름으로 변환한다.
     *
     * 숫자 문자열(`"1"`..`"5"`)은 [PRIORITY_NAME_BY_NUMBER] 로 이름을 조회하고, 그 외에는
     * 다섯 이름 중 하나와 대소문자 무시로 비교한다. 범위 밖 숫자 또는 미인식 이름은 null(무시).
     */
    private fun normalizePriorityName(raw: String?): String? {
        if (raw == null) return null
        val byNumber = raw.toIntOrNull()?.let { PRIORITY_NAME_BY_NUMBER[it] }
        return byNumber ?: PRIORITY_NAME_BY_NUMBER.values.find { it.equals(raw, ignoreCase = true) }
    }

    /** NUL 및 제어문자(탭·개행·CR 제외)를 제거한다. 따옴표 안 개행 등 정상 콘텐츠 문자는 보존한다. */
    private fun sanitizeControlChars(raw: String): String =
        raw.filterNot { ch ->
            (ch.code < CONTROL_CHAR_MAX && ch != '\t' && ch != '\n' && ch != '\r') || ch.code == DEL_CHAR
        }

    companion object {
        // CSV 헤더 컬럼 이름(대소문자 무시 매칭 키 — 항상 소문자로 비교).
        private const val HEADER_SUMMARY = "summary"
        private const val HEADER_DESCRIPTION = "description"
        private const val HEADER_ISSUE_TYPE = "issue type"
        private const val HEADER_PRIORITY = "priority"
        private const val HEADER_REPORTER = "reporter"
        private const val HEADER_ASSIGNEE = "assignee"
        private const val HEADER_LABELS = "labels"
        private const val HEADER_COMPONENT = "component"
        private const val HEADER_STATUS = "status"
        private const val HEADER_FIX_VERSION = "fix version"
        private const val HEADER_AFFECTS_VERSION = "affects version"

        private const val CSV_DELIMITER = ','

        // JSON(Jira REST export) 필드 이름.
        private const val FIELD_ISSUES = "issues"
        private const val FIELD_FIELDS = "fields"
        private const val FIELD_SUMMARY = "summary"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_ISSUETYPE = "issuetype"
        private const val FIELD_PRIORITY = "priority"
        private const val FIELD_REPORTER = "reporter"
        private const val FIELD_ASSIGNEE = "assignee"
        private const val FIELD_EMAIL_ADDRESS = "emailAddress"
        private const val FIELD_LABELS = "labels"
        private const val FIELD_COMPONENTS = "components"
        private const val FIELD_STATUS = "status"
        private const val FIELD_FIX_VERSIONS = "fixVersions"
        private const val FIELD_VERSIONS = "versions"
        private const val FIELD_NAME = "name"

        // 정화 대상 제어문자 범위 — ASCII 0x20 미만(단 탭/개행/CR 제외) + DEL(0x7F).
        private const val CONTROL_CHAR_MAX = 0x20
        private const val DEL_CHAR = 0x7F

        /** SDD 05 우선순위 정의(1=Highest .. 5=Lowest) — issue-tracking `IssuePriority` 미러(BC 격리로 직접 참조 불가). */
        private val PRIORITY_NAME_BY_NUMBER =
            mapOf(
                1 to "Highest",
                2 to "High",
                3 to "Medium",
                4 to "Low",
                5 to "Lowest",
            )

        private val JSON_FACTORY = JsonFactory()
        private val OBJECT_MAPPER = ObjectMapper()
    }
}
