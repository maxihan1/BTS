// 보드 상세 보기 필드 구성 REST 컨트롤러 — 그룹 4종 조회 + 그룹 단위 PATCH (부채 177 Task 13 · RED 껍데기)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.DetailViewSettingsService
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.shared.permission.IssuePermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 상세 보기 구성 PATCH 요청 바디.
 *
 * @property groups `fieldGroup → 필드 키 목록`. 요청에 없는 그룹은 건드리지 않는다.
 */
data class DetailViewFieldsPatchRequest(
    val groups: Map<String, List<String>>,
)

/**
 * 상세 보기 구성 응답 바디.
 *
 * @property groups `fieldGroup → 필드 키 목록`.
 */
data class DetailViewFieldsResponse(
    val groups: Map<String, List<String>>,
)

/**
 * 보드 상세 보기 필드 구성 REST 컨트롤러 (R7 · J46~J49).
 *
 * RED 단계 껍데기 — 권한 게이트와 검증은 GREEN 에서 채운다.
 *
 * @param service 상세 보기 구성 유스케이스.
 * @param boardRepository 보드 메타(projectKey) 조회용.
 * @param permissionResolver cross-BC 권한 판정 포트.
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/detail-view-fields")
class BoardDetailViewController(
    private val service: DetailViewSettingsService,
    @Suppress("UnusedPrivateProperty") private val boardRepository: BoardRepository,
    @Suppress("UnusedPrivateProperty") private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 상세 보기 구성을 조회한다.
     *
     * @param boardId path variable 보드 UUID.
     * @return 200 OK + [DetailViewFieldsResponse].
     */
    @GetMapping
    fun getFields(
        @PathVariable boardId: UUID,
    ): ResponseEntity<DataResponse<DetailViewFieldsResponse>> {
        log.info("BoardDetailViewController.getFields boardId={}", boardId)
        return ResponseEntity.ok(DataResponse(DetailViewFieldsResponse(service.findFields(boardId))))
    }

    /**
     * 요청에 실린 그룹의 구성을 교체한다.
     *
     * @param boardId path variable 보드 UUID.
     * @param request 교체 요청 바디.
     * @return 200 OK + 교체 후 전체 구성.
     */
    @PatchMapping
    fun patchFields(
        @PathVariable boardId: UUID,
        @RequestBody request: DetailViewFieldsPatchRequest,
    ): ResponseEntity<DataResponse<DetailViewFieldsResponse>> {
        log.info("BoardDetailViewController.patchFields boardId={} groups={}", boardId, request.groups.keys)
        return ResponseEntity.ok(DataResponse(DetailViewFieldsResponse(service.replaceGroups(boardId, request.groups))))
    }
}
