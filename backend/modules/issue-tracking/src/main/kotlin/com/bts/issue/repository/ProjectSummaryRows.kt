// 프로젝트 요약·활동 집계 원천 read-model — repository 계층 전용, summary 패키지 비의존

package com.bts.issue.repository

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * [IssueRepository.fetchActiveVisibleIssuesForSummary] 조회 결과 1행.
 *
 * 프로젝트 요약 화면이 필요로 하는 모든 집계(카드 3종 · 분포 4종 · 마감/지연)를 이 한 행 집합에서
 * 뽑아낸다. **분포를 SQL 로 GROUP BY 하지 않는 것이 의도**다 — status·priority·type·assignee 를
 * 한 쿼리로 묶으면 다중 조인이 건수를 부풀리고(`IssueTypeRepository` PR#31 학습), 축마다 쿼리를
 * 나누면 같은 보안 술어를 4번 반복하게 된다. 행 하나 = 이슈 하나라는 불변식을 지키면
 * 어느 축으로 집계해도 합이 항상 이슈 수와 같다.
 *
 * core repository(`com.bts.issue.repository`)가 feature 패키지(`summary`)를 의존하지 않도록
 * 이 read-model 을 repository 계층에 정의한다([CfdIssueSourceRow] 선례).
 *
 * @property issueId 이슈 UUID.
 * @property typeId 이슈 유형 id (`issue_types.id`) — 유형 분포와 워크플로우 카테고리 해석에 쓴다.
 * @property currentStateKey 현재 워크플로우 상태 키 — 상태 분포의 축.
 * @property priority 우선순위(1~5, `SMALLINT`) — 우선순위 분포의 축.
 * @property assigneeId 담당자 UUID. null 이면 미할당(담당자 분포의 미할당 버킷).
 * @property dueDate 마감 예정일. null 이면 미지정 — 마감/지연 집계에서 제외된다.
 * @property createdAt 생성 시각 — 「최근 7일 생성」 카드.
 * @property updatedAt 최종 수정 시각 — 「최근 7일 업데이트」 카드.
 */
data class SummaryIssueRow(
    val issueId: UUID,
    val typeId: Long,
    val currentStateKey: String,
    val priority: Int,
    val assigneeId: UUID?,
    val dueDate: LocalDate?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * [IssueRepository.fetchProjectActivity] 조회 결과 1행 — 변경 그룹 × 변경 항목 하나.
 *
 * 한 변경 그룹이 N 개 항목을 가지므로 같은 [groupId] 로 여러 행이 나온다. 서비스가 [groupId] 로
 * 묶어 피드 항목 하나를 만든다.
 *
 * @property groupId 변경 그룹 id (`issue_change_group.id`) — 그룹핑 키 겸 동시각 tie-break.
 * @property issueId 변경이 기록된 이슈 UUID. 댓글 소속 대조(FR-CO-02 마스킹)에 쓴다 —
 *           [issueKey] 는 기록 시점 값이라 소속 판정의 근거가 되지 못한다.
 * @property issueKey 기록 시점 이슈 키(예: `BTS-1`). 이슈 이동 후에도 당시 키가 보존된다.
 *           **표시 전용**이다 — 권한 판정에 쓰면 이동된 이슈의 활동이 통째로 사라진다.
 * @property currentIssueKey 현재 이슈 키(`issues.key`). 이슈 단위 VIEW 게이트의 판정 근거다 —
 *           권한 resolver 가 키 접두사로 프로젝트를 해석하므로 **지금** 소속을 반영한
 *           키여야 한다. 이동하지 않은 이슈에서는 [issueKey] 와 같다.
 * @property actorId 변경 주체 UUID. null 이면 시스템 자동 변경.
 * @property createdAt 변경 발생 시각.
 * @property field 변경된 필드 식별자(예: `status`, `assignee`).
 * @property fromValue 변경 전 원시 값.
 * @property toValue 변경 후 원시 값.
 * @property fromLabel 변경 전 표시용 라벨(기록 시점 박제값).
 * @property toLabel 변경 후 표시용 라벨.
 */
data class ProjectActivityRow(
    val groupId: Long,
    val issueId: UUID,
    val issueKey: String,
    val currentIssueKey: String,
    val actorId: UUID?,
    val createdAt: Instant,
    val field: String,
    val fromValue: String?,
    val toValue: String?,
    val fromLabel: String?,
    val toLabel: String?,
)
