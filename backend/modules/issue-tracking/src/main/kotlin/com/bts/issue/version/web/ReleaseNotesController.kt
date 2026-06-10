// FR-VR-04 릴리즈 노트 조회 REST 컨트롤러 — VersionController 와 분리된 단일 책임 컨트롤러

package com.bts.issue.version.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.version.releasenotes.ReleaseNotesService
import com.bts.issue.version.web.dto.ReleaseNotesResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** actorId placeholder — FR-PM-03 실 추출 이연. SecurityConfig 가 401 을 보장한다. */
private val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

/**
 * 릴리즈 노트 조회 REST 컨트롤러.
 *
 * `GET /api/v1/projects/{projectIdOrKey}/versions/{versionId}/release-notes` 단일 엔드포인트.
 * 한 버전을 Fix Version 으로 가진 활성 이슈를 타입별로 묶은 Markdown 릴리즈 노트를 생성해 반환한다.
 *
 * ### 별도 컨트롤러인 이유 (eng-review 결정)
 * [VersionController] 에 끼워넣지 않는다 — VersionController 는 이미 다수 엔드포인트를 보유하고,
 * 생성자에 [ReleaseNotesService] 를 추가하면 기존 통합테스트가 파급된다. 단일 책임으로 분리한다.
 *
 * ### 예외 처리 스코프
 * 이 컨트롤러는 `com.bts.issue.version.web` 패키지에 위치하므로
 * [VersionExceptionHandler]`(basePackages=["com.bts.issue.version.web"])` 가 도메인 예외를 커버한다.
 * 버전/프로젝트 미존재는 500 이 아닌 404 + errorCode 로 응답한다.
 *
 * ### 트랜잭션 / 권한
 * 트랜잭션 경계는 [ReleaseNotesService] 의 `@Transactional(readOnly = true)` 가 담당한다.
 * 릴리즈 노트는 읽기 동작이므로 버전 조회와 동일하게 READ 권한 게이트를 적용하지 않는다.
 * actorId 는 [SYSTEM_ACTOR_UUID] placeholder 를 사용한다(FR-PM-03 이연, 로깅 목적).
 *
 * @param service 릴리즈 노트 생성 서비스.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectIdOrKey}/versions/{versionId}/release-notes")
class ReleaseNotesController(
    private val service: ReleaseNotesService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 버전의 릴리즈 노트를 생성해 반환한다.
     *
     * @param projectIdOrKey path variable 프로젝트 UUID 또는 projectKey.
     * @param versionId path variable 버전 UUID.
     * @return 200 OK + [ReleaseNotesResponse] body.
     * @throws com.bts.issue.version.domain.VersionProjectNotFoundException 프로젝트 미존재 → 404.
     * @throws com.bts.issue.version.domain.VersionNotFoundException 버전 미존재(소프트 삭제 포함) → 404.
     */
    @GetMapping
    fun get(
        @PathVariable projectIdOrKey: String,
        @PathVariable versionId: UUID,
    ): ResponseEntity<DataResponse<ReleaseNotesResponse>> {
        log.debug("ReleaseNotesController.get projectIdOrKey={} versionId={}", projectIdOrKey, versionId)
        val releaseNotes =
            service.generate(
                actorId = SYSTEM_ACTOR_UUID,
                projectIdOrKey = projectIdOrKey,
                versionId = versionId,
            )
        return ResponseEntity.ok(DataResponse(data = ReleaseNotesResponse.from(releaseNotes)))
    }
}
