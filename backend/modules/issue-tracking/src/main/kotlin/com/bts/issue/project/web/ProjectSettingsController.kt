// PATCH /api/v1/projects/{projectIdOrKey} 프로젝트 설정(name) 변경 컨트롤러 (FR-PJ PR-3 Task 5)

package com.bts.issue.project.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.project.settings.ProjectSettingsService
import com.bts.issue.project.web.dto.UpdateProjectRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 프로젝트 설정(name) 변경 REST API 컨트롤러 (FR-PJ PR-3 Task 5).
 *
 * 엔드포인트 — PATCH /api/v1/projects/{projectIdOrKey}. PROJECT_ADMIN 전용(컴포넌트 UPDATE 권한 재사용,
 * 게이트1 D2=(i)).
 *
 * ### 이중 인증 가드
 * 1. 클래스 레벨 `@PreAuthorize("isAuthenticated()")` + prod SecurityConfig 필터 체인(미인증 401).
 * 2. 핸들러의 [CurrentActor.current] — 미인증/nil-UUID/형식오류 시 401.
 * ([com.bts.issue.project.web.ProjectCreateController] 동형 이중 가드 — PAT 경로에 role claim 이 없어
 * `@PreAuthorize hasRole` 는 쓰지 않는다).
 *
 * ### actor 추출 순서 — 리소스 조회보다 먼저
 * [CurrentActor.current] 를 **가장 먼저** 추출한다. projectIdOrKey 해석/권한 판정은
 * [ProjectSettingsService.changeName] 안에서 일어나므로, 미인증자는 서비스에 도달하지 못한다
 * ([ProjectCreateController] 동형 순서 선례).
 *
 * ### 응답 — 204 No Content (재조회 없음)
 * [ProjectSettingsService.changeName] 은 갱신 결과를 반환하지 않는다(Unit). 응답 재구성을 위해
 * DB 를 재조회하지 않고, projectKey 를 요청 그대로 되돌려주는 방식([com.bts.issue.project.application.
 * ProjectRequire2faApplicationService.toggle] 의 `projectKey = projectIdOrKey` echo — projectIdOrKey 가
 * UUID 로 전달된 경우 실제 key 가 아닐 수 있는 부정확성)을 반복하지 않기 위해 204 No Content 로 응답한다.
 *
 * @param service 프로젝트 설정 변경 Application Service.
 */
@RestController
@RequestMapping("/api/v1/projects")
@PreAuthorize("isAuthenticated()")
class ProjectSettingsController(
    private val service: ProjectSettingsService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 name 을 변경한다.
     *
     * @param projectIdOrKey path variable — 프로젝트 UUID 또는 projectKey.
     * @param request 이름 변경 요청 바디([UpdateProjectRequest.name] — NotBlank·Size max 255).
     * @return 204 No Content.
     * @throws org.springframework.web.server.ResponseStatusException 미인증 → 401 (CurrentActor).
     * @throws com.bts.issue.project.settings.ProjectNotFoundException 프로젝트 미존재 → 404.
     * @throws com.bts.issue.project.settings.ProjectSettingsForbiddenException
     *   컴포넌트 UPDATE 권한 없음(PROJECT_ADMIN 아님) → 403.
     */
    @PatchMapping("/{projectIdOrKey}")
    fun update(
        @PathVariable projectIdOrKey: String,
        @Valid @RequestBody request: UpdateProjectRequest,
    ): ResponseEntity<Void> {
        // 1. actor 추출 — 리소스 조회보다 먼저 (미인증 401)
        val actor = CurrentActor.current()

        log.info("ProjectSettingsController.update actor={} projectIdOrKey={}", actor.value, projectIdOrKey)

        // 2. 변경 (존재 → 권한 → 영속 순서는 서비스 책임)
        service.changeName(actor.value, projectIdOrKey, request.name)
        return ResponseEntity.noContent().build()
    }
}
