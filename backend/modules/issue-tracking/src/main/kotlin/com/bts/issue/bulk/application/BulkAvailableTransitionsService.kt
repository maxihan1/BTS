// issueKeys 목록을 받아 키별 가용 전이를 best-effort 조회하고 교집합을 반환하는 서비스

package com.bts.issue.bulk.application

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.shared.workflow.AvailableTransitionView
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 여러 이슈에 공통으로 적용 가능한 전이 목록을 조회하는 Application Service.
 *
 * 각 issueKey 에 대해 [IssueApplicationService.availableTransitions] 를 best-effort 로 호출한다.
 * [IssueNotFoundException], [IssueWorkflowNotConfiguredException], [IssueAccessDeniedException] 는
 * unresolvedIssueKeys 에 수집하고 나머지 성공 항목의 교집합을 [TransitionIntersection.intersect] 로 계산한다.
 *
 * 예상치 못한 예외(위 세 가지 외)는 그대로 전파된다.
 *
 * @param issueService 단일 이슈 가용 전이 조회 서비스
 */
@Service
class BulkAvailableTransitionsService(
    private val issueService: IssueApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * issueKeys 목록의 공통 가용 전이를 계산한다.
     *
     * @param actor 요청 행위자
     * @param issueKeys 조회 대상 이슈 키 문자열 목록
     * @return 교집합 전이 목록과 조회 실패 키 목록을 담은 결과
     */
    @Transactional(readOnly = true)
    fun availableCommonTransitions(
        actor: ActorId,
        issueKeys: List<String>,
    ): BulkAvailableTransitionsResult {
        val perIssueTransitions = mutableListOf<List<AvailableTransitionView>>()
        val unresolvedKeys = mutableListOf<String>()

        for (key in issueKeys) {
            try {
                val transitions = issueService.availableTransitions(actor, IssueKey(key))
                perIssueTransitions.add(transitions)
            } catch (e: IssueNotFoundException) {
                log.debug("이슈를 찾을 수 없어 unresolved 처리: key={}, reason={}", key, e.message)
                unresolvedKeys.add(key)
            } catch (e: IssueWorkflowNotConfiguredException) {
                log.debug("워크플로우 미설정으로 unresolved 처리: key={}, reason={}", key, e.message)
                unresolvedKeys.add(key)
            } catch (e: IssueAccessDeniedException) {
                log.debug("접근 권한 없어 unresolved 처리: key={}, reason={}", key, e.message)
                unresolvedKeys.add(key)
            }
        }

        val commonTransitions = TransitionIntersection.intersect(perIssueTransitions)

        return BulkAvailableTransitionsResult(
            transitions = commonTransitions,
            unresolvedIssueKeys = unresolvedKeys,
        )
    }
}

/**
 * [BulkAvailableTransitionsService.availableCommonTransitions] 의 반환 결과.
 *
 * @param transitions 모든 조회 성공 이슈에 공통으로 존재하는 가용 전이 목록
 * @param unresolvedIssueKeys 조회에 실패한(미존재·워크플로우 미설정·접근 불가) 이슈 키 목록
 */
data class BulkAvailableTransitionsResult(
    val transitions: List<AvailableTransitionView>,
    val unresolvedIssueKeys: List<String>,
)
