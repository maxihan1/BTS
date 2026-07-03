// Jira 호환 CSV/JSON import 파일을 코어 필드 행(ParsedImportRow)으로 변환하는 스트리밍 파서
package com.bts.search.imports.parse

import com.bts.search.imports.mapping.TargetField
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
 * Status 는 원본 문자열을 그대로 담고, Fix/Affects Version 은 라벨/컴포넌트와 동일하게 콤마/세미콜론
 * 다중값을 분리한다 — 상태·버전 이름 자체의 존재 확인(프로젝트 워크플로우/버전 매칭)은 후속 단계 책임이다.
 *
 * 임의 Jira 헤더(`Component/s` 등)·자유 매핑은 FR-IM-02(매핑 UI) 몫 — [parseCsv] 3-인자 오버로드
 * (mapped 모드, §매핑 기반 파싱)로 지원한다.
 *
 * ### 댓글/worklog (PR3)
 *
 * 댓글은 CSV/JSON 모두 지원한다. CSV 는 Jira 가 댓글 N 건을 동명 `Comment` 컬럼 N 개로 export 하므로
 * [commentColumnPositions] 로 전 위치를 수집해 각 셀을 `date;author;body` 로 분해한다([parseCsvCommentCell]).
 * JSON 은 `fields.comment.comments[]` 원소를 [jsonCommentOf] 로 개별 추출한다.
 * worklog 는 **JSON 전용**(`fields.worklog.worklogs[]`, [jsonWorklogOf]) — Jira CSV 는 표준 worklog
 * export 형식이 없어 CSV 행은 항상 `worklogs = emptyList()` 다. 원본 시각 문자열은 이 단계에서
 * [java.time.Instant] 로 변환하지 않는다 — [ParsedImportComment]/[ParsedImportWorklog] KDoc 참조.
 *
 * ### sourceKey/첨부/변경이력 (PR4)
 *
 * 세 항목 모두 **JSON 전용** — Jira CSV export 에는 원본 키·첨부 바이너리·변경이력에 대응하는
 * 표준 컬럼이 없어 CSV 행은 항상 `sourceKey = null`, `attachments`/`changelog = emptyList()` 다.
 * `sourceKey`(원본 이슈 키)와 `changelog`(변경이력)는 `fields` 하위가 아니라 **이슈 노드 최상위**
 * 필드([FIELD_KEY]/[FIELD_CHANGELOG])에서 읽는다 — `fields.attachment[]` 만 다른 코어 필드처럼
 * `fields` 하위다. 첨부는 [jsonAttachmentOf] 로 원소를 개별 추출하고, 변경이력은 그룹([jsonChangeGroupOf])
 * 안에 중첩된 `items[]` 를 [jsonChangeItemOf] 로 한 번 더 개별 추출한다 — 댓글/worklog 와 동일하게
 * `fields.comment.comments[]`/`fields.worklog.worklogs[]` 같은 복합 배열은 [textArrayOf](평면 배열
 * 전용)를 재사용할 수 없다. 첨부 크기([ParsedImportAttachment.sizeBytes])는 [longOrNull] 로 숫자
 * 노드 여부를 먼저 확인한다 — `asLong(0)` 폴백은 "값 없음"과 "0바이트"를 구분하지 못해 미사용.
 * 이미 이슈 단위로 스트리밍되는 [buildJsonRow] 부분 트리([issueNode])에서 읽기만 하므로 추가
 * 스트리밍 복잡도는 없다.
 *
 * ### 매핑 기반 파싱 (FR-IM-02 PR-A, mapped 모드)
 *
 * [parseCsv] 는 2개 오버로드를 제공한다. `fieldMapping` 없이 호출하면(2-인자, canonical) 기존
 * canonical 헤더 이름(`summary`/`description`/... — [CANONICAL_HEADER_BY_TARGET_FIELD])으로
 * 컬럼을 찾는다. `fieldMapping`(소스 헤더 → [TargetField.key], FR-IM-02 매핑 UI 확정값)을 함께
 * 넘기면(3-인자, mapped) 리터럴 헤더 이름과 무관하게 매핑이 지정한 컬럼을 사용한다 — 임의 헤더
 * (예: `"제목"`)를 자유롭게 매핑할 수 있다. 두 경로는 [resolveTargetColumns] 로 공통 처리한다.
 *
 * `fieldMapping` 을 **두 번째 인자의 기본값**(`fieldMapping: Map<String, String>? = null`)으로
 * 두지 않고 별도 오버로드로 분리한 이유 — 기존 호출부(`ImportJobProcessor`)가 이미
 * `parser.parseCsv(input, onRow)` positional 2-인자로 호출 중이라, 중간에 기본인자가 끼어들면
 * `onRow` 람다가 `fieldMapping` 자리에 바인딩되어 컴파일이 깨진다.
 *
 * 필수 컬럼(Summary) 검사는 canonical 리터럴 헤더 존재가 아니라 [resolveTargetColumns] 결과에
 * [TargetField.SUMMARY] 위치가 있는지로 판정한다(두 오버로드 공통) — mapped 모드는 `fieldMapping`
 * 에 summary 매핑이 있으면 리터럴 `summary` 헤더가 없어도 통과한다. mapped 모드에서 사용자가
 * summary 매핑 자체를 누락한 경우의 필수값 검증은 이 파서가 아니라 매핑 확정(confirm) 단계 책임이다.
 *
 * 댓글 다중 `Comment` 컬럼 수집([commentColumnPositions])은 [TargetField] 매핑 대상이 아니다
 * (카탈로그에 댓글 항목이 없다, [TargetField] KDoc) — mapped 모드에서도 canonical 과 동일하게
 * 리터럴 헤더 이름(대소문자 무시)으로 동작한다.
 *
 * [readHeaderAndSample] 은 매핑 UI 진입 전 analyze 단계에서 헤더 + 소량 샘플 값을 보여주기 위한
 * 별도 진입점이다 — [parseCsv] 와 달리 전체 파일을 순회하지 않고 헤더 + 최대 N 개 데이터 행만
 * 읽은 뒤 조기 중단한다.
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
     * Jira 호환 CSV(RFC 4180)를 canonical 헤더 이름으로 파싱해 행마다 [onRow] 를 호출한다.
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
    ) = parseCsvWith(input, fieldMapping = null, onRow)

    /**
     * Jira 호환 CSV(RFC 4180)를 FR-IM-02 매핑 UI 가 확정한 [fieldMapping] 으로 파싱해 행마다
     * [onRow] 를 호출한다(클래스 KDoc §매핑 기반 파싱).
     *
     * 리터럴 canonical 헤더 이름과 무관하게 [fieldMapping] 이 지정한 소스 헤더 → [TargetField] 로
     * 컬럼을 찾는다. 그 외 동작(빈 입력/헤더만 있는 입력 → 0행, 필수 컬럼 부재/따옴표 미종결 →
     * [ImportParseException], 댓글 다중 컬럼 수집)은 [parseCsv] 2-인자 오버로드와 동일하다.
     *
     * @param input CSV 원본 스트림(UTF-8). 닫기는 호출자 책임.
     * @param fieldMapping 소스 헤더 이름 → [TargetField.key] 매핑(`trim + lowercase` 무시 매칭).
     *   [TargetField.fromKey] 로 해석되지 않는 값(예: [TargetField.IGNORE_KEY], 미지 키)은 무시한다.
     * @param onRow 파싱된 행 1건을 전달받는 콜백. 데이터 행 순서대로, 1-기준 rowNumber 와 함께 호출된다.
     * @throws ImportParseException [fieldMapping] 으로 Summary 대상 컬럼을 찾지 못했거나 CSV 구조가
     *   깨졌을 때.
     */
    fun parseCsv(
        input: InputStream,
        fieldMapping: Map<String, String>,
        onRow: (ParsedImportRow) -> Unit,
    ) = parseCsvWith(input, fieldMapping, onRow)

    /** [parseCsv] 두 오버로드가 공유하는 실제 CSV 파싱 로직. [fieldMapping] 이 null 이면 canonical. */
    private fun parseCsvWith(
        input: InputStream,
        fieldMapping: Map<String, String>?,
        onRow: (ParsedImportRow) -> Unit,
    ) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val headerLine = readLogicalLine(reader) ?: return
        val headers = parseCsvLine(headerLine)
        val targetColumns = resolveTargetColumns(headers, fieldMapping)
        if (targetColumns[TargetField.SUMMARY] == null) {
            throw ImportParseException("CSV 헤더에 필수 컬럼 Summary 가 없습니다")
        }
        val commentPositions = commentColumnPositions(headers)
        var rowNumber = 0
        generateSequence { readLogicalLine(reader) }
            .filterNot { it.isBlank() }
            .forEach { line ->
                rowNumber++
                onRow(buildCsvRow(rowNumber, targetColumns, commentPositions, parseCsvLine(line)))
            }
    }

    /**
     * FR-IM-02 analyze 단계 — CSV 헤더 + 최대 [sampleSize] 데이터 행만 읽고 조기 중단한다.
     *
     * 매핑 UI 미리보기용이라 전체 파일을 파싱하지 않는다 — 헤더 행 + [sampleSize] 개 데이터 행을
     * 읽은 직후 스트림을 더 읽지 않고 반환한다. [sampleSize] 이후에 손상된 데이터(예: 따옴표
     * 미종결)가 있어도 도달하지 않으므로 [parseCsv] 와 달리 [ImportParseException] 을 던지지
     * 않는다. 값 정화([sanitizeControlChars])/트림도 하지 않는다 — 원본 그대로 미리보기에 노출한다.
     *
     * @param input CSV 원본 스트림(UTF-8). 닫기는 호출자 책임.
     * @param sampleSize 읽을 최대 데이터 행 수(헤더 제외). 기본 [DEFAULT_SAMPLE_SIZE].
     * @return 헤더 셀 목록 + 샘플 데이터 행(각 행은 셀 목록) 최대 [sampleSize] 건. 빈 입력이면 둘 다 비어있다.
     */
    fun readHeaderAndSample(
        input: InputStream,
        sampleSize: Int = DEFAULT_SAMPLE_SIZE,
    ): HeaderSample {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val emptySample = HeaderSample(headers = emptyList(), sampleRows = emptyList())
        val headerLine = readLogicalLine(reader) ?: return emptySample
        val headers = parseCsvLine(headerLine)
        val sampleRows = mutableListOf<List<String>>()
        while (sampleRows.size < sampleSize) {
            val line = readLogicalLine(reader) ?: break
            if (line.isBlank()) continue
            sampleRows.add(parseCsvLine(line))
        }
        return HeaderSample(headers = headers, sampleRows = sampleRows)
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

    /**
     * 헤더 목록을 [TargetField] 별 컬럼 위치로 해석한다(canonical/mapped 두 경로 공통, 클래스
     * KDoc §매핑 기반 파싱).
     *
     * [fieldMapping] 이 null 이면 canonical 헤더 이름([CANONICAL_HEADER_BY_TARGET_FIELD])으로,
     * 아니면 FR-IM-02 매핑 UI 가 확정한 소스 헤더 → [TargetField.key] 매핑으로 위치를 찾는다.
     */
    private fun resolveTargetColumns(
        headers: List<String>,
        fieldMapping: Map<String, String>?,
    ): Map<TargetField, Int> {
        val columnIndex = buildColumnIndex(headers)
        return if (fieldMapping != null) {
            resolveMappedTargetColumns(columnIndex, fieldMapping)
        } else {
            resolveCanonicalTargetColumns(columnIndex)
        }
    }

    /** canonical 헤더 이름([CANONICAL_HEADER_BY_TARGET_FIELD])으로 [TargetField] 별 위치를 찾는다. */
    private fun resolveCanonicalTargetColumns(columnIndex: Map<String, Int>): Map<TargetField, Int> =
        CANONICAL_HEADER_BY_TARGET_FIELD
            .mapNotNull { (field, header) -> columnIndex[header]?.let { position -> field to position } }
            .toMap()

    /**
     * FR-IM-02 매핑 UI 가 확정한 소스 헤더 → [TargetField.key] 매핑으로 [TargetField] 별 위치를
     * 찾는다. 소스 헤더 비교는 [buildColumnIndex] 와 동일하게 `trim + lowercase` 무시 매칭이다.
     * [TargetField.fromKey] 로 해석되지 않는 값(예: [TargetField.IGNORE_KEY], 미지 키)과 CSV
     * 헤더에 없는 소스 헤더는 조용히 걸러진다.
     */
    private fun resolveMappedTargetColumns(
        columnIndex: Map<String, Int>,
        fieldMapping: Map<String, String>,
    ): Map<TargetField, Int> =
        fieldMapping
            .mapNotNull { (sourceHeader, targetKey) ->
                val targetField = TargetField.fromKey(targetKey) ?: return@mapNotNull null
                val position = columnIndex[sourceHeader.trim().lowercase()] ?: return@mapNotNull null
                targetField to position
            }.toMap()

    /**
     * `comment` 헤더(대소문자 무시)의 **전 위치**를 순서대로 수집한다.
     *
     * Jira CSV 는 댓글 N 건을 동명 `Comment` 컬럼 N 개로 export 하는데, [buildColumnIndex] 는
     * `putIfAbsent` 라 첫 컬럼만 남고 2..N 이 조용히 유실된다(eng-review C2). 댓글만 이 함수로
     * 별도 다중 인덱스를 수집하고, 나머지 필드는 기존 단일 인덱스([buildColumnIndex])를 그대로 쓴다.
     */
    private fun commentColumnPositions(headers: List<String>): List<Int> =
        headers.withIndex()
            .filter { (_, header) -> header.trim().lowercase() == HEADER_COMMENT }
            .map { (position, _) -> position }

    /**
     * 대상 컬럼 위치([resolveTargetColumns]) + 댓글 다중 위치 + 셀 목록으로 [ParsedImportRow] 1건을
     * 조립한다. canonical/mapped 두 경로가 이 함수 하나를 공유한다([TargetField] 로 조회하므로
     * 리터럴 헤더 이름을 몰라도 된다).
     */
    private fun buildCsvRow(
        rowNumber: Int,
        targetColumns: Map<TargetField, Int>,
        commentPositions: List<Int>,
        cells: List<String>,
    ): ParsedImportRow {
        fun cell(field: TargetField): String? {
            val position = targetColumns[field]
            val raw = if (position != null && position < cells.size) cells[position] else null
            return raw?.let { sanitizeControlChars(it).trim().ifEmpty { null } }
        }
        return ParsedImportRow(
            rowNumber = rowNumber,
            summary = cell(TargetField.SUMMARY),
            description = cell(TargetField.DESCRIPTION),
            typeName = cell(TargetField.TYPE),
            priorityName = normalizePriorityName(cell(TargetField.PRIORITY)),
            reporterEmail = cell(TargetField.REPORTER),
            assigneeEmail = cell(TargetField.ASSIGNEE),
            labels = splitMultiValue(cell(TargetField.LABELS)),
            componentNames = splitMultiValue(cell(TargetField.COMPONENT)),
            statusName = cell(TargetField.STATUS),
            fixVersionNames = splitMultiValue(cell(TargetField.FIX_VERSION)),
            affectsVersionNames = splitMultiValue(cell(TargetField.AFFECTS_VERSION)),
            comments = csvCommentsOf(commentPositions, cells),
            // worklog 는 CSV 에서 미지원(Jira 표준 worklog export 형식 없음, JSON 전용).
        )
    }

    /** 댓글 컬럼 위치 목록 + 셀 목록으로 댓글 목록을 조립한다. 빈 셀은 무시한다(PR1 빈 셀 동형). */
    private fun csvCommentsOf(
        commentPositions: List<Int>,
        cells: List<String>,
    ): List<ParsedImportComment> =
        commentPositions.mapNotNull { position ->
            if (position >= cells.size) return@mapNotNull null
            val raw = sanitizeControlChars(cells[position]).trim()
            raw.ifEmpty { null }?.let { parseCsvCommentCell(it) }
        }

    /**
     * 댓글 셀 하나를 `date;author;body` 로 분해한다(세미콜론, [CSV_COMMENT_PART_COUNT] 제한 분할).
     *
     * `split(limit = 3)` 이라 본문 내부의 세미콜론은 분할되지 않고 세 번째 파트에 그대로 남는다.
     * 파트 수가 3 미만(구분자 미준수)이면 전체를 본문으로, 작성자/작성시각은 null 로 폴백한다.
     */
    private fun parseCsvCommentCell(raw: String): ParsedImportComment {
        val parts = raw.split(CSV_COMMENT_DELIMITER, limit = CSV_COMMENT_PART_COUNT)
        if (parts.size < CSV_COMMENT_PART_COUNT) {
            return ParsedImportComment(body = raw, authorEmail = null, createdAt = null)
        }
        return ParsedImportComment(
            body = parts[COMMENT_PART_BODY].trim(),
            authorEmail = parts[COMMENT_PART_AUTHOR].trim().ifEmpty { null },
            createdAt = parts[COMMENT_PART_CREATED].trim().ifEmpty { null },
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
            comments = jsonCommentsOf(fields),
            worklogs = jsonWorklogsOf(fields),
            sourceKey = textOf(issueNode, FIELD_KEY),
            attachments = jsonAttachmentsOf(fields),
            changelog = jsonChangelogOf(issueNode),
        )
    }

    /**
     * `fields.comment.comments[]` 배열을 [ParsedImportComment] 목록으로 변환한다.
     *
     * 원소가 `{ author: { emailAddress }, body, created }` 복합 객체라 [textArrayOf](평면 배열
     * 전용)를 재사용할 수 없다 — 원소마다 [jsonCommentOf] 로 개별 추출한다. `fields.comment` 가
     * 없으면 빈 목록.
     */
    private fun jsonCommentsOf(fields: JsonNode): List<ParsedImportComment> {
        val comments = fields.path(FIELD_COMMENT).path(FIELD_COMMENTS)
        if (!comments.isArray) return emptyList()
        return comments.map { element -> jsonCommentOf(element) }
    }

    /** 댓글 배열 원소 하나에서 작성자 이메일/본문/작성시각을 추출한다. */
    private fun jsonCommentOf(element: JsonNode): ParsedImportComment =
        ParsedImportComment(
            body = textOf(element, FIELD_BODY).orEmpty(),
            authorEmail = textOf(element.path(FIELD_AUTHOR), FIELD_EMAIL_ADDRESS),
            createdAt = textOf(element, FIELD_CREATED),
        )

    /**
     * `fields.worklog.worklogs[]` 배열을 [ParsedImportWorklog] 목록으로 변환한다(JSON 전용 — CSV
     * 는 미지원). `fields.worklog` 가 없으면 빈 목록.
     */
    private fun jsonWorklogsOf(fields: JsonNode): List<ParsedImportWorklog> {
        val worklogs = fields.path(FIELD_WORKLOG).path(FIELD_WORKLOGS)
        if (!worklogs.isArray) return emptyList()
        return worklogs.map { element -> jsonWorklogOf(element) }
    }

    /** worklog 배열 원소 하나에서 작성자 이메일/소요시간/작업시작시각/코멘트를 추출한다. */
    private fun jsonWorklogOf(element: JsonNode): ParsedImportWorklog =
        ParsedImportWorklog(
            timeSpentSeconds = element.path(FIELD_TIME_SPENT_SECONDS).asInt(0),
            startedAt = textOf(element, FIELD_STARTED),
            authorEmail = textOf(element.path(FIELD_AUTHOR), FIELD_EMAIL_ADDRESS),
            comment = textOf(element, FIELD_COMMENT),
        )

    /**
     * `fields.attachment[]` 배열을 [ParsedImportAttachment] 목록으로 변환한다(JSON 전용 — CSV 는
     * 미지원). `fields.attachment` 가 없으면 빈 목록.
     */
    private fun jsonAttachmentsOf(fields: JsonNode): List<ParsedImportAttachment> {
        val attachments = fields.path(FIELD_ATTACHMENT)
        if (!attachments.isArray) return emptyList()
        return attachments.map { element -> jsonAttachmentOf(element) }
    }

    /** 첨부 배열 원소 하나에서 파일명/작성자 이메일/업로드시각/MIME 타입/크기를 추출한다. */
    private fun jsonAttachmentOf(element: JsonNode): ParsedImportAttachment =
        ParsedImportAttachment(
            filename = textOf(element, FIELD_FILENAME).orEmpty(),
            authorEmail = textOf(element.path(FIELD_AUTHOR), FIELD_EMAIL_ADDRESS),
            created = textOf(element, FIELD_CREATED),
            mimeType = textOf(element, FIELD_MIME_TYPE),
            sizeBytes = longOrNull(element, FIELD_SIZE),
        )

    /**
     * `changelog.histories[]` 배열을 [ParsedImportChangeGroup] 목록으로 변환한다(JSON 전용 — CSV
     * 는 미지원). `changelog` 는 `fields` 가 아니라 이슈 노드 최상위에 위치한다. `changelog` 가
     * 없으면 빈 목록.
     */
    private fun jsonChangelogOf(issueNode: JsonNode): List<ParsedImportChangeGroup> {
        val histories = issueNode.path(FIELD_CHANGELOG).path(FIELD_HISTORIES)
        if (!histories.isArray) return emptyList()
        return histories.map { element -> jsonChangeGroupOf(element) }
    }

    /** 변경 이력 배열 원소 하나에서 작성자 이메일/변경시각/중첩 items[] 를 추출한다. */
    private fun jsonChangeGroupOf(element: JsonNode): ParsedImportChangeGroup =
        ParsedImportChangeGroup(
            authorEmail = textOf(element.path(FIELD_AUTHOR), FIELD_EMAIL_ADDRESS),
            created = textOf(element, FIELD_CREATED),
            items = jsonChangeItemsOf(element.path(FIELD_ITEMS)),
        )

    /** `items[]` 배열 노드를 [ParsedImportChangeItem] 목록으로 변환한다. 배열이 아니면 빈 목록. */
    private fun jsonChangeItemsOf(node: JsonNode): List<ParsedImportChangeItem> {
        if (!node.isArray) return emptyList()
        return node.map { element -> jsonChangeItemOf(element) }
    }

    /** 변경 항목 배열 원소 하나에서 필드명/변경 전 값/변경 후 값을 추출한다. */
    private fun jsonChangeItemOf(element: JsonNode): ParsedImportChangeItem =
        ParsedImportChangeItem(
            field = textOf(element, FIELD_ITEM_FIELD).orEmpty(),
            fromValue = textOf(element, FIELD_FROM_STRING),
            toValue = textOf(element, FIELD_TO_STRING),
        )

    /** 숫자 노드면 [Long] 값을, 숫자가 아니거나(누락 포함) 없으면 null 을 반환한다. */
    private fun longOrNull(
        node: JsonNode,
        field: String,
    ): Long? {
        val target = node.path(field)
        return if (target.isNumber) target.asLong() else null
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
        private const val HEADER_COMMENT = "comment"

        /** canonical 모드([resolveCanonicalTargetColumns])가 쓰는 [TargetField] → 헤더 이름 매핑. */
        private val CANONICAL_HEADER_BY_TARGET_FIELD =
            mapOf(
                TargetField.SUMMARY to HEADER_SUMMARY,
                TargetField.DESCRIPTION to HEADER_DESCRIPTION,
                TargetField.TYPE to HEADER_ISSUE_TYPE,
                TargetField.PRIORITY to HEADER_PRIORITY,
                TargetField.REPORTER to HEADER_REPORTER,
                TargetField.ASSIGNEE to HEADER_ASSIGNEE,
                TargetField.LABELS to HEADER_LABELS,
                TargetField.COMPONENT to HEADER_COMPONENT,
                TargetField.STATUS to HEADER_STATUS,
                TargetField.FIX_VERSION to HEADER_FIX_VERSION,
                TargetField.AFFECTS_VERSION to HEADER_AFFECTS_VERSION,
            )

        /** [readHeaderAndSample] 기본 샘플 행 수. */
        private const val DEFAULT_SAMPLE_SIZE = 5

        private const val CSV_DELIMITER = ','

        // 댓글 CSV 셀(`date;author;body`) 분해 — 본문 내부 세미콜론 보존을 위한 limit 분할(PR3).
        private const val CSV_COMMENT_DELIMITER = ';'
        private const val CSV_COMMENT_PART_COUNT = 3
        private const val COMMENT_PART_CREATED = 0
        private const val COMMENT_PART_AUTHOR = 1
        private const val COMMENT_PART_BODY = 2

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

        // JSON 댓글/worklog 중첩 배열 필드 이름(PR3) — `fields.comment.comments[]` / `fields.worklog.worklogs[]`.
        private const val FIELD_COMMENT = "comment"
        private const val FIELD_COMMENTS = "comments"
        private const val FIELD_WORKLOG = "worklog"
        private const val FIELD_WORKLOGS = "worklogs"
        private const val FIELD_BODY = "body"
        private const val FIELD_CREATED = "created"
        private const val FIELD_AUTHOR = "author"
        private const val FIELD_TIME_SPENT_SECONDS = "timeSpentSeconds"
        private const val FIELD_STARTED = "started"

        // JSON sourceKey/첨부/변경이력 필드 이름(PR4) — `issues[].key`, `fields.attachment[]`,
        // `changelog.histories[]`(중첩 `items[]`). CSV 는 세 항목 모두 미지원(JSON 전용).
        private const val FIELD_KEY = "key"
        private const val FIELD_ATTACHMENT = "attachment"
        private const val FIELD_FILENAME = "filename"
        private const val FIELD_MIME_TYPE = "mimeType"
        private const val FIELD_SIZE = "size"
        private const val FIELD_CHANGELOG = "changelog"
        private const val FIELD_HISTORIES = "histories"
        private const val FIELD_ITEMS = "items"
        private const val FIELD_ITEM_FIELD = "field"
        private const val FIELD_FROM_STRING = "fromString"
        private const val FIELD_TO_STRING = "toString"

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

/**
 * [ImportRowParser.readHeaderAndSample] 결과 — FR-IM-02 매핑 UI analyze 단계의 헤더 + 미리보기.
 *
 * @property headers CSV 헤더 셀 목록(원본 순서, `trim`/`lowercase` 미적용 — 매핑 UI에 원본 그대로
 *   노출한다. 대소문자 무시 매칭이 필요한 곳(예: [ImportRowParser.parseCsv] mapped 모드)은 이 값을
 *   그대로 `fieldMapping` 키로 넘겨도 된다).
 * @property sampleRows 헤더 다음 데이터 행 최대 N 건([ImportRowParser.readHeaderAndSample] 의
 *   `sampleSize`). 각 행은 셀 목록(원본 그대로, 정화/트림 미적용).
 */
data class HeaderSample(
    val headers: List<String>,
    val sampleRows: List<List<String>>,
)
