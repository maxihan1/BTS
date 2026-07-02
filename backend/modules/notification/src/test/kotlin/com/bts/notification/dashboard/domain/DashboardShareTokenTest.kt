// 대시보드 공유 토큰 도메인 단위 테스트 — 불투명 원문 발급·SHA-256 해싱·만료 경계·원문 미보관 검증

package com.bts.notification.dashboard.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.reflect.full.memberProperties

class DashboardShareTokenTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-07-02T00:00:00Z")
    val dashboardId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    val createdBy: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    val minter = ShareTokenMinter()

    // ── 발급·해싱 ───────────────────────────────────────────────────────────────

    describe("ShareTokenMinter.mint — 원문 발급·해싱") {
        it("256bit(32바이트) base64url 불투명 원문 토큰과 그 SHA-256 hex(소문자 64자) 해시를 반환한다") {
            val minted = minter.mint(dashboardId, createdBy, expiresAt = null, now = fixedNow)

            // 원문은 base64url(패딩 없음) 인코딩된 32바이트 랜덤값
            val rawBytes = Base64.getUrlDecoder().decode(minted.plaintext)
            rawBytes.size shouldBe 32

            // 해시는 소문자 hex 64자
            minted.token.tokenHash.length shouldBe 64
            minted.token.tokenHash shouldMatch Regex("^[0-9a-f]{64}$")

            // 해시는 원문의 SHA-256 재해싱과 일치
            minted.token.tokenHash shouldBe minter.hash(minted.plaintext)

            // 엔티티 필드 매핑
            minted.token.dashboardId shouldBe dashboardId
            minted.token.createdBy shouldBe createdBy
            minted.token.createdAt shouldBe fixedNow
            minted.token.expiresAt shouldBe null
        }

        it("동일 원문 토큰은 항상 동일 해시 문자열로 매핑된다(결정적)") {
            // 알려진 SHA-256 벡터로 결정성 고정: SHA-256("abc")
            minter.hash("abc") shouldBe
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

            // 반복 호출 안정성
            val minted = minter.mint(dashboardId, createdBy, expiresAt = null, now = fixedNow)
            minter.hash(minted.plaintext) shouldBe minter.hash(minted.plaintext)
        }

        it("서로 다른 발급은 서로 다른 원문 토큰을 생성한다") {
            val a = minter.mint(dashboardId, createdBy, expiresAt = null, now = fixedNow)
            val b = minter.mint(dashboardId, createdBy, expiresAt = null, now = fixedNow)

            a.plaintext shouldNotBe b.plaintext
            a.token.tokenHash shouldNotBe b.token.tokenHash
            a.token.id shouldNotBe b.token.id
        }
    }

    // ── 만료 경계 ───────────────────────────────────────────────────────────────

    describe("DashboardShareToken.isExpired — 만료 경계") {
        fun tokenWithExpiry(expiresAt: Instant?): DashboardShareToken =
            minter.mint(dashboardId, createdBy, expiresAt = expiresAt, now = fixedNow).token

        it("expiresAt < now 이면 만료(true)") {
            tokenWithExpiry(fixedNow.minusSeconds(1)).isExpired(fixedNow) shouldBe true
        }

        it("expiresAt == now 이면 만료(true) — 경계 포함") {
            tokenWithExpiry(fixedNow).isExpired(fixedNow) shouldBe true
        }

        it("expiresAt > now 이면 미만료(false)") {
            tokenWithExpiry(fixedNow.plusSeconds(1)).isExpired(fixedNow) shouldBe false
        }

        it("expiresAt == null 이면 항상 미만료(false)") {
            val token = tokenWithExpiry(null)
            token.isExpired(fixedNow) shouldBe false
            token.isExpired(fixedNow.plusSeconds(3_153_600_000)) shouldBe false
        }
    }

    // ── 원문 미보관 (보안) ──────────────────────────────────────────────────────

    describe("DashboardShareToken — 원문 미보관") {
        it("tokenHash(String) 필드만 존재하고 원문 필드는 존재하지 않는다") {
            val minted = minter.mint(dashboardId, createdBy, expiresAt = null, now = fixedNow)

            val props = DashboardShareToken::class.memberProperties
            val propNames = props.map { it.name }.toSet()

            // tokenHash 필드는 String 이어야 한다
            propNames.contains("tokenHash") shouldBe true
            minted.token.tokenHash::class shouldBe String::class

            // 원문 노출 필드가 없어야 한다
            propNames.contains("plaintext") shouldBe false
            propNames.contains("plainText") shouldBe false
            propNames.contains("rawToken") shouldBe false
            propNames.contains("token") shouldBe false
            propNames.contains("secret") shouldBe false

            // 어떤 필드 값도 원문과 같지 않아야 한다
            val holdsPlaintext =
                props.any { prop ->
                    @Suppress("UNCHECKED_CAST")
                    val value = (prop as kotlin.reflect.KProperty1<DashboardShareToken, *>).get(minted.token)
                    value == minted.plaintext
                }
            holdsPlaintext shouldBe false
        }
    }
})
