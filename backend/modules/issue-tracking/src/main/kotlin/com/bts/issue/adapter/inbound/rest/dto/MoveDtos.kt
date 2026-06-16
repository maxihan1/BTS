// 이슈 프로젝트 간 이동 API 요청/응답 DTO — FR-MV-01 Task 8

package com.bts.issue.adapter.inbound.rest.dto

import jakarta.validation.constraints.NotBlank
import java.util.UUID

/**
 * POST /api/v1/issues/{key}/move/preview 요청 바디.
 *
 * @property targetProjectKey 이동 대상 프로젝트 키. 공백 불가.
 */
data class MovePreviewRequest(
    @field:NotBlank(message = "targetProjectKey는 비어 있을 수 없습니다.")
    val targetProjectKey: String,
)

/**
 * POST /api/v1/issues/{key}/move 요청 바디.
 *
 * preview(1단계) 에서 사용자가 확인한 매핑 정보를 그대로 전달한다.
 *
 * @property targetProjectKey 이동 대상 프로젝트 키. 공백 불가.
 * @property expectedVersion OCC 낙관락 버전.
 * @property targetStateKey 대상 프로젝트에서 적용할 상태 키. null 이면 소스 상태 키 그대로 사용 시도.
 * @property targetStateIsDone 대상 상태가 DONE 카테고리인지 여부.
 *   false 이면 resolution_id 를 null 로 clear 한다 (C4). 기본값 false.
 * @property componentMapping 원본 컴포넌트 UUID → 대상 컴포넌트 UUID 매핑. 값이 null 이면 미매핑(제거).
 * @property affectsVersionMapping 원본 affects-version UUID → 대상 버전 UUID 매핑.
 * @property fixVersionMapping 원본 fix-version UUID → 대상 버전 UUID 매핑.
 * @property customFieldValues 대상 프로젝트에서 필수이지만 기존 이슈에 없는 커스텀 필드 추가 값.
 */
data class MoveRequest(
    @field:NotBlank(message = "targetProjectKey는 비어 있을 수 없습니다.")
    val targetProjectKey: String,
    val expectedVersion: Long,
    val targetStateKey: String? = null,
    val targetStateIsDone: Boolean = false,
    val componentMapping: Map<UUID, UUID?> = emptyMap(),
    val affectsVersionMapping: Map<UUID, UUID?> = emptyMap(),
    val fixVersionMapping: Map<UUID, UUID?> = emptyMap(),
    val customFieldValues: Map<String, Any?> = emptyMap(),
)

/**
 * POST /api/v1/issues/{key}/move 성공 응답.
 *
 * @property issueKey 이동 후 새 이슈 키.
 * @property previousKey 이동 전 원본 이슈 키.
 */
data class MoveResponse(
    val issueKey: String,
    val previousKey: String,
)
