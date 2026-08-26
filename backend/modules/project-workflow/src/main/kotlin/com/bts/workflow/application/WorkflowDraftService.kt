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
 * ### base_version 은 처음 한 번만 기록한다
 * 저장할 때마다 현재 버전으로 갱신하면 낙관적 락이 무력해진다. A 가 초안을 뜨고 → B 가 발행하고 →
 * A 가 초안을 한 번 더 저장하면 base 가 새 버전으로 올라가 A 의 발행이 **B 의 변경을 조용히
 * 덮어쓴다.** 그래서 이미 초안이 있으면 그 값을 그대로 유지한다.
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
     * @throws WorkflowInvalidRequestException 정의가 invariant 를 어겼을 때
     */
    @Transactional
    fun save(
        actorId: UUID,
        key: String,
        definition: WorkflowDraftDefinition,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflow = requireLive(key)
        validate(key, definition)

        // 이미 있는 초안의 base_version 은 유지한다 — 갱신하면 그 사이 남이 한 발행을 덮어쓰게 된다.
        val baseVersion = draftRepository.findByWorkflowId(workflow.id)?.baseVersion ?: workflow.version
        draftRepository.upsert(workflow.id, definition, baseVersion, actorId)
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
    ): WorkflowDraftDefinition {
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
        // 복원도 편집의 일종이라 base_version 규칙이 같다 — 처음 만들 때만 기록한다.
        val baseVersion = draftRepository.findByWorkflowId(workflow.id)?.baseVersion ?: workflow.version
        draftRepository.upsert(workflow.id, definition, baseVersion, actorId)
        return definition
    }

    // ── 내부 ──────────────────────────────────────────────────────────────────

    private fun requireLive(key: String): WorkflowVersionRow {
        val row = publishRepository.findLiveByKey(key)
        return row ?: throw WorkflowNotFoundException(key)
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
