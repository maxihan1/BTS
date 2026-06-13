// LinkedIssueRow — issue_links + issues 단일 JOIN 결과 읽기 모델 (N+1 방지용)

package com.bts.issue.link.repository

import com.bts.issue.link.domain.LinkType
import java.util.UUID

/**
 * `issue_links` 와 `issues` 를 단일 LEFT JOIN 해서 반환하는 읽기 전용 결과 행.
 *
 * [IssueLinkRepository.findOutwardWithIssue] 및 [IssueLinkRepository.findInwardWithIssue] 가
 * 이 타입으로 반환한다.
 *
 * ## N+1 방지
 * 한 번의 JOIN 쿼리로 상대 이슈의 핵심 필드(key/summary/currentStateKey)를 함께 가져온다.
 * 소프트삭제된 상대 이슈(`deleted_at IS NOT NULL`)는 쿼리 단에서 제외하므로 서비스에서 추가 필터가 불필요하다.
 *
 * @property linkId `issue_links.id` — 링크 식별자.
 * @property linkType 링크 유형. [LinkType] 참조.
 * @property otherIssueId 상대 이슈의 내부 UUID.
 * @property otherIssueKey 상대 이슈의 이슈 키 문자열 (예: "BTS-2").
 * @property otherIssueSummary 상대 이슈의 제목.
 * @property otherCurrentStateKey 상대 이슈의 현재 워크플로우 상태 키.
 */
data class LinkedIssueRow(
    val linkId: Long,
    val linkType: LinkType,
    val otherIssueId: UUID,
    val otherIssueKey: String,
    val otherIssueSummary: String,
    val otherCurrentStateKey: String,
)
