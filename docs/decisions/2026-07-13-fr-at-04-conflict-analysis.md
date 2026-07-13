<!-- FR-AT-04 규칙 충돌 정적 분석 — 충돌 4종·soft warning·저장 응답 포함·cross-BC 권한 근사 결정 -->

# ADR — FR-AT-04 규칙 충돌 정적 분석

> 날짜. 2026-07-13 | PR. #268 | BC. automation | 상태. 채택

## 맥락

FR-AT-04는 TCA(Trigger-Condition-Action) 자동화 엔진의 안전장치다. 트리거(FR-AT-01)·액션(FR-AT-02)·조건(FR-AT-03)이 완료되면서 한 프로젝트에 규칙이 여러 개 쌓인다. 규칙들은 서로 독립적으로 저장·실행되므로 간섭한다 — 한 규칙의 액션이 다른 규칙을 유발해 무한 루프가 되거나, 같은 이벤트에 여러 규칙이 걸려 순서에 따라 결과가 달라지거나, 두 규칙이 같은 필드를 다른 값으로 덮거나, 규칙의 실행 주체가 권한이 없어 런타임에 조용히 실패한다.

FR-AT-02 `AutomationExecutionWorker` KDoc은 "견고한 사이클 검출은 FR-AT-04에 위임"이라 명시했다. 이 FR이 그 공백을 저장 시점 정적 분석으로 채운다(런타임 루프 가드는 그대로 유지 — 정적 분석은 저장 시점 경고, 런타임 가드는 실행 시점 방어로 상보).

문서 drift가 있었다 — product §2.4는 충돌을 "사이클/우선순위 모호성/필드 충돌"로, SDD 8.7은 "같은 트리거+동일 필드/무한 루프/권한 부족 액션"으로 서로 다르게 명시했다.

## 결정

### 1. 충돌 4종 — product 3종 + SDD 3종의 합집합 (Maxi 확정)

`ConflictType` enum 4종을 검출한다. product/SDD drift를 4종으로 정렬(이 PR에서 전수 동기화).

- **CYCLE** — 액션이 다른 규칙의 트리거를 유발하는 방향 그래프의 사이클(DFS, self-loop 포함).
  엣지 A→B = A의 액션이 B의 트리거를 유발: `SetFieldAction(field=F)`→ISSUE_UPDATED(fields에 F 포함 또는 empty) / `AssignAction`→ISSUE_UPDATED(assignee) / `AddCommentAction`→ISSUE_COMMENTED. `CallWebhookAction`은 외부라 정적 엣지 없음.
- **FIELD_CONFLICT** — 같은 트리거에 동시 매칭되는 규칙들(또는 한 규칙 내 액션들)이 같은 필드를 다른 값(`JsonNode.equals`)으로 SET.
- **PRIORITY_AMBIGUITY** — 같은 트리거에 매칭되는 enabled 규칙이 2개 이상이고 관측 가능한 부수효과가 있어 실행 순서가 결과에 영향. 순서 결정 필드가 `created_at, id`뿐(automation_rules에 priority 컬럼 부재)이 근거. FIELD_CONFLICT가 걸린 쌍은 억제(확정·심각 특수 케이스이므로 중복 노이즈 제거).
- **PERMISSION_MISSING** — rule actor(`actor_user_id`)가 액션 대상의 프로젝트 레벨 이슈 UPDATE 권한 부재.

### 2. 강제성 = 전부 soft WARNING (저장 무차단, Maxi 확정)

어떤 충돌도 저장을 막지 않는다. 저장은 항상 성공하고, 충돌은 정보성 경고로 응답에 담긴다. SDD 8.7·product D6가 "경고"로 명시한 것과 일치. 정당한 규칙 조합(의도된 중복 등)까지 차단하지 않기 위함이며, 규칙 조합의 정당성 판단은 관리자에게 위임한다.

- **대안 기각**: 사이클 hard-block(무한 루프 위험) — 런타임 루프 가드(FR-AT-02)가 이미 실행 시점을 방어하고, 정당한 자기 참조 규칙까지 저장을 막는 부작용이 있어 기각. `severity`는 UI 표현용으로 두되 현재 전부 WARNING.

### 3. 분석 방식 = 저장 응답에 포함 (별도 엔드포인트·테이블 없음, Maxi 확정)

기존 `POST`/`PATCH .../automation/rules` 저장 경로가 저장 커밋 **후** `RuleConflictAnalyzer`로 프로젝트 규칙을 분석하고 응답 DTO에 `conflicts` 배열을 담는다.

- 신규 엔드포인트·마이그레이션 없음(product §2.4 D3 "활용"). 기존 규칙을 읽어 분석, 결과 미영속.
- **fail-safe**: 분석 전체를 예외 격리 — 어떤 예외도 저장 성공을 훼손하지 않음(예외 시 빈 conflicts + 경고 로그). 저장은 이미 커밋됨.
- **GET 미포함**: conflicts를 `@JsonInclude(NON_NULL)` nullable로 두고 GET(단건/목록)은 analyzer를 호출하지 않는다(조회마다 전 규칙 분석은 비용 과다). create/patch만 lint.
- **대안 기각**: 별도 dry-run lint 엔드포인트 / 조회형 conflicts API — soft warning + 저장 응답 포함이 저장 흐름에 자연스럽게 통합되고 왕복이 하나 준다.

### 4. cross-BC 권한 근사 — IssuePermissionResolver (프로젝트 레벨)

PERMISSION_MISSING은 신규 소비 `IssuePermissionResolver`(shared-kernel)로 판정한다. 저장 시점엔 구체 이슈가 없으므로 `IssueScope.Project(projectKey)` + `IssuePermission.UPDATE`로 **프로젝트 레벨 근사**한다.

- SetFieldAction·AssignAction → UPDATE 권한 필요. AddCommentAction은 `IssuePermission`에 댓글 권한이 없어 **분석 제외**(이 FR 범위 밖). CallWebhookAction은 이슈 권한 무관.
- 프로젝트 레벨에도 없으면 확실히 실패(false negative 허용 — 프로젝트 레벨은 있으나 특정 이슈 보안등급에서 막히는 경우는 런타임 fail-closed가 방어).
- `(actorId, projectKey, permission)` 메모이제이션(분석 1회 내). fail-open 금지 — non-null 주입.
- non-prod은 consumer-owns-stub(`AlwaysAllow`), prod는 :modules:app의 identity-access 어댑터. 따라서 PERMISSION_MISSING은 prod에서만 실질 검출(테스트는 @MockBean/MockK로 false 주입해 로직 검증).
- **BC 격리**: analyzer는 cross-BC 직접 import 없이 shared-kernel 포트만 소비(AutomationBcArchTest 강제).

### 5. 근사의 보수성 — 조건 겹침 미판정

트리거 동시 매칭·사이클 엣지는 조건(Condition)의 실제 참/거짓을 판정하지 않는다(조건 겹침은 SAT급 난제). 트리거 겹침만으로 잠재 충돌을 경고한다 — 놓침 없음, 일부 false positive 허용(soft warning이라 비용 낮음).

## 결과 / 파급

- 신규: `RuleConflictAnalyzer`(@Component) + `RuleConflict`/`ConflictType`/`ConflictSeverity` 도메인 값 객체 + `RuleConflictResponse` DTO + `AutomationRuleResponse.conflicts` 필드. 마이그레이션 0.
- 신규 cross-BC 소비 `IssuePermissionResolver` → automation 컨텍스트 부팅이 이 빈을 요구. 모듈 test 11개 @SpringBootTest에 stub 동반 등록(NoSuchBean 회귀 방지). prod 조립(:modules:app)은 identity-access 어댑터가 이미 존재(issue-tracking 소비 중) — 머지 전 조립 부팅 재검증 필요.
- 성능: 100규칙 worst-case(전부 ISSUE_CREATED) 저장 후 분석 0.876s(NFR 1s 이내).
- 범위: 백엔드 D1~D5. 충돌 경고 모달 UI(D6)/E2E(D7)는 별개 후속(FR-AT-01·02·03 분할 선례). automation BC 3/7 유지.

## 관련

- 스펙. [docs/specs/2026-07-13-fr-at-04-conflict-analysis.md](../specs/2026-07-13-fr-at-04-conflict-analysis.md)
- Plan. [docs/plans/2026-07-13-fr-at-04-automation-lint.md](../plans/2026-07-13-fr-at-04-automation-lint.md)
- 선행. [2026-07-11-fr-at-02-automation-actions](2026-07-11-fr-at-02-automation-actions.md) (사이클 검출 위임 출처)
