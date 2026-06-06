// IssueSecuritySchemeService 단위 테스트 — 스킴/등급/멤버 CRUD 오케스트레이션·예외변환·다형검증 (FR-PM-06 PR-A Task 5)

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * IssueSecuritySchemeService 단위 테스트 (FR-PM-06 PR-A Task 5).
 *
 * MockK 기반 순수 단위 테스트 — [IssueSecuritySchemeRepository] / [UserRepository] /
 * [UserGroupRepository] 모두 mock.
 *
 * ## 테스트 시나리오
 *
 * - 스킴: 생성 정규화 위임, 이름중복→SchemeNameConflict, 갱신/삭제 없는 스킴→SchemeNotFound, 조회 위임.
 * - 등급: 추가 시 스킴 실재 확인(없으면 SchemeNotFound)·이름중복→LevelNameConflict,
 *   갱신/삭제 없는 등급→LevelNotFound, 목록 위임.
 * - 멤버: USER/GROUP memberValue 실재 사전조회(없으면 UserNotFound/GroupNotFound),
 *   PROJECT_ROLE/REPORTER/ASSIGNEE는 도메인 검증만, 멱등 추가/제거 위임, 없는 등급→LevelNotFound.
 * - Annotation 회귀 가드: @Service + @Transactional.
 */
class IssueSecuritySchemeServiceTest {
    private lateinit var schemeRepo: IssueSecuritySchemeRepository
    private lateinit var userRepo: UserRepository
    private lateinit var groupRepo: UserGroupRepository
    private lateinit var service: IssueSecuritySchemeService

    private val schemeId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val levelId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val memberId = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val userId = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val groupId = UUID.fromString("55555555-5555-4555-8555-555555555555")

    @BeforeEach
    fun setUp() {
        schemeRepo = mockk()
        userRepo = mockk()
        groupRepo = mockk()
        service = IssueSecuritySchemeService(schemeRepo, userRepo, groupRepo)
    }

    private fun persistedScheme(
        name: String = "기밀 스킴",
        description: String? = "설명",
    ): IssueSecurityScheme =
        IssueSecurityScheme(
            id = schemeId,
            name = name,
            description = description,
            createdAt = Instant.parse("2026-06-06T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-06T00:00:00Z"),
        )

    private fun persistedLevel(
        name: String = "임원만",
        isDefault: Boolean = false,
    ): IssueSecurityLevel =
        IssueSecurityLevel(
            id = levelId,
            schemeId = schemeId,
            name = name,
            description = "설명",
            isDefault = isDefault,
            createdAt = Instant.parse("2026-06-06T00:00:00Z"),
        )

    private fun persistedMember(
        type: MemberType = MemberType.USER,
        value: String? = "44444444-4444-4444-8444-444444444444",
    ): SecurityLevelMember =
        SecurityLevelMember(
            id = memberId,
            levelId = levelId,
            memberType = type,
            memberValue = value,
            createdAt = Instant.parse("2026-06-06T00:00:00Z"),
        )

    private fun persistedUser(): User =
        User(
            id = userId,
            username = "alice",
            email = null,
            displayName = "Alice",
            createdAt = Instant.parse("2026-06-06T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-06T00:00:00Z"),
        )

    // ── 스킴 ────────────────────────────────────────────────────────────────────

    @Test
    fun `createScheme은 도메인 정규화를 경유해 저장한다`() {
        // 앞뒤 공백이 IssueSecurityScheme.create 에서 trim 되어 영속 계층에 전달되어야 한다
        every { schemeRepo.create(match { it.name == "기밀 스킴" && it.description == "설명" }) } returns persistedScheme()

        val result = service.createScheme("  기밀 스킴  ", "  설명  ")

        assertThat(result.id).isEqualTo(schemeId)
        assertThat(result.name).isEqualTo("기밀 스킴")
        verify(exactly = 1) { schemeRepo.create(match { it.name == "기밀 스킴" }) }
    }

    @Test
    fun `createScheme은 이름 중복 시 SchemeNameConflict로 변환한다`() {
        every { schemeRepo.create(any()) } throws DuplicateKeyException("dup")

        assertThatThrownBy { service.createScheme("기밀 스킴", null) }
            .isInstanceOf(SchemeNameConflictException::class.java)
    }

    @Test
    fun `updateScheme은 없는 스킴이면 SchemeNotFound를 던진다`() {
        every { schemeRepo.update(schemeId, any(), any()) } returns null

        assertThatThrownBy { service.updateScheme(schemeId, "새 이름", null) }
            .isInstanceOf(SchemeNotFoundException::class.java)
    }

    @Test
    fun `updateScheme은 이름 중복 시 SchemeNameConflict로 변환한다`() {
        every { schemeRepo.update(schemeId, any(), any()) } throws DuplicateKeyException("dup")

        assertThatThrownBy { service.updateScheme(schemeId, "기밀 스킴", null) }
            .isInstanceOf(SchemeNameConflictException::class.java)
    }

    @Test
    fun `updateScheme은 존재하면 정규화된 값으로 갱신한다`() {
        every { schemeRepo.update(schemeId, "기밀 스킴", "새 설명") } returns persistedScheme("기밀 스킴", "새 설명")

        val result = service.updateScheme(schemeId, "  기밀 스킴  ", "  새 설명  ")

        assertThat(result.description).isEqualTo("새 설명")
        verify(exactly = 1) { schemeRepo.update(schemeId, "기밀 스킴", "새 설명") }
    }

    @Test
    fun `deleteScheme은 없는 스킴이면 SchemeNotFound를 던진다`() {
        every { schemeRepo.delete(schemeId) } returns false

        assertThatThrownBy { service.deleteScheme(schemeId) }
            .isInstanceOf(SchemeNotFoundException::class.java)
    }

    @Test
    fun `deleteScheme은 존재하면 삭제를 위임한다`() {
        every { schemeRepo.delete(schemeId) } returns true

        service.deleteScheme(schemeId)

        verify(exactly = 1) { schemeRepo.delete(schemeId) }
    }

    @Test
    fun `getScheme은 없는 스킴이면 SchemeNotFound를 던진다`() {
        every { schemeRepo.findById(schemeId) } returns null

        assertThatThrownBy { service.getScheme(schemeId) }
            .isInstanceOf(SchemeNotFoundException::class.java)
    }

    @Test
    fun `getScheme은 존재하면 상세를 반환한다`() {
        val detail = IssueSecuritySchemeDetail(persistedScheme(), listOf(persistedLevel()))
        every { schemeRepo.findById(schemeId) } returns detail

        val result = service.getScheme(schemeId)

        assertThat(result).isEqualTo(detail)
    }

    @Test
    fun `listSchemes는 repo findAll에 위임한다`() {
        val all = listOf(IssueSecuritySchemeDetail(persistedScheme(), emptyList()))
        every { schemeRepo.findAll() } returns all

        assertThat(service.listSchemes()).isEqualTo(all)
        verify(exactly = 1) { schemeRepo.findAll() }
    }

    // ── 등급 ────────────────────────────────────────────────────────────────────

    @Test
    fun `addLevel은 없는 스킴이면 SchemeNotFound를 던진다`() {
        every { schemeRepo.findById(schemeId) } returns null

        assertThatThrownBy { service.addLevel(schemeId, "임원만", null, false) }
            .isInstanceOf(SchemeNotFoundException::class.java)

        verify(exactly = 0) { schemeRepo.addLevel(any()) }
    }

    @Test
    fun `addLevel은 스킴이 존재하면 도메인 정규화를 경유해 추가한다`() {
        every { schemeRepo.findById(schemeId) } returns IssueSecuritySchemeDetail(persistedScheme(), emptyList())
        every {
            schemeRepo.addLevel(match { it.schemeId == schemeId && it.name == "임원만" && it.isDefault })
        } returns persistedLevel(isDefault = true)

        val result = service.addLevel(schemeId, "  임원만  ", null, true)

        assertThat(result.id).isEqualTo(levelId)
        verify(exactly = 1) { schemeRepo.addLevel(match { it.name == "임원만" }) }
    }

    @Test
    fun `addLevel은 같은 스킴 내 이름 중복 시 LevelNameConflict로 변환한다`() {
        every { schemeRepo.findById(schemeId) } returns IssueSecuritySchemeDetail(persistedScheme(), emptyList())
        every { schemeRepo.addLevel(any()) } throws DuplicateKeyException("dup")

        assertThatThrownBy { service.addLevel(schemeId, "임원만", null, false) }
            .isInstanceOf(LevelNameConflictException::class.java)
    }

    @Test
    fun `updateLevel은 없는 등급이면 LevelNotFound를 던진다`() {
        every { schemeRepo.updateLevel(levelId, any(), any(), any()) } returns null

        assertThatThrownBy { service.updateLevel(levelId, "새 이름", null, false) }
            .isInstanceOf(LevelNotFoundException::class.java)
    }

    @Test
    fun `updateLevel은 이름 중복 시 LevelNameConflict로 변환한다`() {
        every { schemeRepo.updateLevel(levelId, any(), any(), any()) } throws DuplicateKeyException("dup")

        assertThatThrownBy { service.updateLevel(levelId, "임원만", null, false) }
            .isInstanceOf(LevelNameConflictException::class.java)
    }

    @Test
    fun `updateLevel은 존재하면 정규화된 값으로 갱신한다`() {
        every { schemeRepo.updateLevel(levelId, "내부용", null, true) } returns persistedLevel("내부용", isDefault = true)

        val result = service.updateLevel(levelId, "  내부용  ", null, true)

        assertThat(result.name).isEqualTo("내부용")
        verify(exactly = 1) { schemeRepo.updateLevel(levelId, "내부용", null, true) }
    }

    @Test
    fun `deleteLevel은 없는 등급이면 LevelNotFound를 던진다`() {
        every { schemeRepo.deleteLevel(levelId) } returns false

        assertThatThrownBy { service.deleteLevel(levelId) }
            .isInstanceOf(LevelNotFoundException::class.java)
    }

    @Test
    fun `deleteLevel은 존재하면 삭제를 위임한다`() {
        every { schemeRepo.deleteLevel(levelId) } returns true

        service.deleteLevel(levelId)

        verify(exactly = 1) { schemeRepo.deleteLevel(levelId) }
    }

    @Test
    fun `listLevels는 repo에 위임한다`() {
        every { schemeRepo.listLevels(schemeId) } returns listOf(persistedLevel())

        assertThat(service.listLevels(schemeId)).containsExactly(persistedLevel())
    }

    // ── 멤버 ────────────────────────────────────────────────────────────────────

    @Test
    fun `addMember은 USER memberValue가 실재하지 않으면 UserNotFound를 던진다`() {
        every { userRepo.findById(userId) } returns null

        assertThatThrownBy { service.addMember(levelId, MemberType.USER, userId.toString()) }
            .isInstanceOf(IssueSecurityUserNotFoundException::class.java)

        verify(exactly = 0) { schemeRepo.addMember(any()) }
    }

    @Test
    fun `addMember은 USER가 실재하면 멤버 추가를 위임한다`() {
        every { userRepo.findById(userId) } returns persistedUser()
        every {
            schemeRepo.addMember(
                match { it.levelId == levelId && it.memberType == MemberType.USER && it.memberValue == userId.toString() },
            )
        } returns persistedMember()

        val result = service.addMember(levelId, MemberType.USER, userId.toString())

        assertThat(result.id).isEqualTo(memberId)
        verify(exactly = 1) { userRepo.findById(userId) }
    }

    @Test
    fun `addMember은 GROUP memberValue가 실재하지 않으면 GroupNotFound를 던진다`() {
        every { groupRepo.existsById(groupId) } returns false

        assertThatThrownBy { service.addMember(levelId, MemberType.GROUP, groupId.toString()) }
            .isInstanceOf(IssueSecurityGroupNotFoundException::class.java)

        verify(exactly = 0) { schemeRepo.addMember(any()) }
    }

    @Test
    fun `addMember은 GROUP이 실재하면 멤버 추가를 위임한다`() {
        every { groupRepo.existsById(groupId) } returns true
        every {
            schemeRepo.addMember(match { it.memberType == MemberType.GROUP && it.memberValue == groupId.toString() })
        } returns persistedMember(MemberType.GROUP, groupId.toString())

        val result = service.addMember(levelId, MemberType.GROUP, groupId.toString())

        assertThat(result.memberType).isEqualTo(MemberType.GROUP)
        verify(exactly = 1) { groupRepo.existsById(groupId) }
    }

    @Test
    fun `addMember은 PROJECT_ROLE는 실재조회 없이 도메인 검증만으로 추가한다`() {
        every {
            schemeRepo.addMember(match { it.memberType == MemberType.PROJECT_ROLE && it.memberValue == "PROJECT_ADMIN" })
        } returns persistedMember(MemberType.PROJECT_ROLE, "PROJECT_ADMIN")

        val result = service.addMember(levelId, MemberType.PROJECT_ROLE, "PROJECT_ADMIN")

        assertThat(result.memberType).isEqualTo(MemberType.PROJECT_ROLE)
        verify(exactly = 0) { userRepo.findById(any()) }
        verify(exactly = 0) { groupRepo.existsById(any()) }
    }

    @Test
    fun `addMember은 REPORTER는 value 없이 도메인 검증만으로 추가한다`() {
        every {
            schemeRepo.addMember(match { it.memberType == MemberType.REPORTER && it.memberValue == null })
        } returns persistedMember(MemberType.REPORTER, null)

        val result = service.addMember(levelId, MemberType.REPORTER, null)

        assertThat(result.memberType).isEqualTo(MemberType.REPORTER)
        verify(exactly = 0) { userRepo.findById(any()) }
    }

    @Test
    fun `addMember은 PROJECT_ROLE 잘못된 값이면 도메인 검증으로 거부한다`() {
        assertThatThrownBy { service.addMember(levelId, MemberType.PROJECT_ROLE, "NOT_A_ROLE") }
            .isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { schemeRepo.addMember(any()) }
    }

    @Test
    fun `addMember은 없는 등급이면 LevelNotFound를 던진다`() {
        // 등급이 없으면 멤버 INSERT 가 FK 위반 → DataIntegrityViolationException 을 LevelNotFound 로 변환
        every {
            schemeRepo.addMember(match { it.memberType == MemberType.PROJECT_ROLE })
        } throws DataIntegrityViolationException("fk")

        assertThatThrownBy { service.addMember(levelId, MemberType.PROJECT_ROLE, "MEMBER") }
            .isInstanceOf(LevelNotFoundException::class.java)
    }

    @Test
    fun `removeMember은 repo에 위임한다 (멱등)`() {
        every { schemeRepo.removeMemberById(memberId) } returns false

        // 없는 멤버여도 멱등 204 → 예외 없이 위임만
        service.removeMember(memberId)

        verify(exactly = 1) { schemeRepo.removeMemberById(memberId) }
    }

    @Test
    fun `listMembers는 repo에 위임한다`() {
        every { schemeRepo.listMembers(levelId) } returns listOf(persistedMember())

        assertThat(service.listMembers(levelId)).containsExactly(persistedMember())
    }

    // ── Annotation 회귀 가드 ──────────────────────────────────────────────────────

    @Test
    fun `서비스는 Service와 Transactional 애노테이션을 가진다`() {
        val clazz = IssueSecuritySchemeService::class.java
        assertThat(clazz.isAnnotationPresent(Service::class.java)).isTrue()
        assertThat(clazz.isAnnotationPresent(Transactional::class.java)).isTrue()
    }
}
