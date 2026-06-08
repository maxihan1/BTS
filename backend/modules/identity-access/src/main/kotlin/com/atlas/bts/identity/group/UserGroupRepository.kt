// user_groups + group_memberships 테이블 접근 인터페이스 (FR-PM-09 Task 3)

package com.atlas.bts.identity.group

import java.util.UUID

/**
 * [UserGroup]과 그룹별 멤버 수를 함께 담는 조회 전용 뷰.
 *
 * 목록 화면처럼 그룹 본문과 멤버 규모를 한 번에 보여줘야 할 때 사용하며,
 * 멤버 수는 영속 계층이 스칼라 서브쿼리로 계산한다(LEFT JOIN cartesian product 회피).
 *
 * @property group 그룹 본문(id/타임스탬프가 채워진 영속 상태).
 * @property memberCount 해당 그룹에 속한 멤버 수(0 이상).
 */
data class UserGroupWithCount(
    val group: UserGroup,
    val memberCount: Int,
)

/**
 * `user_groups` / `group_memberships` 테이블 접근 인터페이스 (FR-PM-09 Task 3).
 *
 * 전역 사용자 그룹의 CRUD 와 멤버십(N:M) 추가/제거/조회를 담당한다.
 * 멤버십 연산은 멱등하게 설계되어, 중복 추가/없는 멤버 제거가 예외를 던지지 않는다.
 *
 * 트랜잭션 경계는 상위 서비스가 소유한다. 본 인터페이스 구현체는 자체 트랜잭션을 열지 않는다.
 *
 * 구현체: [JdbcUserGroupRepository].
 */
interface UserGroupRepository {
    /**
     * 새 그룹을 저장하고 DB 가 채운 id/타임스탬프까지 포함한 [UserGroup]을 반환한다.
     *
     * @param name 그룹 이름(정규화 완료 가정). `user_groups.name` UNIQUE 제약 대상.
     * @param description 그룹 설명(nullable).
     * @return id/createdAt/updatedAt 이 채워진 영속 [UserGroup].
     * @throws org.springframework.dao.DuplicateKeyException [name] 이 이미 존재하는 경우.
     */
    fun create(
        name: String,
        description: String?,
    ): UserGroup

    /**
     * id 로 단건 그룹을 조회한다.
     *
     * @param id 그룹 식별자.
     * @return 일치하는 [UserGroup], 없으면 `null`.
     */
    fun findById(id: UUID): UserGroup?

    /**
     * 모든 그룹을 멤버 수와 함께 조회한다.
     *
     * @return 각 그룹과 그 멤버 수를 담은 [UserGroupWithCount] 목록. 그룹이 없으면 빈 목록.
     */
    fun findAll(): List<UserGroupWithCount>

    /**
     * 그룹의 이름/설명을 갱신하고 갱신된 [UserGroup]을 반환한다.
     *
     * @param id 갱신할 그룹 식별자.
     * @param name 새 이름.
     * @param description 새 설명(nullable).
     * @return 갱신된 [UserGroup], 해당 id 가 없으면 `null`.
     * @throws org.springframework.dao.DuplicateKeyException [name] 이 다른 그룹과 충돌하는 경우.
     */
    fun update(
        id: UUID,
        name: String,
        description: String?,
    ): UserGroup?

    /**
     * 그룹을 삭제한다. 멤버십은 FK ON DELETE CASCADE 로 함께 제거된다.
     *
     * @param id 삭제할 그룹 식별자.
     * @return 실제로 삭제된 행이 있으면 `true`, 없으면 `false`.
     */
    fun delete(id: UUID): Boolean

    /**
     * 그룹에 멤버를 추가한다(멱등).
     *
     * 이미 멤버이면 `ON CONFLICT DO NOTHING` 으로 아무 변화 없이 반환한다.
     *
     * @param groupId 그룹 식별자.
     * @param userId 추가할 사용자 식별자(`users.id`).
     */
    fun addMember(
        groupId: UUID,
        userId: UUID,
    )

    /**
     * 그룹에서 멤버를 제거한다(멱등).
     *
     * 멤버가 아니어도 예외 없이 no-op 으로 반환한다.
     *
     * @param groupId 그룹 식별자.
     * @param userId 제거할 사용자 식별자.
     */
    fun removeMember(
        groupId: UUID,
        userId: UUID,
    )

    /**
     * 그룹에 속한 모든 멤버의 사용자 식별자를 반환한다.
     *
     * @param groupId 그룹 식별자.
     * @return 멤버 사용자 식별자 목록. 멤버가 없으면 빈 목록.
     */
    fun listMemberIds(groupId: UUID): List<UUID>

    /**
     * 그룹 존재 여부를 반환한다.
     *
     * @param id 그룹 식별자.
     * @return 존재하면 `true`, 아니면 `false`.
     */
    fun existsById(id: UUID): Boolean

    /**
     * 사용자가 특정 그룹의 멤버인지 단건 확인한다(FR-PM-06 PR-B Task 7).
     *
     * GROUP 타입 보안 등급 판정에서 actor 의 소속만 확인하면 되므로, 멤버 전체를 가져오는
     * [listMemberIds] 보다 EXISTS 단건 조회가 효율적이다.
     *
     * @param groupId 그룹 식별자.
     * @param userId 확인할 사용자 식별자.
     * @return 해당 그룹 멤버이면 `true`, 아니면(없는 그룹 포함) `false`.
     */
    fun isMemberOf(
        groupId: UUID,
        userId: UUID,
    ): Boolean

    /**
     * 사용자가 소속한 모든 그룹의 식별자를 반환한다(FR-PM-07 PR-A Task 4).
     *
     * 필드 수준 권한 판정([FieldPermissionResolver])에서 actor 의 그룹 집합을 한 번에 회수해
     * `field_permissions` 규칙의 group_id 와 교집합으로 visible/editable 을 계산한다.
     * [isMemberOf] 가 (그룹, 사용자) 단건 확인인 것과 달리, 본 메서드는 actor 기준 역방향
     * 배치 조회로 그룹별 EXISTS N회를 1회로 압축한다(N+1 회피).
     *
     * @param userId 그룹 소속을 조회할 사용자 식별자.
     * @return 사용자가 속한 그룹 식별자 목록. 소속이 없으면 빈 목록.
     */
    fun findGroupIdsByUser(userId: UUID): List<UUID>
}
