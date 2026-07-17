// Git 웹훅 등록 API 요청/응답 DTO — 목록 응답에는 token·secret 필드 자체가 없다 (FR-AT-07 PR-C Task 11)

package com.bts.automation.adapter.web.dto

import com.bts.automation.application.RegisteredGitWebhook
import com.bts.automation.domain.GitProvider
import com.bts.automation.domain.GitWebhook
import java.time.Instant
import java.util.UUID

/**
 * Git 웹훅 등록 요청 DTO — `POST /api/v1/projects/{projectKey}/automation/git-webhooks` 요청 바디.
 *
 * ## secret 검증은 여기(DTO)가 아니라 서비스가 한다
 * automation 모듈에는 Bean Validation **provider** 가 없다(`jakarta.validation-api` 만 있고
 * `spring-boot-starter-validation`/hibernate-validator 는 클래스패스에 없다). 따라서 `@field:NotBlank`
 * 류 어노테이션은 **컴파일은 되지만 런타임에 아무 일도 하지 않는다** — 붙이면 "검증이 있다"고 착각하게
 * 만드는 가짜 가드가 된다(`AutomationRuleRequests` KDoc 이 같은 사실을 기록하고 있다). 이 API 의 secret
 * 검증은 보안상 load-bearing 이므로(아래) 실제로 동작하는
 * [com.bts.automation.application.GitWebhookRegistrationService.register] 의 명시적 검증에 맡긴다.
 *
 * 부수 효과로 검증 순서도 올바르게 정렬된다 — `@Valid` 는 핸들러 진입 **전**(인자 해석 단계)에 돌아
 * 권한 판정보다 앞서지만, 서비스 검증은 권한 판정 **뒤**라 `AutomationRuleController.import` 가 확립한
 * "권한 → 본문 검증" 순서(게이트2 코드리뷰 CONCERN-2)와 일치한다.
 *
 * @property provider Git 호스팅 제공자. 화이트리스트 밖 값은 Jackson enum 역직렬화가 400 으로 거부한다.
 * @property secret provider 서명(HMAC) 검증용 공유 secret 원문. 길이/공백 검증은 서비스가 수행하고,
 *   저장 시에는 AES-256-GCM 암호문으로만 남는다(원문 비저장).
 */
data class CreateGitWebhookRequest(
    val provider: GitProvider,
    val secret: String,
)

/**
 * Git 웹훅 등록 응답 DTO(201) — **원문 토큰을 노출하는 유일한 지점**.
 *
 * DATA.md §8 PAT 패턴 — 원문 토큰은 발급 응답에서 딱 한 번만 보여주고 서버는 `sha256(rawToken)` 만
 * 저장한다. 이후 어떤 조회 API 도 원문을 복원할 수 없다(해시는 되돌릴 수 없다). 사용자가 이 응답의
 * [webhookUrl] 을 놓치면 웹훅을 지우고 다시 등록하는 것 말고는 방법이 없다.
 *
 * [secret] 은 응답에 **포함하지 않는다** — 요청자가 방금 보낸 값이라 되돌려줄 이유가 없고, 응답 로깅/
 * 프록시 캐시로 새어나갈 표면만 늘린다.
 *
 * @property id 등록된 웹훅 식별자.
 * @property provider Git 호스팅 제공자.
 * @property webhookUrl provider 설정 화면에 붙여넣을 인바운드 URL(원문 토큰 포함, [from] KDoc 참고).
 * @property token 원문 URL 토큰(1회 노출).
 */
data class CreateGitWebhookResponse(
    val id: UUID,
    val provider: GitProvider,
    val webhookUrl: String,
    val token: String,
) {
    companion object {
        /**
         * 인바운드 웹훅 경로 prefix — provider 가 PR 머지를 통지할 엔드포인트
         * (`com.bts.automation.security.GitWebhookSignatureVerifier` KDoc 과 동일 경로).
         */
        private const val INBOUND_PATH_PREFIX = "/api/v1/webhooks/git"

        /**
         * [registered] 를 201 응답으로 매핑한다.
         *
         * [webhookUrl] 은 **origin 없는 절대 경로**로 조립한다 — 배포 origin(스킴/호스트)은 automation
         * 모듈이 알지 못하고, 이를 위해 base-url 프로퍼티를 새로 만드는 것은 이 태스크 범위 밖이다.
         * 호출하는 화면이 자신의 origin 을 앞에 붙여 완전한 URL 을 만든다.
         *
         * @param registered 서비스가 발급한 웹훅 + 원문 토큰.
         * @return 원문 토큰과 인바운드 경로를 담은 201 응답 DTO.
         */
        fun from(registered: RegisteredGitWebhook): CreateGitWebhookResponse =
            CreateGitWebhookResponse(
                id = registered.webhook.id,
                provider = registered.webhook.provider,
                webhookUrl = "$INBOUND_PATH_PREFIX/${registered.rawToken}",
                token = registered.rawToken,
            )
    }
}

/**
 * Git 웹훅 목록 응답 DTO(200) — **token·secret 관련 필드를 하나도 두지 않는다**.
 *
 * 원문 토큰/토큰 해시/secret 암호문 어느 것도 필드로 만들지 않는 것이 방어의 핵심이다(`AutomationRuleResponse`
 * 선례 동형 — "타입 상 새어 나갈 수 없게 애초에 필드를 만들지 않는다"). 필드가 없으면 나중에 누가
 * 실수로 [GitWebhook] 전체를 직렬화 경로에 태우는 일이 타입 단계에서 막힌다.
 *
 * @property id 웹훅 식별자(삭제 시 경로 변수로 쓴다).
 * @property provider Git 호스팅 제공자.
 * @property createdAt 생성 시각.
 * @property createdBy 생성자 BTS user id.
 */
data class GitWebhookSummaryResponse(
    val id: UUID,
    val provider: GitProvider,
    val createdAt: Instant,
    val createdBy: UUID,
) {
    companion object {
        /**
         * [webhook] 도메인 객체에서 **노출 가능한 필드만** 골라 응답 DTO 로 매핑한다.
         *
         * @param webhook 매핑할 활성 웹훅.
         * @return token/secret 이 제거된 목록 응답 DTO.
         */
        fun from(webhook: GitWebhook): GitWebhookSummaryResponse =
            GitWebhookSummaryResponse(
                id = webhook.id,
                provider = webhook.provider,
                createdAt = webhook.createdAt,
                createdBy = webhook.createdBy,
            )
    }
}
