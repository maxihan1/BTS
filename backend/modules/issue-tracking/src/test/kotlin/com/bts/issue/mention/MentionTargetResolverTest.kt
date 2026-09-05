// MentionTargetResolver 단위 테스트 — 전체/diff 두 모드 · 자기제외 · 미존재 드롭 · 캡 절단 · 정렬

package com.bts.issue.mention

import com.bts.shared.user.UserLookupPort
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * FR-MN-03 — 멘션 대상 산출 규칙.
 *
 * 이 규칙들은 `IssueApplicationService.publishMentions` 안에 인라인돼 있던 것을 옮긴 것이고,
 * **네 경로(이슈 생성·수정 · 댓글 작성·수정)가 같은 산출을 쓰게 하려고** 뽑았다.
 * 경로마다 따로 구현하면 캡·자기제외 같은 규칙이 경로별로 갈린다.
 */
class MentionTargetResolverTest : DescribeSpec({

    val actor = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val bob = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val carol = UUID.fromString("33333333-3333-3333-3333-333333333333")

    /** username → id 를 고정 맵으로 답하는 포트. 맵에 없는 이름은 미존재로 드롭된다. */
    fun portOf(vararg pairs: Pair<String, UUID>): UserLookupPort =
        object : UserLookupPort {
            override fun exists(userId: UUID) = true

            override fun findIdsByUsernames(usernames: Set<String>): Map<String, UUID> =
                pairs.toMap().filterKeys { it in usernames }
        }

    describe("전체 모드 (이슈 생성 · 댓글 작성)") {
        it("본문의 모든 멘션을 대상으로 낸다") {
            val result =
                MentionTargetResolver.resolve(
                    before = null,
                    after = "@bob 과 @carol 봐주세요",
                    actor = actor,
                    userLookupPort = portOf("bob" to bob, "carol" to carol),
                )

            result.targets shouldBe listOf(bob, carol).sorted()
            result.droppedByCap shouldBe 0
        }

        it("멘션이 없으면 빈 결과다") {
            val result =
                MentionTargetResolver.resolve(
                    before = null,
                    after = "멘션 없는 본문",
                    actor = actor,
                    userLookupPort = portOf("bob" to bob),
                )

            result.targets shouldBe emptyList()
        }
    }

    describe("diff 모드 (이슈 수정 · 댓글 수정)") {
        it("before 에 이미 있던 멘션은 뺀다") {
            val result =
                MentionTargetResolver.resolve(
                    before = "@bob 안녕",
                    after = "@bob 안녕 @carol 도 봐주세요",
                    actor = actor,
                    userLookupPort = portOf("bob" to bob, "carol" to carol),
                )

            result.targets shouldBe listOf(carol)
        }

        it("멘션을 지우기만 한 수정은 대상이 없다") {
            val result =
                MentionTargetResolver.resolve(
                    before = "@bob @carol",
                    after = "@bob",
                    actor = actor,
                    userLookupPort = portOf("bob" to bob, "carol" to carol),
                )

            result.targets shouldBe emptyList()
        }
    }

    describe("승계 규칙 — publishMentions 와 동일해야 한다") {
        it("자기 자신 멘션은 제외한다") {
            val result =
                MentionTargetResolver.resolve(
                    before = null,
                    after = "@me 와 @bob",
                    actor = actor,
                    userLookupPort = portOf("me" to actor, "bob" to bob),
                )

            result.targets shouldBe listOf(bob)
        }

        it("실재하지 않는 username 은 조용히 드롭한다") {
            val result =
                MentionTargetResolver.resolve(
                    before = null,
                    after = "@bob @nobody",
                    actor = actor,
                    userLookupPort = portOf("bob" to bob),
                )

            result.targets shouldBe listOf(bob)
        }

        it("코드블록 안의 @는 멘션이 아니다") {
            val result =
                MentionTargetResolver.resolve(
                    before = null,
                    after = "```\n@bob\n```",
                    actor = actor,
                    userLookupPort = portOf("bob" to bob),
                )

            result.targets shouldBe emptyList()
        }

        it("UUID 오름차순으로 정렬한다 (결정적 직렬화)") {
            val result =
                MentionTargetResolver.resolve(
                    before = null,
                    after = "@carol @bob",
                    actor = actor,
                    userLookupPort = portOf("bob" to bob, "carol" to carol),
                )

            result.targets shouldBe result.targets.sorted()
        }

        it("캡을 넘으면 알파벳 오름차순 앞부분만 남기고 드롭 수를 낸다") {
            // username 60개 → 캡 50. u000..u059 중 앞 50개(u000~u049)만 남아야 한다.
            val names = (0 until 60).map { "u%03d".format(it) }
            val ids = names.associateWith { UUID.nameUUIDFromBytes(it.toByteArray()) }
            val body = names.joinToString(" ") { "@$it" }

            val result =
                MentionTargetResolver.resolve(
                    before = null,
                    after = body,
                    actor = actor,
                    userLookupPort =
                        object : UserLookupPort {
                            override fun exists(userId: UUID) = true

                            override fun findIdsByUsernames(usernames: Set<String>) =
                                ids.filterKeys { it in usernames }
                        },
                )

            result.targets.size shouldBe MentionTargetResolver.MAX_MENTIONS_PER_EVENT
            result.droppedByCap shouldBe 10
            // 절단은 알파벳 기준이므로 u050 이후는 없어야 한다
            result.targets.contains(ids["u050"]) shouldBe false
            result.targets.contains(ids["u000"]) shouldBe true
        }

        // ★E5 는 여기서 지킬 수 없다 — Resolver 는 목록을 하나만 내므로 갈릴 자리가 없고,
        //   「그 하나를 알림과 watcher 양쪽에 넘겼는가」는 서비스 계층의 사실이다.
        //   동어반복 단언(result.targets shouldBe result.targets)을 두면 판정을 지워도 통과하므로
        //   그 방어는 IssueApplicationServiceMentionTest / CommentApplicationServiceTest 가 진다.
    }
})
