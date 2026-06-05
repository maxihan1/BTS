// 전역 사용자 그룹 CRUD/멤버십을 오케스트레이션하는 애플리케이션 서비스 (FR-PM-09 Task 4)

package com.atlas.bts.identity.group

import com.atlas.bts.identity.user.UserRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 전역 사용자 그룹 애플리케이션 서비스 (FR-PM-09 Task 4).
 *
 * ## 책임
 * - 그룹 CRUD 오케스트레이션 — 생성/수정/삭제/조회.
 * - 멤버십(N:M) 추가/제거 — 그룹·사용자 존재 사전검증 후 멱등 위임.
 * - 영속 계층의 [DuplicateKeyException] 을 도메인 예외([UserGroupNameConflictException])로 변환.
 *
 * ## 트랜잭션 경계 (DATA.md §6 / DEVELOPMENT.md §1.2)
 * [UserGroupRepository] 구현체는 자체 트랜잭션을 열지 않으므로 본 서비스가 트랜잭션을 소유한다.
 * 클래스 레벨 @Transactional(REQUIRED) — 쓰기 메서드 기본 적용.
 * 읽기 전용 메서드는 @Transactional(readOnly = true) 로 오버라이드.
 *
 * ## 도메인 불변식
 * 그룹 이름/설명 정규화는 [UserGroup.create] 팩토리가 단일 진실로 소유한다.
 * 서비스는 이 팩토리를 우회하지 않고 정규화된 값만 영속 계층에 전달한다
 * (patch-merge-domain-bypass 회귀 방지).
 *
 * ## 멤버십 멱등성
 * [UserGroupRepository.addMember] 는 `ON CONFLICT DO NOTHING`, [UserGroupRepository.removeMember]
 * 는 no-op 으로 멱등하므로 서비스는 사전 멤버십 조회 없이 위임만 한다.
 * 단, 그룹/사용자 존재는 명시적 404 응답을 위해 사전 검증한다.
 *
 * @see docs/decisions/2026-06-05-user-groups.md 설계 결정 ADR
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class UserGroupService(
    private val groupRepository: UserGroupRepository,
    private val userRepository: UserRepository,
) {
    /**
     * 새 그룹을 생성한다.
     *
     * [UserGroup.create] 로 이름/설명을 정규화·검증한 뒤 영속 계층에 저장한다.
     *
     * @param name 그룹 이름 (정규화 전 원본).
     * @param description 그룹 설명 (정규화 전 원본, nullable).
     * @return id/타임스탬프가 채워진 영속 [UserGroup].
     * @throws IllegalArgumentException 이름이 비어 있거나 길이 제약 위반 ([UserGroup.create]).
     * @throws UserGroupNameConflictException 이름이 이미 존재하는 경우.
     */
    fun createGroup(
        name: String,
        description: String?,
    ): UserGroup {
        val normalized = UserGroup.create(name, description)
        return try {
            groupRepository.create(normalized.name, normalized.description)
        } catch (ex: DuplicateKeyException) {
            throw UserGroupNameConflictException(normalized.name).apply { initCause(ex) }
        }
    }

    /**
     * 그룹의 이름/설명을 갱신한다.
     *
     * 존재 확인 후 [UserGroup.create] 로 정규화한 값으로 갱신한다.
     *
     * @param id 갱신할 그룹 식별자.
     * @param name 새 이름 (정규화 전 원본).
     * @param description 새 설명 (정규화 전 원본, nullable).
     * @return 갱신된 [UserGroup].
     * @throws UserGroupNotFoundException 해당 그룹이 없는 경우.
     * @throws UserGroupNameConflictException 새 이름이 다른 그룹과 충돌하는 경우.
     */
    fun updateGroup(
        id: UUID,
        name: String,
        description: String?,
    ): UserGroup {
        requireGroupExists(id)
        val normalized = UserGroup.create(name, description)
        return try {
            groupRepository.update(id, normalized.name, normalized.description)
                ?: throw UserGroupNotFoundException(id)
        } catch (ex: DuplicateKeyException) {
            throw UserGroupNameConflictException(normalized.name).apply { initCause(ex) }
        }
    }

    /**
     * 그룹을 삭제한다. 멤버십은 FK ON DELETE CASCADE 로 함께 제거된다.
     *
     * @param id 삭제할 그룹 식별자.
     * @throws UserGroupNotFoundException 해당 그룹이 없는 경우.
     */
    fun deleteGroup(id: UUID) {
        requireGroupExists(id)
        groupRepository.delete(id)
    }

    /**
     * 그룹에 멤버를 추가한다 (멱등).
     *
     * 그룹·사용자 존재를 사전 검증한 뒤 영속 계층에 위임한다.
     * 이미 멤버이면 영속 계층이 no-op 으로 처리한다.
     *
     * @param groupId 그룹 식별자.
     * @param userId 추가할 사용자 식별자.
     * @throws UserGroupNotFoundException 해당 그룹이 없는 경우.
     * @throws UserNotFoundException 사용자가 BTS 에 없는 경우.
     */
    fun addMember(
        groupId: UUID,
        userId: UUID,
    ) {
        requireGroupExists(groupId)
        userRepository.findById(userId) ?: throw UserNotFoundException(userId)
        groupRepository.addMember(groupId, userId)
    }

    /**
     * 그룹에서 멤버를 제거한다 (멱등).
     *
     * 그룹 존재만 검증하고 영속 계층에 위임한다.
     * 멤버가 아니어도 영속 계층이 no-op 으로 처리하므로 사전 멤버십 조회는 하지 않는다.
     *
     * @param groupId 그룹 식별자.
     * @param userId 제거할 사용자 식별자.
     * @throws UserGroupNotFoundException 해당 그룹이 없는 경우.
     */
    fun removeMember(
        groupId: UUID,
        userId: UUID,
    ) {
        requireGroupExists(groupId)
        groupRepository.removeMember(groupId, userId)
    }

    /**
     * id 로 단건 그룹을 조회한다.
     *
     * @param id 그룹 식별자.
     * @return 해당 [UserGroup].
     * @throws UserGroupNotFoundException 해당 그룹이 없는 경우.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    fun getGroup(id: UUID): UserGroup = groupRepository.findById(id) ?: throw UserGroupNotFoundException(id)

    /**
     * 모든 그룹을 멤버 수와 함께 조회한다.
     *
     * @return 각 그룹과 멤버 수를 담은 [UserGroupWithCount] 목록. 없으면 빈 목록.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    fun listGroups(): List<UserGroupWithCount> = groupRepository.findAll()

    /**
     * 그룹에 속한 멤버의 사용자 식별자 목록을 조회한다.
     *
     * @param groupId 그룹 식별자.
     * @return 멤버 사용자 식별자 목록. 멤버가 없으면 빈 목록.
     * @throws UserGroupNotFoundException 해당 그룹이 없는 경우.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    fun listMembers(groupId: UUID): List<UUID> {
        requireGroupExists(groupId)
        return groupRepository.listMemberIds(groupId)
    }

    /**
     * 그룹 존재를 검증한다. 없으면 [UserGroupNotFoundException] 을 던진다.
     */
    private fun requireGroupExists(id: UUID) {
        if (!groupRepository.existsById(id)) throw UserGroupNotFoundException(id)
    }
}
