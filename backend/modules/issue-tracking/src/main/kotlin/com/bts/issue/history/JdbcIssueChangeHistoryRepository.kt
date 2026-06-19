// 이슈 변경 이력 DB 영속 구현체 — issue_change_group/item 테이블(V018) append-only INSERT/SELECT

package com.bts.issue.history

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * [IssueChangeHistoryRepository] DB 영속 구현체 (FR-HS-01 Task 4).
 *
 * `issue_change_group` / `issue_change_item` 테이블(V018)에
 * 이슈 변경 이력을 **append-only**로 기록한다. 삭제·수정 메서드는 없다 (DATA.md §3).
 *
 * **트랜잭션 경계.**
 * [record] 는 `Propagation.REQUIRED` (기본값) — 호출자 트랜잭션에 참여하거나 새로 시작한다.
 * 독립 트랜잭션 없이 호출자와 원자적으로 커밋되어야 하므로
 * `REQUIRES_NEW` 는 사용하지 않는다 (이력과 이슈 변경이 같은 트랜잭션에서 커밋).
 * [findByIssue] 는 `readOnly = true` 로 오버라이드.
 *
 * **RETURNING id.**
 * group INSERT 시 `GeneratedKeyHolder` 로 자동 발번된 group id 를 획득한다.
 * 이후 items 를 그 group_id 로 batch INSERT 한다.
 *
 * **SQL 인젝션 방어 (DEVELOPMENT.md §1.3).**
 * 모든 파라미터를 [NamedParameterJdbcTemplate] named parameter 바인딩으로 처리.
 *
 * **선례.**
 * `com.atlas.bts.identity.audit.JdbcAuthAuditLogService` 패턴 재사용
 * (NamedParameterJdbcTemplate + append-only + RowMapper + `getObject("col", UUID::class.java)`).
 */
@Repository
class JdbcIssueChangeHistoryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : IssueChangeHistoryRepository {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 변경 그룹 1건과 items N건을 기록한다.
     *
     * 1. `issue_change_group` 행을 INSERT하고 `RETURNING id` 로 group id 획득.
     * 2. items 를 group_id 로 batch INSERT.
     * items 가 비어 있으면 group 행만 삽입되고 batch INSERT 는 실행하지 않는다.
     *
     * @param group 기록할 변경 그룹.
     */
    @Transactional
    override fun record(group: IssueChangeGroup) {
        log.debug(
            "record issueId={} issueKey={} items={}",
            group.issueId,
            group.issueKey,
            group.items.size,
        )

        val groupId = insertGroup(group)
        if (group.items.isNotEmpty()) {
            insertItemsBatch(groupId, group.items)
        }
    }

    /**
     * 특정 이슈의 변경 이력 그룹 목록을 최신순으로 조회한다.
     *
     * group 행을 먼저 조회한 뒤 group id 목록으로 items 를 별쿼리로 조회한다.
     * JOIN 방식은 group 당 N 개 items 가 곱해져 cartesian product 위험이 있으므로
     * 별쿼리 2개(group/items)로 분리한다 (learnings: jOOQ-cartesian-product).
     *
     * @param issueId 조회할 이슈의 UUID.
     * @return 변경 그룹 목록(items 포함). 이력이 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    override fun findByIssue(issueId: UUID): List<IssueChangeGroup> {
        val groups = jdbc.query(SQL_FIND_GROUPS_BY_ISSUE, mapOf("issueId" to issueId), groupRowMapper)
        if (groups.isEmpty()) return emptyList()

        val groupIds = groups.map { it.first }
        val itemsByGroupId = fetchItemsByGroupIds(groupIds)

        return groups.map { (groupId, group) ->
            group.copy(items = itemsByGroupId[groupId] ?: emptyList())
        }
    }

    /**
     * 특정 이슈의 변경 이력 그룹을 페이지 단위로 최신순 조회한다.
     *
     * [SQL_FIND_GROUPS_BY_ISSUE_PAGED] 로 limit/offset 적용 후
     * [fetchItemsByGroupIds] 배치 패턴으로 items 를 채운다.
     * JOIN 방식의 cartesian product 를 피하기 위해 2-step 조회를 유지한다
     * (learnings: jOOQ-cartesian-product).
     *
     * @param issueId 조회할 이슈의 UUID.
     * @param limit 한 페이지에 반환할 최대 그룹 수.
     * @param offset 건너뛸 그룹 수.
     * @return 변경 그룹 목록(items 포함). 결과가 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    override fun findByIssuePaged(
        issueId: UUID,
        limit: Int,
        offset: Int,
    ): List<IssueChangeGroup> {
        val params = mapOf("issueId" to issueId, "limit" to limit, "offset" to offset)
        val groups = jdbc.query(SQL_FIND_GROUPS_BY_ISSUE_PAGED, params, groupRowMapper)
        if (groups.isEmpty()) return emptyList()

        val groupIds = groups.map { it.first }
        val itemsByGroupId = fetchItemsByGroupIds(groupIds)

        return groups.map { (groupId, group) ->
            group.copy(items = itemsByGroupId[groupId] ?: emptyList())
        }
    }

    /**
     * 특정 이슈의 변경 이력 그룹 총 개수를 반환한다.
     *
     * @param issueId 조회할 이슈의 UUID.
     * @return 변경 그룹 수. 이력이 없으면 0.
     */
    @Transactional(readOnly = true)
    override fun countByIssue(issueId: UUID): Long {
        return jdbc.queryForObject(
            SQL_COUNT_GROUPS_BY_ISSUE,
            mapOf("issueId" to issueId),
            Long::class.java,
        ) ?: 0L
    }

    /**
     * 특정 이슈의 최근 assignee 변경 이력의 from_value 를 반환한다.
     *
     * field = 'assignee' 인 변경 항목 중 가장 최근(item.id DESC) 1건의 from_value 를 반환한다.
     * 이력이 없거나 from_value 가 null 이면 null 을 반환한다.
     *
     * 단일 SQL 쿼리로 처리 — 전 이력 메모리 로드 없음.
     *
     * @param issueId 조회할 이슈의 UUID.
     * @return 최근 assignee 변경의 from_value 문자열. 없으면 null.
     */
    @Transactional(readOnly = true)
    override fun findLatestAssigneeChangeFromValue(issueId: UUID): String? {
        return jdbc.query(
            SQL_FIND_LATEST_ASSIGNEE_FROM_VALUE,
            mapOf("issueId" to issueId),
        ) { rs, _ -> rs.getString("from_value") }.firstOrNull()
    }

    // ── private 헬퍼 ──────────────────────────────────────────────────────────

    /**
     * issue_change_group 행을 INSERT하고 RETURNING id 로 생성된 group id 를 반환한다.
     *
     * [GeneratedKeyHolder] 를 사용하면 INSERT … RETURNING id 결과를 Spring JDBC 가
     * keyHolder 에 저장한다. `keyHolder.keys?.get("id")` 로 BIGINT id 를 꺼낸다.
     */
    private fun insertGroup(group: IssueChangeGroup): Long {
        val keyHolder = GeneratedKeyHolder()
        val params =
            MapSqlParameterSource()
                .addValue("issueId", group.issueId)
                .addValue("issueKey", group.issueKey)
                .addValue("actorId", group.actorId)

        jdbc.update(SQL_INSERT_GROUP, params, keyHolder, arrayOf("id"))

        return (keyHolder.keys?.get("id") as? Number)?.toLong()
            ?: error("issue_change_group INSERT RETURNING id 값이 없음 — issueKey=${group.issueKey}")
    }

    /**
     * items 를 groupId 로 batch INSERT 한다.
     *
     * [NamedParameterJdbcTemplate.batchUpdate] 로 단일 PreparedStatement N건을 전송한다.
     * from_value/to_value/from_label/to_label 은 nullable — `null` 을 그대로 바인딩한다.
     */
    private fun insertItemsBatch(
        groupId: Long,
        items: List<IssueChangeItem>,
    ) {
        val batchParams =
            items
                .map { item ->
                    MapSqlParameterSource()
                        .addValue("groupId", groupId)
                        .addValue("field", item.field)
                        .addValue("fromValue", item.fromValue)
                        .addValue("toValue", item.toValue)
                        .addValue("fromLabel", item.fromLabel)
                        .addValue("toLabel", item.toLabel)
                }.toTypedArray()

        jdbc.batchUpdate(SQL_INSERT_ITEM, batchParams)
    }

    /**
     * group id 목록으로 items 를 한꺼번에 조회하고 groupId 기준으로 그룹핑한다.
     *
     * IN 절을 사용하여 N+1 쿼리를 방지한다.
     *
     * @param groupIds 조회할 group id 목록.
     * @return groupId → items 목록 맵.
     */
    private fun fetchItemsByGroupIds(groupIds: List<Long>): Map<Long, List<IssueChangeItem>> {
        val rows =
            jdbc.query(
                SQL_FIND_ITEMS_BY_GROUP_IDS,
                mapOf("groupIds" to groupIds),
                itemWithGroupIdRowMapper,
            )
        return rows.groupBy({ it.first }, { it.second })
    }

    // ── RowMapper ─────────────────────────────────────────────────────────────

    /**
     * issue_change_group ResultSet → (groupId, IssueChangeGroup) Pair.
     *
     * createdAt 은 TIMESTAMPTZ → Instant 변환.
     * UUID: `getObject + UUID::class.java` — PostgreSQL JDBC 권장 방식 (JdbcAuthAuditLogService 선례).
     * items 는 빈 리스트로 초기화 — 별쿼리 패턴으로 [fetchItemsByGroupIds] 가 채운다.
     */
    private val groupRowMapper: RowMapper<Pair<Long, IssueChangeGroup>> =
        RowMapper { rs, _ -> mapGroupRow(rs) }

    private fun mapGroupRow(rs: ResultSet): Pair<Long, IssueChangeGroup> {
        val groupId = rs.getLong("id")
        val group =
            IssueChangeGroup(
                issueId = rs.getObject("issue_id", UUID::class.java),
                issueKey = rs.getString("issue_key"),
                actorId = rs.getObject("actor_id", UUID::class.java),
                items = emptyList(),
                createdAt = rs.getTimestamp("created_at")?.toInstant(),
            )
        return groupId to group
    }

    /**
     * issue_change_item ResultSet → (groupId, IssueChangeItem) Pair.
     *
     * from_value/to_value/from_label/to_label 은 nullable — `getString` 이 null 을 그대로 반환.
     */
    private val itemWithGroupIdRowMapper: RowMapper<Pair<Long, IssueChangeItem>> =
        RowMapper { rs, _ -> mapItemRow(rs) }

    private fun mapItemRow(rs: ResultSet): Pair<Long, IssueChangeItem> {
        val groupId = rs.getLong("group_id")
        val item =
            IssueChangeItem(
                field = rs.getString("field"),
                fromValue = rs.getString("from_value"),
                toValue = rs.getString("to_value"),
                fromLabel = rs.getString("from_label"),
                toLabel = rs.getString("to_label"),
            )
        return groupId to item
    }

    // ── SQL 상수 ──────────────────────────────────────────────────────────────

    private companion object {
        /**
         * 변경 그룹 append-only INSERT.
         * id 는 DB IDENTITY 자동 발번, RETURNING id 로 반환.
         * actor_id 는 nullable — named parameter 에 null 을 바인딩하면 DB NULL 로 저장됨.
         */
        const val SQL_INSERT_GROUP = """
            INSERT INTO issue_change_group (issue_id, issue_key, actor_id)
            VALUES (:issueId, :issueKey, :actorId)
        """

        /**
         * 변경 항목 append-only INSERT.
         * from_value/to_value/from_label/to_label 은 nullable — null 바인딩 허용.
         */
        const val SQL_INSERT_ITEM = """
            INSERT INTO issue_change_item (group_id, field, from_value, to_value, from_label, to_label)
            VALUES (:groupId, :field, :fromValue, :toValue, :fromLabel, :toLabel)
        """

        /**
         * 이슈 ID로 변경 그룹 목록을 최신순 조회.
         * idx_issue_change_group_issue (issue_id, created_at DESC, id DESC) 인덱스 활용.
         */
        const val SQL_FIND_GROUPS_BY_ISSUE = """
            SELECT id, issue_id, issue_key, actor_id, created_at
            FROM issue_change_group
            WHERE issue_id = :issueId
            ORDER BY created_at DESC, id DESC
        """

        /**
         * group id IN 절로 items 를 한꺼번에 조회.
         * N+1 방지 — group 별 별도 쿼리 대신 IN 절로 단일 쿼리 실행.
         * idx_issue_change_item_group (group_id) 인덱스 활용.
         */
        const val SQL_FIND_ITEMS_BY_GROUP_IDS = """
            SELECT group_id, field, from_value, to_value, from_label, to_label
            FROM issue_change_item
            WHERE group_id IN (:groupIds)
            ORDER BY id ASC
        """

        /**
         * 이슈 ID로 변경 그룹 목록을 최신순 페이지 조회.
         * [SQL_FIND_GROUPS_BY_ISSUE] 와 동일 정렬, LIMIT/OFFSET 추가.
         * named parameter :limit, :offset 으로 SQL 인젝션 방어.
         */
        const val SQL_FIND_GROUPS_BY_ISSUE_PAGED = """
            SELECT id, issue_id, issue_key, actor_id, created_at
            FROM issue_change_group
            WHERE issue_id = :issueId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit OFFSET :offset
        """

        /**
         * 이슈 ID에 해당하는 변경 그룹 총 개수 조회.
         * 페이지네이션의 totalCount 계산에 사용.
         */
        const val SQL_COUNT_GROUPS_BY_ISSUE = """
            SELECT COUNT(*)
            FROM issue_change_group
            WHERE issue_id = :issueId
        """

        /**
         * 특정 이슈의 최근 assignee 변경 항목의 from_value 조회.
         * issue_change_group.issue_id 로 그룹을 찾고, issue_change_item.field = 'assignee' 로 필터링.
         * item.id DESC 로 정렬해 가장 최근 변경의 from_value 1건만 반환.
         * 전 이력 로드 없이 단일 쿼리로 처리.
         */
        const val SQL_FIND_LATEST_ASSIGNEE_FROM_VALUE = """
            SELECT i.from_value
            FROM issue_change_item i
            JOIN issue_change_group g ON i.group_id = g.id
            WHERE g.issue_id = :issueId
              AND i.field = 'assignee'
            ORDER BY i.id DESC
            LIMIT 1
        """
    }
}
