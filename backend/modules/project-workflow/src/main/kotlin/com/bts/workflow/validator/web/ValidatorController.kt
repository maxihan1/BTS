// 워크플로우 전환 validator CRUD REST 컨트롤러 — MANAGE_SCHEME+Global Guard 선행

package com.bts.workflow.validator.web

import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.scheme.web.DataEnvelope
import com.bts.workflow.validator.ValidatorAdminService
import com.bts.workflow.validator.ValidatorRow
import com.bts.workflow.web.requireManageScheme
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 워크플로우 전환 validator CRUD REST 컨트롤러.
 *
 * 경로: `GET/POST /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators`
 *       `PUT/DELETE .../validators/{id}`
 *
 * 형제인 `PostActionController` 와 세그먼트 규칙이 같다. `transitionKey` 는 전환 id(UUID) 이거나
 * 종전 `fromStateKey__toStateKey` 합성 키이며, 해석은 [ValidatorAdminService] 가 맡는다.
 *
 * ### 권한 Guard 가 리소스 조회보다 먼저다
 * 모든 엔드포인트(**GET 포함**) 는 진입 직후 공용 [requireManageScheme] 로 MANAGE_SCHEME + Global 을
 * 검증한다. 순서가 뒤집히면 403 이 거짓말을 한다 — 없는 워크플로우에는 404, 있는 워크플로우에는
 * 403 이 나가서 권한 없는 사용자가 전환의 실재 여부를 응답만으로 알아낸다(존재 probe).
 * (메모리 permission-assert-before-existence-makes-403-lie · auth-extraction-before-resource-lookup)
 *
 * 가드 구현은 형제 `PostActionController` 와 **한 벌을 공유한다** — 스코프를 좁히는 날 한쪽만
 * 고쳐지는 일이 없어야 하고, 사본이면 두 컨트롤러 테스트가 각자 자기 사본만 지켜 red 가 안 난다.
 *
 * ### 권한 예외 누출 방지
 * [com.bts.shared.permission.WorkflowSchemeAccessDeniedException] 메시지에는 actorId·permission·
 * scope 가 들어 있다. [ValidatorExceptionHandler] 가 그 상세를 로그로만 남기고 응답 본문은 일반
 * 메시지로 바꾼다 (메모리 fr-pm-04-guard-exception-message-http-leak).
 *
 * ### 트랜잭션
 * 컨트롤러는 경계를 열지 않는다. `@Transactional` 은 [ValidatorAdminService] 쪽에만 있다.
 * [phaseOf] 가 부르는 팩토리도 인스턴스를 만들 뿐 `validate` 를 부르지 않으므로 DB 접근도
 * 권한 조회도 SpEL 평가도 일어나지 않는다 ([ValidatorAdminService] dry-run 과 같은 계약).
 *
 * ### `phase` 는 인스턴스에서 읽는다 — 표를 만들지 않는다
 * 응답의 `phase` 는 [WorkflowValidatorFactory] 가 만든 인스턴스의 속성에서 온다. 여기에
 * `type → phase` 표를 두면 팩토리의 `when` 분기와 각 구현체의 `override val phase` 에 이은
 * **세 번째 사본**이 되고, 세 목록은 서로를 검사하지 않으므로 갈린 사실이 드러나지 않는다.
 * 응답이 이 값을 실어야 소비자(화면) 쪽에도 같은 표가 생기지 않는다.
 *
 * @param service validator 관리 서비스.
 * @param permissionResolver MANAGE_SCHEME 권한 평가 outbound port.
 * @param validatorFactory validator 인스턴스 팩토리. `phase` 의 진실 출처다.
 */
@RestController
@RequestMapping("/api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators")
class ValidatorController(
    private val service: ValidatorAdminService,
    private val permissionResolver: WorkflowSchemePermissionResolver,
    private val validatorFactory: WorkflowValidatorFactory,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 전환에 속한 validator 목록을 반환한다.
     *
     * 편집할 수 없는 type 의 행도 숨기지 않는다 — 반쪽 목록은 관리자를 속인다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @return 200 OK + [ValidatorResponse] 목록 (displayOrder ASC). 각 행에 `phase` 가 실린다.
     */
    @GetMapping
    fun list(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
    ): ResponseEntity<DataEnvelope<List<ValidatorResponse>>> {
        permissionResolver.requireManageScheme()
        log.debug("ValidatorController.list workflowKey={} transitionKey={}", workflowKey, transitionKey)
        val rows = service.listForTransition(workflowKey, transitionKey)
        return ResponseEntity.ok(DataEnvelope(rows.map { toResponse(it) }))
    }

    /**
     * validator 를 생성한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 id(UUID) 또는 종전 합성 키.
     * @param request 생성 요청 바디.
     * @return 201 Created + 생성된 [ValidatorResponse].
     */
    @PostMapping
    fun create(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
        @RequestBody request: ValidatorRequest,
    ): ResponseEntity<DataEnvelope<ValidatorResponse>> {
        permissionResolver.requireManageScheme()
        log.info(
            "ValidatorController.create workflowKey={} transitionKey={} type={}",
            workflowKey,
            transitionKey,
            request.type,
        )
        val row = service.create(workflowKey, transitionKey, request.type, request.config, request.displayOrder)
        return ResponseEntity.status(HttpStatus.CREATED).body(DataEnvelope(toResponse(row)))
    }

    /**
     * validator 를 수정한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 id(UUID) 또는 종전 합성 키.
     * @param id 수정할 validator UUID.
     * @param request 수정 요청 바디.
     * @return 200 OK + 수정된 [ValidatorResponse].
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
        @PathVariable id: UUID,
        @RequestBody request: ValidatorRequest,
    ): ResponseEntity<DataEnvelope<ValidatorResponse>> {
        permissionResolver.requireManageScheme()
        log.info(
            "ValidatorController.update workflowKey={} transitionKey={} id={} type={}",
            workflowKey,
            transitionKey,
            id,
            request.type,
        )
        val row = service.update(workflowKey, transitionKey, id, request.type, request.config, request.displayOrder)
        return ResponseEntity.ok(DataEnvelope(toResponse(row)))
    }

    /**
     * validator 를 삭제한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 id(UUID) 또는 종전 합성 키.
     * @param id 삭제할 validator UUID.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
        @PathVariable id: UUID,
    ) {
        permissionResolver.requireManageScheme()
        log.info(
            "ValidatorController.delete workflowKey={} transitionKey={} id={}",
            workflowKey,
            transitionKey,
            id,
        )
        service.delete(workflowKey, transitionKey, id)
    }

    /**
     * 행을 응답 DTO 로 옮기면서 `phase` 를 인스턴스에서 읽어 붙인다.
     *
     * @param row validator 행.
     * @return `phase` 가 채워진(판정 불가 시 `null` 인) 응답 DTO.
     */
    private fun toResponse(row: ValidatorRow): ValidatorResponse = ValidatorResponse.from(row, phaseOf(row))

    /**
     * 행 하나의 평가 시점을 판정한다. **실패를 행 단위로 가둔다** — 한 행이 인스턴스화되지 않아도
     * 목록 전체가 500 이 되지 않고 그 행만 `null` 이 된다.
     *
     * ### `runCatching` 을 쓰지 않는 이유
     * `runCatching` 은 [Throwable] 을 잡아 `Error`(OOM · StackOverflow) 까지 삼키고, 치명적 상황을
     * 「phase 를 모른다」로 위장한다. 여기서 가두려는 것은 [WorkflowValidatorFactory.create] 가
     * 계약상 던지는 [IllegalArgumentException] 하나다 — 미지원 type · 필수 config 키 누락 ·
     * config 타입 불일치 · 알 수 없는 enum 이 전부 그 타입으로 온다. 그 밖의 예외가 올라오면
     * 팩토리 쪽 결함이므로 `null` 로 감추지 않고 500 으로 드러나야 한다.
     *
     * @param row validator 행.
     * @return `"AVAILABILITY"` 또는 `"EXECUTION"`. 인스턴스화 실패 시 `null`.
     */
    private fun phaseOf(row: ValidatorRow): String? {
        return try {
            validatorFactory.create(row.type, row.config).phase.name
        } catch (ex: IllegalArgumentException) {
            log.debug(
                "ValidatorController: phase 판정 불가 id={} type={} reason='{}'",
                row.id,
                row.type,
                ex.message,
            )
            null
        }
    }
}
