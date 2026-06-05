<!-- ADR: 컴포넌트 리드를 기본 담당자로 자동 배정하는 규칙과 생성-시점 컴포넌트 입력 도입 (FR-CM-03) -->

# 컴포넌트 기본 담당자 자동 배정 — leadUserId 재사용 + 생성/변경 trigger + 사전순 우선순위 (FR-CM-03)

> 상태: 채택(Accepted)
> 날짜: 2026-06-05
> BC: issue-tracking
> 관련 FR: FR-CM-03(컴포넌트별 기본 담당자 자동 할당). 선행 FR-CM-01(컴포넌트 CRUD + leadUserId) / FR-CM-02(이슈 다중 컴포넌트 할당) / FR-IS-03(담당자 전용 서브리소스 + UserLookupPort).
> 관련 PR: feat/fr-cm-03-default-assignee
> 작성자: Maxi + Claude (backend-engineer)

## 맥락

FR-CM-03은 이슈에 컴포넌트가 지정될 때, 그 컴포넌트의 책임자를 이슈의 기본 담당자로
자동 배정해 분류 직후 담당자 미할당 상태를 줄인다.

기존 코드 조사(ground-truth):
- `Component`(`component/domain/Component.kt`)는 `leadUserId: UUID?` 필드 보유(FR-CM-01).
  리드 실재는 컴포넌트 생성/수정 시 ApplicationService가 `UserLookupPort`로 검증한 뒤 저장한다.
- `Issue`(`domain/Issue.kt`)는 `assigneeId: ActorId?`(0~1명, null 허용) + `componentIds: List<UUID>`
  (다대다, FR-CM-02) 보유. `assignTo()/unassign()`, `assignComponents()/clearComponents()` 메서드 존재.
- 담당자 지정은 `PATCH /api/v1/issues/{key}/assignee`(FR-IS-03)에서 `UserLookupPort.exists()` 검증(422)
  + `expectedVersion` 낙관락 패턴 확립.
- 컴포넌트 할당은 `PATCH /api/v1/issues/{key}/components`(FR-CM-02) 전체교체 서브리소스로만 가능.
- **`createIssue`(IssueApplicationService)는 현재 컴포넌트를 받지 않는다** — `CreateIssueRequest`에
  컴포넌트 필드가 없고, 생성 시 `assigneeId`도 항상 null(생성 API가 담당자도 받지 않음).

## 결정

### D1 — 기본 담당자 = 컴포넌트의 기존 `leadUserId`(필드 신설 없음)
컴포넌트의 "기본 담당자"는 기존 `Component.leadUserId`(리드)를 그대로 사용한다.

- 별도 `defaultAssigneeId` 필드 도입을 검토했으나 기각. 리드(영역 책임자)와 기본 담당자(자동 배정 대상)를
  분리하면 components 마이그레이션 + 컴포넌트 CRUD/UI/검증 포트 확장이 필요해 범위가 과도하다.
  현재 제품 단계에서 "컴포넌트 리드 = 그 영역 이슈의 기본 담당자"는 자연스러운 의미 결합이다.
  (Maxi 결정 2026-06-05.)
- 데이터 신설 0(FR 정의 D3 "활용"과 일치). 리드 실재는 컴포넌트 set 시점에 `UserLookupPort`로 이미 검증되어
  있으므로 자동 배정 시 재검증하지 않는다(현 `users` 스키마에 삭제/비활성 컬럼 없음 — 리드는 유효 사용자로 신뢰).

### D2 — Trigger 2곳: 이슈 생성 + 컴포넌트 변경
자동 배정은 (a) 이슈 생성 시, (b) `PATCH /issues/{key}/components` 컴포넌트 변경 시 평가한다.

- (a)를 위해 **`createIssue`에 컴포넌트 입력(`componentIds`)을 추가**한다. 현재 생성 API가 컴포넌트를 받지
  않아 "생성 시 자동 배정"이 발화할 컴포넌트가 없기 때문. `CreateIssueRequest.componentIds`(선택, 기본 빈 목록)
  추가 + 도메인 `Issue.create`가 componentIds 수용 + 검증(같은 프로젝트+활성, FR-CM-02 `validateComponents` 재사용).
  (Maxi 결정 2026-06-05 — 생성 시점에도 컴포넌트를 붙일 수 있어야 함.)
- (b)는 FR-CM-02 `changeComponents` 경로에 자동 배정 평가를 추가.

### D3 — 덮어쓰기 정책: 담당자가 미할당(null)일 때만 채움
자동 배정은 이슈의 현재 `assigneeId`가 null일 때만 동작한다. 명시적으로 지정된 담당자는 절대 덮지 않는다.

- 생성 시점은 `assigneeId`가 항상 null이라 컴포넌트(리드 보유)가 있으면 항상 발화.
- 컴포넌트 변경 시점은 기존 담당자가 있으면 보존(자동 배정 skip). 미할당 상태에서 컴포넌트를 바꾸면 발화.
  (Maxi 결정 2026-06-05.)

### D4 — 다중 컴포넌트 우선순위: 이름 사전순 첫 번째(리드 보유 한정)
이슈가 여러 컴포넌트에 속하면(FR-CM-02 다대다 유지), 리드가 지정된 컴포넌트 중 **이름(name) 사전순으로
가장 앞선** 컴포넌트의 리드를 기본 담당자로 채택한다.

- 다중 컴포넌트 모델은 FR-CM-02 그대로 유지(단일 전환은 검토했으나 기각). 따라서 충돌 해소 규칙이 필요.
- 이름 사전순은 결정적·예측 가능하며 Jira의 다중 컴포넌트 기본 담당자 동작과 동형.
- 리드가 없는(null) 컴포넌트는 후보에서 제외. 리드 보유 컴포넌트가 하나도 없으면 자동 배정 없음(미할당 유지).
- `componentIds` 목록 순서가 아니라 컴포넌트 이름을 기준으로 한다(set 교체 의미론상 목록 순서는 불안정).
  (Maxi 결정 2026-06-05.)

### D5 — 로직 위치: IssueApplicationService(도메인 경유)
자동 배정은 `IssueApplicationService`의 `createIssue` / `changeComponents` 흐름에서 수행하되, 담당자
설정은 도메인 `Issue.assignTo()`를 경유한다(MEMORY patch-merge-domain-bypass — 서비스가 repository 직행
금지, 도메인 불변식 경유).

- 사전순 첫 리드 결정에는 컴포넌트의 (name, leadUserId)가 필요 → `ComponentRepository`에서 id 집합으로
  (name, leadUserId) 조회. 활성 컴포넌트만(소프트삭제 제외).
- 자동 배정 결과도 일반 담당자 변경과 동일하게 영속(IssueRepository) + 단건 응답에 `assigneeId` 노출.

## BC 격리

- 본 작업은 issue-tracking 단일 BC. 리드 사용자 실재 검증은 컴포넌트 set 시점에 이미 완료되어 있어
  identity-access 추가 접촉 불필요(`UserLookupPort`는 이미 shared-kernel 경유로 결선됨).

## 결과

- 신규 용어/엔티티 없음. "컴포넌트 리드 = 그 컴포넌트 이슈의 기본 담당자" 의미만 명확화.
- 범위: `createIssue` 컴포넌트 입력 추가 + 도메인 생성 검증 + 자동 배정 규칙(생성/변경) + 사전순 우선순위
  + 백엔드 테스트 + 프론트 자동 표시 + E2E.
- FR-CM-02 다대다 구조는 변경 없음.

## 관련

- ADR 2026-06-04 issue-component-assignment-model (이슈↔컴포넌트 다대다 + 전체교체 서브리소스)
- ADR 2026-06-02 component-model-and-permission-deferral (컴포넌트 정규화 엔티티 + leadUserId)
- ADR 2026-06-01 issue-assignee-user-lookup-port (담당자 UserLookupPort 검증)
- MEMORY: patch-merge-domain-bypass, jooq-init-codegen-mirror, issue-scope-global-prod-hard-deny
