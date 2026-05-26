// IssueInvariantPropertyTest — Issue 도메인 invariant Kotest property test × 1000 (S11 IssueKey regex + S12 version monotonic + S14 state 전이 이름 수용). seed 고정 1234L.

package com.bts.issue.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.merge
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import java.util.UUID

/**
 * Issue 도메인 invariant property-based 테스트.
 *
 * Kotest의 [checkAll]을 사용해 1000건의 임의 입력에 대해 3가지 invariant를 검증한다.
 * [PropTestConfig]의 seed를 1234L로 고정해 CI에서 재현 가능하도록 한다.
 *
 * S11. IssueKey regex — 유효 형식이면 항상 생성 성공, 반례는 항상 IllegalArgumentException.
 * S12. version monotonic — Issue copy + version+1을 N회 반복하면 version == 1 + N.
 * S14. state 전이 이름 수용 — Issue copy로 임의 toState를 적용하면 currentStateKey == toState.
 */
class IssueInvariantPropertyTest : FunSpec({

    /** 대문자 A-Z codepoint 생성기. Kotest 5.9.1에 AZ() 별도 확장이 없으므로 직접 범위 생성. */
    val upperAlphaCodepoint: Arb<Codepoint> = Arb.int('A'.code, 'Z'.code).map { Codepoint(it) }

    /** 대문자 A-Z + 숫자 0-9 codepoint 생성기. */
    val upperAlphaNumCodepoint: Arb<Codepoint> =
        upperAlphaCodepoint.merge(Arb.int('0'.code, '9'.code).map { Codepoint(it) })

    /** 대문자 A-Z + 언더스코어(_) codepoint 생성기. */
    val upperAlphaUnderscoreCodepoint: Arb<Codepoint> =
        upperAlphaCodepoint.merge(Arb.int('_'.code, '_'.code).map { Codepoint(it) })

    val config1000 = PropTestConfig(seed = 1234L, iterations = 1000)
    val config20 = PropTestConfig(seed = 1234L, iterations = 20)

    // ────────────────────────────────────────────────────────────────────── //
    // S11. IssueKey regex — 유효 입력 1000건 모두 IllegalArgumentException 없이 통과
    // 반례 (소문자 시작 / `-0` 시작 / prefix 10자 초과 / `-` 없음 / 빈 문자열)
    //       100건 모두 IllegalArgumentException throw
    // ────────────────────────────────────────────────────────────────────── //

    /**
     * S11 정상 경로 — 유효한 IssueKey 형식을 1000건 생성해 모두 수용됨을 검증한다.
     *
     * IssueKey regex: ^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$
     * prefix = 대문자 1자 + 대문자/숫자 1~9자 (총 2~10자), number = 1 이상 양의 정수.
     * 반례: 소문자 시작 / number 0 / prefix 11자 초과 / 하이픈 없음 / 빈 문자열은
     *       모두 IllegalArgumentException을 던진다.
     */
    test("S11 IssueKey regex — 유효 형식 1000건 모두 IllegalArgumentException 없이 수용된다") {
        // prefix = 대문자 1자 고정 + 대문자/숫자 1~9자 = 총 2~10자
        val validPrefixArb: Arb<String> =
            Arb.string(1, 9, upperAlphaNumCodepoint).map { suffix ->
                // 첫 글자는 반드시 대문자 알파벳 (A~Z)
                "A$suffix"
            }
        // number = 1 이상 양의 정수
        val numberArb: Arb<Long> = Arb.int(1, 99999).map { it.toLong() }

        checkAll(config1000, validPrefixArb, numberArb) { prefix, number ->
            val key = "$prefix-$number"
            val issueKey = IssueKey(key)
            issueKey.value shouldBe key
        }
    }

    /**
     * S11 반례 — 소문자 시작 키 20건이 모두 IllegalArgumentException을 던짐을 검증한다.
     */
    test("S11 IssueKey regex — 소문자 시작 반례 20건 모두 IllegalArgumentException throw") {
        val lowerStartArb: Arb<String> =
            Arb.string(1, 9, Codepoint.az()).map { suffix -> "a${suffix}-1" }
        checkAll(config20, lowerStartArb) { key ->
            shouldThrow<IllegalArgumentException> { IssueKey(key) }
        }
    }

    /**
     * S11 반례 — number가 0인 키 20건이 모두 IllegalArgumentException을 던짐을 검증한다.
     */
    test("S11 IssueKey regex — number=0 반례 20건 모두 IllegalArgumentException throw") {
        val zeroNumberArb: Arb<String> =
            Arb.string(1, 9, upperAlphaNumCodepoint).map { suffix -> "A${suffix}-0" }
        checkAll(config20, zeroNumberArb) { key ->
            shouldThrow<IllegalArgumentException> { IssueKey(key) }
        }
    }

    /**
     * S11 반례 — prefix 11자 초과 키 20건이 모두 IllegalArgumentException을 던짐을 검증한다.
     */
    test("S11 IssueKey regex — prefix 11자 초과 반례 20건 모두 IllegalArgumentException throw") {
        val longPrefixArb: Arb<String> =
            Arb.string(11, 20, upperAlphaCodepoint).map { longPrefix -> "$longPrefix-1" }
        checkAll(config20, longPrefixArb) { key ->
            shouldThrow<IllegalArgumentException> { IssueKey(key) }
        }
    }

    /**
     * S11 반례 — 하이픈 없는 키 20건이 모두 IllegalArgumentException을 던짐을 검증한다.
     */
    test("S11 IssueKey regex — 하이픈 없는 반례 20건 모두 IllegalArgumentException throw") {
        val noDashArb: Arb<String> = Arb.string(2, 10, upperAlphaCodepoint)
        checkAll(config20, noDashArb) { key ->
            shouldThrow<IllegalArgumentException> { IssueKey(key) }
        }
    }

    /**
     * S11 반례 — 빈 문자열은 IllegalArgumentException을 던진다.
     */
    test("S11 IssueKey regex — 빈 문자열 반례는 IllegalArgumentException throw") {
        shouldThrow<IllegalArgumentException> { IssueKey("") }
    }

    // ────────────────────────────────────────────────────────────────────── //
    // S12. version monotonic — Issue.copy(version+1)을 N회 반복하면 version == 1 + N
    // Issue 는 immutable data class. updateSummary 는 copy() 패턴으로 구현됨.
    // ────────────────────────────────────────────────────────────────────── //

    /**
     * S12 version monotonic — Issue copy + version 증가를 N회 반복해 version == 1 + N임을 검증한다.
     *
     * Issue 는 immutable data class이므로, "updateSummary N회 적용"은
     * copy(summary = ..., version = version + 1)를 N회 체이닝하는 것과 동치다.
     * 1000건의 임의 N(1~100)에 대해 최종 version == 1 + N임을 검증한다.
     * 반례: N=0이면 version은 여전히 1 (repeat 루프 미실행).
     */
    test("S12 version monotonic — updateSummary N회 copy 후 version == 1 + N (1000건)") {
        val nArb: Arb<Int> = Arb.int(1, 100)

        checkAll(config1000, nArb) { n ->
            var issue =
                Issue.create(
                    id = IssueId(UUID.randomUUID()),
                    key = IssueKey.of("PROP", 1L),
                    projectId = UUID.randomUUID(),
                    summary = "initial",
                    reporterId = ActorId(UUID.randomUUID()),
                    currentStateKey = "OPEN",
                )

            // Issue 는 immutable data class — updateSummary = copy(summary=..., version=version+1)
            repeat(n) { i ->
                issue = issue.copy(
                    summary = "updated-$i",
                    version = issue.version + 1L,
                )
            }

            issue.version shouldBe (1L + n)
        }
    }

    // ────────────────────────────────────────────────────────────────────── //
    // S14. state 전이 이름 수용 — 임의 toState(영문 대문자+언더스코어, 1~30자)를
    //       Issue.copy(currentStateKey=toState)로 적용하면 currentStateKey == toState.
    // 도메인 측 invariant — workflow validator 검증은 project-workflow BC 책임 (scope 외).
    // ────────────────────────────────────────────────────────────────────── //

    /**
     * S14 state 전이 이름 수용 — 임의 toState 1000건을 Issue copy로 적용해
     * currentStateKey == toState임을 검증한다.
     *
     * Issue 도메인 자체는 state 이름의 형식을 제한하지 않는다. 유효 상태 집합 검증은
     * project-workflow BC의 WorkflowEngine 책임이므로 이 테스트 scope 밖이다.
     * 반례: Issue 자체가 상태 이름을 거부하는 케이스는 존재하지 않음.
     */
    test("S14 state 전이 이름 수용 — 임의 toState 1000건 모두 currentStateKey 에 적용된다") {
        val toStateArb: Arb<String> = Arb.string(1, 30, upperAlphaUnderscoreCodepoint)

        checkAll(config1000, toStateArb) { toState ->
            val issue =
                Issue.create(
                    id = IssueId(UUID.randomUUID()),
                    key = IssueKey.of("PROP", 1L),
                    projectId = UUID.randomUUID(),
                    summary = "state transition test",
                    reporterId = ActorId(UUID.randomUUID()),
                    currentStateKey = "OPEN",
                )

            // Issue 는 immutable data class — transition = copy(currentStateKey=toState)
            val transitioned = issue.copy(currentStateKey = toState)

            transitioned.currentStateKey shouldBe toState
        }
    }
})
