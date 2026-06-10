// 버전 단건 응답 DTO — Version 도메인 객체를 REST 응답으로 매핑 (FR-VR-01 + FR-VR-02)

package com.bts.issue.version.web.dto

import com.bts.issue.version.domain.Version
import com.bts.issue.version.domain.VersionStatus
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDate
import java.util.UUID

/**
 * 버전 단건 응답 DTO.
 *
 * [Version] 도메인 객체를 REST 응답용으로 매핑한다.
 * DB 저장 후에만 응답에 포함되므로 [id] 는 null 불가.
 *
 * @property id 버전 UUID.
 * @property projectId 소속 프로젝트 UUID.
 * @property name 버전 이름.
 * @property description 선택적 설명. null 허용.
 * @property startDate 버전 시작일. null 이면 미지정.
 * @property releaseDate 버전 릴리스 예정일. null 이면 미지정.
 * @property status 버전 생명주기 상태 (UNRELEASED/RELEASED/ARCHIVED).
 * @property releasedAt 실제 릴리스 시각. ISO-8601 UTC 문자열. RELEASED 상태일 때만 non-null.
 */
data class VersionResponse(
    val id: UUID,
    val projectId: UUID,
    val name: String,
    val description: String?,
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val startDate: LocalDate?,
    @field:JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    val releaseDate: LocalDate?,
    val status: VersionStatus,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val releasedAt: String?,
) {
    companion object {
        /**
         * [Version] 도메인 객체를 [VersionResponse] 로 변환한다.
         *
         * [Version.releasedAt] ([java.time.Instant]) 은 ISO-8601 UTC 문자열로 변환된다.
         *
         * @param version 변환 대상 도메인 객체. [Version.id] 는 non-null 이어야 한다.
         * @throws IllegalArgumentException [version.id] 가 null 인 경우.
         */
        fun from(version: Version): VersionResponse {
            val id =
                requireNotNull(version.id) { "Version.id must not be null for response mapping" }
            return VersionResponse(
                id = id,
                projectId = version.projectId,
                name = version.name,
                description = version.description,
                startDate = version.startDate,
                releaseDate = version.releaseDate,
                status = version.status,
                releasedAt = version.releasedAt?.toString(),
            )
        }
    }
}
