// 이슈 변경 이력 저장소 인터페이스 — append-only, update/delete 메서드 없음 (DATA.md §3)

package com.bts.issue.history

import java.util.UUID

/**
 * 이슈 변경 이력 저장소.
 *
 * **append-only 설계.**
 * 이 인터페이스에는 update/delete 메서드가 없다.
 * 한 번 기록된 이력은 수정·삭제 불가 (DATA.md §3 — 감사 로그 영구 보존).
 * 인터페이스 시그니처 자체가 컴파일 레벨에서 이 불변식을 강제한다.
 *
 * **본격 조회는 FR-HS-02.**
 * [findByIssue] 는 구현 검증용 최소 조회 메서드다.
 * 페이지네이션·필터링 등 풍부한 조회는 FR-HS-02 에서 별도 도입한다.
 */
interface IssueChangeHistoryRepository {
    /**
     * 이슈 변경 그룹(및 포함된 항목)을 한 번에 기록한다.
     *
     * [IssueChangeGroup] 1건 INSERT + [IssueChangeGroup.items] N건 batch INSERT.
     * 호출자 트랜잭션에 참여하여 원자적으로 커밋된다.
     *
     * @param group 기록할 변경 그룹. items 가 비어 있어도 그룹 행은 삽입된다.
     */
    fun record(group: IssueChangeGroup)

    /**
     * 특정 이슈의 변경 이력 그룹 목록을 최신순으로 조회한다.
     *
     * 각 그룹에 속한 [IssueChangeItem] 목록이 함께 반환된다.
     * FR-HS-02 이전의 최소 검증용 조회 메서드.
     *
     * @param issueId 조회할 이슈의 UUID.
     * @return 변경 그룹 목록. 이력이 없으면 빈 리스트.
     */
    fun findByIssue(issueId: UUID): List<IssueChangeGroup>

    /**
     * 특정 이슈의 변경 이력 그룹을 페이지 단위로 최신순 조회한다.
     *
     * [created_at DESC, id DESC] 순으로 정렬하며 [limit]/[offset]으로 페이지를 구분한다.
     * 각 그룹에 속한 [IssueChangeItem] 목록이 함께 반환된다.
     *
     * @param issueId 조회할 이슈의 UUID.
     * @param limit 한 페이지에 반환할 최대 그룹 수.
     * @param offset 건너뛸 그룹 수. 첫 페이지는 0.
     * @return 변경 그룹 목록(items 포함). 결과가 없으면 빈 리스트.
     */
    fun findByIssuePaged(
        issueId: UUID,
        limit: Int,
        offset: Int,
    ): List<IssueChangeGroup>

    /**
     * 특정 이슈의 변경 이력 그룹 총 개수를 반환한다.
     *
     * 페이지네이션의 totalCount 계산에 사용된다.
     *
     * @param issueId 조회할 이슈의 UUID.
     * @return 변경 그룹 수. 이력이 없으면 0.
     */
    fun countByIssue(issueId: UUID): Long
}
