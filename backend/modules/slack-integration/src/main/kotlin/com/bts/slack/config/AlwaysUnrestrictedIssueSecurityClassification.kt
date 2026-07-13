// 이슈 보안등급 판정 non-prod fail-safe 스텁 — 항상 미제한(false), @Profile("!prod") 로 운영 차단 (FR-SL-06 PR-B Task 2)

package com.bts.slack.config

import com.bts.shared.issue.IssueSecurityClassificationPort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("!prod")
class AlwaysUnrestrictedIssueSecurityClassification : IssueSecurityClassificationPort {
    override fun isSecurityRestricted(issueKey: String): Boolean = false
}
