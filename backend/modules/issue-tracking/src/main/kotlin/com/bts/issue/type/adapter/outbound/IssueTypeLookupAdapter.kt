// IssueTypeLookupPort outbound adapter — project-workflow BC 의 IssueType 조회 요청을 issue-tracking DB 에서 처리

package com.bts.issue.type.adapter.outbound

import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeRef
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [IssueTypeLookupPort] issue-tracking BC 구현체.
 *
 * project-workflow BC 가 워크플로우 스킴 frontend 뷰 layer 에서 IssueType 이름을 표시하기 위해
 * [IssueTypeLookupPort.lookup] 을 호출하면, 이 어댑터가 issue-tracking 의
 * [IssueTypeRepository] 를 통해 DB 에서 실제 값을 조회하고 [IssueTypeRef] 로 변환해 반환한다.
 *
 * ## BC 격리
 *
 * 이 어댑터는 issue-tracking BC 내부에 위치하므로 [IssueTypeRepository] 를 직접 참조할 수 있다.
 * project-workflow BC 는 [IssueTypeLookupPort] interface 만 의존하며, 도메인 엔티티
 * [com.bts.issue.type.domain.IssueType] 을 직접 import 하지 않는다.
 *
 * ## 결정 근거
 *
 * docs/adr/2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup.md
 *
 * @param issueTypeRepository issue_types 테이블 read-only jOOQ repository.
 * @see IssueTypeLookupPort
 * @see IssueTypeRef
 */
@Component
class IssueTypeLookupAdapter(
    private val issueTypeRepository: IssueTypeRepository,
) : IssueTypeLookupPort {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 요청된 IssueType ID 목록에 해당하는 [IssueTypeRef] map 을 반환한다.
     *
     * - 입력이 빈 리스트이면 빈 map 을 즉시 반환한다 (DB 호출 없음).
     * - 미존재 ID 는 결과 map 에서 제외한다 (예외 발생 없음).
     *
     * @param ids 조회할 이슈 타입 ID 목록.
     * @return ID → [IssueTypeRef] 매핑. 미존재 ID 는 포함되지 않는다.
     */
    @Transactional(readOnly = true)
    override fun lookup(ids: List<IssueTypeId>): Map<IssueTypeId, IssueTypeRef> {
        if (ids.isEmpty()) {
            log.debug("lookup called with empty ids — returning empty map without DB call")
            return emptyMap()
        }

        log.debug("lookup ids={}", ids)

        return ids.mapNotNull { id ->
            val issueType = issueTypeRepository.findById(id)
            if (issueType == null) {
                log.debug("IssueType not found for id={}, excluding from result", id.value)
                null
            } else {
                id to IssueTypeRef(key = issueType.key.value, name = issueType.name)
            }
        }.toMap()
    }
}
