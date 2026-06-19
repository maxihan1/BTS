// 사용자 알림 구독 매트릭스 조회·갱신 REST API 컨트롤러 (FR-NT-04)

package com.bts.notification.web

import com.bts.notification.application.SubscriptionPatchEntry
import com.bts.notification.application.UserSubscriptionService
import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.web.dto.PatchSubscriptionsRequest
import com.bts.notification.web.dto.SubscriptionEntryDto
import com.bts.notification.web.dto.SubscriptionMatrixResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 사용자 알림 구독 매트릭스 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - GET   /api/v1/users/me/notifications — 현재 사용자의 구독 매트릭스 전체 조회
 * - PATCH /api/v1/users/me/notifications — 구독 매트릭스 일괄 갱신
 *
 * ## 인증 정책
 * [currentActorId] (패키지 공유 헬퍼) 로 인증 주체 UUID 를 추출한다.
 * 미인증·익명·비-UUID 주체는 401(UNAUTHORIZED) 로 거부한다.
 * actor 추출은 리소스 조회보다 앞서 수행하여 미인증자가 존재를 probe 하지 못하게 한다
 * (memory: auth-extraction-before-resource-lookup 교훈).
 *
 * ## enum 파싱 정책
 * 요청 body 의 [eventType] / [channel] 은 문자열로 수신하여 enum 으로 파싱한다.
 * - [NotificationEventType.fromWire] 가 null 을 반환하면 [IllegalArgumentException] → 400 (EC2)
 * - [Channel.fromWire] 가 null 을 반환하면 [IllegalArgumentException] → 400
 * - channel 이 CONFIGURABLE 화이트리스트 밖이면 서비스가 [IllegalArgumentException] → 400 (EC1)
 * [NotificationExceptionHandler] 가 400 응답으로 변환한다.
 *
 * @param service 사용자 구독 매트릭스 서비스
 */
@RestController
@RequestMapping("/api/v1/users/me/notifications")
class UserNotificationSubscriptionController(
    private val service: UserSubscriptionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 현재 인증 사용자의 구독 매트릭스 전체(20셀)를 반환한다.
     *
     * DB 에 이력이 없는 셀은 opt-out 기본값(enabled=true)으로 채워 반환한다.
     *
     * @return 200 OK + [SubscriptionMatrixResponse] (20개 셀)
     */
    @GetMapping
    fun getMatrix(): ResponseEntity<DataResponse<SubscriptionMatrixResponse>> {
        val actorId = currentActorId()
        log.debug("UserNotificationSubscriptionController.getMatrix actorId={}", actorId)
        val cells = service.getMatrix(actorId)
        val response =
            SubscriptionMatrixResponse(
                subscriptions =
                    cells.map { cell ->
                        SubscriptionEntryDto(
                            eventType = cell.eventType.wireValue,
                            channel = cell.channel.name,
                            enabled = cell.enabled,
                        )
                    },
            )
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 현재 인증 사용자의 구독 매트릭스를 일괄 갱신하고 갱신된 전체 매트릭스를 반환한다.
     *
     * 요청 body 의 [eventType] / [channel] 문자열을 enum 으로 파싱한다.
     * 파싱 실패 또는 CONFIGURABLE 화이트리스트 외 채널은 400 으로 거부한다.
     *
     * @param request 갱신 요청 바디
     * @return 200 OK + [SubscriptionMatrixResponse] (갱신 후 20개 셀)
     */
    @PatchMapping
    @Suppress("ThrowsCount") // eventType / channel 파싱 2종 throw — 응집이 분리보다 적합
    fun patch(
        @RequestBody request: PatchSubscriptionsRequest,
    ): ResponseEntity<DataResponse<SubscriptionMatrixResponse>> {
        val actorId = currentActorId()
        log.debug(
            "UserNotificationSubscriptionController.patch actorId={}, entries={}",
            actorId,
            request.subscriptions.size,
        )

        val entries =
            request.subscriptions.map { dto ->
                val eventType =
                    NotificationEventType.fromWire(dto.eventType)
                        ?: throw IllegalArgumentException("알 수 없는 eventType: ${dto.eventType}")
                val channel =
                    Channel.fromWire(dto.channel)
                        ?: throw IllegalArgumentException("알 수 없는 channel: ${dto.channel}")
                SubscriptionPatchEntry(
                    eventType = eventType,
                    channel = channel,
                    enabled = dto.enabled,
                )
            }

        val cells = service.patch(actorId, entries)
        val response =
            SubscriptionMatrixResponse(
                subscriptions =
                    cells.map { cell ->
                        SubscriptionEntryDto(
                            eventType = cell.eventType.wireValue,
                            channel = cell.channel.name,
                            enabled = cell.enabled,
                        )
                    },
            )
        return ResponseEntity.ok(DataResponse(data = response))
    }
}
