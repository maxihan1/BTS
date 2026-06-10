// 릴리즈 노트 생성 ApplicationService — 조회 조합, Clock 주입, READ 게이트 없음
package com.bts.issue.version.releasenotes

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.ReleaseNoteIssueRow
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.version.domain.VersionNotFoundException
import com.bts.issue.version.domain.VersionProjectNotFoundException
import com.bts.issue.version.repository.VersionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 릴리즈 노트 생성 ApplicationService.
 *
 * 프로젝트/버전 존재 확인 후 fix version 이슈를 조합해 Markdown 릴리즈 노트를 생성한다.
 * DB 에 영속하지 않으며, 요청 시마다 생성한다.
 *
 * **READ 게이트 없음** — [VersionApplicationService.getById] / [listByProject] 정책과 동일.
 * 프로젝트·버전 존재 확인(404)만 수행하고 권한 resolver 를 호출하지 않는다.
 *
 * **N+1 회피** — [ResolutionRepository.findAllActive] 를 1회 호출해 맵을 빌드한 뒤,
 * 이슈 수에 무관하게 resolutionId → resolutionName 매핑에 재사용한다.
 *
 * **Clock 주입** — [generatedAt] 이 결정론적으로 테스트 가능하다.
 */
@Service
@Transactional(readOnly = true)
class ReleaseNotesService(
    private val projectLookup: ProjectLookup,
    private val projectLookupRepository: ProjectLookupRepository,
    private val versionRepository: VersionRepository,
    private val issueRepository: IssueRepository,
    private val resolutionRepository: ResolutionRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 지정한 버전의 릴리즈 노트를 생성한다.
     *
     * 흐름.
     * 1. [ProjectLookup.resolve] — 미존재 시 [VersionProjectNotFoundException](404).
     * 2. [VersionRepository.findById] — 미존재 시 [VersionNotFoundException](404).
     * 3. [IssueRepository.findFixVersionIssuesForReleaseNotes] — fix version 연결 활성 이슈 조회.
     * 4. [ResolutionRepository.findAllActive] 맵 빌드 — N+1 회피(1회 조회).
     * 5. [ProjectLookupRepository.findProjectKeyById] — resolve 성공 후이므로 존재 보장, null 이면 방어적 처리.
     * 6. [ReleaseNoteIssueRow] → [ReleaseNoteIssue] 매핑.
     * 7. [ReleaseNotesGenerator.generate] 위임.
     * 8. [ReleaseNotes] 반환.
     *
     * READ 게이트 없음 — 권한 resolver 를 호출하지 않는다.
     *
     * @param actorId 요청 행위자 UUID (로깅 목적).
     * @param projectIdOrKey 프로젝트 UUID 또는 projectKey (예: `"BTS"`).
     * @param versionId 릴리즈 노트를 생성할 버전 UUID.
     * @return 생성된 [ReleaseNotes].
     * @throws VersionProjectNotFoundException 프로젝트가 존재하지 않을 때.
     * @throws VersionNotFoundException 버전이 존재하지 않을 때.
     */
    fun generate(
        actorId: UUID,
        projectIdOrKey: String,
        versionId: UUID,
    ): ReleaseNotes {
        val projectId = resolveProject(projectIdOrKey)
        val version =
            versionRepository.findById(versionId, projectId)
                ?: throw VersionNotFoundException(versionId)

        val rows = issueRepository.findFixVersionIssuesForReleaseNotes(versionId)
        val resolutionMap = buildResolutionMap()
        val projectKey = resolveProjectKey(projectId, projectIdOrKey)

        log.debug(
            "generate release_notes versionId={} projectId={} actor={} issueCount={}",
            versionId,
            projectId,
            actorId,
            rows.size,
        )

        val issues = rows.map { row -> mapToReleaseNoteIssue(row, resolutionMap) }
        val input =
            ReleaseNotesInput(
                projectKey = projectKey,
                versionName = version.name,
                versionStatus = version.status.name,
                releaseDate = version.releaseDate,
                issues = issues,
            )
        val markdown = ReleaseNotesGenerator.generate(input)

        return ReleaseNotes(
            versionId = versionId,
            projectKey = projectKey,
            versionName = version.name,
            versionStatus = version.status,
            releaseDate = version.releaseDate,
            issueCount = rows.size,
            generatedAt = Instant.now(clock),
            markdown = markdown,
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * projectIdOrKey 를 활성 프로젝트 UUID 로 해석한다.
     * 미존재 시 [VersionProjectNotFoundException] 을 던진다.
     */
    private fun resolveProject(projectIdOrKey: String): UUID =
        projectLookup.resolve(projectIdOrKey)
            ?: throw VersionProjectNotFoundException(projectIdOrKey)

    /**
     * 활성 Resolution 목록을 1회 조회해 id → name 맵을 반환한다.
     *
     * N+1 회피 — 이슈 수에 무관하게 DB 조회를 1회만 수행한다.
     */
    private fun buildResolutionMap(): Map<java.util.UUID, String> =
        resolutionRepository.findAllActive().associate { resolution ->
            checkNotNull(resolution.id) { "resolution.id must not be null" } to resolution.name
        }

    /**
     * 프로젝트 UUID 로 projectKey 를 조회한다.
     *
     * [resolveProject] 가 이미 활성 프로젝트를 확인했으므로 존재가 보장된다.
     * null 이면 [projectIdOrKey] 를 fallback 으로 사용한다 (방어적 처리).
     */
    private fun resolveProjectKey(
        projectId: UUID,
        projectIdOrKey: String,
    ): String {
        val key = projectLookupRepository.findProjectKeyById(projectId)
        if (key == null) {
            log.warn(
                "projectKey not found for projectId={}, falling back to projectIdOrKey={}",
                projectId,
                projectIdOrKey,
            )
        }
        return key ?: projectIdOrKey
    }

    /**
     * [ReleaseNoteIssueRow] 를 [ReleaseNoteIssue] 로 매핑한다.
     *
     * resolutionId 가 있으면 [resolutionMap] 에서 이름을 조회한다.
     * 맵에 없으면 null 로 처리한다 (삭제된 resolution 방어).
     */
    private fun mapToReleaseNoteIssue(
        row: ReleaseNoteIssueRow,
        resolutionMap: Map<UUID, String>,
    ): ReleaseNoteIssue =
        ReleaseNoteIssue(
            key = row.key,
            summary = row.summary,
            typeKey = row.typeKey,
            typeName = row.typeName,
            hierarchyLevel = row.hierarchyLevel,
            resolutionName = row.resolutionId?.let { resolutionMap[it] },
        )
}
