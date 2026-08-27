// 워크플로우 초안 CRUD 와 「기본값으로 복원」

package com.bts.workflow.application

import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
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
    private val ruleWriter: DraftRuleWriter,
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
     * ### 권한이 `UPDATE` **또는** `PUBLISH` 인 이유
     * 앵커는 write-once 다([WorkflowDraftRepository.upsert] 의 `DO UPDATE` 가 그 컬럼을 뺀다).
     * 그래서 `UPDATE` 만 가진 행위자가 옛 버전을 앵커로 초안을 만들면 그 워크플로우의 발행은
     * **영구 409** 가 되고, 그 상태의 유일한 출구가 초안 폐기다. 폐기에 `UPDATE` 만 요구하면
     * **발행 운영자는 스스로 빠져나올 수 없다** — 악의가 필요 없고, 이전 세션의 앵커를 들고 있는
     * 편집기 탭 하나면 만들어진다.
     *
     * 「발행할 수 있는 사람은 발행을 막고 있는 초안도 치울 수 있다」가 읽기로도 자연스럽다.
     * 둘 다 없으면 거절이다 — 넓힌 것이지 연 것이 아니다.
     *
     * @return 지운 초안이 있었으면 true. 호출부가 204 와 404 를 가른다.
     */
    @Transactional
    fun discard(
        actorId: UUID,
        key: String,
    ): Boolean {
        requireAnyPermission(actorId, WorkflowDefinitionPermission.UPDATE, WorkflowDefinitionPermission.PUBLISH)
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

        // ★ 상태 이름·카테고리는 **카탈로그**에서 가져온다 — YAML 의 값이 아니다.
        //
        // 시드는 YAML 의 이름을 그대로 카탈로그에 심지만, 그 뒤 관리자가 상태 API 로 이름을 바꾸면
        // 둘이 갈린다. 그때 YAML 의 옛 이름을 그대로 초안에 실으면 저장은 되고 **발행에서만**
        // 카탈로그 대조에 걸려, 관리자가 만들지도 않은 값 때문에 막히고 고칠 방법을 모른다.
        //
        // YAML 의 권위는 워크플로우의 **구조**(어떤 상태를 쓰고 어떤 전환이 있는가)이지 표시
        // 이름이 아니다. 이름의 정본은 전역 카탈로그 하나다.
        val definition = yaml.toDraftDefinition().withCatalogStateLabels(publishRepository)
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
     * 후보 권한 중 **하나라도** 있으면 통과한다.
     *
     * 포트가 단일 권한만 받으므로(`WorkflowDefinitionPermissionResolver.requirePermission`) 여기서
     * 조합한다. 전부 실패했을 때는 **첫 번째 후보**의 거절을 그대로 올린다 — 그것이 「원래 요구되는」
     * 권한이고, 마지막 시도의 예외를 올리면 응답이 부차적인 권한 이름을 가리켜 안내가 어긋난다.
     */
    private fun requireAnyPermission(
        actorId: UUID,
        vararg candidates: WorkflowDefinitionPermission,
    ) {
        var first: RuntimeException? = null
        for (candidate in candidates) {
            try {
                permissionResolver.requirePermission(actorId, candidate)
                return
            } catch (ex: WorkflowDefinitionAccessDeniedException) {
                if (first == null) first = ex
            }
        }
        throw first ?: error("권한 후보가 비어 있다 — 호출부가 최소 하나를 넘겨야 한다")
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
        // ★ 규칙 관문도 여기서 태운다. 발행 경로만 태우면 「저장은 204 인데 발행에서 400」이 되고,
        //   그것이 바로 이 KDoc 이 없애겠다고 적은 막다른 길이다 — 문서가 앞서 가면 안 된다.
        try {
            ruleWriter.checkAll(definition)
        } catch (ex: TransitionRuleRejected) {
            throw WorkflowInvalidRequestException(key, ex.message ?: "전환 규칙이 관문을 지나지 못했다", ex)
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
