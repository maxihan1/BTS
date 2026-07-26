// WorkflowSchemeController — Scheme CRUD 5 endpoint + Mapping CRUD 2 endpoint MockMvc 슬라이스 테스트 (Task 24, Task 25)

package com.bts.workflow.scheme.web

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.exception.MappingDefaultDuplicateException
import com.bts.workflow.scheme.exception.MappingDuplicateException
import com.bts.workflow.scheme.exception.SchemeInUseException
import com.bts.workflow.scheme.exception.SchemeStandardNotDeletableException
import com.bts.workflow.scheme.exception.WorkflowSchemeNotFoundException
import com.bts.workflow.scheme.web.dto.MappingResponseDetail
import com.bts.workflow.scheme.web.dto.WorkflowSchemeDetailResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.time.Instant
import java.util.UUID

/**
 * WorkflowSchemeController — Scheme CRUD 5 endpoint MockMvc 슬라이스 테스트.
 *
 * 테스트 케이스.
 * - C1. POST   /api/v1/workflow-schemes                   — 201 Created
 * - C2. GET    /api/v1/workflow-schemes                   — 200 목록
 * - C3. GET    /api/v1/workflow-schemes/{schemeKey}       — 200 단건
 * - C4. PUT    /api/v1/workflow-schemes/{schemeKey}       — 200 수정
 * - C5. DELETE /api/v1/workflow-schemes/{schemeKey}       — 204 삭제
 * - C6. DELETE 표준 스킴 차단 — 403 SCHEME_STANDARD_NOT_DELETABLE
 * - C7. DELETE 사용 중 스킴 차단 — 409 SCHEME_IN_USE
 * - C8. GET 없는 스킴 — 404 SCHEME_NOT_FOUND
 * - P1. permissionResolver.requirePermission(MANAGE_SCHEME, Global) wiring 검증
 *
 * ### inline value class MockK 주의
 * MockK 의 `any()` 매처가 ActorId / WorkflowSchemeKey 같은 inline value class 의
 * 서명값을 생성할 때 UUID/regex 검증에 실패한다.
 * 이를 회피하기 위해 every{} 등록에서 파라미터를 모두 명시적 값으로 지정한다.
 * permissionResolver 는 relaxed = true mock 이므로 별도 등록 없이 no-op 으로 동작한다.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [WorkflowSchemeControllerTest.TestMvcConfig::class])
@WebAppConfiguration
class WorkflowSchemeControllerTest {
    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    open class TestMvcConfig {
        @Bean
        open fun applicationService(): WorkflowSchemeApplicationService = mockk()

        @Bean
        open fun permissionResolver(): WorkflowSchemePermissionResolver = mockk(relaxed = true)

        @Bean
        open fun workflowSchemeController(
            svc: WorkflowSchemeApplicationService,
            resolver: WorkflowSchemePermissionResolver,
        ): WorkflowSchemeController = WorkflowSchemeController(svc, resolver)

        @Bean
        open fun workflowSchemeExceptionHandler(): WorkflowSchemeExceptionHandler = WorkflowSchemeExceptionHandler()
    }

    @Autowired
    private lateinit var wac: WebApplicationContext

    @Autowired
    private lateinit var applicationService: WorkflowSchemeApplicationService

    @Autowired
    private lateinit var permissionResolver: WorkflowSchemePermissionResolver

    private lateinit var mockMvc: MockMvc
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    /** mutating 테스트의 @WithMockUser username — 인증 주체 UUID(v4 형식). */
    private val authActor = ActorId(AUTH_ACTOR_UUID_STRING)

    // 컨트롤러가 권한 포트에 넘기는 actor.toUuid() 결과 — verify 블록 기대값.
    private val authActorUuid = UUID.fromString(AUTH_ACTOR_UUID_STRING)

    @BeforeEach
    fun setUp() {
        clearMocks(permissionResolver) // 신규 — 없으면 아래 throws 스터빙이 클래스 전체로 샌다
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build()
    }

    // ── C1. POST /api/v1/workflow-schemes — 201 Created ──────────────────────

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `POST 스킴 생성 — 201 Created + 응답 body key 포함`() {
        val scheme = buildScheme("team-a-scheme", "팀 A 스킴")
        every {
            applicationService.create(
                actor = authActor,
                key = WorkflowSchemeKey("team-a-scheme"),
                name = "팀 A 스킴",
                description = "설명",
            )
        } returns scheme

        val body =
            mapOf(
                "key" to "team-a-scheme",
                "name" to "팀 A 스킴",
                "description" to "설명",
            )

        mockMvc.perform(
            post("/api/v1/workflow-schemes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.key").value("team-a-scheme"))
            .andExpect(jsonPath("$.data.name").value("팀 A 스킴"))
    }

    // ── C2. GET /api/v1/workflow-schemes — 200 목록 ───────────────────────────

    @Test
    fun `GET 스킴 목록 — 200 + data 배열`() {
        val schemes =
            listOf(
                buildSchemeDetail(key = "software-scheme", name = "Software 스킴"),
                buildSchemeDetail(key = "simple-scheme", name = "단순 스킴"),
            )
        every { applicationService.listWithCounts() } returns schemes

        mockMvc.perform(get("/api/v1/workflow-schemes").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data").isArray)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].key").value("software-scheme"))
    }

    // ── C3. GET /api/v1/workflow-schemes/{schemeKey} — 200 단건 ──────────────

    @Test
    fun `GET 스킴 단건 — 200 + key 포함`() {
        val schemeDetail =
            buildSchemeDetail(key = "software-scheme", name = "Software 스킴")
        every { applicationService.findDetail(WorkflowSchemeKey("software-scheme")) } returns schemeDetail

        mockMvc.perform(get("/api/v1/workflow-schemes/software-scheme").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("software-scheme"))
            .andExpect(jsonPath("$.data.name").value("Software 스킴"))
    }

    @Test
    fun `GET 스킴 단건 — mappings 리스트 동봉 (task-4 RED)`() {
        val scheme = buildScheme("software-scheme", "Software 스킴")
        val workflowId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
        val schemeWithMappings =
            buildSchemeDetail(
                key = "software-scheme",
                name = "Software 스킴",
                usedByProjectsCount = 2L,
                mappingsCount = 1L,
                mappings =
                    listOf(
                        buildMappingResponse(
                            id = 5L,
                            issueTypeKey = "bug",
                            issueTypeName = "버그",
                            workflowKey = "software-default",
                            workflowName = "소프트웨어 기본",
                        ),
                    ),
            )
        every { applicationService.findDetail(WorkflowSchemeKey("software-scheme")) } returns schemeWithMappings

        mockMvc.perform(get("/api/v1/workflow-schemes/software-scheme").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.usedByProjectsCount").value(2))
            .andExpect(jsonPath("$.data.mappingsCount").value(1))
            .andExpect(jsonPath("$.data.mappings").isArray)
            .andExpect(jsonPath("$.data.mappings[0].issueTypeKey").value("bug"))
            .andExpect(jsonPath("$.data.mappings[0].issueTypeName").value("버그"))
            .andExpect(jsonPath("$.data.mappings[0].workflowKey").value("software-default"))
            .andExpect(jsonPath("$.data.mappings[0].workflowName").value("소프트웨어 기본"))
    }

    @Test
    fun `GET 스킴 목록 — usedByProjectsCount + mappingsCount 카운트 포함 (task-4 RED)`() {
        val schemes =
            listOf(
                buildSchemeDetail(
                    key = "software-scheme",
                    name = "Software 스킴",
                    usedByProjectsCount = 3L,
                    mappingsCount = 4L,
                    mappings = emptyList(),
                ),
                buildSchemeDetail(
                    key = "simple-scheme",
                    name = "단순 스킴",
                    usedByProjectsCount = 0L,
                    mappingsCount = 1L,
                    mappings = emptyList(),
                ),
            )
        every { applicationService.listWithCounts() } returns schemes

        mockMvc.perform(get("/api/v1/workflow-schemes").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].usedByProjectsCount").value(3))
            .andExpect(jsonPath("$.data[0].mappingsCount").value(4))
            .andExpect(jsonPath("$.data[1].usedByProjectsCount").value(0))
    }

    @Test
    fun `GET 스킴 단건 — 없는 키 404 SCHEME_NOT_FOUND`() {
        every { applicationService.findDetail(WorkflowSchemeKey("missing-scheme")) } throws
            WorkflowSchemeNotFoundException(key = "missing-scheme")

        mockMvc.perform(get("/api/v1/workflow-schemes/missing-scheme").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("SCHEME_NOT_FOUND"))
    }

    // ── N4. GET 읽기 endpoint 권한 게이트 (task-1 RED) — 스펙 준수 복원 ────────

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `GET 스킴 목록 — 권한 거부 시 403 + 본문에 스킴 정보 0건`() {
        val schemes =
            listOf(
                buildSchemeDetail(key = "software-scheme", name = "Software 스킴"),
            )
        every { applicationService.listWithCounts() } returns schemes
        every {
            permissionResolver.requirePermission(
                authActorUuid,
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        } throws
            WorkflowSchemeAccessDeniedException(
                authActorUuid,
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )

        val result =
            mockMvc.perform(get("/api/v1/workflow-schemes").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden)
                .andReturn()

        val body = result.response.contentAsString
        assert(!body.contains("software-scheme")) {
            "권한 거부 응답에 스킴 key 가 노출됨: $body"
        }
    }

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `GET 스킴 단건 — 권한 거부 시 403 + 매핑 정보 0건`() {
        val schemeWithMappings =
            buildSchemeDetail(
                key = "software-scheme",
                name = "Software 스킴",
                mappings =
                    listOf(
                        buildMappingResponse(
                            id = 5L,
                            issueTypeKey = "bug",
                            issueTypeName = "버그",
                            workflowKey = "software-default",
                            workflowName = "소프트웨어 기본",
                        ),
                    ),
            )
        every { applicationService.findDetail(WorkflowSchemeKey("software-scheme")) } returns schemeWithMappings
        every {
            permissionResolver.requirePermission(
                authActorUuid,
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        } throws
            WorkflowSchemeAccessDeniedException(
                authActorUuid,
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )

        val result =
            mockMvc.perform(get("/api/v1/workflow-schemes/software-scheme").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden)
                .andReturn()

        val body = result.response.contentAsString
        assert(!body.contains("software-default")) {
            "권한 거부 응답에 workflowKey 가 노출됨: $body"
        }
    }

    // ── C4. PUT /api/v1/workflow-schemes/{schemeKey} — 200 수정 ──────────────

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `PUT 스킴 수정 — 200 + 변경된 name 반영`() {
        val updated = buildScheme("team-a-scheme", "팀 A 스킴 수정")
        every {
            applicationService.update(
                actor = authActor,
                key = WorkflowSchemeKey("team-a-scheme"),
                newName = "팀 A 스킴 수정",
                newDescription = "새 설명",
                newIsDefault = false,
            )
        } returns updated

        val body =
            mapOf(
                "name" to "팀 A 스킴 수정",
                "description" to "새 설명",
            )

        mockMvc.perform(
            put("/api/v1/workflow-schemes/team-a-scheme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.key").value("team-a-scheme"))
            .andExpect(jsonPath("$.data.name").value("팀 A 스킴 수정"))
    }

    // ── C5. DELETE /api/v1/workflow-schemes/{schemeKey} — 204 삭제 ────────────

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `DELETE 스킴 삭제 — 204 No Content`() {
        every {
            applicationService.softDelete(authActor, WorkflowSchemeKey("team-a-scheme"))
        } returns Unit

        mockMvc.perform(delete("/api/v1/workflow-schemes/team-a-scheme"))
            .andExpect(status().isNoContent)
    }

    // ── M1. POST /api/v1/workflow-schemes/{schemeKey}/mappings — 200 매핑 추가 ─

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `POST 매핑 추가 — 200 OK + 저장된 매핑 id 반환`() {
        val workflowId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
        val mapping = buildMapping(id = 10L, issueTypeId = IssueTypeId(1L), workflowId = workflowId)
        every {
            applicationService.addMappingByKeys(
                actor = authActor,
                schemeKey = WorkflowSchemeKey("team-a-scheme"),
                issueTypeKey = "bug",
                workflowKey = "software-default",
            )
        } returns mapping

        val body =
            mapOf(
                "issueTypeKey" to "bug",
                "workflowKey" to "software-default",
            )

        mockMvc.perform(
            post("/api/v1/workflow-schemes/team-a-scheme/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(10))
    }

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `POST 매핑 추가 — issueTypeKey null 은 default mapping — 200 OK`() {
        val workflowId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002")
        val mapping = buildMapping(id = 11L, issueTypeId = null, workflowId = workflowId)
        every {
            applicationService.addMappingByKeys(
                actor = authActor,
                schemeKey = WorkflowSchemeKey("team-a-scheme"),
                issueTypeKey = null,
                workflowKey = "simple",
            )
        } returns mapping

        val body =
            mapOf(
                "issueTypeKey" to null,
                "workflowKey" to "simple",
            )

        mockMvc.perform(
            post("/api/v1/workflow-schemes/team-a-scheme/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(11))
    }

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `POST 매핑 추가 — 중복 시 409 MAPPING_DUPLICATE`() {
        every {
            applicationService.addMappingByKeys(
                actor = authActor,
                schemeKey = WorkflowSchemeKey("team-a-scheme"),
                issueTypeKey = "bug",
                workflowKey = "software-default",
            )
        } throws MappingDuplicateException(schemeKey = "team-a-scheme", issueTypeKey = "bug")

        val body = mapOf("issueTypeKey" to "bug", "workflowKey" to "software-default")

        mockMvc.perform(
            post("/api/v1/workflow-schemes/team-a-scheme/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("MAPPING_DUPLICATE"))
    }

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `POST 매핑 추가 — default 중복 시 409 MAPPING_DEFAULT_DUPLICATE`() {
        every {
            applicationService.addMappingByKeys(
                actor = authActor,
                schemeKey = WorkflowSchemeKey("team-a-scheme"),
                issueTypeKey = null,
                workflowKey = "simple",
            )
        } throws MappingDefaultDuplicateException(schemeKey = "team-a-scheme")

        val body = mapOf("issueTypeKey" to null, "workflowKey" to "simple")

        mockMvc.perform(
            post("/api/v1/workflow-schemes/team-a-scheme/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("MAPPING_DEFAULT_DUPLICATE"))
    }

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `POST 매핑 추가 — permissionResolver MANAGE_SCHEME 호출 검증`() {
        val workflowId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003")
        val mapping = buildMapping(id = 12L, issueTypeId = IssueTypeId(2L), workflowId = workflowId)
        every {
            applicationService.addMappingByKeys(
                actor = authActor,
                schemeKey = WorkflowSchemeKey("team-a-scheme"),
                issueTypeKey = "story",
                workflowKey = "software-default",
            )
        } returns mapping

        val body = mapOf("issueTypeKey" to "story", "workflowKey" to "software-default")

        mockMvc.perform(
            post("/api/v1/workflow-schemes/team-a-scheme/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isOk)

        verify {
            permissionResolver.requirePermission(
                authActorUuid,
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
    }

    // ── M2. DELETE /api/v1/workflow-schemes/{schemeKey}/mappings/{mappingId} — 204 ──

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `DELETE 매핑 삭제 — 204 No Content`() {
        justRun {
            applicationService.deleteMapping(authActor, 42L)
        }

        mockMvc.perform(delete("/api/v1/workflow-schemes/team-a-scheme/mappings/42"))
            .andExpect(status().isNoContent)
    }

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `DELETE 매핑 삭제 — permissionResolver MANAGE_SCHEME 호출 검증`() {
        justRun {
            applicationService.deleteMapping(authActor, 99L)
        }

        mockMvc.perform(delete("/api/v1/workflow-schemes/team-a-scheme/mappings/99"))
            .andExpect(status().isNoContent)

        verify {
            permissionResolver.requirePermission(
                authActorUuid,
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
    }

    // ── C6. DELETE 표준 스킴 차단 — 403 ──────────────────────────────────────

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `DELETE 표준 스킴 — 403 SCHEME_STANDARD_NOT_DELETABLE`() {
        every {
            applicationService.softDelete(authActor, WorkflowSchemeKey("software-scheme"))
        } throws SchemeStandardNotDeletableException(key = "software-scheme")

        mockMvc.perform(delete("/api/v1/workflow-schemes/software-scheme"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("SCHEME_STANDARD_NOT_DELETABLE"))
    }

    // ── C7. DELETE 사용 중 스킴 차단 — 409 ───────────────────────────────────

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `DELETE 사용 중 스킴 — 409 SCHEME_IN_USE`() {
        every {
            applicationService.softDelete(authActor, WorkflowSchemeKey("team-a-scheme"))
        } throws SchemeInUseException(usedByProjects = listOf(1L))

        mockMvc.perform(delete("/api/v1/workflow-schemes/team-a-scheme"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("SCHEME_IN_USE"))
    }

    // ── P1. permissionResolver.requirePermission wiring 검증 ─────────────────

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `POST 스킴 생성 — permissionResolver MANAGE_SCHEME Global scope 호출 검증`() {
        val scheme = buildScheme("team-b-scheme", "팀 B 스킴")
        every {
            applicationService.create(
                actor = authActor,
                key = WorkflowSchemeKey("team-b-scheme"),
                name = "팀 B 스킴",
                description = null,
            )
        } returns scheme

        val body =
            mapOf(
                "key" to "team-b-scheme",
                "name" to "팀 B 스킴",
                "description" to null,
            )

        mockMvc.perform(
            post("/api/v1/workflow-schemes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)

        verify {
            permissionResolver.requirePermission(
                authActorUuid,
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }
    }

    // ── A1. 미인증 mutating 호출 — 401 UNAUTHORIZED ──────────────────────────

    @Test
    fun `POST 스킴 생성 — 미인증 시 401 UNAUTHORIZED`() {
        val body =
            mapOf(
                "key" to "team-a-scheme",
                "name" to "팀 A 스킴",
                "description" to "설명",
            )

        mockMvc.perform(
            post("/api/v1/workflow-schemes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── A2. 인증 주체 actor 결선 — 권한 포트에 인증 UUID 전달 ─────────────────

    @Test
    @WithMockUser(username = AUTH_ACTOR_UUID_STRING)
    fun `POST 스킴 생성 — 권한 포트에 인증 주체 UUID 전달`() {
        val scheme = buildScheme("team-a-scheme", "팀 A 스킴")
        every {
            applicationService.create(
                actor = authActor,
                key = WorkflowSchemeKey("team-a-scheme"),
                name = "팀 A 스킴",
                description = "설명",
            )
        } returns scheme

        val capturedActor = slot<UUID>()
        justRun {
            permissionResolver.requirePermission(
                capture(capturedActor),
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowSchemeScope.Global,
            )
        }

        val body =
            mapOf(
                "key" to "team-a-scheme",
                "name" to "팀 A 스킴",
                "description" to "설명",
            )

        mockMvc.perform(
            post("/api/v1/workflow-schemes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)),
        )
            .andExpect(status().isCreated)

        assert(capturedActor.captured == authActorUuid) {
            "권한 포트에 전달된 actor=${capturedActor.captured}, 기대=$authActorUuid"
        }
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private fun buildMapping(
        id: Long,
        issueTypeId: IssueTypeId?,
        workflowId: UUID,
    ): SchemeIssueTypeMapping =
        SchemeIssueTypeMapping(
            id = id,
            schemeId = WorkflowSchemeId(1L),
            issueTypeId = issueTypeId,
            workflowId = workflowId,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun buildScheme(
        key: String,
        name: String,
    ): WorkflowScheme =
        WorkflowScheme.reconstruct(
            id = WorkflowSchemeId(1L),
            key = WorkflowSchemeKey(key),
            name = name,
            description = null,
            isDefault = false,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            deletedAt = null,
        )

    private fun buildSchemeDetail(
        key: String,
        name: String,
        usedByProjectsCount: Long = 0L,
        mappingsCount: Long = 0L,
        mappings: List<MappingResponseDetail> = emptyList(),
    ): WorkflowSchemeDetailResponse =
        WorkflowSchemeDetailResponse(
            id = 1L,
            key = key,
            name = name,
            description = null,
            isDefault = false,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
            usedByProjectsCount = usedByProjectsCount,
            mappingsCount = mappingsCount,
            mappings = mappings,
        )

    private fun buildMappingResponse(
        id: Long,
        issueTypeKey: String?,
        issueTypeName: String?,
        workflowKey: String,
        workflowName: String,
    ): MappingResponseDetail =
        MappingResponseDetail(
            id = id,
            issueTypeKey = issueTypeKey,
            issueTypeName = issueTypeName,
            workflowKey = workflowKey,
            workflowName = workflowName,
        )

    companion object {
        /** @WithMockUser username 으로 쓰는 인증 주체 UUID(RFC 4122 v4 형식). */
        private const val AUTH_ACTOR_UUID_STRING = "11111111-1111-4111-8111-111111111111"
    }
}
