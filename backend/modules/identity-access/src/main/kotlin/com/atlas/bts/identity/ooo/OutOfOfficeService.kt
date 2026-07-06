// 부재중 조회 / replace-설정 / clear-해제 + 검증 유스케이스 서비스 (FR-PR-03 Task 3)

package com.atlas.bts.identity.ooo

import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val MAX_MESSAGE_LENGTH = 500

/**
 * 부재중(OOO) 유스케이스 서비스 (FR-PR-03 Task 3).
 *
 * OOO 는 통짜 값(replace 시맨틱)이라 FR-PR-01 프로필의 필드별 3-state 병합이 없다.
 * [setOoo] 는 필수값·기간·대리자·메시지를 검증한 뒤에만 upsert 한다("검증 먼저, 부분 적용 없음").
 *
 * ## 트랜잭션 경계
 * [setOoo]/[clearOoo] 는 쓰기이므로 `@Transactional`. [getOoo] 는 `readOnly = true`.
 * 검증 실패 시 [OooValidationException](unchecked)으로 빈 트랜잭션이 롤백된다.
 *
 * ## GET 필터(G3)와 활성(active) 판정의 구분
 * [getOoo] 는 두 단계로 필터링한다 — (1) `ends_at > now` 이면 저장 row 를 반환(미래예약 포함, G3),
 * 종료된 경우 all-null. (2) 반환 시 `active` 플래그는 `startsAt <= now < endsAt` 으로 별도 계산한다.
 * whoami 활성 판정([OutOfOfficeRepository.findActiveByUserId])은 이 서비스를 거치지 않고
 * 리포지토리를 직접 호출한다(FR-PR-02 `UserStatusRepository.findActiveByUserId` 선례).
 *
 * ## 대리자 검증 (cross-BC 아님)
 * delegate 실재 검증은 이 모듈이 소유한 [UserRepository.findById] 로 수행한다 — identity-access 가
 * users 를 소유하므로 별도 cross-BC 포트가 필요 없다.
 *
 * ## 시각 일관성
 * 기간/만료 판정은 주입된 [clock] 기준([Instant.now] 벽시계 아님)으로 수행한다. 기본값은
 * `Clock.systemUTC()`(identity-access 관례) — 테스트는 고정 Clock 을 주입해 결정성을 확보한다.
 *
 * @param repository user_ooo 접근 포트.
 * @param userRepository delegate 실재 검증용(같은 모듈의 users 조회).
 * @param clock 기간/만료 판정 기준 시각 소스.
 */
@Service
class OutOfOfficeService(
    private val repository: OutOfOfficeRepository,
    private val userRepository: UserRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 현재 OOO 를 조회한다. 미설정 또는 종료(G3, `ends_at <= now`)면 all-null [OooView].
     *
     * @param userId 조회 대상 사용자 id.
     */
    @Transactional(readOnly = true)
    fun getOoo(userId: UUID): OooView {
        val raw = repository.findByUserId(userId) ?: return OooView.EMPTY
        val now = Instant.now(clock)
        if (!raw.endsAt.isAfter(now)) return OooView.EMPTY
        return raw.toView(active = isActive(raw.startsAt, raw.endsAt, now))
    }

    /**
     * OOO 를 원자적으로 교체(replace)한다.
     *
     * @param userId 대상 사용자 id.
     * @param patch 설정할 OOO(정규화 전 원본).
     * @return 설정 후 OOO 뷰.
     * @throws OooValidationException 기간 부재/역전/이미종료, 대리자 자기위임/미존재, 메시지 500자 초과 시.
     */
    @Transactional
    fun setOoo(
        userId: UUID,
        patch: OooPatch,
    ): OooView {
        val startsAt = patch.startsAt ?: throw OooValidationException("시작 시각은 필수입니다.")
        val endsAt = patch.endsAt ?: throw OooValidationException("종료 시각은 필수입니다.")
        val message = patch.message?.trim()?.ifBlank { null }

        val delegate = validate(userId, startsAt, endsAt, patch.delegateUserId, message)

        repository.upsert(userId, startsAt, endsAt, patch.delegateUserId, message)

        val now = Instant.now(clock)
        return OooView(
            startsAt = startsAt,
            endsAt = endsAt,
            delegateUserId = patch.delegateUserId,
            delegateName = delegate?.displayName,
            message = message,
            active = isActive(startsAt, endsAt, now),
        )
    }

    /** OOO 를 해제한다(행 삭제). 없어도 멱등. */
    @Transactional
    fun clearOoo(userId: UUID) {
        repository.deleteByUserId(userId)
    }

    /**
     * 설정 값들을 검증한다(어떤 write 보다 먼저). 위반 사유를 단일 [OooValidationException] 으로 던진다.
     *
     * @return 대리자가 지정됐으면 검증된 [User], 없으면 null(호출 측이 delegateName 파생에 사용).
     */
    private fun validate(
        userId: UUID,
        startsAt: Instant,
        endsAt: Instant,
        delegateUserId: UUID?,
        message: String?,
    ): User? {
        val now = Instant.now(clock)
        if (!endsAt.isAfter(startsAt)) {
            throw OooValidationException("종료 시각은 시작 시각보다 이후여야 합니다.")
        }
        if (!endsAt.isAfter(now)) {
            throw OooValidationException("종료 시각은 현재보다 이후여야 합니다.")
        }
        val delegate =
            delegateUserId?.let { id ->
                if (id == userId) throw OooValidationException("본인을 대체 담당자로 지정할 수 없습니다.")
                userRepository.findById(id) ?: throw OooValidationException("대체 담당자를 찾을 수 없습니다.")
            }
        if (message != null && message.length > MAX_MESSAGE_LENGTH) {
            throw OooValidationException("메시지는 ${MAX_MESSAGE_LENGTH}자를 초과할 수 없습니다.")
        }
        return delegate
    }

    private fun isActive(
        startsAt: Instant,
        endsAt: Instant,
        now: Instant,
    ): Boolean = !startsAt.isAfter(now) && endsAt.isAfter(now)

    private fun OutOfOffice.toView(active: Boolean): OooView =
        OooView(
            startsAt = startsAt,
            endsAt = endsAt,
            delegateUserId = delegateUserId,
            delegateName = delegateName,
            message = message,
            active = active,
        )
}

/**
 * [OutOfOfficeService.getOoo] / [OutOfOfficeService.setOoo] 반환 뷰 (FR-PR-03).
 *
 * 미설정/종료/해제 상태는 [startsAt]/[endsAt]/[delegateUserId]/[delegateName]/[message] 가 all-null 이고
 * [active] 는 false 다.
 */
data class OooView(
    val startsAt: Instant?,
    val endsAt: Instant?,
    val delegateUserId: UUID?,
    val delegateName: String?,
    val message: String?,
    val active: Boolean,
) {
    companion object {
        /** 미설정/종료 상태 공용 인스턴스. */
        val EMPTY = OooView(null, null, null, null, null, false)
    }
}

/**
 * [OutOfOfficeService.setOoo] 입력 (FR-PR-03).
 *
 * [startsAt]/[endsAt] 가 null 이면 필수값 부재로 검증 실패한다(replace 시맨틱이지만 기간은 항상 필요).
 */
data class OooPatch(
    val startsAt: Instant?,
    val endsAt: Instant?,
    val delegateUserId: UUID?,
    val message: String?,
)

/**
 * 부재중 설정 검증 실패 예외(→ 400, 컨트롤러 매핑).
 *
 * @param message 사용자 노출용 일반화 메시지.
 */
class OooValidationException(message: String) : RuntimeException(message)
