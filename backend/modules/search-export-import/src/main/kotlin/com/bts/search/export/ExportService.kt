// 이슈 검색 결과 Export 오케스트레이터 — count-first 페이지 순회 + CSV/XLSX 디스패치

package com.bts.search.export

import com.bts.search.aql.AqlLexer
import com.bts.search.aql.AqlParseResult
import com.bts.search.aql.AqlParser
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 이슈 검색 결과 Export 오케스트레이터.
 *
 * AQL 쿼리를 파싱한 뒤 [IssueSearchPort]를 페이지 단위로 순회해 전체 결과를 수집하고,
 * 요청된 형식([ExportFormat])으로 직렬화해 [ExportResult]를 반환한다.
 *
 * ### count-first 흐름
 *
 * 1. 첫 페이지(page=0)를 조회해 [IssueSearchPage.total][com.bts.shared.search.IssueSearchPage.total]을 확인한다.
 * 2. total > [MAX_ROWS]이면 추가 조회 없이 즉시 [ExportLimitExceededException]을 던진다.
 * 3. total ≤ [MAX_ROWS]이면 첫 페이지 포함 전체 페이지를 순회해 모든 이슈를 수집한다.
 * 4. 수집 결과를 [CsvExportWriter] 또는 [XlsxExportWriter]로 직렬화한다.
 *
 * ### @Transactional 의도적 생략
 *
 * 이 서비스는 직접 DB에 접근하지 않는다. 모든 데이터 접근은 [IssueSearchPort] 구현체가 담당하며
 * 구현체가 자체 트랜잭션 경계를 관리한다. 일관성은 count-first best-effort 스냅샷 방식이다.
 * (ADR §C6). `@Transactional` 누락은 codereview rule 9의 false-positive이며 의도적 생략이다.
 *
 * @param searchPort AQL 검색 포트. issue-tracking 어댑터가 런타임에 주입된다.
 * @param clock 파일명 timestamp 결정을 위한 Clock. 테스트에서 고정 인스턴스를 주입한다.
 */
@Service
class ExportService(
    private val searchPort: IssueSearchPort,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val csvWriter = CsvExportWriter()
    private val xlsxWriter = XlsxExportWriter()

    /**
     * AQL 쿼리를 실행해 이슈 목록을 지정 형식으로 Export한다.
     *
     * actor 추출 및 DTO 검증은 ExportController가 담당한다.
     * 이 메서드는 파싱·조회·직렬화에만 집중한다.
     *
     * @param projectKey 검색 대상 프로젝트 키.
     * @param query AQL 쿼리 문자열. 파싱 실패 시 [com.bts.search.aql.AqlSyntaxException]을 전파한다.
     * @param format CSV 또는 XLSX.
     * @param columns 출력할 컬럼 목록. [ExportColumn.parse]로 조립된 값을 전달한다.
     * @param viewerUserId 검색 요청자 UUID. visibility 필터 기준으로 [IssueSearchPort]에 전달된다.
     * @return [ExportResult] — 파일명, Content-Type, 직렬화된 바이트.
     * @throws com.bts.search.aql.AqlSyntaxException AQL 문법/필드 오류 — 호출자가 400으로 변환한다.
     * @throws ExportLimitExceededException total > [MAX_ROWS] — 호출자가 400으로 변환한다.
     */
    fun export(
        projectKey: String,
        query: String,
        format: ExportFormat,
        columns: List<ExportColumn>,
        viewerUserId: UUID,
    ): ExportResult {
        log.info(
            "Export 시작: projectKey={}, format={}, columns={}",
            projectKey,
            format,
            columns.size,
        )

        // AQL 파싱 — AqlSyntaxException은 잡지 않고 그대로 전파한다.
        val tokens = AqlLexer(query).tokenize()
        val parseResult = AqlParser(tokens).parse()

        // count-first: 첫 페이지를 조회해 총 건수를 확인한다. 상한 초과 시 즉시 거부.
        val firstPage = searchPort.search(buildQuery(projectKey, parseResult, viewerUserId, 0))

        if (firstPage.total > MAX_ROWS) {
            log.warn("Export 상한 초과: total={}, limit={}", firstPage.total, MAX_ROWS)
            throw ExportLimitExceededException(resultCount = firstPage.total, limit = MAX_ROWS)
        }

        // 전체 페이지 순회 — 첫 페이지 포함.
        val allHits = collectAllHits(projectKey, parseResult, viewerUserId, firstPage)

        val bytes = writeBytes(format, columns, allHits)
        val filename = buildFilename(projectKey, format)

        log.info("Export 완료: filename={}, rows={}, bytes={}", filename, allHits.size, bytes.size)
        return ExportResult(filename = filename, contentType = format.contentType, bytes = bytes)
    }

    /**
     * 첫 페이지 포함 전체 페이지를 순회해 이슈 목록을 수집한다.
     *
     * total=0이면 빈 목록을 반환한다(헤더만 있는 파일).
     * 각 페이지 크기는 [PAGE_SIZE]로 고정된다.
     */
    private fun collectAllHits(
        projectKey: String,
        parseResult: AqlParseResult,
        viewerUserId: UUID,
        firstPage: com.bts.shared.search.IssueSearchPage,
    ): List<IssueSearchHit> {
        val allHits = mutableListOf<IssueSearchHit>()
        allHits.addAll(firstPage.items)

        val totalPages = ((firstPage.total + PAGE_SIZE - 1) / PAGE_SIZE).toInt()
        for (pageNum in 1 until totalPages) {
            val page = searchPort.search(buildQuery(projectKey, parseResult, viewerUserId, pageNum))
            allHits.addAll(page.items)
        }

        return allHits
    }

    /**
     * [IssueSearchQuery]를 조립한다.
     *
     * page 외 모든 파라미터는 동일하게 유지되어 전체 순회에서 동일한 AST·sort를 재사용한다.
     */
    private fun buildQuery(
        projectKey: String,
        parseResult: AqlParseResult,
        viewerUserId: UUID,
        page: Int,
    ): IssueSearchQuery =
        IssueSearchQuery(
            projectKey = projectKey,
            ast = parseResult.ast,
            sort = parseResult.sort,
            viewerUserId = viewerUserId,
            page = page,
            size = PAGE_SIZE,
        )

    /**
     * format에 따라 적절한 writer를 호출해 이슈 목록을 바이트로 직렬화한다.
     */
    private fun writeBytes(
        format: ExportFormat,
        columns: List<ExportColumn>,
        hits: List<IssueSearchHit>,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        when (format) {
            ExportFormat.CSV -> csvWriter.write(out, columns, hits)
            ExportFormat.XLSX -> xlsxWriter.write(out, columns, hits)
        }
        return out.toByteArray()
    }

    /**
     * 다운로드 파일명을 생성한다.
     *
     * 형식: `{projectKey}-issues-{yyyyMMdd-HHmmss}.{ext}`
     * timestamp는 [clock]의 현재 시각을 UTC 기준으로 포맷한다.
     */
    private fun buildFilename(
        projectKey: String,
        format: ExportFormat,
    ): String {
        val timestamp =
            LocalDateTime
                .ofInstant(clock.instant(), ZoneOffset.UTC)
                .format(FILENAME_TIMESTAMP_FORMATTER)
        return "$projectKey-issues-$timestamp.${format.fileExtension}"
    }

    companion object {
        /** 동기 Export 행 상한. 초과 시 [ExportLimitExceededException]을 던진다. */
        const val MAX_ROWS = 10_000L

        /** [IssueSearchPort] 페이지 순회 단위 크기. */
        const val PAGE_SIZE = 100

        /** 파일명 timestamp 포맷 — 예: `"20240315-103045"`. */
        private val FILENAME_TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
