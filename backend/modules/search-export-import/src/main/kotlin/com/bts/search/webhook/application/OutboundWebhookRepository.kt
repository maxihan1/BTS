// 아웃바운드 webhook 구독 영속성 포트 인터페이스 — application 계층 DIP 경계 (FR-API-03 PR2)

package com.bts.search.webhook.application

import com.bts.search.webhook.domain.OutboundWebhook
import java.util.UUID

/**
 * 아웃바운드 webhook 구독 영속성 포트 인터페이스 (Port Interface — 의존성 역전 원칙 경계).
 *
 * application 계층이 정의하고 persistence 계층이 구현한다.
 * 서비스는 이 인터페이스만 의존하므로 jOOQ 구현 세부사항이 도메인 로직에 노출되지 않는다.
 *
 * ## 소프트 삭제
 * [findById] / [listAll] 은 `deleted_at IS NULL` 인 행만 반환한다. [softDelete] 는
 * `deleted_at` 를 now() 로 설정할 뿐 물리 삭제하지 않는다 (DATA.md §1.2 신규 엔티티 테이블 기본).
 *
 * ## OCC (낙관적 동시성 제어)
 * [update] 의 OCC 충돌(stale version) 은 `null` 반환으로 표현한다. 예외를 던지지 않는다.
 * 이미 소프트 삭제된 구독에 대한 [update] 도 동일하게 `null` 을 반환한다.
 */
interface OutboundWebhookRepository {
    /**
     * 새 구독을 저장하고 id / 타임스탬프 / version 이 채워진 도메인 객체를 반환한다.
     *
     * INSERT 시 id = UUID 자동 생성, created_at / updated_at = now(), version = 0.
     *
     * @param webhook 저장할 구독 ([OutboundWebhook.id] 는 null 이어야 한다)
     * @return id / 타임스탬프 채워진 저장 결과
     */
    fun save(webhook: OutboundWebhook): OutboundWebhook

    /**
     * id 로 구독을 조회한다. 소프트 삭제된 행은 제외한다.
     *
     * @param id 조회할 구독 식별자
     * @return 구독 도메인 객체, 없거나 소프트 삭제됐으면 null
     */
    fun findById(id: UUID): OutboundWebhook?

    /**
     * 소프트 삭제되지 않은 구독 목록을 페이지네이션으로 반환한다.
     *
     * `ORDER BY created_at DESC, id ASC` 후 `LIMIT :size OFFSET :page * :size` 를 적용한다.
     *
     * @param page 0-based 페이지 번호.
     * @param size 페이지당 최대 항목 수.
     * @return 소프트 삭제 제외 구독 목록 (created_at DESC, id ASC 정렬).
     */
    fun listAll(
        page: Int,
        size: Int,
    ): List<OutboundWebhook>

    /**
     * 낙관적 동시성 제어(OCC)를 적용해 구독을 업데이트한다.
     *
     * `WHERE id = ? AND version = ? AND deleted_at IS NULL` 조건으로 UPDATE 를 실행한다.
     * - 0행 → OCC 충돌(다른 트랜잭션이 먼저 수정) 또는 소프트 삭제됨 → null 반환
     * - 1행 → version + 1, updated_at = now() 적용 후 갱신된 구독 반환
     *
     * @param webhook 업데이트할 구독 (id/version 이 WHERE 절 키로 사용됨)
     * @return 갱신된 구독, OCC 충돌 또는 소프트 삭제된 행이면 null
     */
    fun update(webhook: OutboundWebhook): OutboundWebhook?

    /**
     * id 로 구독을 소프트 삭제한다 (`deleted_at = now()`).
     *
     * 이미 소프트 삭제된 행에는 재적용하지 않는다 (`WHERE deleted_at IS NULL` 조건 — 멱등).
     *
     * @param id 삭제할 구독 식별자
     * @return 삭제된 행이 있으면 true, 없으면(부재 또는 이미 삭제됨) false
     */
    fun softDelete(id: UUID): Boolean

    /**
     * [eventType] 과 [projectKey] 에 매칭되는 발송 대상 구독 목록을 조회한다 (PR3 fanout 후보 조회).
     *
     * - `eventFilter` 에 [eventType] 이 포함된 구독만 대상으로 한다. PG 배열 overlap 연산자 `&&` 로
     *   V603 GIN 인덱스(`idx_outbound_webhooks_event_filter_gin`)를 활용한다.
     * - 구독의 `projectKey` 가 `null` 이면 **전체 프로젝트 대상**이라 [projectKey] 값과 무관하게 항상
     *   매칭되고, 값이 있으면 [projectKey] 와 정확히 일치할 때만 매칭된다.
     * - `enabled = true` 이고 소프트 삭제되지 않은 구독만 반환한다.
     *
     * @param eventType 매칭할 이벤트 wireValue (예: `"issue.created"`).
     * @param projectKey 매칭할 프로젝트 키.
     * @return 매칭 조건을 만족하는 구독 목록. 정렬 순서는 보장하지 않는다.
     */
    fun findMatching(
        eventType: String,
        projectKey: String,
    ): List<OutboundWebhook>
}
