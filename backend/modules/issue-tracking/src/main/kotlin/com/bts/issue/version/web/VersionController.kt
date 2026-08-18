// 버전 CRUD REST 컨트롤러 — POST/GET/PATCH/DELETE + 상태 전환 (FR-VR-01 Task 7 + FR-VR-02)

package com.bts.issue.version.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.config.BEARER_AUTH_SCHEME
import com.bts.issue.version.application.VersionApplicationService
import com.bts.issue.version.web.dto.ChangeVersionDatesRequest
import com.bts.issue.version.web.dto.ChangeVersionStatusRequest
import com.bts.issue.version.web.dto.CreateVersionRequest
import com.bts.issue.version.web.dto.UpdateVersionRequest
import com.bts.issue.version.web.dto.VersionResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** actorId placeholder — FR-PM-03 실 추출 이연. SecurityConfig 가 401 을 보장한다. */
private val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

/**
 * 버전 REST API 컨트롤러.
 *
 * 엔드포인트 목록 — 모두 `/api/v1/projects/{projectIdOrKey}/versions` 하위.
 * - POST                    — 버전 생성 → 201
 * - GET                     — 활성 버전 목록 (name 오름차순) → 200
 * - GET  /{id}              — 단건 조회 → 200
 * - PATCH /{id}             — name/description 수정 → 200
 * - PATCH /{id}/dates       — 날짜 지정 / 해제 (2-state each) → 200
 * - PATCH /{id}/status      — 상태 전환 (UNRELEASED/RELEASED/ARCHIVED) → 200
 * - DELETE /{id}            — 소프트 삭제 → 204
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 모든 트랜잭션은 [VersionApplicationService] 의 `@Transactional` 이 담당한다.
 *
 * ### actorId
 * FR-PM-03 이전까지 [SYSTEM_ACTOR_UUID] 를 placeholder 로 사용한다.
 * Security 필터가 인증 없는 요청에 401 을 보장하므로 서비스 레이어까지 도달하는
 * 요청은 인증된 사용자임이 보장된다.
 *
 * @param service 버전 CRUD Application Service.
 */
@Tag(name = "Versions", description = "프로젝트 버전 CRUD 및 상태 전환 API (FR-VR-01/02)")
@RestController
@RequestMapping("/api/v1/projects/{projectIdOrKey}/versions")
class VersionController(
    private val service: VersionApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 버전을 생성한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param request 버전 생성 요청 바디 (Jakarta Validation 적용).
     * @return 201 Created + [VersionResponse] body.
     * @throws com.bts.issue.version.domain.VersionProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.version.domain.DuplicateVersionNameException 이름 중복 → 409
     */
    @Operation(operationId = "createVersion", summary = "버전 생성")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "프로젝트 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "버전명 중복", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PostMapping
    fun create(
        @PathVariable projectIdOrKey: String,
        @Valid @RequestBody request: CreateVersionRequest,
    ): ResponseEntity<DataResponse<VersionResponse>> {
        log.info("VersionController.create projectIdOrKey={} name={}", projectIdOrKey, request.name)
        val version =
            service.create(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                name = request.name,
                description = request.description,
                startDate = request.startDate,
                releaseDate = request.releaseDate,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse(data = VersionResponse.from(version)))
    }

    /**
     * 프로젝트 소속 활성 버전 목록을 반환한다.
     *
     * `deleted_at IS NULL` 인 버전만 포함되며, name 오름차순으로 정렬된다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @return 200 OK + `{ "data": [ ... ] }`.
     * @throws com.bts.issue.version.domain.VersionProjectNotFoundException 프로젝트 미존재 → 404
     */
    @Operation(operationId = "listVersions", summary = "버전 목록 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "버전 목록 (name 오름차순)"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping
    fun list(
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<DataResponse<List<VersionResponse>>> {
        log.debug("VersionController.list projectIdOrKey={}", projectIdOrKey)
        val versions =
            service.listByProject(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
            ).map(VersionResponse::from)
        return ResponseEntity.ok(DataResponse(data = versions))
    }

    /**
     * 단건 버전을 조회한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param id path variable 버전 UUID.
     * @return 200 OK + [VersionResponse] body.
     * @throws com.bts.issue.version.domain.VersionProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.version.domain.VersionNotFoundException 버전 미존재 → 404
     */
    @Operation(operationId = "getVersionById", summary = "버전 단건 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "버전"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "버전 또는 프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/{id}")
    fun getById(
        @PathVariable projectIdOrKey: String,
        @PathVariable id: UUID,
    ): ResponseEntity<DataResponse<VersionResponse>> {
        log.debug("VersionController.getById projectIdOrKey={} id={}", projectIdOrKey, id)
        val version =
            service.getById(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                versionId = id,
            )
        return ResponseEntity.ok(DataResponse(data = VersionResponse.from(version)))
    }

    /**
     * 버전의 name / description 을 수정한다.
     *
     * null / 생략 = 무변경 (sentinel 정책). 날짜 변경은 [changeDates] 전용 엔드포인트를 사용한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param id path variable 버전 UUID.
     * @param request 수정 요청 바디.
     * @return 200 OK + 수정된 [VersionResponse] body.
     * @throws com.bts.issue.version.domain.VersionProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.version.domain.VersionNotFoundException 버전 미존재 → 404
     */
    @Operation(operationId = "updateVersion", summary = "버전 수정")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "버전 또는 프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PatchMapping("/{id}")
    fun update(
        @PathVariable projectIdOrKey: String,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateVersionRequest,
    ): ResponseEntity<DataResponse<VersionResponse>> {
        log.info("VersionController.update projectIdOrKey={} id={}", projectIdOrKey, id)
        val version =
            service.update(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                versionId = id,
                name = request.name,
                description = request.description,
            )
        return ResponseEntity.ok(DataResponse(data = VersionResponse.from(version)))
    }

    /**
     * 버전의 시작일과 릴리스 예정일을 지정하거나 해제한다.
     *
     * [ChangeVersionDatesRequest.startDate] / [ChangeVersionDatesRequest.releaseDate] 가
     * null 이면 해제, LocalDate 이면 지정. 2-state each.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param id path variable 버전 UUID.
     * @param request 날짜 변경 요청 바디.
     * @return 200 OK + 변경된 [VersionResponse] body.
     * @throws com.bts.issue.version.domain.VersionProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.version.domain.VersionNotFoundException 버전 미존재 → 404
     */
    @Operation(operationId = "changeVersionDates", summary = "버전 날짜 변경")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "버전 또는 프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PatchMapping("/{id}/dates")
    fun changeDates(
        @PathVariable projectIdOrKey: String,
        @PathVariable id: UUID,
        @RequestBody request: ChangeVersionDatesRequest,
    ): ResponseEntity<DataResponse<VersionResponse>> {
        log.info(
            "VersionController.changeDates projectIdOrKey={} id={} startDate={} releaseDate={}",
            projectIdOrKey,
            id,
            request.startDate,
            request.releaseDate,
        )
        val version =
            service.changeDates(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                versionId = id,
                startDate = request.startDate,
                releaseDate = request.releaseDate,
            )
        return ResponseEntity.ok(DataResponse(data = VersionResponse.from(version)))
    }

    /**
     * 버전의 상태를 전환한다.
     *
     * 전환 그래프는 [VersionApplicationService.changeStatus] 가 도메인 Aggregate 를 통해 강제한다.
     * 허용되지 않는 전환은 409 [VersionErrorCodes.VERSION_TRANSITION_NOT_ALLOWED] 로 응답한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param id path variable 버전 UUID.
     * @param request 상태 전환 요청 바디. status 필수.
     * @return 200 OK + 전환 후 [VersionResponse].
     * @throws com.bts.issue.version.domain.VersionProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.version.domain.VersionNotFoundException 버전 미존재 → 404
     * @throws com.bts.issue.version.domain.VersionTransitionNotAllowedException 불허 전환 → 409
     */
    @Operation(operationId = "changeVersionStatus", summary = "버전 상태 전환")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "버전 또는 프로젝트 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "허용되지 않는 전환", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PatchMapping("/{id}/status")
    fun changeStatus(
        @PathVariable projectIdOrKey: String,
        @PathVariable id: UUID,
        @Valid @RequestBody request: ChangeVersionStatusRequest,
    ): ResponseEntity<DataResponse<VersionResponse>> {
        log.info("VersionController.changeStatus projectIdOrKey={} id={} target={}", projectIdOrKey, id, request.status)
        val target = requireNotNull(request.status) { "status must not be null" }
        val version =
            service.changeStatus(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                versionId = id,
                target = target,
            )
        return ResponseEntity.ok(DataResponse(data = VersionResponse.from(version)))
    }

    /**
     * 버전을 소프트 삭제한다.
     *
     * 물리 삭제 금지 (DATA.md §3). `deleted_at` 를 현재 시각으로 설정한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param id path variable 버전 UUID.
     * @throws com.bts.issue.version.domain.VersionProjectNotFoundException 프로젝트 미존재 → 404
     * @throws com.bts.issue.version.domain.VersionNotFoundException 버전 미존재 → 404
     */
    @Operation(operationId = "deleteVersion", summary = "버전 소프트 삭제")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "버전 또는 프로젝트 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable projectIdOrKey: String,
        @PathVariable id: UUID,
    ) {
        log.info("VersionController.delete projectIdOrKey={} id={}", projectIdOrKey, id)
        service.delete(
            actorId = SYSTEM_ACTOR_UUID,
            projectIdOrKey = projectIdOrKey,
            versionId = id,
        )
    }
}
