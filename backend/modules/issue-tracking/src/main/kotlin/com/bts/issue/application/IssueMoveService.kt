// IssueMoveService — 이슈 단건 프로젝트 간 이동 실행 유스케이스 (FR-MV-01 Task 7)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueKeyRedirectRepository
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStateCatalog
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 이슈 이동 실행 요청 DTO.
 *
 * Jira 마법사 2단계(매핑 적용 후 실제 이동)에 해당하는 파라미터를 담는다.
 * preview(1단계)에서 사용자가 확인한 매핑 정보를 그대로 전달한다.
 *
 * @param targetProjectKey 이동 대상 프로젝트 키.
 * @param expectedVersion OCC 낙관락 버전. 읽은 version 과 일치해야 이동이 진행된다.
 * @param targetStateKey 대상 프로젝트에서 적용할 상태 키. null 이면 소스 상태 키를 그대로 사용 시도.
 * @param targetStateIsDone 대상 상태가 DONE 카테고리인지 여부. false 이면 resolution_id 를 null 로 clear 한다(C4).
 * @param componentMapping 원본 컴포넌트 UUID → 대상 컴포넌트 UUID 매핑. 값이 null 이면 미매핑(제거).
 * @param affectsVersionMapping 원본 affects-version UUID → 대상 버전 UUID 매핑.
 * @param fixVersionMapping 원본 fix-version UUID → 대상 버전 UUID 매핑.
 * @param additionalCustomFields 대상 프로젝트에서 필수이지만 기존 이슈에 없는 커스텀 필드 추가 값.
 */
data class IssueMoveRequest(
    val targetProjectKey: String,
    val expectedVersion: Long,
    val targetStateKey: String?,
    val targetStateIsDone: Boolean,
    val componentMapping: Map<UUID, UUID?>,
    val affectsVersionMapping: Map<UUID, UUID?>,
    val fixVersionMapping: Map<UUID, UUID?>,
    val additionalCustomFields: Map<String, Any?>,
)

/**
 * 이슈 단건 프로젝트 간 이동 유스케이스.
 *
 * Jira 마법사 2단계(매핑 적용) 에 해당하며, 다음 불변식을 단일 @Transactional 안에서 원자적으로 보장한다.
 *
 * ### 불변식
 * 1. issues.id 불변 — 이슈 고유 식별자(UUID)는 프로젝트 이동 후에도 바뀌지 않는다.
 * 2. 키 발번 — 대상 프로젝트의 key_sequence 를 pg_advisory_xact_lock 으로 보호하여 중복 없이 증가한다.
 * 3. redirect 영구 보존 — issue_key_redirects 에 (oldKey → newKey) 행을 반드시 삽입한다 (DATA.md §2).
 * 4. resolution_id clear — 대상 상태가 DONE 이 아니면 resolution_id 를 null 로 clear 한다 (C4).
 * 5. parent_id 초기화 — 외부 프로젝트 부모와의 연결을 끊는다.
 */
@Service
@Transactional
@Suppress("TooManyFunctions")
class IssueMoveService(
    private val issueRepository: IssueRepository,
    private val redirectRepository: IssueKeyRedirectRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val workflowKeyResolver: WorkflowKeyResolver,
    private val workflowStateCatalog: WorkflowStateCatalog,
    private val historyRecorder: IssueHistoryRecorder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈를 대상 프로젝트로 이동한다.
     *
     * **단일 @Transactional 안에서 원자적으로 실행된다.**
     *
     * 실행 순서.
     * 1. SELECT FOR UPDATE 비관락 조회 — 미존재 404.
     * 2. OCC: expectedVersion 불일치 → IssueVersionConflictException(409).
     * 3. 권한 검증: 원본 UPDATE + 대상 CREATE.
     * 4. 워크플로우 미설정 검증: resolveExisting null → IssueWorkflowNotConfiguredException(422).
     * 5. IssueMoveOperation.validate — 도메인 규칙 일괄 검증.
     * 6. 대상 키 발번: incrementKeySequence(pg_advisory_xact_lock 포함).
     * 7. issues UPDATE: project_id / key / current_state_key / custom_fields / parent_id=null / version bump.
     * 8. resolution_id C4: 대상 상태 DONE이 아니면 null clear.
     * 9. 조인 테이블 교체: 컴포넌트 / affects-version / fix-version.
     * 10. IssueKeyRedirectRepository.insert(oldKey, newKey) — 리다이렉트 영구 보존.
     * 11. 히스토리 기록.
     *
     * @param actor 이동 행위자.
     * @param issueKey 이동할 이슈 키.
     * @param request 이동 요청 DTO.
     * @return 이동된 이슈의 새 키.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없을 때.
     * @throws com.bts.issue.domain.IssueVersionConflictException OCC 충돌 시.
     * @throws com.bts.issue.domain.IssueAccessDeniedException 권한 없을 때.
     * @throws IssueWorkflowNotConfiguredException 대상 프로젝트 워크플로우 미설정.
     * @throws com.bts.issue.domain.MoveSameProjectException 같은 프로젝트로 이동 시.
     * @throws com.bts.issue.domain.IssueHasSubtasksException 서브태스크 보유 이슈 이동 시.
     * @throws com.bts.issue.domain.InvalidTargetStateException 대상 상태 결정 불가 시.
     * @throws com.bts.issue.domain.InvalidTargetMappingException 컴포넌트/버전 매핑 대상 미존재.
     * @throws com.bts.issue.domain.RequiredFieldMissingException 필수 커스텀필드 누락.
     */
    @Suppress("ThrowsCount", "LongMethod", "TooGenericExceptionCaught")
    fun move(
        actor: ActorId,
        issueKey: IssueKey,
        request: IssueMoveRequest,
    ): IssueKey {
        throw UnsupportedOperationException("IssueMoveService.move — RED 단계: 아직 구현되지 않음")
    }
}
