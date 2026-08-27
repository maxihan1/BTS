// 워크플로우 초안 CRUD 와 「기본값으로 복원」

package com.bts.workflow.application

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.domain.DraftRuleDto
import com.bts.workflow.domain.DraftStateDto
import com.bts.workflow.domain.DraftTransitionDto
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.repository.WorkflowDraftRepository
import com.bts.workflow.repository.WorkflowPublishRepository
import com.bts.workflow.repository.WorkflowVersionRow
import com.bts.workflow.seed.StandardWorkflowDefaults
import com.bts.workflow.seed.WorkflowYamlDto
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 초안 조회·저장·폐기와 「기본값으로 복원」.
 *
 * ### 초안이 없으면 지금 정의로 시작한다
 * 편집기를 열 때 빈 화면을 주지 않는다. Jira 도 편집기를 열면 지금 정의가 채워진 채로 시작한다.
 * 이때 **DB 에 초안을 만들지는 않는다** — 열어만 보고 닫은 워크플로우에 초안이 남으면 「편집 중」
 * 표시가 거짓이 된다. 실제 행은 첫 저장에서 생긴다.
 *
 * ### base_version 은 편집기가 실어 보낸다 — 서버가 다시 읽지 않는다
 * 낙관적 락의 기준은 「**편집기가 무엇을 보고 있었는가**」이고, 서버는 그것을 재구성할 수 없다.
 * 저장 시점에 현재 버전을 다시 읽어 앵커로 삼으면 A 가 초안을 뜨고 → B 가 발행하고 → A 가 저장하는
 * 순서에서 base 가 새 버전으로 올라가 A 의 발행이 **B 의 변경을 조용히 덮어쓴다.**
 *
 * 「이미 초안이 있으면 그 값을 유지한다」만으로는 부족하다 — **발행이 초안 행을 지우기 때문에**
 * 「초안 없음」은 편집 시작 직후뿐 아니라 남이 방금 발행한 직후에도 성립하고, 정확히 그 자리가
 * 위 사고가 나는 자리다. 그래서 요청이 앵커를 싣고, 유지는
 * [com.bts.workflow.repository.WorkflowDraftRepository.upsert] 가 `DO UPDATE` 에서 그 컬럼을
 * 빼는 것으로 SQL 이 강제한다.
 */
@Service
class WorkflowDraftService(
    private val draftRepository: WorkflowDraftRepository,
    private val publishRepository: WorkflowPublishRepository,
    private val currentDefinitionReader: CurrentDefinitionReader,
    private val standardDefaults: StandardWorkflowDefaults,
    private val permissionResolver: WorkflowDefinitionPermissionResolver,
) {
    /**
     * 초안을 돌려준다. 없으면 지금 발행된 정의를 초안 형태로 돌려준다(저장하지는 않는다).
     *
     * @throws WorkflowNotFoundException 워크플로우가 없거나 소프트 삭제됐을 때
     */
    @Transactional(readOnly = true)
    fun get(
        actorId: UUID,
        key: String,
    ): DraftView {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflow = requireLive(key)
        val stored = draftRepository.findByWorkflowId(workflow.id)
        if (stored != null) {
            return DraftView(definition = stored.definition, baseVersion = stored.baseVersion, exists = true)
        }

        val current =
            currentDefinitionReader.read(key)
                ?: throw WorkflowInvalidRequestException(key, "발행된 정의를 읽을 수 없다")
        return DraftView(definition = current, baseVersion = workflow.version, exists = false)
    }

    /**
     * 초안을 저장한다. 발행 시 터질 정의는 여기서 먼저 막는다.
     *
     * @param baseVersion 편집기가 이 정의를 만들 때 보고 있던 `workflows.version`.
     *   초안 행이 새로 생길 때만 앵커로 기록된다(이미 있으면 그 행의 값이 그대로 남는다).
     * @throws WorkflowInvalidRequestException 정의가 invariant 를 어겼을 때, 또는 편집기가 아직
     *   존재하지 않는 미래 버전을 앵커로 주장할 때
     */
    @Transactional
    fun save(
        actorId: UUID,
        key: String,
        definition: WorkflowDraftDefinition,
        baseVersion: Long,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflow = requireLive(key)
        validate(key, definition)
        requireAnchorNotAhead(key, baseVersion, workflow.version)

        // 발행에서 버려질 값은 저장 단계에서 막는다 — 저장 200 뒤 발행에서 터지면 관리자가 고칠
        // 방법을 모르는 막다른 길이 되고, 그대로 스냅샷에 실리면 감사 기록이 거짓이 된다.
        requireKeyMatchesPath(key, definition)
        requireStatesMatchCatalog(
            key,
            definition,
            publishRepository.findCatalogStatuses(definition.states.map { it.key }),
        )

        draftRepository.upsert(workflow.id, definition.copy(key = key), baseVersion, actorId)
    }

    /**
     * 초안을 폐기한다.
     *
     * @return 지운 초안이 있었으면 true. 호출부가 204 와 404 를 가른다.
     */
    @Transactional
    fun discard(
        actorId: UUID,
        key: String,
    ): Boolean {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        return draftRepository.deleteByWorkflowId(requireLive(key).id)
    }

    /**
     * YAML 기본값을 초안으로 불러온다. **정규 테이블은 건드리지 않는다.**
     *
     * 되돌림의 주체가 부팅 이벤트에서 사람으로 바뀐 것이 이 기능의 핵심이다
     * (ADR `2026-08-18-workflow-db-as-source-of-truth` §D4). 그래서 복원은 곧바로 반영되지 않고
     * 초안에 담긴다 — 관리자가 내용을 보고 발행해야 운영에 나간다.
     *
     * @throws WorkflowInvalidRequestException `origin` 이 `SEED` 가 아니거나 YAML 이 없을 때
     */
    @Transactional
    fun resetToDefault(
        actorId: UUID,
        key: String,
        baseVersion: Long,
    ): DraftView {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflow = requireLive(key)
        if (workflow.origin != SEED_ORIGIN) {
            throw WorkflowInvalidRequestException(key, "사용자가 만든 워크플로우에는 되돌릴 기본값이 없다")
        }

        val yaml =
            standardDefaults.loadStandardYaml(key)
                ?: throw WorkflowInvalidRequestException(key, "기본값 YAML 을 찾을 수 없다")

        val definition = yaml.toDraftDefinition()
        validate(key, definition)
        // 복원도 편집의 일종이라 앵커 규칙이 같다 — 요청이 실어 온 값을 그대로 넘긴다.
        requireAnchorNotAhead(key, baseVersion, workflow.version)
        draftRepository.upsert(workflow.id, definition, baseVersion, actorId)

        // 저장된 행을 다시 읽어 응답을 만든다 — 두 번째 트랜잭션으로 나가면 그 사이 폐기된 초안을
        // 「있음」으로 보고하게 된다.
        val stored =
            draftRepository.findByWorkflowId(workflow.id)
                ?: error("방금 upsert 한 초안이 같은 트랜잭션에서 읽히지 않는다")
        return DraftView(definition = stored.definition, baseVersion = stored.baseVersion, exists = true)
    }

    // ── 내부 ──────────────────────────────────────────────────────────────────

    private fun requireLive(key: String): WorkflowVersionRow {
        val row = publishRepository.findLiveByKey(key)
        return row ?: throw WorkflowNotFoundException(key)
    }

    /**
     * 편집기가 아직 존재하지 않는 버전을 봤다고 주장하면 거절한다.
     *
     * 앵커는 클라이언트가 정하므로 그 자체로는 신뢰 대상이 아니다. 다만 **미래 버전**은 어떤
     * 정상 흐름으로도 나올 수 없어 기계로 가를 수 있고, 이 한 줄이 「큰 수를 실어 보내 락을 넘긴다」는
     * 가장 싼 우회를 닫는다. 과거 버전은 정상이다 — 그 초안은 발행에서 409 로 막힌다.
     */
    private fun requireAnchorNotAhead(
        key: String,
        baseVersion: Long,
        currentVersion: Long,
    ) {
        if (baseVersion > currentVersion) {
            throw WorkflowInvalidRequestException(
                key,
                "편집 기준 버전 $baseVersion 은 아직 존재하지 않는다 (현재 $currentVersion). 화면을 다시 불러올 것",
            )
        }
    }

    /**
     * 초안 저장 시점에 발행과 **같은** 검증을 태운다.
     *
     * 두 경로가 갈리면 「초안은 저장됐는데 발행에서 터지는」 상태가 생기고, 관리자는 고칠 방법을
     * 모르는 막다른 길에 놓인다.
     */
    private fun validate(
        key: String,
        definition: WorkflowDraftDefinition,
    ) {
        try {
            definition.toWorkflow()
        } catch (ex: IllegalArgumentException) {
            // 원인을 cause 로 넘긴다 — 400 응답에는 사람 말만 싣고 스택은 로그에 남는다.
            throw WorkflowInvalidRequestException(key, ex.message ?: "초안 정의가 규칙을 어겼다", ex)
        }
    }

    private companion object {
        /** `workflows.origin` 의 시드 표기. V205 CHECK(SEED|CUSTOM) 과 같은 값이다. */
        const val SEED_ORIGIN = "SEED"
    }
}

/**
 * 초안 조회 결과.
 *
 * @property definition 초안 정의. 저장된 초안이 없으면 지금 발행된 정의다.
 * @property baseVersion 발행 요청에 그대로 실어 보낼 버전.
 * @property exists 저장된 초안이 실제로 있었는지. false 면 화면은 「편집 시작 전」으로 표시한다.
 */
data class DraftView(
    val definition: WorkflowDraftDefinition,
    val baseVersion: Long,
    val exists: Boolean,
)

/**
 * 시드 YAML DTO 를 초안 정의로 옮긴다.
 *
 * 두 타입의 구조가 사실상 같아 이 변환이 자명한 것이 「기본값으로 복원」을 싸게 만든 이유다
 * (`WorkflowDraftDefinition` KDoc 의 타입 분리 근거 참고).
 */
private fun WorkflowYamlDto.toDraftDefinition(): WorkflowDraftDefinition =
    WorkflowDraftDefinition(
        key = key,
        name = name,
        description = description,
        states =
            states.map {
                DraftStateDto(
                    key = it.key,
                    name = it.name,
                    category = it.category,
                    displayOrder = it.displayOrder,
                )
            },
        transitions =
            transitions.map { transition ->
                DraftTransitionDto(
                    from = transition.from,
                    to = transition.to,
                    name = transition.name,
                    kind = transition.kind,
                    validators = transition.validators.map { DraftRuleDto(type = it.type, config = it.config) },
                    postActions = transition.postActions.map { DraftRuleDto(type = it.type, config = it.config) },
                )
            },
    )
