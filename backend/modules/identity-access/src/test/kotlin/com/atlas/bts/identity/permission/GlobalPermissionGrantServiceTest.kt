// GlobalPermissionGrantService 단위 테스트 — grantee 존재검증/permission 선검증/예외변환/회수 판별 (FR-PM-10 Task 6)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * GlobalPermissionGrantService 단위 테스트 (FR-PM-10 Task 6).
 *
 * MockK 기반 순수 단위 테스트 — 협력자 3종([GlobalPermissionGrantRepository]/[UserRepository]/
 * [UserGroupRepository])을 모두 mock 한다. 선례 `UserGroupServiceTest`.
 *
 * ## 이 파일이 존재하는 이유 (plan 결함 D)
 * `GlobalPermissionGrantControllerTest` 는 **슬라이스라 서비스를 mock** 한다 — 거기서는 도메인 예외를
 * mock 이 던지므로 **서비스 로직이 한 줄도 실행되지 않는다**. 즉 아래 세 계약은 컨트롤러 테스트만으로는
 * 통째로 지워도 초록이다.
 * - **grantee 존재 검증** — ADR D-4 가 *FK 를 생략한 근거*로 삼은 바로 그 검증. 없으면 ADR 이 거짓이 된다.
 * - **permission 선검증** — ADR D-1 의 이중 방어 중 앱 겹. DB CHECK 만 남으면 400 이 500 으로 변질된다.
 * - **`DuplicateKeyException` → 409 변환** — 없으면 중복 부여가 500 이 된다(T4 실측).
 *
 * ## 테스트 시나리오 (10)
 * - USER 부여: users 존재검증 → 위임, `grantedBy` 원형 전달 (ADR D-5)
 * - USER grantee 미존재 → [GranteeNotFoundException], 부여 미호출
 * - GROUP 부여: user_groups 존재검증 → 위임 (users 는 조회하지 않는다)
 * - GROUP grantee 미존재 → [GranteeNotFoundException], 부여 미호출
 * - 미지 permission → [UnknownPermissionException], 리포지토리 도달 전 차단
 * - 중복 부여 `DuplicateKeyException` → [DuplicateGrantException]
 * - revoke 0행 → [GrantNotFoundException] (ADR D-5)
 * - revoke 성공 → 예외 없음
 * - list 위임
 * - Annotation 회귀 가드: @Service + @Transactional
 */
class GlobalPermissionGrantServiceTest {
    private lateinit var grantRepo: GlobalPermissionGrantRepository
    private lateinit var userRepo: UserRepository
    private lateinit var groupRepo: UserGroupRepository
    private lateinit var service: GlobalPermissionGrantService

    private val adminId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val groupId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val grantId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    private val createProject = "CREATE_PROJECT"

    @BeforeEach
    fun setUp() {
        grantRepo = mockk()
        userRepo = mockk()
        groupRepo = mockk()
        service = GlobalPermissionGrantService(grantRepo, userRepo, groupRepo)
    }

    private fun persistedUser(): User =
        User(
            id = userId,
            username = "alice",
            email = null,
            displayName = "Alice",
            createdAt = Instant.parse("2026-07-17T00:00:00Z"),
            updatedAt = Instant.parse("2026-07-17T00:00:00Z"),
        )

    private fun persistedGrant(
        granteeType: GranteeType = GranteeType.USER,
        granteeId: UUID = userId,
    ): GlobalPermissionGrant =
        GlobalPermissionGrant(
            id = grantId,
            permission = createProject,
            granteeType = granteeType,
            granteeId = granteeId,
            grantedBy = adminId,
            createdAt = Instant.parse("2026-07-17T00:00:00Z"),
        )

    // ── grant — USER 축 ──────────────────────────────────────────────────────

    @Test
    fun `USER 부여는 users 존재검증 후 위임하고 grantedBy 를 그대로 넘긴다`() {
        every { userRepo.findById(userId) } returns persistedUser()
        every { grantRepo.grant(createProject, GranteeType.USER, userId, adminId) } returns persistedGrant()

        val result = service.grant(createProject, GranteeType.USER, userId, adminId)

        assertThat(result).isEqualTo(persistedGrant())
        // grantedBy 인자를 adminId 로 고정한 stub 이라, 서비스가 다른 값(예: granteeId)을 넘기면
        // non-relaxed mockk 가 매칭 실패로 터진다 — ADR D-5 감사 흔적 배선의 판별자다.
        verify(exactly = 1) { userRepo.findById(userId) }
        verify(exactly = 1) { grantRepo.grant(createProject, GranteeType.USER, userId, adminId) }
    }

    @Test
    fun `존재하지 않는 USER grantee 는 GranteeNotFoundException — 부여 미호출`() {
        every { userRepo.findById(userId) } returns null

        assertThatThrownBy { service.grant(createProject, GranteeType.USER, userId, adminId) }
            .isInstanceOf(GranteeNotFoundException::class.java)

        // ★ ADR D-4 의 가드 — 존재 검증을 지우면 grant 가 호출돼 이 단언이 죽는다.
        verify(exactly = 0) { grantRepo.grant(any(), any(), any(), any()) }
    }

    // ── grant — GROUP 축 ─────────────────────────────────────────────────────

    @Test
    fun `GROUP 부여는 user_groups 존재검증 후 위임한다`() {
        every { groupRepo.existsById(groupId) } returns true
        every {
            grantRepo.grant(createProject, GranteeType.GROUP, groupId, adminId)
        } returns persistedGrant(granteeType = GranteeType.GROUP, granteeId = groupId)

        val result = service.grant(createProject, GranteeType.GROUP, groupId, adminId)

        assertThat(result.granteeType).isEqualTo(GranteeType.GROUP)
        verify(exactly = 1) { groupRepo.existsById(groupId) }
        // GROUP 축은 users 를 보지 않는다 — granteeType 분기가 뭉개지면(양쪽 다 조회) 이 단언이 죽는다.
        verify(exactly = 0) { userRepo.findById(any()) }
    }

    @Test
    fun `존재하지 않는 GROUP grantee 는 GranteeNotFoundException — 부여 미호출`() {
        every { groupRepo.existsById(groupId) } returns false

        assertThatThrownBy { service.grant(createProject, GranteeType.GROUP, groupId, adminId) }
            .isInstanceOf(GranteeNotFoundException::class.java)

        verify(exactly = 0) { grantRepo.grant(any(), any(), any(), any()) }
    }

    // ── grant — permission 선검증 (ADR D-1 이중 방어의 앱 겹) ─────────────────

    @Test
    fun `미지 permission 은 UnknownPermissionException — 리포지토리 도달 전 차단`() {
        assertThatThrownBy { service.grant("DROP_EVERYTHING", GranteeType.USER, userId, adminId) }
            .isInstanceOf(UnknownPermissionException::class.java)

        // DB CHECK 에 도달시키지 않는 것이 계약이다 — 도달하면 400 이 아니라
        // DataIntegrityViolationException(500)이 된다. "DB 만 믿지 말 것"의 실체.
        verify(exactly = 0) { grantRepo.grant(any(), any(), any(), any()) }
        verify(exactly = 0) { userRepo.findById(any()) }
    }

    // ── grant — 중복 (ADR D-1 · T4 실측) ─────────────────────────────────────

    @Test
    fun `중복 부여 DuplicateKeyException 은 DuplicateGrantException 으로 변환`() {
        every { userRepo.findById(userId) } returns persistedUser()
        every {
            grantRepo.grant(createProject, GranteeType.USER, userId, adminId)
        } throws DuplicateKeyException("duplicate key value violates unique constraint")

        assertThatThrownBy { service.grant(createProject, GranteeType.USER, userId, adminId) }
            .isInstanceOf(DuplicateGrantException::class.java)
    }

    // ── revoke (ADR D-5) ─────────────────────────────────────────────────────

    @Test
    fun `존재하지 않는 grant 회수는 GrantNotFoundException`() {
        every { grantRepo.revoke(grantId) } returns false

        // "지웠다고 믿었는데 대상이 없었다"를 조용히 성공으로 만들지 않는다 — 리포지토리가
        // Boolean 을 반환하는 유일한 이유이며, 반환값을 버리면 이 단언이 죽는다.
        assertThatThrownBy { service.revoke(grantId) }
            .isInstanceOf(GrantNotFoundException::class.java)
    }

    @Test
    fun `grant 회수 성공은 예외 없음`() {
        every { grantRepo.revoke(grantId) } returns true

        assertThatCode { service.revoke(grantId) }.doesNotThrowAnyException()

        verify(exactly = 1) { grantRepo.revoke(grantId) }
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    fun `list 는 리포지토리 결과를 그대로 위임`() {
        every { grantRepo.list() } returns listOf(persistedGrant())

        assertThat(service.list()).containsExactly(persistedGrant())
    }

    // ── Annotation 회귀 가드 ─────────────────────────────────────────────────

    @Test
    fun `서비스는 Service와 Transactional 애노테이션을 가진다`() {
        val clazz = GlobalPermissionGrantService::class.java
        assertThat(clazz.isAnnotationPresent(Service::class.java)).isTrue()
        assertThat(clazz.isAnnotationPresent(Transactional::class.java)).isTrue()
    }
}
