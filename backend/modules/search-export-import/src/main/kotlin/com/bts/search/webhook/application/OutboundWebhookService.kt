// 아웃바운드 webhook 구독 CRUD 서비스 — SYSTEM_ADMIN 게이트·SSRF 검증·secret 암호화 오케스트레이션 (FR-API-03 PR2)

package com.bts.search.webhook.application

import com.bts.search.webhook.domain.OutboundWebhook
import com.bts.shared.crypto.SecretEncryptor
import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import com.bts.shared.permission.SystemPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 아웃바운드 webhook 구독 CRUD 서비스.
 *
 * ## 책임
 * - **SYSTEM_ADMIN 전역 게이트**. 모든 public 메서드는 리소스 조회 이전에 [requireSystemAdmin] 로
 *   admin 여부를 먼저 판정한다. 존재하지 않는 id 로도 비-admin 이면 404 가 아닌 403 을 반환해
 *   리소스 존재 여부를 노출하지 않는다(존재 probe 차단, auth-extraction-before-resource-lookup).
 * - **SSRF 검증**. create/update 시 shared-kernel [OutboundUrlValidator] 로 URL 을 검사해
 *   Blocked/Malformed 이면 [WebhookValidationException](400)으로 매핑한다. 차단 사유(내부 host)는
 *   서버 보안 로그에만 남기고 예외 메시지에는 포함하지 않는다(FR-NT-05 와 동일 방어 재사용).
 * - **secret 암호화**. 평문 secret 은 shared-kernel [SecretEncryptor](AES-256-GCM)로 암호화한 뒤에만
 *   도메인/영속 경계로 넘긴다. 평문은 로깅/응답/예외 메시지 어디에도 남기지 않는다(DEVELOPMENT §1.1.2).
 *   update 의 secret 3-state — 생략/blank=기존 암호문 유지(재암호화 안 함), 비어있지 않은 값=재암호화 교체.
 * - **불변식 검증 위임**. name·url·eventFilter allowlist 등 도메인 불변식은 [OutboundWebhook.create]/
 *   [OutboundWebhook.applyUpdate] 팩토리가 담당하며, 위반 시 [IllegalArgumentException] 을
 *   [mapDomainViolation] 이 [WebhookValidationException](400)으로 변환한다.
 * - **OCC**. update 는 [OutboundWebhookRepository.update] 가 null 반환 시 [WebhookConflictException](409).
 *
 * ## fail-closed 의존성
 * 네 의존성 모두 nullable 이 아닌 생성자 주입이다. 빈이 등록되지 않으면 부팅이 실패하도록 설계된
 * 안전망이며, 권한/암호화/검증 경로에 allow-all default 를 두지 않는다(fail-open 금지).
 *
 * ## 트랜잭션
 * 클래스 레벨 `@Transactional` 이 모든 public 메서드에 적용된다. 읽기 전용([list]/[get])에는
 * `readOnly = true` 를 별도 선언한다. self-invocation 이 없어 Spring 프록시 우회 문제는 없다.
 *
 * @param systemPermissionResolver 전역(SYSTEM_ADMIN) 권한 판정 포트(cross-BC, fail-closed).
 * @param urlValidator 아웃바운드 URL SSRF 검증기(shared-kernel).
 * @param secretEncryptor 외부 비밀값 대칭 암호화 유틸(shared-kernel, webhook 전용 키로 구성됨).
 * @param repository 아웃바운드 webhook 구독 영속성 포트(DIP 경계).
 */
@Service
@Transactional
class OutboundWebhookService(
    private val systemPermissionResolver: SystemPermissionResolver,
    private val urlValidator: OutboundUrlValidator,
    @Qualifier("webhookSecretEncryptor")
    private val secretEncryptor: SecretEncryptor,
    private val repository: OutboundWebhookRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 webhook 구독을 생성한다.
     *
     * admin 게이트 → SSRF 검증 → secret 암호화 → 도메인 불변식 검증 → 저장 순서로 처리한다.
     *
     * @param actorId 요청 actor UUID(SYSTEM_ADMIN 이어야 함).
     * @param name 구독 이름.
     * @param url 통지 대상 URL.
     * @param eventFilter 관심 이벤트 wireValue 목록(비어있지 않고 발행가능 allowlist 소속).
     * @param secret 서명용 평문 secret. null/blank 이면 미설정. 값이 있으면 암호화 후 저장한다.
     * @param projectKey 필터링할 프로젝트 키. null 이면 전체 프로젝트 대상.
     * @param enabled 구독 활성화 여부. 기본값 `true`.
     * @return id/타임스탬프/version 이 채워진 저장 결과(평문 secret 미포함).
     * @throws WebhookForbiddenException actor 가 SYSTEM_ADMIN 이 아닌 경우(403).
     * @throws WebhookValidationException URL SSRF 차단/형식 오류 또는 도메인 불변식 위반(400).
     */
    @Suppress("LongParameterList")
    fun create(
        actorId: UUID,
        name: String,
        url: String,
        eventFilter: List<String>,
        secret: String? = null,
        projectKey: String? = null,
        enabled: Boolean = true,
    ): OutboundWebhook {
        requireSystemAdmin(actorId)
        validateUrlNotSsrf(url)
        val secretEncrypted = encryptIfPresent(secret)
        val webhook =
            mapDomainViolation {
                OutboundWebhook.create(
                    createdBy = actorId,
                    name = name,
                    url = url,
                    eventFilter = eventFilter,
                    secretEncrypted = secretEncrypted,
                    projectKey = projectKey,
                    enabled = enabled,
                )
            }
        log.info("OutboundWebhookService.create actor={} name={} hasSecret={}", actorId, name, secretEncrypted != null)
        return repository.save(webhook)
    }

    /**
     * webhook 구독 목록을 offset 페이지네이션으로 조회한다.
     *
     * @param actorId 요청 actor UUID(SYSTEM_ADMIN 이어야 함).
     * @param page 0-based 페이지 번호.
     * @param size 페이지당 최대 항목 수.
     * @return 소프트 삭제 제외 구독 목록(created_at DESC, id ASC 정렬).
     * @throws WebhookForbiddenException actor 가 SYSTEM_ADMIN 이 아닌 경우(403).
     */
    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        page: Int,
        size: Int,
    ): List<OutboundWebhook> {
        requireSystemAdmin(actorId)
        return repository.listAll(page, size)
    }

    /**
     * webhook 구독 단건을 조회한다.
     *
     * @param actorId 요청 actor UUID(SYSTEM_ADMIN 이어야 함).
     * @param id 조회 대상 구독 식별자.
     * @return 구독 도메인 객체(평문 secret 미포함).
     * @throws WebhookForbiddenException actor 가 SYSTEM_ADMIN 이 아닌 경우(403, 리소스 조회 이전).
     * @throws WebhookNotFoundException 존재하지 않거나 소프트 삭제된 경우(404).
     */
    @Transactional(readOnly = true)
    fun get(
        actorId: UUID,
        id: UUID,
    ): OutboundWebhook {
        requireSystemAdmin(actorId)
        return repository.findById(id) ?: throw WebhookNotFoundException(id)
    }

    /**
     * webhook 구독을 낙관적 동시성 제어(OCC)로 수정한다.
     *
     * admin 게이트 → 대상 조회(404) → SSRF 검증 → secret 3-state 결정 → 도메인 불변식 검증 →
     * OCC UPDATE 순서로 처리한다. [version] 은 클라이언트가 보유한 현재 버전으로 OCC 키에 사용된다.
     * name/url/eventFilter/projectKey/enabled 는 전체 교체(PUT), secret 만 3-state 이다.
     *
     * @param actorId 요청 actor UUID(SYSTEM_ADMIN 이어야 함).
     * @param id 수정 대상 구독 식별자.
     * @param name 새 구독 이름.
     * @param url 새 통지 대상 URL.
     * @param eventFilter 새 관심 이벤트 wireValue 목록.
     * @param version 클라이언트가 보유한 현재 version(OCC 키).
     * @param secret 새 평문 secret. null/blank 이면 기존 암호문 유지, 비어있지 않으면 재암호화 교체.
     * @param projectKey 새 프로젝트 키. null 이면 전체 프로젝트 대상.
     * @param enabled 새 활성화 여부.
     * @return 갱신된 구독 도메인 객체(version+1, 평문 secret 미포함).
     * @throws WebhookForbiddenException actor 가 SYSTEM_ADMIN 이 아닌 경우(403, 리소스 조회 이전).
     * @throws WebhookNotFoundException 존재하지 않거나 소프트 삭제된 경우(404).
     * @throws WebhookValidationException URL SSRF 차단/형식 오류 또는 도메인 불변식 위반(400).
     * @throws WebhookConflictException OCC 충돌(stale version) 또는 조회~UPDATE 사이 소프트 삭제(409).
     */
    @Suppress("LongParameterList")
    fun update(
        actorId: UUID,
        id: UUID,
        name: String,
        url: String,
        eventFilter: List<String>,
        version: Long,
        secret: String? = null,
        projectKey: String? = null,
        enabled: Boolean = true,
    ): OutboundWebhook {
        requireSystemAdmin(actorId)
        val existing = repository.findById(id) ?: throw WebhookNotFoundException(id)
        validateUrlNotSsrf(url)
        val secretEncrypted =
            if (secret.isNullOrBlank()) existing.secretEncrypted else secretEncryptor.encrypt(secret)
        val toUpdate =
            mapDomainViolation {
                existing.applyUpdate(
                    name = name,
                    url = url,
                    eventFilter = eventFilter,
                    secretEncrypted = secretEncrypted,
                    projectKey = projectKey,
                    enabled = enabled,
                ).copy(version = version)
            }
        log.info("OutboundWebhookService.update id={} actor={} hasSecret={}", id, actorId, secretEncrypted != null)
        return repository.update(toUpdate) ?: throw WebhookConflictException(id)
    }

    /**
     * webhook 구독을 소프트 삭제한다(`deleted_at = now()`).
     *
     * @param actorId 요청 actor UUID(SYSTEM_ADMIN 이어야 함).
     * @param id 삭제 대상 구독 식별자.
     * @throws WebhookForbiddenException actor 가 SYSTEM_ADMIN 이 아닌 경우(403, 리소스 조회 이전).
     * @throws WebhookNotFoundException 존재하지 않거나 이미 소프트 삭제된 경우(404).
     */
    fun delete(
        actorId: UUID,
        id: UUID,
    ) {
        requireSystemAdmin(actorId)
        log.info("OutboundWebhookService.delete id={} actor={}", id, actorId)
        if (!repository.softDelete(id)) throw WebhookNotFoundException(id)
    }

    // ── private helpers ─────────────────────────────────────────────────────────

    /**
     * actor 가 SYSTEM_ADMIN 인지 판정하고, 아니면 [WebhookForbiddenException] 을 던진다.
     *
     * 모든 public 메서드가 리소스 조회 이전에 호출해 존재 probe 를 차단한다(fail-closed).
     *
     * @param actorId 권한 판정 대상 actor UUID.
     * @throws WebhookForbiddenException actor 가 SYSTEM_ADMIN 이 아닌 경우.
     */
    private fun requireSystemAdmin(actorId: UUID) {
        if (!systemPermissionResolver.isSystemAdmin(actorId)) throw WebhookForbiddenException()
    }

    /**
     * URL 을 SSRF 관점에서 검증하고, 차단/형식 오류이면 [WebhookValidationException] 을 던진다.
     *
     * 차단 사유(내부 host/IP)는 서버 보안 로그에만 남기고 예외 메시지에는 포함하지 않는다.
     * malformed 사유는 원본 URL 조각을 포함할 수 있어 로그에도 남기지 않는다(전체 URL 비기록).
     *
     * @param url 검증할 아웃바운드 URL.
     * @throws WebhookValidationException [UrlCheck.Blocked] 또는 [UrlCheck.Malformed] 인 경우.
     */
    private fun validateUrlNotSsrf(url: String) {
        when (val check = urlValidator.check(url)) {
            is UrlCheck.Blocked -> {
                log.warn("webhook_url_rejected reason=blocked detail={}", check.reason)
                throw WebhookValidationException(SSRF_REJECT_MESSAGE)
            }
            is UrlCheck.Malformed -> {
                log.warn("webhook_url_rejected reason=malformed")
                throw WebhookValidationException(SSRF_REJECT_MESSAGE)
            }
            UrlCheck.Allowed -> Unit
        }
    }

    /**
     * 평문 secret 이 존재(non-blank)하면 암호화하여 반환하고, 없으면 null 을 반환한다.
     *
     * 평문은 로깅하지 않으며, 반환값은 암호문(또는 null)만이다.
     *
     * @param secret 평문 secret. null/blank 이면 미설정으로 본다.
     * @return 암호문 hex 문자열 또는 null(미설정).
     */
    private fun encryptIfPresent(secret: String?): String? {
        if (secret.isNullOrBlank()) return null
        return secretEncryptor.encrypt(secret)
    }

    /**
     * 도메인 팩토리([OutboundWebhook.create]/[OutboundWebhook.applyUpdate]) 호출을 감싸
     * 불변식 위반([IllegalArgumentException])을 [WebhookValidationException](400)으로 변환한다.
     *
     * 원인 예외를 cause 로 보존해 디버그 추적을 가능하게 한다.
     *
     * @param block 도메인 불변식을 검증하는 팩토리 호출.
     * @return 검증을 통과한 도메인 객체.
     * @throws WebhookValidationException 불변식 위반 시.
     */
    private inline fun <T> mapDomainViolation(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            throw WebhookValidationException(e.message ?: DEFAULT_INVALID_MESSAGE, e)
        }

    companion object {
        /** 도메인 불변식 위반 메시지가 없을 때 쓰는 기본 400 메시지(비밀값·내부 정보 미포함). */
        private const val DEFAULT_INVALID_MESSAGE = "아웃바운드 webhook 구독 입력이 유효하지 않습니다"

        /** SSRF 차단/형식 오류 URL 에 대한 일반 400 메시지(차단 사유·내부 host 미포함). */
        private const val SSRF_REJECT_MESSAGE = "webhook URL 이 허용되지 않습니다 (형식 오류 또는 접근 불가 대상)"
    }
}
