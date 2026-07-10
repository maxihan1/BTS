<!-- FR-AT-02 자동화 액션 — 액션 도메인·cross-BC 커맨드 포트·rule actor 권한 모델·dry-run·체인 깊이 결정 -->

# ADR — FR-AT-02 자동화 액션 (필드 변경/담당자/댓글/API 호출)

- 날짜: 2026-07-11
- 상태: Accepted
- 관련 FR: FR-AT-02 (automation §2.2)
- 관련 SDD: 08장 §8.4 (액션 종류)
- 선행: FR-AT-01 (완료, PR #251 코어 / #254 UI)
- 관련 PR: #256

## 맥락

FR-AT-01은 TCA(Trigger-Condition-Action) 엔진의 **트리거** 절반을 구현했다 — 5종 트리거를
감지·매칭해 `{ruleId, triggerType, triggerEvent}`를 `q_automation_execution` 큐에
enqueue(`AutomationExecutionEnqueuer`)하는 이음선까지. FR-AT-02는 그 큐를 소비해 **액션**을
실제로 실행하는 executor를 구현한다.

코드 조사로 확인된 이음선·선례.

- `q_automation_execution` 큐(V301, automation 소유)에 `AutomationExecutionEnqueuer`가 발화 결과를
  적재 중. FR-AT-02 executor가 이 큐의 **첫 소비자**.
- `automation_rules`는 **트리거 전용** 스키마 — 조건(FR-AT-03)·액션(FR-AT-02) 컬럼은
  "후행 FR이 add 마이그레이션으로 추가"로 명시(V300 주석). 액션 저장은 본 FR이 신설.
- cross-BC 변경 위임 선례: `shared-kernel/board/IssueTransitionPort`(agile-planning → issue-tracking
  전이 위임). fail-closed(default 구현 없음)·actor는 커맨드 VO로 전달·shared-kernel 배치로 BC 격리.
- 아웃바운드 HTTP SSRF 가드: `shared-kernel/http/OutboundUrlValidator`가 이미 존재(automation import 가능).
- 권한 판정: `shared-kernel/permission/AutomationPermissionResolver`(prod=identity-access, fail-closed).

FR-AT-02 범위는 **4종 액션**이다(product §2.2 정본). SDD 8.4의 9종 중 나머지 5종
(`AddLabel`/`Transition`/`SendNotification`/`CreateIssue`/`RunSubrule`)은 후행 FR/범위 밖.

## 결정

### D1. 액션 도메인 모델 — Action 다형성 + AutomationRule 확장

`AutomationRule` 애그리거트가 **순서 있는 액션 리스트**를 보유한다.

- 액션 4종(sealed): `SetFieldAction` / `AssignAction` / `AddCommentAction` / `CallWebhookAction`.
- `action_type` enum + `action_config`(JSONB)로 영속. 트리거의 `trigger_type`/`trigger_config`와 동형.
- 액션은 룰당 N개, `position`으로 순서 보장(리스트 순차 실행).
- 각 액션의 config는 형식 검증(favorites/트리거 선례 — cross-BC 존재 검증은 실행 시점).

### D2. cross-BC 실행 — 동기 커맨드 포트 (shared-kernel)

automation은 issue-tracking을 직접 import하지 않는다(BC 격리). 이슈를 바꾸는 3종 액션
(SetField/Assign/AddComment)은 **shared-kernel 커맨드 포트**를 통해 위임한다 —
`IssueTransitionPort` 선례 동형(Maxi 확정 — 동기 포트).

- 신규 `shared-kernel/issue/IssueMutationPort`(가칭). issue-tracking이 prod adapter 구현, automation이
  consumer. non-prod은 consumer-owns-stub(automation 컨텍스트 fail-safe stub).
- **동기 반환**: executor가 성공/실패 결과를 즉시 받아 dry-run 판정·실행 이력(FR-AT-05 이음선)에 반영.
- actor는 커맨드 VO로 전달(포트가 SecurityContext 직접 읽지 않음 — async 안전 + 위조 차단,
  `BoardTransitionCommand` 동형).
- CallWebhook은 cross-BC가 아닌 아웃바운드 HTTP — `shared-kernel/http/OutboundUrlValidator`
  (SSRF 가드) 재사용.

**대안 기각**: 커맨드 이벤트 큐(q_automation_commands)로 비동기 위임 — dry-run·실행 결과를 동기로 받지
못하고 큐가 하나 늘며 FR-AT-05 실행 이력과 어긋남. 기각.

### D3. 실행 권한 모델 — rule actor = 룰 생성자 (fail-closed)

액션은 **룰 생성자(`automation_rules.created_by`)의 권한**으로 실행된다(Maxi 확정 — Jira rule-actor 모델).

- executor가 액션 실행 전, 커맨드 포트에 actor=`created_by`를 전달. issue-tracking이 그 actor의
  이슈 필드 편집/담당자 지정/댓글 작성 권한을 강제(기존 이슈 권한 경로 재사용).
- 권한 부족 시 **fail-closed** — 해당 액션 거부, 실행 이력에 `PERMISSION_DENIED` 기록(FR-AT-05 이음선),
  다음 액션 진행 여부는 D5 정책.
- **대안 기각**: (a) 트리거 유발자 actor — 유발자마다 실행 주체가 바뀌어 예측 불가, 저권한→고권한 우발
  실행 위험. (b) 자동화 시스템 액터 — 액터·권한 관리 UI 신설 필요, FR-AT-02 범위 초과. 둘 다 기각.

### D4. dry-run 모드 + 무한 루프 방지 (체인 깊이)

- **dry-run**: 액션을 실제 커밋하지 않고 "무엇이 바뀔지 + 권한 통과 여부"만 계산해 반환. 커맨드 포트에
  dryRun 플래그 전달, issue-tracking이 검증까지만 수행하고 미커밋. 액션 빌더 UI(D6)의 미리보기 근거.
- **체인 깊이 제한**: 액션이 이슈를 바꾸면 새 `issue.updated` 이벤트가 발행돼 다른 룰을 다시 발화할 수
  있다(자동화 체인). automation.md 결정(깊이 10 제한)을 본 FR에서 도입 — `q_automation_execution`
  payload에 `executionDepth`를 실어 나르고, executor가 10 초과 시 실행 중단(무한 루프 차단).
  FR-AT-01 enqueuer는 depth=0으로 시작, executor가 유발한 후속 발화는 depth+1.

### D5. 부분 실패 정책 — best-effort 순차 + 상태 집계

- 룰의 액션들을 `position` 순서로 순차 실행. 한 액션 실패(권한 거부/포트 예외/웹훅 오류)가 나머지를
  막지 않는다(best-effort). 최종 상태: `SUCCESS`(전부 성공) / `PARTIAL`(일부 실패) / `FAILED`(전부 실패).
- 실행 이력 상세(액션별 result/error)의 영속은 FR-AT-05 범위 — 본 FR은 로깅 + 큐 생명주기까지.
  (SDD 8.6 `AutomationRunLog`는 FR-AT-05가 테이블화.)

### D6. PR 범위 — 백엔드 코어 D1~D5

이번 PR = D1~D5(도메인·명세·데이터모델·executor·Testcontainers 테스트). D6(액션 빌더 UI)·D7(E2E)는
후속 PR(Maxi 확정 — FR-AT-01 #251→#254 / FR-SL-01 #244→#247 분할 선례 동일).

## 결과 / 파급

- 신규 마이그레이션: `automation_actions` 테이블(V302, automation V300~ 범위) + `q_automation_execution`
  payload에 `executionDepth` 추가는 스키마 무변경(JSONB payload 필드).
- 신규 shared-kernel 포트: `IssueMutationPort` + 커맨드/결과 VO. issue-tracking prod adapter 구현 →
  cross-BC touch(BC 격리 예외, IssueTransitionPort 선례). 새 포트 소비 → full-boot NoSuchBean,
  test @MockBean/@TestConfiguration 동반([[new-crossbc-dep-openapi-mockbean-regression]]).
- executor는 `q_automation_execution`의 **첫 소비자** — pgmq 메시지 생명주기(read→처리→archive/delete)
  필수 준수([[pgmq-consumer-message-lifecycle-p0]]). @Scheduled 폴링 워커(AutomationEventWorker 동형).
- 권한 fail-closed: nullable 의존성 + `?: return` 금지([[crossbc-resolver-nullable-fail-open]]).
- issue-tracking에 자동화용 setField/assign/addComment 커맨드 경로가 없으면 신설 — 기존 REST 유스케이스
  재사용 우선(도메인 우회 금지 [[patch-merge-domain-bypass]]).

## 대안 (기각)

- **비동기 커맨드 이벤트 큐** (D2 참조) — dry-run·동기 결과 불가. 기각.
- **트리거 유발자/시스템 액터 권한** (D3 참조) — 예측 불가/범위 초과. 기각.
- **9종 액션 전부** — product 정본은 4종. 나머지 5종은 후행 FR. 기각.
