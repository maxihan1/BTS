// JooqOutboundWebhookRepository 통합테스트 — Testcontainers + Flyway V603 + OCC + 소프트삭제 + event_filter 배열 왕복 (FR-API-03 PR2)

package com.bts.search.webhook.persistence

import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import com.bts.search.webhook.domain.OutboundWebhook
import com.bts.search.webhook.domain.WebhookEventCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * JooqOutboundWebhookRepository 통합테스트.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-alpine(pgmq 포함) 위에서
 * Flyway V600~V603 마이그레이션 체인 적용 후 Repository 동작을 검증한다.
 * `SearchPersistenceTestBase` (savedfilter 패키지 소유, 같은 모듈/BC 내 공용 JVM-singleton 컨테이너
 * 기반 클래스)를 그대로 재사용한다 — 별도 컨테이너 기동 없이 V603 까지 포함된 마이그레이션 체인을 공유한다.
 *
 * 검증 범위 (plan Task 5).
 * - save → findById 라운드트립 (id/타임스탬프 채워짐, version=0, event_filter 배열 왕복)
 * - findById — 존재하지 않는 id / 소프트삭제된 id 는 null
 * - listAll — 소프트삭제 제외 + 페이지네이션(created_at DESC, id ASC 안정 정렬)
 * - update — OCC 성공 시 version+1, 실패(잘못된 version) 시 null
 * - softDelete — 성공 시 true + 이후 findById=null, 존재하지 않거나 이미 삭제된 id 는 false(멱등)
 */
class JooqOutboundWebhookRepositoryTest : SearchPersistenceTestBase() {
    private val repo get() = JooqOutboundWebhookRepository(dsl)

    @AfterEach
    fun cleanOutboundWebhooks() {
        dsl.execute("DELETE FROM webhook_deliveries")
        dsl.execute("DELETE FROM outbound_webhooks")
    }

    // 도메인 OutboundWebhook.create 와 동일하게 7개 필드를 그대로 받는다 — VO 분리보다
    // 명시적 시그니처가 명료하다(OutboundWebhook.kt REFACTOR 사유와 동일, task-3).
    @Suppress("LongParameterList")
    private fun buildWebhook(
        createdBy: UUID = UUID.randomUUID(),
        name: String = "테스트 웹훅",
        url: String = "https://example.com/hooks/atlas",
        eventFilter: List<String> = listOf(WebhookEventCatalog.ISSUE_CREATED),
        secretEncrypted: String? = null,
        projectKey: String? = null,
        enabled: Boolean = true,
    ): OutboundWebhook =
        OutboundWebhook.create(
            createdBy = createdBy,
            name = name,
            url = url,
            eventFilter = eventFilter,
            secretEncrypted = secretEncrypted,
            projectKey = projectKey,
            enabled = enabled,
        )

    // ── save → findById 라운드트립 ────────────────────────────────────────────

    @Test
    fun `save 후 findById 로 라운드트립 — id와 타임스탬프가 채워지고 version은 0, eventFilter 왕복`() {
        val webhook =
            buildWebhook(
                eventFilter = listOf(WebhookEventCatalog.ISSUE_CREATED, WebhookEventCatalog.ISSUE_TRANSITIONED),
                secretEncrypted = "cipher-text-abc",
                projectKey = "ATLAS",
            )

        val saved = repo.save(webhook)

        assertThat(saved.id).isNotNull()
        assertThat(saved.createdAt).isNotNull()
        assertThat(saved.updatedAt).isNotNull()
        assertThat(saved.version).isEqualTo(0L)
        assertThat(saved.name).isEqualTo(webhook.name)
        assertThat(saved.url).isEqualTo(webhook.url)
        assertThat(saved.secretEncrypted).isEqualTo("cipher-text-abc")
        assertThat(saved.eventFilter)
            .containsExactlyInAnyOrder(WebhookEventCatalog.ISSUE_CREATED, WebhookEventCatalog.ISSUE_TRANSITIONED)
        assertThat(saved.projectKey).isEqualTo("ATLAS")
        assertThat(saved.enabled).isTrue()
        assertThat(saved.createdBy).isEqualTo(webhook.createdBy)

        val found = repo.findById(saved.id!!)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(saved.id)
        assertThat(found.name).isEqualTo(saved.name)
        assertThat(found.eventFilter).containsExactlyInAnyOrderElementsOf(saved.eventFilter)
        assertThat(found.secretEncrypted).isEqualTo(saved.secretEncrypted)
        assertThat(found.version).isEqualTo(0L)
    }

    @Test
    fun `save — secretEncrypted와 projectKey 미설정 시 null로 저장된다`() {
        val webhook = buildWebhook(secretEncrypted = null, projectKey = null)

        val saved = repo.save(webhook)

        assertThat(saved.secretEncrypted).isNull()
        assertThat(saved.projectKey).isNull()
    }

    @Test
    fun `findById — 존재하지 않는 id 는 null`() {
        val result = repo.findById(UUID.randomUUID())
        assertThat(result).isNull()
    }

    @Test
    fun `findById — 소프트삭제된 id 는 null`() {
        val saved = repo.save(buildWebhook())
        repo.softDelete(saved.id!!)

        assertThat(repo.findById(saved.id!!)).isNull()
    }

    // ── listAll 페이지네이션 + 소프트삭제 제외 ──────────────────────────────────

    @Test
    fun `listAll — 소프트삭제된 구독은 목록에서 제외된다`() {
        val kept = repo.save(buildWebhook(name = "유지되는 웹훅"))
        val deleted = repo.save(buildWebhook(name = "삭제되는 웹훅"))
        repo.softDelete(deleted.id!!)

        val result = repo.listAll(page = 0, size = 20)

        assertThat(result.map { it.id }).containsExactly(kept.id)
    }

    @Test
    fun `listAll — page와 size로 전체 목록을 중복 누락 없이 분할한다`() {
        val saved = (1..3).map { repo.save(buildWebhook(name = "웹훅 $it")) }

        val page0 = repo.listAll(page = 0, size = 2)
        val page1 = repo.listAll(page = 1, size = 2)

        assertThat(page0).hasSize(2)
        assertThat(page1).hasSize(1)
        assertThat(page0.map { it.id } + page1.map { it.id })
            .containsExactlyInAnyOrderElementsOf(saved.map { it.id })
    }

    // ── update OCC ────────────────────────────────────────────────────────────

    @Test
    fun `update — version+1 및 updated_at 갱신, eventFilter 교체 왕복`() {
        val saved = repo.save(buildWebhook())
        val updatedDomain =
            saved.applyUpdate(
                name = "수정된 이름",
                url = "https://example.com/hooks/updated",
                eventFilter = listOf(WebhookEventCatalog.ISSUE_TRANSITIONED),
                enabled = false,
            )

        val updated = repo.update(updatedDomain)

        assertThat(updated).isNotNull()
        assertThat(updated!!.version).isEqualTo(1L)
        assertThat(updated.name).isEqualTo("수정된 이름")
        assertThat(updated.url).isEqualTo("https://example.com/hooks/updated")
        assertThat(updated.eventFilter).containsExactly(WebhookEventCatalog.ISSUE_TRANSITIONED)
        assertThat(updated.enabled).isFalse()
        assertThat(updated.updatedAt).isNotNull()
    }

    @Test
    fun `update OCC — stale version 으로 update 시 null 반환`() {
        val saved = repo.save(buildWebhook())
        // version=0 으로 1차 업데이트 → DB version 이 1 이 됨
        repo.update(saved.applyUpdate(name = "1차 수정", url = saved.url, eventFilter = saved.eventFilter))

        // saved 는 여전히 version=0 → WHERE version=0 은 0행 → null
        val result =
            repo.update(saved.applyUpdate(name = "stale 수정", url = saved.url, eventFilter = saved.eventFilter))
        assertThat(result).isNull()
    }

    @Test
    fun `update — 소프트삭제된 구독은 갱신되지 않고 null 반환`() {
        val saved = repo.save(buildWebhook())
        repo.softDelete(saved.id!!)

        val result =
            repo.update(saved.applyUpdate(name = "삭제후 수정", url = saved.url, eventFilter = saved.eventFilter))

        assertThat(result).isNull()
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    @Test
    fun `softDelete 후 findById 는 null`() {
        val saved = repo.save(buildWebhook())

        val deleted = repo.softDelete(saved.id!!)

        assertThat(deleted).isTrue()
        assertThat(repo.findById(saved.id!!)).isNull()
    }

    @Test
    fun `softDelete — 존재하지 않는 id 는 false`() {
        val result = repo.softDelete(UUID.randomUUID())
        assertThat(result).isFalse()
    }

    @Test
    fun `softDelete — 이미 소프트삭제된 id 재삭제 시 false(멱등)`() {
        val saved = repo.save(buildWebhook())
        repo.softDelete(saved.id!!)

        val result = repo.softDelete(saved.id!!)

        assertThat(result).isFalse()
    }
}
