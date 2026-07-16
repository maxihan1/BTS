// IssueMutationPort prod 어댑터 — automation 액션을 기존 Issue/CommentApplicationService 위임 (FR-AT-02 Task 7)

package com.bts.issue.adapter.outbound.automation

import com.bts.issue.application.AppChangeAssigneeRequest
import com.bts.issue.application.AppChangeVersionsRequest
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.UpdateIssueRequest
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.shared.issue.AddCommentCommand
import com.bts.shared.issue.AssignCommand
import com.bts.shared.issue.IssueMutationPermissionDeniedException
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.MutationResult
import com.bts.shared.issue.SetFieldCommand
import com.bts.shared.issue.SetFixVersionsCommand
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * [IssueMutationPort] prod 구현체 (FR-AT-02 Task 7).
 *
 * automation BC 의 자동화 액션(필드 변경/담당자 배정/댓글 추가/수정 예정 버전 설정)을 issue-tracking 의 기존
 * [IssueApplicationService]/[CommentApplicationService] 유스케이스에 위임한다. 도메인
 * repository 를 직접 호출하지 않으므로 권한 검증([IssueApplicationService.assertPermission] 계열)·
 * 필드 검증·OCC(낙관적 동시성 제어)·이벤트 발행이 모두 기존 경로 그대로 강제된다
 * (PATCH 도메인 우회 금지, DEVELOPMENT.md §회귀 방지).
 *
 * ### actor — cmd.actorUserId 신뢰 (SecurityContext 직접 추출 안 함)
 *
 * 자동화 액션은 pgmq 워커(별도 스레드/프로세스)에서 비동기 실행되므로 SecurityContext 자체가
 * 존재하지 않을 수 있다. adapter 는 SecurityContext 를 읽지 않고 각 커맨드의 `actorUserId` 를
 * 신뢰한다 — 이 값을 채우는 유일한 곳은 automation 룰 실행기이며(위조 차단은 그 책임),
 * adapter 는 이를 그대로 issue-tracking 권한 검증 경로에 전달한다([IssueTransitionAdapter] 의
 * "actor 신뢰" 패턴과 동형). rule actor 의 권한 부족은 각 Application Service 의
 * `assertPermission` 이 [com.bts.issue.domain.IssueAccessDeniedException] 으로 자동 강제한다 —
 * 이 adapter 는 별도의 권한 검증을 수행하지 않는다(fail-closed 는 위임 대상이 이미 보유). 이
 * 도메인 권한 예외는 [runAttempt] 에서 포트 계약의 [com.bts.shared.issue.IssueMutationPermissionDeniedException]
 * 으로 번역해 던진다(FR-AT-02 C3 — 소비자가 클래스명 문자열 매칭 없이 타입으로 권한 거부를 분류).
 *
 * ### 트랜잭션 경계 — `@Transactional` 대신 [TransactionTemplate] (OCC 재시도 격리)
 *
 * [setField]/[assign]/[setFixVersions] 는 OCC 충돌 시 현재 version 을 재조회해 최대 1 회 재시도한다. 이 재시도를
 * 선언적 `@Transactional` 메서드 하나의 몸체 안에서 수행하면 Spring 의 "참여(participating) 트랜잭션
 * 실패 시 전체 rollback-only 전파" 규칙([org.springframework.transaction.support.AbstractPlatformTransactionManager]
 * 기본 동작, `isGlobalRollbackOnParticipationFailure=true`) 에 의해 첫 시도의 OCC 예외가 물리 트랜잭션
 * 전체를 rollback-only 로 오염시킨다. 이후 재시도가 성공해도 최종 commit 시점에
 * [org.springframework.transaction.UnexpectedRollbackException] 이 던져지며 성공한 변경까지 함께
 * 폐기된다(catch 로 예외를 삼켜도 이 플래그는 해제되지 않는다) — 실제 사고로 이어질 수 있는 트랩이다.
 *
 * 이를 피하기 위해 각 시도를 [TransactionTemplate.execute] 로 독립된 물리 트랜잭션으로 감싼다
 * ([IssueTransitionAdapter] 기존 선례와 동형 API). 호출 시점에 앙비언트(ambient) Spring 트랜잭션이
 * 없다는 전제([IssueMutationPort] KDoc "async 안전" 설계) 하에, 실패한 시도는 해당 트랜잭션만
 * 물리적으로 롤백되고 완전히 종료된 뒤에야 재시도(새 트랜잭션)가 시작되므로 오염이 전파되지 않는다.
 *
 * ### dryRun — 실행 후 롤백으로 "진실한 미리보기"
 *
 * [SetFieldCommand.dryRun]/[AssignCommand.dryRun]/[AddCommentCommand.dryRun] 이 true 이면
 * [TransactionTemplate] 이 제공하는 [org.springframework.transaction.TransactionStatus] 에
 * `setRollbackOnly()` 를 호출해 커밋을 막는다. 위임 대상 서비스 호출 자체는 그대로 수행되므로
 * 권한 검증·필드 검증·OCC 버전 검사가 실제 경로와 동일하게 작동한다("진실한 미리보기") — 커밋과
 * 이벤트 발행(같은 트랜잭션의 outbox 성격 pgmq enqueue)만 트랜잭션 롤백으로 함께 폐기된다.
 *
 * @param issueApplicationService 이슈 필드 수정/담당자 배정/조회 위임 대상.
 * @param commentApplicationService 댓글 추가 위임 대상.
 * @param objectMapper [SetFieldCommand.value] JSON 문자열을 필드별 타입으로 디코딩하는 데 사용.
 *   shared-kernel [IssueMutationPort] 는 Jackson 비의존이므로 이 어댑터가 자신의 ObjectMapper 로
 *   디코딩 책임을 진다([SetFieldCommand] KDoc 참조).
 * @param transactionTemplate 시도별 독립 트랜잭션 경계. 위 "트랜잭션 경계" 절 참조.
 *
 * 공개 메서드 4개(setField/assign/addComment/setFixVersions) + private 헬퍼 7개 = 11개.
 * TooManyFunctions 임계값과 정확히 맞물리나, [IssueMutationPort] 4 메서드를 한 어댑터가 위임하는
 * 설계([VersionApplicationService] KDoc 동형 근거) 상 클래스 분리는 과도 — Suppress 처리.
 */
@Suppress("TooManyFunctions")
@Component
@Profile("prod")
class AutomationIssueMutationAdapter(
    private val issueApplicationService: IssueApplicationService,
    private val commentApplicationService: CommentApplicationService,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
) : IssueMutationPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [cmd.field] 를 지원 필드 목록(MVP: summary/description/priority/labels/environment/impact)에서
     * 찾아 [IssueApplicationService.updateIssue] 에 위임한다. OCC 충돌 시 현재 version 을 1 회
     * 재조회해 재시도한다.
     *
     * @throws IllegalArgumentException [cmd.field] 가 지원 목록에 없을 때(사전 검증, DB 접근 없음).
     * @throws IssueVersionConflictException 재시도 후에도 OCC 충돌이 지속될 때.
     */
    override fun setField(cmd: SetFieldCommand): MutationResult {
        // 미지원 필드는 트랜잭션을 열기 전에 걸러낸다 — OCC 재시도 대상이 아닌 결정적 실패.
        validateSupportedField(cmd.field)
        val actor = ActorId(cmd.actorUserId)
        val key = IssueKey(cmd.issueKey)
        val version =
            runWithOccRetry(key, cmd.dryRun) {
                val expectedVersion = issueApplicationService.findByKey(actor, key).version
                val request = buildUpdateRequest(cmd.field, cmd.value, expectedVersion)
                issueApplicationService.updateIssue(actor, key, request).version
            }
        return toResult(key.value, cmd.dryRun, version)
    }

    /**
     * [cmd.assigneeId] 를 [IssueApplicationService.changeAssignee] 에 위임한다(null 이면 해제).
     * OCC 충돌 시 현재 version 을 1 회 재조회해 재시도한다.
     *
     * @throws IssueVersionConflictException 재시도 후에도 OCC 충돌이 지속될 때.
     */
    override fun assign(cmd: AssignCommand): MutationResult {
        val actor = ActorId(cmd.actorUserId)
        val key = IssueKey(cmd.issueKey)
        val version =
            runWithOccRetry(key, cmd.dryRun) {
                val expectedVersion = issueApplicationService.findByKey(actor, key).version
                issueApplicationService
                    .changeAssignee(actor, key, AppChangeAssigneeRequest(cmd.assigneeId, expectedVersion))
                    .version
            }
        return toResult(key.value, cmd.dryRun, version)
    }

    /**
     * [cmd.body] 를 [CommentApplicationService.create] 에 위임한다. 작성자(authorId)는 actor(룰
     * 실행 주체)와 동일하게 전달한다(FR-AT-02 확정 — actor=authorId=rule actor). 댓글은 OCC 버전
     * 대상이 아니므로 재시도가 필요 없고, 결과 [MutationResult.version] 은 항상 null 이다.
     */
    override fun addComment(cmd: AddCommentCommand): MutationResult {
        val actor = ActorId(cmd.actorUserId)
        val key = IssueKey(cmd.issueKey)
        runAttempt(cmd.dryRun) {
            commentApplicationService.create(actor = actor, issueKey = key, body = cmd.body, authorId = actor)
        }
        return MutationResult(issueKey = key.value, applied = !cmd.dryRun, version = null)
    }

    /**
     * [cmd.versionIds] 를 [IssueApplicationService.changeFixVersions] 에 위임한다(전체 교체 —
     * 빈 목록이면 전체 해제). OCC 충돌 시 현재 version 을 1 회 재조회해 재시도한다.
     *
     * @throws IssueVersionConflictException 재시도 후에도 OCC 충돌이 지속될 때.
     */
    override fun setFixVersions(cmd: SetFixVersionsCommand): MutationResult {
        val actor = ActorId(cmd.actorUserId)
        val key = IssueKey(cmd.issueKey)
        val version =
            runWithOccRetry(key, cmd.dryRun) {
                val expectedVersion = issueApplicationService.findByKey(actor, key).version
                issueApplicationService
                    .changeFixVersions(actor, key, AppChangeVersionsRequest(cmd.versionIds, expectedVersion))
                    .version
            }
        return toResult(key.value, cmd.dryRun, version)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [cmd.dryRun] 여부에 따라 dryRun 이면 null, 아니면 시도 결과 version 을 담은 [MutationResult] 를
     * 만든다. [MutationResult.applied] 는 dryRun 의 반대값이다.
     */
    private fun toResult(
        issueKey: String,
        dryRun: Boolean,
        version: Long,
    ): MutationResult = MutationResult(issueKey = issueKey, applied = !dryRun, version = if (dryRun) null else version)

    /**
     * [attempt] 를 실행하고, [IssueVersionConflictException] 이 발생하면 (재조회는 [attempt] 내부에서
     * 매번 [IssueApplicationService.findByKey] 를 다시 호출하므로 자동으로 이뤄진다) 1 회만 재시도한다.
     * 재시도도 실패하면 예외를 그대로 전파한다.
     *
     * 클래스 KDoc "트랜잭션 경계" 절 참조 — 각 시도는 [runAttempt] 를 통해 독립된 물리 트랜잭션에서
     * 실행되므로 첫 시도의 실패가 재시도 트랜잭션을 오염시키지 않는다.
     */
    private fun <T> runWithOccRetry(
        key: IssueKey,
        dryRun: Boolean,
        attempt: () -> T,
    ): T =
        try {
            runAttempt(dryRun, attempt)
        } catch (e: IssueVersionConflictException) {
            log.warn("automation_mutation_occ_retry issueKey={}", key.value, e)
            runAttempt(dryRun, attempt)
        }

    /**
     * [attempt] 를 [transactionTemplate] 이 제공하는 독립 트랜잭션 안에서 실행한다. [dryRun] 이면
     * 트랜잭션에 rollback-only 를 표시해 커밋·이벤트 발행을 막는다(클래스 KDoc "dryRun" 절 참조).
     */
    private fun <T> runAttempt(
        dryRun: Boolean,
        attempt: () -> T,
    ): T =
        transactionTemplate.execute { status ->
            val result =
                try {
                    attempt()
                } catch (e: IssueAccessDeniedException) {
                    // 도메인 권한 예외를 포트 계약의 타입 있는 예외로 번역한다(FR-AT-02 C3) — 소비자
                    // (automation ActionExecutor)가 클래스명 문자열 매칭 없이 권한 거부를 타입으로 분류한다.
                    throw IssueMutationPermissionDeniedException(e.message ?: "이슈 변경 권한이 없습니다", e)
                }
            if (dryRun) {
                status.setRollbackOnly()
            }
            result
        } ?: error("transactionTemplate.execute 반환값이 null 입니다 — unexpected")

    /**
     * [field] 가 setField 가 지원하는 MVP 필드 목록에 속하는지 사전 검증한다.
     *
     * @throws IllegalArgumentException 지원 목록에 없을 때.
     */
    private fun validateSupportedField(field: String) {
        require(field in SUPPORTED_FIELDS) { "unsupported field: $field" }
    }

    /**
     * [field]/[value] 를 [UpdateIssueRequest] 로 변환한다. [value] 는 JSON 인코딩 문자열이며
     * [objectMapper] 로 필드별 타입에 맞게 디코딩한다.
     *
     * MVP 지원 필드: summary(String)/description(String)/priority(Int)/labels(List&lt;String&gt;)/
     * environment(String)/impact(Int). 그 외 필드는 [validateSupportedField] 가 이미 걸러낸다.
     */
    private fun buildUpdateRequest(
        field: String,
        value: String?,
        expectedVersion: Long,
    ): UpdateIssueRequest =
        when (field) {
            FIELD_SUMMARY -> UpdateIssueRequest(summary = decodeValue(value), expectedVersion = expectedVersion)
            FIELD_DESCRIPTION ->
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = expectedVersion,
                    description = decodeValue(value),
                )
            FIELD_PRIORITY ->
                UpdateIssueRequest(summary = null, expectedVersion = expectedVersion, priority = decodeValue(value))
            FIELD_LABELS ->
                UpdateIssueRequest(summary = null, expectedVersion = expectedVersion, labels = decodeStringList(value))
            FIELD_ENVIRONMENT ->
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = expectedVersion,
                    environment = decodeValue(value),
                )
            FIELD_IMPACT ->
                UpdateIssueRequest(summary = null, expectedVersion = expectedVersion, impact = decodeValue(value))
            // validateSupportedField 가 setField 진입 시점에 이미 걸러내므로 도달하지 않는다.
            else -> throw IllegalArgumentException("unsupported field: $field")
        }

    /**
     * [value](JSON 인코딩 문자열)를 [objectMapper] 로 [T] 타입으로 디코딩하는 공통 헬퍼.
     * null 이면 null 을 반환한다. [T] 가 `List&lt;String&gt;` 처럼 제네릭 컬렉션이면 타입 소거
     * 때문에 정확히 디코딩되지 않으므로 [decodeStringList] 를 대신 사용한다.
     */
    private inline fun <reified T> decodeValue(value: String?): T? {
        return value?.let { objectMapper.readValue(it, T::class.java) }
    }

    private fun decodeStringList(value: String?): List<String>? =
        value?.let { objectMapper.readValue(it, object : TypeReference<List<String>>() {}) }

    private companion object {
        const val FIELD_SUMMARY = "summary"
        const val FIELD_DESCRIPTION = "description"
        const val FIELD_PRIORITY = "priority"
        const val FIELD_LABELS = "labels"
        const val FIELD_ENVIRONMENT = "environment"
        const val FIELD_IMPACT = "impact"
        val SUPPORTED_FIELDS =
            setOf(FIELD_SUMMARY, FIELD_DESCRIPTION, FIELD_PRIORITY, FIELD_LABELS, FIELD_ENVIRONMENT, FIELD_IMPACT)
    }
}
