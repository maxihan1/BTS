// 전역 권한 부여/회수/목록을 오케스트레이션하는 애플리케이션 서비스 — grantee 존재검증 + permission 선검증 (FR-PM-10 Task 6)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.user.UserRepository
import com.bts.shared.permission.GlobalPermissionCodes
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 전역 권한 부여 애플리케이션 서비스 (FR-PM-10 Task 6).
 *
 * ## 책임
 * - **grantee 존재 검증** — [GranteeType] 에 따라 `users`([UserRepository]) 또는
 *   `user_groups`([UserGroupRepository]) 를 조회한다. 아래 §무결성 참조.
 * - **permission 선검증** — [ALLOWED_GLOBAL_PERMISSIONS] 화이트리스트. 아래 §이중 방어 참조.
 * - 영속 계층의 [DuplicateKeyException] 을 도메인 예외([DuplicateGrantException])로 변환.
 * - 회수 시 영향 행 0 을 [GrantNotFoundException] 으로 승격 (ADR D-5).
 *
 * ## 무결성은 여기가 진다 (ADR D-4) — 선택이 아니다
 * `grantee_id` 는 `granteeType` 에 따라 `users.id`/`user_groups.id` 를 가리키는 **다형 참조**라
 * PostgreSQL 단일 FK 로 표현할 수 없다. ADR D-4 는 *"서비스 층이 존재 검증"* 을 **FK 생략의 근거**로
 * 삼았으므로, [requireGranteeExists] 를 제거하면 그 ADR 이 거짓이 된다. USER 고아 행은 자동 정리
 * 기전이 없어(ADR 잔여 위험 1) **부여 시점의 이 검증이 유이한 사전 방어**다.
 *
 * ## 이중 방어 (ADR D-1) — DB CHECK 만 믿지 않는다
 * `permission` 은 REST 바디에서 오는 **사용자 입력**이다. V036 의 `CHECK (permission IN (...))` 가
 * 최후 방어이지만, 앱 겹이 없으면 미지 코드가 DB 까지 내려가 `DataIntegrityViolationException` → **500**
 * 으로 변질된다. 400(사용자 입력 오류)이어야 할 것을 서버 오류로 만들지 않기 위해 여기서 먼저 거른다.
 *
 * > 🛑 **권한코드 추가 시 3곳을 동시에 갱신한다** (ADR 잔여 위험 4) — SDD 12(`docs/sdd/12-permissions.md`,
 * > 정본) · V036 의 `permission` CHECK · [ALLOWED_GLOBAL_PERMISSIONS]. 하나만 놓치면 fail-closed 로
 * > 조용히 막힌다(코드는 늘렸는데 부여가 400/500 으로 거부됨).
 *
 * ## 트랜잭션 경계 (DATA.md §6 / DEVELOPMENT.md §1.2)
 * 클래스 레벨 `@Transactional(REQUIRED, READ_COMMITTED)` — 존재 검증 조회와 INSERT 를 한 트랜잭션에
 * 묶는다. 읽기 전용 [list] 는 `readOnly = true` 로 오버라이드한다. 선례 `UserGroupService`.
 *
 * @see docs/decisions/2026-07-17-global-permission-grants.md 설계 결정 ADR
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
class GlobalPermissionGrantService(
    private val grantRepository: GlobalPermissionGrantRepository,
    private val userRepository: UserRepository,
    private val userGroupRepository: UserGroupRepository,
) {
    /**
     * 전역 권한을 사용자/그룹에게 부여한다.
     *
     * permission 화이트리스트 → grantee 존재 → 부여 순서다. 화이트리스트를 **먼저** 두는 이유는
     * 미지 코드가 DB 조회를 유발하지 않게 하기 위함이다(입력 검증이 자원 접근보다 앞선다).
     *
     * @param permission 전역 권한코드. [ALLOWED_GLOBAL_PERMISSIONS] 만 허용.
     * @param granteeType 부여 대상 종류. [granteeId] 의 해석을 결정한다.
     * @param granteeId USER 면 `users.id`, GROUP 이면 `user_groups.id`.
     * @param grantedBy 부여한 SYSTEM_ADMIN 의 `users.id`. **호출자가 인증된 actor 를 명시로 넘긴다** —
     *   기본값을 두면 감사 흔적을 조용히 위조하는 통로가 된다(ADR D-5).
     * @return DB 에 반영된 [GlobalPermissionGrant].
     * @throws UnknownPermissionException 알려지지 않은 권한코드 (→ 400).
     * @throws GranteeNotFoundException 부여 대상이 존재하지 않음 (→ 404).
     * @throws DuplicateGrantException 같은 조합이 이미 부여됨 (→ 409).
     */
    fun grant(
        permission: String,
        granteeType: GranteeType,
        granteeId: UUID,
        grantedBy: UUID,
    ): GlobalPermissionGrant {
        if (permission !in ALLOWED_GLOBAL_PERMISSIONS) throw UnknownPermissionException(permission)
        requireGranteeExists(granteeType, granteeId)

        return try {
            grantRepository.grant(permission, granteeType, granteeId, grantedBy)
        } catch (ex: DuplicateKeyException) {
            throw DuplicateGrantException(permission, granteeType, granteeId).apply { initCause(ex) }
        }
    }

    /**
     * 부여된 전역 권한을 회수한다 — hard delete (ADR D-5).
     *
     * @param grantId 회수할 [GlobalPermissionGrant.id].
     * @throws GrantNotFoundException 해당 grant 행이 없는 경우 (→ 404). 리포지토리의 `false` 를
     *   삼키지 않는다 — "지웠다고 믿었는데 대상이 없었다"를 조용히 성공으로 만들지 않기 위함이다.
     */
    fun revoke(grantId: UUID) {
        if (!grantRepository.revoke(grantId)) throw GrantNotFoundException(grantId)
    }

    /**
     * 부여된 전역 권한 전량을 반환한다 (관리 UI 목록).
     *
     * USER 고아 행을 필터링하지 않는 것이 의도다 — 자동 정리 기전이 없으므로 이 목록이 유령 행을
     * **사후 발견하는 유일한 통로**다(ADR D-4 / 잔여 위험 1).
     *
     * @return 부여 시각 오름차순. 없으면 빈 목록.
     */
    @Transactional(readOnly = true)
    fun list(): List<GlobalPermissionGrant> = grantRepository.list()

    /**
     * 부여 대상이 실재하는지 확인한다 — FK 가 없으므로(ADR D-4) 앱이 지는 무결성이다.
     *
     * @throws GranteeNotFoundException 대상이 없는 경우.
     */
    private fun requireGranteeExists(
        granteeType: GranteeType,
        granteeId: UUID,
    ) {
        val exists =
            when (granteeType) {
                GranteeType.USER -> userRepository.findById(granteeId) != null
                GranteeType.GROUP -> userGroupRepository.existsById(granteeId)
            }
        if (!exists) throw GranteeNotFoundException(granteeType, granteeId)
    }

    internal companion object {
        /**
         * 부여 가능한 전역 권한코드 화이트리스트 — V036 의 `CHECK (permission IN ('CREATE_PROJECT'))`
         * 와 **같은 집합을 유지해야 한다**(ADR D-1 이중 방어 / 잔여 위험 4).
         *
         * 정본은 SDD 12(`docs/sdd/12-permissions.md`)이고, **문자열 리터럴의 단일 출처는
         * shared-kernel [GlobalPermissionCodes]** 다(2026-07-27 — 앱 겹 2곳이 각자 리터럴을
         * 들고 있던 것을 그 상수 참조로 통일했다). 코드 추가 시 고칠 곳은 SDD · 마이그레이션 CHECK ·
         * `GlobalPermissionCodes` 세 곳이다.
         *
         * ## `internal` 인 이유
         * 앱 겹과 DB 겹이 **같은 집합인지 자동으로 확인하는 테스트**
         * ([GlobalPermissionGrantSchemaMigrationTest] 의 화이트리스트↔CHECK 정합 단언)가
         * 이 값을 직접 읽어야 한다. `private` 이면 리플렉션이 필요해지고, 리플렉션 가드는
         * 필드명 변경에 조용히 깨진다. Kotlin `internal` 은 같은 Gradle 모듈 안에서만 보이므로
         * 다른 BC 로는 새지 않는다.
         */
        val ALLOWED_GLOBAL_PERMISSIONS = setOf(GlobalPermissionCodes.CREATE_PROJECT)
    }
}
