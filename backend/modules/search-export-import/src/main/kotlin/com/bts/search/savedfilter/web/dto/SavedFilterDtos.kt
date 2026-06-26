// 저장된 필터 REST 요청/응답 DTO (FR-SR-03)

package com.bts.search.savedfilter.web.dto

import com.bts.search.savedfilter.domain.SavedFilter
import java.time.Instant
import java.util.UUID

/**
 * 필터 생성 요청 바디.
 *
 * PR1(PRIVATE 전용)은 `shares`를 포함하지 않는다(공유는 PR2).
 *
 * @param name 필터 이름(owner 내 유니크).
 * @param aqlQuery 저장할 AQL 쿼리 문자열.
 * @param projectKey 필터 실행 컨텍스트 프로젝트 키.
 */
data class SavedFilterCreateRequest(
    val name: String?,
    val aqlQuery: String?,
    val projectKey: String?,
)

/**
 * 필터 수정 요청 바디.
 *
 * projectKey는 변경 불가이므로 포함하지 않는다(FR-10). version은 OCC 키.
 *
 * @param name 새 필터 이름.
 * @param aqlQuery 새 AQL 쿼리 문자열.
 * @param version 클라이언트가 보유한 현재 버전(OCC 검사).
 */
data class SavedFilterUpdateRequest(
    val name: String?,
    val aqlQuery: String?,
    val version: Long?,
)

/**
 * 필터 응답 바디.
 *
 * @param isOwner 요청자가 소유자인지 여부(요청자 기준 계산).
 */
data class SavedFilterResponse(
    val id: UUID,
    val ownerId: UUID,
    val name: String,
    val aqlQuery: String,
    val projectKey: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val version: Long,
    val isOwner: Boolean,
) {
    companion object {
        /**
         * 도메인 [SavedFilter]를 응답 DTO로 변환한다.
         *
         * @param filter 변환할 도메인 객체.
         * @param actorId 요청자 UUID([isOwner] 계산용).
         */
        fun from(
            filter: SavedFilter,
            actorId: UUID,
        ): SavedFilterResponse =
            SavedFilterResponse(
                id = requireNotNull(filter.id) { "영속된 필터는 id가 있어야 한다." },
                ownerId = filter.ownerId,
                name = filter.name,
                aqlQuery = filter.aqlQuery,
                projectKey = filter.projectKey,
                createdAt = filter.createdAt,
                updatedAt = filter.updatedAt,
                version = filter.version,
                isOwner = filter.ownerId == actorId,
            )
    }
}
