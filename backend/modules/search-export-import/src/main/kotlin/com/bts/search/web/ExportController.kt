// 이슈 Export REST 컨트롤러 — POST /api/v1/search/export (FR-EX-01 Task 5)

package com.bts.search.web

import com.bts.search.export.ExportColumn
import com.bts.search.export.ExportFormat
import com.bts.search.export.ExportService
import com.bts.search.web.dto.ExportRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 이슈 Export REST 컨트롤러.
 *
 * 엔드포인트.
 * - `POST /api/v1/search/export` — AQL 쿼리 결과를 CSV 또는 XLSX 파일로 다운로드한다.
 *
 * ### 처리 흐름
 *
 * 1. actor 추출 — [SecurityContextHolder]에서 인증 주체를 UUID로 변환한다.
 *    미인증·익명·비-UUID 주체는 401을 던진다(리소스 조회보다 먼저 수행하여 probe 차단).
 * 2. 요청 검증 — [validateRequest]로 projectKey/query를 수동 검증한다.
 * 3. format 파싱 — [parseFormat]으로 문자열을 [ExportFormat]으로 변환한다.
 *    유효하지 않은 format(예: "PDF")은 [SearchValidationException]을 던진다.
 * 4. columns 파싱 — [ExportColumn.parse]로 이름 목록을 컬럼 목록으로 변환한다.
 *    미지원 컬럼 이름은 [SearchValidationException]을 던진다.
 * 5. Export 위임 — [ExportService.export]에 파싱된 파라미터를 전달한다.
 * 6. 응답 — [ResponseEntity] ByteArray + Content-Disposition + 동적 contentType.
 *
 * ### 보안
 *
 * - projectKey 패턴 검증([PROJECT_KEY_PATTERN])으로 Content-Disposition 헤더 인젝션 차단.
 * - visibility 보안 술어 및 BROWSE 권한 게이트는 [ExportService] → [com.bts.shared.search.IssueSearchPort] 구현체가 담당한다.
 *
 * ### BC 격리
 *
 * issue-tracking 내부 패키지를 직접 import하지 않는다. [com.bts.shared.search.IssueSearchPort](shared-kernel)만 참조한다.
 * actor 추출도 [com.bts.issue.adapter.inbound.rest.CurrentActor]를 재사용하지 않고
 * 이 모듈에서 직접 [SecurityContextHolder]를 통해 추출한다.
 *
 * @param exportService Export 오케스트레이터 서비스.
 */
@RestController
class ExportController(
    private val exportService: ExportService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * AQL 쿼리 결과를 CSV 또는 XLSX 파일로 다운로드한다.
     *
     * @param request Export 요청 바디. [ExportRequest] Jakarta Validation 적용.
     * @return 200 OK + 파일 바이트 + Content-Disposition attachment + 동적 Content-Type.
     * @throws ResponseStatusException(401) 미인증 — actor 추출 실패 시.
     * @throws SearchValidationException(400) 검증 실패 — [ExportExceptionHandler]가 400으로 변환.
     * @throws com.bts.search.aql.AqlSyntaxException(400) AQL 오류 — [ExportExceptionHandler]가 400으로 변환.
     * @throws com.bts.search.export.ExportLimitExceededException(400) 상한 초과 — [ExportExceptionHandler]가 400으로 변환.
     * @throws SecurityException(403) BROWSE 권한 없음 — [ExportExceptionHandler]가 403으로 변환.
     */
    @PostMapping("/api/v1/search/export")
    fun export(
        @Valid @RequestBody request: ExportRequest,
    ): ResponseEntity<ByteArray> {
        val actorId = currentActorId()
        log.info(
            "export projectKey={} format={} cols={} actor={}",
            request.projectKey,
            request.format,
            request.columns?.size,
            actorId,
        )

        validateRequest(request)
        val format = parseFormat(request.format)
        val columns = ExportColumn.parse(request.columns)

        val result =
            exportService.export(
                projectKey = request.projectKey,
                query = request.query,
                format = format,
                columns = columns,
                viewerUserId = actorId,
            )
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, buildContentDisposition(result.filename))
            .contentType(MediaType.parseMediaType(result.contentType))
            .body(result.bytes)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [ExportRequest] 필드를 명시적으로 검증한다.
     *
     * projectKey 패턴 검증은 Content-Disposition 헤더 인젝션 방어의 핵심이다.
     * 영문자·숫자·하이픈만 허용하여 CRLF 등 특수문자를 차단한다.
     *
     * @param request 검증할 요청 DTO.
     * @throws ResponseStatusException(400) blank 필드 검증 실패 시.
     * @throws SearchValidationException(400) 패턴·길이 검증 실패 시.
     */
    @Suppress("ThrowsCount")
    private fun validateRequest(request: ExportRequest) {
        if (request.projectKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectKey는 필수입니다.")
        }
        if (!PROJECT_KEY_PATTERN.matches(request.projectKey)) {
            throw SearchValidationException("projectKey는 영문자·숫자·하이픈만 허용됩니다.")
        }
        if (request.query.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "query는 필수입니다.")
        }
        if (request.query.length > MAX_QUERY_LENGTH) {
            throw SearchValidationException("query는 최대 ${MAX_QUERY_LENGTH}자까지 허용됩니다.")
        }
    }

    /**
     * format 문자열을 [ExportFormat]으로 파싱한다.
     *
     * null이면 [ExportFormat.CSV]를 기본값으로 반환한다.
     * 인식할 수 없는 문자열이면 [SearchValidationException]을 던진다.
     *
     * @param formatStr 요청 바디의 format 필드 값. null이면 CSV 기본값.
     * @return 파싱된 [ExportFormat].
     * @throws SearchValidationException 지원하지 않는 format 문자열인 경우.
     */
    private fun parseFormat(formatStr: String?): ExportFormat {
        if (formatStr == null) return ExportFormat.CSV
        return ExportFormat.entries.firstOrNull { it.name == formatStr.uppercase() }
            ?: throw SearchValidationException("format은 CSV 또는 XLSX여야 합니다.")
    }

    /**
     * Content-Disposition attachment 헤더 값을 생성한다.
     *
     * 파일명은 [ExportService]가 생성하며 영문자·숫자·하이픈·점만 포함하므로 헤더 인젝션 위험이 없다.
     * projectKey는 [PROJECT_KEY_PATTERN]으로 사전 검증되어 있다.
     *
     * @param filename 다운로드 파일명.
     * @return Content-Disposition 헤더 문자열.
     */
    private fun buildContentDisposition(filename: String): String = "attachment; filename=\"$filename\""

    /**
     * [SecurityContextHolder]에서 인증 주체를 UUID로 추출한다.
     *
     * 미인증·익명·비-UUID·nil-UUID 주체는 401 [ResponseStatusException]을 던진다.
     * issue-tracking [com.bts.issue.adapter.inbound.rest.CurrentActor]와 동일 로직이지만
     * BC 격리 원칙상 import할 수 없어 이 모듈에서 자체 구현한다.
     *
     * @return 인증된 사용자의 UUID.
     * @throws ResponseStatusException(401) 미인증 또는 UUID 변환 실패 시.
     */
    private fun currentActorId(): UUID {
        val authentication: Authentication =
            SecurityContextHolder.getContext().authentication
                ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        return try {
            val uuid = UUID.fromString(authentication.name)
            require(uuid != UUID(0L, 0L)) { "nil UUID는 actor로 허용되지 않습니다." }
            uuid
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", e)
        }
    }

    companion object {
        /** 쿼리 문자열 최대 길이 — DoS 방어(NFR-2). */
        private const val MAX_QUERY_LENGTH = 2000

        /**
         * projectKey 허용 패턴 — 영문자·숫자·하이픈만 허용.
         *
         * CRLF·공백·특수문자를 차단하여 Content-Disposition 헤더 인젝션을 방어한다.
         */
        private val PROJECT_KEY_PATTERN = Regex("[A-Za-z0-9\\-]+")
    }
}
