// 상태별 이슈 사용량 조회 포트 — 다른 BC 테이블을 읽기만 하는 outbound port

package com.bts.workflow.application.port

import java.util.UUID

/**
 * 상태 키를 쓰고 있는 이슈 수를 센다.
 *
 * ### 왜 포트인가 — BC 격리
 * `issues` 는 issue-tracking 의 테이블이다. project-workflow 가 그 BC 의 코드를 import 하면
 * 격리가 깨진다. 포트를 두고 어댑터가 **읽기 전용 스칼라 count** 만 하는 것이
 * 이 BC 의 선례다(`IssueTypeUsagePort` · `IssueTypeUsageAdapter`).
 *
 * ### 지금은 보수적으로 센다
 * 상태 키가 전역이라 「그 키를 가진 이슈」에는 다른 워크플로우를 쓰는 이슈도 섞인다.
 * 프로젝트 → 스킴 → 워크플로우 3단을 거슬러 정밀하게 좁히는 것은 로드맵 **PR 7** 의 일이다.
 * 그때까지는 과하게 막는 쪽을 택한다 — 덜 막아서 이슈가 「없는 상태」에 남는 것보다 낫다.
 */
interface IssueStatusUsagePort {
    /**
     * 이 상태 키를 현재 값으로 갖는 이슈 수. [projectIds] 안의 프로젝트만 센다.
     *
     * @param statusKey 셀 상태 키.
     * @param projectIds 셀 범위가 되는 프로젝트 id 집합. **비어 있으면 0** 이다 — 「전체」가 아니다.
     * @return 살아 있는(소프트 삭제되지 않은) 이슈 수.
     */
    fun countIssuesInStatus(
        statusKey: String,
        projectIds: Set<UUID>,
    ): Long
}
