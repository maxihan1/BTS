// 라벨 자동완성 유스케이스 — q 정규화 후 IssueRepository에 위임 (권한 가드 없음, FR-PM-05 위임)

package com.bts.issue.application

import com.bts.issue.repository.IssueRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 라벨 자동완성 유스케이스 Application Service.
 *
 * - 라벨 자동완성은 인증 사용자 공통 접근으로 두고 별도 권한 가드를 적용하지 않는다.
 *   라벨은 비민감(이미 이슈에 노출된 태그)이고 VIEW 권한도 전반 미결선 상태이므로
 *   권한 정교화는 FR-PM-05에서 재도입한다.
 *   (ADR docs/adr/2026-06-04-issue-label-freeform-tag-model.md §권한)
 * - q 정규화: null/공백-only → "" (전체 인기순), 앞뒤 공백 제거 후 prefix 위임.
 * - LIMIT: FR4 "최대 10개" — [LABEL_COMPLETION_LIMIT] 상수를 항상 명시 전달.
 *   [IssueRepository.findLabelsByPrefix] 기본값(20)에 의존하지 않는다.
 */
@Service
@Transactional(readOnly = true)
class LabelApplicationService(
    private val repo: IssueRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 입력 prefix로 시작하는 라벨 후보를 최대 [LABEL_COMPLETION_LIMIT]개 반환한다.
     *
     * 라벨 자동완성은 인증 사용자 공통 접근이다. 권한 정교화는 FR-PM-05 위임.
     * (ADR docs/adr/2026-06-04-issue-label-freeform-tag-model.md §권한)
     *
     * @param q 자동완성 prefix. null/공백-only 이면 전체 인기순 반환.
     * @return 빈도 내림차순 라벨 목록. 최대 [LABEL_COMPLETION_LIMIT]개.
     */
    fun completeLabels(q: String?): List<String> {
        val normalized = q?.trim() ?: ""
        log.debug("label_autocomplete q='{}' normalized='{}'", q, normalized)
        return repo.findLabelsByPrefix(normalized, LABEL_COMPLETION_LIMIT)
    }

    private companion object {
        /** FR-IS-09 FR4 — 자동완성 결과 최대 10개. */
        const val LABEL_COMPLETION_LIMIT = 10
    }
}
