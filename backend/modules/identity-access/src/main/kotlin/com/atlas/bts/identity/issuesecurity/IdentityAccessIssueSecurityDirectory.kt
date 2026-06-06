// IssueSecurityDirectory prod 구현 — 적용 스킴 기준 actor 접근 가능 등급 집합 산출 + 등급-스킴 소속 검증 (FR-PM-06 PR-B Task 9)

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.project.ProjectDirectory
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * [IssueSecurityDirectory] prod 구현체 (FR-PM-06 PR-B Task 9).
 *
 * issue-tracking BC 의 목록 필터(T10)와 등급 지정 422 검증(T6)이 소비하는 cross-BC 포트의 운영 구현이다.
 * 개발/테스트/스테이징에서는 issue-tracking 의 `AlwaysAllowIssueSecurityDirectory`(`@Profile("!prod")`)가
 * 활성화되므로, 본 구현은 운영(prod)에서만 동작한다.
 *
 * ## accessibleLevels — 정적 vs 동적 등급 분리
 * actor 가 접근 가능한 등급을 세 갈래로 산출한다([IssueSecurityAccess]).
 * - staticLevelIds — USER/GROUP/PROJECT_ROLE 멤버로 actor 가 충족하는 등급(이슈 무관, 항상 적용).
 * - reporterLevelIds — REPORTER 멤버를 가진 등급(actor 가 그 이슈의 보고자일 때만 통과).
 * - assigneeLevelIds — ASSIGNEE 멤버를 가진 등급(actor 가 그 이슈의 담당자일 때만 통과).
 * REPORTER/ASSIGNEE 는 이슈별로 충족이 갈리므로 정적 집합에 넣지 않고 분리한다(목록 WHERE 동적 조건).
 *
 * ## 빠른 경로 (C5)
 * 프로젝트에 적용 스킴이 없으면 보안 등급 자체가 없으므로 모든 이슈가 공개다. 이때
 * `unrestricted=true`(필터 미적용)를 단일 스킴 조회만으로 반환해 추가 쿼리를 0에 가깝게 둔다.
 *
 * ## 고아 등급·미충족 등급
 * 멤버가 0명인 고아 등급, actor 가 충족하지 못하는 등급은 어느 집합에도 포함되지 않는다.
 * 그 결과 해당 등급이 지정된 이슈는 목록에서 제외된다([IssueSecurityDecider]의 고아=차단과 일관).
 *
 * ## 관리자 우회 없음 (ADR §결정5)
 * 본 구현은 isSystemAdmin 을 호출하지 않는다. 멤버십만이 접근의 유일한 근거다.
 *
 * @see IssueSecurityDirectory
 * @see IssueSecurityAccess
 */
@Component
@Profile("prod")
class IdentityAccessIssueSecurityDirectory(
    private val projectDirectory: ProjectDirectory,
    private val projectSecuritySchemeRepo: ProjectSecuritySchemeRepository,
    private val schemeRepo: IssueSecuritySchemeRepository,
    private val membershipRepo: ProjectMembershipRepository,
    private val userGroupRepo: UserGroupRepository,
) : IssueSecurityDirectory {
    override fun levelBelongsToProjectScheme(
        levelId: UUID,
        projectKey: String,
    ): Boolean {
        val projectId = projectDirectory.resolveKeyToId(projectKey) ?: return false
        return schemeRepo.levelBelongsToProjectScheme(levelId, projectId)
    }

    // ReturnCount: guard-clause early return 2개(프로젝트 미존재·적용 스킴 없음 → UNRESTRICTED) + 본문 1.
    // DEVELOPMENT.md §2.3 Early return 권장 정책에 부합 — 전역 임계 완화 대신 국소 Suppress.
    @Suppress("ReturnCount")
    override fun accessibleLevels(
        actorId: UUID,
        projectKey: String,
    ): IssueSecurityAccess {
        val projectId = projectDirectory.resolveKeyToId(projectKey) ?: return UNRESTRICTED
        // 빠른 경로(C5) — 적용 스킴이 없으면 보안 등급 자체가 없어 모든 이슈가 공개.
        val schemeId = projectSecuritySchemeRepo.findByProject(projectId) ?: return UNRESTRICTED

        val actorRole = membershipRepo.findByProjectAndUser(projectId, actorId)?.role?.name
        val staticLevelIds =
            schemeRepo.listLevels(schemeId)
                .mapNotNull { it.id }
                .filterTo(mutableSetOf()) { levelId ->
                    actorSatisfiesStaticMember(levelId, actorId, actorRole)
                }

        return IssueSecurityAccess(
            unrestricted = false,
            staticLevelIds = staticLevelIds,
            reporterLevelIds = schemeRepo.listLevelIdsByMemberType(schemeId, MemberType.REPORTER),
            assigneeLevelIds = schemeRepo.listLevelIdsByMemberType(schemeId, MemberType.ASSIGNEE),
        )
    }

    /**
     * actor 가 한 등급의 정적 멤버(USER/GROUP/PROJECT_ROLE) 중 하나라도 충족하는지 판정한다.
     *
     * REPORTER/ASSIGNEE 는 이슈별 동적 조건이라 여기서 평가하지 않는다([accessibleLevels] 가 별도 집합으로 분리).
     * GROUP 은 등급에 등록된 GROUP 멤버에 대해서만 [UserGroupRepository.isMemberOf] 를 호출해
     * 전 그룹 순회를 피한다.
     */
    private fun actorSatisfiesStaticMember(
        levelId: UUID,
        actorId: UUID,
        actorRole: String?,
    ): Boolean =
        schemeRepo.listMembers(levelId).any { member ->
            when (member.memberType) {
                MemberType.USER -> member.memberValue == actorId.toString()
                MemberType.GROUP ->
                    member.memberValue?.let { userGroupRepo.isMemberOf(UUID.fromString(it), actorId) } ?: false
                MemberType.PROJECT_ROLE -> actorRole != null && actorRole == member.memberValue
                MemberType.REPORTER, MemberType.ASSIGNEE -> false
            }
        }

    private companion object {
        /** 적용 스킴이 없을 때 반환하는 무제한 접근(필터 미적용) 빠른 경로 값. */
        val UNRESTRICTED =
            IssueSecurityAccess(
                unrestricted = true,
                staticLevelIds = emptySet(),
                reporterLevelIds = emptySet(),
                assigneeLevelIds = emptySet(),
            )
    }
}
