// 일괄 작업 payload sealed class — BULK_EDIT(Edit) / BULK_TRANSITION(Transition) /
// STATUS_MIGRATION(StatusMigration) 타입별 파라미터를 안전하게 표현

package com.bts.issue.bulk.domain

import com.bts.shared.issue.StatusMigrationTypeFilter
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

/**
 * 일괄 작업의 타입별 파라미터를 표현하는 sealed class.
 *
 * - [Edit]: BULK_EDIT 작업의 필드 변경 파라미터.
 * - [Transition]: BULK_TRANSITION 작업의 전환 대상 상태 키.
 * - [StatusMigration]: STATUS_MIGRATION 작업의 상태 매핑과 프로젝트 범위.
 *
 * Repository 는 이 값을 JSONB 컬럼에 직렬화하여 저장하고, 조회 시 복원한다.
 * PR2 워커는 이 payload 를 읽어 실제 이슈 갱신을 수행한다.
 */
sealed class BulkOperationPayload {
    /**
     * BULK_EDIT 작업 파라미터.
     *
     * 각 필드는 null=무변경 (merge-patch 시맨틱).
     *
     * @property priority 변경할 우선순위. non-null 이면 1..5 범위. null 이면 무변경.
     * @property impact 변경할 영향도. non-null 이면 1..3 범위. null 이면 무변경.
     */
    data class Edit(
        val priority: Int?,
        val impact: Int?,
    ) : BulkOperationPayload()

    /**
     * BULK_TRANSITION 작업 파라미터.
     *
     * 일괄 전환 대상 전체에 동일한 resolutionId 를 적용한다.
     * null 이면 각 이슈의 resolution_id 를 clear (비DONE 전환 시맨틱과 동일).
     *
     * @property toStateKey 전환할 대상 상태 키. 비어 있으면 안 된다.
     * @property resolutionId DONE 상태로 전환할 때 지정하는 해결책 UUID.
     *   null 이면 resolution_id clear. 전체 일괄에 동일하게 적용된다.
     */
    data class Transition(
        val toStateKey: String,
        val resolutionId: UUID? = null,
        val assigneeId: UUID? = null,
        val comment: String? = null,
    ) : BulkOperationPayload()

    /**
     * STATUS_MIGRATION 작업 파라미터.
     *
     * 워크플로우에서 빠지는 상태에 남은 이슈를 어디로 옮길지 정의한다.
     * 대상 이슈 목록은 큐잉 시점에 확정되지 않는다 — 워커가 실행 시점에
     * [mappings] 의 출발 상태와 [projectScopes] 로 이슈를 다시 조회한다.
     *
     * @property mappings 출발 상태 키 → 대상 상태 키. 항목의 현재 상태로 조회해 대상을 정한다.
     * @property projectScopes 이관 대상 프로젝트 키 → 그 프로젝트에서 옮길 이슈 타입. 상태 키는 전역이라
     *   프로젝트 범위가 없으면 남의 프로젝트 이슈까지, 타입 범위가 없으면 같은 프로젝트의 다른
     *   워크플로우 이슈까지 함께 옮겨진다(WF-07-2). 계약은 [StatusMigrationTypeFilter] KDoc 이 정본이다.
     */
    @JsonIgnoreProperties(value = ["projectKeys"], allowGetters = true)
    data class StatusMigration(
        val mappings: Map<String, String>,
        val projectScopes: Map<String, StatusMigrationTypeFilter>,
    ) : BulkOperationPayload() {
        /**
         * [projectScopes] 의 키. **직렬화 전용**이다 — JSON 에만 실리고 읽을 때는 버린다.
         *
         * ### ★지우면 두 소비자가 조용히 깨진다
         * WF-07-2 이전 payload 모양(`projectKeys` 배열)을 직접 읽는 곳이 이 BC 밖에 둘 있다.
         * - project-workflow `MigrationInFlightAdapter` — `payload -> 'projectKeys'` 를 SQL 로 읽는다. 키가
         *   없으면 NULL → 0행 → 「진행 중 이관 없음」이 되어 모순되는 이관 2건이 함께 큐에 얹힌다(fail-open).
         * - apps/web `statusMigrationPayloadSchema` — `projectKeys` 가 필수라 없으면 진행률 폴링이 ZodError 로 멈춘다.
         *
         * 이 필드가 그 둘을 이 BC 안에서 지킨다. `BulkOperationRepositoryTest` 가 저장된 JSON 에 이 키가
         * 있는지 잰다.
         */
        val projectKeys: Set<String>
            get() = projectScopes.keys
    }
}
