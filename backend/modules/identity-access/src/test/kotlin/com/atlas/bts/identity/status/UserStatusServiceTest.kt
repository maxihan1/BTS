// UserStatusService 단위 테스트 — 조회/replace/clear + 검증 (mockk repo, FR-PR-02 task-3)

package com.atlas.bts.identity.status

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * UserStatusService 단위 테스트 (FR-PR-02 Task 3).
 *
 * 검증: getActiveStatus(미설정 all-null / 활성 매핑), setStatus(replace upsert / 정규화 / 해제 /
 * 최소 하나 검증 / 길이 상한 / 과거 만료 거부).
 */
class UserStatusServiceTest {
    private val repo = mockk<UserStatusRepository>()
    private val service = UserStatusService(repo)
    private val userId: UUID = UUID.randomUUID()

    @Test
    fun `getActiveStatus는 상태 없으면 all-null 뷰 반환`() {
        every { repo.findActiveByUserId(userId) } returns null

        val view = service.getActiveStatus(userId)

        assertThat(view.emoji).isNull()
        assertThat(view.text).isNull()
        assertThat(view.expiresAt).isNull()
    }

    @Test
    fun `getActiveStatus는 활성 상태를 뷰로 매핑`() {
        val expiresAt = Instant.parse("2099-01-01T00:00:00Z")
        every { repo.findActiveByUserId(userId) } returns UserStatus(userId, "🌴", "휴가 중", expiresAt)

        val view = service.getActiveStatus(userId)

        assertThat(view.emoji).isEqualTo("🌴")
        assertThat(view.text).isEqualTo("휴가 중")
        assertThat(view.expiresAt).isEqualTo(expiresAt)
    }

    @Test
    fun `setStatus emoji와 text 설정 시 upsert 호출`() {
        every { repo.upsert(userId, "🌴", "휴가 중", null) } just Runs
        every { repo.findActiveByUserId(userId) } returns UserStatus(userId, "🌴", "휴가 중", null)

        val view = service.setStatus(userId, StatusPatch("🌴", "휴가 중", null))

        verify { repo.upsert(userId, "🌴", "휴가 중", null) }
        assertThat(view.emoji).isEqualTo("🌴")
    }

    @Test
    fun `setStatus emoji만 있어도 성립`() {
        every { repo.upsert(userId, "🌴", null, null) } just Runs
        every { repo.findActiveByUserId(userId) } returns UserStatus(userId, "🌴", null, null)

        service.setStatus(userId, StatusPatch("🌴", null, null))

        verify { repo.upsert(userId, "🌴", null, null) }
    }

    @Test
    fun `setStatus text만 있어도 성립`() {
        every { repo.upsert(userId, null, "회의 중", null) } just Runs
        every { repo.findActiveByUserId(userId) } returns UserStatus(userId, null, "회의 중", null)

        service.setStatus(userId, StatusPatch(null, "회의 중", null))

        verify { repo.upsert(userId, null, "회의 중", null) }
    }

    @Test
    fun `setStatus 공백은 null로 정규화되어 둘 다 비면 해제(delete)`() {
        every { repo.deleteByUserId(userId) } just Runs
        every { repo.findActiveByUserId(userId) } returns null

        val view = service.setStatus(userId, StatusPatch("   ", "", null))

        verify { repo.deleteByUserId(userId) }
        verify(exactly = 0) { repo.upsert(any(), any(), any(), any()) }
        assertThat(view.emoji).isNull()
        assertThat(view.text).isNull()
    }

    @Test
    fun `setStatus expiresAt만 있고 emoji-text 비면 해제(만료만으로 상태 성립 안 함)`() {
        every { repo.deleteByUserId(userId) } just Runs
        every { repo.findActiveByUserId(userId) } returns null

        service.setStatus(userId, StatusPatch(null, null, Instant.parse("2099-01-01T00:00:00Z")))

        verify { repo.deleteByUserId(userId) }
        verify(exactly = 0) { repo.upsert(any(), any(), any(), any()) }
    }

    @Test
    fun `setStatus text 100자 초과면 ValidationException, upsert 안 함`() {
        val tooLong = "가".repeat(101)

        assertThatThrownBy { service.setStatus(userId, StatusPatch(null, tooLong, null)) }
            .isInstanceOf(StatusValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any()) }
    }

    @Test
    fun `setStatus emoji 32자 초과면 ValidationException`() {
        val tooLong = "a".repeat(33)

        assertThatThrownBy { service.setStatus(userId, StatusPatch(tooLong, null, null)) }
            .isInstanceOf(StatusValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any()) }
    }

    @Test
    fun `setStatus 과거 expiresAt이면 ValidationException`() {
        val past = Instant.parse("2020-01-01T00:00:00Z")

        assertThatThrownBy { service.setStatus(userId, StatusPatch("🌴", null, past)) }
            .isInstanceOf(StatusValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any()) }
    }

    // C2(이중 클럭) — 만료 과거 판정을 벽시계가 아닌 주입된 Clock 기준으로 수행해야 한다.
    // 만료 lazy 필터(repository의 DB NOW())와 판정 클럭을 일관되게 만들기 위함.

    @Test
    fun `setStatus는 주입된 Clock 기준으로 만료 미래 여부를 판정한다`() {
        // 고정 Clock(2000-01-01). 벽시계(현재)로는 과거지만 이 Clock 기준으로는 미래인 값을 통과시켜야 한다.
        val fixedNow = Instant.parse("2000-01-01T00:00:00Z")
        val clockedService = UserStatusService(repo, Clock.fixed(fixedNow, ZoneOffset.UTC))
        val futurePerClock = fixedNow.plusSeconds(3600)
        every { repo.upsert(userId, null, "회의 중", futurePerClock) } just Runs

        clockedService.setStatus(userId, StatusPatch(null, "회의 중", futurePerClock))

        verify { repo.upsert(userId, null, "회의 중", futurePerClock) }
    }

    @Test
    fun `setStatus는 주입된 Clock 기준 과거 만료를 거부한다`() {
        val fixedNow = Instant.parse("2000-01-01T00:00:00Z")
        val clockedService = UserStatusService(repo, Clock.fixed(fixedNow, ZoneOffset.UTC))
        val pastPerClock = fixedNow.minusSeconds(1)

        assertThatThrownBy { clockedService.setStatus(userId, StatusPatch(null, "회의 중", pastPerClock)) }
            .isInstanceOf(StatusValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any()) }
    }
}
