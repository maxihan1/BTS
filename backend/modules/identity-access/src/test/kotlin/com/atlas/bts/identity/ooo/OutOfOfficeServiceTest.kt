// OutOfOfficeService 단위 테스트 — 조회/replace/clear + 검증 (mockk repo/userRepository, FR-PR-03 task-3)

package com.atlas.bts.identity.ooo

import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
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
 * OutOfOfficeService 단위 테스트 (FR-PR-03 Task 3).
 *
 * 검증: getOoo(미설정/활성/종료 G3-필터/미래예약 all-null 아님), setOoo(replace upsert / 정규화 / 필수값 /
 * 기간 검증 / 대리자 자기위임-미존재 검증 / 메시지 길이 검증), clearOoo(delete).
 *
 * 고정 [Clock] (2026-07-07T10:00:00Z) 을 기준으로 판정 결정성을 확보한다(FR-PR-02 `UserStatusServiceTest` 미러).
 */
class OutOfOfficeServiceTest {
    private val repo = mockk<OutOfOfficeRepository>()
    private val userRepository = mockk<UserRepository>()
    private val userId: UUID = UUID.randomUUID()
    private val delegateId: UUID = UUID.randomUUID()

    private val fixedNow: Instant = Instant.parse("2026-07-07T10:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private val service = OutOfOfficeService(repo, userRepository, clock)

    private fun delegateUser(): User =
        User(
            id = delegateId,
            username = "bob",
            email = "bob@bts.local",
            displayName = "Bob Kim",
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

    // ── getOoo ─────────────────────────────────────────────────────────────────

    @Test
    fun `getOoo는 설정 없으면 all-null 뷰 반환`() {
        every { repo.findByUserId(userId) } returns null

        val view = service.getOoo(userId)

        assertThat(view.startsAt).isNull()
        assertThat(view.endsAt).isNull()
        assertThat(view.delegateUserId).isNull()
        assertThat(view.delegateName).isNull()
        assertThat(view.message).isNull()
        assertThat(view.active).isFalse()
    }

    @Test
    fun `getOoo는 활성 OOO를 뷰로 매핑하고 active true`() {
        val startsAt = fixedNow.minusSeconds(3600)
        val endsAt = fixedNow.plusSeconds(3600)
        every { repo.findByUserId(userId) } returns
            OutOfOffice(userId, startsAt, endsAt, delegateId, "Bob Kim", "휴가 중")

        val view = service.getOoo(userId)

        assertThat(view.startsAt).isEqualTo(startsAt)
        assertThat(view.endsAt).isEqualTo(endsAt)
        assertThat(view.delegateUserId).isEqualTo(delegateId)
        assertThat(view.delegateName).isEqualTo("Bob Kim")
        assertThat(view.message).isEqualTo("휴가 중")
        assertThat(view.active).isTrue()
    }

    @Test
    fun `getOoo는 종료된 OOO를 all-null로 취급한다 (G3)`() {
        val startsAt = fixedNow.minusSeconds(7200)
        val endsAt = fixedNow.minusSeconds(3600)
        every { repo.findByUserId(userId) } returns OutOfOffice(userId, startsAt, endsAt, null, null, null)

        val view = service.getOoo(userId)

        assertThat(view.startsAt).isNull()
        assertThat(view.endsAt).isNull()
        assertThat(view.active).isFalse()
    }

    @Test
    fun `getOoo는 미래 예약 OOO를 값은 노출하되 active false로 반환한다`() {
        val startsAt = fixedNow.plusSeconds(3600)
        val endsAt = fixedNow.plusSeconds(7200)
        every { repo.findByUserId(userId) } returns OutOfOffice(userId, startsAt, endsAt, null, null, null)

        val view = service.getOoo(userId)

        assertThat(view.startsAt).isEqualTo(startsAt)
        assertThat(view.endsAt).isEqualTo(endsAt)
        assertThat(view.active).isFalse()
    }

    // ── setOoo — 정상 ────────────────────────────────────────────────────────

    @Test
    fun `setOoo 기간-대리자-메시지 설정 시 upsert 호출 및 저장값 반환`() {
        val startsAt = fixedNow.plusSeconds(3600)
        val endsAt = fixedNow.plusSeconds(7200)
        every { userRepository.findById(delegateId) } returns delegateUser()
        every { repo.upsert(userId, startsAt, endsAt, delegateId, "휴가 중입니다.") } just Runs

        val view = service.setOoo(userId, OooPatch(startsAt, endsAt, delegateId, "휴가 중입니다."))

        verify { repo.upsert(userId, startsAt, endsAt, delegateId, "휴가 중입니다.") }
        assertThat(view.startsAt).isEqualTo(startsAt)
        assertThat(view.endsAt).isEqualTo(endsAt)
        assertThat(view.delegateUserId).isEqualTo(delegateId)
        assertThat(view.delegateName).isEqualTo("Bob Kim")
        assertThat(view.message).isEqualTo("휴가 중입니다.")
        assertThat(view.active).isFalse()
    }

    @Test
    fun `setOoo 기간만 설정해도 성립 (대리자-메시지 없이도)`() {
        val startsAt = fixedNow.minusSeconds(100)
        val endsAt = fixedNow.plusSeconds(3600)
        every { repo.upsert(userId, startsAt, endsAt, null, null) } just Runs

        val view = service.setOoo(userId, OooPatch(startsAt, endsAt, null, null))

        verify { repo.upsert(userId, startsAt, endsAt, null, null) }
        assertThat(view.delegateUserId).isNull()
        assertThat(view.delegateName).isNull()
        assertThat(view.message).isNull()
        assertThat(view.active).isTrue()
        verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `setOoo 메시지 공백은 null로 정규화된다`() {
        val startsAt = fixedNow.minusSeconds(100)
        val endsAt = fixedNow.plusSeconds(3600)
        every { repo.upsert(userId, startsAt, endsAt, null, null) } just Runs

        val view = service.setOoo(userId, OooPatch(startsAt, endsAt, null, "   "))

        verify { repo.upsert(userId, startsAt, endsAt, null, null) }
        assertThat(view.message).isNull()
    }

    // ── setOoo — 검증 실패 ───────────────────────────────────────────────────

    @Test
    fun `setOoo startsAt 부재면 ValidationException`() {
        assertThatThrownBy {
            service.setOoo(userId, OooPatch(null, fixedNow.plusSeconds(3600), null, null))
        }.isInstanceOf(OooValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setOoo endsAt 부재면 ValidationException`() {
        assertThatThrownBy {
            service.setOoo(userId, OooPatch(fixedNow.plusSeconds(100), null, null, null))
        }.isInstanceOf(OooValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setOoo endsAt이 startsAt 이하면 ValidationException`() {
        val startsAt = fixedNow.plusSeconds(7200)
        val endsAt = fixedNow.plusSeconds(3600)

        assertThatThrownBy {
            service.setOoo(userId, OooPatch(startsAt, endsAt, null, null))
        }.isInstanceOf(OooValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setOoo endsAt이 현재 이하(이미 종료)면 ValidationException`() {
        val startsAt = fixedNow.minusSeconds(7200)
        val endsAt = fixedNow.minusSeconds(1)

        assertThatThrownBy {
            service.setOoo(userId, OooPatch(startsAt, endsAt, null, null))
        }.isInstanceOf(OooValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setOoo 대리자가 본인이면 ValidationException, users 조회 안 함`() {
        val startsAt = fixedNow.minusSeconds(100)
        val endsAt = fixedNow.plusSeconds(3600)

        assertThatThrownBy {
            service.setOoo(userId, OooPatch(startsAt, endsAt, userId, null))
        }.isInstanceOf(OooValidationException::class.java)

        verify(exactly = 0) { userRepository.findById(any()) }
        verify(exactly = 0) { repo.upsert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setOoo 대리자가 존재하지 않으면 ValidationException`() {
        val startsAt = fixedNow.minusSeconds(100)
        val endsAt = fixedNow.plusSeconds(3600)
        every { userRepository.findById(delegateId) } returns null

        assertThatThrownBy {
            service.setOoo(userId, OooPatch(startsAt, endsAt, delegateId, null))
        }.isInstanceOf(OooValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `setOoo 메시지 500자 초과면 ValidationException`() {
        val startsAt = fixedNow.minusSeconds(100)
        val endsAt = fixedNow.plusSeconds(3600)
        val tooLong = "가".repeat(501)

        assertThatThrownBy {
            service.setOoo(userId, OooPatch(startsAt, endsAt, null, tooLong))
        }.isInstanceOf(OooValidationException::class.java)

        verify(exactly = 0) { repo.upsert(any(), any(), any(), any(), any()) }
    }

    // ── clearOoo ───────────────────────────────────────────────────────────────

    @Test
    fun `clearOoo는 repository delete를 호출한다`() {
        every { repo.deleteByUserId(userId) } just Runs

        service.clearOoo(userId)

        verify { repo.deleteByUserId(userId) }
    }
}
