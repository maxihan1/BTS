// 비-prod 조립 부팅 시 로그인 가능한 최소 dev 계정을 보장하는 시더 (조립 모듈 전용, 멱등)

package com.bts.app

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.credential.StoredPasswordCredentialRepository
import com.atlas.bts.identity.permission.GlobalPermissionGrantRepository
import com.atlas.bts.identity.permission.GranteeType
import com.atlas.bts.identity.user.UserRepository
import com.bts.shared.permission.GlobalPermissionCodes
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 비-prod 조립 부팅에서 **로그인 가능한 최소 상태**를 보장한다 (dev 손검증 경로 개방).
 *
 * ## 왜 조립 모듈에 있나 — 그리고 왜 여기 **한 벌만** 있나
 * 시드 대상 데이터가 `users`·`local_credentials`·전역 부여로 identity-access 소속이지만, 이 문제는
 * **조립 계층의 문제**다. #321 이 확립한 A안 — *"조립 계층의 결함은 조립 모듈에서 봉합한다"* — 을 따른다.
 *
 * 과거에는 모듈마다 `data-dev.sql` 이 있었고 두 모듈이 같은 리소스 이름(`classpath:data-dev.sql`)을 써서
 * **조립 클래스패스에서 충돌**해 조립에서는 애초에 비활성이었다. 2026-07-29 에 그 파일들을 삭제하고
 * 이 클래스 한 벌로 **단일화**했다 — 같은 목적의 장치 2벌은 시간이 지나면 어긋나고 한쪽만 고치는 사고가 난다.
 * 부수 효과로 **해시 리터럴을 SQL 에 박아 두는 문제 자체가 사라졌다**(실 인코더가 기동 시 계산한다).
 *
 * ⇒ 개별 BC 를 **단독으로** dev 프로파일로 띄우면 시드가 없다. 조립 앱으로 띄울 것.
 *
 * ## ★ `CreateLocalAccountService` 를 쓰면 안 된다
 * 이름은 정확히 맞아 보이지만 의미가 다르다 — `create():78-81` 이 **무작위** 임시 비밀번호를 만들고
 * `mustChange = true` 로 저장한다. 쓰면 (a) 시드 비밀번호를 정할 수 없고 (b) 로그인 직후 비밀번호 변경
 * 화면으로 강제 이동해 **손검증 경로를 여는 목적 자체가 깨진다**.
 * 그래서 한 단계 아래인 [UserRepository.create] + [LocalCredentialService.store] 를 직접 조합한다.
 * 둘 다 프로덕션 컴포넌트이므로 "스텁 금지"(#321 D7) 는 그대로 충족된다.
 *
 * ## ★ `SYSTEM_ADMIN` 을 주면 안 된다
 * `MfaEnforcementPolicy:24` *"관리자(SYSTEM_ADMIN)는 무조건 강제 대상이다"* · `:71`
 * `systemPermissionResolver.isSystemAdmin(userId) ||` → `JwtIssuer:109` 가 `mfa_enrollment_required=true` 를
 * 토큰에 박고 → `MfaEnrollmentGateFilter:54` 가 허용목록 밖 **전 경로를 403** 으로 막는다.
 * 즉 관리자로 시드하면 로그인은 되지만 **어떤 화면에도 못 간다**.
 * 대신 `CREATE_PROJECT` **전역 부여**만 준다 — 판정식이
 * `IdentityAccessSystemPermissionResolver:66` `hasGrant(actorId, permission) || isSystemAdmin(actorId)` 라
 * 앞항만으로 프로젝트 생성이 열리고 MFA 강제는 켜지지 않는다.
 * 회귀 가드는 [NonProdDevSeedBootTest] 의 `mfa 강제 등록 대상이 아니다`.
 *
 * ## 멱등 — 없는 것만 채우고 있는 것은 건드리지 않는다
 * 세 쓰기 각각 앞에 존재 조회를 둔다. 부분 상태(사용자만 있고 자격증명 없음)도 없는 쪽만 채운다.
 * **기존 비밀번호가 다르면 덮어쓰지 않는다** — 멱등 원칙이 편의보다 우선이며, 덮어쓰면 사람이 바꾼
 * 비밀번호를 재부팅이 조용히 되돌린다.
 *
 * ## 트랜잭션
 * 클래스 레벨 `@Transactional` — 세 쓰기가 한 트랜잭션이라 부분 시드가 남지 않는다.
 * 실패 시 catch 는 **이 경계 밖**([NonProdDevSeedRunner])에서 한다. 안에서 잡으면 롤백이 안 걸린다.
 *
 * ## 평문 수명 (DEVELOPMENT.md §1.1)
 * [LocalCredentialService.store] 가 전달받은 `CharArray` 를 해시 후 wipe 한다. 그래서 사본을 만들지 않고
 * 변환 즉시 넘긴다. 설정에서 온 `String` 은 Spring 이 보유하는 값이라 이 클래스가 수명을 통제하지 못한다 —
 * 그것이 이 시드가 **비-prod 전용**이어야 하는 이유 중 하나다.
 */
@Component
@Profile(NonProdDevSeedRunner.PROFILE_EXPRESSION)
@Transactional
class NonProdDevSeeder(
    private val userRepository: UserRepository,
    private val credentialRepository: StoredPasswordCredentialRepository,
    private val localCredentialService: LocalCredentialService,
    private val grantRepository: GlobalPermissionGrantRepository,
    @Value("\${bts.dev-seed.username:alice}") private val username: String,
    @Value("\${bts.dev-seed.password:password}") private val password: String,
    @Value("\${bts.dev-seed.email:alice@bts.local}") private val email: String,
    @Value("\${bts.dev-seed.display-name:Alice (Dev Seed)}") private val displayName: String,
) : DevSeeder {
    private val log = LoggerFactory.getLogger(NonProdDevSeeder::class.java)

    /**
     * 로그인 가능한 최소 상태를 보장한다. 이미 있으면 아무것도 바꾸지 않는다.
     *
     * 비밀번호는 **절대 로깅하지 않는다** (DEVELOPMENT.md §1.1 규칙 2).
     */
    override fun seed() {
        val existing = userRepository.findByUsername(username)
        val user = existing ?: userRepository.create(username, email, displayName)
        if (existing == null) {
            log.info("dev seed: created user username={}", username)
        }

        if (credentialRepository.findByUserId(user.id) == null) {
            localCredentialService.store(user.id, password.toCharArray(), mustChange = false)
            log.info("dev seed: stored local credential for username={}", username)
        }

        if (!grantRepository.hasGrant(user.id, GlobalPermissionCodes.CREATE_PROJECT)) {
            // grantedBy 는 자기 자신 — dev 시드에는 부여 주체가 될 관리자가 없다.
            grantRepository.grant(
                GlobalPermissionCodes.CREATE_PROJECT,
                GranteeType.USER,
                user.id,
                user.id,
            )
            log.info("dev seed: granted {} to username={}", GlobalPermissionCodes.CREATE_PROJECT, username)
        }
    }
}
