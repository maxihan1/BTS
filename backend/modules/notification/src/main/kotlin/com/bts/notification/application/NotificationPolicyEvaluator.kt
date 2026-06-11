// 알림 정책 평가 엔진 — projectKey 우선(replace) 방식으로 활성 정책을 선별해 PolicyMatch 목록으로 반환

package com.bts.notification.application

import com.bts.notification.domain.NotificationEventType
import com.bts.notification.repository.NotificationPolicyRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 알림 정책 평가 엔진 서비스.
 *
 * 이벤트 유형과 프로젝트 키를 받아 발송 대상([PolicyMatch]) 목록을 결정한다.
 *
 * ## 평가 알고리즘 (spec §6 — projectKey 우선, replace 방식)
 * 1. [projectKey] != null 이면 해당 프로젝트의 정책 행을 **enabled 무관** 전체 조회한다.
 *    - 행이 1개 이상이면(= 프로젝트가 독자 관리) → 그 중 enabled=true 만 매핑해 반환.
 *      전역 정책은 절대 참조하지 않는다(replace 의미).
 *      전부 enabled=false 면 빈 목록 반환 — 전역 fallback 없음.
 *    - 행이 0개이면 → 2번(전역) 폴백.
 * 2. [projectKey] == null 이거나 프로젝트 행이 0개이면 전역(projectKey=null) 정책 중
 *    enabled=true 만 매핑해 반환한다.
 * 3. 어떤 경우든 enabled=true 정책이 0개이면 빈 목록을 반환한다 — 예외 없음.
 *
 * @param repository notification_policies 테이블 접근 repository
 */
@Service
class NotificationPolicyEvaluator(
    private val repository: NotificationPolicyRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [eventType] 과 [projectKey] 를 기반으로 활성 알림 정책을 평가한다.
     *
     * 반환된 [PolicyMatch] 목록의 각 항목은 독립된 발송 작업(역할×채널)에 해당한다.
     * 목록이 비어 있으면 해당 이벤트에 대해 발송할 대상이 없음을 의미하며 예외는 던지지 않는다.
     *
     * @param eventType 평가할 이벤트 유형
     * @param projectKey 프로젝트 범위. null 이면 전역 정책만 평가한다.
     * @return 활성 정책에서 추출한 [PolicyMatch] 목록 (비어 있을 수 있음)
     */
    @Transactional(readOnly = true)
    fun evaluate(eventType: NotificationEventType, projectKey: String?): List<PolicyMatch> {
        if (projectKey != null) {
            val projectPolicies = repository.findByEventTypeAndProjectKey(eventType.wireValue, projectKey)

            if (projectPolicies.isNotEmpty()) {
                // 프로젝트가 독자 관리 중 — replace 방식. 전역은 무시.
                val matches = projectPolicies
                    .filter { it.enabled }
                    .map { PolicyMatch(recipientRole = it.recipientRole, channel = it.channel) }

                log.debug(
                    "프로젝트 정책 평가 완료 — projectKey={}, eventType={}, 전체={}, 활성={}",
                    projectKey, eventType.wireValue, projectPolicies.size, matches.size,
                )
                return matches
            }

            log.debug(
                "프로젝트 정책 없음 — 전역 폴백 — projectKey={}, eventType={}",
                projectKey, eventType.wireValue,
            )
        }

        // 전역 정책 평가 (projectKey==null 이거나 프로젝트 행 0개)
        val globalMatches = repository.findByEventTypeAndProjectKey(eventType.wireValue, null)
            .filter { it.enabled }
            .map { PolicyMatch(recipientRole = it.recipientRole, channel = it.channel) }

        log.debug(
            "전역 정책 평가 완료 — eventType={}, 활성={}",
            eventType.wireValue, globalMatches.size,
        )
        return globalMatches
    }
}
