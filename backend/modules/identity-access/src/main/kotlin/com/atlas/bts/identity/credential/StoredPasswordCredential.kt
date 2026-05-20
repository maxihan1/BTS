// local_credentials 테이블 행 매핑 data class — Argon2id 해시 영속 저장 엔티티 (Local Provider 전용)

package com.atlas.bts.identity.credential

import java.time.Instant
import java.util.UUID

/**
 * Local Provider 사용자의 Argon2id 해시를 BTS DB에 영속 저장하는 엔티티.
 *
 * ## SPI [com.atlas.bts.identity.spi.Credential] 과의 구분
 * - `Credential` (sealed interface) — 인증 *시도* 입력 VO. 메모리 전용, DB 저장 안 됨.
 * - `StoredPasswordCredential` (이 클래스) — 인증 *결과* 저장. `local_credentials` 테이블에 영속.
 *
 * ## 필드
 * - [userId]: BTS 내부 사용자 식별자 (users.id FK). 테이블 PK.
 * - [passwordHash]: Argon2id 인코딩 문자열 (`$argon2id$v=19$m=65536,...`). 평문 절대 저장 금지.
 * - [algoVersion]: 해시 알고리즘 버전 식별자 (현재 `argon2id-v1`). 향후 알고리즘 마이그레이션 시 WHERE 필터 용도.
 * - [createdAt]: 행 최초 생성 시각. UPSERT 시 보존된다.
 * - [updatedAt]: 비밀번호 마지막 변경 시각.
 *
 * ## equals / hashCode
 * [userId] 기반으로만 동등성을 판단한다. 한 사용자에게 하나의 로컬 자격증명만 존재하기 때문이다.
 * 같은 [userId] 라면 [passwordHash] / [algoVersion] / 타임스탬프가 달라도 동일 엔티티로 취급된다.
 *
 * ## toString 보안
 * [passwordHash] 는 `***` 로 마스킹되어 로그/출력에 해시값이 노출되지 않는다.
 */
data class StoredPasswordCredential(
    val userId: UUID,
    val passwordHash: String,
    val algoVersion: String = "argon2id-v1",
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredPasswordCredential) return false
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()

    override fun toString(): String =
        "StoredPasswordCredential(userId=$userId, passwordHash=***, algoVersion=$algoVersion, createdAt=$createdAt, updatedAt=$updatedAt)"
}
