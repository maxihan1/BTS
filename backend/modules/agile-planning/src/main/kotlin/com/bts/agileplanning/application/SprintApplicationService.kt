// 스프린트 CRUD + 이슈 할당 애플리케이션 서비스 — agile-planning BC (FR-BL-02)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.Sprint
import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.SprintRepository
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.permission.IssuePermissionResolver
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * 스프린트 CRUD 및 이슈 할당 위임 애플리케이션 서비스.
 *
 * cross-BC 통신은 shared-kernel 포트만 사용한다. issue-tracking / identity-access 내부를 직접 import 하지 않는다.
 *
 * @param permissionResolver cross-BC 권한 판정 포트 (fail-closed, non-null 주입)
 * @param sprintRepository sprints / sprint_issues jOOQ repository
 * @param boardIssueLookupPort 이슈 단건 가시성 확인 포트 (issue-tracking 구현)
 */
@Service
@Transactional
class SprintApplicationService(
    private val permissionResolver: IssuePermissionResolver,
    private val sprintRepository: SprintRepository,
    private val boardIssueLookupPort: BoardIssueLookupPort,
) {
    fun create(
        actorId: UUID,
        projectKey: String,
        name: String,
        goal: String?,
        startDate: LocalDate?,
        endDate: LocalDate?,
    ): Sprint = TODO("GREEN 단계에서 구현")

    fun update(
        actorId: UUID,
        sprintId: UUID,
        name: String,
        goal: String?,
        startDate: LocalDate?,
        endDate: LocalDate?,
        version: Long,
    ): Sprint = TODO("GREEN 단계에서 구현")

    fun softDelete(
        actorId: UUID,
        sprintId: UUID,
    ): Unit = TODO("GREEN 단계에서 구현")

    fun list(
        actorId: UUID,
        projectKey: String,
        statusFilter: SprintStatus?,
    ): List<Sprint> = TODO("GREEN 단계에서 구현")

    fun get(
        actorId: UUID,
        sprintId: UUID,
    ): Sprint = TODO("GREEN 단계에서 구현")

    fun start(
        actorId: UUID,
        sprintId: UUID,
    ): Sprint = TODO("GREEN 단계에서 구현")

    fun complete(
        actorId: UUID,
        sprintId: UUID,
    ): Sprint = TODO("GREEN 단계에서 구현")

    fun assignIssue(
        actorId: UUID,
        sprintId: UUID,
        issueKey: String,
    ): Unit = TODO("GREEN 단계에서 구현")

    fun unassignIssue(
        actorId: UUID,
        sprintId: UUID,
        issueKey: String,
    ): Unit = TODO("GREEN 단계에서 구현")
}
