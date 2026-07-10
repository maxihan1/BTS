<!-- FR-AT-02 자동화 액션 executor 스펙 — 백엔드 코어 D1~D5 (4종 액션·동기 커맨드 포트·rule actor·dry-run·체인 깊이) -->

# FR-AT-02 자동화 액션 (필드 변경/담당자/댓글/API 호출) — 스펙

- FR: FR-AT-02 (automation §2.2)
- 범위: **백엔드 코어 D1~D5** (UI D6·E2E D7은 후속 PR)
- ADR: [docs/decisions/2026-07-11-fr-at-02-automation-actions.md](../decisions/2026-07-11-fr-at-02-automation-actions.md)
- 선행: FR-AT-01 (트리거, PR #251/#254 완료)

## 개요

FR-AT-01이 매칭된 트리거를 `q_automation_execution` 큐에 `{ruleId, triggerType, triggerEvent}`로
적재한다. FR-AT-02는 이 큐의 **첫 소비자** — 룰에 정의된 **액션 리스트**를 순서대로 실행한다.

액션 4종.

| 액션 | 대상 | 실행 경로 |
|---|---|---|
| `SetFieldAction` | 이슈 필드 변경 | `IssueMutationPort.setField` → issue-tracking `updateIssue` |
| `AssignAction` | 담당자 지정/해제 | `IssueMutationPort.assign` → issue-tracking `changeAssignee` |
| `AddCommentAction` | 댓글 추가 | `IssueMutationPort.addComment` → issue-tracking `CommentApplicationService` |
| `CallWebhookAction` | 외부 API 호출 | `OutboundUrlValidator`(SSRF 가드) + HTTP 클라이언트 |

## 사용자 시나리오 (Given-When-Then)

### S1. 필드 변경 액션 (해피 패스)

```
Given "버그 생성 시 우선순위 High 설정" 룰(트리거=ISSUE_CREATED, 액션=SetField priority=High)이 활성
  And 룰 생성자 alice가 PROJ에서 이슈 UPDATE 권한 보유
When PROJ-1(Bug) 이슈가 생성됨 → 트리거 매칭 → q_automation_execution enqueue
Then executor가 큐를 소비 → SetFieldAction 실행 → PROJ-1.priority = High 로 변경됨
  And issue.updated 이벤트 발행(정상 도메인 경로)
  And 실행 결과 SUCCESS
```

### S2. 담당자 자동 배정 + 댓글 (다중 액션 순차)

```
Given "버그를 리드에게 할당 + 안내 댓글" 룰(액션 2개: Assign, AddComment)이 활성
When 트리거 발화 → executor 소비
Then position 순서대로 Assign 실행 → AddComment 실행
  And 둘 다 성공 시 SUCCESS, 하나만 실패 시 PARTIAL
```

### S3. 권한 부족 — fail-closed 거부

```
Given 룰 생성자 bob이 이후 PROJ에서 UPDATE 권한을 잃음(멤버십 변경)
When bob의 룰이 발화 → SetFieldAction 실행 시도
Then IssueMutationPort가 actor=bob 으로 assertPermission(UPDATE) 호출 → 거부 예외
  And 해당 액션은 실행되지 않음(이슈 미변경)
  And 실행 결과에 PERMISSION_DENIED 기록(로그), best-effort 로 다음 액션 진행
```

### S4. 외부 API 호출 (CallWebhook) — SSRF 차단

```
Given "이슈 생성 시 사내 챗봇 알림" 룰(액션=CallWebhook url=https://chat.example.com/hook)
When 발화 → CallWebhookAction 실행
Then OutboundUrlValidator 로 URL 검증(사설 IP/loopback/메타데이터 IP 거부)
  And 통과 시 POST(타임아웃·본문 크기 제한), 실패 시 액션 실패 기록(best-effort)
```

### S5. dry-run 미리보기

```
Given 룰 편집 화면(D6, 후속)에서 "이 액션이 무엇을 바꿀지" 미리보기 요청
When executor/서비스가 dryRun=true 로 액션 평가
Then 권한 체크 + 도메인 검증까지 수행, 실제 커밋 없음
  And "변경 예정 필드/값 + 권한 통과 여부" 반환
```

### S6. 무한 루프 차단 (체인 깊이)

```
Given 룰 A(SetField X) 발화 → issue.updated → 룰 B(SetField Y) 발화 → issue.updated → 룰 A ...
When 자동화 체인이 깊어짐
Then q_automation_execution payload 의 executionDepth 가 액션 유발 발화마다 +1
  And executor 가 depth > 10 이면 실행 중단(루프 차단), 경고 로그
```

## 기능 요구사항 (FR)

- **FR1. 액션 영속** — `automation_actions(id, rule_id, position, action_type, action_config JSONB, created_at)`.
  룰당 N개, position 순서. action_type CHECK 4종 화이트리스트(DB 이중 방어).
- **FR2. 액션 도메인** — sealed `Action` 4종. 각 config 형식 검증(cross-BC 존재 검증은 실행 시점).
  `AutomationRule`이 `actions: List<Action>` 보유(빈 리스트 허용 — 트리거만 있고 액션 없는 룰 유효).
- **FR3. executor 워커** — `q_automation_execution` pgmq consumer(@Scheduled 폴링, AutomationEventWorker 동형).
  read → 룰+액션 로드 → position 순차 실행 → archive/delete(메시지 생명주기 P0).
- **FR4. cross-BC 커맨드 포트** — shared-kernel `IssueMutationPort`(동기). 커맨드 VO에 actor·issueKey·
  변경내용·dryRun. issue-tracking prod adapter 가 기존 application service(updateIssue/changeAssignee/
  addComment) 위임(도메인 우회 금지). non-prod consumer-owns-stub.
- **FR5. rule actor 권한 (선택 가능)** — 룰마다 `actor_user_id`(신규, 기본값=생성 시 `created_by`, 룰
  편집에서 프로젝트 사용자로 변경 가능 — 변경 UI 는 D6). 이 actor 가 **모든 액션 권한 주체 + 댓글 작성자**를
  결정(지라 Actor 모델). 권한 부족 시 fail-closed 거부(기존 assertPermission 재사용). actor 는 커맨드 VO 로
  전달(SecurityContext 직접 접근 금지 — async 안전). 본 PR 은 스키마·도메인·CRUD payload 필드까지.
- **FR6. CallWebhook** — `OutboundUrlValidator`(기존 SSRF 가드) 재사용. 타임아웃·본문 크기 제한.
  URL·헤더·본문은 action_config. 리다이렉트 미추종(NEVER — 기존 아웃바운드 웹훅 정책 준거).
- **FR7. dry-run** — 커맨드 포트에 dryRun 플래그. true 면 권한/검증까지만, 미커밋·이벤트 미발행. 예상 결과 반환.
- **FR8. 체인 깊이/실행 상한** — (a) automation 직접 체인은 payload `executionDepth`(기본 0, +1), 10 초과 중단.
  (b) issue-tracking 왕복 사이클(깊이 리셋됨)은 **단일 root 트리거당 실행 횟수 런타임 상한**으로 방어.
  (c) 규칙 사이클 A→B→A 견고 검출은 FR-AT-04(정적 분석)에 위임. FR-AT-01 enqueuer 는 depth=0.
- **FR9. 부분 실패** — best-effort 순차. 액션 실패가 나머지를 막지 않음. 상태 집계 SUCCESS/PARTIAL/FAILED.
- **FR10. 템플릿 변수 (AddComment 등)** — `{{ path.to.var }}` 단순 치환(로직 없음). 변수 컨텍스트 = 이슈
  스냅샷 + 트리거 이벤트(`{{ issue.key }}`/`{{ issue.assignee.name }}`/`{{ trigger.type }}` 등). 미정의 →
  빈 문자열(경고 로그), 문법 오류 → 리터럴 유지. 치환값은 기존 댓글 새니타이즈 경로 통과(XSS 방어).

## 비기능 요구사항 (NFR)

- **NFR1. 처리 지연** — 트리거 → 액션 처리 p95 ≤ 5s(automation BC 완료 게이트).
- **NFR2. 권한 위반 차단** — 권한 부족 액션 차단율 100%(보안 가드). rule actor 의 실제 권한만 통과.
- **NFR3. BC 격리** — automation 은 shared-kernel 만 의존. issue-tracking 직접 import 0(ArchUnit 강제).
- **NFR4. 멱등/생명주기** — pgmq 메시지 read→처리→archive. 처리 실패 시 메시지 유실/중복 정책 명시.
- **NFR5. SSRF** — CallWebhook 은 사설/loopback/링크로컬/메타데이터 IP 거부(OutboundUrlValidator).

## API 인터페이스 (REST)

**이번 PR(D1~D5)은 executor 백엔드 코어** — 사용자 대면 신규 REST 없음(액션 빌더 UI 는 D6).
단, 액션 CRUD 는 룰 편집의 일부이므로 다음 중 하나로 명세(plan 에서 확정).

- 옵션. 기존 `AutomationRuleController` 의 룰 생성/수정 payload 에 `actions[]` + `actorUserId?` 필드 확장
  (룰·액션·actor 를 한 애그리거트로 저장). D6 UI 가 이 확장 payload 소비. **권장** — 액션은 룰의 일부(애그리거트 경계).
- dry-run 미리보기 엔드포인트(`POST .../actions/preview`)는 D6 UI 필요 시점(후속 PR)에 노출.
  본 PR 은 dryRun 을 포트/서비스 레벨에서 구현(내부 계약)까지.

## 데이터 모델 변경

- 신규 `automation_actions`(V302, automation V300~ 범위).
  - `id UUID PK`, `rule_id UUID NOT NULL`(FK → automation_rules, ON DELETE CASCADE — 룰 삭제 시 액션 정리),
    `position INT NOT NULL`, `action_type VARCHAR(20) NOT NULL CHECK(4종)`, `action_config JSONB NOT NULL`,
    `created_at TIMESTAMPTZ`.
  - UNIQUE(rule_id, position) — 순서 유일. 조회 인덱스 (rule_id, position).
- `automation_rules` 에 `actor_user_id UUID NOT NULL` 컬럼 추가(V303, rule actor). 기존 행 backfill =
  `created_by`(FR-AT-01 룰 존재 시). 기본값·NOT NULL 제약 마이그레이션 순서 주의.
- `q_automation_execution` payload 에 `executionDepth` 필드 추가(스키마 무변경, JSONB).

## 엣지 케이스

- **EC1. OCC version 부재** — automation 은 사용자의 expectedVersion 을 모른다. 포트 adapter 가 대상 이슈의
  **현재 version 을 읽어 적용**(automation 은 최신 상태에 작용, last-write). 동시 충돌 시 1회 재시도 후 실패 기록.
- **EC2. 액션 없는 룰** — actions 빈 리스트. executor 는 no-op 성공(트리거만 있는 룰 유효).
- **EC3. 삭제/이동된 이슈** — triggerEvent 의 이슈가 소프트 삭제/이동됨. 포트가 404/redirect → 액션 실패 기록,
  나머지 진행(best-effort).
- **EC4. 담당자 미존재** — AssignAction 대상 userId 부재 → AssigneeNotFoundException → 액션 실패 기록.
- **EC5. dry-run 은 이벤트 미발행** — dry-run 은 issue.updated 등 도메인 이벤트도 발행하지 않는다(체인 유발 없음).
- **EC6. 웹훅 타임아웃/5xx** — CallWebhook 실패는 재시도하지 않음(best-effort, FR-AT-05 재실행이 담당).
- **EC7. 비활성/삭제된 룰** — 큐 소비 시점에 룰이 disabled/deleted 면 실행 스킵(발화 후 상태 변경 대비).
- **EC8. rule actor 계정 비활성화** — createdBy 사용자가 비활성/삭제. 권한 조회가 fail-closed 로 거부 → 액션 거부.
- **EC9. 큐 메시지 처리 실패 + at-least-once** — pgmq 는 최소 1회 전달 → 워커 크래시 시 같은 실행 메시지가
  재처리돼 부작용 액션(AddComment 등)이 중복될 수 있다. **본 FR 정책**: best-effort + at-least-once 수용,
  견고한 dedup(실행 원장)은 FR-AT-05 로 위임. executor 예외 시 메시지 생명주기(archive vs 재큐) 명시, 무한 재시도 방지.
- **EC10. 순환 액션 자기 유발** — SetField 가 같은 룰을 다시 트리거. automation 직접 체인은 executionDepth,
  issue-tracking 왕복은 단일 root 실행 상한으로 차단(FR8).
- **EC11. rule actor 미선택/삭제** — actor_user_id 기본=created_by. actor 사용자가 비활성/삭제 시 권한 조회
  fail-closed 거부.
- **EC12. 템플릿 미정의 변수** — `{{ issue.unknown }}` → 빈 문자열 치환 + 경고 로그(실행 중단 아님).

## 제약 조건

- automation 은 issue-tracking 직접 import 금지(BC 격리). shared-kernel 포트만.
- 포트 adapter 는 기존 application service 위임(도메인 우회 금지 — [[patch-merge-domain-bypass]]).
- actor 는 커맨드 VO 로만(SecurityContext 직접 접근 금지 — [[BoardTransitionCommand]] 선례).
- fail-closed — nullable 의존성 + `?: return` 금지([[crossbc-resolver-nullable-fail-open]]).
- 새 shared-kernel 포트 소비 → full-boot NoSuchBean, test @MockBean/@TestConfiguration 동반
  ([[new-crossbc-dep-openapi-mockbean-regression]]).
- 4종 외 액션(AddLabel/Transition/SendNotification/CreateIssue/RunSubrule)은 범위 밖.

## 측정 가능한 완료 기준

- [ ] `automation_actions` 마이그레이션(V302) + SchemaMigrationTest 카운트 갱신.
- [ ] Action sealed 4종 + config 검증 단위 테스트.
- [ ] `IssueMutationPort`(shared-kernel) + issue-tracking prod adapter + automation consumer-owns-stub.
- [ ] executor 워커가 q_automation_execution 소비 → 4종 액션 실행(Testcontainers 통합).
- [ ] 권한 부족 시 reject 테스트(rule actor fail-closed) — 차단율 100%.
- [ ] dry-run: 미커밋 + 예상 결과 반환 테스트.
- [ ] 체인 깊이 10 초과 차단 테스트.
- [ ] BC 격리 ArchTest(automation → issue-tracking import 0).
- [ ] CallWebhook SSRF 거부 테스트(OutboundUrlValidator 경유).
- [ ] rule actor 선택 필드(actor_user_id) 마이그레이션(V303) + backfill + CRUD payload.
- [ ] AddComment 템플릿 변수 치환 단위 테스트(미정의→빈문자열, 문법오류→리터럴).
- [ ] 댓글 작성자 = rule actor 검증.

## Brainstorming Check

✅ 통과 (Phase B 자체 adversarial 검토 — gap 4건 발견 후 해소).
- Gap A (댓글 작성자) → Maxi 확정: **선택 가능한 rule actor**(지라 Actor 모델), 기본 created_by. D3 정제.
- Gap B (템플릿 변수) → Maxi 확정: **포함**. `{{ var }}` 단순 치환(로직 없음), FR10 신설.
- Gap C (무한루프 — issue-tracking 왕복서 깊이 리셋) → Maxi 확정: **런타임 상한 + FR-AT-04 위임**. FR8 정제.
- Gap D (at-least-once 중복 부작용) → 결정: best-effort 수용, dedup 은 FR-AT-05 위임. EC9 명시.
