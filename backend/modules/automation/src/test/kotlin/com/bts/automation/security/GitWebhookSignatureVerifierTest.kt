// Git 인바운드 웹훅 서명 검증기 단위 테스트 — GITHUB HMAC / GITLAB 평문 토큰의 fail-closed 계약 (FR-AT-07 PR-C Task 7)

package com.bts.automation.security

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bts.shared.crypto.SecretEncryptor
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import org.slf4j.LoggerFactory
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [GitWebhookSignatureVerifier] 의 보안 계약 검증.
 *
 * 이 테스트가 지키는 핵심 불변식은 **"검증되지 않은 요청은 예외가 아니라 `false` 로 수렴한다"** 이다.
 * 컨트롤러(Task 10)는 `false` → 401 매핑만 하므로, 여기서 예외가 새어나가면 401 이어야 할 응답이 500 이
 * 된다(저장소 3대 사고 — catch-all / 핸들러 스코프 / 동명 예외). 따라서 복호화 실패까지 이 클래스가
 * 삼켜 `false` 로 만드는지를 명시적으로 검증한다.
 */
class GitWebhookSignatureVerifierTest : DescribeSpec({

    val webhookId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    val projectKey = "PROJ"
    val ciphertext = "0badc0de"
    val secret = "shared-secret-at-least-16-chars"
    val body = """{"action":"closed"}""".toByteArray(Charsets.UTF_8)

    /** 복호화 결과를 [plaintext] 로 고정한 검증기를 만든다. */
    fun verifierWithSecret(plaintext: String): GitWebhookSignatureVerifier {
        val encryptor = mockk<SecretEncryptor>()
        every { encryptor.decrypt(ciphertext) } returns plaintext
        return GitWebhookSignatureVerifier(encryptor)
    }

    /** 복호화가 [failure] 를 던지는 검증기를 만든다. */
    fun verifierWithDecryptFailure(failure: RuntimeException): GitWebhookSignatureVerifier {
        val encryptor = mockk<SecretEncryptor>()
        every { encryptor.decrypt(ciphertext) } throws failure
        return GitWebhookSignatureVerifier(encryptor)
    }

    describe("GITHUB — X-Hub-Signature-256 HMAC 검증") {
        it("★ GitHub 공식 문서의 테스트 벡터를 유효로 판정한다 (외부 오라클 — 구현이 GitHub 규격과 일치함을 증명)") {
            // GitHub "Validating webhook deliveries" 문서 예시.
            // secret="It's a Secret to Everybody", payload="Hello, World!" 의 정답 서명.
            // 자체 계산끼리 비교하면 "둘 다 틀려도 통과"하므로, 외부에서 고정된 이 값으로 규격 일치를 못 박는다.
            val docSecret = "It's a Secret to Everybody"
            val docBody = "Hello, World!".toByteArray(Charsets.UTF_8)
            val docSignature = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"

            verifierWithSecret(docSecret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = docSignature,
                gitlabTokenHeader = null,
                rawBody = docBody,
            ) shouldBe true
        }

        it("유효한 서명이면 true 를 반환한다") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = githubSignature(secret, body),
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe true
        }

        it("위조된 서명이면 false 를 반환한다") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = githubSignature("공격자가-추측한-secret", body),
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }

        it("다른 본문으로 계산된 서명이면 false 를 반환한다 (본문 무결성)") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = githubSignature(secret, """{"action":"opened"}""".toByteArray()),
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }

        it("X-Hub-Signature-256 헤더가 없으면 false 를 반환한다") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = null,
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }

        it("X-Hub-Signature-256 헤더가 빈 문자열이면 false 를 반환한다") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = "",
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }

        it("★ 비-UTF8 바이트 본문도 원문 그대로 검증한다 (String 왕복 금지 계약 — 왕복하면 U+FFFD 치환으로 오거부)") {
            // 0xFF 0xFE 는 유효한 UTF-8 시퀀스가 아니다. 구현이 본문을 String 으로 디코드했다가
            // 되돌리면 이 바이트가 EF BF BD(U+FFFD)로 바뀌어 HMAC 대상이 달라지고 진짜 요청이 거부된다.
            val binaryBody = byteArrayOf(0x7B, 0xFF.toByte(), 0xFE.toByte(), 0x7D)

            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = githubSignature(secret, binaryBody),
                gitlabTokenHeader = null,
                rawBody = binaryBody,
            ) shouldBe true
        }
    }

    describe("GITLAB — X-Gitlab-Token 평문 비교") {
        it("등록 secret 과 같은 토큰이면 true 를 반환한다") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITLAB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = null,
                gitlabTokenHeader = secret,
                rawBody = body,
            ) shouldBe true
        }

        it("토큰이 다르면 false 를 반환한다") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITLAB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = null,
                gitlabTokenHeader = "$secret-접미사",
                rawBody = body,
            ) shouldBe false
        }

        it("X-Gitlab-Token 헤더가 없으면 false 를 반환한다") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITLAB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = null,
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }
    }

    describe("★ 교차 헤더 — provider 는 등록행으로만 판정하고 폴백하지 않는다") {
        it("EC2. GITHUB 등록인데 X-Gitlab-Token 만 오면 false (GitLab 방식으로 폴백 금지)") {
            // 토큰 값이 secret 과 정확히 일치해도 GITHUB 등록이므로 통과시키면 안 된다.
            // 폴백을 허용하면 공격자가 약한 쪽(평문 비교) 방식을 골라 HMAC 검증을 우회한다(혼동 공격).
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = null,
                gitlabTokenHeader = secret,
                rawBody = body,
            ) shouldBe false
        }

        it("EC3. GITLAB 등록인데 X-Hub-Signature-256 만 오면 false (GitHub 방식으로 폴백 금지)") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITLAB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = githubSignature(secret, body),
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }

        it("등록행 provider 가 화이트리스트 밖이면 false (알 수 없는 provider 는 거부)") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "BITBUCKET",
                secretEncrypted = ciphertext,
                githubSignatureHeader = githubSignature(secret, body),
                gitlabTokenHeader = secret,
                rawBody = body,
            ) shouldBe false
        }

        it("provider 판정은 대소문자를 관대하게 받지 않는다 (등록행은 DB CHECK 로 대문자 2종뿐)") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "github",
                secretEncrypted = ciphertext,
                githubSignatureHeader = githubSignature(secret, body),
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }
    }

    describe("★ EC4. GitHub 레거시 SHA-1 미지원") {
        it("레거시 X-Hub-Signature 만 온 요청은 false (SHA-256 헤더 부재 → 거부)") {
            // 레거시 헤더는 아예 읽지 않는다. 컨트롤러가 X-Hub-Signature-256 만 전달하므로 null 로 도달한다.
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = null,
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }

        it("sha1= 접두 서명 값이 오면 false (접두 검사로 SHA-1 거부)") {
            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = "sha1=${sha1Signature(secret, body)}",
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }

        it("접두 없는 순수 hex 서명이면 false") {
            val bare = githubSignature(secret, body).removePrefix("sha256=")

            verifierWithSecret(secret).isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = bare,
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }
    }

    describe("★ EC15. 복호화 결과가 blank 면 fail-closed") {
        it("GITLAB — secret 이 공백뿐이면 같은 공백 토큰을 보내도 false") {
            // ★ 이 테스트가 판별자다. blank 가드가 없으면 상수시간 비교가 "   " == "   " 로 true 가 되어
            // 공격자는 웹훅 URL 토큰만 알면 공백 헤더로 통과한다. secret_encrypted 가 NOT NULL 이어도
            // "공백의 암호문"은 저장 가능하므로 도달 가능한 경로다.
            verifierWithSecret("   ").isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITLAB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = null,
                gitlabTokenHeader = "   ",
                rawBody = body,
            ) shouldBe false
        }

        it("GITHUB — secret 이 빈 문자열이면 빈 키로 계산한 정상 서명도 false") {
            // 빈 문자열도 HMAC 키로 성립한다. blank 가드가 없으면 secret 이 비었다는 사실만 알면
            // 누구나 유효한 서명을 만들 수 있다.
            verifierWithSecret("").isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = githubSignature("", body),
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }
    }

    describe("★ EC9. 복호화 실패 → 예외가 아니라 false + git_webhook_decrypt_failed 로그") {
        it("암호화 키 미설정(IllegalStateException)이면 false 를 반환하고 예외를 던지지 않는다") {
            val verifier = verifierWithDecryptFailure(IllegalStateException("encryption key not configured"))

            verifier.isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITHUB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = githubSignature(secret, body),
                gitlabTokenHeader = null,
                rawBody = body,
            ) shouldBe false
        }

        it("암호문 손상(IllegalArgumentException)이면 false 를 반환하고 예외를 던지지 않는다") {
            val verifier = verifierWithDecryptFailure(IllegalArgumentException("Illegal hexadecimal character"))

            verifier.isValid(
                webhookId = webhookId,
                projectKey = projectKey,
                provider = "GITLAB",
                secretEncrypted = ciphertext,
                githubSignatureHeader = null,
                gitlabTokenHeader = secret,
                rawBody = body,
            ) shouldBe false
        }

        it("복호화 실패는 git_webhook_decrypt_failed 로 로깅한다 (서명 불일치와 이벤트명이 갈린다)") {
            val verifier = verifierWithDecryptFailure(IllegalStateException("encryption key not configured"))

            val logs =
                captureLogs {
                    verifier.isValid(
                        webhookId = webhookId,
                        projectKey = projectKey,
                        provider = "GITHUB",
                        secretEncrypted = ciphertext,
                        githubSignatureHeader = githubSignature(secret, body),
                        gitlabTokenHeader = null,
                        rawBody = body,
                    )
                }

            val message = logs.single()
            message shouldContain "git_webhook_decrypt_failed"
            message shouldContain webhookId.toString()
            message shouldContain projectKey
        }

        it("★ 복호화 실패 로그에 암호문·secret 평문이 새지 않는다 (§1.1.2)") {
            val verifier = verifierWithDecryptFailure(IllegalStateException("encryption key not configured"))

            val logs =
                captureLogs {
                    verifier.isValid(
                        webhookId = webhookId,
                        projectKey = projectKey,
                        provider = "GITHUB",
                        secretEncrypted = ciphertext,
                        githubSignatureHeader = githubSignature(secret, body),
                        gitlabTokenHeader = null,
                        rawBody = body,
                    )
                }

            val message = logs.single()
            message shouldNotContain ciphertext
            message shouldNotContain secret
        }
    }

    describe("★ 2A. 서명 불일치 로그는 복호화 실패와 이벤트명이 갈린다") {
        it("서명이 위조되면 git_webhook_signature_rejected 로 로깅한다") {
            val logs =
                captureLogs {
                    verifierWithSecret(secret).isValid(
                        webhookId = webhookId,
                        projectKey = projectKey,
                        provider = "GITHUB",
                        secretEncrypted = ciphertext,
                        githubSignatureHeader = githubSignature("wrong", body),
                        gitlabTokenHeader = null,
                        rawBody = body,
                    )
                }

            val message = logs.single()
            message shouldContain "git_webhook_signature_rejected"
            message shouldNotContain "git_webhook_decrypt_failed"
        }

        it("★ 서명 불일치 로그에 secret·서명 헤더·본문이 새지 않는다 (§1.1.2)") {
            val forged = githubSignature("wrong", body)
            val logs =
                captureLogs {
                    verifierWithSecret(secret).isValid(
                        webhookId = webhookId,
                        projectKey = projectKey,
                        provider = "GITHUB",
                        secretEncrypted = ciphertext,
                        githubSignatureHeader = forged,
                        gitlabTokenHeader = null,
                        rawBody = body,
                    )
                }

            val message = logs.single()
            message shouldNotContain secret
            message shouldNotContain forged
            message shouldNotContain String(body, Charsets.UTF_8)
        }
    }
})

private const val HMAC_SHA256 = "HmacSHA256"

/**
 * 검증기와 **독립적으로** GitHub 서명(`sha256=` + hex(HMAC-SHA256(secret, body)))을 계산하는 테스트 오라클.
 * 구현 코드를 재사용하면 "구현이 틀려도 테스트가 같이 틀려" 통과하므로 여기서 직접 계산한다.
 */
private fun githubSignature(
    secret: String,
    body: ByteArray,
): String {
    val mac = Mac.getInstance(HMAC_SHA256)
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC_SHA256))
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body))
}

/** 레거시 SHA-1 서명값(미지원 확인용) 계산. */
private fun sha1Signature(
    secret: String,
    body: ByteArray,
): String {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
    return HexFormat.of().formatHex(mac.doFinal(body))
}

/**
 * [block] 실행 중 [GitWebhookSignatureVerifier] 로거에 쌓인 로그의 포맷 메시지 목록을 반환한다
 * ([com.bts.automation.application.TemplateRendererTest] 동형 패턴).
 */
private fun captureLogs(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(GitWebhookSignatureVerifier::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.filter { it.level == Level.WARN || it.level == Level.ERROR }.map { it.formattedMessage }
}
