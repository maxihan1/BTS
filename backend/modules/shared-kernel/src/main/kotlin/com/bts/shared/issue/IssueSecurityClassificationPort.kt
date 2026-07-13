// 이슈 보안등급 여부 판정 cross-BC 포트 — Slack 채널 브로드캐스트 제외 게이트 전용 (FR-SL-06 PR-B Task 1)

package com.bts.shared.issue

/**
 * 이슈가 보안등급으로 제한되어 있는지 판정하는 cross-BC 읽기 포트.
 */
interface IssueSecurityClassificationPort {
    fun isSecurityRestricted(issueKey: String): Boolean
}
