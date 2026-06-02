<!-- ADR: 이슈 권한 가드 — IssuePermissionResolver port + AlwaysAllow stub (FR-AU-12 도입까지 임시) -->

# ADR — issue-permission-resolver-port

**일자**. 2026-05-22
**상태**. Accepted (FR-AU-12 머지 시 stub 교체)
**관련 PR**. `issue-tracking-bc-fr-is-01-crud`
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

issue-tracking BC 진입 조건 (`docs/plan/product/issue-tracking.md §0`) 은 `identity-access §4.2 PERMISSION 가드` 완료를 명시한다. 그러나 현재 identity-access BC 는 인증 (AuthenticationProvider, Session, PAT, LDAP) 완료 (PR #2~#8) 후 **권한 가드 인프라 (PermissionEvaluator + scope + role-permission 매핑)는 FR-AU-12 후속 PR 예정**.

본 PR 은 진입 조건 결손 상태에서 진행하므로 **임시 권한 가드 전략** 필요.

### 고려한 옵션

**옵션 A — `@PreAuthorize("isAuthenticated()")` 만 부착.**
- 인증 여부만 검증. scope (어느 프로젝트/이슈) / role 검증 없음
- 장점. 코드 변경 최소
- 단점. "로그인만 하면 아무 이슈나 조회/수정 가능" 임시 상태 — 보안 책임 모호. FR-AU-12 시 `@PreAuthorize` 표현식 전부 교체 필요 (호출자 30+ 곳 변경)

**옵션 B — issue-tracking BC 자체 권한 가드 인라인 작성.**
- BC 내부에 임시 권한 검증 함수 작성
- 단점. BC 경계 침범. FR-AU-12 도입 시 인라인 가드를 전부 삭제하고 재배선 — 회귀 위험

**옵션 C — IssuePermissionResolver port-adapter (workflow ADR 일관).**
- project-workflow BC 의 `workflow-bc-cross-bc-port` ADR 과 동일 패턴
- issue-tracking BC 가 port interface 정의 → AlwaysAllow stub 구현 → FR-AU-12 시 identity-access BC 가 실제 adapter 제공
- 장점. 호출자 코드 무변경 (interface 만 의존). BC 경계 보존. 운영 차단 (`@Profile("!prod")`) 보장

## 결정

**옵션 C — IssuePermissionResolver port-adapter 채택.**

### outbound port (issue-tracking 정의)

```kotlin
// backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/port/outbound/IssuePermissionResolver.kt

interface IssuePermissionResolver {
    fun hasPermission(actorId: ActorId, permission: IssuePermission, scope: IssueScope): Boolean
}

enum class IssuePermission {
    VIEW,
    CREATE,
    UPDATE,
    TRANSITION,
    SOFT_DELETE,
    HARD_DELETE
}

sealed interface IssueScope {
    object Global : IssueScope
    data class Project(val key: String) : IssueScope
    data class Issue(val key: String) : IssueScope
}
```

`ActorId` 는 identity-access 의 기존 `UserId` (UUID) 를 재사용 — 둘 다 SPI 수준에서 UUID 별칭.

### stub 구현 (본 PR)

```kotlin
// backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/adapter/outbound/AlwaysAllowIssuePermissionResolver.kt

@Component
@Profile("!prod")
class AlwaysAllowIssuePermissionResolver : IssuePermissionResolver {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun hasPermission(actorId: ActorId, permission: IssuePermission, scope: IssueScope): Boolean {
        log.warn(
            "AlwaysAllowIssuePermissionResolver: granting {} on {} to actor {} — stub (FR-AU-12 미도입)",
            permission, scope, actorId
        )
        return true
    }
}
```

- `@Profile("!prod")` 로 운영(prod) 환경 부팅 차단 — workflow `AlwaysAllowPermissionResolver` 패턴 동일
- WARN 로그 — 호출 빈도가 높으면 dev/staging 에서도 잡음 가능. 통합 테스트에서 INFO 로그 어설션 권장
- prod profile 에서 `IssuePermissionResolver` Bean 없으면 부팅 실패 — Spring `@Autowired` 미해소 BeanCreationException

### 호출 위치

`IssueApplicationService` 의 각 메서드 진입 직후. `@PreAuthorize` 표현식 대체 (`@PreAuthorize` 자체는 미사용 — 표현식 SpEL 파싱 대비 명시적 메서드 호출 선호).

```kotlin
@Service
class IssueApplicationService(
    private val permissionResolver: IssuePermissionResolver,
    ...
) {
    @Transactional
    fun createIssue(actor: ActorId, request: CreateIssueRequest): IssueKey {
        if (!permissionResolver.hasPermission(actor, IssuePermission.CREATE, IssueScope.Project(request.projectKey))) {
            throw IssueAccessDeniedException(actor, IssuePermission.CREATE, IssueScope.Project(request.projectKey))
        }
        // ... 이슈 생성 로직
    }
}
```

### 정정 (2026-06-02, FR-PM-02 / PR #53)

본 ADR은 stub 교체 시점을 "FR-AU-12"로 적었으나, **FR-PM-02(이슈 등록/수정/삭제 권한 분리)가 권한 스킴 인프라(`permission_schemes` + `role_permissions` + `project_permission_scheme`)를 구축하면서 그 stub 교체를 흡수**했다. 즉 실무상 FR-AU-12 ≡ FR-PM-02. 교체 adapter는 `users.roles`가 아니라 **FR-PM-01 `project_memberships`(ProjectRole 2종) 멤버 게이트 + `role_permissions` 매트릭스**로 평가한다. 계약 타입(IssuePermissionResolver/IssuePermission/IssueScope)은 shared-kernel `com.bts.shared.permission`으로 이전됐고(배선 B) 시그니처는 `hasPermission(actorId: UUID, …)`로 변경됐다. 상세는 [issue-permission-scheme-model](../decisions/2026-06-02-issue-permission-scheme-model.md). 아래 "FR-AU-12 도입 시 교체 흐름"의 `users.roles` 표기는 stale — 실제는 멤버십 기반.

### FR-AU-12 도입 시 교체 흐름

1. identity-access BC 가 `IdentityAccessIssuePermissionResolver` (`@Component @Profile("prod")`) 구현 — `users.roles` + `role_permissions` 테이블 조회 후 평가
2. `AlwaysAllowIssuePermissionResolver` 의 `@Profile("!prod")` 가 dev/staging 에서만 작동 — prod 는 새 adapter 사용
3. `AlwaysAllowIssuePermissionResolver` 제거 시점은 dev/staging 도 새 adapter 검증 완료 후 (운영 검증 완료 + 1주 대기 권장)

### ArchUnit 강제

PR #10 의 `workflow-bc-cross-bc-port` 와 동일 룰:
- `IssueApplicationService` (`Service` 계층) 는 `IssuePermissionResolver` interface 만 의존. 구체 구현체 import 금지
- 외부 BC (`com.bts.identity.*`) 의 클래스를 issue-tracking 에서 직접 import 시 빌드 실패

## 결과

### 긍정

- **BC 경계 보존** — issue-tracking 이 identity-access 의 내부 구조를 모름. interface 만 의존
- **호출자 코드 무변경 보장** — FR-AU-12 도입 시 issue-tracking 측 변경 0건. adapter 추가만으로 교체 완료
- **운영 안전성** — `@Profile("!prod")` 로 stub 의 운영 노출 차단
- **회귀 가드** — ArchUnit 룰로 빌드 시 BC 격리 검증

### 부정 / 위험

- **stub 시기 dev/staging 권한 검증 부재** — "로그인하면 무엇이든 가능" 상태가 dev/staging 에서 지속. QA E2E 시 권한 케이스를 통합 테스트 (MockK) 로만 검증, 실제 E2E 는 FR-AU-12 후
- **interface 진화 비용** — `IssuePermission` enum / `IssueScope` 분기 추가 시 stub + 실제 adapter 동시 갱신. 본 PR scope 의 6개 permission 으로 시작
- **운영 차단 의존성** — `@Profile("!prod")` 누락 시 stub 이 운영에 노출될 수 있음. prod 배포 직전 profile 검증 체크리스트 (`docs/sdd/16-infra.md` 후속) 권장

## 관련

- `docs/plans/2026-05-22-issue-tracking-bc-fr-is-01-crud.md` §도메인 정리 §ADR-2
- `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md` — port-adapter 원형 패턴
- `docs/plan/product/issue-tracking.md §0` — 진입 조건 (PERMISSION 가드 미완 명시)
- `docs/plan/product/identity-access.md` — FR-AU-12 권한 가드 BC 후속 (본 ADR 의 stub 교체 시점)
