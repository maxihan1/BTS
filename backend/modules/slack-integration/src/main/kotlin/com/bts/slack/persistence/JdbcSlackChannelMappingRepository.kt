// SlackChannelMappingRepository JdbcTemplate 구현체 — slack_channel_project_map CRUD + text[] 처리 (FR-SL-06 Task 4)

package com.bts.slack.persistence

import com.bts.slack.application.SlackChannelMappingRepository
import com.bts.slack.domain.ChannelProjectMapping
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * [SlackChannelMappingRepository] JdbcTemplate 구현체 (FR-SL-06 Task 4).
 *
 * **SQL 인젝션 방어:** 모든 파라미터를 `?` 위치 바인딩으로 처리하며, SQL 문자열 결합은 하지 않는다
 * (DATA.md §5). `SlackDeliveryWorker` 와 동일하게 `NamedParameterJdbcTemplate` 대신 순정
 * [JdbcTemplate] 을 쓴다 — `event_types` 컬럼(`text[]`)을 바인딩하려면 `Connection.createArrayOf`
 * 로 `java.sql.Array` 를 만들어야 하는데, 이 작업은 `Connection` 에 직접 접근해야 한다.
 *
 * **save/update 는 값 그대로 영속화** — `id`/`createdAt`/`updatedAt` 을 DB 가 생성하지 않고 호출자
 * (서비스)가 채운 [ChannelProjectMapping] 값을 그대로 쓴다. 그래서 `save`/`update` 는 인자로 받은
 * [ChannelProjectMapping] 을 그대로 반환해도 안전하다(재조회 불필요).
 */
@Repository
class JdbcSlackChannelMappingRepository(
    private val jdbcTemplate: JdbcTemplate,
) : SlackChannelMappingRepository {
    @Suppress("MagicNumber") // 1-based JDBC PreparedStatement 파라미터 인덱스(SQL_INSERT 컬럼 순서 그대로)
    @Transactional
    override fun save(mapping: ChannelProjectMapping): ChannelProjectMapping {
        jdbcTemplate.update { connection ->
            connection.prepareStatement(SQL_INSERT).apply {
                setObject(1, mapping.id)
                setString(2, mapping.teamId)
                setString(3, mapping.projectKey)
                setString(4, mapping.channelId)
                setString(5, mapping.channelName)
                setArray(6, connection.createArrayOf(TEXT_ARRAY_TYPE, mapping.eventTypes.toTypedArray()))
                setTimestamp(7, Timestamp.from(mapping.createdAt))
                setTimestamp(8, Timestamp.from(mapping.updatedAt))
            }
        }
        return mapping
    }

    /** 조회 전용 트랜잭션 — 쓰기 잠금을 잡지 않는다 (DATA.md §6 읽기 전용 규칙). */
    @Transactional(readOnly = true)
    override fun findByProjectKey(projectKey: String): List<ChannelProjectMapping> =
        jdbcTemplate.query(SQL_FIND_BY_PROJECT_KEY, ChannelProjectMappingRowMapper, projectKey)

    @Transactional(readOnly = true)
    override fun findById(id: UUID): ChannelProjectMapping? =
        jdbcTemplate.query(SQL_FIND_BY_ID, ChannelProjectMappingRowMapper, id).firstOrNull()

    @Suppress("MagicNumber") // 1-based JDBC PreparedStatement 파라미터 인덱스(SQL_UPDATE SET 순서 그대로)
    @Transactional
    override fun update(mapping: ChannelProjectMapping): ChannelProjectMapping {
        jdbcTemplate.update { connection ->
            connection.prepareStatement(SQL_UPDATE).apply {
                setString(1, mapping.channelId)
                setString(2, mapping.channelName)
                setArray(3, connection.createArrayOf(TEXT_ARRAY_TYPE, mapping.eventTypes.toTypedArray()))
                setTimestamp(4, Timestamp.from(mapping.updatedAt))
                setObject(5, mapping.id)
            }
        }
        return mapping
    }

    @Transactional
    override fun deleteById(id: UUID): Boolean = jdbcTemplate.update(SQL_DELETE, id) > 0

    private companion object {
        /** text[] 컬럼 바인딩용 PostgreSQL 배열 요소 타입명. */
        const val TEXT_ARRAY_TYPE = "text"

        /** 신규 매핑 삽입 — 모든 컬럼을 [ChannelProjectMapping] 값 그대로 명시 삽입한다(DB 기본값 미사용). */
        const val SQL_INSERT = """
            INSERT INTO slack_channel_project_map
                (id, team_id, project_key, channel_id, channel_name, event_types, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """

        /** [SlackChannelMappingRepository.findByProjectKey] — projectKey 매칭 매핑 전체 조회. */
        const val SQL_FIND_BY_PROJECT_KEY = """
            SELECT id, team_id, project_key, channel_id, channel_name, event_types, created_at, updated_at
            FROM slack_channel_project_map
            WHERE project_key = ?
        """

        /** id 단건 조회. */
        const val SQL_FIND_BY_ID = """
            SELECT id, team_id, project_key, channel_id, channel_name, event_types, created_at, updated_at
            FROM slack_channel_project_map
            WHERE id = ?
        """

        /** 채널/이벤트 필터 갱신 — id/team_id/project_key/created_at 은 변경하지 않는다. */
        const val SQL_UPDATE = """
            UPDATE slack_channel_project_map
            SET channel_id = ?, channel_name = ?, event_types = ?, updated_at = ?
            WHERE id = ?
        """

        /** 하드 삭제 — 설정성 행(V704 마이그레이션 주석 정합, 소프트 삭제 대상 아님). */
        const val SQL_DELETE = "DELETE FROM slack_channel_project_map WHERE id = ?"
    }
}

/**
 * `slack_channel_project_map` 한 행 → [ChannelProjectMapping] VO 매핑([JdbcSlackInstallRepository]의
 * `SlackInstallRowMapper` 동형).
 *
 * `event_types` 는 `text[]` 컬럼이라 [ResultSet.getArray] 로 [java.sql.Array] 를 얻은 뒤
 * `.array` 로 꺼낸 `Object[]` 를 `String` 으로 캐스팅해 `Set<String>` 으로 모은다(순서는 보존하지
 * 않는다 — [ChannelProjectMapping.eventTypes] 는 애초에 순서 없는 필터 집합).
 */
private object ChannelProjectMappingRowMapper : RowMapper<ChannelProjectMapping> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): ChannelProjectMapping {
        val eventTypes = (rs.getArray("event_types").array as Array<*>).map { it as String }.toSet()
        return ChannelProjectMapping(
            id = rs.getObject("id", UUID::class.java),
            teamId = rs.getString("team_id"),
            projectKey = rs.getString("project_key"),
            channelId = rs.getString("channel_id"),
            channelName = rs.getString("channel_name"),
            eventTypes = eventTypes,
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
