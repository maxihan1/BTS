// 워크플로우 상태 이관 큐잉 cross-BC 쓰기 포트 — project-workflow → issue-tracking (FR-WF-07 D2)
package com.bts.shared.issue

import java.util.UUID

/**
 * 워크플로우 상태 이관 큐잉 cross-BC 쓰기 포트 (FR-WF-07 D2 · 로드맵 PR 7).
 *
 * default 구현을 두지 않는다(fail-closed) — 어댑터 미결선이면 부팅이 실패해야 한다.
 */
interface IssueStatusMigrationPort {
    /**
     * 상태 이관을 큐잉하고 일괄작업 id 를 돌려준다. 실패는 예외로 던진다.
     *
     * @param cmd 이관 커맨드. actor · 대상 프로젝트 범위 · 상태별 매핑 목록.
     * @return 생성된 일괄작업 id.
     */
    fun enqueueStatusMigration(cmd: StatusMigrationCommand): UUID
}

/**
 * 상태 이관 커맨드 VO.
 *
 * @property actorUserId 실행 주체 UUID.
 * @property projectKeys 대상 프로젝트 범위.
 * @property mappings 빠지는 상태마다 옮길 곳.
 */
data class StatusMigrationCommand(
    val actorUserId: UUID,
    val projectKeys: Set<String>,
    val mappings: List<StatusMigrationMapping>,
)

/**
 * 상태 1개의 이관 대상 매핑 VO.
 *
 * @property fromStatusKey 사라지는 상태 키.
 * @property toStatusKey 옮겨 갈 상태 키.
 */
data class StatusMigrationMapping(
    val fromStatusKey: String,
    val toStatusKey: String,
)
