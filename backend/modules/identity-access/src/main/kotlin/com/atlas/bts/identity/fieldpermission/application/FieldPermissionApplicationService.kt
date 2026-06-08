// 필드 권한 규칙 CRUD 응용 서비스 — MANAGE_FIELD_PERMISSIONS 게이트 + CORE 화이트리스트 (FR-PM-07 PR-A Task 5)

package com.atlas.bts.identity.fieldpermission.application

import com.atlas.bts.identity.fieldpermission.domain.FieldAccessLevel
import com.atlas.bts.identity.fieldpermission.domain.FieldPermission
import com.atlas.bts.identity.fieldpermission.repository.FieldPermissionRepository
import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.permission.PermissionSchemeRepository
import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.shared.permission.FieldKind
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 필드 권한 규칙 CRUD 응용 서비스 (FR-PM-07 PR-A Task 5).
 *
 * ## MANAGE_FIELD_PERMISSIONS 게이트 (F3, EC11)
 * 모든 CRUD 진입점에서 [requireManagePermission] 으로 actor 의 권한을 **가장 먼저** 평가한다
 * (인증/권한 평가를 리소스 조회보다 앞에 둬서 미보유자에게 리소스 존재를 노출하지 않는다).
 * 판정은 두 단계.
 * 1. [ProjectMembershipRepository.findByProjectAndUser] 로 actor 의 프로젝트 role 조회. 비멤버면 거부.
 * 2. [PermissionSchemeRepository.roleHasPermission] 으로 유효 권한 스킴에서 role 이
 *    `MANAGE_FIELD_PERMISSIONS` 를 보유하는지 DB 로 판정. 미보유면 거부.
 *
 * `@PreAuthorize hasRole` 대신 DB 진실원천을 직접 조회하는 이유 — JWT claim 이 stale 일 수 있고
 * PAT 경로에는 role claim 이 없어, 두 인증 경로에서 일관된 권한 판정을 보장하기 위함(FR-PM-09 선례).
 *
 * ## 검증 책임
 * - CORE field_key 화이트리스트([CORE_FIELD_KEYS]) 위반 → [InvalidFieldKey].
 * - CUSTOM field_key 는 존재 확인을 생략(cross-BC 정의 조회 불가, orphan 허용 — 스펙 EC8/EC13).
 * - group_id 미존재 → [GroupNotFound]. (EC12)
 * - field_key 정규화/길이 불변식은 [FieldPermission.create] 가 소유.
 *
 * ## 멱등
 * [FieldPermissionRepository.save] 의 ON CONFLICT DO NOTHING 으로 동일 규칙 재추가가 멱등하다.
 *
 * ## 트랜잭션 경계 (DATA.md §6)
 * 클래스 레벨 `@Transactional` — 게이트 조회·검증·쓰기를 한 트랜잭션으로 묶는다.
 * 읽기 전용 [listRules] 는 `@Transactional(readOnly = true)` 로 오버라이드.
 */
@Service
@Transactional
class FieldPermissionApplicationService(
    private val fieldPermissionRepository: FieldPermissionRepository,
    private val membershipRepository: ProjectMembershipRepository,
    private val permissionSchemeRepository: PermissionSchemeRepository,
    private val userGroupRepository: UserGroupRepository,
) {
    /**
     * 프로젝트의 전체 필드 권한 규칙을 그룹 이름과 함께 조회한다.
     *
     * @param actorId 요청 행위자 식별자.
     * @param projectId 대상 프로젝트 식별자.
     * @return (규칙, 그룹 이름) 쌍 목록. 그룹이 사라진 orphan 규칙은 CASCADE 로 제거되므로 항상 존재.
     * @throws ManageFieldPermissionsDenied actor 가 권한을 보유하지 않은 경우.
     */
    @Transactional(readOnly = true)
    fun listRules(
        actorId: UUID,
        projectId: UUID,
    ): List<RuleWithGroupName> {
        requireManagePermission(actorId, projectId)

        return fieldPermissionRepository.findByProject(projectId).map { rule ->
            val groupName = userGroupRepository.findById(rule.groupId)?.name ?: ""
            RuleWithGroupName(rule, groupName)
        }
    }

    /**
     * 필드 권한 규칙을 생성한다(멱등).
     *
     * @param actorId 요청 행위자 식별자.
     * @param projectId 대상 프로젝트 식별자.
     * @param fieldKind 대상 필드 종류.
     * @param fieldKey 대상 필드 키(정규화 전 원본).
     * @param groupId 허용 대상 그룹 식별자.
     * @param accessLevel 허용 수준.
     * @return 저장된(또는 이미 존재하던) 규칙 + 그룹 이름.
     * @throws ManageFieldPermissionsDenied actor 가 권한을 보유하지 않은 경우.
     * @throws InvalidFieldKey CORE field_key 가 화이트리스트에 없는 경우.
     * @throws GroupNotFound group_id 가 존재하지 않는 경우.
     */
    @Suppress("LongParameterList")
    fun createRule(
        actorId: UUID,
        projectId: UUID,
        fieldKind: FieldKind,
        fieldKey: String,
        groupId: UUID,
        accessLevel: FieldAccessLevel,
    ): RuleWithGroupName {
        requireManagePermission(actorId, projectId)

        val normalizedKey = fieldKey.trim()
        if (fieldKind == FieldKind.CORE && normalizedKey !in CORE_FIELD_KEYS) {
            throw InvalidFieldKey()
        }
        val group = userGroupRepository.findById(groupId) ?: throw GroupNotFound()

        val rule = FieldPermission.create(projectId, fieldKind, normalizedKey, groupId, accessLevel)
        fieldPermissionRepository.save(rule)

        // save 는 ON CONFLICT DO NOTHING 이라 영속 id 를 돌려주지 않으므로, 재조회로 영속 규칙을 찾는다.
        val persisted =
            fieldPermissionRepository.findByProject(projectId).first {
                it.fieldKind == rule.fieldKind &&
                    it.fieldKey == rule.fieldKey &&
                    it.groupId == rule.groupId &&
                    it.accessLevel == rule.accessLevel
            }
        return RuleWithGroupName(persisted, group.name)
    }

    /**
     * 필드 권한 규칙을 식별자로 삭제한다(멱등).
     *
     * @param actorId 요청 행위자 식별자.
     * @param projectId 대상 프로젝트 식별자.
     * @param ruleId 삭제할 규칙 식별자.
     * @throws ManageFieldPermissionsDenied actor 가 권한을 보유하지 않은 경우.
     */
    fun deleteRule(
        actorId: UUID,
        projectId: UUID,
        ruleId: UUID,
    ) {
        requireManagePermission(actorId, projectId)
        fieldPermissionRepository.deleteById(ruleId)
    }

    /**
     * actor 가 대상 프로젝트에서 `MANAGE_FIELD_PERMISSIONS` 를 보유하는지 검증한다.
     *
     * 비멤버이거나 유효 권한 스킴에서 role 이 권한을 보유하지 않으면 [ManageFieldPermissionsDenied].
     */
    private fun requireManagePermission(
        actorId: UUID,
        projectId: UUID,
    ) {
        val membership =
            membershipRepository.findByProjectAndUser(projectId, actorId)
                ?: throw ManageFieldPermissionsDenied()
        val hasPermission =
            permissionSchemeRepository.roleHasPermission(
                projectId,
                membership.role.name,
                MANAGE_FIELD_PERMISSIONS,
            )
        if (!hasPermission) throw ManageFieldPermissionsDenied()
    }

    private companion object {
        /** 필드 권한 규칙 관리 권한 코드 (V018 시드, 기본 스킴 PROJECT_ADMIN 보유). */
        const val MANAGE_FIELD_PERMISSIONS = "MANAGE_FIELD_PERMISSIONS"

        /**
         * 권한 제어 대상 CORE field_key 화이트리스트 (스펙 §3.1).
         *
         * 제외 필드(id·key·securityLevelId·componentIds 등)는 별도 통제이거나 시스템/식별 필드이므로
         * 여기 포함하지 않는다. CUSTOM 키는 화이트리스트 대상이 아니다(형식만 검증).
         */
        val CORE_FIELD_KEYS =
            setOf(
                "summary",
                "description",
                "priority",
                "labels",
                "environment",
                "impact",
                "assigneeId",
            )
    }
}

/**
 * 규칙과 그 규칙이 가리키는 그룹 이름을 함께 담는 응용 계층 반환 타입.
 *
 * @property rule 영속 필드 권한 규칙.
 * @property groupName 규칙이 가리키는 그룹 이름.
 */
data class RuleWithGroupName(
    val rule: FieldPermission,
    val groupName: String,
)

/**
 * 필드 권한 규칙 관리 예외의 sealed 최상위.
 *
 * 컨트롤러가 이 계층만 잡아 HTTP 로 매핑한다(광범위 catch 금지).
 */
sealed class FieldPermissionException(message: String) : RuntimeException(message)

/** actor 가 `MANAGE_FIELD_PERMISSIONS` 를 보유하지 않음 → 403 (EC11). */
class ManageFieldPermissionsDenied : FieldPermissionException("필드 권한 규칙을 관리할 권한이 없습니다.")

/** CORE field_key 가 화이트리스트에 없음 → 422 (EC13). */
class InvalidFieldKey : FieldPermissionException("허용되지 않은 필드 키입니다.")

/** group_id 가 존재하지 않음 → 422 (EC12). */
class GroupNotFound : FieldPermissionException("대상 그룹이 존재하지 않습니다.")
