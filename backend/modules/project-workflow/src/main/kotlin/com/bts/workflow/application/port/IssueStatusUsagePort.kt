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
 * ### 세는 범위를 부르는 쪽이 정한다
 * 상태 키가 전역이라 「그 키를 가진 이슈」에는 다른 워크플로우를 쓰는 이슈도 섞인다. 그래서
 * 세는 범위를 프로젝트 id 집합으로 받는다 — 워크플로우 → 스킴 → 프로젝트를 거슬러 올라가는
 * 일은 `ProjectWorkflowSchemeAssignmentRepository.findProjectRefsByWorkflowId` 가 이미 한다.
 * 이 포트는 그 결과를 받아 쓸 뿐 스킴 구조를 다시 풀지 않는다.
 *
 * ### 빈 집합은 「전체」가 아니라 0 이다
 * 이 계약이 뒤집히면 fail-open 이다. 스킴이 아직 안 붙은 워크플로우가 **남의 프로젝트 이슈
 * 때문에** 발행을 못 하게 되고, 편성에서 상태를 빼는 것도 영영 막힌다.
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
