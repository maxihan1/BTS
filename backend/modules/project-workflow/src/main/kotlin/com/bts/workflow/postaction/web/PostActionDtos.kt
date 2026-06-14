// post-action 관리 API 요청/응답 DTO

package com.bts.workflow.postaction.web

import com.bts.workflow.postaction.PostActionRow
import java.util.UUID

/**
 * post-action 생성/수정 요청 바디 DTO.
 *
 * @property type post-action 타입 식별자 (예: "CALL_WEBHOOK").
 * @property config 타입별 설정 Map.
 * @property displayOrder UI 표시 순서. 기본값 0.
 */
data class PostActionRequest(
    val type: String,
    val config: Map<String, Any?> = emptyMap(),
    val displayOrder: Int = 0,
)

/**
 * post-action 응답 DTO.
 *
 * @property id post-action UUID.
 * @property type post-action 타입 식별자.
 * @property config 타입별 설정 Map.
 * @property displayOrder UI 표시 순서.
 */
data class PostActionResponse(
    val id: UUID,
    val type: String,
    val config: Map<String, Any?>,
    val displayOrder: Int,
) {
    companion object {
        /**
         * [PostActionRow] 를 [PostActionResponse] 로 변환한다.
         *
         * @param row 도메인 행 객체.
         * @return 응답 DTO.
         */
        fun from(row: PostActionRow): PostActionResponse =
            PostActionResponse(
                id = row.id,
                type = row.type,
                config = row.config,
                displayOrder = row.displayOrder,
            )
    }
}
