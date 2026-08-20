// issue-tracking 컨트롤러 경로에서 project-workflow 의 AmbiguousTransitionException 이 409 로 나오는지 실측하는 판정 테스트

package com.bts.issue.integration

import com.bts.issue.adapter.inbound.rest.IssueController
import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.IssueKey
import com.bts.shared.workflow.TransitionRequest
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowTransitionPort
import com.bts.workflow.domain.exception.AmbiguousTransitionException
import com.bts.workflow.domain.exception.TransitionCandidate
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator
import org.springframework.http.MediaType
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID
import javax.sql.DataSource
import com.bts.issue.application.TransitionIssueRequest as AppTransitionIssueRequest

/** 전환을 시도할 이슈 키. `IssueKey` 정규식 `^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$` 를 만족한다. */
private const val ISSUE_KEY = "AMB-1"

/** 모호 전환이 발생한 워크플로우 키. */
private const val WORKFLOW_KEY = "software-default"

/** 인증 주체. `CurrentActor.current()` 가 비-nil UUID 를 요구한다. */
private const val ACTOR_ID = "11111111-1111-4111-8111-111111111111"

/** 첫 번째 후보 전환 ID. */
private val CANDIDATE_ONE_ID: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")

/** 두 번째 후보 전환 ID. */
private val CANDIDATE_TWO_ID: UUID = UUID.fromString("44444444-4444-4444-8444-444444444444")

/** 재요청이 성공했을 때 서비스가 돌려주는 응답. 전환 실행 자체는 이 테스트의 판정 대상이 아니다. */
private fun transitionedIssueResponse(): IssueResponse =
    IssueResponse(
        key = ISSUE_KEY,
        id = UUID.fromString("55555555-5555-4555-8555-555555555555"),
        projectKey = "AMB",
        summary = "모호 전환 후보 지목 재요청",
        currentStateKey = "DONE",
        reporterId = UUID.fromString(ACTOR_ID),
        version = 2L,
        createdAt = null,
        updatedAt = null,
        typeId = 1L,
        typeKey = "task",
        typeName = "Task",
    )

/**
 * application DTO 가 실제로 받은 `transitionId` 를 읽는다.
 *
 * 필드를 직접 참조하지 않고 직렬화한 JSON 으로 읽는 이유는, 이 판정이 **DTO 사슬에 필드가 있는지**를
 * 묻기 때문이다. 필드를 직접 참조하면 필드가 없을 때 컴파일이 깨져 red 가 실행 결과로 남지 않는다.
 *
 * @param req 컨트롤러가 application 계층으로 넘긴 전환 요청.
 * @return `transitionId` 문자열. 필드 자체가 없거나 null 이면 null.
 */
private fun ObjectMapper.transitionIdOf(req: AppTransitionIssueRequest): String? {
    val node: JsonNode = valueToTree(req)
    val field = node.path("transitionId")
    return if (field.isMissingNode || field.isNull) null else field.asText()
}

/** 테스트가 던지는 모호 전환 예외. 후보 2건. */
private fun ambiguousTransitionException(): AmbiguousTransitionException =
    AmbiguousTransitionException(
        workflowKey = WORKFLOW_KEY,
        candidates =
            listOf(
                TransitionCandidate(transitionId = CANDIDATE_ONE_ID, name = "조건부 승인"),
                TransitionCandidate(transitionId = CANDIDATE_TWO_ID, name = "즉시 완료"),
            ),
    )

/**
 * 모호 전환 예외를 그대로 던지는 [WorkflowTransitionPort] 스텁.
 *
 * `plan` 은 포트 계약대로 [Propagation.MANDATORY] 다 — 실구현
 * `com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter` 와 같은 트랜잭션 속성을 쓴다.
 * 이 속성 때문에 예외가 **호출자의 공유 트랜잭션**을 rollback-only 로 오염시킨다.
 */
private class AmbiguousThrowingTransitionPort : WorkflowTransitionPort {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun plan(req: TransitionRequest): TransitionResult = throw ambiguousTransitionException()

    override fun availableTransitions(
        req: com.bts.shared.workflow.AvailableTransitionsRequest,
    ): com.bts.shared.workflow.AvailableTransitionsResult = throw UnsupportedOperationException("테스트 미사용")
}

/**
 * 모호 전환 409 의 **cross-BC 실측** (FR-WF-05 결정 D-2, plan Task 1 테스트 ②·③).
 *
 * ## 왜 issue-tracking 에서 재는가
 * `AmbiguousTransitionException` 은 project-workflow 가 던지지만, 실제로 그것을 마주치는 컨트롤러는
 * issue-tracking 의 `IssueController` (`POST /api/v1/issues/{key}/transition`) 다.
 * `WorkflowExceptionHandler` 는 선택자 없는 **광역** `@RestControllerAdvice` 라 BC 경계를 넘어
 * 붙어야 하고, 넘지 못하면 `IssueExceptionHandler` 의 catch-all `@ExceptionHandler(Exception::class)`
 * 이 이 예외를 삼켜 **500** 으로 변질시킨다. 그러면 결정 D-2 가 무효다.
 *
 * ## advice 등록 순서를 손으로 정하지 않는다
 * Spring 의 `ExceptionHandlerExceptionResolver` 는 advice 목록을 **등록 순서대로** 훑어
 * 처음 매칭되는 advice 를 쓴다. 그래서 등록 순서를 테스트가 직접 정하면 원하는 답을 만들어 낼 수 있다.
 * 이 테스트는 그러지 않고 `BtsApplication` 과 **같은 방식**(루트 `com.bts` 컴포넌트 스캔 +
 * `FullyQualifiedAnnotationBeanNameGenerator`)으로 advice 를 등록해 순서 결정을 Spring 에 맡긴다.
 * 남는 차이는 클래스패스 정렬뿐이다 — 조립(`:modules:app`)은 project-workflow 를 issue-tracking
 * 보다 먼저 의존한다(`app/build.gradle.kts:51`·`:52`).
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [AmbiguousTransitionStatusCodeIntegrationTest.AdviceScanConfig::class])
@WebAppConfiguration
class AmbiguousTransitionStatusCodeIntegrationTest {
    /**
     * 조립과 같은 방식으로 `@RestControllerAdvice` 를 전수 스캔하는 테스트 컨텍스트.
     *
     * `useDefaultFilters = false` + advice 어노테이션 include 필터로 컨트롤러·서비스는 빼고
     * advice 만 올린다. `IssueController` 는 전환 경로를 노출하기 위해 명시 빈으로 등록한다.
     */
    @Configuration
    @EnableWebMvc
    @ComponentScan(
        basePackages = ["com.bts"],
        useDefaultFilters = false,
        includeFilters = [
            ComponentScan.Filter(type = FilterType.ANNOTATION, classes = [RestControllerAdvice::class]),
        ],
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator::class,
    )
    open class AdviceScanConfig {
        /** 전환 요청을 받자마자 모호 전환 예외를 던질 서비스 목. 스텁 설정은 `@BeforeEach` 가 한다. */
        @Bean
        open fun issueApplicationService(): IssueApplicationService = mockk()

        @Bean
        open fun issueController(service: IssueApplicationService): IssueController = IssueController(service)
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    private lateinit var mockMvc: MockMvc

    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                ACTOR_ID,
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
        // IssueKey 는 `value class` 라 MockK 의 any() 가 빈 문자열로 인스턴스를 만들다 init require 에 걸린다.
        // 그래서 이 자리만 실제 값을 준다.
        every {
            issueApplicationService.transitionIssue(any(), IssueKey(ISSUE_KEY), any())
        } throws ambiguousTransitionException()
    }

    @Test
    fun `issue 전환 경로에서 던져진 AmbiguousTransitionException 이 500 이 아니라 409 로 나온다`() {
        val body = mapper.writeValueAsString(mapOf("toStatusKey" to "DONE", "expectedVersion" to 1L))

        val result =
            mockMvc.perform(
                post("/api/v1/issues/{key}/transition", ISSUE_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andReturn()

        assertThat(result.response.status)
            .withFailMessage(
                "결정 D-2 판정 — 기대 409, 실제 %d. 응답 본문=%s",
                result.response.status,
                result.response.contentAsString,
            )
            .isEqualTo(409)
        assertThat(result.response.contentAsString).contains("AMBIGUOUS_TRANSITION")
    }

    @Test
    fun `모호 전환 예외 뒤 호출자 트랜잭션이 rollback-only 로 마킹된다`() {
        AnnotationConfigApplicationContext(MandatoryTxConfig::class.java).use { ctx ->
            val port = ctx.getBean(WorkflowTransitionPort::class.java)
            val txTemplate = TransactionTemplate(ctx.getBean(PlatformTransactionManager::class.java))

            assertThatThrownBy {
                txTemplate.execute {
                    // 미래에 누군가 「모호하면 후보를 보여주고 계속 진행」 폴백을 넣는 상황을 흉내낸다.
                    val swallowed = runCatching { port.plan(transitionRequest()) }.exceptionOrNull()
                    assertThat(swallowed).isInstanceOf(AmbiguousTransitionException::class.java)
                    null
                }
            }
                .describedAs("MANDATORY 포트가 던진 예외는 공유 트랜잭션을 rollback-only 로 오염시켜야 한다")
                .isInstanceOf(UnexpectedRollbackException::class.java)
        }
    }

    @Test
    fun `409 후보의 transitionId 를 되실어 재요청하면 200 으로 실행된다`() {
        val received = mutableListOf<AppTransitionIssueRequest>()
        every {
            issueApplicationService.transitionIssue(any(), IssueKey(ISSUE_KEY), capture(received))
        } answers {
            // 엔진과 같은 판단을 흉내낸다 — 후보 지목이 없으면 조용히 고르지 않고 409 로 되돌린다.
            if (mapper.transitionIdOf(received.last()) == null) throw ambiguousTransitionException()
            transitionedIssueResponse()
        }

        val conflict = performTransition(mapOf("toStatusKey" to "DONE", "expectedVersion" to 1L))
        assertThat(conflict.response.status).isEqualTo(409)
        val candidateId = firstCandidateId(conflict.response.contentAsString)
        assertThat(candidateId).isEqualTo(CANDIDATE_ONE_ID.toString())

        val retried =
            performTransition(
                mapOf("toStatusKey" to "DONE", "expectedVersion" to 1L, "transitionId" to candidateId),
            )

        assertThat(retried.response.status)
            .withFailMessage(
                "409 후보 왕복 판정 — 기대 200, 실제 %d. 응답 본문=%s",
                retried.response.status,
                retried.response.contentAsString,
            )
            .isEqualTo(200)
        assertThat(mapper.transitionIdOf(received.last()))
            .describedAs("REST 바디의 transitionId 가 application DTO 까지 그대로 도달해야 한다")
            .isEqualTo(candidateId)
    }

    /** 전환 엔드포인트를 한 번 호출한다. 바디는 맵 그대로 직렬화한다. */
    private fun performTransition(body: Map<String, Any?>) =
        mockMvc.perform(
            post("/api/v1/issues/{key}/transition", ISSUE_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        ).andReturn()

    /** 409 응답 본문에서 첫 후보의 전환 ID 를 읽는다. */
    private fun firstCandidateId(responseBody: String): String =
        mapper.readTree(responseBody).path("candidates").path(0).path("transitionId").asText()

    private fun transitionRequest(): TransitionRequest =
        TransitionRequest(
            workflowKey = WORKFLOW_KEY,
            issueKey = ISSUE_KEY,
            fromStateKey = "IN_REVIEW",
            toStateKey = "DONE",
            actorId = ACTOR_ID,
            issueFields = emptyMap(),
            actorRoles = emptySet(),
            version = 1L,
        )

    /**
     * `@Transactional` 프록시가 실제로 도는 최소 컨텍스트.
     *
     * DB 왕복 없이 트랜잭션 기계만 보면 되므로 [DataSource] 는 목이다 —
     * rollback-only 마킹은 `DataSourceTransactionManager` 의 `ConnectionHolder` 상태로 판정되며
     * 실제 SQL 실행과 무관하다.
     */
    @Configuration
    @EnableTransactionManagement
    open class MandatoryTxConfig {
        @Bean
        open fun dataSource(): DataSource = mockk(relaxed = true)

        @Bean
        open fun transactionManager(dataSource: DataSource) = DataSourceTransactionManager(dataSource)

        @Bean
        open fun workflowTransitionPort(): WorkflowTransitionPort = AmbiguousThrowingTransitionPort()
    }
}
