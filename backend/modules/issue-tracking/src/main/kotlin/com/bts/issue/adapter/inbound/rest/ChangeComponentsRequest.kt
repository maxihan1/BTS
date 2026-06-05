// PATCH /api/v1/issues/{key}/components 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * 이슈 컴포넌트 변경 REST 요청 바디.
 *
 * [componentIds] 에 명시된 UUID 목록으로 이슈의 컴포넌트 연결을 전체 교체한다.
 * 빈 목록이면 기존 컴포넌트를 전부 해제한다.
 * [expectedVersion] 은 낙관적 잠금(optimistic locking)을 위해 필수다.
 *
 * @property componentIds 새로 연결할 컴포넌트 UUID 목록. 빈 목록이면 전체 해제.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 */
data class ChangeComponentsRequest(
    val componentIds: List<UUID> = emptyList(),
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long?,
)
