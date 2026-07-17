// GitWebhookService 단위 테스트 — PR_MERGED 파이프라인/3중 상한/dedup/롤백 (FR-AT-07 PR-C Task 9 TDD RED)

package com.bts.automation.application

import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.adapter.GitWebhookRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.GitProvider
import com.bts.automation.domain.GitWebhook
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val PROJECT_KEY = "PROJ"
private const val OTHER_PROJECT_KEY = "OTHER"

/**
 * [GitWebhookService] 단위 테스트 (FR-AT-07 PR-C Task 9).
 *
 * [GitWebhookRepository]/[AutomationRuleRepository]/[AutomationExecutionEnqueuer] 를 MockK 로 대체해
 * DB/pgmq 없이 §3.5 파이프라인(⑤ 이벤트 판정 → ⑥ 이슈키 추출+스코프 필터+20키 상한 → ⑦ dedup →
 * ⑧ 룰 조회+룰×키 100 상한 → ⑨ targetBranch 필터 → ⑩ enqueue)만 검증한다.
 *
 * ## ★ S1 검증 경계 (DEC-25 — outside voice 발견)
 * S1 은 [AutomationExecutionEnqueuer.enqueue] 가 `TriggerType.PR_MERGED` + 이 서비스가 조립한
 * triggerEvent(§3.10 스키마)로 호출되는 지점까지만 검증한다. "그 triggerEvent 가 워커에 소비돼
 * 실제로 `IssueMutationPort.setFixVersions` 를 호출하고 이슈 fixVersions 가 실제로 바뀐다"는 이
 * 테스트의 관측 범위 **밖**이다 — automation 모듈은 `AutomationExecutionWorker`→`ActionExecutor`
 * 재사용이 이미 FR-AT-01/02 에서 검증됐고(하류 재사용, 신규 로직 아님), "포트 호출 → 실제 이슈
 * 변경" 구간은 PR-B(#276) 가 issue-tracking `AutomationIssueMutationAdapter` 통합 테스트로 이미
 * 검증했다(`StubIssueMutationPort.kt:14-22` 가 automation 클래스패스에 prod 어댑터가 없음을 명시).
 * 두 PR 을 이으면 S1 전 구간이 덮인다 — 이 파일은 "안 쟀다"가 아니라 **책임 분리**다.
 */
class GitWebhookServiceTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-07-17T00:00:00Z")
    val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    val objectMapper = ObjectMapper()

    val gitWebhookRepository = mockk<GitWebhookRepository>()
    val automationRuleRepository = mockk<AutomationRuleRepository>()
    val executionEnqueuer = mockk<AutomationExecutionEnqueuer>()

    val service =
        GitWebhookService(
            gitWebhookRepository = gitWebhookRepository,
            automationRuleRepository = automationRuleRepository,
            executionEnqueuer = executionEnqueuer,
            objectMapper = objectMapper,
            clock = clock,
        )

    beforeEach {
        // 기본값 — 배달 신규(dedup 통과), enqueue 성공. 개별 it 이 필요 시 오버라이드.
        every { gitWebhookRepository.insertDelivery(any(), any(), any()) } returns true
        every { executionEnqueuer.enqueue(any(), any(), any()) } just Runs
    }

    afterEach {
        clearMocks(gitWebhookRepository, automationRuleRepository, executionEnqueuer)
    }

    fun webhookOf(
        projectKey: String = PROJECT_KEY,
        provider: GitProvider = GitProvider.GITHUB,
    ): GitWebhook =
        GitWebhook(
            id = UUID.randomUUID(),
            projectKey = projectKey,
            provider = provider,
            tokenHash = "token-hash",
            secretEncrypted = "enc",
            createdAt = fixedNow,
            createdBy = UUID.randomUUID(),
            deletedAt = null,
        )

    fun prMergedRule(
        projectKey: String = PROJECT_KEY,
        targetBranch: String? = null,
    ): AutomationRule =
        AutomationRule.create(
            projectKey = projectKey,
            name = "PR 머지 룰",
            triggerType = TriggerType.PR_MERGED,
            triggerConfig = targetBranch?.let { """{"targetBranch":"$it"}""" } ?: TriggerConfig.EMPTY,
            createdBy = UUID.randomUUID(),
            now = fixedNow,
        )

    fun githubMergedPayload(
        title: String = "Fix login",
        body: String = "Closes PROJ-42",
        targetBranch: String = "release/1.2",
    ): JsonNode =
        objectMapper.readTree(
            """
            {
              "action": "closed",
              "pull_request": {
                "number": 123,
                "title": "$title",
                "body": "$body",
                "merged": true,
                "merged_at": "2026-07-17T00:00:00Z",
                "html_url": "https://github.com/org/repo/pull/123",
                "base": { "ref": "$targetBranch" }
              }
            }
            """.trimIndent(),
        )

    fun gitlabMergedPayload(
        title: String = "Fix login",
        body: String = "Closes PROJ-42",
        targetBranch: String = "release/1.2",
    ): JsonNode =
        objectMapper.readTree(
            """
            {
              "object_kind": "merge_request",
              "object_attributes": {
                "iid": 55,
                "title": "$title",
                "description": "$body",
                "action": "merge",
                "target_branch": "$targetBranch",
                "url": "https://gitlab.com/org/repo/-/merge_requests/55",
                "updated_at": "2026-07-17T00:00:00Z"
              }
            }
            """.trimIndent(),
        )

    describe("S1 — GITHUB 정상 머지가 발화한다") {
        it("PR_MERGED 룰이 issueKey·provider·pr 필드를 담은 triggerEvent 로 enqueue 된다") {
            val webhook = webhookOf()
            val rule = prMergedRule(targetBranch = "release/1.2")
            every {
                automationRuleRepository.findEnabledByProjectAndTriggerType(PROJECT_KEY, TriggerType.PR_MERGED)
            } returns listOf(rule)

            val eventSlot = slot<JsonNode>()
            service.handleInboundEvent(webhook, "pull_request", "delivery-1", githubMergedPayload())

            verify(exactly = 1) {
                executionEnqueuer.enqueue(rule.id, TriggerType.PR_MERGED, capture(eventSlot))
            }
            eventSlot.captured.path("issueKey").asText() shouldBe "PROJ-42"
            eventSlot.captured.path("provider").asText() shouldBe "GITHUB"
            eventSlot.captured.path("pr").path("title").asText() shouldBe "Fix login"
            eventSlot.captured.path("pr").path("body").asText() shouldBe "Closes PROJ-42"
            eventSlot.captured.path("pr").path("targetBranch").asText() shouldBe "release/1.2"
            eventSlot.captured.path("pr").path("number").asInt() shouldBe 123
            verify(exactly = 1) { gitWebhookRepository.insertDelivery(webhook.id, "delivery-1", fixedNow) }
        }
    }

    describe("S1-GITLAB — GITLAB 정상 머지도 동일 파이프라인으로 발화한다") {
        it("Merge Request Hook + action=merge 가 GITLAB provider 로 enqueue 된다") {
            val webhook = webhookOf(provider = GitProvider.GITLAB)
            val rule = prMergedRule()
            every {
                automationRuleRepository.findEnabledByProjectAndTriggerType(PROJECT_KEY, TriggerType.PR_MERGED)
            } returns listOf(rule)

            val eventSlot = slot<JsonNode>()
            service.handleInboundEvent(webhook, "Merge Request Hook", "delivery-gl-1", gitlabMergedPayload())

            verify(exactly = 1) {
                executionEnqueuer.enqueue(rule.id, TriggerType.PR_MERGED, capture(eventSlot))
            }
            eventSlot.captured.path("provider").asText() shouldBe "GITLAB"
            eventSlot.captured.path("issueKey").asText() shouldBe "PROJ-42"
        }
    }

    describe("S3 — 프로젝트 스코프 위반") {
        it("추출된 이슈키가 등록행 projectKey 접두와 다르면 0건 처리하고 룰 조회조차 하지 않는다") {
            val webhook = webhookOf(projectKey = PROJECT_KEY)

            service.handleInboundEvent(
                webhook,
                "pull_request",
                "delivery-2",
                githubMergedPayload(body = "Closes $OTHER_PROJECT_KEY-1"),
            )

            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
            verify(exactly = 0) { automationRuleRepository.findEnabledByProjectAndTriggerType(any(), any()) }
        }

        it("PROJ2-1 처럼 하이픈까지 포함한 접두가 아니면 PROJ 룰을 통과하지 못한다") {
            val webhook = webhookOf(projectKey = "PROJ")

            service.handleInboundEvent(
                webhook,
                "pull_request",
                "delivery-2b",
                githubMergedPayload(body = "Closes PROJ2-1"),
            )

            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
        }
    }

    describe("S4 — 머지 아닌 이벤트는 0건 처리한다") {
        it("action=opened 는 무시된다") {
            val webhook = webhookOf()
            val payload =
                objectMapper.readTree(
                    """{"action":"opened","pull_request":{"number":1,"merged":false,"base":{"ref":"main"}}}""",
                )

            service.handleInboundEvent(webhook, "pull_request", "delivery-3", payload)

            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
            verify(exactly = 0) { gitWebhookRepository.insertDelivery(any(), any(), any()) }
        }

        it("action=closed 인데 merged=false 는 무시된다") {
            val webhook = webhookOf()
            val payload =
                objectMapper.readTree(
                    """{"action":"closed","pull_request":{"number":1,"merged":false}}""",
                )

            service.handleInboundEvent(webhook, "pull_request", "delivery-4", payload)

            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
        }

        it("push 이벤트는 무시된다") {
            val webhook = webhookOf()
            val payload = objectMapper.readTree("""{"ref":"refs/heads/main","commits":[]}""")

            service.handleInboundEvent(webhook, "push", "delivery-5", payload)

            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
        }
    }

    describe("S5 — targetBranch 로 선택 발화한다") {
        it("release/1.2 룰만 발화하고 release/2.0 룰은 skip 된다") {
            val webhook = webhookOf()
            val ruleMatching = prMergedRule(targetBranch = "release/1.2")
            val ruleOther = prMergedRule(targetBranch = "release/2.0")
            every {
                automationRuleRepository.findEnabledByProjectAndTriggerType(PROJECT_KEY, TriggerType.PR_MERGED)
            } returns listOf(ruleMatching, ruleOther)

            service.handleInboundEvent(
                webhook,
                "pull_request",
                "delivery-6",
                githubMergedPayload(targetBranch = "release/1.2"),
            )

            verify(exactly = 1) { executionEnqueuer.enqueue(ruleMatching.id, TriggerType.PR_MERGED, any()) }
            verify(exactly = 0) { executionEnqueuer.enqueue(ruleOther.id, TriggerType.PR_MERGED, any()) }
        }

        it("targetBranch 미지정 룰은 모든 브랜치에 발화한다") {
            val webhook = webhookOf()
            val ruleAny = prMergedRule(targetBranch = null)
            every {
                automationRuleRepository.findEnabledByProjectAndTriggerType(PROJECT_KEY, TriggerType.PR_MERGED)
            } returns listOf(ruleAny)

            service.handleInboundEvent(
                webhook,
                "pull_request",
                "delivery-7",
                githubMergedPayload(targetBranch = "feature/whatever"),
            )

            verify(exactly = 1) { executionEnqueuer.enqueue(ruleAny.id, TriggerType.PR_MERGED, any()) }
        }
    }

    describe("EC12 — 팬아웃 3중 상한 중 distinct 키 20 초과") {
        it("21개 이슈키를 언급하면 0건 처리한다(fail-closed)") {
            val webhook = webhookOf()
            val mentions = (1..21).joinToString(" ") { "Closes PROJ-$it" }
            val payload = githubMergedPayload(body = mentions)

            service.handleInboundEvent(webhook, "pull_request", "delivery-8", payload)

            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
            verify(exactly = 0) { automationRuleRepository.findEnabledByProjectAndTriggerType(any(), any()) }
        }
    }

    describe("EC12 — 팬아웃 3중 상한 중 룰×키 100 초과") {
        it("6개 룰 × 20개 키(=120) 는 100 을 넘어 0건 처리한다") {
            val webhook = webhookOf()
            val rules = (1..6).map { prMergedRule() }
            every {
                automationRuleRepository.findEnabledByProjectAndTriggerType(PROJECT_KEY, TriggerType.PR_MERGED)
            } returns rules
            val mentions = (1..20).joinToString(" ") { "Closes PROJ-$it" }

            service.handleInboundEvent(webhook, "pull_request", "delivery-9", githubMergedPayload(body = mentions))

            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
        }
    }

    describe("팬아웃 payload 절단 — pr.title/pr.body 는 2KB 이하로 절단된다") {
        it("4000자 title/body 도 enqueue 되는 payload 에서는 2048자 이하다") {
            val webhook = webhookOf()
            val rule = prMergedRule()
            every {
                automationRuleRepository.findEnabledByProjectAndTriggerType(PROJECT_KEY, TriggerType.PR_MERGED)
            } returns listOf(rule)
            val longTitle = "T".repeat(4000)
            val longBody = "Closes PROJ-42 " + "B".repeat(4000)

            val eventSlot = slot<JsonNode>()
            service.handleInboundEvent(
                webhook,
                "pull_request",
                "delivery-10",
                githubMergedPayload(title = longTitle, body = longBody),
            )

            verify(exactly = 1) { executionEnqueuer.enqueue(rule.id, TriggerType.PR_MERGED, capture(eventSlot)) }
            val prNode = eventSlot.captured.path("pr")
            (prNode.path("title").asText().length <= 2048) shouldBe true
            (prNode.path("body").asText().length <= 2048) shouldBe true
        }
    }

    describe("EC10 — 팬아웃 중 일부 enqueue 실패는 전량 롤백(dedup 포함) 대상이 되도록 예외를 전파한다") {
        it("두번째 enqueue 호출이 실패하면 예외가 그대로 전파된다(swallow 금지)") {
            val webhook = webhookOf()
            val ruleA = prMergedRule()
            val ruleB = prMergedRule()
            every {
                automationRuleRepository.findEnabledByProjectAndTriggerType(PROJECT_KEY, TriggerType.PR_MERGED)
            } returns listOf(ruleA, ruleB)
            every { executionEnqueuer.enqueue(ruleA.id, TriggerType.PR_MERGED, any()) } just Runs
            every {
                executionEnqueuer.enqueue(ruleB.id, TriggerType.PR_MERGED, any())
            } throws IllegalStateException("pgmq 장애")

            shouldThrow<IllegalStateException> {
                service.handleInboundEvent(webhook, "pull_request", "delivery-11", githubMergedPayload())
            }

            // dedup INSERT 는 실패 이전에 이미 호출됐다 — 단일 @Transactional 이 이 메서드 전체를 감싸므로
            // Spring 프록시가 전파된 예외를 잡아 dedup 포함 전량 롤백한다(단위 테스트는 예외 전파만 실증).
            verify(exactly = 1) { gitWebhookRepository.insertDelivery(webhook.id, "delivery-11", fixedNow) }
        }
    }

    describe("EC10 부속 — 중복 배달은 룰 조회 없이 조용히 종료한다") {
        it("insertDelivery 가 false(중복)면 룰 조회·enqueue 모두 스킵된다") {
            val webhook = webhookOf()
            every { gitWebhookRepository.insertDelivery(any(), any(), any()) } returns false

            service.handleInboundEvent(webhook, "pull_request", "delivery-12", githubMergedPayload())

            verify(exactly = 0) { automationRuleRepository.findEnabledByProjectAndTriggerType(any(), any()) }
            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
        }
    }

    describe("EC11 — delivery id 헤더 부재는 dedup 을 건너뛰고 처리를 진행한다(fail-open)") {
        it("deliveryId 가 null 이어도 정상 발화한다") {
            val webhook = webhookOf()
            val rule = prMergedRule()
            every {
                automationRuleRepository.findEnabledByProjectAndTriggerType(PROJECT_KEY, TriggerType.PR_MERGED)
            } returns listOf(rule)

            service.handleInboundEvent(webhook, "pull_request", null, githubMergedPayload())

            verify(exactly = 0) { gitWebhookRepository.insertDelivery(any(), any(), any()) }
            verify(exactly = 1) { executionEnqueuer.enqueue(rule.id, TriggerType.PR_MERGED, any()) }
        }
    }

    describe("EC13 — 이슈키 추출 0건이면 조용히 202 상당으로 종료한다") {
        it("Closes/Fixes/Resolves 언급이 없으면 룰 조회 없이 종료한다") {
            val webhook = webhookOf()

            service.handleInboundEvent(
                webhook,
                "pull_request",
                "delivery-13",
                githubMergedPayload(body = "그냥 버그를 고쳤습니다"),
            )

            verify(exactly = 0) { automationRuleRepository.findEnabledByProjectAndTriggerType(any(), any()) }
            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
        }
    }

    describe("EC14 — 활성 PR_MERGED 룰이 0건이면 종료한다") {
        it("dedup 은 통과했지만 매칭 룰이 없으면 enqueue 하지 않는다") {
            val webhook = webhookOf()
            every {
                automationRuleRepository.findEnabledByProjectAndTriggerType(PROJECT_KEY, TriggerType.PR_MERGED)
            } returns emptyList()

            service.handleInboundEvent(webhook, "pull_request", "delivery-14", githubMergedPayload())

            verify(exactly = 1) { gitWebhookRepository.insertDelivery(webhook.id, "delivery-14", fixedNow) }
            verify(exactly = 0) { executionEnqueuer.enqueue(any(), any(), any()) }
        }
    }
})
