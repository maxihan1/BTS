// IssueTypeCatalog outbound adapter — search-export-import BC 의 이슈타입 전체 목록 조회 요청을 issue-tracking DB 에서 처리

package com.bts.issue.type.adapter.outbound

import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeCatalog
import com.bts.shared.issue.IssueTypeRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [IssueTypeCatalog] issue-tracking BC 구현체.
 *
 * search-export-import BC 의 Import 값 매핑 위저드가 프로젝트 이슈타입 후보 목록을 얻기 위해
 * [IssueTypeCatalog.listTypes] 를 호출하면, 이 어댑터가 issue-tracking 의
 * [IssueTypeRepository] 를 통해 DB 에서 전체 이슈타입을 조회하고 [IssueTypeRef] 로 변환해 반환한다.
 *
 * [IssueTypeLookupAdapter] 와 자매 어댑터다 — [IssueTypeLookupAdapter] 는 ID 목록으로 조회하는
 * `IssueTypeLookupPort`(project-workflow consumer) 구현체이고, 이 어댑터는 전체 목록을
 * 조회하는 [IssueTypeCatalog](search-export-import consumer) 구현체다.
 *
 * ## 책임 분리
 *
 * - 조회 로직은 [IssueTypeRepository.findAll] 이 담당한다 (active 필터, is_standard 무관 전체 반환).
 * - 본 구현체는 SPI 경계 변환만 수행한다 — 도메인 [com.bts.issue.type.domain.IssueType] → [IssueTypeRef]
 *   (key, name 두 필드만 노출).
 *
 * ## BC 격리
 *
 * 이 어댑터는 issue-tracking BC 내부에 위치하므로 [IssueTypeRepository] 를 직접 참조할 수 있다.
 * search-export-import BC 는 [IssueTypeCatalog] interface 만 의존하며, 도메인 엔티티
 * [com.bts.issue.type.domain.IssueType] 을 직접 import 하지 않는다.
 *
 * ## 결정 근거
 *
 * `docs/decisions/2026-05-27-shared-kernel-extraction.md`,
 * `docs/adr/2026-05-28-workflow-scheme-frontend-view-layer-cross-bc-lookup.md`
 * ([com.bts.shared.workflow.WorkflowStateCatalog] 미러 패턴).
 *
 * @param issueTypeRepository issue_types 테이블 read-only jOOQ repository.
 * @see IssueTypeCatalog
 * @see IssueTypeRef
 */
@Component
class IssueTypeCatalogAdapter(
    private val issueTypeRepository: IssueTypeRepository,
) : IssueTypeCatalog {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 전역 이슈타입 전체 목록을 [IssueTypeRef] 리스트로 반환한다.
     *
     * - 활성(소프트 삭제되지 않은) 이슈타입만 반환한다 ([IssueTypeRepository.findAll] 위임).
     * - 이슈타입이 없으면 빈 리스트를 반환한다.
     *
     * @return [IssueTypeRef] 리스트.
     */
    @Transactional(readOnly = true)
    override fun listTypes(): List<IssueTypeRef> {
        val types = issueTypeRepository.findAll()
        log.debug("listTypes returned {} issue types", types.size)
        return types.map { IssueTypeRef(key = it.key.value, name = it.name) }
    }
}
