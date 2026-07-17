// Git 인바운드 웹훅(GitHub/GitLab) 요청의 진위를 등록행 provider 기준으로 검증하는 컴포넌트 (FR-AT-07 PR-C Task 7)

package com.bts.automation.security

import com.bts.shared.crypto.SecretEncryptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * GitHub/GitLab 이 우리 서버로 보내는 인바운드 웹훅 요청(`POST /api/v1/webhooks/git/{token}`)이 **정말
 * 그 provider 에서 왔는지**를 검증한다 (FR-AT-07 PR-C). 이 경로는 Spring Security 필터에서 permitAll 이라
 * **인증 주체가 필터가 아니라 이 검증기**다 — 여기서 통과시키면 그대로 자동화가 발화한다.
 *
 * ## [com.bts.slack.security.SlackSignatureVerifier] 에서 승계한 4계약
 * 1. **미설정/blank = 거부(fail-closed)** — secret 이 비어 있으면 검증을 스킵하지 않고 항상 거부한다.
 * 2. **예외가 아니라 boolean 으로 수렴** — 헤더 누락·형식 오류·불일치·복호화 실패는 **모두 `false`**.
 * 3. **[MessageDigest.isEqual] 상수 시간 비교** — `==`/`equals` 는 앞자리부터 다른 순간 즉시 false 가 되어
 *    응답 시간 차이로 서명을 한 자리씩 알아내는 **타이밍 공격**에 노출된다.
 * 4. **원문 [ByteArray] 에 직접 HMAC — String 왕복 금지** — 본문을 String 으로 디코드했다 되돌리면 유효
 *    UTF-8 이 아닌 바이트가 U+FFFD 로 치환돼 HMAC 대상이 바뀌고(진짜 요청이 원인 불명 거부), 복사본이
 *    하나 더 생겨 미인증 요청이 힙을 증폭시킨다.
 *
 * ## ★ replay(재전송) 방어는 **구조적으로 불가능하다** — 우리 구현의 누락이 아니다
 * slack 은 서명 대상에 timestamp 가 들어 있어 ±300초 윈도우로 재전송을 막지만, GitHub 서명은
 * `sha256=hex(HMAC(secret, rawBody))` 로 **본문만 서명한다 — base string 에 timestamp 가 없다**.
 * `X-GitHub-Delivery` 는 **서명 대상 밖**이라 위조 가능해 대체물이 못 된다. 따라서 slack 의 replay 윈도우를
 * 이식할 수 없고 [java.time.Clock] 주입도 필요 없다. **캡처된 유효 요청은 재전송하면 다시 통과한다.**
 * 상위 계층의 dedup 은 "정직한 재시도" 완화책일 뿐 replay 방어가 아니다
 * (ADR `2026-07-17-git-webhook-inbound-permitall.md` 잔여위험 R2 — 수용).
 *
 * ## ★ GITLAB 은 GITHUB 과 **보안 등급이 다르다** (동급으로 읽지 말 것)
 * GitLab 은 HMAC 서명을 제공하지 않고 `X-Gitlab-Token` **평문 토큰**만 보낸다. 따라서 GITLAB 경로는
 * - **본문 무결성을 전혀 보장하지 않는다** (토큰만 맞으면 본문은 무엇이든 통과),
 * - 전송 경로에 토큰이 그대로 노출된다.
 *
 * 이는 **GitLab 프로토콜의 한계이지 우리가 더 강하게 만들 수 있는 지점이 아니다**. 완화책으로 등록 API 가
 * secret 최소 길이를 강제하고, 여기서는 상수 시간 비교와 blank 거부를 적용한다
 * (ADR 잔여위험 R3 / DEC-13 — 수용).
 *
 * ## ★ provider 는 **등록행으로만** 판정한다 — 헤더 추론·폴백 금지
 * 등록행이 GITHUB 이면 `X-Hub-Signature-256` 만, GITLAB 이면 `X-Gitlab-Token` 만 본다. 기대 헤더가 없으면
 * 즉시 거부하고 **다른 provider 방식으로 폴백하지 않는다**. 폴백을 허용하면 공격자가 약한 쪽(평문 비교)을
 * 골라 HMAC 검증을 우회한다(**혼동 공격**). GitHub 레거시 `X-Hub-Signature`(SHA-1)도 지원하지 않는다.
 *
 * ## ★ 복호화까지 이 클래스가 책임진다 — 예외→상태코드 변질 경로를 아예 만들지 않는다
 * [SecretEncryptor.decrypt] 는 키 미설정 시 `IllegalStateException` 을, 암호문 손상·키 변경 시 Hex 디코더/
 * crypto 예외를 **던진다**. 그 예외가 컨트롤러 밖으로 새면 401 이어야 할 응답이 500 이 된다(저장소 3대 사고 —
 * catch-all 핸들러 / 도메인예외 핸들러 스코프 / 동명 예외 오import). 따라서 복호화를 이 클래스가 감싸
 * `false` 로 수렴시키고, 컨트롤러(Task 10)는 **`false` → 401 매핑만** 하면 된다.
 *
 * ## 관측성 — 구조화 로그가 유일한 수단
 * 운영자가 사유를 구분할 수 있도록 **이벤트명을 가른다**.
 * - 복호화 실패 → `git_webhook_decrypt_failed` (키 배포/암호문 손상 = 운영자가 고쳐야 할 우리 쪽 문제)
 * - 그 외 거부 → `git_webhook_signature_rejected` (공격 또는 설정 불일치)
 *
 * 로그에는 `webhookId`·`projectKey`·`provider`·거부 사유만 남기고 **secret 평문·암호문·서명 헤더·본문은
 * 절대 남기지 않는다**(DEVELOPMENT.md §1.1.2). HTTP 응답은 사유와 무관하게 **단일 errorCode** 로 수렴시켜
 * 응답이 존재 오라클이 되지 않게 한다 — 사유 구분은 **로그에서만**. 저장소에 메트릭 인프라가 없어
 * (micrometer 미도입) 관측성은 로그 단일 수단이며, 메트릭 도입은 신규 의존성으로 별건이다.
 *
 * @param secretEncryptor automation BC 전용 암호화 빈. 키 미설정이어도 빈은 등록되고(부팅 안전) 실제 거부는
 *   이 검증 호출 시점에 일어난다.
 */
@Component
class GitWebhookSignatureVerifier(
    @param:Qualifier("automationSecretEncryptor") private val secretEncryptor: SecretEncryptor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 인바운드 Git 웹훅 요청이 등록행의 provider·secret 과 일치하는지 검증한다.
     *
     * 모든 거부는 예외 없이 `false` 로 수렴한다(클래스 KDoc "복호화까지 이 클래스가 책임진다" 참조).
     *
     * @param webhookId 등록행 id. 로그 식별자로만 쓴다.
     * @param projectKey 등록행 프로젝트 키. 로그 식별자로만 쓴다.
     * @param provider 등록행 provider — [PROVIDER_GITHUB]/[PROVIDER_GITLAB] 두 값만 유효하다. DB CHECK
     *   화이트리스트(`ck_git_webhooks_provider`)와 같은 문자열이며, 도메인 enum 을 쓰는 호출자는 `name` 을
     *   넘긴다. **요청 헤더가 아니라 반드시 등록행 값**이어야 한다(헤더 추론 = 혼동 공격). 그 밖의 값은 거부.
     * @param secretEncrypted 등록행의 secret 암호문. 이 함수가 복호화하며 실패는 `false` 로 수렴한다.
     * @param githubSignatureHeader `X-Hub-Signature-256` 값(`sha256=`+hex). GITHUB 등록에서만 사용한다.
     * @param gitlabTokenHeader `X-Gitlab-Token` 값(평문). GITLAB 등록에서만 사용한다.
     * @param rawBody 서명 대상 원문 바이트 — 수신한 그대로여야 한다(디코드/재인코딩 왕복 금지).
     * @return 해당 provider 의 진짜 요청으로 검증되면 `true`, 그 외 모든 경우 `false`(fail-closed).
     */
    @Suppress("LongParameterList") // 등록행 4값 + 헤더 2종 + 본문. 묶으면 도메인 타입 중복 정의가 되어 오히려 결합이 는다
    fun isValid(
        webhookId: UUID,
        projectKey: String,
        provider: String,
        secretEncrypted: String,
        githubSignatureHeader: String?,
        gitlabTokenHeader: String?,
        rawBody: ByteArray,
    ): Boolean {
        val secret = decryptSecret(webhookId, projectKey, secretEncrypted) ?: return false
        val reason = rejectionReason(secret, provider, githubSignatureHeader, gitlabTokenHeader, rawBody)
        if (reason != null) {
            log.warn(
                "git_webhook_signature_rejected webhookId={} projectKey={} provider={} reason={}",
                webhookId,
                projectKey,
                provider,
                reason,
            )
        }
        return reason == null
    }

    /**
     * 등록행 secret 암호문을 복호화한다. 실패하면 `git_webhook_decrypt_failed` 로그를 남기고 `null` 을
     * 반환해 호출자가 `false` 로 수렴하게 한다(예외를 밖으로 던지지 않는다 — 클래스 KDoc 참조).
     *
     * 키 미설정(`IllegalStateException`)·암호문 손상(Hex 디코더의 `IllegalArgumentException`)·키 변경(GCM
     * 인증 태그 실패) 등 구체 예외 타입은 [SecretEncryptor] 내부 구현에 딸린 세부사항이라, 하나라도 놓치면
     * 그대로 500 으로 새어 나간다. 여기서는 **분류가 아니라 봉쇄가 목적**이므로 [RuntimeException] 을 넓게
     * 잡고, 운영자 진단용으로 예외 **타입명만** 남긴다(메시지는 남기지 않는다 — 암호문 조각이 섞일 수 있다).
     */
    @Suppress("TooGenericExceptionCaught") // 넓게 잡는 것이 목적 — 누락된 예외 1건이 401 을 500 으로 바꾼다
    private fun decryptSecret(
        webhookId: UUID,
        projectKey: String,
        secretEncrypted: String,
    ): String? =
        try {
            secretEncryptor.decrypt(secretEncrypted)
        } catch (e: RuntimeException) {
            log.error(
                "git_webhook_decrypt_failed webhookId={} projectKey={} errorType={}",
                webhookId,
                projectKey,
                e::class.simpleName,
            )
            null
        }

    /**
     * 거부 사유를 반환한다. 검증에 통과하면 `null`.
     *
     * provider 분기는 **등록행 값으로만** 하며 기대 헤더가 없어도 다른 provider 방식으로 폴백하지 않는다.
     */
    private fun rejectionReason(
        secret: String,
        provider: String,
        githubSignatureHeader: String?,
        gitlabTokenHeader: String?,
        rawBody: ByteArray,
    ): String? {
        // blank secret 은 provider 무관 거부. GITLAB 은 평문 비교라 secret 이 공백이면 공격자가 같은 공백
        // 헤더로 통과하고, GITHUB 도 빈 키로 유효한 HMAC 을 계산할 수 있다. NOT NULL 은 "공백의 암호문"을
        // 막지 못하므로 도달 가능한 경로다.
        if (secret.isBlank()) {
            return REASON_BLANK_SECRET
        }
        return when (provider) {
            PROVIDER_GITHUB ->
                if (matchesGithubSignature(secret, githubSignatureHeader, rawBody)) null else REASON_MISMATCH
            PROVIDER_GITLAB ->
                if (matchesGitlabToken(secret, gitlabTokenHeader)) null else REASON_MISMATCH
            else -> REASON_UNKNOWN_PROVIDER
        }
    }

    /**
     * GitHub `X-Hub-Signature-256` 검증.
     *
     * SHA-1 거부를 **실제로 강제하는 것은 접두 검사가 아니라 [constantTimeEquals]** 다 — 기대값이 항상
     * `sha256=` 으로 시작하므로 `sha1=...` 이나 접두 없는 hex 는 전체 문자열 비교에서 어차피 불일치한다
     * (접두 검사를 지워도 SHA-1 테스트는 통과한다 — 뮤테이션으로 확인). 접두 검사를 남기는 이유는
     * **미인증 경로의 빠른 거부**다. 형식이 명백히 틀린 헤더에까지 본문 전체 HMAC 을 계산하면 공격자가
     * 헤더 한 줄로 최대 본문 크기만큼의 CPU 를 소모시킬 수 있다.
     *
     * 레거시 `X-Hub-Signature`(SHA-1) 헤더 자체는 컨트롤러가 읽지 않으므로 `null` 로 도달해 거부된다.
     */
    private fun matchesGithubSignature(
        secret: String,
        signatureHeader: String?,
        rawBody: ByteArray,
    ): Boolean {
        if (signatureHeader.isNullOrEmpty() || !signatureHeader.startsWith(GITHUB_SIGNATURE_PREFIX)) {
            return false
        }
        return constantTimeEquals(computeGithubSignature(secret, rawBody), signatureHeader)
    }

    /** GitLab `X-Gitlab-Token` 평문 비교. 평문이라도 상수 시간 비교로 타이밍 누출은 막는다(등급차는 클래스 KDoc). */
    private fun matchesGitlabToken(
        secret: String,
        tokenHeader: String?,
    ): Boolean {
        if (tokenHeader.isNullOrEmpty()) {
            return false
        }
        return constantTimeEquals(secret, tokenHeader)
    }

    /**
     * `sha256=` + lowercase-hex(HMAC-SHA256(secret, rawBody)) 를 계산한다.
     * [Mac] 은 스레드 안전하지 않으므로 호출마다 새 인스턴스를 만든다.
     *
     * slack 과 달리 GitHub base string 에는 접두(버전·timestamp)가 없어 **원문 바이트가 곧 서명 대상**이다.
     * 따라서 [Mac.doFinal] 에 원문을 그대로 넘긴다 — String 왕복도, base string 조립 복사본도 생기지 않는다.
     */
    private fun computeGithubSignature(
        secret: String,
        rawBody: ByteArray,
    ): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return GITHUB_SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(rawBody))
    }

    /**
     * 길이가 같으면 전체를 훑어 비교해 타이밍 누출을 막는다([MessageDigest.isEqual]).
     *
     * ## ★ 이 줄을 `==` 로 바꿔도 **테스트는 한 건도 깨지지 않는다** (뮤테이션으로 확인)
     * 상수 시간 비교는 **결과가 아니라 소요 시간**에 대한 계약이라 기능 테스트로 잡을 수 없다. 즉 이 방어는
     * 테스트가 아니라 **코드 리뷰가 지키는 지점**이다. "같은 결과인데 장황하다"는 이유로 `==`/`equals` 로
     * 바꾸면 응답 시간 차이로 서명을 앞자리부터 한 자리씩 알아내는 타이밍 공격이 열린다. 바꾸지 말 것.
     */
    private fun constantTimeEquals(
        expected: String,
        actual: String,
    ): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8),
        )

    companion object {
        /** 등록행 provider — GitHub. DB CHECK `ck_git_webhooks_provider` 화이트리스트와 같은 문자열. */
        const val PROVIDER_GITHUB = "GITHUB"

        /** 등록행 provider — GitLab. DB CHECK `ck_git_webhooks_provider` 화이트리스트와 같은 문자열. */
        const val PROVIDER_GITLAB = "GITLAB"

        private const val HMAC_ALGORITHM = "HmacSHA256"

        /** `X-Hub-Signature-256` 값의 알고리즘 접두. 서명 = `sha256=`+hex. SHA-1(`sha1=`)은 미지원. */
        private const val GITHUB_SIGNATURE_PREFIX = "sha256="

        /** 거부 사유 — 복호화 결과가 blank(§EC15 fail-closed). */
        private const val REASON_BLANK_SECRET = "blank_secret"

        /** 거부 사유 — 기대 헤더 부재/형식 오류/불일치. 응답에서는 구분하지 않는다(존재 오라클 방지). */
        private const val REASON_MISMATCH = "signature_mismatch"

        /** 거부 사유 — 등록행 provider 가 화이트리스트 밖(데이터 이상). */
        private const val REASON_UNKNOWN_PROVIDER = "unknown_provider"
    }
}
