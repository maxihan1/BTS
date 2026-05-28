// 워크플로우 스킴 frontend 뷰 layer 용 issue-tracking BC IssueType lookup outbound port

package com.bts.workflow.scheme.application.port

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeRef

/**
 * 워크플로우 스킴 frontend 뷰 layer 용 IssueType lookup outbound port.
 *
 * project-workflow BC 가 매핑 응답(MappingResponse) 에 IssueType 이름을 포함하기 위해
 * issue-tracking BC 의 IssueType 정보를 read-only 로 조회할 때 사용한다.
 *
 * ## BC 격리 규칙
 *
 * project-workflow 가 issue-tracking 내부 패키지를 직접 import 하면 ArchUnit 빌드 실패가 발생한다.
 * 이 인터페이스는 project-workflow 가 선언하고,
 * issue-tracking 의 [com.bts.issue.type.adapter.outbound.IssueTypeLookupAdapter] 가 구현을 제공한다.
 * 반환 타입은 shared-kernel [IssueTypeRef] 를 사용하므로 도메인 엔티티 직접 import 가 0건이다.
 *
 * ## 결정 근거
 *
 * docs/adr/2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup.md
 *
 * @see IssueTypeRef
 * @see com.bts.issue.type.adapter.outbound.IssueTypeLookupAdapter
 */
interface IssueTypeLookupPort {
    /**
     * 요청된 IssueType ID 목록에 해당하는 [IssueTypeRef] map 을 반환한다.
     *
     * - 입력이 빈 리스트이면 빈 map 을 즉시 반환한다 (DB 호출 없음).
     * - 미존재 ID 는 결과 map 에서 제외한다 (예외 발생 없음).
     *
     * @param ids 조회할 이슈 타입 ID 목록.
     * @return ID → [IssueTypeRef] 매핑. 미존재 ID 는 포함되지 않는다.
     */
    fun lookup(ids: List<IssueTypeId>): Map<IssueTypeId, IssueTypeRef>
}
