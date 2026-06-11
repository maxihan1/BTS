// NotificationPolicyRepository — notification_policies 테이블 jOOQ DSL 접근. DATA.md §5, §6 준수.

package com.bts.notification.repository

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationPolicy
import com.bts.notification.domain.RecipientRole
import com.bts.notification.jooq.tables.records.NotificationPoliciesRecord
import com.bts.notification.jooq.tables.references.NOTIFICATION_POLICIES
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * notification_policies 테이블의 CRUD + 평가 조회를 담당하는 Repository.
 *
 * jOOQ DSL 만 사용한다 — SQL 문자열 결합 금지 (DATA.md §5).
 * 모든 public 메서드에 [Transactional] 을 명시한다 (DATA.md §6).
 * ArchUnit 룰4: jOOQ 접근은 repository 레이어에만 허용.
 * ArchUnit 룰5: @Transactional 보유 클래스는 @Repository/@Service/@Component 필수.
 *
 * @param dsl jOOQ DSLContext — SQL 을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
 */
@Repository
class NotificationPolicyRepository(
    private val dsl: DSLContext,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 알림 정책 1건을 삽입하고 DB 에서 생성된 행(타임스탬프 포함)을 반환한다.
     *
     * UNIQUE 제약(uq_notification_policy) 위반 시 jOOQ 가 [org.springframework.dao.DuplicateKeyException] 으로
     * 래핑해 던진다 — 호출자(Task 5 서비스)가 409 매핑.
     *
     * @param policy 저장할 정책 도메인 객체
     * @return DB 에서 읽어온 최신 행 (id/created_at/updated_at DB 기본값 포함)
     */
    @Transactional
    fun insert(policy: NotificationPolicy): NotificationPolicy {
        log.debug("알림 정책 삽입 — projectKey={}, eventType={}, role={}, channel={}",
            policy.projectKey, policy.eventType.wireValue, policy.recipientRole, policy.channel)

        val record = dsl.insertInto(NOTIFICATION_POLICIES)
            .set(NOTIFICATION_POLICIES.ID, policy.id)
            .set(NOTIFICATION_POLICIES.PROJECT_KEY, policy.projectKey)
            .set(NOTIFICATION_POLICIES.EVENT_TYPE, policy.eventType.wireValue)
            .set(NOTIFICATION_POLICIES.RECIPIENT_ROLE, policy.recipientRole.name)
            .set(NOTIFICATION_POLICIES.CHANNEL, policy.channel.name)
            .set(NOTIFICATION_POLICIES.ENABLED, policy.enabled)
            .set(NOTIFICATION_POLICIES.CREATED_BY, policy.createdBy)
            .returning()
            .fetchOne()
            ?: error("INSERT 후 행 반환 실패 — id=${policy.id}")

        return toDomain(record)
    }

    /**
     * id 로 알림 정책 1건을 조회한다.
     *
     * @param id 조회할 정책 식별자
     * @return 조회된 정책, 없으면 null
     */
    @Transactional(readOnly = true)
    fun findById(id: UUID): NotificationPolicy? {
        return dsl.selectFrom(NOTIFICATION_POLICIES)
            .where(NOTIFICATION_POLICIES.ID.eq(id))
            .fetchOne()
            ?.let { toDomain(it) }
    }

    /**
     * 범위별 정책 목록을 조회한다.
     *
     * - [projectKey] == null → PROJECT_KEY IS NULL (전역 기본 정책)
     * - [projectKey] != null → PROJECT_KEY = projectKey (해당 프로젝트 전용 정책)
     *
     * @param projectKey 조회 범위. null 이면 전역 정책만, non-null 이면 해당 프로젝트 정책만.
     * @return 조건에 맞는 정책 목록
     */
    @Transactional(readOnly = true)
    fun findAll(projectKey: String?): List<NotificationPolicy> {
        val condition = if (projectKey == null) {
            NOTIFICATION_POLICIES.PROJECT_KEY.isNull
        } else {
            NOTIFICATION_POLICIES.PROJECT_KEY.eq(projectKey)
        }

        return dsl.selectFrom(NOTIFICATION_POLICIES)
            .where(condition)
            .fetch()
            .map { toDomain(it) }
    }

    /**
     * 이벤트 유형 + 프로젝트 키 기준으로 정책 목록을 조회한다.
     *
     * 평가 엔진이 "이 프로젝트에 대한 정책이 존재하는가?" 를 판정하기 위해 호출한다.
     * **enabled 여부에 관계없이** 해당 조합의 모든 행을 반환한다 — spec §6.
     *
     * - [projectKey] == null → PROJECT_KEY IS NULL (전역)
     * - [projectKey] != null → PROJECT_KEY = projectKey
     *
     * @param eventType 이벤트 유형 wireValue 문자열 (예: "issue.created")
     * @param projectKey null 이면 전역 범위, non-null 이면 특정 프로젝트 범위
     * @return 조건에 맞는 정책 목록 (enabled 무관 전체)
     */
    @Transactional(readOnly = true)
    fun findByEventTypeAndProjectKey(eventType: String, projectKey: String?): List<NotificationPolicy> {
        val projectCondition = if (projectKey == null) {
            NOTIFICATION_POLICIES.PROJECT_KEY.isNull
        } else {
            NOTIFICATION_POLICIES.PROJECT_KEY.eq(projectKey)
        }

        return dsl.selectFrom(NOTIFICATION_POLICIES)
            .where(NOTIFICATION_POLICIES.EVENT_TYPE.eq(eventType))
            .and(projectCondition)
            .fetch()
            .map { toDomain(it) }
    }

    /**
     * 정책의 enabled 상태와 updated_at 을 갱신한다.
     *
     * @param id 갱신할 정책 식별자
     * @param enabled 변경할 활성 여부
     * @param now 갱신 시각 (호출자 Clock 에서 주입)
     * @return 영향받은 행 수 (0 이면 해당 id 미존재)
     */
    @Transactional
    fun toggle(id: UUID, enabled: Boolean, now: Instant): Int {
        log.debug("알림 정책 toggle — id={}, enabled={}", id, enabled)

        return dsl.update(NOTIFICATION_POLICIES)
            .set(NOTIFICATION_POLICIES.ENABLED, enabled)
            .set(NOTIFICATION_POLICIES.UPDATED_AT, now.atOffset(ZoneOffset.UTC))
            .where(NOTIFICATION_POLICIES.ID.eq(id))
            .execute()
    }

    /**
     * 정책 1건을 삭제한다.
     *
     * 설정 데이터이므로 하드 삭제 허용 (이슈 키와 달리 이력 보존 불필요).
     *
     * @param id 삭제할 정책 식별자
     * @return 영향받은 행 수 (0 이면 해당 id 미존재)
     */
    @Transactional
    fun delete(id: UUID): Int {
        log.debug("알림 정책 삭제 — id={}", id)

        return dsl.deleteFrom(NOTIFICATION_POLICIES)
            .where(NOTIFICATION_POLICIES.ID.eq(id))
            .execute()
    }

    /**
     * jOOQ [NotificationPoliciesRecord] 를 도메인 [NotificationPolicy] 로 변환한다.
     *
     * enum 역매핑 시 null 이면 DB 에 유효하지 않은 값이 있음을 의미하므로 IllegalStateException 으로 fail-fast.
     *
     * @param record jOOQ 에서 읽어온 DB 레코드
     * @return 변환된 도메인 객체
     * @throws IllegalStateException DB 에 알 수 없는 enum 문자열이 저장된 경우 (데이터 손상)
     */
    private fun toDomain(record: NotificationPoliciesRecord): NotificationPolicy {
        val eventType = NotificationEventType.fromWire(record.eventType)
            ?: throw IllegalStateException("알 수 없는 event_type 값: ${record.eventType} — DB 데이터 손상")

        val recipientRole = RecipientRole.fromWire(record.recipientRole)
            ?: throw IllegalStateException("알 수 없는 recipient_role 값: ${record.recipientRole} — DB 데이터 손상")

        val channel = Channel.fromWire(record.channel)
            ?: throw IllegalStateException("알 수 없는 channel 값: ${record.channel} — DB 데이터 손상")

        val createdAt = record.createdAt?.toInstant()
            ?: throw IllegalStateException("created_at 이 null — id=${record.id}")

        val updatedAt = record.updatedAt?.toInstant()
            ?: throw IllegalStateException("updated_at 이 null — id=${record.id}")

        return NotificationPolicy(
            id = record.id ?: throw IllegalStateException("id 가 null — DB 데이터 손상"),
            projectKey = record.projectKey,
            eventType = eventType,
            recipientRole = recipientRole,
            channel = channel,
            enabled = record.enabled ?: throw IllegalStateException("enabled 가 null — id=${record.id}"),
            createdBy = record.createdBy,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

}
