// 저장된 필터 REST 요청/응답 DTO (FR-SR-03)

package com.bts.search.savedfilter.web.dto

import com.bts.search.savedfilter.domain.SavedFilter
import com.bts.search.savedfilter.domain.SavedFilterShare
import com.bts.search.savedfilter.domain.ShareType
import java.time.Instant
import java.util.UUID

/**
 * 공유 대상 요청 단일 항목.
 *
 * POST/PUT 바디의 `shares` 배열 원소로 사용된다.
 * [shareType]이 null이거나 알 수 없는 값이면 [toDomain] 호출 시 [IllegalArgumentException]이 발생해
 * 핸들러([com.bts.search.savedfilter.web.SavedFilterExceptionHandler.handleIllegalArgument])가
 * HTTP 400으로 매핑한다.
 *
 * @param shareType 공유 종류 문자열(예: "PROJECT", "GROUP", "AUTHENTICATED").
 * @param targetId 공유 대상 식별자. [ShareType.AUTHENTICATED]이면 `null`.
 */
data class ShareRequest(
    val shareType: String?,
    val targetId: String?,
) {
    /**
     * 도메인 [SavedFilterShare]로 변환한다.
     *
     * @return 불변식을 만족하는 [SavedFilterShare].
     * @throws IllegalArgumentException [shareType]이 null이거나 알 수 없는 값일 때,
     *         또는 도메인 불변식(PROJECT/GROUP에 targetId 필수, AUTHENTICATED에 targetId 불가)을 위반할 때.
     */
    fun toDomain(): SavedFilterShare {
        val rawType = shareType ?: throw IllegalArgumentException("shareType은 필수입니다.")
        val type = ShareType.from(rawType)
        return SavedFilterShare.create(type, targetId)
    }
}

/**
 * 공유 대상 응답 단일 항목.
 *
 * [ShareType] enum을 그대로 직렬화하면 클라이언트와의 계약이 enum 이름에 의존하므로
 * 명시적으로 문자열로 변환해 응답에 포함한다.
 *
 * @param shareType 공유 종류 문자열(예: "PROJECT").
 * @param targetId 공유 대상 식별자. [ShareType.AUTHENTICATED]이면 `null`.
 */
data class ShareDto(
    val shareType: String,
    val targetId: String?,
) {
    companion object {
        /**
         * 도메인 [SavedFilterShare]를 응답 DTO로 변환한다.
         *
         * @param share 변환할 도메인 공유 객체.
         */
        fun from(share: SavedFilterShare): ShareDto {
            return ShareDto(shareType = share.shareType.name, targetId = share.targetId)
        }
    }
}

/**
 * 필터 생성 요청 바디.
 *
 * @param name 필터 이름(owner 내 유니크).
 * @param aqlQuery 저장할 AQL 쿼리 문자열.
 * @param projectKey 필터 실행 컨텍스트 프로젝트 키.
 * @param shares 공유 대상 목록. `null`이면 공유 없음(PRIVATE). `[]`이면 공유 전체 제거.
 */
data class SavedFilterCreateRequest(
    val name: String?,
    val aqlQuery: String?,
    val projectKey: String?,
    val shares: List<ShareRequest>? = null,
)

/**
 * 필터 수정 요청 바디.
 *
 * projectKey는 변경 불가이므로 포함하지 않는다(FR-10). version은 OCC 키.
 *
 * @param name 새 필터 이름.
 * @param aqlQuery 새 AQL 쿼리 문자열.
 * @param version 클라이언트가 보유한 현재 버전(OCC 검사).
 * @param shares 공유 대상 목록. `null`이면 기존 공유 유지. `[]`이면 전체 제거. `[..]`이면 교체.
 */
data class SavedFilterUpdateRequest(
    val name: String?,
    val aqlQuery: String?,
    val version: Long?,
    val shares: List<ShareRequest>? = null,
)

/**
 * 필터 응답 바디.
 *
 * @param isOwner 요청자가 소유자인지 여부(요청자 기준 계산).
 * @param shares 현재 공유 대상 목록.
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
    val shares: List<ShareDto>,
) {
    companion object {
        /**
         * 도메인 [SavedFilter]와 공유 목록을 응답 DTO로 변환한다.
         *
         * @param filter 변환할 도메인 객체.
         * @param shares 해당 필터의 공유 대상 목록.
         * @param actorId 요청자 UUID([isOwner] 계산용).
         */
        fun from(
            filter: SavedFilter,
            shares: List<SavedFilterShare>,
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
                shares = shares.map { ShareDto.from(it) },
            )
    }
}
