// 워크플로우 상태 픽스처 — 테스트가 상태를 심는 유일한 경로 (전역 카탈로그 2단 + 구형 테이블)

package com.bts.issue.testsupport

import java.sql.Connection
import java.util.UUID

/**
 * 워크플로우에 상태 1개를 심는다. **테스트가 상태를 만드는 유일한 경로**다.
 *
 * ### 왜 헬퍼인가
 * 읽기 경로가 `statuses` + `workflow_statuses` **2단 카탈로그**로 옮겨졌다. 테스트가 구형
 * `workflow_states` 에만 심으면 그 워크플로우는 **상태 0개**로 읽힌다. mirror data 의 형식은
 * 정적 문자열이 아니라 **helper 호출로 본질 차단**한다(learnings 2026-05-23 fixture 옵션 B 패턴).
 * 원시 SQL 재유입은 `RawWorkflowStateInsertGuardTest` 가 막는다.
 *
 * ### 왜 구형 테이블에도 심는가 — add→backfill→drop 의 중간이다
 * ```
 *   statuses ──┐
 *              ├── workflow_statuses ── (읽기: 상태 목록)   ← PR 3 이 여기로 옮김
 *   workflows ─┘
 *              └── workflow_states ──── (전환 FK 의 참조 대상)  ← 아직 필요
 *                        ↑
 *      workflow_transitions.from_state_id / to_state_id 가 이것을 가리킨다.
 *      workflow_statuses 로의 재지정은 **로드맵 PR 4** 의 일이다.
 * ```
 * 그래서 이 헬퍼는 **세 테이블 모두**에 심고 `workflow_states.id` 를 돌려준다 — 호출부가 그 id 로
 * 전환을 잇기 때문이다. PR 4 가 전환을 재지정하면 여기서 구형 삽입과 반환 타입을 함께 바꾼다.
 *
 * @param workflowId 상태를 붙일 워크플로우
 * @param key 상태 키. 전역 카탈로그에서 유일하다 — 같은 키면 기존 카탈로그 행을 재사용한다
 * @param category `TODO` · `IN_PROGRESS` · `DONE` 중 하나
 * @return `workflow_states.id`. 전환 픽스처가 from/to 로 쓴다
 */
fun insertWorkflowStatus(
    conn: Connection,
    workflowId: UUID,
    key: String,
    name: String,
    category: String,
    displayOrder: Int,
): UUID {
    val statusId = upsertStatus(conn, key, name, category)
    linkStatusToWorkflow(conn, workflowId, statusId, displayOrder)
    return insertLegacyState(conn, workflowId, key, name, category, displayOrder)
}

/** 전역 카탈로그에 상태를 넣거나 이미 있으면 그 id 를 준다. 카탈로그는 사이트 전역이라 키가 유일하다. */
private fun upsertStatus(
    conn: Connection,
    key: String,
    name: String,
    category: String,
): UUID =
    conn.prepareStatement(
        "INSERT INTO statuses (key, name, category) VALUES (?, ?, ?)" +
            " ON CONFLICT (key) WHERE deleted_at IS NULL DO UPDATE SET name = EXCLUDED.name" +
            " RETURNING id",
    ).use { stmt ->
        stmt.setString(1, key)
        stmt.setString(2, name)
        stmt.setString(3, category)
        stmt.executeQuery().use { rs ->
            rs.next()
            rs.getObject(1) as UUID
        }
    }

/** 워크플로우 ↔ 상태 편성 1행. 같은 짝이 이미 있으면 표시 순서만 갱신한다. */
private fun linkStatusToWorkflow(
    conn: Connection,
    workflowId: UUID,
    statusId: UUID,
    displayOrder: Int,
) {
    conn.prepareStatement(
        "INSERT INTO workflow_statuses (workflow_id, status_id, display_order) VALUES (?, ?, ?)" +
            " ON CONFLICT (workflow_id, status_id) DO UPDATE SET display_order = EXCLUDED.display_order",
    ).use { stmt ->
        stmt.setObject(1, workflowId)
        stmt.setObject(2, statusId)
        stmt.setInt(3, displayOrder)
        stmt.executeUpdate()
    }
}

/**
 * 구형 `workflow_states` 행. **전환 FK 가 아직 이 테이블을 참조하므로 남긴다** — 로드맵 PR 4 가 재지정한다.
 *
 * 이 함수가 이 저장소에서 `workflow_states` 에 INSERT 하는 것이 허용된 유일한 테스트 경로다.
 */
private fun insertLegacyState(
    conn: Connection,
    workflowId: UUID,
    key: String,
    name: String,
    category: String,
    displayOrder: Int,
): UUID =
    conn.prepareStatement(
        "INSERT INTO workflow_states (workflow_id, key, name, category, display_order)" +
            " VALUES (?, ?, ?, ?, ?)" +
            " ON CONFLICT (workflow_id, key) DO UPDATE SET display_order = EXCLUDED.display_order" +
            " RETURNING id",
    ).use { stmt ->
        stmt.setObject(1, workflowId)
        stmt.setString(2, key)
        stmt.setString(3, name)
        stmt.setString(4, category)
        stmt.setInt(5, displayOrder)
        stmt.executeQuery().use { rs ->
            rs.next()
            rs.getObject(1) as UUID
        }
    }
