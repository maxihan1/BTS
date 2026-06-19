// 프로젝트 멤버/관리자 수신자 cross-BC 조회 포트 (notification 알림 확장 대상자 해석용)

package com.bts.shared.issue

import java.util.UUID

/**
 * 프로젝트 전체 멤버·관리자 수신자 cross-BC 조회 포트 (FR-NT-03 Task 1).
 *
 * notification BC 가 프로젝트 범위 이벤트(예: 프로젝트 알림, 멘션 후보군) 발생 시
 * 알림 대상자를 결정하기 위해 이 인터페이스를 호출한다.
 * 구현체는 identity-access BC 또는 project-workflow BC 가 제공하며,
 * shared-kernel 을 통해 BC 간 직접 결합을 제거한다.
 *
 * ### fail-safe 방향
 * 수신자 조회 실패(adapter 부재, 프로젝트 미존재)는 빈 수신자 반환 = 알림 누락으로 처리한다.
 * 알림 누락이 과발송보다 안전하다 — [IssueRecipientLookupPort] 와 동일한 설계 원칙.
 *
 * ### BC 경계 규칙
 * identity-access / project-workflow 타입을 이 interface 에 사용하면
 * `SharedKernelBoundaryArchTest` 가 빌드를 차단한다.
 * 파라미터는 원시 타입(String, UUID, List)만 사용한다.
 */
interface ProjectRecipientLookupPort {
    /**
     * 주어진 프로젝트 키의 멤버·관리자 UUID 목록을 반환한다.
     *
     * 프로젝트가 존재하지 않거나 adapter 가 부재한 경우 빈 수신자([ProjectRecipients.empty])를 반환한다.
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * @param projectKey "PROJ" 형태의 프로젝트 키
     * @return 멤버·관리자 UUID 목록 (조회 불가 시 빈 리스트)
     */
    fun findProjectRecipients(projectKey: String): ProjectRecipients = ProjectRecipients.empty()
}

/**
 * 프로젝트 알림 수신자 정보.
 *
 * @param memberIds 프로젝트 일반 멤버 UUID 목록. 조회 불가 시 빈 리스트.
 * @param adminIds 프로젝트 관리자 UUID 목록. 조회 불가 시 빈 리스트.
 */
data class ProjectRecipients(
    val memberIds: List<UUID>,
    val adminIds: List<UUID>,
) {
    companion object {
        /** adapter 부재 또는 프로젝트 미존재 시 반환하는 fail-safe 빈 수신자. */
        fun empty(): ProjectRecipients =
            ProjectRecipients(
                memberIds = emptyList(),
                adminIds = emptyList(),
            )
    }
}
