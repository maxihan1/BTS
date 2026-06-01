// PATCH /api/v1/issues/{key}/assignee 요청 바디 DTO — Jakarta Validation 어노테이션으로 입력값 검증

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.NotNull
import java.util.UUID

/**
 * 담당자 변경 REST 요청 바디.
 *
 * [assigneeId] 가 null 이면 담당자를 해제한다 (3-state null 시맨틱).
 * [expectedVersion] 은 낙관적 잠금(optimistic locking)을 위해 필수다.
 *
 * ### 전용 서브리소스 엔드포인트 설계 사유
 * RFC 7396 JSON Merge Patch 에서 null 은 "필드 삭제" 를 의미한다.
 * 그러나 PATCH /issues/{key} 의 [UpdateIssueRequest] 는 null 을 "변경 없음" 으로 사용하는
 * partial-update 시맨틱을 따른다. assignee 의 null=해제(3-state) 의미와 충돌하므로
 * 전용 서브리소스 /assignee 로 분리하여 시맨틱 모호성을 제거한다.
 *
 * @property assigneeId 새 담당자 UUID. null 이면 담당자 해제.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 */
data class ChangeAssigneeRequest(
    val assigneeId: UUID?,
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long?,
)
