// 필드 권한 규칙 CRUD 엔드포인트 — projectIdOrKey 해석 + 이중가드 + actor(JWT/PAT) 추출 (FR-PM-07 PR-A Task 5)

package com.atlas.bts.identity.fieldpermission.web

import com.atlas.bts.identity.fieldpermission.application.FieldPermissionApplicationService
import com.atlas.bts.identity.fieldpermission.application.GroupNotFound
import com.atlas.bts.identity.fieldpermission.application.InvalidFieldKey
import com.atlas.bts.identity.fieldpermission.application.ManageFieldPermissionsDenied
import com.atlas.bts.identity.fieldpermission.web.dto.CreateFieldPermissionRequest
import com.atlas.bts.identity.fieldpermission.web.dto.FieldPermissionResponse
import com.atlas.bts.identity.project.ProjectDirectory
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 필드 권한 규칙 CRUD 컨트롤러 (FR-PM-07 PR-A Task 5).
 *
 * ## 엔드포인트 (모두 /api/v1/projects/{projectIdOrKey}/field-permissions 하위)
 * - [listRules]: GET 규칙 목록 → 200 (groupName 포함)
 * - [createRule]: POST 규칙 추가(멱등) → 201
 * - [deleteRule]: DELETE 규칙 삭제 → 204
 *
 * ## 이중 가드 (DEVELOPMENT.md §1.1 #6)
 * 1. 클래스 레벨 [PreAuthorize]("isAuthenticated()") — 미인증 요청을 필터 체인에서 차단(401).
 * 2. [FieldPermissionApplicationService] 가 핸들러 호출 시 DB 기반 `MANAGE_FIELD_PERMISSIONS`
 *    권한을 수동 평가한다(미보유 403, EC11). actor 추출/권한 평가가 리소스 조회보다 먼저 실행된다.
 *
 * ## Actor 추출 ([resolveActorId], ProjectMemberController 동형)
 * - jwt != null → JWT subject 를 UUID 로 파싱.
 * - jwt == null → SecurityContext principal(String, PatAuthenticationFilter 설정)을 UUID 로 파싱.
 * - 둘 다 실패 → null → 401.
 *
 * ## projectIdOrKey 해석 ([resolveProjectId], ProjectMemberController 동형)
 * UUID 파싱 성공 시 [ProjectDirectory.exists] 확인, 실패 시 key 로 간주해 [ProjectDirectory.resolveKeyToId].
 * 결과 null → 404. 정규식 사전거부 없음(모든 비정상 입력이 DB 조회 결과로 404 수렴).
 *
 * ## 에러 매핑 ([mapDomainException])
 * 도메인 예외를 snake_case `error` 코드 + HTTP 상태로 인라인 매핑한다(RestControllerAdvice 없음).
 *
 * ## 보안 (SecurityConfig 무변경)
 * /api 하위 전체 authenticated 규칙이 이 경로(/api/v1/projects)를 이미 커버한다.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectIdOrKey}/field-permissions")
@PreAuthorize("isAuthenticated()")
class FieldPermissionController(
    private val applicationService: FieldPermissionApplicationService,
    private val projectDirectory: ProjectDirectory,
) {
    /**
     * GET — 프로젝트 전체 필드 권한 규칙 목록을 반환한다.
     *
     * @return 200 `[FieldPermissionResponse]` 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @GetMapping
    fun listRules(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectIdOrKey: String,
    ): ResponseEntity<*> {
        val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE
        val projectId = resolveProjectId(projectIdOrKey) ?: return PROJECT_NOT_FOUND_RESPONSE

        return runHandler {
            val rules =
                applicationService.listRules(actorId, projectId).map {
                    FieldPermissionResponse.from(it.rule, it.groupName)
                }
            ResponseEntity.ok(rules)
        }
    }

    /**
     * POST — 필드 권한 규칙을 추가한다(멱등).
     *
     * @return 201 [FieldPermissionResponse] 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @PostMapping
    fun createRule(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectIdOrKey: String,
        @RequestBody @Valid body: CreateFieldPermissionRequest,
    ): ResponseEntity<*> {
        val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE
        val projectId = resolveProjectId(projectIdOrKey) ?: return PROJECT_NOT_FOUND_RESPONSE

        return runHandler {
            val created =
                applicationService.createRule(
                    actorId = actorId,
                    projectId = projectId,
                    fieldKind = requireNotNull(body.fieldKind),
                    fieldKey = requireNotNull(body.fieldKey),
                    groupId = requireNotNull(body.groupId),
                    accessLevel = requireNotNull(body.accessLevel),
                )
            ResponseEntity.status(HttpStatus.CREATED).body(FieldPermissionResponse.from(created.rule, created.groupName))
        }
    }

    /**
     * DELETE — 필드 권한 규칙을 식별자로 삭제한다(멱등).
     *
     * @return 204 No Content 또는 에러 응답
     */
    @Suppress("ReturnCount")
    @DeleteMapping("/{ruleId}")
    fun deleteRule(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable projectIdOrKey: String,
        @PathVariable ruleId: UUID,
    ): ResponseEntity<*> {
        val actorId = resolveActorId(jwt) ?: return UNAUTHORIZED_RESPONSE
        val projectId = resolveProjectId(projectIdOrKey) ?: return PROJECT_NOT_FOUND_RESPONSE

        return runHandler {
            applicationService.deleteRule(actorId, projectId, ruleId)
            ResponseEntity.noContent().build<Void>()
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * JWT 또는 PAT SecurityContext 에서 actor UUID 를 추출한다.
     *
     * - jwt != null → JWT subject 를 UUID 로 파싱.
     * - jwt == null → SecurityContext principal(String)을 UUID 로 파싱(PAT 경로).
     * - 파싱 실패 → null (호출 측 401).
     */
    @Suppress("ReturnCount")
    private fun resolveActorId(jwt: Jwt?): UUID? {
        if (jwt != null) {
            return runCatching { UUID.fromString(jwt.subject) }.getOrNull()
        }
        val rawPrincipal = SecurityContextHolder.getContext().authentication?.principal as? String ?: return null
        return runCatching { UUID.fromString(rawPrincipal) }.getOrNull()
    }

    /**
     * projectIdOrKey 문자열을 활성 프로젝트 UUID 로 해석한다.
     *
     * UUID 파싱 성공 시 [ProjectDirectory.exists] 로 활성 여부 확인, 실패 시 key 로 간주해
     * [ProjectDirectory.resolveKeyToId]. 미존재·soft-deleted 이면 null(호출 측 404).
     */
    private fun resolveProjectId(raw: String): UUID? {
        val asUuid = runCatching { UUID.fromString(raw) }.getOrNull()
        if (asUuid != null) {
            return if (projectDirectory.exists(asUuid)) asUuid else null
        }
        return projectDirectory.resolveKeyToId(raw)
    }

    /**
     * 핸들러 본문을 실행하고 도메인 예외만 HTTP 응답으로 매핑한다.
     *
     * 잡는 예외를 도메인 sealed 계층으로 한정한다(광범위 catch 금지, silently swallow 금지).
     * 그 외 예외는 전파시켜 기본 처리에 위임한다.
     */
    private inline fun runHandler(block: () -> ResponseEntity<*>): ResponseEntity<*> =
        try {
            block()
        } catch (ex: ManageFieldPermissionsDenied) {
            mapDomainException(ex)
        } catch (ex: InvalidFieldKey) {
            mapDomainException(ex)
        } catch (ex: GroupNotFound) {
            mapDomainException(ex)
        }

    /**
     * 도메인 예외를 snake_case `error` 코드 + HTTP 상태로 매핑한다.
     */
    private fun mapDomainException(ex: RuntimeException): ResponseEntity<Map<String, String>> =
        when (ex) {
            is ManageFieldPermissionsDenied -> errorResponse(HttpStatus.FORBIDDEN, "forbidden")
            is InvalidFieldKey -> errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_field_key")
            is GroupNotFound -> errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "group_not_found")
            else -> throw ex
        }

    private companion object {
        /** actor 추출 실패(JWT/PAT 파싱 오류) 공용 401 응답. */
        val UNAUTHORIZED_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "unauthorized"))

        /** 프로젝트 미존재 공용 404 응답. */
        val PROJECT_NOT_FOUND_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "project_not_found"))

        /** 주어진 상태/코드로 error 키 단일 맵 응답을 만든다. */
        fun errorResponse(
            status: HttpStatus,
            code: String,
        ): ResponseEntity<Map<String, String>> = ResponseEntity.status(status).body(mapOf("error" to code))
    }
}
