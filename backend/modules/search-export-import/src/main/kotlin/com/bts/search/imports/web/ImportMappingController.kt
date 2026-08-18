// FR-IM-02 Import 매핑 REST 컨트롤러 — analyze/validate/confirm 3 엔드포인트 (PR-A Task 8)

package com.bts.search.imports.web

import com.bts.search.imports.job.application.ImportAnalyzeCommand
import com.bts.search.imports.job.application.ImportJobService
import com.bts.search.imports.job.domain.ImportJobId
import com.bts.search.imports.mapping.ImportMappingService
import com.bts.search.imports.web.dto.ImportAnalysisResponse
import com.bts.search.imports.web.dto.ImportJobResponse
import com.bts.search.imports.web.dto.MappingConfirmRequest
import com.bts.search.imports.web.dto.MappingValidateRequest
import com.bts.search.imports.web.dto.MappingValidationResponse
import com.bts.search.imports.web.dto.UserCollectionResponse
import com.bts.search.imports.web.dto.ValueCollectionResponse
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * FR-IM-02 Import 매핑 REST 컨트롤러.
 *
 * 엔드포인트.
 * - `POST /api/v1/imports/analyze` — CSV/JSON 파일을 분석해 매핑 UI 진입 정보를 200으로 즉시 반환한다.
 * - `POST /api/v1/imports/{jobId}/mapping/validate` — 제안된 필드 매핑을 저장 없이 검증한다.
 * - `POST /api/v1/imports/{jobId}/mapping/users` — 원본에 등장하는 작성자 식별자를 수집하고 BTS 사용자
 *   추천을 계산한다(저장 없이 조회만, FR-IM-02 PR-B).
 * - `POST /api/v1/imports/{jobId}/mapping/values` — 원본에 등장하는 상태/유형/우선순위 이름을 수집하고
 *   BTS 대상 값 추천을 계산한다(저장 없이 조회만, FR-IM-02 PR-C).
 * - `POST /api/v1/imports/{jobId}/mapping` — 필드 매핑 + 사용자 매핑 + 값 매핑을 확정하고 작업을
 *   PENDING으로 전환한다.
 *
 * ### BC 격리 — [ImportController]와 동형 복제
 *
 * [ImportController]와 같은 패키지에 있지만, `currentActorId`/`validateProjectKey`/`sanitizeFilename`
 * 헬퍼를 재사용하지 않고 이 클래스 안에 동형 복제한다 — private 메서드라 물리적으로도 재사용이 불가능할
 * 뿐 아니라, 컨트롤러 간 헬퍼 공유를 지양하는 관례를 따른다([com.bts.search.web.ExportController]/
 * [com.bts.search.web.SearchController]도 각자 자체 구현). [ImportValidationException]은 이미 같은
 * 패키지의 공개 클래스([ImportController.kt])라 재정의하지 않고 그대로 재사용한다.
 *
 * ### actor 추출 우선
 *
 * 세 엔드포인트 모두 [currentActorId]를 서비스 호출보다 먼저 호출해 미인증자의 존재 probe를 차단한다
 * (교훈 auth-extraction-before-resource-lookup). [ImportMappingService]의 소유확인(타인 job 404)은
 * actor 추출이 끝난 뒤에만 실행된다.
 *
 * ### 예외 처리
 *
 * [ImportExceptionHandler]가 `assignableTypes`에 이 클래스를 포함해([ImportExceptionHandler] KDoc
 * 참조) 이 컨트롤러에서 발생하는 모든 예외를 RFC 7807 [org.springframework.http.ProblemDetail]로 변환한다.
 *
 * @param importJobService 분석(analyze) 유스케이스 서비스.
 * @param importMappingService 필드 매핑 검증/확정(validate/confirm) 유스케이스 서비스.
 */
@RestController
class ImportMappingController(
    private val importJobService: ImportJobService,
    private val importMappingService: ImportMappingService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Import 파일을 분석해 매핑 UI 진입에 필요한 정보를 반환한다.
     *
     * 비동기 워커를 거치지 않는 동기 완결 흐름이라 202가 아닌 200으로 분석 결과 본문을 즉시 반환한다
     * ([ImportJobService.analyze] KDoc §분석 흐름 참조). 권한 fail-fast·파일 크기·format 검증은
     * [ImportJobService.analyze] 내부에서 [ImportController.accept]와 동일한 로직으로 수행된다.
     *
     * @param file 업로드할 CSV/JSON 파일. `name="file"` part 필수.
     * @param projectKey Import 대상 프로젝트 키.
     * @param format 파일 형식 문자열(`"CSV"` 또는 `"JSON"`, 대소문자 무관).
     * @return 200 OK + [ImportAnalysisResponse].
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(400) projectKey 공백 시.
     * @throws ImportValidationException(400) projectKey 패턴 위반 시(헤더 인젝션 방어).
     * @throws com.bts.search.imports.job.application.ImportAccessDeniedException CREATE_ISSUE 권한 없을 때 → 403.
     * @throws com.bts.search.imports.job.application.ImportFileTooLargeException 파일 크기 초과 → 413.
     * @throws com.bts.search.imports.job.application.ImportUnsupportedFormatException 미지원 format → 400.
     */
    @PostMapping("/api/v1/imports/analyze")
    fun analyze(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("projectKey") projectKey: String,
        @RequestParam("format") format: String,
    ): ResponseEntity<ImportAnalysisResponse> {
        val actorId = currentActorId()
        validateProjectKey(projectKey)
        log.info(
            "import_analyze_request projectKey={} format={} filename={} actor={}",
            projectKey,
            format,
            sanitizeFilename(file.originalFilename ?: file.name),
            actorId,
        )

        val result =
            importJobService.analyze(
                ImportAnalyzeCommand(
                    projectKey = projectKey,
                    format = format,
                    filename = file.originalFilename ?: file.name,
                    contentType = file.contentType,
                    sizeBytes = file.size,
                    inputStream = file.inputStream,
                    requesterUserId = actorId,
                ),
            )

        return ResponseEntity.ok(ImportAnalysisResponse.from(result))
    }

    /**
     * 제안된 필드 매핑을 저장 없이 검증한다.
     *
     * @param jobId 검증 대상 Import 작업 식별자(경로 변수).
     * @param request 검증할 필드 매핑 목록.
     * @return 200 OK + [MappingValidationResponse].
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(404) 작업이 없거나 타인 소유인 경우(존재 은닉).
     * @throws com.bts.search.imports.mapping.ImportMappingStateConflictException 작업 상태가
     *   AWAITING_MAPPING이 아닌 경우 → 409.
     */
    @PostMapping("/api/v1/imports/{jobId}/mapping/validate")
    fun validateMapping(
        @PathVariable jobId: UUID,
        @Valid @RequestBody request: MappingValidateRequest,
    ): ResponseEntity<MappingValidationResponse> {
        val actorId = currentActorId()
        val result = importMappingService.validate(ImportJobId(jobId), actorId, request.toFieldMappingsMap())
        return ResponseEntity.ok(MappingValidationResponse.from(result))
    }

    /**
     * Import 원본을 전량 스캔해 등장하는 작성자 식별자를 수집하고, BTS 사용자 추천을 계산한다.
     *
     * 저장 없이 조회만 수행한다([com.bts.search.imports.mapping.ImportMappingService.collectUsers]
     * KDoc 참조). 요청 바디는 [validateMapping]과 동일한 [MappingValidateRequest]를 재사용한다 —
     * 필드 매핑 검증(CSV 한정)에 그대로 필요한 계약이기 때문이다.
     *
     * @param jobId 대상 Import 작업 식별자(경로 변수).
     * @param request 필드 매핑 목록(CSV 전량 스캔 전 선검증에 사용, JSON은 스킵).
     * @return 200 OK + [UserCollectionResponse].
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(404) 작업이 없거나 타인 소유인 경우(존재 은닉).
     * @throws com.bts.search.imports.mapping.ImportMappingStateConflictException 작업 상태가
     *   AWAITING_MAPPING이 아닌 경우 → 409.
     * @throws com.bts.search.imports.mapping.ImportMappingInvalidException CSV 필드 매핑 검증 실패 → 422.
     */
    @PostMapping("/api/v1/imports/{jobId}/mapping/users")
    fun collectUsers(
        @PathVariable jobId: UUID,
        @Valid @RequestBody request: MappingValidateRequest,
    ): ResponseEntity<UserCollectionResponse> {
        val actorId = currentActorId()
        val result = importMappingService.collectUsers(ImportJobId(jobId), actorId, request.toFieldMappingsMap())
        return ResponseEntity.ok(UserCollectionResponse.from(result))
    }

    /**
     * Import 원본을 전량 스캔해 등장하는 상태/유형/우선순위 이름을 수집하고, BTS 대상 값 추천을 계산한다.
     *
     * 저장 없이 조회만 수행한다([com.bts.search.imports.mapping.ImportMappingService.collectValues]
     * KDoc 참조). 요청 바디는 [validateMapping]/[collectUsers]와 동일한 [MappingValidateRequest]를
     * 재사용한다 — 필드 매핑 검증(CSV 한정)에 그대로 필요한 계약이기 때문이다.
     *
     * @param jobId 대상 Import 작업 식별자(경로 변수).
     * @param request 필드 매핑 목록(CSV 전량 스캔 전 선검증에 사용, JSON은 스킵).
     * @return 200 OK + [ValueCollectionResponse].
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(404) 작업이 없거나 타인 소유인 경우(존재 은닉).
     * @throws com.bts.search.imports.mapping.ImportMappingStateConflictException 작업 상태가
     *   AWAITING_MAPPING이 아닌 경우 → 409.
     * @throws com.bts.search.imports.mapping.ImportMappingInvalidException CSV 필드 매핑 검증 실패 → 422.
     */
    @PostMapping("/api/v1/imports/{jobId}/mapping/values")
    fun collectValues(
        @PathVariable jobId: UUID,
        @Valid @RequestBody request: MappingValidateRequest,
    ): ResponseEntity<ValueCollectionResponse> {
        val actorId = currentActorId()
        val result = importMappingService.collectValues(ImportJobId(jobId), actorId, request.toFieldMappingsMap())
        return ResponseEntity.ok(ValueCollectionResponse.from(result))
    }

    /**
     * 필드 매핑 + 사용자 매핑 + 값 매핑을 검증한 뒤 확정하고, 작업을 PENDING으로 전환해 실행 큐에
     * enqueue한다.
     *
     * @param jobId 확정 대상 Import 작업 식별자(경로 변수).
     * @param request 확정할 필드 매핑 목록 + optional dryRun + optional 사용자 매핑 목록 + optional
     *   값 매핑 목록.
     * @return 200 OK + [ImportJobResponse](status="PENDING"). 이후 상태 폴링은 기존 `GET /{jobId}` 경로를 사용한다.
     * @throws ResponseStatusException(401) 미인증 시.
     * @throws ResponseStatusException(400) [request]의 값 매핑 `targetField`가
     *   [com.bts.search.imports.mapping.ValueTargetField]의 유효한 상수명이 아닌 경우(서비스 미호출).
     * @throws ResponseStatusException(404) 작업이 없거나 타인 소유인 경우(존재 은닉).
     * @throws com.bts.search.imports.mapping.ImportMappingInvalidException 필드 매핑 검증 실패 → 422.
     * @throws com.bts.search.imports.mapping.ImportUserMappingInvalidException 사용자 매핑 검증 실패 → 422.
     * @throws com.bts.search.imports.mapping.ImportValueMappingInvalidException 값 매핑 검증 실패 → 422.
     * @throws com.bts.search.imports.mapping.ImportMappingStateConflictException 작업 상태가
     *   사전확인 또는 CAS 시점(TOCTOU 포함)에 AWAITING_MAPPING이 아닌 경우 → 409.
     */
    @PostMapping("/api/v1/imports/{jobId}/mapping")
    fun confirmMapping(
        @PathVariable jobId: UUID,
        @Valid @RequestBody request: MappingConfirmRequest,
    ): ResponseEntity<ImportJobResponse> {
        val actorId = currentActorId()
        val job =
            importMappingService.confirm(
                ImportJobId(jobId),
                actorId,
                request.toFieldMappingsMap(),
                request.dryRun,
                request.toUserMappingPairs(),
                request.toValueMappingTriples(),
            )
        return ResponseEntity.ok(ImportJobResponse.from(job))
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * projectKey를 검증한다. 공백이면 400, 허용 패턴([PROJECT_KEY_PATTERN])을 벗어나면
     * [ImportValidationException](400)을 던진다. [ImportController.validateProjectKey]와 동형이다.
     *
     * @param projectKey 검증할 요청 프로젝트 키.
     * @throws ResponseStatusException(400) 공백인 경우.
     * @throws ImportValidationException 허용 패턴을 벗어난 경우.
     */
    private fun validateProjectKey(projectKey: String) {
        if (projectKey.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "projectKey는 필수입니다.")
        }
        if (!PROJECT_KEY_PATTERN.matches(projectKey)) {
            throw ImportValidationException("projectKey는 영문자·숫자·하이픈만 허용됩니다.")
        }
    }

    /**
     * [SecurityContextHolder]에서 인증 주체를 UUID로 추출한다. [ImportController.currentActorId]와 동형이다.
     *
     * 미인증·익명·비-UUID·nil-UUID 주체는 401 [ResponseStatusException]을 던진다.
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

    /**
     * 로그 인젝션 방어 — 파일명의 제어 문자(CRLF 포함)를 제거한다.
     * [ImportController.sanitizeFilename]과 동형이다(원래는 Content-Disposition 헤더용이지만,
     * 이 컨트롤러는 로그 라인에 파일명을 그대로 남기므로 동일 정화 함수를 적용한다).
     *
     * @param raw 원본 파일명 문자열.
     * @return 제어 문자가 제거된 안전한 파일명.
     */
    private fun sanitizeFilename(raw: String): String = raw.replace(Regex("[\\x00-\\x1F\\x7F]"), "_")

    companion object {
        /**
         * 허용 projectKey 패턴 — 영문자·숫자·하이픈만 허용.
         * [ImportController.PROJECT_KEY_PATTERN]과 동형 — CRLF 헤더 인젝션·경로 조작 차단.
         */
        private val PROJECT_KEY_PATTERN = Regex("[A-Za-z0-9\\-]+")
    }
}
