<!-- ADR: 워크플로우 스킴 frontend 뷰 layer IssueType cross-BC lookup — outbound port 패턴 -->

# ADR — workflow-scheme-frontend-view-layer-cross-bc-lookup

**일자**. 2026-05-28
**상태**. Accepted
**관련 PR**. `fr-wf-02-d6-ui-crud`
**작성자**. Maxi + Claude (backend-engineer)

## 컨텍스트

FR-WF-02 D6 frontend 가 워크플로우 스킴 매핑 행에 IssueType 이름을 표시해야 한다.
UX 결정 D2 에서 MappingResponse 에 `key + name` 을 포함하는 것으로 결정되었다.

project-workflow BC 는 WorkflowScheme 의 SchemeIssueTypeMapping 을 소유하지만,
IssueType 의 `name` 필드는 issue-tracking BC 가 소유한다.

project-workflow BC 가 view layer 응답 조립 시 IssueType 이름을 포함하려면
issue-tracking BC 에서 IssueType 정보를 read-only 로 조회해야 한다.

### BC 격리 규칙

BTS 의 BC 격리 룰 (CLAUDE.md §핵심 패턴, ArchUnit 강제) 은 아래를 금지한다.

```
project-workflow BC 가 com.bts.issue.* 내부 클래스를 직접 import 하면 ArchUnit 빌드 실패
```

따라서 issue-tracking 의 도메인 엔티티 `IssueType` 을 project-workflow 에서 직접 사용할 수 없다.

### 고려한 대안

**옵션 A — frontend 가 IssueType API 별도 호출.**
매핑 목록 API 에서 ID 만 반환하고, frontend 가 별도 `/api/issue-types` 를 호출해 이름을 조합한다.
탈락 이유. N+1 API 호출 패턴. 화면 로딩 속도 저하, UX D2 결정에 위배.

**옵션 B — MappingResponse 가 ID 만 포함, frontend lookup map 구성.**
frontend 가 모든 IssueType 을 미리 캐싱해두고 응답 ID 를 lookup 한다.
탈락 이유. 프론트엔드 캐시 일관성 문제, 네트워크 부담. 서버에서 응답을 완성하는 것이 더 단순.

**옵션 C — Hexagonal outbound port 패턴.**
project-workflow BC 가 `IssueTypeLookupPort` outbound interface 를 선언하고,
issue-tracking BC 의 `IssueTypeLookupAdapter` 가 구현체를 제공한다.
반환 타입은 shared-kernel `IssueTypeRef` (key + name read-only) 를 사용해
도메인 엔티티 직접 import 를 0건으로 유지한다.
채택. BC 격리 룰 준수 + 단일 API 호출로 응답 완성 + 이전 선례(WorkflowTransitionPort) 와 일관.

## 결정

**Hexagonal outbound port 패턴 채택 (옵션 C).**

### 구성 요소

| 구성 요소 | 위치 | 역할 |
|---|---|---|
| `IssueTypeRef` | `shared-kernel/com.bts.shared.issue` | key+name read-only DTO. BC 격리 계약 타입 |
| `IssueTypeLookupPort` | `project-workflow/com.bts.workflow.scheme.application.port` | project-workflow 가 선언하는 outbound interface |
| `IssueTypeLookupAdapter` | `issue-tracking/com.bts.issue.type.adapter.outbound` | issue-tracking 이 제공하는 구현체. @Transactional(readOnly=true) |

### 의존 방향

```
project-workflow
  └─ (선언) IssueTypeLookupPort
  └─ (반환 타입) IssueTypeRef [shared-kernel]

issue-tracking
  └─ (구현) IssueTypeLookupAdapter → implements IssueTypeLookupPort
  └─ (변환) IssueType → IssueTypeRef
```

- project-workflow → issue-tracking 내부 패키지 import 0건.
- issue-tracking → project-workflow 의존은 `IssueTypeLookupPort` 선언 목적으로만 허용.
- issue-tracking ← project-workflow WorkflowResolver (PR #27 기존 의존) 와 별개로,
  issue-tracking → project-workflow 방향이 추가되어 **양방향 의존** 이 발생한다.

### 양방향 의존에 대한 결정

일반적으로 BC 간 양방향 의존은 순환 구조를 낳아 모듈러 모놀리스 원칙에 위배된다.
그러나 아래 조건이 모두 충족될 때 view layer 전용 outbound port 한해 허용한다.

1. issue-tracking → project-workflow 의존은 `IssueTypeLookupPort` 인터페이스만.
   내부 도메인(Workflow, WorkflowScheme 등) 직접 import 0건.
2. project-workflow → issue-tracking 의존은 `testRuntimeOnly` 만. 컴파일 타임 순환 없음.
3. Gradle 에서 컴파일 타임 순환이 없으므로 빌드 실패 없음.
4. ArchUnit IssueBcArchTest 금지 패키지 목록에 `com.bts.workflow.scheme.application.port` 가 없으므로
   `IssueTypeLookupPort` import 는 룰 통과.

이 패턴은 향후 project-management BC 분리 시 재검토가 필요하다.
이상적으로는 `IssueTypeLookupPort` 를 shared-kernel 로 이동해 양방향 의존을 제거하는 것이
BC 격리 원칙에 더 충실하다. 후속 PR 에서 검토.

### ArchUnit 예외 등록

`ProjectWorkflowArchitectureTest.mustNotImportIssueTracking` 룰의 allowedClassNames 에
`com.bts.shared.issue.IssueTypeRef` 를 추가한다.

`IssueTypeRef` 는 shared-kernel (`com.bts.shared.issue`) 위치이므로
`com.bts.issue.*` 금지 패키지 매칭 대상이 아니다. 예외 등록 없이도 룰 통과하지만,
의도를 명시하기 위해 KDoc 에 허용 이유를 기재한다.

## 결과

### 긍정

- **BC 격리 준수.** project-workflow 가 issue-tracking 도메인 엔티티를 직접 import 하지 않는다.
- **응답 완성.** 서버에서 단일 API 호출로 key+name 포함 MappingResponse 를 반환한다.
- **테스트 용이.** IssueTypeLookupPort 는 MockK 로 대체 가능해 project-workflow 단위 테스트에서 DB 없이 검증 가능하다.

### 부정 / 위험

- **양방향 BC 의존.** issue-tracking ↔ project-workflow 방향이 양쪽으로 존재한다.
  현재는 컴파일 타임 순환이 없어 빌드에 영향 없지만, 향후 모듈 분리 시 리팩토링 필요.
- **향후 shared-kernel 이전 권고.** `IssueTypeLookupPort` 를 shared-kernel 로 이동하면
  issue-tracking 이 project-workflow 의존을 완전히 제거할 수 있다. 후속 FR 에서 결정.

## 관련

- `backend/modules/shared-kernel/src/main/kotlin/com/bts/shared/issue/IssueTypeRef.kt`
- `backend/modules/project-workflow/src/main/kotlin/com/bts/workflow/scheme/application/port/IssueTypeLookupPort.kt`
- `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/type/adapter/outbound/IssueTypeLookupAdapter.kt`
- `backend/modules/project-workflow/src/test/kotlin/com/bts/workflow/archunit/ProjectWorkflowArchitectureTest.kt`
- `docs/adr/2026-05-21-workflow-bc-cross-bc-port.md` — inbound port 패턴 선례 (대칭 관계)
- CLAUDE.md §핵심 패턴 — BC 격리
