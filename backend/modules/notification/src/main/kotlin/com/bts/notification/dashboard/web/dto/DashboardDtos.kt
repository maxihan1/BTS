// 대시보드 REST API 요청·응답 DTO — 직렬화 계약 및 Jakarta Validation

package com.bts.notification.dashboard.web.dto

import com.bts.notification.dashboard.domain.Dashboard
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.util.UUID

/**
 * 대시보드 생성 요청 DTO.
 *
 * @param name 대시보드 이름 (빈 문자열 불가 — 도메인에서도 검증)
 * @param description 설명 (선택)
 * @param visibility 공개 범위 (PRIVATE/TEAM/ORG)
 * @param layout 위젯 배치 JSONB 문자열 (선택, 기본 빈 배열)
 * @param sharedUserIds TEAM 공유 대상 사용자 ID 목록 (선택)
 */
data class CreateDashboardRequest(
    @field:NotBlank(message = "대시보드 이름은 필수입니다.")
    val name: String,
    val description: String? = null,
    @field:NotBlank(message = "visibility 는 필수입니다.")
    val visibility: String,
    val layout: String? = null,
    val sharedUserIds: List<UUID>? = null,
)

/**
 * 대시보드 부분 수정 요청 DTO.
 *
 * 3-state PATCH 패턴.
 * - 키 없음 / null = 미변경
 * - sharedUserIds = emptyList() = 전체 제거
 *
 * version 은 OCC 낙관적 잠금 키로 필수다.
 *
 * @param name 변경할 이름 (null = 유지)
 * @param description 변경할 설명 (null = 유지)
 * @param visibility 변경할 공개 범위 문자열 (null = 유지)
 * @param layout 변경할 위젯 배치 JSON (null = 유지)
 * @param sharedUserIds 변경할 공유 대상 (null = 유지, 빈 리스트 = 전체 제거)
 * @param version OCC 버전 (필수)
 */
data class PatchDashboardRequest(
    val name: String? = null,
    val description: String? = null,
    val visibility: String? = null,
    val layout: String? = null,
    val sharedUserIds: List<UUID>? = null,
    val version: Long,
)

/**
 * 대시보드 단건·목록 응답 DTO.
 *
 * nullable 필드는 @JsonInclude(NON_NULL) 로 직렬화 시 생략한다.
 * (memory: frontend-zod-backend-dto-contract-gap — Zod nullable 실측 필수)
 *
 * @param id 대시보드 식별자
 * @param ownerId 소유자 사용자 ID
 * @param name 대시보드 이름
 * @param description 설명 (null 이면 직렬화 생략)
 * @param visibility 공개 범위 문자열
 * @param layout 위젯 배치 JSONB 문자열
 * @param sharedUserIds TEAM 공유 대상 사용자 ID 목록
 * @param createdAt 생성 시각 (ISO 8601)
 * @param updatedAt 최종 수정 시각 (ISO 8601)
 * @param version OCC 버전
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DashboardResponse(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val description: String?,
    val visibility: String,
    val layout: String,
    val sharedUserIds: List<UUID>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    companion object {
        /**
         * 도메인 Dashboard 를 응답 DTO 로 변환한다.
         *
         * @param domain 변환 대상 도메인 객체
         * @return 직렬화 준비된 응답 DTO
         */
        fun from(domain: Dashboard): DashboardResponse =
            DashboardResponse(
                id = domain.id,
                ownerId = domain.ownerId,
                name = domain.name,
                description = domain.description,
                visibility = domain.visibility.name,
                layout = domain.layout,
                sharedUserIds = domain.sharedUserIds.toList(),
                createdAt = domain.createdAt,
                updatedAt = domain.updatedAt,
                version = domain.version,
            )
    }
}

/**
 * 대시보드 목록 페이지네이션 응답 DTO.
 *
 * BTS notification BC 기존 목록 API 관례 형식: items + total + limit + offset.
 *
 * @param items 현재 페이지 대시보드 목록
 * @param total 전체 접근 가능 대시보드 수
 * @param limit 요청한 페이지 크기
 * @param offset 요청한 오프셋
 */
data class DashboardPageResponse(
    val items: List<DashboardResponse>,
    val total: Int,
    val limit: Int,
    val offset: Int,
)
