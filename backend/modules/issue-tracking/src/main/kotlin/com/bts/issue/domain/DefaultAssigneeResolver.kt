// 컴포넌트 후보에서 이슈 기본 담당자를 결정하는 순수 도메인 함수

package com.bts.issue.domain

import java.util.UUID

/**
 * 컴포넌트 한 건의 리드 정보.
 *
 * 이슈에 할당된 컴포넌트 후보 목록을 [DefaultAssigneeResolver.resolve] 에 넘길 때 사용한다.
 *
 * @property id 컴포넌트 UUID.
 * @property name 컴포넌트 이름. 프로젝트 내 활성 유니크(ux_components_project_id_name_active).
 * @property leadUserId 리드 사용자 UUID. null 이면 리드 미지정.
 */
data class ComponentLead(
    val id: UUID,
    val name: String,
    val leadUserId: UUID?,
)

/**
 * 컴포넌트 후보 목록에서 이슈 기본 담당자를 결정하는 순수 도메인 규칙.
 *
 * 상태를 보유하지 않는다. 모든 결정은 [resolve] 단일 함수를 통해 이루어진다.
 *
 * **정렬 단일 진실은 이 resolver 의 JVM compareBy.**
 * 컴포넌트명은 프로젝트 내 활성 유니크(ux_components_project_id_name_active 부분 유니크 인덱스)라
 * name 동률은 방어적 dead-path 이다.
 *
 * 근거 ADR: docs/adr/2026-06-05-component-default-assignee-auto-assignment.md
 */
object DefaultAssigneeResolver {
    /**
     * 컴포넌트 후보 목록에서 이슈 기본 담당자를 결정한다.
     *
     * - [current] 가 null 이 아니면 후보를 무시하고 [current] 를 그대로 반환한다 (덮어쓰기 금지 — S3).
     * - [current] 가 null 이면 [candidates] 중 [ComponentLead.leadUserId] 가 null 이 아닌 항목을
     *   name 오름차순, id 오름차순(name 동률 방어) 으로 정렬해 첫 번째 리드를 반환한다 (S1, S4, FR6).
     * - 리드 보유 컴포넌트가 없으면 null 을 반환한다 (S5).
     *
     * @param current 현재 이슈 담당자. null 이면 미할당 상태.
     * @param candidates 이슈에 연결된 컴포넌트 후보 목록.
     * @return 결정된 담당자. 없으면 null.
     */
    fun resolve(
        current: ActorId?,
        candidates: List<ComponentLead>,
        projectLeadUserId: UUID? = null,
    ): ActorId? {
        if (current != null) return current

        val componentLead =
            candidates
                .filter { it.leadUserId != null }
                .sortedWith(compareBy({ it.name }, { it.id }))
                .firstOrNull()
                ?.leadUserId
                ?.let { ActorId(it) }

        return componentLead ?: projectLeadUserId?.let { ActorId(it) }
    }
}
