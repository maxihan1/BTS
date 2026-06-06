// 이슈 보안 스킴/등급/멤버 aggregate 영속 인터페이스 (FR-PM-06 PR-A Task 3)

package com.atlas.bts.identity.issuesecurity

import java.util.UUID

/**
 * [IssueSecurityScheme]과 그 소속 등급([IssueSecurityLevel])을 함께 담는 조회 전용 뷰.
 *
 * 스킴 상세/목록 화면처럼 스킴 본문과 등급 목록을 한 번에 보여줘야 할 때 사용한다.
 * 등급은 영속 계층이 스칼라/배치 조회로 채우며, LEFT JOIN cartesian product 를 회피한다.
 *
 * @property scheme 스킴 본문(id/타임스탬프가 채워진 영속 상태).
 * @property levels 스킴에 속한 보안 등급 목록(없으면 빈 목록).
 */
data class IssueSecuritySchemeDetail(
    val scheme: IssueSecurityScheme,
    val levels: List<IssueSecurityLevel>,
)

/**
 * `issue_security_schemes` / `issue_security_levels` / `issue_security_level_members` 테이블
 * 접근 인터페이스 (FR-PM-06 PR-A Task 3).
 *
 * 이슈 보안 스킴 aggregate(스킴 + 등급 + 멤버)의 CRUD 를 한 포트로 담당한다.
 * 멤버 추가는 멱등하게 설계되어 중복 추가가 예외를 던지지 않는다(ON CONFLICT DO NOTHING).
 *
 * 트랜잭션 경계는 본 인터페이스 구현체([JdbcIssueSecuritySchemeRepository])가 클래스 수준
 * `@Transactional` 로 소유한다.
 */
interface IssueSecuritySchemeRepository {
    // ── 스킴 ──────────────────────────────────────────────────────────────────────

    /**
     * 새 스킴을 저장하고 DB 가 채운 id/타임스탬프까지 포함한 [IssueSecurityScheme]을 반환한다.
     *
     * @param scheme 도메인 팩토리([IssueSecurityScheme.create])로 검증된 신규 스킴(id/타임스탬프 `null`).
     * @return id/createdAt/updatedAt 이 채워진 영속 [IssueSecurityScheme].
     * @throws org.springframework.dao.DuplicateKeyException [IssueSecurityScheme.name] 이 이미 존재하는 경우.
     */
    fun create(scheme: IssueSecurityScheme): IssueSecurityScheme

    /**
     * id 로 스킴을 소속 등급과 함께 조회한다.
     *
     * @param id 스킴 식별자.
     * @return 스킴 본문 + 등급 목록을 담은 [IssueSecuritySchemeDetail], 없으면 `null`.
     */
    fun findById(id: UUID): IssueSecuritySchemeDetail?

    /**
     * 모든 스킴을 각자의 등급 목록과 함께 조회한다.
     *
     * @return 각 스킴과 그 등급을 담은 [IssueSecuritySchemeDetail] 목록. 스킴이 없으면 빈 목록.
     */
    fun findAll(): List<IssueSecuritySchemeDetail>

    /**
     * 스킴의 이름/설명을 갱신하고 갱신된 [IssueSecurityScheme]을 반환한다.
     *
     * @param id 갱신할 스킴 식별자.
     * @param name 새 이름.
     * @param description 새 설명(nullable).
     * @return 갱신된 [IssueSecurityScheme], 해당 id 가 없으면 `null`.
     * @throws org.springframework.dao.DuplicateKeyException [name] 이 다른 스킴과 충돌하는 경우.
     */
    fun update(
        id: UUID,
        name: String,
        description: String?,
    ): IssueSecurityScheme?

    /**
     * 스킴을 삭제한다. 소속 등급과 멤버는 FK ON DELETE CASCADE 로 함께 제거된다.
     *
     * @param id 삭제할 스킴 식별자.
     * @return 실제로 삭제된 행이 있으면 `true`, 없으면 `false`.
     */
    fun delete(id: UUID): Boolean

    // ── 등급 ──────────────────────────────────────────────────────────────────────

    /**
     * 스킴에 새 등급을 추가하고 DB 가 채운 id/createdAt 까지 포함한 [IssueSecurityLevel]을 반환한다.
     *
     * @param level 도메인 팩토리([IssueSecurityLevel.create])로 검증된 신규 등급(id/createdAt `null`).
     * @return id/createdAt 이 채워진 영속 [IssueSecurityLevel].
     * @throws org.springframework.dao.DuplicateKeyException 같은 스킴 내 [IssueSecurityLevel.name] 중복인 경우.
     * @throws org.springframework.dao.DataIntegrityViolationException 스킴당 둘째 기본 등급(부분 유니크 위반) 추가 시.
     */
    fun addLevel(level: IssueSecurityLevel): IssueSecurityLevel

    /**
     * 스킴에 속한 모든 등급을 조회한다.
     *
     * @param schemeId 스킴 식별자.
     * @return 등급 목록. 등급이 없으면 빈 목록.
     */
    fun listLevels(schemeId: UUID): List<IssueSecurityLevel>

    /**
     * 등급의 이름/설명/기본 여부를 갱신하고 갱신된 [IssueSecurityLevel]을 반환한다.
     *
     * @param id 갱신할 등급 식별자.
     * @param name 새 이름.
     * @param description 새 설명(nullable).
     * @param isDefault 스킴 기본 등급 여부.
     * @return 갱신된 [IssueSecurityLevel], 해당 id 가 없으면 `null`.
     * @throws org.springframework.dao.DuplicateKeyException 같은 스킴 내 [name] 충돌인 경우.
     * @throws org.springframework.dao.DataIntegrityViolationException 둘째 기본 등급(부분 유니크 위반)으로 갱신 시.
     */
    fun updateLevel(
        id: UUID,
        name: String,
        description: String?,
        isDefault: Boolean,
    ): IssueSecurityLevel?

    /**
     * 등급을 삭제한다. 소속 멤버는 FK ON DELETE CASCADE 로 함께 제거된다.
     *
     * @param id 삭제할 등급 식별자.
     * @return 실제로 삭제된 행이 있으면 `true`, 없으면 `false`.
     */
    fun deleteLevel(id: UUID): Boolean

    // ── 멤버 ──────────────────────────────────────────────────────────────────────

    /**
     * 등급에 멤버를 추가하고 DB 가 채운 id/createdAt 까지 포함한 [SecurityLevelMember]를 반환한다(멱등).
     *
     * 같은 (level_id, member_type, member_value) 가 이미 있으면 `ON CONFLICT DO NOTHING` 으로
     * 기존 행을 그대로 반환한다.
     *
     * @param member 도메인 팩토리([SecurityLevelMember.create])로 검증된 신규 멤버(id/createdAt `null`).
     * @return id/createdAt 이 채워진 영속 [SecurityLevelMember](멱등 시 기존 행).
     */
    fun addMember(member: SecurityLevelMember): SecurityLevelMember

    /**
     * 등급에 속한 모든 멤버를 조회한다.
     *
     * @param levelId 등급 식별자.
     * @return 멤버 목록. 멤버가 없으면 빈 목록.
     */
    fun listMembers(levelId: UUID): List<SecurityLevelMember>

    /**
     * 멤버를 id 로 제거한다.
     *
     * @param id 제거할 멤버 식별자.
     * @return 실제로 삭제된 행이 있으면 `true`, 없으면 `false`.
     */
    fun removeMemberById(id: UUID): Boolean
}
