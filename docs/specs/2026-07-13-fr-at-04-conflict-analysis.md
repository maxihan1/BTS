<!-- FR-AT-04 규칙 충돌 정적 분석 — automation 규칙 저장 시 4종 충돌(사이클/필드/우선순위/권한) lint 스펙 -->

# FR-AT-04 규칙 충돌 정적 분석 — 스펙

- 날짜. 2026-07-13
- BC. automation
- 선행. FR-AT-01(트리거) · FR-AT-02(액션) · FR-AT-03(조건) 완료
- 관련. product `automation.md §2.4` · SDD `08-automation-engine.md §8.7`
- 범위. **백엔드 코어 D1~D5** (D6 경고 모달 UI · D7 E2E는 후속 PR — FR-AT-01·02·03 분할 선례)

## 배경

automation TCA(Trigger-Condition-Action) 엔진은 규칙을 서로 독립적으로 저장·실행한다. 규칙이 늘면
서로 간섭한다 — 한 규칙의 액션이 다른 규칙을 유발해 무한 루프를 만들거나, 같은 이벤트에 여러 규칙이
걸려 순서에 따라 결과가 달라지거나, 두 규칙이 같은 필드를 다른 값으로 덮어쓰거나, 규칙의 실행 주체가
액션 권한이 없어 런타임에 조용히 실패한다. FR-AT-04는 이런 충돌을 **규칙 저장 시점에 정적으로
분석**해 경고로 알린다.

FR-AT-02 `AutomationExecutionWorker` KDoc은 "견고한 사이클 검출은 FR-AT-04에 위임"이라 명시했다.
이 FR이 그 공백을 채운다(런타임 루프 가드는 그대로 유지 — 정적 분석은 저장 시점 경고, 런타임 가드는
실행 시점 방어로 상보).

## 결정 요약 (Maxi 확정)

1. **충돌 4종** — `CYCLE` / `FIELD_CONFLICT` / `PRIORITY_AMBIGUITY` / `PERMISSION_MISSING`
   (product 3종 + SDD 3종의 합집합). 문서 drift는 이 PR에서 4종으로 전수 동기화.
2. **강제성 = 전부 경고(soft)** — 어떤 충돌도 저장을 막지 않는다. 저장은 항상 성공.
3. **분석 방식 = 저장 응답에 포함** — 별도 엔드포인트 없음. 기존 `POST`/`PATCH` 저장 경로가
   저장 후 lint를 수행하고 응답 DTO에 `conflicts` 배열을 담는다.

## 사용자 시나리오 (Given-When-Then)

### S1. 사이클 경고
- **Given** 규칙 A(트리거 ISSUE_UPDATED{fields:[priority]}, 액션 SetField(priority))가 존재
- **When** 관리자가 규칙 B(트리거 ISSUE_UPDATED{fields:[priority]}, 액션 SetField(priority))를 저장
- **Then** 저장은 성공하고, 응답 `conflicts`에 `CYCLE`(A↔B가 서로 priority 변경을 유발)이 포함된다

### S2. 필드 충돌 경고
- **Given** 규칙 A(트리거 ISSUE_CREATED, 액션 SetField(priority, 1))가 존재
- **When** 규칙 B(트리거 ISSUE_CREATED, 액션 SetField(priority, 5))를 저장
- **Then** 저장 성공 + `conflicts`에 `FIELD_CONFLICT`(A·B가 priority를 각각 1·5로 SET) 포함

### S3. 우선순위 모호 경고
- **Given** 규칙 A(트리거 ISSUE_CREATED, 액션 Assign)가 존재
- **When** 규칙 B(트리거 ISSUE_CREATED, 액션 AddComment)를 저장
- **Then** 저장 성공 + `conflicts`에 `PRIORITY_AMBIGUITY`(같은 ISSUE_CREATED에 A·B가 매칭, 실행
  순서가 created_at·id로만 결정) 포함

### S4. 권한 부족 경고
- **Given** 규칙 A의 rule actor(actor_user_id)가 프로젝트 ATLAS에서 이슈 UPDATE 권한이 없다
- **When** 규칙 A(액션 SetField)를 저장 (또는 A가 이미 존재하는 상태에서 다른 규칙 저장 시 재분석)
- **Then** 저장 성공 + `conflicts`에 `PERMISSION_MISSING`(actor가 UPDATE 권한 없어 SetField가 런타임
  fail-closed 예정) 포함

### S5. 충돌 없음
- **Given** 서로 간섭하지 않는 규칙들만 존재
- **When** 새 규칙을 저장
- **Then** 저장 성공 + `conflicts`는 빈 배열 `[]`

## 기능 요구사항 (FR)

### FR-1. 저장 후 lint 통합
`AutomationRuleService.create`/`patch`는 규칙 저장(커밋) 후 `RuleConflictAnalyzer`를 호출해 해당
프로젝트의 규칙 집합을 분석하고, 결과 `conflicts`를 응답 DTO에 담는다. **lint 실패(분석 중 예외)는
저장 성공을 훼손하지 않는다** — 분석은 fail-safe로 감싸고, 예외 시 빈 conflicts + 경고 로그
(저장은 이미 커밋됨).

### FR-2. 분석 대상 규칙 집합
- `AutomationRuleRepository.findByProject(projectKey)`로 프로젝트의 **삭제되지 않은 모든 규칙**을
  로드하고, 각 규칙의 액션(`AutomationActionRepository.findByRuleId`)·조건을 hydrate한다.
- 사이클·필드·우선순위 분석의 그래프 노드는 **enabled 규칙만** (disabled는 발화하지 않으므로 제외).
- 권한 분석은 **enabled 규칙만** 대상 (disabled는 실행 안 됨).
- 성능(NFR): 프로젝트당 배치 로드 1회. 규칙별 액션 조회 N+1은 허용하되 100규칙 1s 임계 내
  (필요 시 IN 절 배치 조회로 최적화 — plan에서 판단).

### FR-3. CYCLE 검출
- 방향 그래프. 노드 = enabled 규칙. 엣지 `A → B` = A의 어떤 액션이 B의 트리거를 유발할 수 있음.
- **액션 → 유발 트리거 매핑**:
  | 액션 | 유발 이벤트 | 매칭 트리거 |
  |---|---|---|
  | `SetFieldAction(field=F)` | issue.updated (updatedFields=[F]) | B.trigger=ISSUE_UPDATED **and** (B.fields 비었거나 F ∈ B.fields) |
  | `AssignAction` | issue.updated (assignee 변경) | B.trigger=ISSUE_UPDATED **and** (B.fields 비었거나 `assignee` ∈ B.fields) |
  | `AddCommentAction` | issue.commented | B.trigger=ISSUE_COMMENTED |
  | `CallWebhookAction` | (외부 HTTP, automation 재유입 정적 추적 불가) | 엣지 없음 |
- 필드 매칭 판정은 `TriggerMatcher.matchesFieldFilter` 시맨틱 재사용(configuredFields 비면 전체 매칭).
- **self-loop 포함** (A의 액션이 A 자신의 트리거를 유발 = 무한 루프).
- DFS 기반 사이클 검출. 각 사이클을 1건의 `CYCLE` 충돌로 리포트(ruleIds = 사이클 경로).
- 같은 사이클을 중복 리포트하지 않도록 정규화(예: 최소 ruleId 시작 정렬 후 dedup).

### FR-4. FIELD_CONFLICT 검출
- 같은 트리거에 **동시 매칭 가능한** enabled 규칙 쌍 중, 둘 다 같은 `field`를 **서로 다른 `value`로**
  SetField 하는 경우.
- "동시 매칭 가능" = 같은 triggerType **and** (ISSUE_UPDATED면 fields 필터 겹침 또는 한쪽 empty).
- value 비교 = `JsonNode` 동등성(`equals`). 같은 값이면 충돌 아님(멱등).
- 한 규칙 **내부**의 액션 간 같은 필드 다른 값 SET도 검출(같은 규칙 자기 자신, ruleIds=[self]).
- 조건 차이는 정적으로 겹침을 정밀 판정하지 않는다(SAT급 난제) — 조건 유무·차이는 detail에
  참고로 명시하되, 트리거 겹침만으로 잠재 충돌 경고(보수적 = 놓치지 않음, 일부 false positive 허용).

### FR-5. PRIORITY_AMBIGUITY 검출
- 같은 `(projectKey, triggerType[+겹치는 fields])`에 동시 매칭 가능한 enabled 규칙이 **2개 이상**이고,
  그 규칙들이 **관측 가능한 부수효과(이슈 필드 변경)를 내는 액션**을 가져 실행 순서가 결과에 영향을 줄
  수 있는 경우. 순서 결정 필드가 `created_at, id`뿐이라는 게 근거(automation_rules에 priority 컬럼 부재).
- FIELD_CONFLICT(같은 필드 확정 상충)와 구분: PRIORITY_AMBIGUITY는 **순서 모호**(반드시 같은 필드는
  아님, 예: 규칙1이 assignee 바꾸고 규칙2가 priority 바꾸는데 순서가 로그·후속 트리거에 영향).
- 순수 부수효과 없는 조합(예: CallWebhook만)은 순서 무관으로 간주해 제외 가능(plan 판단).

### FR-6. PERMISSION_MISSING 검출
- 각 enabled 규칙의 rule actor(`actor_user_id`)가 액션 대상 권한을 프로젝트 레벨에서 보유하는지
  `IssuePermissionResolver.hasPermission(actorId, permission, IssueScope.Project(projectKey))`로 근사 판정.
- **권한 매핑**:
  | 액션 | 필요 권한 | 비고 |
  |---|---|---|
  | `SetFieldAction` | `IssuePermission.UPDATE` | |
  | `AssignAction` | `IssuePermission.UPDATE` | 담당 지정도 이슈 필드 편집 권한 |
  | `AddCommentAction` | (근사 대상 아님) | **IssuePermission에 댓글 권한 없음** → 이 FR 범위에서 권한 분석 제외 |
  | `CallWebhookAction` | (권한 무관) | 외부 HTTP, 이슈 권한과 무관 |
- 저장 시점엔 구체 이슈가 없으므로 **프로젝트 레벨(IssueScope.Project) 권한으로 근사**한다. 실제 실행은
  이슈별 보안등급까지 강제하므로, 정적 분석은 "프로젝트 레벨에서도 없으면 확실히 실패"만 잡는다
  (false negative 허용 — 프로젝트 레벨은 있으나 특정 이슈 보안등급에서 막히는 경우는 못 잡음, 이는
  런타임 fail-closed가 방어).
- 권한 미보유 시 `PERMISSION_MISSING` 경고(ruleId, actorId, 부족 권한, 해당 액션 종류).
- **cross-BC 새 의존**: automation이 `IssuePermissionResolver`(shared-kernel) 신규 소비.
  prod 조립(:modules:app)엔 identity-access 어댑터가 이미 존재(issue-tracking 소비 중). automation
  컨텍스트 test/dev엔 fail-safe stub 또는 @MockBean 배선 필요([[new-crossbc-dep-openapi-mockbean-regression]]).

### FR-7. 응답 DTO 확장
- `AutomationRuleResponse`에 `conflicts: List<RuleConflictResponse>` 필드 추가(기본 빈 배열).
- `RuleConflictResponse` = `{ type: ConflictType, severity: "WARNING", ruleIds: List<UUID>, detail: String }`.
- `detail`은 사람이 읽을 수 있는 한국어 설명(예: "규칙 '높은 우선순위 설정'과 'priority 초기화'가 서로
  priority 변경을 유발해 무한 루프가 될 수 있습니다").
- **GET(단건/목록) 응답에는 conflicts를 포함하지 않는다** — lint는 저장(create/patch) 시점만.
  (조회 시마다 전 규칙 분석은 비용 과다 + product/SDD가 "저장 시" 명시.)

## 비기능 요구사항 (NFR)

- **성능**. 100개 규칙 프로젝트에서 저장 후 분석 p95 < 1s (product §NFR "규칙 충돌 정적 분석 1s").
- **fail-safe**. 분석 중 어떤 예외도 저장 성공을 훼손하지 않음. 예외 → 빈 conflicts + 경고 로그.
- **BC 격리**. cross-BC는 shared-kernel 포트만(`IssuePermissionResolver`). issue-tracking 직접 import
  금지(`AutomationBcArchTest` 강제).
- **권한**. lint는 기존 `MANAGE_AUTOMATION` 가드 뒤에서만 실행(별도 권한 없음 — 저장 경로에 이미 가드).

## API 인터페이스 (REST)

**신규 엔드포인트 없음.** 기존 경로 응답만 확장.

```
POST   /api/v1/projects/{projectKey}/automation/rules        → 201 { ...rule, conflicts: [...] }
PATCH  /api/v1/projects/{projectKey}/automation/rules/{id}    → 200 { ...rule, conflicts: [...] }
GET    .../rules, GET .../rules/{id}                          → conflicts 없음 (기존 그대로)
```

`RuleConflictResponse`:
```json
{ "type": "CYCLE", "severity": "WARNING",
  "ruleIds": ["<uuid-a>", "<uuid-b>"],
  "detail": "규칙 A와 B가 서로 priority 변경을 유발해 무한 루프가 될 수 있습니다." }
```

## 데이터 모델 변경

**없음** (product §2.4 D3 "활용"). 기존 `automation_rules`/`automation_actions`/`automation_conditions`를
읽어 분석하고, 결과는 영속하지 않는다(요청-응답 계산값). 신규 마이그레이션 없음.

## 도메인 모델 (신규)

```
domain/
  RuleConflict.kt        — 값 객체 { type, severity, ruleIds, detail }
  ConflictType.kt        — enum { CYCLE, FIELD_CONFLICT, PRIORITY_AMBIGUITY, PERMISSION_MISSING }
  ConflictSeverity.kt    — enum { WARNING }  (현재 단일, 향후 확장 여지)
application/
  RuleConflictAnalyzer.kt — 프로젝트 규칙 집합 → List<RuleConflict>. 순수 분석(권한은 포트 주입).
```

## 엣지 케이스

- **규칙 1개뿐**. cross-rule 충돌 없음. self-loop(FR-3)·자기 규칙 내부 필드 충돌(FR-4)·권한(FR-6)만 가능.
- **disabled 규칙**. CYCLE/FIELD/PRIORITY 그래프에서 제외(발화 안 함). 저장 대상이 disabled면 그 규칙
  관련 이 3종은 경고 없음. 나중에 enable하면 그때 저장(patch) 시 재분석.
- **ISSUE_UPDATED fields 필터 empty**. "모든 필드 변경에 발화" → 어떤 SetField/Assign과도 엣지 성립.
- **CallWebhook만 있는 규칙**. CYCLE 엣지 없음. FIELD_CONFLICT 없음. PERMISSION_MISSING 없음.
  PRIORITY_AMBIGUITY는 부수효과 없어 제외 가능.
- **SCHEDULED/WEBHOOK 트리거**. 액션이 이들을 유발하지 못함(cron·외부 토큰) → CYCLE 진입 엣지 없음.
  단, 이들 규칙의 액션은 여전히 ISSUE_UPDATED/COMMENTED 규칙을 유발할 수 있음(나가는 엣지는 있음).
- **actor가 삭제된 사용자**. 권한 조회 false → PERMISSION_MISSING 경고(합당).
- **대량 규칙(100+)**. NFR 1s 임계. 배치 로드 + 그래프 크기 제한 고려.
- **같은 사이클 중복**. 정규화 dedup으로 1건만 리포트.

## 제약 조건

- 저장은 항상 성공(soft warning). conflicts는 정보성.
- GET에는 conflicts 미포함.
- AddComment 액션의 권한 분석은 이 FR 범위 밖(IssuePermission에 댓글 권한 부재).
- 조건 겹침의 정밀 판정 안 함(보수적 트리거 겹침 근사, 일부 false positive 허용).
- 이슈별 보안등급 권한은 정적 분석 대상 아님(프로젝트 레벨 근사, false negative는 런타임 방어).

## Brainstorming Check (직접 adversarial 검토, 5 gap 보완)

1. **CYCLE도 조건 무시(보수적)**. FR-3 그래프 엣지는 B의 조건이 실제로 참이 될지 판정하지 않는다
   (조건 겹침 SAT 난제). 트리거 유발 가능성만으로 엣지를 긋는다 = 일부 false positive 허용, 놓침 없음
   (FR-4와 동일 정신).
2. **updatedFields 실제 필드명 확인**. `AssignAction`이 유발하는 issue.updated 이벤트의 필드명
   (`assignee` 가정)과 `SetFieldAction.field`↔`ISSUE_UPDATED.fields`의 네임스페이스 일치는 **impl에서
   issue-tracking의 실제 이벤트 필드명(updatedFields 원소)으로 검증**한다. 불일치 시 정규화 매핑을
   analyzer에 둔다(automation 내부, cross-BC import 없이 상수 매핑).
3. **FIELD_CONFLICT / PRIORITY_AMBIGUITY 중복 억제**. 한 규칙 쌍이 둘 다 해당하면(같은 트리거 + 같은
   필드 상충) **FIELD_CONFLICT만 리포트**하고 그 쌍의 PRIORITY_AMBIGUITY는 억제한다(노이즈 감소).
   FIELD_CONFLICT는 PRIORITY_AMBIGUITY의 확정·심각 특수 케이스이므로.
4. **권한 조회 메모이제이션**. FR-6의 `IssuePermissionResolver` 호출은 `(actorId, projectKey, permission)`
   키로 분석 1회 내 캐시(같은 키 중복 조회 제거). N규칙×M액션이 아니라 고유 (actor,permission) 수만큼만
   포트 호출 → NFR 1s 안전 마진.
5. **non-prod stub 한계 명시**. non-prod에서 `IssuePermissionResolver`는 `AlwaysAllow` stub이라
   PERMISSION_MISSING이 뜨지 않는다(다른 cross-BC 권한 검증과 동일 한계). 실질 의미는 prod. 테스트는
   @MockBean으로 false를 주입해 검출 로직을 검증한다.

✅ 통과 (직접 검토 1회, 5 gap 전부 스펙 내 보완 — Maxi 결정 필요 항목 없음).

## 측정 가능한 완료 기준

- [ ] `ConflictType` 4종 enum + `RuleConflict` 값 객체 + `RuleConflictAnalyzer` 구현
- [ ] CYCLE: self-loop·2-cycle·3-cycle·no-cycle 테스트 통과, dedup 검증
- [ ] FIELD_CONFLICT: 같은 필드 다른 값 검출, 같은 값(멱등) 미검출, 규칙 내부 액션 간 검출
- [ ] PRIORITY_AMBIGUITY: 같은 트리거 2규칙 검출, 부수효과 없는 조합 제외
- [ ] PERMISSION_MISSING: UPDATE 없는 actor의 SetField/Assign 검출, 권한 있으면 미검출
- [ ] 저장 응답에 conflicts 포함, GET 응답엔 미포함
- [ ] 분석 예외 시 저장 성공 유지(fail-safe) 테스트
- [ ] cross-BC `IssuePermissionResolver` 소비 배선(automation 컨텍스트 부팅 + prod 조립 검증)
- [ ] 100규칙 분석 성능 확인(NFR 1s)
- [ ] product §2.4 · SDD §8.7 · fr-index 등 문서 4종 표기 전수 동기화(verify-master-plan 통과)
