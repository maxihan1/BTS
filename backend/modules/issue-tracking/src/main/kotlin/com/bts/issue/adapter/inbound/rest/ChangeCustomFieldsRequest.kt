// PATCH /api/v1/issues/{key}/custom-fields 요청 바디 DTO — 사용자 정의 필드 값만 받는다 (BTS-F10-02)

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.NotNull

/**
 * 사용자 정의 필드 값만 고치는 요청 바디.
 *
 * 「QA Hub AI」 쓰기 문(`QaHubAiWriteGateFilter`)이 이 경로만 연다. 그래서 제목 · 설명 같은 다른 칸은
 * 받지 않는다 — 본문에 실려 와도 버린다. 병합은 서비스 updateIssue 의 customFields 규칙을 따른다
 * (키 단위 병합 · 값 null 이면 그 필드 제거).
 *
 * @property customFields 필드 키 → 값.
 * @property expectedVersion 읽어온 시점의 버전 값. DB 버전과 다르면 409 Version Conflict.
 */
data class ChangeCustomFieldsRequest(
    @field:NotNull(message = "customFields는 필수입니다.")
    val customFields: Map<String, Any?>?,
    @field:NotNull(message = "expectedVersion은 필수입니다.")
    val expectedVersion: Long?,
)
