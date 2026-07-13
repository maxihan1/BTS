// 프로젝트 관리자 게이트 하에 Slack 채널↔프로젝트 매핑 CRUD 를 오케스트레이션 (FR-SL-06 Task 6)

package com.bts.slack.application

import com.bts.shared.permission.SlackChannelMappingPermissionResolver
import com.bts.slack.domain.ChannelProjectMapping
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * Slack 채널↔프로젝트 매핑 CRUD 오케스트레이션 (FR-SL-06 Task 6).
 *
 * `/api/v1/slack/channel-mappings` 컨트롤러(Task 7)가 이 서비스에 위임한다. 프로젝트 관리자만
 * 자신의 프로젝트에 대한 채널 매핑을 생성/조회/수정/삭제할 수 있다.
 *
 * ## fail-closed 권한 게이트 (모든 연산 진입 시 선확인)
 * 각 연산은 저장소를 건드리기 전에 [SlackChannelMappingPermissionResolver.hasManageChannelMapping]
 * 로 행위자의 관리 권한을 먼저 확인하고, 불명/거부는 모두 [SlackChannelMappingPermissionDeniedException]
 * (403)으로 수렴시킨다(교훈 — fail-open 금지: 불명은 거부). [create]/[list] 는 인자로 받은 projectKey 로
 * 즉시 게이트한다. [update]/[delete] 는 대상 매핑의 projectKey 로 게이트해야 하므로 [requireMapping]
 * 조회가 권한 확인보다 **먼저** 온다 — 단, 대상 매핑 자체가 없으면 권한을 묻기 전에 [SlackChannelMappingNotFoundException]
 * (404)으로 끝낸다. 행위자 식별(actorId)은 이미 상위(컨트롤러 JWT 인증)에서 추출되어 넘어오므로,
 * 이 순서는 "인증(추출)은 리소스 조회보다 먼저"(교훈 auth-extraction-before-resource-lookup) 원칙과
 * 상충하지 않는다 — 여기서 선행하는 것은 인가(권한 판정)에 필요한 대상 projectKey 조회일 뿐이다.
 *
 * ## team_id 해석 (단일 설치 가정)
 * [create] 는 새 매핑에 찍을 `team_id` 를 유일 워크스페이스 설치([SlackInstallRepository.findCurrentInstallation])
 * 에서 해석한다. 설치가 0건이면 [WorkspaceNotInstalledException](409, SlackConnectionExceptions 재사용)으로
 * 거부한다(EC12). [update]/[delete] 는 기존 매핑의 team_id 를 그대로 유지하므로 설치를 다시 조회하지 않는다.
 *
 * ## eventTypes 검증 위임
 * 이벤트 필터 유효성(빈 집합 금지·미지 wireValue 금지, EC1)은 [ChannelProjectMapping] 생성(및 [update] 의
 * `copy` 재생성)이 `init` 블록에서 담당한다 — 위반 시 `IllegalArgumentException` 이 전파되어 상위 웹 레이어가
 * 400 으로 매핑한다. 서비스는 검증 로직을 중복하지 않는다.
 *
 * ## UNIQUE 위반 → 409 번역
 * 저장소가 `UNIQUE(team_id, project_key, channel_id)` 위반([DataIntegrityViolationException])을 던지면
 * [persistTranslatingConflict] 가 [SlackChannelMappingConflictException](409)으로 번역한다 — 의도된
 * 거부이므로 미분류 500 이 되지 않게 한다(EC2). 예외를 삼키지 않고 타입만 바꿔 다시 던지며 원인을 로깅한다.
 *
 * ## @Transactional 경계 (DATA.md §6)
 * 쓰기 연산([create]/[update]/[delete])은 `@Transactional`, 조회([list])는 `@Transactional(readOnly = true)`.
 * 단일 BC(slack-integration) 트랜잭션이며 cross-BC 직접 호출이 없다(권한은 shared-kernel 포트 경계로 위임).
 *
 * @param permissionResolver 프로젝트 관리자 권한 판정 cross-BC 포트(prod=identity-access, non-prod=stub).
 * @param installRepository `slack_installs` 조회 포트 — [create] 의 team_id 해석에 사용.
 * @param mappingRepository `slack_channel_project_map` 영속화 포트.
 * @param clock 매핑 `createdAt`/`updatedAt` 산정용 시계 — slack 모듈에 Clock 빈이 없으므로 기본값
 *   [Clock.systemUTC] 를 두고, 테스트는 [Clock.fixed] 로 고정한다(교훈 — 시각 의존 로직은 Clock 주입).
 */
@Service
class SlackChannelMappingService(
    private val permissionResolver: SlackChannelMappingPermissionResolver,
    private val installRepository: SlackInstallRepository,
    private val mappingRepository: SlackChannelMappingRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 채널↔프로젝트 매핑을 생성한다.
     *
     * @param actorId 요청 행위자 UUID(컨트롤러 JWT 에서 추출).
     * @param projectKey 매핑 대상 프로젝트 키.
     * @param channelId 게시 대상 Slack 채널 id(`C…`).
     * @param channelName 채널 표시명(nullable, UI 편의용).
     * @param eventTypes 이 매핑으로 게시할 이벤트 유형(wire 문자열) 집합(≥1, 카탈로그 값만).
     * @return 저장된 매핑(생성 id·시각 포함).
     * @throws SlackChannelMappingPermissionDeniedException 행위자가 관리 권한이 없는 경우(403).
     * @throws WorkspaceNotInstalledException Slack 워크스페이스 설치가 0건인 경우(409, EC12).
     * @throws IllegalArgumentException eventTypes 가 비었거나 미지 wireValue 를 포함하는 경우(상위 400, EC1).
     * @throws SlackChannelMappingConflictException 같은 team+project+channel 매핑이 이미 존재하는 경우(409, EC2).
     */
    @Transactional
    fun create(
        actorId: UUID,
        projectKey: String,
        channelId: String,
        channelName: String?,
        eventTypes: Set<String>,
    ): ChannelProjectMapping {
        requireManagePermission(actorId, projectKey)
        val now = clock.instant()
        val mapping =
            ChannelProjectMapping(
                id = UUID.randomUUID(),
                teamId = requireInstallation().teamId,
                projectKey = projectKey,
                channelId = channelId,
                channelName = channelName,
                eventTypes = eventTypes,
                createdAt = now,
                updatedAt = now,
            )
        return persistTranslatingConflict { mappingRepository.save(mapping) }
    }

    /**
     * [projectKey] 에 속한 채널 매핑을 전부 조회한다(다대다 — 한 프로젝트가 여러 채널을 가질 수 있다).
     *
     * @throws SlackChannelMappingPermissionDeniedException 행위자가 관리 권한이 없는 경우(403).
     */
    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        projectKey: String,
    ): List<ChannelProjectMapping> {
        requireManagePermission(actorId, projectKey)
        return mappingRepository.findByProjectKey(projectKey)
    }

    /**
     * 기존 매핑의 채널/이벤트 필터를 부분 갱신한다(PATCH 의미 — null 인자는 "미변경").
     *
     * @param id 갱신 대상 매핑 id.
     * @param channelId 새 채널 id(null 이면 기존 유지).
     * @param channelName 새 채널 표시명(null 이면 기존 유지).
     * @param eventTypes 새 이벤트 필터(null 이면 기존 유지, 제공 시 도메인 재검증).
     * @throws SlackChannelMappingNotFoundException 대상 id 가 없는 경우(404).
     * @throws SlackChannelMappingPermissionDeniedException 대상 프로젝트의 관리 권한이 없는 경우(403).
     * @throws IllegalArgumentException eventTypes 가 비었거나 미지 wireValue 를 포함하는 경우(상위 400, EC1).
     * @throws SlackChannelMappingConflictException 갱신 결과가 UNIQUE 를 위반하는 경우(409, EC2).
     */
    @Transactional
    fun update(
        actorId: UUID,
        id: UUID,
        channelId: String?,
        channelName: String?,
        eventTypes: Set<String>?,
    ): ChannelProjectMapping {
        val existing = requireMapping(id)
        requireManagePermission(actorId, existing.projectKey)
        val updated =
            existing.copy(
                channelId = channelId ?: existing.channelId,
                channelName = channelName ?: existing.channelName,
                eventTypes = eventTypes ?: existing.eventTypes,
                updatedAt = clock.instant(),
            )
        return persistTranslatingConflict { mappingRepository.update(updated) }
    }

    /**
     * 매핑을 삭제한다(하드 삭제 — 설정성 행). 삭제 후 해당 채널로의 이후 게시는 중단된다(과거 게시는 보존).
     *
     * @param id 삭제 대상 매핑 id.
     * @throws SlackChannelMappingNotFoundException 대상 id 가 없는 경우(404).
     * @throws SlackChannelMappingPermissionDeniedException 대상 프로젝트의 관리 권한이 없는 경우(403).
     */
    @Transactional
    fun delete(
        actorId: UUID,
        id: UUID,
    ) {
        val existing = requireMapping(id)
        requireManagePermission(actorId, existing.projectKey)
        mappingRepository.deleteById(existing.id)
    }

    /**
     * 행위자가 대상 프로젝트의 채널 매핑 관리 권한을 보유하는지 확인한다(fail-closed).
     *
     * [SlackChannelMappingPermissionResolver.hasManageChannelMapping] 이 `false`(비관리자·미해석 키·
     * 비멤버 포함)면 [SlackChannelMappingPermissionDeniedException] 으로 거부한다 — 네 연산이 공유하는
     * 유일한 권한 진입점이다.
     */
    private fun requireManagePermission(
        actorId: UUID,
        projectKey: String,
    ) {
        if (!permissionResolver.hasManageChannelMapping(actorId, projectKey)) {
            throw SlackChannelMappingPermissionDeniedException()
        }
    }

    /** [id] 로 매핑을 조회한다. 없으면 [SlackChannelMappingNotFoundException](404). */
    private fun requireMapping(id: UUID): ChannelProjectMapping =
        mappingRepository.findById(id) ?: throw SlackChannelMappingNotFoundException()

    /** 현재 워크스페이스 설치를 조회한다. 설치가 없으면 [WorkspaceNotInstalledException](409, EC12). */
    private fun requireInstallation(): SlackInstallationView =
        installRepository.findCurrentInstallation() ?: throw WorkspaceNotInstalledException()

    /**
     * 저장/갱신을 실행하되 UNIQUE 위반([DataIntegrityViolationException])을
     * [SlackChannelMappingConflictException](409)으로 번역한다([create]/[update] 공유).
     *
     * 예외를 삼키지 않고 타입만 바꿔 다시 던지며, 원인은 원본 예외의 message(제약명 등 비민감)만 로깅한다 —
     * 응답에는 가변값 없는 고정 문구만 나간다(§1.1.2).
     */
    private fun persistTranslatingConflict(persist: () -> ChannelProjectMapping): ChannelProjectMapping =
        try {
            persist()
        } catch (ex: DataIntegrityViolationException) {
            log.info("Slack channel mapping rejected by unique constraint (team+project+channel)")
            throw SlackChannelMappingConflictException().apply { initCause(ex) }
        }
}
