// 스킴 컨트롤러 전 핸들러를 MockMvc 로 실제 호출해 캡처한 (권한, 스코프)를 분류맵과 대조하는 런타임 봉인

package com.bts.workflow.archunit

import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.scheme.web.CapturingPermissionResolverStub
import com.bts.workflow.scheme.web.ProjectWorkflowSchemeController
import com.bts.workflow.scheme.web.WorkflowSchemeController
import com.bts.workflow.scheme.web.WorkflowSchemeExceptionHandler
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.util.UUID

/**
 * 스킴 컨트롤러의 **모든 요청 매핑 핸들러를 실제로 호출**해, 권한 포트에 넘어간
 * `(permission, scope)` 가 [HANDLER_CLASSIFICATION] 과 일치하는지 대조하는 런타임 봉인.
 *
 * ### 왜 필요한가 — 정적 봉인의 세 한계를 한 번에 닫는다
 * [SchemeHandlerPermissionMatrixTest] 는 바이트코드만 본다. 그래서 셋이 안 잡혔다.
 *
 * 1. **맵 값이 죽은 데이터였다.** 정적 축 2 는 `HANDLER_CLASSIFICATION.containsKey(...)` 로 등록
 *    여부만 봤고, [HandlerClassification] 의 `permission`·`scopeKind` 두 필드를 **읽는 코드가 0곳**
 *    이었다. 이 테스트가 그 값을 처음으로 읽는다 — 맵이 등록부에서 **계약**이 된다.
 * 2. **"안 타는 분기의 가드" 도 정적으로는 통과한다.** 여기서는 요청을 실제로 태워
 *    "캡처 정확히 1건 + 403 응답" 을 요구하므로, 정상 응답 경로 밖에 숨은 가드는 통과하지 못한다.
 * 3. **판별 범위가 패키지 한정이었다.** 여기서는 범위를 패키지가 아니라 **의존 관계**로 잡는다 —
 *    `두 번째 테스트`가 [WorkflowSchemeApplicationService] 를 주입받는 `@RestController` 를
 *    ArchUnit 으로 전 모듈에서 수집해 그 핸들러 집합이 분류맵과 같은지 단언한다.
 *    지금은 2클래스/10핸들러로 같지만, 패키지 밖 컨트롤러가 생기면 자동 편입된다.
 *
 * ### 캡처 방식 — 일부러 거부시킨다
 * [CapturingPermissionResolverStub.denyWith] 를 걸어 캡처 직후 403 을 던지게 한다.
 * 그러면 (a) 유스케이스 스텁을 핸들러마다 구성할 필요가 없고, (b) **403 응답 자체가 "가드가 정상
 * 요청 경로에서 실행됐다" 는 증거**가 된다. 캡처된 인자는 거부 여부와 무관하게 코드가 넘긴 값 그대로다.
 *
 * ### 뮤테이션으로 실증 (2026-07-27, 기준선 EXIT=0 선확인)
 * - M1. `listAssignableSchemes` 의 스코프 `Project(projectKey)` → `Global` 주입 → FAILED.
 * - M2. `update` 의 권한 `MANAGE_SCHEME` → `ASSIGN_SCHEME` 주입 → FAILED.
 * - M3. `get` 의 가드를 `if (schemeKey.isEmpty()) { … }` 로 감싸 **안 타는 분기**로 옮김 →
 *   [SchemeHandlerPermissionMatrixTest] 2건은 **green 유지**, 이 테스트만 FAILED (한계 2 봉합 실증).
 * - M4. [SCANNED_ROOT_PACKAGES] 를 없는 패키지로 교체 → 의존 관계 수집 테스트 FAILED
 *   (빈 집합에서 공허하게 통과하지 않는다).
 *
 * 관련 교훈 — `mutation-site-count-equals-verified-scope` · `archunit-vacuous-rule-silent-pass`.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [SchemeHandlerPermissionRuntimeMatrixTest.TestMvcConfig::class])
@WebAppConfiguration
class SchemeHandlerPermissionRuntimeMatrixTest {
    /** 두 스킴 컨트롤러 + 스킴 예외 핸들러만 올린 최소 MVC 컨텍스트. */
    @Configuration
    @EnableWebMvc
    open class TestMvcConfig {
        val permResolverStub = CapturingPermissionResolverStub()

        @Bean
        open fun workflowSchemeApplicationService(): WorkflowSchemeApplicationService = mockk(relaxed = true)

        @Bean
        open fun workflowSchemePermissionResolver(): WorkflowSchemePermissionResolver = permResolverStub

        @Bean
        open fun projectLookupPort(): ProjectLookupPort = mockk(relaxed = true)

        @Bean
        open fun workflowSchemeController(
            appService: WorkflowSchemeApplicationService,
            permissionResolver: WorkflowSchemePermissionResolver,
        ): WorkflowSchemeController = WorkflowSchemeController(appService, permissionResolver)

        @Bean
        open fun projectWorkflowSchemeController(
            svc: WorkflowSchemeApplicationService,
            resolver: WorkflowSchemePermissionResolver,
            lookupPort: ProjectLookupPort,
        ): ProjectWorkflowSchemeController = ProjectWorkflowSchemeController(svc, resolver, lookupPort)

        @Bean
        open fun workflowSchemeExceptionHandler(): WorkflowSchemeExceptionHandler = WorkflowSchemeExceptionHandler()
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var config: TestMvcConfig

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
    }

    // ── 축 1. 런타임 대조 — 전 핸들러의 캡처값 == 분류맵 ───────────────────────

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `전 핸들러를 실제 호출해 캡처한 permission-scope 가 분류맵과 정확히 일치한다`() {
        assertTrue(HANDLER_CLASSIFICATION.isNotEmpty(), "분류맵이 비어 있다 — 이 대조는 공허하다")

        val captured = mutableMapOf<HandlerKey, HandlerClassification>()

        INVOCATIONS.forEach { (handlerKey, request) ->
            config.permResolverStub.reset()
            config.permResolverStub.denyWith = DENY

            mockMvc.perform(request())
                .andExpect(status().isForbidden)

            assertThat(config.permResolverStub.callCount)
                .describedAs("%s 는 요청 1건당 requirePermission 을 정확히 1회 호출해야 한다", handlerKey)
                .isEqualTo(1)

            val permission =
                requireNotNull(config.permResolverStub.capturedPermission) { "$handlerKey 의 permission 미캡처" }
            val scope = requireNotNull(config.permResolverStub.capturedScope) { "$handlerKey 의 scope 미캡처" }
            captured[handlerKey] = HandlerClassification(permission, scope.toScopeKind())
        }

        // 값 일치 + 누락/과잉 동시 판정 — 맵 전체 비교라 행이 빠지거나 늘어도 실패한다.
        assertThat(captured).isEqualTo(HANDLER_CLASSIFICATION)
    }

    // ── 축 2. 판별 범위 — 패키지가 아니라 의존 관계 ────────────────────────────

    @Test
    fun `WorkflowSchemeApplicationService 를 주입받는 RestController 의 핸들러 집합이 분류맵과 일치한다`() {
        val importedClasses =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages(SCANNED_ROOT_PACKAGES)

        val consumerControllers =
            importedClasses.filter {
                it.isAnnotatedWith(RestController::class.java) && injectsSchemeApplicationService(it)
            }

        assertTrue(
            consumerControllers.isNotEmpty(),
            "WorkflowSchemeApplicationService 를 주입받는 @RestController 를 하나도 못 찾았다 — " +
                "스캔 대상 패키지($SCANNED_ROOT_PACKAGES)를 확인하라. 빈 집합이면 이 규칙은 공허하다",
        )

        val discoveredHandlers =
            consumerControllers
                .flatMap { controller ->
                    controller.methods
                        .filter(isRequestMappingHandler::test)
                        .map { HandlerKey(controller.simpleName, it.name) }
                }.toSet()

        assertThat(discoveredHandlers)
            .describedAs("스킴 유스케이스를 소비하는 컨트롤러의 핸들러는 전부 분류맵에 있어야 하고, 그 반대도 같아야 한다")
            .isEqualTo(HANDLER_CLASSIFICATION.keys)
    }

    private companion object {
        /** @WithMockUser username 으로 쓰는 인증 주체 UUID(RFC 4122 v4 형식). */
        private const val AUTH_ACTOR_UUID_STRING = "22222222-2222-4222-8222-222222222222"

        /** ArchUnit 스캔 루트 — BTS 백엔드의 두 최상위 패키지. */
        private val SCANNED_ROOT_PACKAGES = listOf("com.bts", "com.atlas.bts")

        /** 캡처 직후 던져 403 을 만드는 예외. 캡처값과 무관하므로 고정 인자를 쓴다. */
        private val DENY =
            WorkflowSchemeAccessDeniedException(
                UUID.fromString(AUTH_ACTOR_UUID_STRING),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )

        private const val SCHEME_KEY = "software-scheme"
        private const val PROJECT_KEY = "ATLAS"

        /** 요청 본문이 있는 핸들러용 JSON 헬퍼 — 본문 역직렬화는 핸들러 진입 전에 끝나야 한다. */
        private fun MockHttpServletRequestBuilder.json(body: String) = contentType(APPLICATION_JSON).content(body)

        /**
         * 핸들러 전수 호출표 — (분류맵 키, 요청 빌더).
         *
         * 표의 행 집합 자체가 눈가리개가 되지 않도록, 실제 호출 결과를 분류맵 **전체**와 비교하고
         * (누락 = 실패) 별도로 의존 관계 기반 수집과도 대조한다(과잉/신규 = 실패).
         */
        private val INVOCATIONS: List<Pair<HandlerKey, () -> MockHttpServletRequestBuilder>> =
            listOf(
                HandlerKey("WorkflowSchemeController", "create") to {
                    post("/api/v1/workflow-schemes").json("""{"key":"$SCHEME_KEY","name":"n","description":null}""")
                },
                HandlerKey("WorkflowSchemeController", "list") to { get("/api/v1/workflow-schemes") },
                HandlerKey("WorkflowSchemeController", "get") to { get("/api/v1/workflow-schemes/$SCHEME_KEY") },
                HandlerKey("WorkflowSchemeController", "update") to {
                    put("/api/v1/workflow-schemes/$SCHEME_KEY").json("""{"name":"n","description":null}""")
                },
                HandlerKey("WorkflowSchemeController", "delete") to { delete("/api/v1/workflow-schemes/$SCHEME_KEY") },
                HandlerKey("WorkflowSchemeController", "addMapping") to {
                    post("/api/v1/workflow-schemes/$SCHEME_KEY/mappings")
                        .json("""{"issueTypeKey":null,"workflowKey":"default-workflow"}""")
                },
                HandlerKey("WorkflowSchemeController", "deleteMapping") to {
                    delete("/api/v1/workflow-schemes/$SCHEME_KEY/mappings/1")
                },
                HandlerKey("ProjectWorkflowSchemeController", "assignScheme") to {
                    put("/api/v1/projects/$PROJECT_KEY/workflow-scheme").json("""{"schemeKey":"$SCHEME_KEY"}""")
                },
                HandlerKey("ProjectWorkflowSchemeController", "getAssignedScheme") to {
                    get("/api/v1/projects/$PROJECT_KEY/workflow-scheme")
                },
                HandlerKey("ProjectWorkflowSchemeController", "listAssignableSchemes") to {
                    get("/api/v1/projects/$PROJECT_KEY/assignable-workflow-schemes")
                },
            )

        /** [javaClass] 가 [WorkflowSchemeApplicationService] 를 필드 또는 생성자 파라미터로 주입받는가. */
        private fun injectsSchemeApplicationService(javaClass: JavaClass): Boolean {
            val target = WorkflowSchemeApplicationService::class.java
            val viaField = javaClass.fields.any { it.rawType.isAssignableTo(target) }
            val viaConstructor =
                javaClass.constructors.any { ctor -> ctor.rawParameterTypes.any { it.isAssignableTo(target) } }
            return viaField || viaConstructor
        }
    }
}
