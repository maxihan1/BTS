// PAT 서비스 — verify/markLastUsed/hasScope + 발급(issue)/목록(listByUser)/폐기(revoke) (FR-AU-09 Task 18 · FR-API-04 Task 4)

package com.atlas.bts.identity.pat

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
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

/** PAT 이름이 공백일 때. HTTP 400 매핑 대상. */
class BlankPatNameException : RuntimeException("PAT 이름을 지정해야 합니다")

/**
 * 만료 기간이 허용 범위(1..365일)를 벗어나거나 미지정(무기한)일 때. HTTP 400 매핑 대상.
 * 입력 반사를 피하려 실제 입력값은 message 에 담지 않는다.
 */
class InvalidPatExpiryException : RuntimeException("만료 기간은 1일 이상 365일 이하로 지정해야 합니다")

/** 사용자당 활성 PAT 개수 상한에 도달했을 때. HTTP 400 매핑 대상. */
class PatQuotaExceededException : RuntimeException("활성 PAT 개수 상한에 도달했습니다")

/**
 * id+userId 로 본인 소유 PAT 를 찾지 못했을 때(미존재/타인 소유). HTTP 404 매핑 대상.
 * IDOR 차단을 위해 미존재와 타인 소유를 구분하지 않고 동일 응답으로 수렴시킨다.
 */
class PersonalAccessTokenNotFoundException : RuntimeException("PAT 를 찾을 수 없습니다")

/**
 * PAT 발급 결과 — raw token 원문(1회 노출)과 저장된 [PersonalAccessToken] 메타데이터.
 *
 * [rawToken] 은 발급 응답에만 담고 이후 어디에도(DB·로그·감사) 저장하지 않는다(EC-26). 컨트롤러는
 * [rawToken] 을 한 번 반환한 뒤 폐기하며, 목록/상세 응답에는 해시만 아는 [token] 메타만 노출한다.
 *
 * @property rawToken prefix 포함 raw PAT token ("pat_" + 48자 base62 body). **로그 기록 금지.**
 * @property token DB 에 저장된 PAT (token_hash 는 SHA-256, raw 아님).
 */
data class IssuedPersonalAccessToken(
    val rawToken: String,
    val token: PersonalAccessToken,
)

/**
 * Personal Access Token 서비스 (FR-AU-09 Task 18 · FR-API-04 Task 4 / SDD §19.5).
 *
 * ## 책임
 * - [verify]: raw token → SHA-256 해시 → DB 조회 → 만료/revoke 검증 → [PersonalAccessToken] 반환
 * - [markLastUsed]: `last_used_at` 갱신 위임
 * - [hasScope]: PAT scope 평가 helper
 * - [issue]: PAT 발급 (scope 검증 + 개수 상한 + CSPRNG 토큰 + 감사)
 * - [listByUser]: 본인 PAT 목록 조회 (만료 포함, token 미포함)
 * - [revoke]: 본인 PAT 폐기 (소유권 확인 + 멱등 + 감사)
 *
 * ## 보안 계약 (EC-26 / DEVELOPMENT.md §1.1)
 * - token_hash = SHA-256(rawToken) 전체. "pat_" prefix 포함. DB 엔 해시만 저장한다.
 * - raw token 은 발급 응답([issue] 반환값)에만 1회 노출된다. 절대 로깅/재조회 불가.
 * - 난수원은 [SecureRandom](CSPRNG)만 사용한다(java.util.Random·UUID·Math.random 금지).
 * - 발급/폐기 감사는 트랜잭션 안에서 기록하며 실패 시 예외 전파로 롤백한다(fail-closed).
 *
 * ## 발급 정책 (FR-API-04)
 * - scope 는 [PatScopeCatalog] 화이트리스트만 허용(빈/미지 거부).
 * - 만료는 1..365일만 허용(무기한 금지, EC-27 발급 경로 한정).
 * - 활성 PAT 는 사용자당 [MAX_ACTIVE_TOKENS]개까지. 개수 판정은 advisory lock 후 재조회로 TOCTOU 를 막는다.
 *
 * ## 트랜잭션 경계 (DATA.md §6)
 * - 클래스 레벨 `@Transactional(REQUIRED)` — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * - 시각 비교는 주입 [clock] 기준(time-bomb 회피, authcontroller-revokesession-timebomb 교훈).
 *
 * @see PersonalAccessTokenRepository DB 접근 계층
 * @see PatScopeCatalog scope 화이트리스트/정규화
 */
@Service
@Transactional(propagation = Propagation.REQUIRED)
class PersonalAccessTokenService(
    private val patRepository: PersonalAccessTokenRepository,
    private val auditLogService: AuthAuditLogService,
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
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
    fun hasScope(pat: PersonalAccessToken, scope: String): Boolean = pat.hasScope(scope)

    /**
     * 새 PAT 를 발급하고 raw token(1회 노출)을 포함한 결과를 반환한다 (FR-API-04).
     *
     * name 공백, 미지/빈 scope, 무기한·범위 밖 만료는 [BlankPatNameException]/[UnknownScopeException] 등으로
     * 거부한다. 개수 상한 판정은 [acquireUserLock] 직렬화 후 [PersonalAccessTokenRepository.countActiveByUser]
     * 재조회로 수행해 TOCTOU 를 막는다. raw token 은 [SecureRandom] base62 48자로 만들고 DB 엔 SHA-256 해시만
     * 저장하며, [AuthEventType.PAT_ISSUED] 감사를 트랜잭션 안에서 fail-closed 로 기록한다.
     *
     * @param userId 발급 주체(토큰 소유자)
     * @param name 사용자 지정 레이블(공백 불가)
     * @param scopes 요청 scope 목록([PatScopeCatalog] 화이트리스트, 중복 허용 — 정규화됨)
     * @param expiresInDays 만료까지 일수(1..365, null/무기한 금지)
     * @return raw token 을 포함한 [IssuedPersonalAccessToken]
     * @throws BlankPatNameException name 이 공백일 때
     * @throws EmptyScopeException scope 가 비었을 때
     * @throws UnknownScopeException 화이트리스트 밖 scope 가 있을 때
     * @throws InvalidPatExpiryException 만료가 null 이거나 1..365일 밖일 때
     * @throws PatQuotaExceededException 활성 PAT 가 [MAX_ACTIVE_TOKENS]개에 도달했을 때
     */
    @Transactional
    fun issue(
        userId: UUID,
        name: String,
        scopes: List<String>,
        expiresInDays: Int?,
    ): IssuedPersonalAccessToken {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) throw BlankPatNameException()

        val normalizedScopes = PatScopeCatalog.normalize(scopes)
        PatScopeCatalog.validate(normalizedScopes)

        val now = clock.instant()
        val expiresAt = computeExpiry(expiresInDays, now)

        // TOCTOU 차단: 개수 판정 전 사용자 단위 advisory lock 으로 직렬화하고 lock 안에서 재조회한다.
        acquireUserLock(userId)
        if (patRepository.countActiveByUser(userId, now) >= MAX_ACTIVE_TOKENS) {
            throw PatQuotaExceededException()
        }

        val rawToken = generateRawToken()
        // token_hash 는 SHA-256(raw) 만 저장한다 — raw 원문은 DB 에 넘기지 않는다(EC-26).
        val saved =
            patRepository.save(
                PersonalAccessToken(
                    id = UUID.randomUUID(),
                    userId = userId,
                    name = trimmedName,
                    tokenHash = sha256Hex(rawToken),
                    scopes = normalizedScopes,
                    expiresAt = expiresAt,
                    lastUsedAt = null,
                    revokedAt = null,
                    createdAt = now,
                ),
            )

        // fail-closed(§1.1): 감사 실패를 삼키지 않는다 — 예외 전파 시 @Transactional 이 발급을 롤백한다.
        emitAudit(userId, AuthEventType.PAT_ISSUED, mapOf("patId" to saved.id.toString(), "name" to saved.name))
        return IssuedPersonalAccessToken(rawToken = rawToken, token = saved)
    }

    /**
     * 본인 PAT 목록을 반환한다 — 만료 포함, revoke 제외 (FR-API-04).
     *
     * token_hash 만 담긴 [PersonalAccessToken] 을 반환하므로 raw token 은 노출되지 않는다.
     *
     * @param userId 조회 주체
     * @return created_at 최신순 PAT 목록(빈 목록 허용)
     */
    @Transactional(readOnly = true)
    fun listByUser(userId: UUID): List<PersonalAccessToken> = patRepository.listByUserIncludingExpired(userId)

    /**
     * 본인 PAT 를 폐기한다 (FR-API-04).
     *
     * 소유권을 먼저 확인해 미존재/타인 소유는 동일하게 [PersonalAccessTokenNotFoundException](404)으로 수렴시킨다
     * (IDOR 차단 — 존재 여부 미노출). 활성 상태만 원자적으로 revoke 하며, 이미 취소된 본인 토큰은 영향 0행으로
     * 멱등 성공한다(감사 미emit — 상태가 바뀌지 않았으므로). 실제 취소(1행)일 때만 [AuthEventType.PAT_REVOKED] 를
     * fail-closed 로 기록한다.
     *
     * @param id 폐기할 PAT UUID
     * @param userId 요청 주체(소유자)
     * @throws PersonalAccessTokenNotFoundException 미존재 또는 타인 소유일 때
     */
    @Transactional
    fun revoke(
        id: UUID,
        userId: UUID,
    ) {
        // 소유권 확인(IDOR): 미존재/타인은 동일 404 — 존재 여부를 노출하지 않는다.
        patRepository.findByIdAndUserId(id, userId) ?: throw PersonalAccessTokenNotFoundException()

        // 활성만 원자 폐기. 0행 = 이미 취소(멱등 성공). 동시 취소 race 로 0행이면 중복 감사도 없다.
        val affected = patRepository.revokeOwned(id, userId, clock.instant())
        if (affected > 0) {
            emitAudit(userId, AuthEventType.PAT_REVOKED, mapOf("patId" to id.toString()))
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    /**
     * 만료 시각을 산출한다. 무기한(null)·범위 밖(1..365일 외)은 거부한다(발급 정책).
     *
     * @throws InvalidPatExpiryException [expiresInDays] 가 null 이거나 [MIN_EXPIRY_DAYS]..[MAX_EXPIRY_DAYS] 밖일 때
     */
    private fun computeExpiry(
        expiresInDays: Int?,
        now: Instant,
    ): Instant {
        val days = expiresInDays ?: throw InvalidPatExpiryException()
        if (days !in MIN_EXPIRY_DAYS..MAX_EXPIRY_DAYS) throw InvalidPatExpiryException()
        return now.plus(Duration.ofDays(days.toLong()))
    }

    /**
     * CSPRNG 로 `pat_` + [PersonalAccessToken.TOKEN_BODY_LENGTH]자 base62 raw token 을 생성한다.
     *
     * 난수원은 [SecureRandom] 만 사용한다(예측 가능한 java.util.Random·UUID·Math.random 금지). base62 는
     * modulo 편향 없는 [SecureRandom.nextInt] 균등 추출로 채운다. 반환 raw 는 절대 로깅/저장하지 않는다(§1.1).
     */
    private fun generateRawToken(): String {
        val body =
            buildString(PersonalAccessToken.TOKEN_BODY_LENGTH) {
                repeat(PersonalAccessToken.TOKEN_BODY_LENGTH) {
                    append(BASE62_ALPHABET[SECURE_RANDOM.nextInt(BASE62_ALPHABET.length)])
                }
            }
        return PersonalAccessToken.TOKEN_PREFIX + body
    }

    /**
     * 사용자 단위 advisory lock 획득 — 동시 발급의 개수 판정을 직렬화한다(TOCTOU 차단, FR-API-04).
     *
     * UUID 는 `pg_advisory_xact_lock(bigint)` 에 직접 넣을 수 없어 [hashtextextended] 로 텍스트 전폭을 bigint 로
     * 해시한다(절단 금지·충돌은 무관 사용자의 거짓 직렬화일 뿐 안전 — ExternalAccountRepository 선례). DATA.md §5
     * 예외: PG 전용 함수 + parameter binding(문자열 결합 없음). lock 은 트랜잭션 종료까지 유지된다.
     */
    private fun acquireUserLock(userId: UUID) {
        // pg_advisory_xact_lock 은 void 반환 — 결과 행은 소비(discard)한다. userId 는 text 로 바인딩.
        jdbc.queryForList(SQL_ACQUIRE_USER_LOCK, mapOf("userId" to userId.toString()))
    }

    /**
     * PAT 감사 이벤트를 기록한다. providerId 는 `"pat"`, metadata 엔 비밀값(token 원문/hash)을 담지 않는다(§1.1.2).
     *
     * 발급/폐기 트랜잭션 안에서 호출되므로 기록 실패 시 예외가 전파되어 트랜잭션이 롤백된다(fail-closed).
     */
    private fun emitAudit(
        userId: UUID,
        eventType: AuthEventType,
        metadata: Map<String, String>,
    ) {
        auditLogService.record(
            AuthAuditLog(
                userId = userId,
                eventType = eventType,
                providerId = AUDIT_PROVIDER_ID,
                metadata = metadata,
            ),
        )
    }

    private companion object {
        /** 사용자당 활성 PAT 개수 상한. 도달 시 [PatQuotaExceededException]. */
        const val MAX_ACTIVE_TOKENS = 20L

        /** 발급 만료 기간 하한(일). */
        const val MIN_EXPIRY_DAYS = 1

        /** 발급 만료 기간 상한(일). 무기한 금지 — 반드시 [MIN_EXPIRY_DAYS]..[MAX_EXPIRY_DAYS] 안이어야 한다. */
        const val MAX_EXPIRY_DAYS = 365

        /** PAT 감사 이벤트의 providerId 라벨(SSO provider 아님). */
        const val AUDIT_PROVIDER_ID = "pat"

        /** base62 알파벳(숫자+대문자+소문자). raw token body 를 이 집합에서 균등 추출한다. */
        const val BASE62_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        /** 스레드 안전한 CSPRNG. 인스턴스 재사용으로 시드 재초기화 비용 회피(TemporaryPasswordGenerator 선례). */
        val SECURE_RANDOM = SecureRandom()

        /**
         * 사용자 단위 advisory lock SQL — hashtextextended 로 UUID 텍스트 전폭을 bigint 로 해시.
         * pg_advisory_xact_lock(bigint) 단일 시그니처와 정합(bigint,bigint 시그니처 없음). 파라미터 바인딩.
         */
        const val SQL_ACQUIRE_USER_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(:userId, 0))"
    }
}
