// SlackChannelMappingService의 권한 게이트·team_id 해석·CRUD 위임·중복/미존재 예외 변환을 검증하는 단위 테스트

package com.bts.slack.application

import com.bts.shared.permission.SlackChannelMappingPermissionResolver
import com.bts.slack.domain.ChannelProjectMapping
import com.bts.slack.domain.SlackChannelEventType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [SlackChannelMappingService] 단위 테스트 (FR-SL-06 Task 6).
 *
 * 오케스트레이션 경계만 검증한다 — 3개 협력자([SlackChannelMappingPermissionResolver]·
 * [SlackInstallRepository]·[SlackChannelMappingRepository])를 모두 mockk 로 대체하고, 시각은
 * [Clock.fixed] 로 고정해 결정적으로 검증한다(교훈 — 시각 의존 로직은 Clock 주입).
 *
 * 검증 축.
 * - 각 연산 진입 시 권한 게이트([SlackChannelMappingPermissionResolver.hasManageChannelMapping])를
 *   먼저 확인하고 거부(false)면 리소스/저장소를 건드리지 않고 [SlackChannelMappingPermissionDeniedException]
 *   으로 조기 거부(fail-closed).
 * - create 는 team_id 를 유일 설치([SlackInstallRepository.findCurrentInstallation])에서 해석하고,
 *   설치 0건이면 [WorkspaceNotInstalledException].
 * - eventTypes 검증은 도메인([ChannelProjectMapping]) 생성이 담당 — 미지 wireValue 는 IllegalArgumentException.
 * - 저장소가 UNIQUE 위반([DataIntegrityViolationException])을 던지면 [SlackChannelMappingConflictException] 으로 번역.
 * - update/delete 는 대상 매핑을 먼저 조회해 projectKey 로 게이트하고, 미존재는 [SlackChannelMappingNotFoundException].
 * - 신규 예외 3종의 message 에 projectKey·channelId·teamId 같은 가변값이 담기지 않음(§1.1.2 누출 방지).
 */
class SlackChannelMappingServiceTest {
    private val permissionResolver = mockk<SlackChannelMappingPermissionResolver>()
    private val installRepository = mockk<SlackInstallRepository>()
    private val mappingRepository = mockk<SlackChannelMappingRepository>()

    private val service =
        SlackChannelMappingService(
            permissionResolver = permissionResolver,
            installRepository = installRepository,
            mappingRepository = mappingRepository,
            clock = FIXED_CLOCK,
        )

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    fun `create - 관리 권한이 있으면 team_id를 유일 설치에서 해석해 매핑을 저장하고 반환한다`() {
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns true
        every { installRepository.findCurrentInstallation() } returns installationView()
        val saved = slot<ChannelProjectMapping>()
        every { mappingRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.create(ACTOR_ID, PROJECT_KEY, CHANNEL_ID, CHANNEL_NAME, EVENT_TYPES)

        assertThat(result.teamId).isEqualTo(TEAM_ID)
        assertThat(saved.captured.teamId).isEqualTo(TEAM_ID)
        assertThat(saved.captured.projectKey).isEqualTo(PROJECT_KEY)
        assertThat(saved.captured.channelId).isEqualTo(CHANNEL_ID)
        assertThat(saved.captured.channelName).isEqualTo(CHANNEL_NAME)
        assertThat(saved.captured.eventTypes).containsExactlyInAnyOrderElementsOf(EVENT_TYPES)
        assertThat(saved.captured.createdAt).isEqualTo(FIXED_INSTANT)
        assertThat(saved.captured.updatedAt).isEqualTo(FIXED_INSTANT)
        verify { installRepository.findCurrentInstallation() }
    }

    @Test
    fun `create - 관리 권한이 없으면 PermissionDenied를 던지고 설치 조회·저장을 하지 않는다`() {
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns false

        assertThatThrownBy { service.create(ACTOR_ID, PROJECT_KEY, CHANNEL_ID, CHANNEL_NAME, EVENT_TYPES) }
            .isInstanceOf(SlackChannelMappingPermissionDeniedException::class.java)

        verify(exactly = 0) { installRepository.findCurrentInstallation() }
        verify(exactly = 0) { mappingRepository.save(any()) }
    }

    @Test
    fun `create - 설치가 0건이면 WorkspaceNotInstalledException을 던지고 저장하지 않는다`() {
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns true
        every { installRepository.findCurrentInstallation() } returns null

        assertThatThrownBy { service.create(ACTOR_ID, PROJECT_KEY, CHANNEL_ID, CHANNEL_NAME, EVENT_TYPES) }
            .isInstanceOf(WorkspaceNotInstalledException::class.java)

        verify(exactly = 0) { mappingRepository.save(any()) }
    }

    @Test
    fun `create - 미지 eventType이 섞이면 도메인 검증이 IllegalArgumentException을 던지고 저장하지 않는다`() {
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns true
        every { installRepository.findCurrentInstallation() } returns installationView()

        val withUnknown = setOf(SlackChannelEventType.ISSUE_CREATED, "bogus.event")
        assertThatThrownBy {
            service.create(ACTOR_ID, PROJECT_KEY, CHANNEL_ID, CHANNEL_NAME, withUnknown)
        }.isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { mappingRepository.save(any()) }
    }

    @Test
    fun `create - 저장소가 UNIQUE 위반을 던지면 Conflict로 번역한다`() {
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns true
        every { installRepository.findCurrentInstallation() } returns installationView()
        every { mappingRepository.save(any()) } throws
            DataIntegrityViolationException("duplicate key value violates unique constraint")

        assertThatThrownBy { service.create(ACTOR_ID, PROJECT_KEY, CHANNEL_ID, CHANNEL_NAME, EVENT_TYPES) }
            .isInstanceOf(SlackChannelMappingConflictException::class.java)
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    fun `list - 관리 권한이 있으면 projectKey의 매핑 목록을 반환한다`() {
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns true
        every { mappingRepository.findByProjectKey(PROJECT_KEY) } returns listOf(existingMapping())

        val result = service.list(ACTOR_ID, PROJECT_KEY)

        assertThat(result).hasSize(1)
        assertThat(result.first().id).isEqualTo(MAPPING_ID)
    }

    @Test
    fun `list - 관리 권한이 없으면 PermissionDenied를 던지고 조회하지 않는다`() {
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns false

        assertThatThrownBy { service.list(ACTOR_ID, PROJECT_KEY) }
            .isInstanceOf(SlackChannelMappingPermissionDeniedException::class.java)

        verify(exactly = 0) { mappingRepository.findByProjectKey(any()) }
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    fun `update - 대상 매핑의 projectKey로 게이트 통과 시 eventTypes와 updatedAt을 갱신 위임한다`() {
        every { mappingRepository.findById(MAPPING_ID) } returns existingMapping()
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns true
        val updated = slot<ChannelProjectMapping>()
        every { mappingRepository.update(capture(updated)) } answers { updated.captured }

        val newEventTypes = setOf(SlackChannelEventType.ISSUE_TRANSITIONED)
        service.update(ACTOR_ID, MAPPING_ID, channelId = null, channelName = null, eventTypes = newEventTypes)

        assertThat(updated.captured.id).isEqualTo(MAPPING_ID)
        assertThat(updated.captured.projectKey).isEqualTo(PROJECT_KEY)
        assertThat(updated.captured.eventTypes).containsExactlyInAnyOrderElementsOf(newEventTypes)
        assertThat(updated.captured.updatedAt).isEqualTo(FIXED_INSTANT)
        assertThat(updated.captured.createdAt).isEqualTo(CREATED_AT)
    }

    @Test
    fun `update - 대상 매핑의 관리 권한이 없으면 PermissionDenied를 던지고 갱신하지 않는다`() {
        every { mappingRepository.findById(MAPPING_ID) } returns existingMapping()
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns false

        assertThatThrownBy { service.update(ACTOR_ID, MAPPING_ID, null, null, EVENT_TYPES) }
            .isInstanceOf(SlackChannelMappingPermissionDeniedException::class.java)

        verify(exactly = 0) { mappingRepository.update(any()) }
    }

    @Test
    fun `update - 대상 id가 없으면 NotFound를 던지고 권한 조회·갱신을 하지 않는다`() {
        every { mappingRepository.findById(MAPPING_ID) } returns null

        assertThatThrownBy { service.update(ACTOR_ID, MAPPING_ID, null, null, EVENT_TYPES) }
            .isInstanceOf(SlackChannelMappingNotFoundException::class.java)

        verify(exactly = 0) { permissionResolver.hasManageChannelMapping(any(), any()) }
        verify(exactly = 0) { mappingRepository.update(any()) }
    }

    @Test
    fun `update - 미지 eventType이 섞이면 도메인 재검증이 IllegalArgumentException을 던지고 갱신하지 않는다`() {
        every { mappingRepository.findById(MAPPING_ID) } returns existingMapping()
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns true

        assertThatThrownBy {
            service.update(ACTOR_ID, MAPPING_ID, null, null, setOf("bogus.event"))
        }.isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { mappingRepository.update(any()) }
    }

    @Test
    fun `update - 갱신이 UNIQUE 위반을 던지면 Conflict로 번역한다`() {
        every { mappingRepository.findById(MAPPING_ID) } returns existingMapping()
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns true
        every { mappingRepository.update(any()) } throws
            DataIntegrityViolationException("duplicate key value violates unique constraint")

        assertThatThrownBy {
            service.update(ACTOR_ID, MAPPING_ID, channelId = "C999", channelName = null, eventTypes = null)
        }.isInstanceOf(SlackChannelMappingConflictException::class.java)
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    fun `delete - 대상 매핑의 관리 권한이 있으면 삭제를 위임한다`() {
        every { mappingRepository.findById(MAPPING_ID) } returns existingMapping()
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns true
        every { mappingRepository.deleteById(MAPPING_ID) } returns true

        service.delete(ACTOR_ID, MAPPING_ID)

        verify { mappingRepository.deleteById(MAPPING_ID) }
    }

    @Test
    fun `delete - 대상 매핑의 관리 권한이 없으면 PermissionDenied를 던지고 삭제하지 않는다`() {
        every { mappingRepository.findById(MAPPING_ID) } returns existingMapping()
        every { permissionResolver.hasManageChannelMapping(ACTOR_ID, PROJECT_KEY) } returns false

        assertThatThrownBy { service.delete(ACTOR_ID, MAPPING_ID) }
            .isInstanceOf(SlackChannelMappingPermissionDeniedException::class.java)

        verify(exactly = 0) { mappingRepository.deleteById(any()) }
    }

    @Test
    fun `delete - 대상 id가 없으면 NotFound를 던지고 권한 조회·삭제를 하지 않는다`() {
        every { mappingRepository.findById(MAPPING_ID) } returns null

        assertThatThrownBy { service.delete(ACTOR_ID, MAPPING_ID) }
            .isInstanceOf(SlackChannelMappingNotFoundException::class.java)

        verify(exactly = 0) { permissionResolver.hasManageChannelMapping(any(), any()) }
        verify(exactly = 0) { mappingRepository.deleteById(any()) }
    }

    // ── 예외 message 가변값 미노출 ────────────────────────────────────────────────

    @Test
    fun `신규 예외 3종의 message에는 projectKey·channelId·teamId 같은 가변값이 담기지 않는다`() {
        val exceptions =
            listOf(
                SlackChannelMappingPermissionDeniedException(),
                SlackChannelMappingConflictException(),
                SlackChannelMappingNotFoundException(),
            )

        exceptions.forEach { exception ->
            val message = requireNotNull(exception.message)
            assertThat(message).doesNotContain(PROJECT_KEY)
            assertThat(message).doesNotContain(CHANNEL_ID)
            assertThat(message).doesNotContain(TEAM_ID)
        }
    }

    // ── fixture builders ──────────────────────────────────────────────────────

    private fun installationView() =
        SlackInstallationView(
            teamId = TEAM_ID,
            teamName = "Acme Workspace",
            botUserId = "U0BOT",
            installedAt = FIXED_INSTANT,
            updatedAt = FIXED_INSTANT,
            installedBy = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        )

    private fun existingMapping() =
        ChannelProjectMapping(
            id = MAPPING_ID,
            teamId = TEAM_ID,
            projectKey = PROJECT_KEY,
            channelId = CHANNEL_ID,
            channelName = CHANNEL_NAME,
            eventTypes = setOf(SlackChannelEventType.ISSUE_CREATED),
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
        )

    private companion object {
        val ACTOR_ID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val MAPPING_ID: UUID = UUID.fromString("99999999-8888-7777-6666-555555555555")
        const val PROJECT_KEY = "PROJ"
        const val CHANNEL_ID = "C123CHAN"
        const val CHANNEL_NAME = "#proj-feed"
        const val TEAM_ID = "T123WS"
        val EVENT_TYPES: Set<String> =
            setOf(SlackChannelEventType.ISSUE_CREATED, SlackChannelEventType.ISSUE_TRANSITIONED)
        val FIXED_INSTANT: Instant = Instant.parse("2026-07-13T00:00:00Z")
        val CREATED_AT: Instant = Instant.parse("2026-07-01T00:00:00Z")
        val FIXED_CLOCK: Clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC)
    }
}
