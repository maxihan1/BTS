// BTS PAT 서비스 — verify/markLastUsed/hasScope. 발급은 Auth_platform 으로 옮겼다 (FR-AU-09 Task 18 · FR-API-04 S20)

package com.atlas.bts.identity.pat

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * PAT raw token → SHA-256 hex 변환 (EC-26).
 *
 * SHA-256(rawToken) 전체를 해시한다. "pat_" prefix 가 raw token 에 이미 포함돼 있다.
 * 결과는 64자 소문자 hex 문자열이다.
 *
 * [PersonalAccessTokenService] 와 테스트 코드 모두에서 사용하기 위해 패키지 레벨 internal 함수로 선언.
 *
 * @param raw prefix 포함 raw token ("pat_" + 48자 body)
 * @return 64자 소문자 hex 문자열
 */
internal fun sha256Hex(raw: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(raw.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

/**
 * PAT 검증 실패 시 발생하는 예외.
 *
 * 실패 원인 (token 미존재 / 만료 / revoke) 은 [message] 로 구분한다.
 * 호출 측에서 실패 원인을 외부에 노출하면 안 되므로 HTTP 응답 변환은 컨트롤러/필터 책임이다.
 *
 * @param message 내부 로깅용 원인 설명 (외부 응답에 포함 금지)
 */
class PatVerificationException(message: String) : RuntimeException(message)

/**
 * Personal Access Token 서비스 (FR-AU-09 Task 18 · FR-API-04 / SDD §19.5).
 *
 * PAT 발급 · 목록 · 폐기는 Auth_platform 으로 옮겼다(S20). 이미 발급된 BTS PAT 는 만료까지 살아 있으므로
 * 검사만 남는다 — 급히 끊을 BTS PAT 는 SQL 로 폐기한다. Auth PAT 는 [AuthPlatformPatVerifier] 가 보고,
 * 둘을 묶는 진입점은 [PatUserResolver] 다.
 *
 * ## 책임
 * - [verify]: raw token → SHA-256 해시 → DB 조회 → 만료/revoke 검증 → [PersonalAccessToken] 반환
 * - [markLastUsed]: `last_used_at` 갱신 위임
 * - [hasScope]: PAT scope 평가 helper
 *
 * ## 보안 계약 (EC-26 / DEVELOPMENT.md §1.1)
 * - token_hash = SHA-256(rawToken) 전체. "pat_" prefix 포함. DB 엔 해시만 저장한다.
 * - raw token 은 절대 로깅하지 않는다.
 *
 * ## 트랜잭션 경계 (DATA.md §6)
 * - 클래스 레벨 `@Transactional(REQUIRED)` — 호출 측 트랜잭션에 참여하거나 새로 시작.
 *
 * @see PersonalAccessTokenRepository DB 접근 계층
 */
@Service
@Transactional(propagation = Propagation.REQUIRED)
class PersonalAccessTokenService(
    private val patRepository: PersonalAccessTokenRepository,
) {
    private val log = LoggerFactory.getLogger(PersonalAccessTokenService::class.java)

    /**
     * raw PAT 토큰을 검증하고 활성 [PersonalAccessToken] 을 반환한다.
     *
     * ## 검증 흐름
     * 1. raw token → SHA-256 hex 변환 (EC-26)
     * 2. [PersonalAccessTokenRepository.findByTokenHash] 로 DB 조회
     * 3. 미존재 → [PatVerificationException] (Result.failure)
     * 4. [PersonalAccessToken.isActive] 로 만료/revoke 확인
     * 5. 비활성 → [PatVerificationException] (Result.failure)
     * 6. 통과 시 [markLastUsed] 호출 후 Result.success(pat) 반환
     *
     * raw token 은 절대 로그에 남기지 않는다 (DATA.md §8).
     *
     * @param rawToken prefix 포함 raw PAT token ("pat_" + 48자 body)
     * @return [Result]<[PersonalAccessToken]> — 성공 시 활성 PAT, 실패 시 [PatVerificationException]
     */
    fun verify(rawToken: String): Result<PersonalAccessToken> {
        val hash = sha256Hex(rawToken)
        val pat = patRepository.findByTokenHash(hash)

        if (pat == null) {
            log.debug("PAT verify — hash 미존재 (token_hash masked)")
            return Result.failure(PatVerificationException("PAT not found"))
        }

        val now = Instant.now()
        if (!pat.isActive(now)) {
            log.debug("PAT verify — 비활성 PAT id={} (expired or revoked)", pat.id)
            return Result.failure(PatVerificationException("PAT is expired or revoked"))
        }

        markLastUsed(pat.id)
        return Result.success(pat)
    }

    /**
     * PAT 의 `last_used_at` 을 현재 시각으로 갱신한다.
     *
     * [verify] 내부에서 자동 호출되므로 일반적으로 외부에서 직접 호출할 필요가 없다.
     * 존재하지 않는 [patId] 는 조용히 무시된다 ([PersonalAccessTokenRepository.updateLastUsed] 계약).
     *
     * @param patId 갱신할 PAT UUID
     */
    fun markLastUsed(patId: UUID) {
        patRepository.updateLastUsed(patId)
    }

    /**
     * PAT 에 요청된 [scope] 가 허용돼 있는지 확인한다.
     *
     * [PersonalAccessToken.hasScope] 에 위임한다.
     * `*` 와일드카드가 scopes 에 포함된 경우 모든 scope 를 허용한다.
     *
     * @param pat 검증 대상 PAT
     * @param scope 확인할 scope 문자열 (예: `"read:issues"`)
     * @return scope 가 허용되면 true, 아니면 false
     */
    fun hasScope(
        pat: PersonalAccessToken,
        scope: String,
    ): Boolean = pat.hasScope(scope)
}
