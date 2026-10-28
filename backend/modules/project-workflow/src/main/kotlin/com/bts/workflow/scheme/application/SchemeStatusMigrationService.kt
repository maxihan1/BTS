// 스킴 게시 · 프로젝트 스킴 교체를 유형별 상태 이관과 함께 처리하는 서비스 (R11 · FR-WF-02 · FR-WF-07)

package com.bts.workflow.scheme.application

import com.bts.shared.issue.IssueStatusMigrationPort
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.StatusMigrationCommand
import com.bts.shared.issue.StatusMigrationMapping
import com.bts.shared.issue.StatusMigrationTypeFilter
import com.bts.workflow.application.port.MigrationInFlightPort
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.scheme.domain.StrandedIssueType
import com.bts.workflow.scheme.domain.TypeStatusMapping
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.exception.SchemeChangeStrandsIssuesException
import com.bts.workflow.scheme.exception.SchemeMigrationInFlightException
import com.bts.workflow.scheme.exception.SchemeStatusMappingInvalidException
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 스킴 변경과 상태 이관을 한 트랜잭션으로 묶는다 (R11 A1 · A2 · 결정 D4).
 *
 * 남는 이슈 판정은 [WorkflowSchemeApplicationService] 의 것을 그대로 쓰고 처리기만 바꾼다 —
 * 상태 매핑이 남는 (이슈 타입, 상태)를 전부 덮으면 막지 않고 이관 명령을 만든다. 매핑 · 배정을
 * 저장한 **뒤** 같은 트랜잭션에서 큐잉한다(pgmq MANDATORY). 저장이 실패하면 큐잉도 없다.
 *
 * 이슈 타입마다 명령 1건이다 — 타입마다 옛 워크플로우 · 새 워크플로우가 달라 같은 상태 키가 다른
 * 곳으로 갈 수 있다. 유형 필터로 좁히므로 shared-kernel 계약은 그대로다.
 */
@Service
class SchemeStatusMigrationService(
    private val schemeService: WorkflowSchemeApplicationService,
    private val projectLookupPort: ProjectLookupPort,
    private val migrationInFlightPort: MigrationInFlightPort,
    private val issueStatusMigrationPort: IssueStatusMigrationPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 스킴 매핑 전체를 교체하고 남는 이슈를 옮긴다 — 「게시」.
     *
     * @param mappings 이슈 타입(null = 기본) → 워크플로우 id ([WorkflowSchemeApplicationService.resolveMappingKeys]).
     * @return 큐잉한 이관 작업 id. 남는 이슈가 없으면 빈 목록.
     * @throws SchemeChangeStrandsIssuesException 상태 매핑이 남는 (타입, 상태)를 다 덮지 않을 때 — 마법사 입력.
     */
    @Transactional
    fun publish(
        actor: ActorId,
        schemeKey: WorkflowSchemeKey,
        mappings: List<Pair<IssueTypeId?, UUID>>,
        statusMappings: List<TypeStatusMapping>,
    ): List<UUID> {
        var commands: List<StatusMigrationCommand> = emptyList()
        schemeService.replaceMappings(actor, schemeKey, mappings) { projectIds, stranded ->
            commands = commandsFor(actor, stranded, statusMappings, projectKeysOf(projectIds))
        }
        return enqueue(commands)
    }

    /**
     * 프로젝트 스킴을 교체하고 남는 이슈를 옮긴다.
     *
     * @return 큐잉한 이관 작업 id. 남는 이슈가 없으면 빈 목록.
     * @throws SchemeChangeStrandsIssuesException 상태 매핑이 남는 (타입, 상태)를 다 덮지 않을 때 — 마법사 입력.
     */
    @Transactional
    fun switchScheme(
        actor: ActorId,
        projectId: UUID,
        projectKey: String,
        schemeKey: WorkflowSchemeKey,
        statusMappings: List<TypeStatusMapping>,
    ): List<UUID> {
        var commands: List<StatusMigrationCommand> = emptyList()
        schemeService.assignToProject(actor, projectId, projectKey, schemeKey) { _, stranded ->
            commands = commandsFor(actor, stranded, statusMappings, setOf(projectKey))
        }
        return enqueue(commands)
    }

    private fun enqueue(commands: List<StatusMigrationCommand>): List<UUID> =
        commands.map { issueStatusMigrationPort.enqueueStatusMigration(it) }.also {
            if (it.isNotEmpty()) log.info("스킴 변경 이관 큐잉. bulkOperationIds={}", it)
        }

    /** 이관 명령은 프로젝트 **키**로 범위를 받는다. 키를 못 찾는 프로젝트는 판정 직후 지워진 것이라 뺀다. */
    private fun projectKeysOf(projectIds: Set<UUID>): Set<String> =
        projectIds.mapNotNull { projectLookupPort.findKeyById(it)?.value }.toSet()

    @Suppress("ThrowsCount")
    private fun commandsFor(
        actor: ActorId,
        stranded: List<StrandedIssueType>,
        statusMappings: List<TypeStatusMapping>,
        projectKeys: Set<String>,
    ): List<StatusMigrationCommand> {
        val byType = statusMappings.groupBy { it.issueTypeId }
        val covered =
            stranded.all { t ->
                t.statuses.keys.all { from -> byType[t.issueTypeId].orEmpty().any { it.fromStatusKey == from } }
            }
        if (!covered) throw SchemeChangeStrandsIssuesException(stranded)
        if (projectKeys.isEmpty()) return emptyList()

        if (migrationInFlightPort.hasInFlightMigration(projectKeys)) throw SchemeMigrationInFlightException()
        return stranded.map { t ->
            val mappings =
                t.statuses.keys.sorted().map { from ->
                    val to = byType.getValue(t.issueTypeId).first { it.fromStatusKey == from }.toStatusKey
                    if (to !in t.targetStatusKeys) {
                        throw SchemeStatusMappingInvalidException(
                            "이슈 타입 ${t.issueTypeId} 의 도착 상태 $to 가 워크플로우 ${t.workflowKey} 에 없다",
                        )
                    }
                    StatusMigrationMapping(fromStatusKey = from, toStatusKey = to)
                }
            StatusMigrationCommand(
                actorUserId = actor.toUuid(),
                projectScopes =
                    projectKeys.associateWith { StatusMigrationTypeFilter(setOf(t.issueTypeId), exceptListed = false) },
                mappings = mappings,
            )
        }
    }
}
