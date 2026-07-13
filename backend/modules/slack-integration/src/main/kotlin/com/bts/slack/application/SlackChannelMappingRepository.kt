// slack_channel_project_map 영속화 포트 — 채널↔프로젝트 매핑 CRUD (FR-SL-06 Task 4)

package com.bts.slack.application

import com.bts.slack.domain.ChannelProjectMapping
import java.util.UUID

/**
 * `slack_channel_project_map` 영속화 포트.
 *
 * 구현체는 [com.bts.slack.persistence.JdbcSlackChannelMappingRepository] — JdbcTemplate 기반
 * ([SlackUserMappingRepository]/[SlackInstallRepository] 동형, 단일 테이블 CRUD).
 *
 * UNIQUE(team_id, project_key, channel_id) 위반 시 구현체는 예외를 삼키지 않고 그대로 전파한다
 * (`DataIntegrityViolationException`/`DuplicateKeyException`). 409 로의 변환은 상위 서비스 책임이다.
 */
interface SlackChannelMappingRepository {
    /**
     * 새 채널↔프로젝트 매핑을 저장한다.
     *
     * [mapping] 의 모든 필드([ChannelProjectMapping.id] 포함)는 호출자(서비스)가 이미 채워 넘긴다 —
     * 이 포트는 값을 그대로 영속화할 뿐 id/시각을 새로 생성하지 않는다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException UNIQUE(team_id, project_key, channel_id) 위반 시.
     */
    fun save(mapping: ChannelProjectMapping): ChannelProjectMapping

    /**
     * [projectKey] 에 속한 채널 매핑을 전부 조회한다(다대다 — 한 프로젝트가 여러 채널을 가질 수 있다).
     * 매핑이 없으면 빈 리스트.
     */
    fun findByProjectKey(projectKey: String): List<ChannelProjectMapping>

    /** [id] 로 매핑을 단건 조회한다. 존재하지 않으면 null. */
    fun findById(id: UUID): ChannelProjectMapping?

    /**
     * 채널([ChannelProjectMapping.channelId]/[ChannelProjectMapping.channelName])과
     * [ChannelProjectMapping.eventTypes]/[ChannelProjectMapping.updatedAt] 을 갱신한다.
     * [ChannelProjectMapping.id]/[ChannelProjectMapping.teamId]/[ChannelProjectMapping.projectKey]/
     * [ChannelProjectMapping.createdAt] 은 변경 대상이 아니다(호출자가 값을 유지해 넘겨야 한다).
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 갱신 결과가 UNIQUE 제약을 위반하면.
     */
    fun update(mapping: ChannelProjectMapping): ChannelProjectMapping

    /** [id] 로 매핑을 삭제한다(하드 삭제 — 설정성 행, V704 마이그레이션 주석 정합). 삭제된 행이 있으면 true. */
    fun deleteById(id: UUID): Boolean
}
