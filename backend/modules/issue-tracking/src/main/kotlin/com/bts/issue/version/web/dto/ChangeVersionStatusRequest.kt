// PATCH /api/v1/projects/{projectIdOrKey}/versions/{id}/status 요청 바디 DTO — 상태 전환

package com.bts.issue.version.web.dto

import com.bts.issue.version.domain.VersionStatus
import jakarta.validation.constraints.NotNull

/**
 * 버전 상태 전환 REST 요청 바디.
 *
 * `/status` 전용 서브리소스. [ChangeVersionDatesRequest] 와 동형 패턴.
 * status 에 정의되지 않은 문자열이 전달되면 Jackson 역직렬화 실패로 400 이 반환된다.
 *
 * @property status 목표 [VersionStatus]. 필수(@NotNull). 잘못된 문자열은 400 VALIDATION_FAILED.
 */
data class ChangeVersionStatusRequest(
    @field:NotNull
    val status: VersionStatus?,
)
