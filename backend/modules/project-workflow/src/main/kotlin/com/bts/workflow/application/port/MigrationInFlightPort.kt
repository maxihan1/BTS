// 진행 중인 상태 이관 유무 조회 포트 — 다른 BC 테이블을 읽기만 하는 outbound port

package com.bts.workflow.application.port

/**
 * 아직 끝나지 않은 상태 이관이 있는지 묻는다.
 *
 * ### 왜 포트인가 — BC 격리
 * `bulk_operations` 는 issue-tracking 의 테이블이다. 포트를 두고 어댑터가 **읽기 전용 스칼라**만
 * 하는 것이 이 BC 의 선례다([IssueStatusUsagePort] · `IssueTypeUsagePort`).
 *
 * ### 왜 shared-kernel 포트를 늘리지 않는가
 * `IssueStatusMigrationPort` KDoc 이 「조회 메서드를 만들지 않는다 — 두 번째 경로를 만들면 둘이
 * 서로를 검사하지 않는다」로 못박았고, `shared-kernel` 모듈 전체가 T3 표면이라 이 PR(T2)이
 * 건드릴 수 없다. BC 로컬 포트는 그 둘 다에 걸리지 않는다.
 *
 * ### 범위가 프로젝트 **키**인 이유
 * `bulk_operations` 에는 `project_id` 컬럼이 없다. 이관의 범위는 `payload` JSONB 의
 * `projectKeys` 문자열 배열로만 존재하므로, 없는 축(UUID)으로 포트를 열면 어댑터가 그 자리에서
 * 막힌다. 커맨드가 싣는 것과 같은 축으로 묻는다.
 */
interface MigrationInFlightPort {
    /**
     * [projectKeys] 중 하나라도 겹치는 **끝나지 않은**(PENDING·RUNNING) 상태 이관이 있는가.
     *
     * COMPLETED·FAILED 는 끝난 것이다. FAILED 까지 막으면 한 번 실패한 워크플로우가 영영 다시
     * 이관하지 못한다 — 재시도야말로 실패 뒤에 해야 할 일이다.
     *
     * @param projectKeys 이관하려는 범위. **비어 있으면 false** 다 — 「전체」가 아니다.
     * @return 겹치는 진행 중 이관이 하나라도 있으면 true.
     */
    fun hasInFlightMigration(projectKeys: Set<String>): Boolean
}
