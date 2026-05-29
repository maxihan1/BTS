// IssueTypeController — GET 목록 조회 + POST/PATCH/DELETE CRUD (FR-IS-02)

package com.bts.issue.type.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.type.application.IssueTypeApplicationService
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.type.web.dto.CreateIssueTypeRequest
import com.bts.issue.type.web.dto.IssueTypeResponse
import com.bts.issue.type.web.dto.UpdateIssueTypeRequest
import com.bts.shared.issue.IssueTypeId
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 이슈 타입 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - GET    /api/v1/issue-types — 활성 이슈 타입 전체 목록 조회 (FR-WF-02-10)
 * - POST   /api/v1/issue-types — 커스텀 이슈 타입 생성 (FR-IS-02)
 * - PATCH  /api/v1/issue-types/{id} — 이슈 타입 수정 (FR-IS-02)
 * - DELETE /api/v1/issue-types/{id} — 이슈 타입 소프트 삭제 (FR-IS-02)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * GET 은 [IssueTypeRepository.findAll] 의 `@Transactional(readOnly = true)` 가,
 * CRUD 는 [IssueTypeApplicationService] 의 `@Transactional` 이 담당한다.
 *
 * @param repository 이슈 타입 Repository (GET 조회 + PATCH 후 재조회)
 * @param service 이슈 타입 CRUD Application Service
 */
@RestController
@RequestMapping("/api/v1/issue-types")
class IssueTypeController(
    private val repository: IssueTypeRepository,
    private val service: IssueTypeApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 활성 이슈 타입 목록을 반환한다.
     *
     * V003 seed 직후에는 5 표준 타입 (epic/story/task/subtask/bug) 이 반환된다.
     * `deleted_at IS NULL` 인 타입만 포함된다.
     *
     * @return 200 OK + `{ "data": [ { id, key, name, description, iconName, isStandard, hierarchyLevel }, ... ] }`
     */
    @GetMapping
    fun listIssueTypes(): ResponseEntity<DataResponse<List<IssueTypeResponse>>> {
        log.debug("IssueTypeController.listIssueTypes")
        val types = repository.findAll().map { IssueTypeResponse.from(it) }
        return ResponseEntity.ok(DataResponse(data = types))
    }

    /**
     * 새 커스텀 이슈 타입을 생성한다.
     *
     * @param request 이슈 타입 생성 요청 바디 (Jakarta Validation 적용).
     * @return 201 Created + [IssueTypeResponse] body
     * @throws com.bts.issue.type.domain.IssueTypeKeyInvalidException key 형식 위반 → 409
     * @throws com.bts.issue.type.domain.IssueTypeKeyDuplicateException key 중복 → 409
     */
    @PostMapping
    fun createIssueType(
        @Valid @RequestBody request: CreateIssueTypeRequest,
    ): ResponseEntity<DataResponse<IssueTypeResponse>> {
        log.info("IssueTypeController.createIssueType key={}", request.key)
        val created = service.create(request.toAppRequest())
        val response = IssueTypeResponse.from(created)
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse(data = response))
    }

    /**
     * 이슈 타입의 변경 가능 필드를 수정한다.
     *
     * key / isStandard 는 불변이므로 수정할 수 없다.
     * 수정 후 최신 상태를 조회하여 응답 바디로 반환한다.
     *
     * @param id path variable 이슈 타입 DB PK.
     * @param request 수정 요청 바디 (Jakarta Validation 적용).
     * @return 200 OK + 수정된 [IssueTypeResponse] body
     * @throws com.bts.issue.type.domain.IssueTypeNotFoundException id 미존재 → 404
     * @throws com.bts.issue.type.domain.IssueTypeStandardImmutableException 표준 타입 수정 시도 → 409
     */
    @PatchMapping("/{id}")
    fun updateIssueType(
        @PathVariable id: Long,
        @Valid @RequestBody request: UpdateIssueTypeRequest,
    ): ResponseEntity<DataResponse<IssueTypeResponse>> {
        log.info("IssueTypeController.updateIssueType id={}", id)
        val typeId = IssueTypeId(id)
        service.update(typeId, request.toAppRequest())
        val updated = repository.findById(typeId)
        requireNotNull(updated) { "IssueType not found after update: id=$id" }
        return ResponseEntity.ok(DataResponse(data = IssueTypeResponse.from(updated)))
    }

    /**
     * 이슈 타입을 소프트 삭제한다.
     *
     * 사용 중인 이슈가 있을 때 [reassignTo] 를 지정하면 해당 타입으로 일괄 재배정 후 삭제한다.
     * 스킴 매핑이 존재하면 [reassignTo] 지정과 무관하게 409 로 거부된다.
     *
     * @param id path variable 이슈 타입 DB PK.
     * @param reassignTo 선택적 재배정 대상 이슈 타입 id. null 이면 이슈 사용 중일 때 409.
     * @throws com.bts.issue.type.domain.IssueTypeNotFoundException id 미존재 → 404
     * @throws com.bts.issue.type.domain.IssueTypeStandardImmutableException 표준 타입 삭제 시도 → 409
     * @throws com.bts.issue.type.domain.IssueTypeInUseException 사용 중이고 해소 불가 → 409
     * @throws com.bts.issue.type.domain.IssueTypeReassignTargetInvalidException reassignTo 무효 → 409
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteIssueType(
        @PathVariable id: Long,
        @RequestParam(required = false) reassignTo: Long?,
    ) {
        log.info("IssueTypeController.deleteIssueType id={} reassignTo={}", id, reassignTo)
        service.delete(
            id = IssueTypeId(id),
            reassignTo = reassignTo?.let { IssueTypeId(it) },
        )
    }
}
