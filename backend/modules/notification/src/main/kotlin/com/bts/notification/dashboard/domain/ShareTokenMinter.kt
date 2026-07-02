// 대시보드 공유 토큰 발급기 — SecureRandom 원문 생성 + SHA-256 해싱(원문 미저장, PAT 패턴 미러)

package com.bts.notification.dashboard.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * 공유 토큰 발급 결과.
 *
 * plaintext 는 발급 응답에서 단 한 번만 노출되는 원문 토큰으로, DB 에 저장하지 않는다.
 * token 은 tokenHash(해시)만 보관하는 영속 엔티티로, 저장 대상이다.
 *
 * @param plaintext 원문 토큰 (base64url, 패딩 없음) — 응답 1회 노출용, 로그 출력 금지
 * @param token 저장 대상 공유 토큰 엔티티 (해시만 보관)
 */
data class MintedShareToken(
    val plaintext: String,
    val token: DashboardShareToken,
)

/**
 * 대시보드 공유 토큰 발급기.
 *
 * identity-access 의 PAT(Personal Access Token) 발급 패턴을 미러한다
 * (SecureRandom 원문 + SHA-256 해시, 원문 미저장). BC 격리상 해당 코드를 import 하지 않고
 * 패턴만 재현한다.
 *
 * 원문은 256bit(32바이트) 랜덤값을 base64url(패딩 없음) 로 인코딩한 불투명 문자열이며,
 * 저장·조회 시에는 SHA-256 hex(소문자 64자) 해시만 사용한다.
 *
 * SecureRandom 은 thread-safe 하므로 인스턴스 하나를 재사용한다.
 */
class ShareTokenMinter {
    private val secureRandom = SecureRandom()

    /**
     * 새 공유 토큰을 발급한다.
     *
     * 32바이트 랜덤 원문을 생성해 base64url 로 인코딩하고, 그 SHA-256 해시를 담은
     * DashboardShareToken 을 함께 반환한다. 원문(plaintext)은 반환 결과에만 담기고
     * 엔티티에는 저장되지 않는다.
     *
     * @param dashboardId 대상 대시보드 식별자
     * @param createdBy 발급 사용자 ID
     * @param expiresAt 만료 시각 (null = 무기한)
     * @param now 발급 시각 (호출자 Clock 에서 주입)
     * @return 원문 + 저장 대상 엔티티를 담은 MintedShareToken
     */
    fun mint(
        dashboardId: UUID,
        createdBy: UUID,
        expiresAt: Instant?,
        now: Instant,
    ): MintedShareToken {
        val rawBytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
        val token =
            DashboardShareToken(
                id = UUID.randomUUID(),
                dashboardId = dashboardId,
                tokenHash = hash(plaintext),
                createdBy = createdBy,
                createdAt = now,
                expiresAt = expiresAt,
            )
        return MintedShareToken(plaintext = plaintext, token = token)
    }

    /**
     * 원문 토큰을 SHA-256 hex(소문자 64자) 해시로 변환한다.
     *
     * 결정적 함수로, 동일 원문은 항상 동일 해시를 반환한다.
     * 조회 시 클라이언트가 제시한 원문을 재해싱해 저장된 tokenHash 와 비교하는 데 사용한다.
     *
     * MessageDigest 는 stateful·non-thread-safe 이므로 호출마다 새 인스턴스를 생성한다.
     *
     * @param plaintext 원문 토큰 문자열
     * @return SHA-256 hex 소문자 64자 해시
     */
    fun hash(plaintext: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(plaintext.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** 원문 토큰 엔트로피 바이트 수 (256bit) */
        const val TOKEN_BYTES: Int = 32

        /** 해시 알고리즘 식별자 */
        private const val SHA_256: String = "SHA-256"
    }
}
