// IssueTypeController — GET /api/v1/issue-types 이슈 타입 목록 조회 (read-only, CRUD 후속 FR-IS-02 PR scope)

package com.bts.issue.type.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.type.web.dto.IssueTypeResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 이슈 타입 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - GET /api/v1/issue-types — 활성 이슈 타입 전체 목록 조회 (FR-WF-02-10)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 트랜잭션은 [IssueTypeRepository.findAll] 에 선언된 `@Transactional(readOnly = true)` 가 담당한다.
 *
 * ### PR scope (read-only)
 * 본 PR (FR-WF-02) 에서는 read-only 조회 1 엔드포인트만 포함한다.
 * 커스텀 IssueType CRUD API 는 후속 FR-IS-02 PR scope.
 *
 * @param repository 이슈 타입 Repository
 */
@RestController
@RequestMapping("/api/v1/issue-types")
class IssueTypeController(
    private val repository: IssueTypeRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 활성 이슈 타입 목록을 반환한다.
     *
     * V003 seed 직후에는 5 표준 타입 (epic/story/task/subtask/bug) 이 반환된다.
     * `deleted_at IS NULL` 인 타입만 포함된다.
     *
     * @return 200 OK + `{ "data": [ { id, key, name, description, iconName, isStandard }, ... ] }`
     */
    @GetMapping
    fun listIssueTypes(): ResponseEntity<DataResponse<List<IssueTypeResponse>>> {
        log.debug("IssueTypeController.listIssueTypes")
        val types = repository.findAll().map { IssueTypeResponse.from(it) }
        return ResponseEntity.ok(DataResponse(data = types))
    }
}
