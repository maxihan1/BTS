// 프로젝트 멤버십 쓰기 포트 — 프로젝트 생성 시 생성자를 멤버로 등록하는 cross-BC 통로

package com.bts.shared.membership

import java.util.UUID

/**
 * 프로젝트 멤버십을 등록하는 cross-BC 쓰기 포트 (FR-PM-10 / FR-PJ-01).
 *
 * project_memberships 는 identity-access BC 소유이고 projects 는 issue-tracking BC 소유다.
 * 프로젝트 생성은 두 테이블에 각각 1행씩 **같은 트랜잭션**으로 써야 하므로(불변식 I1),
 * issue-tracking 이 이 포트를 통해 identity-access 에 쓴다. 직접 테이블 접근은 BC 격리 위반이다.
 *
 * ### default 구현 금지 (fail-closed)
 * [ProjectMembershipPort] 와 같은 정신이다. Bean 이 없으면 부팅이 실패하도록 둔다 — no-op default 를
 * 두면 멤버십 없는 프로젝트가 조용히 만들어지고, 생성자조차 못 들어가는 프로젝트가 된다.
 *
 * ### 새 쓰기 경로 금지
 * 프로젝트 멤버십 쓰기는 이 포트로만 한다.
 *
 * ### BC 경계 규칙
 * 파라미터는 원시 타입(UUID)만 쓴다. identity-access 도메인 타입(ProjectRole 등)을 쓰면 두 단계로
 * 막힌다 — 1차는 Gradle 클래스패스(shared-kernel 에 modules 의존이 0건이라 unresolved reference 로
 * 컴파일이 먼저 죽는다), 2차는 SharedKernelBoundaryArchTest.
 *
 * ### 트랜잭션 계약
 * 구현체는 트랜잭션 경계를 **선언하지 않는다**. 호출자 트랜잭션에 참여하는 것이 이 포트의 존재 이유다.
 *
 * @see ProjectMembershipPort 같은 fail-closed 원칙을 따르는 읽기 짝
 */
interface ProjectMembershipWritePort {
    /**
     * 프로젝트 생성자를 그 프로젝트의 PROJECT_ADMIN 으로 등록한다. 호출자 트랜잭션에 참여한다.
     *
     * 역할은 파라미터가 아니다 — 이 포트의 유일한 용도가 FR-PJ-01 의 생성자 등록이고 역할은 항상
     * PROJECT_ADMIN 이다. 일반 멤버 관리는 identity-access 내부(ProjectMemberController)에 있고
     * 이 포트를 타지 않는다. 역할을 열어두면 "임의 사용자를 임의 프로젝트의 관리자로" 만드는
     * 무가드 프리미티브의 표면만 넓어진다.
     *
     * 멱등이 아니다 — project_memberships 의 UNIQUE(project_id, user_id) 로 2회 호출은 실패한다.
     * 신규 프로젝트에서만 호출되므로 충돌이 성립하지 않는다.
     *
     * @param projectId 멤버십을 등록할 프로젝트 UUID
     * @param userId PROJECT_ADMIN 으로 등록할 생성자 UUID
     */
    fun addCreatorAsAdmin(
        projectId: UUID,
        userId: UUID,
    )
}
