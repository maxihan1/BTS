// MFA 백업 코드(user_mfa_backup_codes) 영속 연산 추상 — 재발급(교체)/atomic 소진/카운트/삭제 (FR-MF-02 Task 4)

package com.atlas.bts.identity.mfa

import java.util.UUID

/**
 * MFA(다단계 인증) 1회용 백업 코드의 영속 연산 추상(`user_mfa_backup_codes`, FR-MF-02 Task 4). SDD §19.7.
 *
 * 백업 코드는 SHA-256 해시([BackupCodeHasher])로만 저장되며, 본 Repository 는 해시 문자열만 다룬다.
 * 평문 코드는 절대 다루지 않는다(DEVELOPMENT.md §1.1.1·1.1.2).
 *
 * 구현은 [JdbcMfaBackupCodeRepository] 이며, 테스트 대역 교체와 의존 역전을 위해 인터페이스로 노출한다.
 */
interface MfaBackupCodeRepository {
    /**
     * 사용자의 기존 백업 코드를 전량 삭제하고 새 해시 묶음으로 교체한다(재발급).
     *
     * 사용/미사용 무관 이전 묶음을 모두 무효화한 뒤 [codeHashes] 를 INSERT 한다.
     * 삭제와 삽입은 단일 트랜잭션으로 처리되어, 중간 실패 시 이전 코드가 부분 삭제된 채 남지 않는다.
     *
     * @param userId 사용자 식별자(`users.id`).
     * @param codeHashes 새로 발급할 백업 코드의 SHA-256 해시 목록(빈 목록이면 기존 코드만 삭제).
     */
    fun replaceAll(
        userId: UUID,
        codeHashes: List<String>,
    )

    /**
     * 미사용 백업 코드 한 개를 atomic 하게 소진(used_at 설정)한다.
     *
     * 같은 코드를 두 번 소진하거나, 존재하지 않는 해시면 `false` 를 반환한다(멱등).
     *
     * @param userId 사용자 식별자.
     * @param codeHash 사용자 입력 코드의 SHA-256 해시.
     * @return 미사용 코드를 이번 호출로 소진했으면 `true`, 이미 사용됐거나 없으면 `false`.
     */
    fun consumeIfUnused(
        userId: UUID,
        codeHash: String,
    ): Boolean

    /**
     * 사용자의 미사용(used_at IS NULL) 백업 코드 개수를 반환한다(상태 표시용).
     *
     * @param userId 사용자 식별자.
     * @return 남은 미사용 코드 수.
     */
    fun countUnused(userId: UUID): Int

    /**
     * 사용자의 전체 백업 코드 개수(사용/미사용 합)를 반환한다(상태 표시용).
     *
     * @param userId 사용자 식별자.
     * @return 전체 코드 수.
     */
    fun countTotal(userId: UUID): Int

    /**
     * 사용자의 백업 코드를 전량 삭제한다(MFA disable 등).
     *
     * @param userId 사용자 식별자.
     */
    fun deleteAllByUser(userId: UUID)
}
