// local_credentials 테이블 행 매핑 data class — Argon2id 해시 영속 저장 엔티티 (Local Provider 전용)

package com.atlas.bts.identity.credential

import java.time.Instant
import java.util.UUID

/**
 * Local Provider 사용자의 Argon2id 해시를 BTS DB에 영속 저장하는 엔티티.
 *
 * ## SPI [com.atlas.bts.identity.spi.Credential] 과의 구분
 *
 * 이름이 유사하여 혼동하기 쉬우나 역할이 완전히 다르다.
 *
 * | | `Credential` (SPI) | `StoredPasswordCredential` (이 클래스) |
 * |-|-|-|
 * | 역할 | 인증 *시도* 입력 VO | 인증 *결과* 영속 저장 엔티티 |
 * | 위치 | 메모리 전용, DB 저장 안 됨 | `local_credentials` 테이블 영속 |
 * | Provider 범위 | 모든 Provider (Local/LDAP/OIDC 등) | Local Provider 전용 |
 *
 * LDAP / OIDC / SAML Provider 는 외부 시스템이 자체 저장하므로 이 클래스를 사용하지 않는다.
 *
 * ## 필드
 * - [userId]: BTS 내부 사용자 식별자 (`users.id` FK). 테이블 PK. 사용자 삭제 시 ON DELETE CASCADE.
 * - [passwordHash]: Argon2id 인코딩 문자열 (`$argon2id$v=19$m=65536,t=3,p=4$<salt>$<hash>`).
 *   **평문 절대 저장 금지** (DEVELOPMENT.md §1.1). [toString]에서 `***`로 마스킹된다.
 * - [algoVersion]: 해시 알고리즘 버전 식별자 (현재 `argon2id-v1` 단일).
 *   향후 알고리즘 변경 시 `WHERE algo_version = 'argon2id-v1'` batch 재해시에 사용된다.
 * - [createdAt]: 행 최초 생성 시각. UPSERT 시 보존된다 (비밀번호 변경 시에도 갱신 안 됨).
 * - [updatedAt]: 비밀번호 마지막 변경 시각. UPSERT 시 `now()`로 갱신된다.
 * - [mustChangePassword]: 강제 비밀번호 변경 플래그 (FR-AU-05). `true`면 다음 로그인 시 변경 필수.
 *   관리자가 임시 비밀번호로 계정을 생성하면 `true`로 저장된다. 정상 변경(`rotate`) 성공 시
 *   UPSERT 의 `ON CONFLICT DO UPDATE SET` 경로로 `false`로 자동 해제된다 (별도 해제 메서드 없음).
 *
 * ## equals / hashCode
 * [userId] 기반으로만 동등성을 판단한다. 한 사용자에게 하나의 로컬 자격증명만 존재하기 때문이다.
 * 같은 [userId] 라면 [passwordHash] / [algoVersion] / 타임스탬프가 달라도 동일 엔티티로 취급된다.
 *
 * ## toString 보안
 * [passwordHash] 는 `***` 로 마스킹되어 로그/출력에 해시값이 노출되지 않는다.
 * Logback / Pino 어느 쪽으로 직렬화되어도 평문 해시가 로그에 남지 않는다.
 *
 * @see com.atlas.bts.identity.spi.Credential 인증 시도 입력 VO (메모리 전용, 이 클래스와 혼동 주의)
 */
data class StoredPasswordCredential(
    val userId: UUID,
    val passwordHash: String,
    val algoVersion: String = "argon2id-v1",
    val createdAt: Instant,
    val updatedAt: Instant,
    val mustChangePassword: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredPasswordCredential) return false
        return userId == other.userId
    }

    override fun hashCode(): Int = userId.hashCode()

    override fun toString(): String =
        "StoredPasswordCredential(" +
            "userId=$userId, passwordHash=***, algoVersion=$algoVersion, " +
            "createdAt=$createdAt, updatedAt=$updatedAt, mustChangePassword=$mustChangePassword)"
}
