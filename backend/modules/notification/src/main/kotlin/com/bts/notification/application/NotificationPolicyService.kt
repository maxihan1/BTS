// 알림 정책 CRUD 애플리케이션 서비스 — 권한 게이트 + 도메인 객체 생성 + repository 위임

package com.bts.notification.application

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationPolicy
import com.bts.notification.domain.RecipientRole
import com.bts.notification.repository.NotificationPolicyRepository
import com.bts.shared.permission.SystemPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * 알림 정책(NotificationPolicy) CRUD 를 담당하는 애플리케이션 서비스.
 *
 * 모든 조작은 시스템 관리자(SYSTEM_ADMIN)만 허용한다.
 * 권한 판정은 shared-kernel 의 [SystemPermissionResolver] 포트를 통해 수행하므로
 * identity-access BC 내부를 직접 의존하지 않는다 (BC 격리 원칙).
 *
 * Clock 은 생성자로 주입받아 테스트 결정성을 보장한다
 * (memory: AuthController revokeSession time-bomb 교훈).
 *
 * @param repository 알림 정책 jOOQ 저장소
 * @param systemPermissionResolver 전역 관리자 판정 포트
 * @param clock 시각 주입 (기본값 UTC — 테스트에서 고정 Clock 으로 교체)
 */
@Service
class NotificationPolicyService(
    private val repository: NotificationPolicyRepository,
    private val systemPermissionResolver: SystemPermissionResolver,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 요청 행위자가 시스템 관리자인지 검증한다.
     *
     * 관리자가 아닌 경우 [NotificationPolicyForbiddenException] 을 던진다.
     * message 에 actorId 나 권한 상세를 포함하지 않는다 — 내부 정보 누출 방지.
     *
     * @param actorId 검증할 행위자 UUID
     * @throws NotificationPolicyForbiddenException 관리자가 아닌 경우
     */
    private fun requireSystemAdmin(actorId: UUID) {
        if (!systemPermissionResolver.isSystemAdmin(actorId)) {
            throw NotificationPolicyForbiddenException("알림 정책 관리 권한이 없습니다")
        }
    }

    /**
     * 새 알림 정책을 생성한다.
     *
     * UNIQUE 제약(projectKey + eventType + recipientRole + channel) 위반 시
     * [NotificationPolicyDuplicateException] 으로 변환해 던진다.
     *
     * @param actorId 요청 행위자 UUID (시스템 관리자여야 함)
     * @param projectKey 프로젝트 범위 (null = 전역 기본 정책)
     * @param eventType 알림을 발생시킬 이벤트 유형
     * @param recipientRole 알림을 수신할 역할
     * @param channel 알림 전송 채널
     * @param enabled 정책 초기 활성 여부
     * @return 저장된 [NotificationPolicy] (DB 에서 반환된 타임스탬프 포함)
     * @throws NotificationPolicyForbiddenException 권한 없음
     * @throws NotificationPolicyDuplicateException 동일 조합 정책 중복
     */
    @Transactional
    // LongParameterList — 도메인 조합 키 6종, 각각 독립 의미. 커맨드 객체 도입 시 오히려 과설계.
    // ThrowsCount — UNIQUE 위반의 3경로(Spring DuplicateKey/DataIntegrity + jOOQ native) 변환은 본질적 다중 throw.
    @Suppress("LongParameterList", "ThrowsCount")
    fun create(
        actorId: UUID,
        projectKey: String?,
        eventType: NotificationEventType,
        recipientRole: RecipientRole,
        channel: Channel,
        enabled: Boolean,
    ): NotificationPolicy {
        requireSystemAdmin(actorId)

        val now = clock.instant()
        val policy =
            NotificationPolicy(
                id = UUID.randomUUID(),
                projectKey = projectKey,
                eventType = eventType,
                recipientRole = recipientRole,
                channel = channel,
                enabled = enabled,
                createdBy = actorId,
                createdAt = now,
                updatedAt = now,
            )

        log.info(
            "알림 정책 생성 — actorId={}, projectKey={}, eventType={}, role={}, channel={}",
            actorId,
            projectKey,
            eventType.wireValue,
            recipientRole,
            channel,
        )

        val duplicateMessage =
            "동일한 알림 정책이 이미 존재합니다. " +
                "eventType=${eventType.wireValue}, role=$recipientRole, channel=$channel"

        return try {
            repository.insert(policy)
        } catch (ex: DuplicateKeyException) {
            // Spring ExceptionTranslator 활성 컨텍스트(운영 조립체) — DuplicateKeyException 으로 도착.
            throw NotificationPolicyDuplicateException(duplicateMessage, ex)
        } catch (ex: DataIntegrityViolationException) {
            // Spring ExceptionTranslator 활성 컨텍스트 — 다른 무결성 위반도 중복으로 처리.
            throw NotificationPolicyDuplicateException(duplicateMessage, ex)
        } catch (ex: org.jooq.exception.DataAccessException) {
            // Spring ExceptionTranslator 비활성 컨텍스트(예: plain DSL.using()) — jOOQ native 로 도착.
            // SQLState 23505(unique_violation)만 중복으로 변환, 그 외는 그대로 전파.
            // (project-workflow SchemeIssueTypeMappingRepository 동형 — 조립 환경 무관 409 보장)
            if (isUniqueViolation(ex)) {
                throw NotificationPolicyDuplicateException(duplicateMessage, ex)
            }
            throw ex
        }
    }

    /**
     * jOOQ native [org.jooq.exception.DataAccessException] 의 cause chain 에서
     * SQLState `23505`(unique_violation)를 찾아 UNIQUE 위반인지 판정한다.
     *
     * @param ex 검사할 jOOQ 예외
     * @return cause chain 에 SQLState 23505 SQLException 이 있으면 true
     */
    private fun isUniqueViolation(ex: org.jooq.exception.DataAccessException): Boolean {
        val sqlEx =
            generateSequence(ex as Throwable?) { it.cause }
                .filterIsInstance<java.sql.SQLException>()
                .firstOrNull()
        return sqlEx?.sqlState == SQL_STATE_UNIQUE_VIOLATION
    }

    /**
     * 범위별 알림 정책 목록을 조회한다.
     *
     * @param actorId 요청 행위자 UUID (시스템 관리자여야 함)
     * @param projectKey null = 전역 기본 정책 목록, non-null = 해당 프로젝트 정책 목록
     * @return 조건에 맞는 [NotificationPolicy] 목록
     * @throws NotificationPolicyForbiddenException 권한 없음
     */
    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        projectKey: String?,
    ): List<NotificationPolicy> {
        requireSystemAdmin(actorId)
        return repository.findAll(projectKey)
    }

    /**
     * 알림 정책의 활성/비활성 상태를 전환한다.
     *
     * @param actorId 요청 행위자 UUID (시스템 관리자여야 함)
     * @param id 전환할 정책 식별자
     * @param enabled 변경할 활성 여부
     * @throws NotificationPolicyForbiddenException 권한 없음
     * @throws NotificationPolicyNotFoundException 해당 id 정책 미존재
     */
    @Transactional
    fun toggle(
        actorId: UUID,
        id: UUID,
        enabled: Boolean,
    ) {
        requireSystemAdmin(actorId)

        val affected = repository.toggle(id, enabled, clock.instant())
        if (affected == 0) {
            throw NotificationPolicyNotFoundException(id)
        }

        log.info("알림 정책 toggle — id={}, enabled={}", id, enabled)
    }

    /**
     * 알림 정책을 삭제한다.
     *
     * @param actorId 요청 행위자 UUID (시스템 관리자여야 함)
     * @param id 삭제할 정책 식별자
     * @throws NotificationPolicyForbiddenException 권한 없음
     * @throws NotificationPolicyNotFoundException 해당 id 정책 미존재
     */
    @Transactional
    fun delete(
        actorId: UUID,
        id: UUID,
    ) {
        requireSystemAdmin(actorId)

        val affected = repository.delete(id)
        if (affected == 0) {
            throw NotificationPolicyNotFoundException(id)
        }

        log.info("알림 정책 삭제 — id={}", id)
    }

    private companion object {
        /** PostgreSQL UNIQUE 제약 위반 SQLState (unique_violation). */
        private const val SQL_STATE_UNIQUE_VIOLATION = "23505"
    }
}
