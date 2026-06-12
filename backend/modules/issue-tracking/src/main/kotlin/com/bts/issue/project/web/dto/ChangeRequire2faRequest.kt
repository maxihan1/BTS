// PATCH require-2fa 요청 바디 DTO — requireTwoFactor 플래그 (FR-MF-04)

package com.bts.issue.project.web.dto

/**
 * 프로젝트 require_2fa 토글 REST 요청 바디.
 *
 * SYSTEM_ADMIN 전용 엔드포인트 PATCH /api/v1/projects/{projectIdOrKey}/require-2fa 에서 사용한다.
 *
 * @property requireTwoFactor true 면 민감 프로젝트(MFA 강제 대상) 활성, false 면 해제.
 */
data class ChangeRequire2faRequest(
    val requireTwoFactor: Boolean,
)
