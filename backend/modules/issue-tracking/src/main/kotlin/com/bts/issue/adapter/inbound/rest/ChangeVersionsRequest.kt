// PATCH /api/v1/issues/{key}/affects-versions 및 /fix-versions 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * 이슈 버전 연결 변경 REST 요청 바디.
 *
 * [versionIds] 에 명시된 UUID 목록으로 이슈의 영향 버전(affects) 또는 수정 예정 버전(fix) 연결을 전체 교체한다.
 * 빈 목록이면 기존 연결을 전부 해제한다.
 * affects-versions 와 fix-versions 엔드포인트가 같은 DTO 를 공용으로 사용한다.
 * [expectedVersion] 은 낙관적 잠금(optimistic locking)을 위해 필수다.
 *
 * @property versionIds 새로 연결할 버전 UUID 목록. 빈 목록이면 전체 해제.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 */
data class ChangeVersionsRequest(
    val versionIds: List<UUID> = emptyList(),
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long?,
)
