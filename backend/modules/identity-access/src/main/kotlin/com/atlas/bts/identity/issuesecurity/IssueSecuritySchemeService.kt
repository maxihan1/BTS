// 이슈 보안 스킴/등급/멤버 CRUD 를 오케스트레이션하는 애플리케이션 서비스 (FR-PM-06 PR-A Task 5)

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.user.UserRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 이슈 보안 스킴 애플리케이션 서비스 (FR-PM-06 PR-A Task 5).
 *
 * ## 책임
 * - 스킴/등급/멤버 3계층 CRUD 오케스트레이션 — 생성/수정/삭제/조회.
 * - 도메인 팩토리([IssueSecurityScheme.create]/[IssueSecurityLevel.create]/[SecurityLevelMember.create])
 *   경유로 불변식을 강제(우회 금지, patch-merge-domain-bypass 회귀 방지).
 * - 영속 계층의 [DuplicateKeyException] 을 의미별 도메인 예외(NameConflict)로 변환.
 * - 멤버 추가 시 USER/GROUP memberValue 실재 사전조회.
 *
 * ## 트랜잭션 경계 (DATA.md §6 / DEVELOPMENT.md §1.2)
 * [IssueSecuritySchemeRepository] 구현체가 자체 트랜잭션을 열지만, 본 서비스는 다중 호출
 * (사전 존재 확인 + 위임)을 한 단위로 묶어야 하므로 트랜잭션을 소유한다.
 * 클래스 레벨 @Transactional(REQUIRED) — 쓰기 메서드 기본 적용. 읽기 전용은 readOnly 로 오버라이드.
 *
 * ## 멤버 실재 사전조회 (plan Task 5 C1 정정)
 * - USER: [UserRepository] 에 `existsById` 가 없으므로 `findById(uuid) != null` 로 실재 확인.
 * - GROUP: [UserGroupRepository.existsById] 로 실재 확인.
 * - PROJECT_ROLE/REPORTER/ASSIGNEE: 도메인 팩토리 검증만(외부 실재 대상 없음).
 *
 * ## 등급 실재 처리
 * [IssueSecuritySchemeRepository] 는 level-by-id 조회를 제공하지 않으므로,
 * 등급 멤버 추가 시 누락된 등급은 멤버 INSERT 의 FK 위반([DataIntegrityViolationException])으로
 * 표면화되며 이를 [LevelNotFoundException] 으로 변환한다(멤버 값 중복은 ON CONFLICT DO NOTHING 으로
 * 예외를 던지지 않으므로 무결성 위반은 곧 등급 부재를 의미한다).
 *
 * ## 함수 수 (detekt TooManyFunctions)
 * 스킴/등급/멤버 3계층 CRUD 를 한 트랜잭션 경계 안에서 오케스트레이션하므로 메서드가 13개다
 * (임계 11). 계층별로 서비스를 쪼개면 트랜잭션 경계가 흩어지고 사전 존재 확인이 분산되므로
 * 의도된 단일 aggregate 서비스이며 [Repository][IssueSecuritySchemeRepository] 와 같은 근거로 `@Suppress` 한다.
 *
 * @see docs/decisions/2026-06-06-issue-security-level-scheme-model.md 설계 결정 ADR
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
@Suppress("TooManyFunctions")
class IssueSecuritySchemeService(
    private val schemeRepository: IssueSecuritySchemeRepository,
    private val userRepository: UserRepository,
    private val userGroupRepository: UserGroupRepository,
) {
    private companion object {
        /**
         * 등급 갱신 시 [IssueSecurityLevel.create] 의 이름/설명 정규화만 재사용하기 위한 자리표시 schemeId.
         *
         * 갱신은 schemeId 를 변경하지 않으므로(영속 계층이 기존 값 보존) 이 값은 영속되지 않는다.
         */
        val PLACEHOLDER_SCHEME_ID: UUID = UUID(0L, 0L)
    }

    // ── 스킴 ────────────────────────────────────────────────────────────────────

    /**
     * 새 보안 스킴을 생성한다.
     *
     * [IssueSecurityScheme.create] 로 이름/설명을 정규화·검증한 뒤 영속 계층에 저장한다.
     *
     * @param name 스킴 이름 (정규화 전 원본).
     * @param description 스킴 설명 (정규화 전 원본, nullable).
     * @return id/타임스탬프가 채워진 영속 [IssueSecurityScheme].
     * @throws IllegalArgumentException 이름이 비어 있거나 길이 제약 위반.
     * @throws SchemeNameConflictException 이름이 이미 존재하는 경우.
     */
    fun createScheme(
        name: String,
        description: String?,
    ): IssueSecurityScheme {
        val normalized = IssueSecurityScheme.create(name, description)
        return try {
            schemeRepository.create(normalized)
        } catch (ex: DuplicateKeyException) {
            throw SchemeNameConflictException(normalized.name).apply { initCause(ex) }
        }
    }

    /**
     * 스킴의 이름/설명을 갱신한다.
     *
     * [IssueSecurityScheme.create] 로 정규화한 값으로 갱신한다.
     *
     * @param id 갱신할 스킴 식별자.
     * @param name 새 이름 (정규화 전 원본).
     * @param description 새 설명 (정규화 전 원본, nullable).
     * @return 갱신된 [IssueSecurityScheme].
     * @throws SchemeNotFoundException 해당 스킴이 없는 경우.
     * @throws SchemeNameConflictException 새 이름이 다른 스킴과 충돌하는 경우.
     */
    fun updateScheme(
        id: UUID,
        name: String,
        description: String?,
    ): IssueSecurityScheme {
        val normalized = IssueSecurityScheme.create(name, description)
        return try {
            schemeRepository.update(id, normalized.name, normalized.description)
                ?: throw SchemeNotFoundException(id)
        } catch (ex: DuplicateKeyException) {
            throw SchemeNameConflictException(normalized.name).apply { initCause(ex) }
        }
    }

    /**
     * 스킴을 삭제한다. 소속 등급과 멤버는 FK ON DELETE CASCADE 로 함께 제거된다.
     *
     * @param id 삭제할 스킴 식별자.
     * @throws SchemeNotFoundException 해당 스킴이 없는 경우.
     */
    fun deleteScheme(id: UUID) {
        if (!schemeRepository.delete(id)) throw SchemeNotFoundException(id)
    }

    /**
     * id 로 스킴을 소속 등급과 함께 조회한다.
     *
     * @param id 스킴 식별자.
     * @return 스킴 본문 + 등급 목록을 담은 [IssueSecuritySchemeDetail].
     * @throws SchemeNotFoundException 해당 스킴이 없는 경우.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    fun getScheme(id: UUID): IssueSecuritySchemeDetail {
        return schemeRepository.findById(id) ?: throw SchemeNotFoundException(id)
    }

    /**
     * 모든 스킴을 각자의 등급 목록과 함께 조회한다.
     *
     * @return [IssueSecuritySchemeDetail] 목록. 스킴이 없으면 빈 목록.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    fun listSchemes(): List<IssueSecuritySchemeDetail> = schemeRepository.findAll()

    // ── 등급 ────────────────────────────────────────────────────────────────────

    /**
     * 스킴에 새 보안 등급을 추가한다.
     *
     * 스킴 실재를 사전 확인한 뒤 [IssueSecurityLevel.create] 로 정규화·검증해 추가한다.
     *
     * @param schemeId 소속 스킴 식별자.
     * @param name 등급 이름 (정규화 전 원본).
     * @param description 등급 설명 (정규화 전 원본, nullable).
     * @param isDefault 스킴 기본 등급 여부.
     * @return id/createdAt 이 채워진 영속 [IssueSecurityLevel].
     * @throws SchemeNotFoundException 소속 스킴이 없는 경우.
     * @throws LevelNameConflictException 같은 스킴 내 이름이 충돌하는 경우.
     * @throws IllegalArgumentException 이름이 비어 있거나 길이 제약 위반.
     */
    fun addLevel(
        schemeId: UUID,
        name: String,
        description: String?,
        isDefault: Boolean,
    ): IssueSecurityLevel {
        schemeRepository.findById(schemeId) ?: throw SchemeNotFoundException(schemeId)
        val normalized = IssueSecurityLevel.create(schemeId, name, description, isDefault)
        return try {
            schemeRepository.addLevel(normalized)
        } catch (ex: DuplicateKeyException) {
            throw LevelNameConflictException(normalized.name).apply { initCause(ex) }
        }
    }

    /**
     * 등급의 이름/설명/기본 여부를 갱신한다.
     *
     * @param id 갱신할 등급 식별자.
     * @param name 새 이름 (정규화 전 원본).
     * @param description 새 설명 (정규화 전 원본, nullable).
     * @param isDefault 스킴 기본 등급 여부.
     * @return 갱신된 [IssueSecurityLevel].
     * @throws LevelNotFoundException 해당 등급이 없는 경우.
     * @throws LevelNameConflictException 같은 스킴 내 이름이 충돌하는 경우.
     */
    fun updateLevel(
        id: UUID,
        name: String,
        description: String?,
        isDefault: Boolean,
    ): IssueSecurityLevel {
        // 갱신 시 schemeId 는 영속 계층이 보존하므로 검증/정규화만 도메인 팩토리에 위임(우회 금지).
        // create 가 요구하는 schemeId 자리에 placeholder(NIL)를 넣어 이름/설명 정규화만 재사용한다.
        val normalized = IssueSecurityLevel.create(PLACEHOLDER_SCHEME_ID, name, description, isDefault)
        return try {
            schemeRepository.updateLevel(id, normalized.name, normalized.description, isDefault)
                ?: throw LevelNotFoundException(id)
        } catch (ex: DuplicateKeyException) {
            throw LevelNameConflictException(normalized.name).apply { initCause(ex) }
        }
    }

    /**
     * 등급을 삭제한다. 소속 멤버는 FK ON DELETE CASCADE 로 함께 제거된다.
     *
     * @param id 삭제할 등급 식별자.
     * @throws LevelNotFoundException 해당 등급이 없는 경우.
     */
    fun deleteLevel(id: UUID) {
        if (!schemeRepository.deleteLevel(id)) throw LevelNotFoundException(id)
    }

    /**
     * 스킴에 속한 모든 등급을 조회한다.
     *
     * @param schemeId 스킴 식별자.
     * @return 등급 목록. 없으면 빈 목록.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    fun listLevels(schemeId: UUID): List<IssueSecurityLevel> = schemeRepository.listLevels(schemeId)

    // ── 멤버 ────────────────────────────────────────────────────────────────────

    /**
     * 등급에 멤버를 추가한다 (멱등).
     *
     * USER/GROUP memberValue 는 실재를 사전조회하고, [SecurityLevelMember.create] 로 타입별 다형 규칙을
     * 검증한 뒤 영속 계층에 위임한다. 같은 (level, type, value) 중복은 영속 계층이 멱등 처리한다.
     *
     * @param levelId 소속 등급 식별자.
     * @param memberType 멤버 타입.
     * @param memberValue 타입별 값 (USER/GROUP=UUID, PROJECT_ROLE=역할, REPORTER/ASSIGNEE=null).
     * @return id/createdAt 이 채워진 영속 [SecurityLevelMember] (멱등 시 기존 행).
     * @throws IllegalArgumentException 타입별 [memberValue] 규칙 위반.
     * @throws IssueSecurityUserNotFoundException USER 값 사용자가 실재하지 않는 경우.
     * @throws IssueSecurityGroupNotFoundException GROUP 값 그룹이 실재하지 않는 경우.
     * @throws LevelNotFoundException 소속 등급이 없는 경우(FK 위반).
     */
    fun addMember(
        levelId: UUID,
        memberType: MemberType,
        memberValue: String?,
    ): SecurityLevelMember {
        val normalized = SecurityLevelMember.create(levelId, memberType, memberValue)
        verifyMemberValueExists(normalized)
        return try {
            schemeRepository.addMember(normalized)
        } catch (ex: DataIntegrityViolationException) {
            throw LevelNotFoundException(levelId).apply { initCause(ex) }
        }
    }

    /**
     * USER/GROUP 멤버 값의 실재를 사전 확인한다. PROJECT_ROLE/REPORTER/ASSIGNEE 는 검증 대상이 없다.
     *
     * @throws IssueSecurityUserNotFoundException USER 사용자가 없는 경우.
     * @throws IssueSecurityGroupNotFoundException GROUP 그룹이 없는 경우.
     */
    private fun verifyMemberValueExists(member: SecurityLevelMember) {
        when (member.memberType) {
            MemberType.USER -> {
                // 도메인 팩토리가 UUID 형식을 이미 보장하므로 fromString 은 안전하다.
                val userId = UUID.fromString(member.memberValue)
                userRepository.findById(userId) ?: throw IssueSecurityUserNotFoundException(userId)
            }
            MemberType.GROUP -> {
                val groupId = UUID.fromString(member.memberValue)
                if (!userGroupRepository.existsById(groupId)) throw IssueSecurityGroupNotFoundException(groupId)
            }
            MemberType.PROJECT_ROLE, MemberType.REPORTER, MemberType.ASSIGNEE -> Unit
        }
    }

    /**
     * 등급에서 멤버를 제거한다 (멱등).
     *
     * 없는 멤버여도 영속 계층이 0행 삭제로 멱등 처리하므로 예외 없이 위임만 한다.
     *
     * @param memberId 제거할 멤버 식별자.
     */
    fun removeMember(memberId: UUID) {
        schemeRepository.removeMemberById(memberId)
    }

    /**
     * 등급에 속한 모든 멤버를 조회한다.
     *
     * @param levelId 등급 식별자.
     * @return 멤버 목록. 없으면 빈 목록.
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    fun listMembers(levelId: UUID): List<SecurityLevelMember> = schemeRepository.listMembers(levelId)
}
