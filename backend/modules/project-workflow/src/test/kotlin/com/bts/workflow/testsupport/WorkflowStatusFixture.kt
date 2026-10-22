// 워크플로우 상태 픽스처 — 테스트가 상태를 심는 유일한 경로 (전역 카탈로그 2단)

package com.bts.workflow.testsupport

import java.sql.Connection
import java.util.UUID

/**
 * 워크플로우에 상태 1개를 심는다. **테스트가 상태를 만드는 유일한 경로**다.
 *
 * 읽기 경로가 `statuses` + `workflow_statuses` **2단 카탈로그**를 본다. mirror data 의 형식은
 * 정적 문자열이 아니라 **helper 호출로 본질 차단**한다(learnings 2026-05-23 fixture 옵션 B 패턴).
 *
 * @param workflowId 상태를 붙일 워크플로우
 * @param key 상태 키. 전역 카탈로그에서 유일하다 — 같은 키면 기존 카탈로그 행을 재사용한다
 * @param category `TODO` · `IN_PROGRESS` · `DONE` 중 하나
 * @return `workflow_statuses.id`. 전환 픽스처가 `from_status_id` · `to_status_id` 로 쓴다
 *
 * ### 파라미터가 6개인 이유
 * DB 컬럼을 그대로 받는 픽스처라 자연스러운 형태다. spec data class 로 묶으면 **두 BC 에 또 하나의
 * 복제**가 생기고(이 헬퍼가 이미 2벌이다) 호출부 40여 곳의 가독성도 떨어진다.
 */
@Suppress("LongParameterList")
fun insertWorkflowStatus(
    conn: Connection,
    workflowId: UUID,
    key: String,
    name: String,
    category: String,
    displayOrder: Int,
): UUID {
    val statusId = upsertStatus(conn, key, name, category)
    return linkStatusToWorkflow(conn, workflowId, statusId, displayOrder)
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
): UUID =
    conn.prepareStatement(
        "INSERT INTO workflow_statuses (workflow_id, status_id, display_order) VALUES (?, ?, ?)" +
            " ON CONFLICT (workflow_id, status_id) DO UPDATE SET display_order = EXCLUDED.display_order" +
            " RETURNING id",
    ).use { stmt ->
        stmt.setObject(1, workflowId)
        stmt.setObject(2, statusId)
        stmt.setInt(3, displayOrder)
        stmt.executeQuery().use { rs ->
            rs.next()
            rs.getObject(1) as UUID
        }
    }
