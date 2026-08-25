// ValidatorAdminService 단위 테스트 — 실 팩토리 dry-run 으로 type/config 검증 계약을 고정한다

package com.bts.workflow.validator

import com.bts.workflow.engine.DefaultWorkflowValidatorFactory
import com.bts.workflow.expression.SpelEvaluator
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.transition.TransitionKeyResolver
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [ValidatorAdminService] 단위 테스트 — 9건. 스펙 §엣지 케이스와 1:1 이다.
 *
 * ### 팩토리만 실물이다 (mock 이 아니다)
 * 형제 `PostActionAdminServiceTest` 는 팩토리까지 mock 으로 두지만 여기서는
 * [DefaultWorkflowValidatorFactory] 실물을 쓰고 그 의존 2개(권한 resolver · SpEL 평가기)만 mock 이다.
 * 이유 3가지.
 * - `not-status-category` 의 알 수 없는 category(E6) 와 `permission-check` 의 scope 생략(E7) 은
 *   **팩토리의 판정**이다. 팩토리를 mock 으로 두면 「던지라고 시킨 것이 던졌다」를 확인할 뿐이라
 *   그 두 테스트가 공허해진다.
 * - 편집 불가 판정이 `instance is CustomExpressionValidator` 타입 검사라, 팩토리가 진짜 인스턴스를
 *   돌려줘야 그 경로가 실행된다.
 * - 지원 type 의 정본은 팩토리의 `when` 분기 하나뿐이다. 테스트가 목록을 흉내 내면 그 순간
 *   대조되지 않는 두 번째 목록이 생긴다.
 *
 * DB 도 Spring 컨텍스트도 뜨지 않는다 — 리포지토리와 전환 해석기는 mock 이다.
 *
 * ### SpEL 은 평가되지 않는다
 * [spelEvaluator] 는 strict mock 이라 `evaluate` 를 한 번이라도 부르면 그 자리에서 실패한다.
 * dry-run 은 인스턴스 생성까지라는 계약(스펙 §제약 4)의 기계 확인이다.
 */
class ValidatorAdminServiceTest {
    private lateinit var repository: ValidatorRepository
    private lateinit var transitionResolver: TransitionKeyResolver
    private lateinit var service: ValidatorAdminService

    /** SpEL 평가기 — strict mock. 생성만 하고 평가하지 않는다는 계약의 감시자다. */
    private val spelEvaluator: SpelEvaluator = mockk()

    /** 권한 resolver — `permission-check` 인스턴스 생성 시 주입될 뿐 호출되지 않는다. */
    private val permissionResolver: PermissionResolver = mockk()

    /**
     * 편집 불가 타입의 런타임 식별자.
     *
     * 리터럴을 적지 않고 [CustomExpressionValidator.type] 에서 읽는다 — 테스트가 문자열을 복사하면
     * 정본이 바뀌었을 때 이 테스트가 먼저 거짓말을 한다.
     */
    private val customExpressionType =
        CustomExpressionValidator(evaluator = spelEvaluator, expression = "true").type

    private val workflowKey = "test-wf"
    private val transitionKey = "open__in_progress"
    private val transitionId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val validatorId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        repository = mockk()
        transitionResolver = mockk()
        service =
            ValidatorAdminService(
                repository = repository,
                factory = DefaultWorkflowValidatorFactory(permissionResolver, spelEvaluator),
                transitionResolver = transitionResolver,
            )

        // 기본 stub: 합성 키 해석 성공 (단건)
        every {
            transitionResolver.resolveTransitionIds(workflowKey, "open", "in_progress")
        } returns listOf(transitionId)
    }

    // ── type / config 검증 (FR-5 · E6 · E7) ───────────────────────────────────

    @Test
    fun `미지원 type 은 ValidatorValidationException`() {
        assertThatThrownBy {
            service.create(workflowKey, transitionKey, "NoSuchValidator", emptyMap(), 0)
        }.isInstanceOf(ValidatorValidationException::class.java)

        // S3 — 400 이면 DB 에 행이 생기지 않는다.
        verify(exactly = 0) { repository.insert(any(), any(), any(), any()) }
    }

    @Test
    fun `RequiredField 에 field 키가 없으면 ValidatorValidationException`() {
        assertThatThrownBy {
            service.create(workflowKey, transitionKey, "RequiredField", emptyMap(), 0)
        }.isInstanceOf(ValidatorValidationException::class.java)

        verify(exactly = 0) { repository.insert(any(), any(), any(), any()) }
    }

    @Test
    fun `not-status-category 의 category 가 알 수 없는 값이면 400`() {
        val config = mapOf<String, Any?>("category" to "NOT_A_CATEGORY")

        assertThatThrownBy {
            service.create(workflowKey, transitionKey, "not-status-category", config, 0)
        }.isInstanceOf(ValidatorValidationException::class.java)

        verify(exactly = 0) { repository.insert(any(), any(), any(), any()) }
    }

    @Test
    fun `permission-check 는 scope 를 생략해도 통과한다`() {
        // E7 — scope 는 선택이고 기본값이 ISSUE 다. 생략을 400 으로 만들면 안 된다.
        val config = mapOf<String, Any?>("permission" to "TRANSITION_ISSUE")
        every {
            repository.insert(transitionId, "permission-check", config, 0)
        } returns ValidatorRow(validatorId, transitionId, "permission-check", config, 0)

        val result = service.create(workflowKey, transitionKey, "permission-check", config, 0)

        assertThat(result.id).isEqualTo(validatorId)
        verify(exactly = 1) { repository.insert(transitionId, "permission-check", config, 0) }
    }

    // ── 편집 불가 타입 (FR-6 · E8) ────────────────────────────────────────────

    @Test
    fun `CustomExpression 생성은 ValidatorTypeNotEditableException`() {
        val config = mapOf<String, Any?>("expression" to "issue.priority == 'HIGH'")

        assertThatThrownBy {
            service.create(workflowKey, transitionKey, customExpressionType, config, 0)
        }.isInstanceOf(ValidatorTypeNotEditableException::class.java)

        verify(exactly = 0) { repository.insert(any(), any(), any(), any()) }
        // 스펙 §제약 4 — dry-run 은 인스턴스 생성까지다. 표현식을 평가하지 않는다.
        verify(exactly = 0) { spelEvaluator.evaluate(any(), any()) }
    }

    @Test
    fun `기존 행의 type 을 CustomExpression 으로 수정해도 400`() {
        // 리뷰 C1 — PUT 은 기존 RequiredField 행의 type 을 바꿔 넣는 경로다.
        // 생성만 막으면 이 문으로 들어온다.
        val existing = ValidatorRow(validatorId, transitionId, "RequiredField", mapOf("field" to "resolution"), 0)
        every { repository.findByTransitionId(transitionId) } returns listOf(existing)
        val config = mapOf<String, Any?>("expression" to "true")

        assertThatThrownBy {
            service.update(workflowKey, transitionKey, validatorId, customExpressionType, config, 0)
        }.isInstanceOf(ValidatorTypeNotEditableException::class.java)

        verify(exactly = 0) { repository.update(any(), any(), any(), any()) }
        verify(exactly = 0) { spelEvaluator.evaluate(any(), any()) }
    }

    @Test
    fun `CustomExpression 행도 목록에 나오고 삭제된다`() {
        // E8 — seed 로 들어간 기존 행을 목록에서 숨기면 관리자가 반쪽 목록에 속는다.
        val row = ValidatorRow(validatorId, transitionId, customExpressionType, mapOf("expression" to "true"), 0)
        every { repository.findByTransitionId(transitionId) } returns listOf(row)
        justRun { repository.deleteById(validatorId) }

        val listed = service.listForTransition(workflowKey, transitionKey)
        assertThat(listed.map { it.type }).containsExactly(customExpressionType)

        service.delete(workflowKey, transitionKey, validatorId)
        verify(exactly = 1) { repository.deleteById(validatorId) }
    }

    // ── 전환 해석 · IDOR (E1 · E4) ────────────────────────────────────────────

    @Test
    fun `합성 키가 2건에 걸리면 ValidatorNotFoundException`() {
        // E1 — V207 ① 이 UNIQUE(workflow, from, to) 를 풀어 생길 수 있는 모양이다.
        // 아무 쪽이나 고르면 규칙이 엉뚱한 전환에 붙고 그 오배치는 화면에서 보이지 않는다.
        every {
            transitionResolver.resolveTransitionIds(workflowKey, "open", "in_progress")
        } returns listOf(transitionId, UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"))

        assertThatThrownBy {
            service.listForTransition(workflowKey, transitionKey)
        }.isInstanceOf(ValidatorNotFoundException::class.java)
    }

    @Test
    fun `남의 전환에 속한 id 로 수정하면 ValidatorNotFoundException`() {
        // E4 — config 는 유효하다. 404 의 원인이 소속 확인 하나뿐이어야 IDOR 차단을 증명한다.
        val foreignId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000099")
        val mine = ValidatorRow(validatorId, transitionId, "RequiredField", mapOf("field" to "resolution"), 0)
        every { repository.findByTransitionId(transitionId) } returns listOf(mine)
        val config = mapOf<String, Any?>("field" to "resolution")

        assertThatThrownBy {
            service.update(workflowKey, transitionKey, foreignId, "RequiredField", config, 0)
        }.isInstanceOf(ValidatorNotFoundException::class.java)

        verify(exactly = 0) { repository.update(any(), any(), any(), any()) }
    }
}
