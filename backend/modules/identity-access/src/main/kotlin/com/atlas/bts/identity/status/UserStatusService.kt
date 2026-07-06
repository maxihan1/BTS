// 사용자 상태 메시지 조회 / replace-설정 / clear-해제 + 검증 유스케이스 서비스 (FR-PR-02 Task 3)

package com.atlas.bts.identity.status

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

private const val MAX_EMOJI_LENGTH = 32
private const val MAX_TEXT_LENGTH = 100

/**
 * 사용자 상태 메시지 유스케이스 서비스 (FR-PR-02 Task 3).
 *
 * 상태는 통짜 값(replace 시맨틱)이라 FR-PR-01 프로필의 필드별 3-state 병합이 없다.
 * [setStatus] 는 emoji/text 를 정규화(공백→null)한 뒤 둘 다 비면 해제(delete), 아니면 검증 후 upsert 한다.
 *
 * ## 트랜잭션 경계
 * [setStatus] 는 검증을 어떤 write 보다 먼저 수행하므로, 검증 실패 시 아무 것도 쓰지 않고
 * [StatusValidationException](unchecked)으로 빈 트랜잭션이 롤백된다("검증 먼저, 부분 적용 없음").
 *
 * ## 시각 일관성 (C2)
 * 만료 과거 판정은 주입된 [clock] 기준([Instant.now] 벽시계 아님)으로 수행한다. 조회 시의 만료 lazy
 * 필터는 DB `NOW()`를 쓰므로, 검증 클럭을 [clock]으로 통일해 경계값에서의 비대칭을 없앤다. 기본값은
 * `Clock.systemUTC()`(identity-access 관례) — 테스트는 고정 Clock을 주입해 결정성을 확보한다.
 *
 * @param repository user_statuses 접근 포트.
 * @param clock 만료 판정 기준 시각 소스.
 */
@Service
class UserStatusService(
    private val repository: UserStatusRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 활성 상태를 조회한다. 미설정/만료면 all-null [StatusView].
     *
     * @param userId 조회 대상 사용자 id.
     */
    @Transactional(readOnly = true)
    fun getActiveStatus(userId: UUID): StatusView = repository.findActiveByUserId(userId).toView()

    /**
     * 상태를 원자적으로 교체(replace)한다.
     *
     * emoji/text 는 공백을 null 로 정규화한다. 정규화 결과 둘 다 null 이면 해제(row 삭제)한다 —
     * expiresAt 만으로는 상태가 성립하지 않는다. 최소 하나가 있으면 길이/과거-만료 검증 후 upsert 한다.
     *
     * @param userId 대상 사용자 id.
     * @param patch 설정할 상태(정규화 전 원본).
     * @return 설정 후 활성 상태(해제 시 all-null).
     * @throws StatusValidationException emoji 32자 초과, text 100자 초과, 또는 expiresAt 이 과거일 때.
     */
    @Transactional
    fun setStatus(
        userId: UUID,
        patch: StatusPatch,
    ): StatusView {
        val emoji = patch.emoji?.trim()?.ifBlank { null }
        val text = patch.text?.trim()?.ifBlank { null }

        // 둘 다 비면 해제 — expiresAt 유무 무관(만료만으로 상태 성립 안 함).
        if (emoji == null && text == null) {
            repository.deleteByUserId(userId)
            return StatusView(null, null, null)
        }

        validate(emoji, text, patch.expiresAt)

        repository.upsert(userId, emoji, text, patch.expiresAt)
        return StatusView(emoji, text, patch.expiresAt)
    }

    /**
     * 설정 값들을 검증한다(어떤 write 보다 먼저). 위반 사유를 단일 [StatusValidationException] 으로 던진다.
     */
    private fun validate(
        emoji: String?,
        text: String?,
        expiresAt: Instant?,
    ) {
        val error =
            when {
                emoji != null && emoji.length > MAX_EMOJI_LENGTH ->
                    "이모지는 ${MAX_EMOJI_LENGTH}자를 초과할 수 없습니다."
                text != null && text.length > MAX_TEXT_LENGTH ->
                    "상태 텍스트는 ${MAX_TEXT_LENGTH}자를 초과할 수 없습니다."
                expiresAt != null && expiresAt.isBefore(Instant.now(clock)) ->
                    "만료 시각은 과거일 수 없습니다."
                else -> null
            }
        if (error != null) throw StatusValidationException(error)
    }

    // 블록 body — expr body(같은 줄)는 130자로 detekt MaxLineLength(120) 위반이라 블록으로 회피
    // (ktlint↔detekt 라인길이 함정).
    private fun UserStatus?.toView(): StatusView {
        return StatusView(emoji = this?.emoji, text = this?.text, expiresAt = this?.expiresAt)
    }
}

/**
 * [UserStatusService.getActiveStatus] / [UserStatusService.setStatus] 반환 뷰 (FR-PR-02).
 *
 * 미설정/만료/해제 상태는 세 필드 모두 null 이다.
 */
data class StatusView(
    val emoji: String?,
    val text: String?,
    val expiresAt: Instant?,
)

/**
 * [UserStatusService.setStatus] 입력 (FR-PR-02).
 *
 * FR-PR-01 프로필의 3-state 와 달리, 부재 필드는 "미설정"(null)으로 해석된다(replace 시맨틱, 보존 아님).
 * emoji/text 정규화(공백→null) 후 둘 다 null 이면 해제.
 */
data class StatusPatch(
    val emoji: String?,
    val text: String?,
    val expiresAt: Instant?,
)

/**
 * 상태 설정 검증 실패 예외(→ 400, 컨트롤러 매핑).
 *
 * @param message 사용자 노출용 일반화 메시지.
 */
class StatusValidationException(message: String) : RuntimeException(message)
