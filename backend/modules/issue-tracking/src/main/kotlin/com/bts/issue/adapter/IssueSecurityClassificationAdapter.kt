// IssueSecurityClassificationPort prod 어댑터 — issueRepository 직접 조회로 보안등급 여부 판정 (FR-SL-06 PR-B Task 1)

package com.bts.issue.adapter

import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import com.bts.shared.issue.IssueSecurityClassificationPort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Profile("prod")
class IssueSecurityClassificationAdapter(
    private val issueRepository: IssueRepository,
) : IssueSecurityClassificationPort {
    @Transactional(readOnly = true)
    override fun isSecurityRestricted(issueKey: String): Boolean {
        val key = IssueKey(issueKey)
        val issue = issueRepository.findByKey(key) ?: return true
        return issue.securityLevelId != null
    }
}
