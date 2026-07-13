// 프로젝트 관리자 게이트 하에 Slack 채널↔프로젝트 매핑 CRUD 를 오케스트레이션 (FR-SL-06 Task 6)

package com.bts.slack.application

import com.bts.shared.permission.SlackChannelMappingPermissionResolver
import com.bts.slack.domain.ChannelProjectMapping
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class SlackChannelMappingService(
    private val permissionResolver: SlackChannelMappingPermissionResolver,
    private val installRepository: SlackInstallRepository,
    private val mappingRepository: SlackChannelMappingRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun create(
        actorId: UUID,
        projectKey: String,
        channelId: String,
        channelName: String?,
        eventTypes: Set<String>,
    ): ChannelProjectMapping {
        if (!permissionResolver.hasManageChannelMapping(actorId, projectKey)) {
            throw SlackChannelMappingPermissionDeniedException()
        }
        val teamId = requireInstallation().teamId
        val now = clock.instant()
        val mapping =
            ChannelProjectMapping(
                id = UUID.randomUUID(),
                teamId = teamId,
                projectKey = projectKey,
                channelId = channelId,
                channelName = channelName,
                eventTypes = eventTypes,
                createdAt = now,
                updatedAt = now,
            )
        return try {
            mappingRepository.save(mapping)
        } catch (ex: DataIntegrityViolationException) {
            throw SlackChannelMappingConflictException().apply { initCause(ex) }
        }
    }

    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        projectKey: String,
    ): List<ChannelProjectMapping> {
        if (!permissionResolver.hasManageChannelMapping(actorId, projectKey)) {
            throw SlackChannelMappingPermissionDeniedException()
        }
        return mappingRepository.findByProjectKey(projectKey)
    }

    @Transactional
    fun update(
        actorId: UUID,
        id: UUID,
        channelId: String?,
        channelName: String?,
        eventTypes: Set<String>?,
    ): ChannelProjectMapping {
        val existing = mappingRepository.findById(id) ?: throw SlackChannelMappingNotFoundException()
        if (!permissionResolver.hasManageChannelMapping(actorId, existing.projectKey)) {
            throw SlackChannelMappingPermissionDeniedException()
        }
        val updated =
            existing.copy(
                channelId = channelId ?: existing.channelId,
                channelName = channelName ?: existing.channelName,
                eventTypes = eventTypes ?: existing.eventTypes,
                updatedAt = clock.instant(),
            )
        return try {
            mappingRepository.update(updated)
        } catch (ex: DataIntegrityViolationException) {
            throw SlackChannelMappingConflictException().apply { initCause(ex) }
        }
    }

    @Transactional
    fun delete(
        actorId: UUID,
        id: UUID,
    ) {
        val existing = mappingRepository.findById(id) ?: throw SlackChannelMappingNotFoundException()
        if (!permissionResolver.hasManageChannelMapping(actorId, existing.projectKey)) {
            throw SlackChannelMappingPermissionDeniedException()
        }
        mappingRepository.deleteById(existing.id)
    }

    private fun requireInstallation(): SlackInstallationView =
        installRepository.findCurrentInstallation() ?: throw WorkspaceNotInstalledException()
}
