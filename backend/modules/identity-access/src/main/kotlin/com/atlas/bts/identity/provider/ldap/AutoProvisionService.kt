// LDAP 인증 성공 후 users + user_external_accounts UPSERT Auto-provisioning 서비스

package com.atlas.bts.identity.provider.ldap

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 외부 IdP 인증 성공 후 자동 프로비저닝 서비스 (FR-AU-09 Task 15 / SDD §19.2 supportsAutoProvisioning).
 *
 * **공통 컴포넌트 (LDAP 전용 아님)**:
 * 이 서비스는 LDAP 전용이 아니라 외부 IdP(Identity Provider) 프로비저닝 공통 컴포넌트다.
 * SAML SSO(FR-AU-03)도 이 클래스를 이동·복제 없이 그대로 import 해 재사용한다.
 * 패키지 위치는 `provider/ldap/` 로 유지하되, 역할은 모든 외부 IdP 의 users +
 * user_external_accounts UPSERT 진입점이다(게이트1 옵션 C — 이동 없이 재사용).
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
    private val auditLog: AuthAuditLogService,
) {
    private val log = LoggerFactory.getLogger(AutoProvisionService::class.java)

    /**
     * LDAP bind 성공 후 사용자 자동 프로비저닝 수행.
     *
     * 실행 순서 (FK 의존성):
     * 1. users UPSERT (UserRepository.provisionFromExternal)
     * 2. user_external_accounts UPSERT (ExternalAccountRepository.provisionUser)
     *
     * **감사 emit (FR-AU-10):**
     * Step 1 이 신규 INSERT 였을 때만([ProvisionResult.isNew]) [AuthEventType.USER_PROVISIONED] 를 감사 로그에 기록한다.
     * 기존 사용자 외부 재로그인(ON CONFLICT UPDATE)은 emit 하지 않는다 (EC-3).
     * service 레이어 `@Transactional` 경계 내 동기 기록이라 프로비저닝 UPSERT 와 원자적으로 커밋/롤백된다.
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

        // Step 1: users UPSERT — username 충돌 시 email/displayName 갱신 (id 보존). isNew 로 신규 INSERT 여부 노출.
        val (user, isNew) = userRepo.provisionFromExternal(
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

        // FR-AU-10: 신규 INSERT 일 때만 USER_PROVISIONED 감사 기록 (기존 재로그인은 emit 안 함, EC-3)
        if (isNew) {
            auditLog.record(
                AuthAuditLog(
                    userId = user.id,
                    eventType = AuthEventType.USER_PROVISIONED,
                    providerId = providerId.toString(),
                    metadata = mapOf("username" to attrs.username),
                ),
            )
        }

        log.debug("Auto-provisioning 완료 — providerId={}, userId={}", providerId, user.id)
        return account
    }
}
