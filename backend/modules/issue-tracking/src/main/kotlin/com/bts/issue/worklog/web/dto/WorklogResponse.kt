// 워크로그 단건 응답 DTO (FR-TT-01)

package com.bts.issue.worklog.web.dto

import com.bts.issue.worklog.domain.Worklog
import java.time.Instant
import java.util.UUID

/**
 * 워크로그 단건 응답 DTO.
 *
 * [Worklog] 도메인 엔티티를 HTTP 응답 형식으로 변환한다.
 * issueKey 는 도메인 엔티티에 없으므로 컨트롤러에서 path variable 로 전달받아 채운다.
 *
 * @property id              워크로그 UUID PK.
 * @property issueKey        소속 이슈 키 (경로 변수 기반).
 * @property authorId        작성자 UUID.
 * @property timeSpentSeconds 작업 소요 시간 (초).
 * @property startedAt       작업 시작 시각 (ISO-8601).
 * @property comment         선택적 코멘트.
 * @property createdAt       생성 시각 (ISO-8601).
 * @property updatedAt       마지막 수정 시각 (ISO-8601).
 */
data class WorklogResponse(
    val id: UUID,
    val issueKey: String,
    val authorId: UUID,
    val timeSpentSeconds: Int,
    val startedAt: Instant,
    val comment: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * [Worklog] 도메인 엔티티로부터 [WorklogResponse] 를 생성한다.
         *
         * @param worklog 변환할 도메인 엔티티.
         * @param issueKey 소속 이슈 키 문자열.
         * @return 변환된 응답 DTO.
         */
        fun from(
            worklog: Worklog,
            issueKey: String,
        ): WorklogResponse =
            WorklogResponse(
                id = worklog.id,
                issueKey = issueKey,
                authorId = worklog.authorId,
                timeSpentSeconds = worklog.timeSpentSeconds,
                startedAt = worklog.startedAt,
                comment = worklog.comment,
                createdAt = worklog.createdAt,
                updatedAt = worklog.updatedAt,
            )
    }
}
