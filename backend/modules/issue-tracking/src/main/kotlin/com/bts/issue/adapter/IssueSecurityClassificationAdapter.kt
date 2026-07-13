// IssueSecurityClassificationPort prod 어댑터 — issueRepository 직접 조회로 보안등급 여부 판정 (FR-SL-06 PR-B Task 1)

package com.bts.issue.adapter

import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import com.bts.shared.issue.IssueSecurityClassificationPort
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [IssueSecurityClassificationPort] 의 issue-tracking BC prod 구현체 (FR-SL-06 PR-B Task 1).
 *
 * [IssueRepository.findByKey] (활성 이슈만, `deleted_at IS NULL`)를 직접 호출해
 * `security_level_id` 컬럼의 null 여부만 확인한다. 뷰어별 가시성 강제 read 경로
 * ([com.bts.issue.application.IssueApplicationService.findByKey] 의 `assertViewIssueOrNotFound` 등)를
 * 재사용하지 않는다 — 이 판정은 특정 뷰어의 VIEW 권한이 아니라 "이슈 자체가 보안등급으로
 * 제한되어 있는가"라는 뷰어 무관 이진 질문이기 때문이다([IssueSecurityClassificationPort] 클래스
 * KDoc "왜 새 포트가 필요한가" 절 참조).
 *
 * ## fail-closed — 미존재/소프트 삭제는 제한(true)
 *
 * [IssueRepository.findByKey] 가 null 을 반환하면(이슈 미존재 또는 소프트 삭제) 판정 불명으로 보아
 * `true`(제한)를 반환한다. `?.securityLevelId != null` 형태로 작성하면 조회 실패 시 `null != null`이
 * `false`(제한 없음)로 오판되어 fail-open 이 되므로, 반드시 조회 실패를 먼저 분기해 `true`를 명시
 * 반환한다.
 */
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
