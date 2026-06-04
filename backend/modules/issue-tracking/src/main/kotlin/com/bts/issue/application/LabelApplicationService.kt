// 라벨 자동완성 유스케이스 — 권한 가드 + q 정규화 후 IssueRepository에 위임

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 라벨 자동완성 유스케이스 Application Service.
 *
 * - 권한 가드: [IssuePermission.VIEW] + [IssueScope.Global] — 라벨은 특정 이슈/프로젝트가 아닌
 *   시스템 전역 태그 풀에서 완성되므로 Global 스코프를 사용한다.
 *   (ADR docs/adr/2026-06-04-issue-label-freeform-tag-model.md)
 * - q 정규화: null/공백-only → "" (전체 인기순), 앞뒤 공백 제거 후 prefix 위임.
 * - LIMIT: FR4 "최대 10개" — [LABEL_COMPLETION_LIMIT] 상수를 항상 명시 전달.
 *   [IssueRepository.findLabelsByPrefix] 기본값(20)에 의존하지 않는다.
 */
@Service
@Transactional(readOnly = true)
class LabelApplicationService(
    private val repo: IssueRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 입력 prefix로 시작하는 라벨 후보를 최대 [LABEL_COMPLETION_LIMIT]개 반환한다.
     *
     * 글로벌 스코프 사유: 라벨은 특정 프로젝트/이슈에 귀속되지 않는 시스템 전역 자유 태그(freeform tag)이므로
     * 프로젝트 멤버십과 무관하게 VIEW 권한 보유자라면 전체 태그 풀을 조회할 수 있다.
     * (ADR docs/adr/2026-06-04-issue-label-freeform-tag-model.md)
     *
     * @param actor 조회 행위자.
     * @param q 자동완성 prefix. null/공백-only 이면 전체 인기순 반환.
     * @return 빈도 내림차순 라벨 목록. 최대 [LABEL_COMPLETION_LIMIT]개.
     * @throws IssueAccessDeniedException VIEW 권한 없을 때.
     */
    fun completeLabels(actor: ActorId, q: String?): List<String> {
        if (!permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Global)) {
            throw IssueAccessDeniedException(actor, IssuePermission.VIEW, IssueScope.Global)
        }
        val normalized = q?.trim() ?: ""
        log.debug("label_autocomplete actor={} q='{}' normalized='{}'", actor.value, q, normalized)
        return repo.findLabelsByPrefix(normalized, LABEL_COMPLETION_LIMIT)
    }

    private companion object {
        /** FR-IS-09 FR4 — 자동완성 결과 최대 10개. */
        const val LABEL_COMPLETION_LIMIT = 10
    }
}
