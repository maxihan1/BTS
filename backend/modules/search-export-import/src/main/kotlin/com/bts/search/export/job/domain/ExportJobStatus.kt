// 비동기 Export 작업 생명주기 상태 enum — PENDING→RUNNING→COMPLETED / PENDING|RUNNING→FAILED

package com.bts.search.export.job.domain

/**
 * [ExportJob] 상태 머신의 상태.
 *
 * 허용 전환 다이어그램.
 * ```
 * PENDING ──► RUNNING ──► COMPLETED
 *    │            │
 *    └────────────┴──► FAILED
 * ```
 *
 * - [PENDING] → [RUNNING]: 워커가 작업을 클레임(claim)할 때.
 * - [RUNNING] → [COMPLETED]: 직렬화 + MinIO 업로드 완료 시.
 * - [PENDING] → [FAILED]: 워커 클레임 전 인프라 오류 시.
 * - [RUNNING] → [FAILED]: 직렬화/업로드 중 오류 시.
 * - [COMPLETED], [FAILED] 는 종단(terminal) 상태 — 추가 전환 불가.
 *
 * `issue-tracking` `BulkOperationStatus` 와 동일한 4-상태 패턴을 미러한다.
 *
 * @see ExportJob.status 도메인 집계체 상태 필드
 * @see ExportJob 도메인 집계체
 */
enum class ExportJobStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    ;

    /**
     * [target] 으로 전환을 시도한다.
     *
     * 허용된 전환이면 [target] 을 반환한다.
     * 허용되지 않은 전환(역전환, 종단 재전환, 중간 건너뛰기)이면 [IllegalStateException] 을 던진다.
     *
     * @param target 전환할 대상 상태.
     * @return 전환 성공 시 [target].
     * @throws IllegalStateException 허용되지 않은 전환인 경우.
     */
    fun transitionTo(target: ExportJobStatus): ExportJobStatus {
        val allowed = ALLOWED_TRANSITIONS[this].orEmpty()
        check(target in allowed) {
            "ExportJobStatus 전환 거부: $this → $target (허용: $allowed)"
        }
        return target
    }

    companion object {
        /**
         * 허용된 전환 맵.
         *
         * 종단 상태([COMPLETED], [FAILED])는 키 자체가 없으므로 `emptySet()` 폴백으로 처리된다.
         */
        private val ALLOWED_TRANSITIONS: Map<ExportJobStatus, Set<ExportJobStatus>> =
            mapOf(
                PENDING to setOf(RUNNING, FAILED),
                RUNNING to setOf(COMPLETED, FAILED),
            )
    }
}
