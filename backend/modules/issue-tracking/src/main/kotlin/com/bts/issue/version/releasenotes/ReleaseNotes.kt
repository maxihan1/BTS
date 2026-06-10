// 릴리즈 노트 생성 결과 출력 data class — Service 가 반환하는 불변 응답 객체
package com.bts.issue.version.releasenotes

import com.bts.issue.version.domain.VersionStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 릴리즈 노트 생성 결과.
 *
 * [com.bts.issue.version.releasenotes.ReleaseNotesService.generate] 의 반환 타입.
 * DB 에 영속하지 않으며, 요청 시마다 생성한다.
 *
 * @property versionId 릴리즈 노트를 생성한 버전 UUID.
 * @property projectKey 프로젝트 키. 예: `"BTS"`.
 * @property versionName 버전 이름. 예: `"v1.0.0"`.
 * @property versionStatus 버전 상태. 예: [VersionStatus.RELEASED].
 * @property releaseDate 버전 릴리스 예정일. null 이면 미지정.
 * @property issueCount Fix Version 으로 연결된 활성 이슈 수.
 * @property generatedAt 릴리즈 노트 생성 시각 (Clock 주입으로 결정적).
 * @property markdown 생성된 Markdown 본문.
 */
data class ReleaseNotes(
    val versionId: UUID,
    val projectKey: String,
    val versionName: String,
    val versionStatus: VersionStatus,
    val releaseDate: LocalDate?,
    val issueCount: Int,
    val generatedAt: Instant,
    val markdown: String,
)
