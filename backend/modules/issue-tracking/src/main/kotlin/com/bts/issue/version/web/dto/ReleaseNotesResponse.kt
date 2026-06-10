// FR-VR-04 릴리즈 노트 REST 응답 DTO — ReleaseNotes 도메인 결과의 직렬화 표현

package com.bts.issue.version.web.dto

import com.bts.issue.version.domain.VersionStatus
import com.bts.issue.version.releasenotes.ReleaseNotes
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 릴리즈 노트 조회 응답 DTO.
 *
 * [com.bts.issue.version.web.ReleaseNotesController] 의 `GET .../release-notes` 응답 바디로
 * `DataResponse<ReleaseNotesResponse>` 형태로 래핑되어 반환된다.
 *
 * 메타데이터(헤더 정보)와 [markdown] 본문을 함께 내려 프론트 미리보기에서 둘 다 활용한다.
 *
 * @property versionId 릴리즈 노트 대상 버전 UUID.
 * @property projectKey 버전이 속한 프로젝트 키 (예: `"ATLAS"`). 동명 버전·이슈 0건 시 식별용.
 * @property versionName 버전 이름.
 * @property versionStatus 버전 생명주기 상태. Jackson 이 enum 이름(예: `"RELEASED"`)으로 직렬화한다.
 * @property releaseDate 버전 릴리스 예정일. null 이면 미지정.
 * @property issueCount 릴리즈 노트에 포함된 이슈 수.
 * @property generatedAt 릴리즈 노트 생성 시각 (서버 Clock 기준).
 * @property markdown 생성된 Markdown 본문.
 */
data class ReleaseNotesResponse(
    val versionId: UUID,
    val projectKey: String,
    val versionName: String,
    val versionStatus: VersionStatus,
    val releaseDate: LocalDate?,
    val issueCount: Int,
    val generatedAt: Instant,
    val markdown: String,
) {
    companion object {
        /**
         * [ReleaseNotes] 도메인 결과를 응답 DTO 로 변환한다.
         *
         * @param releaseNotes 서비스가 생성한 릴리즈 노트.
         * @return 직렬화 가능한 [ReleaseNotesResponse].
         */
        fun from(releaseNotes: ReleaseNotes): ReleaseNotesResponse =
            ReleaseNotesResponse(
                versionId = releaseNotes.versionId,
                projectKey = releaseNotes.projectKey,
                versionName = releaseNotes.versionName,
                versionStatus = releaseNotes.versionStatus,
                releaseDate = releaseNotes.releaseDate,
                issueCount = releaseNotes.issueCount,
                generatedAt = releaseNotes.generatedAt,
                markdown = releaseNotes.markdown,
            )
    }
}
