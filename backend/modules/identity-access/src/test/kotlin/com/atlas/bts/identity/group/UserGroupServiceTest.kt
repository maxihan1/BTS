// UserGroupService 단위 테스트 — CRUD 오케스트레이션/예외변환/멱등/존재검증 (FR-PM-09 Task 4)

package com.atlas.bts.identity.group

import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * UserGroupService 단위 테스트 (FR-PM-09 Task 4).
 *
 * MockK 기반 순수 단위 테스트 — [UserGroupRepository] 와 [UserRepository] 모두 mock.
 *
 * ## 테스트 시나리오
 *
 * - createGroup 정상: 도메인 [UserGroup.create] 정규화 경유 → repo.create 위임
 * - createGroup 이름중복: repo DuplicateKeyException → [UserGroupNameConflictException]
 * - updateGroup 없는 그룹 → [UserGroupNotFoundException]
 * - updateGroup 이름중복: repo DuplicateKeyException → [UserGroupNameConflictException]
 * - updateGroup 정상: existsById 확인 후 update 위임
 * - deleteGroup 없는 그룹 → [UserGroupNotFoundException]
 * - deleteGroup 정상: existsById 확인 후 delete 위임
 * - addMember 없는 그룹 → [UserGroupNotFoundException]
 * - addMember 없는 사용자 → [UserNotFoundException]
 * - addMember 정상/멱등: repo.addMember 위임
 * - removeMember 없는 그룹 → [UserGroupNotFoundException]
 * - removeMember 정상/멱등: repo.removeMember 위임
 * - getGroup / listGroups / listMembers 위임
 * - Annotation 회귀 가드: @Service + @Transactional
 */
class UserGroupServiceTest {
    private lateinit var groupRepo: UserGroupRepository
    private lateinit var userRepo: UserRepository
    private lateinit var service: UserGroupService

    private val groupId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @BeforeEach
    fun setUp() {
        groupRepo = mockk()
        userRepo = mockk()
        service = UserGroupService(groupRepo, userRepo)
    }

    private fun persistedGroup(
        name: String = "팀 A",
        description: String? = "설명",
    ): UserGroup =
        UserGroup(
            id = groupId,
            name = name,
            description = description,
            createdAt = Instant.parse("2026-06-05T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-05T00:00:00Z"),
        )

    private fun persistedUser(): User =
        User(
            id = userId,
            username = "alice",
            email = null,
            displayName = "Alice",
            createdAt = Instant.parse("2026-06-05T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-05T00:00:00Z"),
        )

    // ── createGroup ─────────────────────────────────────────────────────────

    @Test
    fun `createGroup은 도메인 정규화를 경유해 저장한다`() {
        // name 앞뒤 공백이 도메인 create 에서 trim 되어 repo 에 전달되어야 한다
        every { groupRepo.create("팀 A", "설명") } returns persistedGroup()

        val result = service.createGroup("  팀 A  ", "  설명  ")

        assertThat(result.id).isEqualTo(groupId)
        assertThat(result.name).isEqualTo("팀 A")
        verify(exactly = 1) { groupRepo.create("팀 A", "설명") }
    }

    @Test
    fun `createGroup은 이름 중복 시 NameConflict로 변환한다`() {
        every { groupRepo.create(any(), any()) } throws DuplicateKeyException("dup")

        assertThatThrownBy { service.createGroup("팀 A", null) }
            .isInstanceOf(UserGroupNameConflictException::class.java)
    }

    // ── updateGroup ─────────────────────────────────────────────────────────

    @Test
    fun `updateGroup은 없는 그룹이면 NotFound를 던진다`() {
        every { groupRepo.existsById(groupId) } returns false

        assertThatThrownBy { service.updateGroup(groupId, "새 이름", null) }
            .isInstanceOf(UserGroupNotFoundException::class.java)

        verify(exactly = 0) { groupRepo.update(any(), any(), any()) }
    }

    @Test
    fun `updateGroup은 이름 중복 시 NameConflict로 변환한다`() {
        every { groupRepo.existsById(groupId) } returns true
        every { groupRepo.update(groupId, any(), any()) } throws DuplicateKeyException("dup")

        assertThatThrownBy { service.updateGroup(groupId, "팀 A", null) }
            .isInstanceOf(UserGroupNameConflictException::class.java)
    }

    @Test
    fun `updateGroup은 존재하면 정규화된 값으로 갱신한다`() {
        every { groupRepo.existsById(groupId) } returns true
        every { groupRepo.update(groupId, "팀 B", "새 설명") } returns persistedGroup("팀 B", "새 설명")

        val result = service.updateGroup(groupId, "  팀 B  ", "  새 설명  ")

        assertThat(result.name).isEqualTo("팀 B")
        verify(exactly = 1) { groupRepo.update(groupId, "팀 B", "새 설명") }
    }

    // ── deleteGroup ─────────────────────────────────────────────────────────

    @Test
    fun `deleteGroup은 없는 그룹이면 NotFound를 던진다`() {
        every { groupRepo.existsById(groupId) } returns false

        assertThatThrownBy { service.deleteGroup(groupId) }
            .isInstanceOf(UserGroupNotFoundException::class.java)

        verify(exactly = 0) { groupRepo.delete(any()) }
    }

    @Test
    fun `deleteGroup은 존재하면 삭제를 위임한다`() {
        every { groupRepo.existsById(groupId) } returns true
        every { groupRepo.delete(groupId) } returns true

        service.deleteGroup(groupId)

        verify(exactly = 1) { groupRepo.delete(groupId) }
    }

    // ── addMember ───────────────────────────────────────────────────────────

    @Test
    fun `addMember은 없는 그룹이면 NotFound를 던진다`() {
        every { groupRepo.existsById(groupId) } returns false

        assertThatThrownBy { service.addMember(groupId, userId) }
            .isInstanceOf(UserGroupNotFoundException::class.java)

        verify(exactly = 0) { groupRepo.addMember(any(), any()) }
    }

    @Test
    fun `addMember은 없는 사용자면 UserNotFound를 던진다`() {
        every { groupRepo.existsById(groupId) } returns true
        every { userRepo.findById(userId) } returns null

        assertThatThrownBy { service.addMember(groupId, userId) }
            .isInstanceOf(UserNotFoundException::class.java)

        verify(exactly = 0) { groupRepo.addMember(any(), any()) }
    }

    @Test
    fun `addMember은 그룹과 사용자가 존재하면 멤버 추가를 위임한다`() {
        every { groupRepo.existsById(groupId) } returns true
        every { userRepo.findById(userId) } returns persistedUser()
        every { groupRepo.addMember(groupId, userId) } just runs

        service.addMember(groupId, userId)

        verify(exactly = 1) { groupRepo.addMember(groupId, userId) }
    }

    // ── removeMember ────────────────────────────────────────────────────────

    @Test
    fun `removeMember은 없는 그룹이면 NotFound를 던진다`() {
        every { groupRepo.existsById(groupId) } returns false

        assertThatThrownBy { service.removeMember(groupId, userId) }
            .isInstanceOf(UserGroupNotFoundException::class.java)

        verify(exactly = 0) { groupRepo.removeMember(any(), any()) }
    }

    @Test
    fun `removeMember은 그룹이 존재하면 멤버 제거를 위임한다 (멱등)`() {
        every { groupRepo.existsById(groupId) } returns true
        every { groupRepo.removeMember(groupId, userId) } just runs

        // 없는 멤버여도 repo 가 no-op 멱등 처리하므로 사전 멤버십 조회 없이 위임만 한다
        service.removeMember(groupId, userId)

        verify(exactly = 1) { groupRepo.removeMember(groupId, userId) }
    }

    // ── 조회 위임 ────────────────────────────────────────────────────────────

    @Test
    fun `getGroup은 없는 그룹이면 NotFound를 던진다`() {
        every { groupRepo.findById(groupId) } returns null

        assertThatThrownBy { service.getGroup(groupId) }
            .isInstanceOf(UserGroupNotFoundException::class.java)
    }

    @Test
    fun `getGroup은 존재하면 그룹을 반환한다`() {
        every { groupRepo.findById(groupId) } returns persistedGroup()

        val result = service.getGroup(groupId)

        assertThat(result.id).isEqualTo(groupId)
    }

    @Test
    fun `listGroups는 repo findAll에 위임한다`() {
        val views = listOf(UserGroupWithCount(persistedGroup(), 3))
        every { groupRepo.findAll() } returns views

        val result = service.listGroups()

        assertThat(result).isEqualTo(views)
        verify(exactly = 1) { groupRepo.findAll() }
    }

    @Test
    fun `listMembers는 없는 그룹이면 NotFound를 던진다`() {
        every { groupRepo.existsById(groupId) } returns false

        assertThatThrownBy { service.listMembers(groupId) }
            .isInstanceOf(UserGroupNotFoundException::class.java)
    }

    @Test
    fun `listMembers는 그룹이 존재하면 멤버 ID 목록을 반환한다`() {
        every { groupRepo.existsById(groupId) } returns true
        every { groupRepo.listMemberIds(groupId) } returns listOf(userId)

        val result = service.listMembers(groupId)

        assertThat(result).containsExactly(userId)
    }

    // ── Annotation 회귀 가드 ──────────────────────────────────────────────────

    @Test
    fun `서비스는 Service와 Transactional 애노테이션을 가진다`() {
        val clazz = UserGroupService::class.java
        assertThat(clazz.isAnnotationPresent(Service::class.java)).isTrue()
        assertThat(clazz.isAnnotationPresent(Transactional::class.java)).isTrue()
    }
}
