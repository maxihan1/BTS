// Git 웹훅 등록/조회/소프트삭제 유스케이스 — MANAGE_AUTOMATION 가드 + secret 검증 + 토큰 해시 저장 (FR-AT-07 PR-C Task 11)

package com.bts.automation.application

import com.bts.automation.adapter.GitWebhookRepository
import com.bts.automation.domain.GitProvider
import com.bts.automation.domain.GitWebhook
import com.bts.shared.crypto.SecretEncryptor
import com.bts.shared.permission.AutomationPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

/**
 * Git 인바운드 웹훅 등록/조회/소프트삭제 유스케이스 (FR-AT-07 PR-C Task 11).
 *
 * spec §5-2 endpoint 3종의 application 계층. [AutomationRuleService] 와 동일한 관례를 따른다 —
 * 트랜잭션 경계·권한 판정은 이 서비스가 지고, 컨트롤러는 actor 추출과 HTTP 매핑만 한다.
 *
 * ## 인가 순서 — 권한 판정이 리소스 조회·본문 검증보다 먼저
 * 모든 public 메서드는 **가장 먼저** [assertManageAutomation] 을 호출한다
 * ([[auth-extraction-before-resource-lookup]]). [delete] 가 조회를 먼저 하면 권한 없는 사용자가
 * 403/404 차이로 웹훅 존재 여부를 알아낼 수 있고, [register] 가 secret 검증을 먼저 하면 400/403
 * 차이가 같은 역할을 한다. `AutomationRuleController.import` 가 확립한 "권한 → 본문 파싱/검증"
 * 순서(게이트2 코드리뷰 CONCERN-2)를 [register] 도 그대로 따른다.
 *
 * ## 비밀값 2종의 역할이 다르다 — 둘 다 필요하다
 * - **URL 토큰**([mintToken]) — "어느 웹훅 등록인가"를 식별한다. provider 설정 화면·CI 로그·프록시
 *   접근 로그에 URL 그대로 남는 값이라 **비밀로 유지된다고 가정할 수 없다**.
 * - **secret** — "이 요청이 정말 그 provider 가 보낸 것인가"를 서명(HMAC)으로 증명한다. 토큰이 새도
 *   secret 이 건재하면 위조 요청은 서명 검증에서 막힌다.
 *
 * 그래서 [validateSecret] 이 load-bearing 이다. GITLAB 은 `X-Gitlab-Token` **평문 비교**라 빈
 * secret 을 허용하면 공격자가 빈 값을 보내 그대로 일치시킨다 — 즉 secret 계층이 통째로 사라지고
 * **새기 쉬운 URL 토큰만으로 인바운드가 뚫린다**. `secret_encrypted TEXT NOT NULL`(V307)은 빈 문자열의
 * *암호문*을 막지 못하므로 DB 제약은 이 방어를 대신하지 못한다.
 *
 * ## 저장 형태 (DATA.md §8 / DEVELOPMENT.md §1.1-1)
 * - 토큰 — `sha256(rawToken)` **해시만** 저장. 원문은 발급 응답에서 1회 노출 후 서버에 남지 않는다.
 * - secret — [secretEncryptor] 로 AES-256-GCM 암호화한 값만 저장(원문 비저장). 서명 검증 시
 *   복호화해서 쓴다(해시가 아니라 암호화인 이유 — HMAC 계산에 원문이 필요하다).
 *
 * @param repository `git_webhooks` 영속 어댑터.
 * @param permissionResolver MANAGE_AUTOMATION 판정 cross-BC 포트. **non-null 필수** — nullable 로 두고
 *   `?: return` 하면 prod 에서 빈 부재 시 전부 허용으로 떨어진다([[crossbc-resolver-nullable-fail-open]]).
 * @param secretEncryptor secret 암호화기. 같은 타입 빈이 prod 조립 시 4개라 by-name 주입이 필수다
 *   (`automationSecretEncryptor`, [com.bts.automation.AutomationEncryptionConfig] KDoc 참고).
 * @param clock 시각 주입. 테스트가 [java.time.Clock.fixed] 로 고정할 수 있게 하드코딩하지 않는다.
 */
@Service
class GitWebhookRegistrationService(
    private val repository: GitWebhookRepository,
    private val permissionResolver: AutomationPermissionResolver,
    @param:Qualifier("automationSecretEncryptor") private val secretEncryptor: SecretEncryptor,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val secureRandom = SecureRandom()

    /**
     * [projectKey] 에 Git 웹훅을 등록하고 원문 URL 토큰을 1회 발급한다.
     *
     * 순서는 **권한(403) → secret 검증(400) → 발급/저장** 이다(클래스 KDoc §인가 순서).
     *
     * @param actorId 등록을 요청하는 행위자.
     * @param projectKey 웹훅이 속할 프로젝트 키.
     * @param provider Git 호스팅 제공자.
     * @param secret provider 서명 검증용 공유 secret 원문.
     * @return 저장된 웹훅 + 원문 토큰([RegisteredGitWebhook] — 원문은 이 반환값 밖으로 나가면 안 된다).
     * @throws AutomationForbiddenException [actorId] 가 [projectKey] 에서 MANAGE_AUTOMATION 이 없을 때.
     * @throws GitWebhookSecretInvalidException [secret] 이 공백이거나 길이 범위를 벗어날 때.
     */
    @Transactional
    fun register(
        actorId: UUID,
        projectKey: String,
        provider: GitProvider,
        secret: String,
    ): RegisteredGitWebhook {
        assertManageAutomation(actorId, projectKey)
        validateSecret(secret)
        val rawToken = mintToken()
        val webhook =
            GitWebhook(
                id = UUID.randomUUID(),
                projectKey = projectKey,
                provider = provider,
                tokenHash = sha256Hex(rawToken),
                secretEncrypted = secretEncryptor.encrypt(secret),
                createdAt = clock.instant(),
                createdBy = actorId,
                deletedAt = null,
            )
        repository.insert(webhook)
        // 토큰/secret 은 로그에 남기지 않는다(DEVELOPMENT.md §1.1-2) — 식별자만 남긴다.
        log.info(
            "GitWebhookRegistrationService.register actor={} projectKey={} id={} provider={}",
            actorId,
            projectKey,
            webhook.id,
            provider,
        )
        return RegisteredGitWebhook(webhook, rawToken)
    }

    /**
     * [projectKey] 의 활성 웹훅 목록을 반환한다.
     *
     * @param actorId 조회를 요청하는 행위자.
     * @param projectKey 조회할 프로젝트 키.
     * @return 생성순 활성 웹훅 목록(호출자가 노출 가능 필드만 골라 매핑해야 한다).
     * @throws AutomationForbiddenException MANAGE_AUTOMATION 이 없을 때.
     */
    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        projectKey: String,
    ): List<GitWebhook> {
        assertManageAutomation(actorId, projectKey)
        return repository.findByProjectKey(projectKey)
    }

    /**
     * [id] 웹훅을 소프트 삭제한다(DATA.md §1.2 — 행은 남고 `deleted_at` 이 채워진다).
     *
     * 권한 판정 **후** [projectKey] 소속인지 확인한다. 타 프로젝트 소속이거나 이미 삭제/부재면
     * 똑같이 404 로 수렴해 존재를 숨긴다(`AutomationRuleService` 프로젝트 경계 관례 동형).
     * 조회와 삭제가 한 트랜잭션이라 그 사이 상태가 바뀌지 않는다.
     *
     * @param actorId 삭제를 요청하는 행위자.
     * @param projectKey 웹훅이 속해야 하는 프로젝트 키.
     * @param id 삭제할 웹훅 id.
     * @throws AutomationForbiddenException MANAGE_AUTOMATION 이 없을 때.
     * @throws GitWebhookNotFoundException [id] 가 [projectKey] 의 활성 웹훅이 아닐 때.
     */
    @Transactional
    fun delete(
        actorId: UUID,
        projectKey: String,
        id: UUID,
    ) {
        assertManageAutomation(actorId, projectKey)
        val target =
            repository.findByProjectKey(projectKey).firstOrNull { it.id == id }
                ?: throw GitWebhookNotFoundException(id)
        repository.softDelete(target.id, clock.instant())
        log.info(
            "GitWebhookRegistrationService.delete actor={} projectKey={} id={}",
            actorId,
            projectKey,
            id,
        )
    }

    /**
     * [actorId] 가 [projectKey] 에서 MANAGE_AUTOMATION 을 보유하지 않으면 거부한다.
     *
     * 기존 `MANAGE_AUTOMATION` 권한 코드를 그대로 쓴다(V035 시드 · 신규 권한 코드 없음). 판정 실패는
     * 예외 하나로 수렴한다 — 기본값·미해석 키·비멤버는 resolver 가 모두 `false` 로 돌려주는 fail-closed
     * 계약이다.
     */
    private fun assertManageAutomation(
        actorId: UUID,
        projectKey: String,
    ) {
        if (!permissionResolver.hasManageAutomation(actorId, projectKey)) {
            throw AutomationForbiddenException()
        }
    }

    /**
     * secret 이 서명 검증의 방어선으로 기능할 수 있는 값인지 확인한다(클래스 KDoc §비밀값 2종 참고).
     *
     * **trim 하지 않는다** — provider 의 HMAC 은 secret 바이트열 그대로를 쓰므로 서버가 값을 손대면
     * 서명이 영원히 불일치한다. 앞뒤 공백까지 포함해 사용자가 provider 에 붙여넣은 값 그대로 보관한다.
     * 예외 메시지에 [secret] 값을 절대 넣지 않는다(로그 누출 — DEVELOPMENT.md §1.1-2).
     */
    private fun validateSecret(secret: String) {
        if (secret.isBlank()) {
            throw GitWebhookSecretInvalidException("secret 이 비어 있거나 공백뿐입니다.")
        }
        if (secret.length < MIN_SECRET_LENGTH || secret.length > MAX_SECRET_LENGTH) {
            throw GitWebhookSecretInvalidException(
                "secret 길이가 허용 범위($MIN_SECRET_LENGTH~$MAX_SECRET_LENGTH)를 벗어났습니다.",
            )
        }
    }

    /** [SecureRandom] 256bit 원문 토큰을 base64url 로 발급한다(`AutomationRuleService.mintWebhookToken` 동형). */
    private fun mintToken(): String {
        val rawBytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes)
    }

    /** 원문을 SHA-256 hex(소문자 64자)로 해시한다. [MessageDigest] 는 non-thread-safe 라 호출마다 새로 만든다. */
    private fun sha256Hex(plaintext: String): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(plaintext.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SHA_256 = "SHA-256"

        /** 원문 토큰 바이트 수(256bit) — DATA.md §8 PAT 패턴. */
        const val TOKEN_BYTES = 32

        /**
         * secret 최소 길이. 짧은 secret 은 HMAC 을 쓰는 GITHUB 에서도 오프라인 무차별 대입 여지를 남기고,
         * 평문 비교인 GITLAB 에서는 추측 가능성이 그대로 위험이 된다.
         */
        const val MIN_SECRET_LENGTH = 16

        /** secret 최대 길이 — 무의미하게 큰 입력이 암호화/저장 경로로 들어오는 것을 막는 상한. */
        const val MAX_SECRET_LENGTH = 4096
    }
}

/**
 * [GitWebhookRegistrationService.register] 결과 — 저장된 웹훅 + **원문 토큰**.
 *
 * 원문 토큰은 이 객체와 201 응답 밖으로 나가면 안 된다. data class 기본 [toString] 이 토큰을 그대로
 * 찍어 로그/스택트레이스로 새는 것을 막기 위해 [toString] 을 재정의한다(DEVELOPMENT.md §1.1-2).
 *
 * @property webhook 저장된 웹훅(토큰은 해시 형태로만 들어 있다).
 * @property rawToken 발급된 원문 URL 토큰(1회 노출용).
 */
data class RegisteredGitWebhook(
    val webhook: GitWebhook,
    val rawToken: String,
) {
    override fun toString(): String = "RegisteredGitWebhook(webhookId=${webhook.id}, rawToken=***)"
}

/**
 * 요청한 Git 웹훅이 없거나 경로의 프로젝트 소속이 아니어서 존재를 숨겨야 함을 나타낸다. 웹 레이어에서
 * 404 로 매핑된다.
 *
 * @property id 조회를 시도한 웹훅 id(호출자가 이미 URL 로 알고 있는 값이라 로그에 남겨도 누출이 아니다).
 */
class GitWebhookNotFoundException(
    val id: UUID,
) : RuntimeException("Git 웹훅을 찾을 수 없습니다.")

/**
 * 등록 요청의 secret 이 서명 검증 방어선으로 쓸 수 없는 값임을 나타낸다. 웹 레이어에서 400 으로 매핑된다.
 *
 * [message] 는 **서버 로그 전용**이다 — HTTP 응답 detail 은 웹 레이어가 고정 문자열로 치환한다
 * ([[fr-pm-04-guard-exception-message-http-leak]] 관례).
 */
class GitWebhookSecretInvalidException(
    override val message: String,
) : RuntimeException(message)
