// IssueSnapshotPort prod 어댑터 — automation 조건 평가용 이슈 스냅샷을 기존 actor 가시성 강제 read 경로로 매핑 (FR-AT-03 Task 6)

package com.bts.issue.adapter.outbound.automation

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.shared.issue.IssueSnapshot
import com.bts.shared.issue.IssueSnapshotPort
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [IssueSnapshotPort] prod 구현체 (FR-AT-03 Task 6).
 *
 * automation BC 의 조건 평가기(`ConditionEvaluator`)가 조건 표현식(`{"var":"issue.*"}`)을 평가하기
 * 위해 이슈의 현재 필드 값을 조회할 때 이 어댑터를 경유한다.
 *
 * ### actor 가시성 강제 — 기존 read 경로 재사용(SDD §12.4 "관리자 우회 없음")
 *
 * [fetch] 는 issue-tracking 이 기존에 보유한 단건 조회 유스케이스
 * [IssueApplicationService.findByKey] 를 그대로 재사용한다. 이 메서드는 내부적으로 VIEW 권한 검증
 * (`assertViewIssueOrNotFound`)을 거치며, prod 환경에서는 identity-access 의
 * `IdentityAccessIssuePermissionResolver` 가 보안 등급(security level, FR-PM-06) 멤버십 게이트를
 * 추가로 강제한다 — 등급이 지정된 이슈는 리포터/담당자/그룹/프로젝트 역할/개별 지정 멤버십 중 하나를
 * 충족해야만 조회된다. **관리자 우회가 없다** — `isSystemAdmin` 을 별도로 확인하지 않는다.
 *
 * 새로운 무필터 조회 경로를 만들지 않는다 — [IssueApplicationService.findByKey] 는
 * [com.bts.issue.adapter.outbound.automation.AutomationIssueMutationAdapter](FR-AT-02 Task 7)가
 * 이미 재사용하는 동일한 진입점이다(sibling 선례와 동형).
 *
 * ### null 매핑 — 존재하지 않거나 볼 수 없는 이슈
 *
 * [IssueApplicationService.findByKey] 가 [IssueNotFoundException] 을 던지는 경우는 두 가지다 —
 * 이슈가 실제로 존재하지 않거나, `actorUserId` 가 VIEW 권한(보안 등급 게이트 포함)이 없어 존재 자체가
 * 숨겨진 경우(404 로 통일, 403 이 아님 — FR-PM-05 존재 숨김 정책). 두 경우 모두 [fetch] 는 null 을
 * 반환한다([IssueSnapshotPort.fetch] KDoc "null 의 두 가지 의미" 참조).
 *
 * [com.bts.issue.domain.IssueMovedException](이슈가 이동되어 옛 키가 redirect 체인에 있는 경우)은
 * 캐치하지 않고 그대로 전파한다 — 이 포트의 null 계약에 포함되지 않는 별도 실패 모드다.
 *
 * ### 표현 매핑 — [IssueSnapshot] 계약 고정
 *
 * [IssueResponse.typeName]/[IssueResponse.currentStateKey]/[IssueResponse.priority] 를 각각
 * [IssueSnapshot.type]/[IssueSnapshot.status]/[IssueSnapshot.priority] 에 그대로 매핑한다 —
 * [IssueApplicationService.findByKey] 가 이미 jOOQ 조인으로 typeName 을 해석해 반환하므로 별도 타입
 * 이름 조회가 필요 없다. 표현 형태(이름 문자열/stateKey 문자열/숫자)의 계약은 [IssueSnapshot] KDoc
 * "표현 확정" 절 참조.
 *
 * @param issueApplicationService actor 가시성 강제 단건 조회 위임 대상.
 */
@Component
@Profile("prod")
class AutomationIssueSnapshotAdapter(
    private val issueApplicationService: IssueApplicationService,
) : IssueSnapshotPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [issueKey] 를 [actorUserId] 권한으로 조회해 [IssueSnapshot] 으로 매핑한다.
     *
     * @return 조회된 스냅샷. 이슈가 존재하지 않거나 `actorUserId` 가 볼 수 없으면(보안 등급 게이트
     *   미충족 포함) null.
     */
    @Transactional(readOnly = true)
    override fun fetch(
        actorUserId: UUID,
        issueKey: String,
    ): IssueSnapshot? =
        try {
            issueApplicationService.findByKey(ActorId(actorUserId), IssueKey(issueKey)).toSnapshot()
        } catch (e: IssueNotFoundException) {
            log.debug(
                "automation_snapshot_not_visible actorUserId={} issueKey={} reason={}",
                actorUserId,
                issueKey,
                e.message,
            )
            null
        }

    private fun IssueResponse.toSnapshot(): IssueSnapshot =
        IssueSnapshot(
            key = key,
            projectKey = projectKey,
            type = typeName,
            status = currentStateKey,
            priority = priority,
            assigneeId = assigneeId,
            reporterId = reporterId,
            labels = labels,
            summary = summary,
        )
}
