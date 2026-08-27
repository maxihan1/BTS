// 워크플로우 초안·발행 REST 컨트롤러 — 초안 CRUD · 발행 · 미리보기 · 기본값 복원

package com.bts.workflow.web

import com.bts.workflow.application.WorkflowDraftService
import com.bts.workflow.application.WorkflowPublishService
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.web.dto.DraftResponse
import com.bts.workflow.web.dto.PublishPreviewResponse
import com.bts.workflow.web.dto.PublishRequest
import com.bts.workflow.web.dto.PublishResponse
import com.bts.workflow.web.dto.ResetToDefaultRequest
import com.bts.workflow.web.dto.SaveDraftRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 초안 편집과 발행 API.
 *
 * ### 왜 [WorkflowController] 와 나뉘어 있나
 * 그 컨트롤러는 `@Suppress("TooManyFunctions")` 로 detekt 한도(11)에 **이미 닿아 있고**, KDoc 이
 * 분리를 「다음 PR 의 몫」으로 명시해 뒀다. 경로도 `/draft`·`/publish` 하위라 자원이 다르다 —
 * `WorkflowStatusCompositionController` 가 세운 「하위 경로 단위로 나눈다」 기준을 그대로 따른다.
 *
 * ### 권한
 * 초안 편집은 `WorkflowDefinitionPermission.UPDATE`, 발행은 `PUBLISH` 다. 판정은 서비스 계층이
 * 하고 이 컨트롤러는 행위자만 넘긴다 — 이 BC 의 다른 컨트롤러와 같은 배치다.
 */
@RestController
@RequestMapping("/api/v1/workflows/{key}")
class WorkflowDraftController(
    private val draftService: WorkflowDraftService,
    private val publishService: WorkflowPublishService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 초안을 읽는다. 저장된 초안이 없으면 지금 발행된 정의를 담아 돌려준다 — 편집기를 빈 화면으로
     * 열지 않기 위해서다. 이때 DB 에 초안을 만들지는 않는다.
     */
    @GetMapping("/draft")
    fun getDraft(
        @PathVariable key: String,
    ): DataResponse<DraftResponse> {
        val actor = CurrentActor.current()
        val view = draftService.get(actor.toUuid(), key)
        return DataResponse(
            DraftResponse(definition = view.definition, baseVersion = view.baseVersion, exists = view.exists),
        )
    }

    /**
     * 초안을 저장한다(자동 저장 포함). 발행에서 터질 정의는 여기서 먼저 400 으로 막는다.
     *
     * 본문이 `baseVersion` 을 함께 싣는다 — 낙관적 락의 기준은 「**편집기가 무엇을 보고 있었는가**」이고
     * 서버는 그것을 재구성할 수 없다. 서버가 저장 시점에 현재 버전을 다시 읽으면, 남이 방금 발행해
     * 초안 행이 사라진 직후의 자동 저장 한 번으로 락이 풀린다.
     */
    @PutMapping("/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun saveDraft(
        @PathVariable key: String,
        @RequestBody request: SaveDraftRequest,
    ) {
        val actor = CurrentActor.current()
        log.info(
            "WorkflowDraftController.saveDraft workflow={} states={} baseVersion={}",
            key,
            request.definition.states.size,
            request.baseVersion,
        )
        draftService.save(actor.toUuid(), key, request.definition, request.baseVersion)
    }

    /**
     * 초안을 폐기한다. 정규 테이블은 그대로다.
     *
     * 초안이 없으면 404 다. 없는 것을 지웠다고 204 를 주면 화면이 「폐기됨」을 표시하고 관리자는
     * 초안이 있었다고 오해한다.
     */
    @DeleteMapping("/draft")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun discardDraft(
        @PathVariable key: String,
    ) {
        val actor = CurrentActor.current()
        log.info("WorkflowDraftController.discardDraft workflow={}", key)
        if (!draftService.discard(actor.toUuid(), key)) {
            throw WorkflowInvalidRequestException(key, "폐기할 초안이 없다")
        }
    }

    /**
     * 발행하지 않고 무엇이 바뀌는지만 계산한다.
     *
     * 화면이 발행 버튼을 누르기 전에 이 결과로 이관 모달을 미리 띄운다 — 눌러 보고 409 를 받는
     * 것보다 낫다. Jira 의 `validateOnly` 에 해당하며, 응답 형태가 발행과 달라 경로를 나눴다.
     */
    @PostMapping("/publish/preview")
    fun previewPublish(
        @PathVariable key: String,
    ): DataResponse<PublishPreviewResponse> {
        val actor = CurrentActor.current()
        val preview = publishService.preview(actor.toUuid(), key)
        return DataResponse(
            PublishPreviewResponse(
                baseVersion = preview.baseVersion,
                currentVersion = preview.currentVersion,
                removedStatusKeys = preview.removedStatusKeys,
                pendingIssueCounts = preview.pendingIssueCounts,
            ),
        )
    }

    /**
     * 초안을 발행한다.
     *
     * 빠지는 상태에 이슈가 남아 있으면 409 이고, 응답이 상태별 잔여 건수를 알려준다.
     * 그 사이 남이 먼저 발행했으면 역시 409 다.
     */
    @PostMapping("/publish")
    fun publish(
        @PathVariable key: String,
        @RequestBody request: PublishRequest,
    ): DataResponse<PublishResponse> {
        val actor = CurrentActor.current()
        log.info("WorkflowDraftController.publish workflow={} baseVersion={}", key, request.baseVersion)
        val versionNo = publishService.publish(actor.toUuid(), key, request.baseVersion)
        return DataResponse(PublishResponse(versionNo))
    }

    /**
     * YAML 기본값을 **초안으로** 불러온다. 정규 테이블은 건드리지 않는다.
     *
     * 되돌림의 주체가 부팅 이벤트에서 사람으로 바뀐 것이 이 기능의 핵심이라(ADR D4), 눌러도
     * 곧바로 운영에 반영되지 않는다. 관리자가 내용을 보고 발행해야 나간다.
     */
    @PostMapping("/reset-to-default")
    fun resetToDefault(
        @PathVariable key: String,
        @RequestBody request: ResetToDefaultRequest,
    ): DataResponse<DraftResponse> {
        val actor = CurrentActor.current()
        log.info("WorkflowDraftController.resetToDefault workflow={} baseVersion={}", key, request.baseVersion)
        // 한 트랜잭션이 정의와 앵커를 함께 돌려준다 — 두 번 부르면 그 사이 폐기된 초안을
        // 「있음」으로 보고하게 된다.
        val view = draftService.resetToDefault(actor.toUuid(), key, request.baseVersion)
        return DataResponse(
            DraftResponse(definition = view.definition, baseVersion = view.baseVersion, exists = view.exists),
        )
    }
}
