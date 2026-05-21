// LDAP 인증 성공 후 users + user_external_accounts UPSERT Auto-provisioning 서비스

package com.atlas.bts.identity.provider.ldap

import com.atlas.bts.identity.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * LDAP 인증 성공 후 자동 프로비저닝 서비스 (FR-AU-09 Task 15 / SDD §19.2 supportsAutoProvisioning).
 *
 * **단일 트랜잭션 원칙 (DATA.md §6)**:
 * [provision] 은 users UPSERT + user_external_accounts UPSERT 를 하나의 @Transactional 경계 안에서 처리한다.
 * 어느 한 쪽이 실패하면 양쪽 모두 rollback 된다 (EC-17 부분 성공 금지).
 *
 * **UPSERT 멱등성 (DATA.md §5)**:
 * - users: ON CONFLICT (username) → email / displayName 갱신, id 보존.
 * - user_external_accounts: ON CONFLICT (provider_id, external_subject) → groups / updated_at 갱신.
 * EC-11 동시 race condition 은 ON CONFLICT 로 방어한다.
 *
 * **보안 (DEVELOPMENT.md §1.2)**:
 * 로그에 externalSubject(PII) 를 직접 출력하지 않는다. 로그에는 providerId 만 출력한다.
 *
 * **@Service 필수 (PR #6 learning #1)**:
 * @Transactional 적용 시 @Service (또는 @Component) 가 있어야 Spring AOP 프록시가 생성된다.
 * @Repository 는 도메인 데이터 접근 계층 전용이므로 여기에 사용하지 않는다.
 */
@Service
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class AutoProvisionService(
    private val userRepo: UserRepository,
    private val externalAccountRepo: ExternalAccountRepository,
) {
    private val log = LoggerFactory.getLogger(AutoProvisionService::class.java)

    /**
     * LDAP bind 성공 후 사용자 자동 프로비저닝 수행.
     *
     * 실행 순서 (FK 의존성):
     * 1. users UPSERT (UserRepository.provisionFromExternal)
     * 2. user_external_accounts UPSERT (ExternalAccountRepository.provisionUser)
     *
     * @param providerId authn_providers.id (LDAP 공급자 식별)
     * @param attrs LDAP 인증 결과에서 추출한 사용자 속성
     * @return 저장된 ExternalAccount — userId 를 포함하므로 Principal 구성에 사용
     * @throws RuntimeException DB 오류 시 예외 전파 → @Transactional rollback 트리거 (EC-17)
     */
    fun provision(
        providerId: UUID,
        attrs: LdapProvisionAttrs,
    ): ExternalAccount {
        log.debug("Auto-provisioning 시작 — providerId={}", providerId)

        // Step 1: users UPSERT — username 충돌 시 email/displayName 갱신 (id 보존)
        val user = userRepo.provisionFromExternal(
            username = attrs.username,
            email = attrs.email,
            displayName = attrs.displayName,
        )

        // Step 2: user_external_accounts UPSERT — Step 1 의 user.id 를 FK 로 사용 (REFERENCES users(id))
        val account = externalAccountRepo.provisionUser(
            providerId = providerId,
            externalSubject = attrs.externalSubject,
            userId = user.id,
            groups = attrs.groups,
        )

        log.debug("Auto-provisioning 완료 — providerId={}, userId={}", providerId, user.id)
        return account
    }
}
