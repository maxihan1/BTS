// Inbox 읽음/보관 상태 변경 + 목록 조회 + 미읽음 카운트를 담당하는 애플리케이션 서비스

package com.bts.notification.inbox.application

import com.bts.notification.domain.Notification
import com.bts.notification.repository.InboxQuery
import com.bts.notification.repository.NotificationRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Inbox 기능의 비즈니스 로직을 담당하는 애플리케이션 서비스.
 *
 * 트랜잭션 정책 (DATA.md §6).
 * - 읽기 메서드([listInbox], [unreadCount]): @Transactional(readOnly = true)
 * - 쓰기 메서드([markRead], [markArchive], [readAll]): @Transactional (기본값 REQUIRED)
 *
 * 소유권 검증 정책.
 * - 단건 상태 변경([markRead], [markArchive])은 repository 에서 본인(recipientUserId) + IN_APP 조건으로
 *   타인 항목을 차단한다. 영향받은 행이 0 이면 [InboxItemNotFoundException] (HTTP 404).
 * - 일괄 읽음([readAll])은 0건도 정상이므로 예외를 던지지 않는다.
 *
 * Clock 주입 (메모리 authcontroller-revokesession-timebomb).
 * - 시각 의존 로직은 [Clock] 을 주입받아 테스트에서 고정 시각으로 재현 가능하게 한다.
 *
 * @param repository Inbox 조회/상태변경을 담당하는 jOOQ repository
 * @param clock      현재 시각 공급자 — 테스트에서 고정 Clock 으로 대체 가능
 */
@Service
class InboxService(
    private val repository: NotificationRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Inbox 목록을 탭/검색 조건 + 페이지네이션으로 조회한다.
     *
     * repository.findInbox 에 위임하며 IN_APP + 본인 격리는 repository 가 보장한다.
     *
     * @param actorId  요청 주체 사용자 ID
     * @param query    탭/검색 조건 VO
     * @param pageable 페이지 정보
     * @return content + totalElements 포함 Page
     */
    @Transactional(readOnly = true)
    fun listInbox(
        actorId: UUID,
        query: InboxQuery,
        pageable: Pageable,
    ): Page<Notification> = repository.findInbox(actorId, query, pageable)

    /**
     * 수신자의 미읽음 IN_APP 알림 수를 반환한다.
     *
     * @param actorId 요청 주체 사용자 ID
     * @return 미읽음 알림 수
     */
    @Transactional(readOnly = true)
    fun unreadCount(actorId: UUID): Long = repository.countUnread(actorId)

    /**
     * 알림 1건의 읽음 상태를 변경한다.
     *
     * @param actorId 요청 주체 사용자 ID
     * @param id      대상 알림 ID
     * @param read    true = 읽음 처리(현재 시각), false = 미읽음 처리(null)
     * @throws InboxItemNotFoundException 대상이 없거나 본인 소유가 아닌 경우
     */
    @Transactional
    fun markRead(
        actorId: UUID,
        id: UUID,
        read: Boolean,
    ) {
        val readAt: Instant? = if (read) Instant.now(clock) else null
        val affected = repository.updateReadAt(id, actorId, readAt)
        if (affected == 0) {
            log.warn("읽음 변경 대상 없음 또는 권한 없음 — actorId={}", actorId)
            throw InboxItemNotFoundException()
        }
    }

    /**
     * 알림 1건의 보관 상태를 변경한다.
     *
     * @param actorId  요청 주체 사용자 ID
     * @param id       대상 알림 ID
     * @param archived true = 보관(현재 시각), false = 보관 해제(null)
     * @throws InboxItemNotFoundException 대상이 없거나 본인 소유가 아닌 경우
     */
    @Transactional
    fun markArchive(
        actorId: UUID,
        id: UUID,
        archived: Boolean,
    ) {
        val archivedAt: Instant? = if (archived) Instant.now(clock) else null
        val affected = repository.updateArchivedAt(id, actorId, archivedAt)
        if (affected == 0) {
            log.warn("보관 변경 대상 없음 또는 권한 없음 — actorId={}", actorId)
            throw InboxItemNotFoundException()
        }
    }

    /**
     * 본인의 미읽음 알림을 일괄 읽음 처리한다.
     *
     * 변경 건수가 0 이어도 정상이다 (일괄 작업은 NotFound 없음).
     *
     * @param actorId 요청 주체 사용자 ID
     * @param ids     변경 대상 ID 목록. null 또는 빈 목록이면 미읽음 전체.
     * @return 변경된 행 수
     */
    @Transactional
    fun readAll(
        actorId: UUID,
        ids: List<UUID>?,
    ): Int = repository.markAllRead(actorId, ids, Instant.now(clock))
}
