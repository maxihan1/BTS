# FR-AT-07 PR-C — Git Webhook 인바운드 + PR_MERGED 트리거 (백엔드)

> slug: fr-at-07-pr-c-git-webhook
> 마스터 스펙 `docs/specs/2026-07-15-fr-at-07-pr-merge.md` **§C의 상세화**.
> **선행**. PR-A(#274/#275 permitAll 인프라·암호화 키·prod 조립 HTTP 테스트) · PR-B(#276 SET_FIX_VERSIONS).
> **2회차** — Phase B 적대적 검토(security + backend 병렬, BLOCKER 7 / CONCERN 12 / NIT 7) 전건 반영.
> 1회차가 무엇을 틀렸는지는 §부록 A.

## 0. 범위 (DEC-18)

**PR-C = 백엔드만.** D6(웹훅 URL 발급 화면)·D7(E2E)은 **PR-D**.

- **근거**. automation BC 선례 **6/6 전부 백엔드→UI 분할** (#251→#254, #256→#260, #262→#265,
  #268→#269, #270→#271, #272→#273). 예외 없음
- 프론트 Zod 계약 동기화(FR-C11)는 본 PR 포함 — PR-B 동형
- **★ FR-AT-07 완료 마킹·automation BC 7/7은 PR-D 몫.** 본 PR은 D1/D2/D4/D5만 `[x]`
- **FR 총수 123 불변** → **카운트 파일 미변경** = 동시 PR #277(123→128)과 충돌 0

## 1. 신뢰 경계 (§C-1 승계 — 아래 모든 상한의 전제)

> **PR 제목·본문은 신뢰할 수 없는 외부 입력이다.** HMAC이 증명하는 것은 **"GitHub이 보냈다"**
> 이지 **"내용이 믿을 만하다"** 가 아니다. PR은 BTS 계정이 없는 외부 기여자도 열고 제목·본문을 자유롭게 쓴다.

실측 G10이 층을 하나 더 얹는다 — `ActionExecutor.kt:271-275`가 `triggerEvent.issueKey`를 **무검증 신뢰**
하고 그 값이 `rule.actorUserId` 권한으로 실행된다(`rule.projectKey` 대조 코드 **없음**). **폭발 반경 = 룰
actor 권한.** `:176`(`?: return@runCatching true`)이 **조건 없는 룰**을 게이트 없이 통과시키므로,
조건 있는 룰만 `:177`의 `createdBy` 가시성으로 부분 완화된다.

## 2. 사용자 시나리오 (Given-When-Then)

### S1. 정상 — PR 머지가 Fix Version을 자동 설정 (본 FR의 존재 이유)

```
Given 프로젝트 PROJ에 GITHUB git webhook 등록 (secret 설정 완료)
  And PR_MERGED 트리거 + targetBranch="release/1.2" + SET_FIX_VERSIONS(versionIds=[v1.2.0]) 룰이 활성
 When GitHub이 base=release/1.2 로 머지된 PR(제목 "Fix login", 본문 "Closes PROJ-42")의
      pull_request(action=closed, merged=true) 이벤트를 유효 서명과 함께 전송하면
 Then 202를 즉시 반환하고 (동기 경로 = 조회 + enqueue만)
  And PROJ-42 의 fixVersions 가 [v1.2.0] 으로 전체교체된다 (룰 actor 권한으로)
```

### S2. 서명 위조 → 401 (검증 주체가 컨트롤러임의 실증)
### S3. 프로젝트 스코프 위반(`Closes OTHER-1`) → 202 + 추출 0건 + WARN
### S4. 머지 아닌 PR 이벤트(opened / closed+merged=false / push) → 202 + enqueue 0건
### S5. targetBranch 불일치 → 무발화 (release/1.2 룰과 release/2.0 룰 공존 시 하나만)
### S6. 정직한 재시도 → at-most-once dedup (**replay 방어 아님** — §C-6)
### S7. 등록 → 201 + 토큰 1회 노출 (이후 조회 불가)

## 3. 기능 요구사항

| ID | 요구사항 | 근거/함정 |
|---|---|---|
| **FR-C1** | `POST /api/v1/webhooks/git/{token}` — permitAll + CSRF-ignore + **bearer skip** (**3곳**) | ★ G5 |
| **FR-C2** | 토큰 SHA-256 조회 → 등록행. 미존재/삭제 균일 **401** | §C-7 C-b |
| **FR-C3** | 서명 검증은 **등록행 `provider`로만 분기**. 헤더 추론·폴백 금지 | §C-7 C-a |
| **FR-C4** | 머지 이벤트만 — GITHUB `pull_request`+`action=closed`+`merged=true` / GITLAB `Merge Request Hook`+`action=merge` | |
| **FR-C5** | **targetBranch 필터 — 4계층** (§3.6) | §C-5·DEC-12 |
| **FR-C6** | 이슈 키 추출 — PR 제목+본문, `Closes\|Fixes\|Resolves` 계열 **필수**, 대문자 고정, **단어 경계** (§3.4) | |
| **FR-C7** | 프로젝트 스코프 필터 — prefix ≠ 등록 `project_key` 인 키 무시 | |
| **FR-C8** | **팬아웃 3중 상한** (§3.7) — distinct 키 20 **+ title/body 각 2KB 절단 + 룰×키 곱 100** | ★ §C-4 3항 전부 |
| **FR-C9** | 배달 dedup — **서명 검증 후**, 단일 트랜잭션 (§3.8) | §C-6·DEC-23 |
| **FR-C10** | 등록 API + **secret 검증**(§3.9), 권한 `MANAGE_AUTOMATION` | |
| **FR-C11** | `TriggerType.PR_MERGED` + **파급 전수 grep** + 프론트 `triggerTypeSchema` 동기화 | ★ G2 교정본 |
| **FR-C12** | `automationSecretEncryptor` 빈 + `.env.prod.example` (선택적 연동 배치) | G8·G9·DEC-19 |
| **FR-C13** | **`ActionExecutor.execute` 내부** 방어심층 (§3.11) — **조건 유무 무관** | ★ G10·DEC-24·DEC-26 |
| **FR-C14** | ADR — §1.4 정식 예외 + replay 잔여위험 + GitLab 등급차 + **automation 프레이밍 차이** | DEC-13·DEC-22 |
| **FR-C15** | automation 웹훅 permitAll 중앙등록 — **3곳 전부** + 잔여위험 ADR | DEC-22 |

### 3.4. 이슈 키 추출 (FR-C6)

- **대상**. PR 제목 + PR 본문 (커밋 메시지 **미포함** — 별도 API 호출이 필요해 NFR-1을 깬다)
- **정규식**.
  ```
  (?i:Closes?d?|Fix(?:es|ed)?|Resolves?d?)\s+([A-Z][A-Z0-9]{1,9}-[1-9][0-9]*)(?![A-Za-z0-9-])
  ```
  - 키워드만 대소문자 무시(`(?i:...)`), **이슈키는 대문자 고정**
  - **★ 뒤 단어 경계 `(?![A-Za-z0-9-])` 필수** (C2-be) — 없으면 `Closes PROJ-42x` → `PROJ-42`,
    `PROJ-420` → `PROJ-42` 오추출. **1회차는 프로즈로만 경고하고 정규식에 반영 안 했다**
- **★ 값 복제**. BC 격리로 `IssueKey.REGEX`(`issue/domain/IssueKey.kt:31`) import 불가 →
  `AtlasIssueUrlParser.kt:56` 선례대로 값 복제 + **주석에 복제 사실 명시**
- **distinct** — 같은 키 중복 언급은 1건

### 3.5. ★★ 파이프라인 — PR_MERGED는 제3의 경로다 (B2-be 해소)

> **1회차의 가장 큰 오류.** 1회차는 `TriggerMatcher` wire 맵 등록을 완료 기준(§9-6)으로 요구했다.
> **그 코드는 아무도 실행하지 않는다.**

**사실**.
- `q_automation_events`는 **issue-tracking이 소유·생성한 이슈 이벤트 fan-out 큐**(`AutomationEventWorker.kt:20` KDoc)
- `TriggerMatcher.WIRE_TYPE_TO_TRIGGER_TYPE`(`:42-47`)는 `issue.created/updated/commented` **3종만**
- issue-tracking은 GitHub PR 머지를 알 수 없다 → **Git 웹훅은 이 큐에 올라갈 수 없다**
- `git_webhooks`에 `rule_id` 없음 = **프로젝트 스코프**

**확정 — 경로**.
```
GitWebhookController
  ① 토큰 SHA-256 → git_webhooks 조회 (1 쿼리)          ← 미존재/삭제 = 401
  ② readNBytes(MAX+1) 크기 상한                          ← 413
  ③ secret 복호화 → provider별 서명 검증                 ← 401 (여기까지 통과해야 DB 쓰기 0)
  ④ 이벤트 종류 판정 (머지 아니면 202 종료)
  ⑤ 이슈키 추출 + 프로젝트 스코프 필터 + 팬아웃 상한
  ┌─ 단일 @Transactional ────────────────────────────┐
  │ ⑥ dedup INSERT (충돌 = 중복 → 202 종료)          │
  │ ⑦ findEnabledByProjectAndTriggerType(pk, PR_MERGED) (1 쿼리)
  │ ⑧ targetBranch 인메모리 필터                      │
  │ ⑨ 이슈키 × 매칭룰 N×M enqueue → q_automation_execution
  └───────────────────────────────────────────────────┘
  ⑩ 202
```
- **`TriggerMatcher`·`AutomationEventWorker`·`q_automation_events`를 쓰지 않는다.**
  **wire 맵에 `pr.merged` 추가 금지**(죽은 코드)
- 하류는 기존 `AutomationExecutionWorker` → `ActionExecutor` 재사용 (**FR-AT-01 WEBHOOK 경로와 동일**)
- **★ 단일 트랜잭션 (DEC-23·C1-be)**. `AutomationExecutionEnqueuer.kt:22-25` KDoc — *"호출자에
  트랜잭션이 있으면 그 안에서 enqueue되어 원자적으로 커밋"*. ⑥~⑨를 한 `@Transactional` 서비스 메서드로
  묶으면 부분 실패 시 **dedup도 롤백** → GitHub 재전송이 정상 복구. **1회차 EC10(영구 유실)은 근거 없이
  받아들인 트레이드오프였다 → 폐기**
- self-invocation 무관(컨트롤러→서비스 호출, [[transaction-self-invocation-requires-new]])

### 3.6. targetBranch — 4계층 (FR-C5)

> **★ 1회차는 3계층이라 했다** (plan G11). **4번째를 빠뜨렸고 그게 B4-be**.

| # | 계층 | 위치 | 내용 |
|---|---|---|---|
| ① | **검증** | `TriggerConfig.kt` | **PR_MERGED 전용 분기 신설.** `:46`의 `-> Unit` 그룹에 얹으면 **컴파일 통과 + 무검증** → `targetBranch: 123`·오타 키 저장 → 매칭 실패 → 관대한 기본값(부재=전체 발화)으로 **B8 실패 모드 부활** (C6-sec) |
| ② | **매칭** | GitWebhookController (§3.5 ⑧) | 인메모리 정확 일치. 미지정=전 브랜치 |
| ③ | **프론트** | `automation-rules.types.ts:233-237` | `omitManagedKeys`에 `targetBranch` 등록 (미등록 시 `baseConfigJson` 병합이 빈 값을 보존해 `fields`와 비동형) |
| ④ | **★ 충돌 분석** | `RuleConflictAnalyzer.kt:244-245` | **`targetBranchCoFire` 신설** |

**④ 상세 (B4-be)**. 현재 `coFire`.
```kotlin
a.triggerType != b.triggerType -> false
a.triggerType == TriggerType.WEBHOOK -> false
a.triggerType != TriggerType.ISSUE_UPDATED -> true   // ← PR_MERGED가 여기로 떨어짐
else -> fieldsCoFire(a.triggerConfig, b.triggerConfig)
```
targetBranch가 다른 두 PR_MERGED 룰(= **S5가 정확히 그 시나리오**)이 무조건 "동시 발화 가능"으로 판정되고,
`AutomationRuleController.kt:118,240,282`가 `conflicts`를 **REST 응답 body에 그대로 실어 사용자에게 노출**한다.
→ **이 기능이 지향하는 사용 패턴에서 거짓 경고**. `fieldsCoFire` 동형으로 `targetBranchCoFire` 추가
(양쪽이 겹치거나 한쪽이 비었을 때만 true).

### 3.7. 팬아웃 3중 상한 (FR-C8 — B1 해소)

> **★ 1회차는 마스터 §C-4의 확정 3개 중 1개만 가져왔다.** 두 검토자가 독립적으로 같은 지적.

| # | 상한 | 초과 시 |
|---|---|---|
| ① | distinct 이슈키 **≤ 20** | 202 + WARN, **처리 0건**(fail-closed — 일부 처리는 비결정적) |
| ② | **triggerEvent `pr.title`/`pr.body` 각 2KB 절단** | 절단 후 진행 |
| ③ | **룰 수 × distinct 키 수 ≤ 100** | 202 + WARN, **처리 0건** |

**②③이 왜 필수인가**. `AutomationRuleRepository.kt:152-162`가 프로젝트당 룰 수에 **상한을 걸지 않고**
(`MAX_RULES` grep 0건) 전부 반환한다. 그리고 저장 경로가 **영구**다.
- `AutomationExecutionEnqueuer.kt:59` — `triggerEvent`를 **통째로** 큐에 적재
- `AutomationExecutionWorker.kt:251`(성공)·`:209`(억제 스킵) **둘 다** `archiveMessage` →
  `pgmq.a_q_automation_execution` 영구. **아카이브 정리 배치 저장소 전체 0건**
- `V305__rule_executions.sql:22` — `trigger_event JSONB NOT NULL`, 실행마다 1행, 보존 배치 없음

→ 20키 상한만으로는 **비율만 줄고 무한성은 그대로**. S6이 자인하듯 replay가 가능하므로(유효
(body,signature) 1쌍이면 delivery UUID만 갈아끼워 무한 재전송) 재전송 1회 = 20키 × N룰 × 최대 256KB 영구 적재.
억제창(`AutomationExecutionWorker.kt:201`)은 **실행만** 막고 enqueue·archive는 못 막는다.

### 3.8. dedup (FR-C9 — C3-sec 해소)

- **키**. GITHUB `X-GitHub-Delivery` / GITLAB `X-Gitlab-Event-UUID`
- **★ 순서 고정 (DEC-23)**. **서명 검증 통과 후에만** dedup INSERT.
  1회차는 "enqueue 전"만 규정하고 **서명 검증과의 상대 순서를 열어뒀다** → 중복 요청의 HMAC 비용을
  아끼려 dedup을 앞에 두는 자연스러운 최적화를 하면, **secret 없이 토큰만 아는 자가 `delivery_id`를
  바꿔가며 무한 INSERT**(디스크 고갈). "보존 7일"은 못 막는다 — 시간 기반 보존은 정직한 트래픽의
  정상상태를 재는 장치이고 **공격자는 증가율을 스스로 정한다**
- **불변식**. **서명 미검증 요청은 DB에 어떤 쓰기도 남기지 않는다**
- **헤더 부재** → dedup 불가, **처리 진행**(fail-open) + WARN. dedup은 보안 통제가 아니라 재시도 완화책
- **보존**. 7일 + 삭제 배치 (automation `@Scheduled` 결선 확인 후 동형)
- **replay 방어 아님** — GitHub HMAC은 본문만 서명하고 `X-GitHub-Delivery`는 **서명 대상 밖**.
  GITLAB은 평문 토큰이라 본문 무결성 0 → rawBody SHA fallback도 무의미. **ADR 잔여위험**

### 3.9. 등록 API secret 검증 (FR-C10 — B4-sec 해소)

> **★ 1회차는 `secret: string`이라고만 썼다.** GITLAB은 평문 비교(`X-Gitlab-Token`)라 `secret=""`이면
> 공격자가 **빈 값을 보내 일치**시킨다. 웹훅 URL의 토큰만 알면 뚫리는데, URL은 GitHub 설정 화면·CI 로그·
> 프록시 로그로 새는 값이다(**그게 secret이 따로 존재하는 이유**). `secret_encrypted TEXT NOT NULL`은
> **빈 문자열의 암호문**을 막지 못한다.

- **등록 시**. `@field:NotBlank` + **최소 길이 16** + 최대 길이 상한
- **★ 검증 시에도**. 복호화 결과가 blank면 **fail-closed 401**
  (`SlackSignatureVerifier.kt:72-74` `if (signingSecret.isBlank()) return false` 선례 — **1회차가 인용한
  `:64-66`은 stale**, #275의 ByteArray 경로 추가로 이동)
- EC15 재정의 — "미설정"이 아니라 **"복호화 결과 blank"** (NOT NULL과 모순 없이 도달 가능)

### 3.10. triggerEvent 스키마 (C7-sec 해소 — D1/D2의 실체)

> **★ 1회차는 스키마를 정의하지 않았다.** FR-C13(issueKey prefix)·FR-C8②(절단 대상)·FR-C5(targetBranch)가
> 전부 이 스키마에 의존한다.

```json
{
  "issueKey": "PROJ-42",          // 필수. ActionExecutor.extractIssueKey:271-275 규약(최상위 issueKey)
  "provider": "GITHUB",
  "pr": {
    "number": 123,
    "title": "...",               // ≤ 2KB 절단
    "body":  "...",               // ≤ 2KB 절단
    "targetBranch": "release/1.2",
    "mergedAt": "2026-07-17T...",
    "url": "https://github.com/..."
  }
}
```
- **이슈키 1개당 1 이벤트** — 팬아웃은 이벤트를 복제(각 `issueKey`만 다름)
- **`actorId` 없음** — GitHub 사용자는 BTS user가 아니다. `buildContext:287`이 `actorId` 부재 시
  `actor`를 빈 맵으로 → `{{actor.id}}`는 빈 값. **위조된 actor를 신뢰하지 않는다**
- **★ 최상위에 `title`/`body`를 두지 않는 것이 의도적** — `buildContext:279`가
  `if (triggerEvent.has("issue")) ... else triggerEvent` 라 **`issue` 키가 없으면 triggerEvent 전체를
  issue로 취급**한다. 최상위 `title`이 있으면 `{{issue.title}}`이 **PR 제목**으로 렌더돼 §C-7 C-k가
  우려한 오염이 발생. `pr` 하위로 내리면 `{{issue.title}}`은 빈 값이고 `{{issue.pr.title}}`로 명시 접근 →
  **C-k 한계를 설계로 회피**(1회차는 "후속 한계로 명시"였다)
- **템플릿 오염 잔존분**. `{{issue.pr.title}}`·`{{issue.pr.body}}`는 여전히 외부 입력(§1 신뢰 경계).
  ADD_COMMENT 액션과 조합 시 PR 본문이 코멘트에 렌더됨 — **의도된 동작**이나 ADR에 명시

### 3.11. ★★ FR-C13 방어심층 — null과 비교 방식 (DEC-26, outside voice 발견)

> **2회차는 "룰 projectKey ≠ 이슈키 prefix → SKIPPED"만 적었다. 그대로 구현하면 SCHEDULED가 죽는다.**

**① null issueKey는 게이트를 통과시킨다 (기존 동작 보존).**
`AutomationScheduleWorker.kt:101`이 `enqueuer.enqueue(rule.id, rule.triggerType, objectMapper.createObjectNode())`
— **빈 `{}`** 를 발행한다 → `extractIssueKey`(`ActionExecutor.kt:271-275`)가 **null**. null은 prefix가 없다.
"prefix가 projectKey와 같아야 한다"를 자연스럽게 구현하면 **모든 SCHEDULED 룰 + issue-less WEBHOOK 룰이
SKIPPED**가 되어 **FR-AT-01이 사문화**된다. 그런데 automation BC는 7/7 완료로 선언된다.
- **null은 오늘 합법이다** — `attemptWebhook`(`ActionExecutor.kt:252-260`)이 issueKey를 쓰지 않아
  CALL_WEBHOOK 룰이 정상 동작 중. issueKey가 **필요한** 액션은 `FAILURE_ISSUE_KEY_MISSING`(`:234`)로 이미 처리됨
- **확정**. 게이트는 **issueKey가 non-null일 때만** 판정. null → 기존 하류 로직에 위임(**동작 변경 0**)

**② 비교는 `"${rule.projectKey}-"` 접두여야 한다.**
`issueKey.startsWith(rule.projectKey)`면 **`PROJ2-1`이 `PROJ` 룰을 통과**한다 — prefix 정규식
`^[A-Z][A-Z0-9]{1,9}$`(ADR `2026-05-22-issue-key-prefix-policy`)가 `PROJ`와 `PROJ2`를 **둘 다 허용**하므로
실재 가능한 조합이다. **하이픈까지 포함**해야 정확.
> 2회차가 **추출** 정규식의 뒤 경계(C2-be)엔 CONCERN을 쓰고, 정작 **FR-C7·FR-C13이 둘 다 의존하는 비교**는
> 명세하지 않았다.

## 4. 비기능 요구사항

| ID | 요구사항 | 검증 (★ B9 — 1회차 마스터는 검증 항목 0건이었다) |
|---|---|---|
| NFR-1 | 동기 경로 < 200ms (p95) | **DB 왕복 = 토큰조회 1 + dedup INSERT 1 + 룰조회 1 + enqueue N×M**. 완료 기준에 왕복 수 단언 + p95 실측 |
| NFR-2 | 미인증 힙 적재 방어 | **핸들러 시그니처 화이트리스트 단언**(§9-1) + 413 실서블릿 |
| NFR-3 | secret·토큰 평문 미저장·미로그 | 로그에 webhookId·projectKey만 |
| NFR-4 | 서명 검증 실패율 100% 차단 | 양성 + 음성(본문 판별자) |

## 5. API 인터페이스

### 5-1. 인바운드 (permitAll)
```
POST /api/v1/webhooks/git/{token}
  consumes: application/json          ← form-urlencoded 는 415 명시 거부 (§C-3)
  GITHUB → X-Hub-Signature-256: sha256=<hex>, X-GitHub-Event, X-GitHub-Delivery
  GITLAB → X-Gitlab-Token: <plain>,           X-Gitlab-Event,  X-Gitlab-Event-UUID
  → 202 (정상·무시 모두) | 401 | 413 | 415 | 400
```

**★ 401 응답 본문 단일화 (B3-sec 해소).** EC1~EC4·EC9·EC15 **전부 동일 errorCode**
`GIT_WEBHOOK_UNAUTHORIZED`. 사유 구분은 **로그·메트릭에서만**.
> 1회차는 §9-3이 `GIT_WEBHOOK_INVALID_SIGNATURE`를 판별자로 쓰면서 EC9는 "서명 불일치와 반드시 구분"을
> 요구해 **정면 충돌**했다. 마스터 C-7 원문(`:310`)의 "구분" 대상은 **로그·메트릭**인데 1회차가 그 문맥을
> 잘라냈다. errorCode가 사유별로 갈리면 **404를 포기하면서까지 막은 존재 오라클이 응답 본문으로 부활**한다.
> §9-3의 판별자 역할은 **"필터가 아니라 컨트롤러가 준 401"의 증명**이지 사유 구분이 아니다.

### 5-2. 등록 API (인증 + `MANAGE_AUTOMATION`)
```
POST   /api/v1/projects/{projectKey}/automation/git-webhooks
       { provider: GITHUB|GITLAB, secret: string(NotBlank, 16..) }
       → 201 { id, provider, webhookUrl, token }     ← token 1회 노출
GET    → 200 [{ id, provider, createdAt, createdBy }]  ← token·secret 절대 미포함
DELETE /{id} → 204
```
- **가드 순서**. `AutomationActorExtractor.extract()`(401) → `assertManageAutomationPermission`(403) →
  리소스 조회. `AutomationRuleController.kt:64-67` 동형 ([[auth-extraction-before-resource-lookup]])
- **★ 동형 복제 시 가드 전수 대조** ([[isomorphic-clone-permission-guard-gap]])
- `MANAGE_AUTOMATION` 실재 확인 — `V035__manage_automation_permission.sql:19`(PROJECT_ADMIN 시드) ·
  `IdentityAccessAutomationPermissionResolver.kt:68`. **신규 권한코드 없음** → 권한 시드 카운트 가드
  무관([[fr-pm-permission-seed-migration-test-coupling]])

## 6. 데이터 모델 (DEC-20 — V307~V309)

| 파일 | 내용 |
|---|---|
| **V307** | `git_webhooks` 신규 |
| **V308** | `git_webhook_deliveries` 신규 |
| **V309** | `ck_automation_rules_trigger_type` CHECK **5→6** (DROP→ADD) **+ ★ COMMENT 재발행** |

```sql
-- V307
CREATE TABLE git_webhooks (
    id UUID PRIMARY KEY,
    project_key VARCHAR(10) NOT NULL,
    provider VARCHAR(16) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    secret_encrypted TEXT NOT NULL,          -- AES-256-GCM (outbound_webhooks V603:6 선례)
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT ck_git_webhooks_provider CHECK (provider IN ('GITHUB','GITLAB'))
);
CREATE UNIQUE INDEX uq_git_webhooks_token_hash ON git_webhooks(token_hash) WHERE deleted_at IS NULL;

-- V308
CREATE TABLE git_webhook_deliveries (
    webhook_id UUID NOT NULL REFERENCES git_webhooks(id) ON DELETE CASCADE,
    delivery_id VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (webhook_id, delivery_id)
);
CREATE INDEX ix_git_webhook_deliveries_received_at ON git_webhook_deliveries(received_at);
```

**★ V309는 COMMENT를 반드시 재발행한다 (C4-be).** `V300__automation_rules.sql:67`이
`COMMENT ON COLUMN automation_rules.trigger_type IS '트리거 종류 — CHECK 5종(...)'`.
`V306:21-24`가 정확히 같은 상황(action_type 4→5)에서 *"COMMENT 재발행 — 갱신하지 않으면 운영 DB의
코멘트에 '4종' drift가 영구히 남는다"*는 선례를 남겼다. **1회차는 그 선례를 인용하면서 정작 V309 계획에서
재발행을 빠뜨렸다.**

- `token_hash` 부분 UNIQUE — `V300:53-55` 선례 동형
- FK CASCADE 필수 ([[join-table-fk-cascade-testcontainers-cleanup]])
- **init_codegen 면제** — automation은 JdbcTemplate (jOOQ 4모듈에 automation 없음)
- **`:modules:app:test`는 5433 영속 DB** ([[app-test-persistent-db-migration-checksum-trap]]) — 적용 후 편집 금지
- 테이블 카운트 가드 부재 확인(G3) → 신규 테이블이 기존 테스트를 깨지 않음

## 7. 엣지 케이스

| ID | 상황 | 기대 |
|---|---|---|
| EC1 | 토큰 미존재/소프트삭제 | **401** (`GIT_WEBHOOK_UNAUTHORIZED`) |
| EC2 | GITHUB 등록인데 `X-Gitlab-Token` 만 | **401**. 폴백 금지 |
| EC3 | GITLAB 등록인데 `X-Hub-Signature-256` 만 | **401**. 폴백 금지 |
| EC4 | GitHub 레거시 `X-Hub-Signature`(SHA-1) | **401** (SHA-1 미지원) |
| EC5 | 본문 > 256KB | **413**, 서명 검증 **이전**, `readNBytes` |
| EC6 | `Content-Type: x-www-form-urlencoded` | **415** |
| EC7 | JSON 파싱 실패 | **400** |
| EC8 | **등록 시** 암호화 키 미설정 | **500** (운영자 즉시 인지) |
| EC9 | **검증 시** 복호화 실패 | **401 (동일 errorCode)** + **ERROR 로그**(id·projectKey만) + 메트릭. 구분은 로그에서만 |
| EC10 | 팬아웃 중 일부 enqueue 실패 | **전량 롤백** (dedup 포함) → 재전송이 정상 복구. **1회차의 "영구 유실"은 폐기** (DEC-23) |
| EC11 | delivery 헤더 부재 | dedup 불가 → 처리 진행 + WARN |
| EC12 | distinct 키 > 20 **또는** 룰×키 > 100 | **202 + WARN, 처리 0건** |
| EC13 | 이슈키 추출 0건 | 202 |
| EC14 | 활성 룰 0건 | 202 |
| EC15 | **복호화 결과 blank** secret | **401** (fail-closed) |
| EC16 | `Closes PROJ-42x` / `PROJ-420` | `PROJ-42`로 **오추출 안 됨** (단어 경계) |

## 8. 제약

1. **`@RequestBody` 금지** — `HttpServletRequest.inputStream.readNBytes(MAX+1)`. HMAC은 **raw 바이트에 직접**
2. **`@RequestParam`/`@ModelAttribute` 병용 절대 금지**
3. **★ bearer skip 필수** (G5) — GitHub form-urlencoded 시 `access_token` 조회가 Tomcat 파싱을 유발해
   본문 소진. 누락 증상은 permitAll 미등록과 **구분 불가능한 401**
4. **CSRF는 `antMatcher(method, path)`** — 문자열 오버로드는 메서드 고정이 조용히 사라지며 컴파일·테스트 통과
5. **`/api/**` authenticated(`SecurityConfig.kt:209`)보다 위**
6. **`/*` 단일 세그먼트** — `/**` 금지. `AutomationTestSecurityConfig.kt:49`의 `/**`도 `/*`로 정합화 +
   **git 경로 permitAll 추가**(NIT-be)
7. **prod 조립 테스트는 `ProdAssemblyHttpTestBase` 상속만**. 신규 프로퍼티는 **베이스 `props()`에 추가**
8. **MockMvc 금지** ([[multipart-default-limit-app-policy-false-green]])
9. **상수시간 비교** `MessageDigest.isEqual`
10. **fail-closed** — boolean 수렴 후 컨트롤러가 401 매핑
11. **cross-BC 조립 재검증** — 머지 전 rebase + `:modules:app:test` ([[prod-assembly-boot-verification-required]])
12. **`@param:Qualifier("automationSecretEncryptor")`**
13. **★ `AutomationRuleService.kt:210,890`은 변경하지 않는다** (C3-be) —
    `if (triggerType == WEBHOOK) mintWebhookToken(...) else null`에서 PR_MERGED가 `else null`로 떨어지는 게
    **정답**. git 토큰은 프로젝트 단위 `git_webhooks` 소유이지 룰별 `automation_rules.webhook_token_hash`가
    아니다. **구현자가 "WEBHOOK과 비슷하니까"로 토큰 발급을 추가하면 아무도 조회하지 않는 죽은 토큰이 룰마다 생긴다**
14. **`SLACK_INBOUND_PATHS` 리네이밍 검토** — 경로군 2개가 얹히면 이름이 거짓이 됨 (NIT-be). plan에서 결정

## 9. 측정 가능한 완료 기준

> ★ 1회차는 14개 중 **핵심 기능(S1/S5) 검증 0건**이었고 **FR-C15 검증 0건**이었다.

**기능 (B3-be 해소 — 1회차 전무)**

> **★★ 3회차 정정 (outside voice) — 2회차의 §9-1은 실현 불가능했다.**
> 2회차는 "이슈 fixVersions **실제 변경** 확인"을 요구했으나 **관측할 수단이 저장소에 없다**.
> ① automation test-boot은 **stub을 쓴다** — prod 어댑터(issue-tracking `AutomationIssueMutationAdapter`)가
> **automation의 컴파일/테스트 클래스패스에 존재하지 않는다**(`StubIssueMutationPort.kt:14-22`가 그 사실을
> 명시). ② 조립 앱은 **dev seed 비활성**(`application.yml:29` — "모듈마다 존재해 classpath 충돌 → 조립
> 앱에선 비활성. 필요 시 별도 seed 전략") → 9-BC prod 조립에 프로젝트·유저·이슈·버전이 **없다**.
> **게다가 18 task 중 아무도 §9-1을 자기 일로 적지 않았다.** B3-be("핵심 기능 검증 0건")를 고친다면서
> **완료기준 문장만 쓰고 task 배정도 실현가능성 확인도 안 한** — 같은 실패의 한 층 위 반복.
> → **Maxi 확정 DEC-25 (아래 A)**.

1. **S1 happy path 통합 (T9 단위 + T15 조립)** — HMAC 실계산 요청 → `q_automation_execution` 도달 →
   워커 소비 → **`IssueMutationPort.setFixVersions(issueKey, versionIds)` 호출까지** 검증
   (`StubIssueMutationPort`가 command 기록 → issueKey·versionIds 단언).
   **★ 책임 분리 (DEC-25)** — 그 너머 "**포트 호출 → 실제 이슈 변경**"은 **PR-B(#276)가 이미 검증했다**
   (issue-tracking `AutomationIssueMutationAdapter` 테스트 — OCC 재조회 + `runWithOccRetry`).
   **두 PR이 이어지면 S1 전구간이 덮인다.** 이 경계를 KDoc·plan에 명시해 "안 쟀다"와 구분한다
2. **S5 targetBranch 선택 발화 통합** — release/1.2 룰만 발화, release/2.0 룰 skip
3. **④ 충돌 분석** — targetBranch 다른 두 PR_MERGED 룰에 **경고가 뜨지 않음** (B4-be)
4. **① 검증 분기** — PR_MERGED가 `TriggerConfig.validate`에 **전용 분기**를 갖고 targetBranch 타입/공백 거부 (C6-sec)
5. **팬아웃 3중 상한** — 20키 초과 0건 처리 / 룰×키 100 초과 0건 / **enqueue된 payload의 `pr.title`·`pr.body` ≤ 2KB**
6. **EC16 단어 경계** — `Closes PROJ-42x`/`PROJ-420` 오추출 0

**보안**

7. **§9-1 화이트리스트 단언 (C4-sec)** — 핸들러 시그니처가 **정확히 `(@PathVariable String, HttpServletRequest)`**
   이고 그 외 파라미터 애노테이션 **0개**. 1회차의 "`@RequestBody` 미사용"은 blacklist 1개라
   `@RequestParam`·`@ModelAttribute`·`HttpEntity<ByteArray>`가 전부 통과했다.
   **선례 실재** — `SlackInboundBodyGuardTest.kt:76-88` 리플렉션
8. **양성 단언 (git)** — 유효 HMAC 직접 재계산 → **202** (permitAll+CSRF+bearer skip 3곳 + 서명 검증 동시 증명)
9. **★ 양성 단언 (automation) (B2-sec)** — FR-C15 경로도 **동일하게** 202 실증.
   BC test config는 `AutomationTestSecurityConfig.kt:47 csrf { it.disable() }`이라 **중앙 CSRF-ignore 누락을
   원리적으로 못 잡는다** → `app` 조립 테스트가 유일한 관문
10. **음성 단언 — 판별자는 응답 본문** ([[negative-guard-needs-body-discriminator]])
11. **★ 오라클 부재 실증 (B3-sec)** — **EC1 응답 본문과 S2 응답 본문이 (timestamp 제외) 동일**
12. **★ 서명 미검증 요청은 DB 쓰기 0 (C3-sec)** — 서명 틀린 요청 N회 후 `git_webhook_deliveries` 행 수 **불변**
13. **★ 위반 주입으로 가드 실증** ([[archunit-vacuous-rule-silent-pass]]) — permitAll 목록에서 git 경로를
    빼고 fail 확인 후 되돌림. **automation 경로에 대해서도 별도 수행**
14. **secret 검증** — 등록 시 blank/15자 거부 + 검증 시 복호화 blank → 401
15. **EC5 413 실서블릿** (`TestRestTemplate`)

**회귀**

16. **★ 파급 전수 grep 재검증** ([[spec-stated-count-becomes-blindfold]]) — plan G2 표의 개수를 **세지 말고**
    `grep -rn "TriggerType\." backend/modules/automation/src/main` 으로 직접. **표에 없던 지점이 2개 더
    발견된 전력**(`AutomationRulesYaml.kt:63`, `AutomationExecutionWorker.kt:437` — 둘 다 무해)
17. **`TriggerMatcher` wire 맵에 `pr.merged`가 없음을 확인** (§3.5 — 있으면 죽은 코드)
18. `TriggerConfigTest.kt:15,17,21-29` + **`:16`·`:20` it 문구** (N2-sec) 5→6
19. `SchemaMigrationTest.kt:561` **프로브 값 교체** + `:553-557` 유효 6종 + `:551`·`:554` 문구
20. **FR-C13 방어심층** — **조건 없는 룰**에도 게이트가 걸림 + **replay 경로(`RuleExecutionService.kt:160`)에도** (C1-sec)
21. **NFR-1** — DB 왕복 수 단언 + p95 실측 기록
22. `:modules:automation:test` + `:modules:app:test` + `:modules:identity-access:test` +
    `:modules:slack-integration:test` 회귀 0
23. `pnpm typecheck`(tsconfig.app.json) + `pnpm test` 회귀 0 ([[zod-schema-strengthen-inline-mock-fanout]])
24. `ktlintCheck` + `detekt` 0 (SecurityConfig `@Suppress("LongMethod")` 임계 이미 1줄 초과)
25. `bash scripts/verify-master-plan.sh` 통과

## 10. 후속 (본 PR 범위 밖 — 명시)

- **D6/D7 UI + E2E** → **PR-D** (FR-AT-07 완료·BC 7/7 마킹은 거기서)
- **GitHub replay 방어** — 구조적 불가. 완화는 멱등 처리·팬아웃 상한·rate limit
- **★ rate limit** — 인바운드 3종 공통 부재. **본 PR로 등급 상승**: PR-A ADR은 "CPU 소모"(R1) 수준으로
  봤으나, 팬아웃 + 영구 아카이브가 붙으면 **디스크 고갈**이 된다. FR-C8 3중 상한이 1차 방어이나
  **요청 빈도 축은 여전히 무방비**
- **pgmq 아카이브·`rule_executions` 보존 배치 부재** — 저장소 전체 0건 (본 PR이 만든 문제 아님, 등급 상승분)
- **`PathContributor` 확장점** — permitAll 하드코딩이 부채의 구조적 원인
- **`hasObservableSideEffect` exhaustive when 전환** (`pr-b-fix-version.md:766`)
- **`DATA.md:85-94` 나머지 6행 stale** (DEC-21 — automation 행만)
- **`fr-index.md:5` stale "122"** — #277 몫
- **`sha256Hex` private top-level 중복** (G14)
- **프로젝트당 룰 수 상한(`MAX_RULES`) 부재** — 코드 전역 0건

## 11. Maxi 확정 결정

| ID | 결정 |
|---|---|
| DEC-18 | **PR-C = 백엔드만.** D6/D7은 PR-D (선례 6/6) |
| DEC-19 | secret = **선택적 연동**. `.env.prod.example`에 주석 처리 신규 §Git 웹훅 (slack `:67-78` 동형) |
| DEC-20 | 마이그레이션 **분리** V307/V308/V309 |
| DEC-21 | `DATA.md:90` **automation 행만** 갱신 |
| **DEC-22** | **FR-C15 포함 + 잔여위험 ADR 명시.** §1.4 예외는 **게이트1 승인이 성립 요건** — ADR 작성은 산출물이지 승인이 아님 (C5-sec) |
| **DEC-23** | dedup은 **서명 검증 후** + ⑥~⑨ **단일 트랜잭션**. EC10 "영구 유실" 폐기 |
| **DEC-24** | FR-C13은 **`ActionExecutor.execute` 내부** (3경로 단일 choke point) |
| **DEC-25** | **§9-1은 `setFixVersions` 포트 호출까지** 검증(stub command 관측). "포트 호출 → 실제 이슈 변경"은 **PR-B가 이미 검증** — 책임 분리 명시. automation은 prod 어댑터가 클래스패스에 없고 조립 앱은 seed 비활성이라 **관측 수단이 없다** (outside voice 발견) |
| **DEC-26** | FR-C13 게이트는 **issueKey non-null일 때만** 판정(null → 기존 위임, SCHEDULED 보호) + 비교는 **`"${projectKey}-"` 접두**(`PROJ2-1`이 `PROJ` 룰 통과 방지) |
| **DEC-27** | **B4-be 등급 하향 BLOCKER→P2.** `RuleConflictAnalyzer:230-237` KDoc이 "FP > FN 보수 정책"을 **문서화된 의도**로 명시 → PR_MERGED가 `:245`로 떨어지는 건 설계대로 동작. **T4는 유지**(targetBranch는 cron과 달리 정확 문자열 비교라 정밀) 하되 **KDoc도 함께 갱신**(안 하면 문서가 코드와 모순) |

### ★ DEC-22 상세 — automation permitAll의 정직한 프레이밍 (C2-sec·C5-sec)

PR-A ADR의 §1.4 정당화 논거는 *"필터가 비키는 자리에 **더 강한 검증이 이미 서 있다**"*(D3-b)였다.
**git 경로는 HMAC이 서 있어 성립한다. 그러나 `AutomationWebhookController`에는 서명 검증이 없다**
(불투명 토큰 소지가 곧 인증). → **동일 프레이밍을 automation에 그대로 쓸 수 없다.** ADR에 별도 기술.

**FR-C13이 PR-A의 DEC-15 우려를 "없애지" 않는다 — 과장 금지.**
PR-A ADR `:143-145`의 위험은 *"룰을 임의 이슈로 유도, 폭발 반경은 룰 actor 권한"*.
FR-C13(projectKey prefix 대조)은 **cross-project만** 막는다. **같은 프로젝트 안에서는 토큰 보유자가
`{"issueKey":"PROJ-<임의>"}`로 여전히 임의 이슈를 룰 actor 권한으로 조작할 수 있다**
(`ActionExecutor.kt:176`이 조건 없는 룰을 게이트 없이 통과). → **ADR 잔여위험표에 등재**해 PR-A의 판단과
정직하게 잇는다. 근본 해소(조건 없는 룰에도 `createdBy` 가시성 게이트)는 **전 트리거 동작 변경이라 별도 PR**.

## 부록 A. Phase B 적대적 검토 (1회차 — BLOCKER 7 / CONCERN 12 / NIT 7)

security-engineer + backend-engineer 병렬, 실제 코드 대조. **인용 대부분은 정확했으나 설계에 결함.**

| # | BLOCKER | 해소 |
|---|---|---|
| B1 (양쪽 독립 지적) | §C-4 확정 3개 중 **2개(절단·곱 상한) 소실** → 저장 폭발 무한성 잔존 | §3.7 |
| B2-be | **PR_MERGED 파이프라인 미정의** — §9-6이 죽은 코드를 만들라고 지시 | §3.5 |
| B3-be | §9에 **핵심 기능(S1/S5) 검증 0건** | §9-1~6 |
| B4-be | `RuleConflictAnalyzer.coFire`가 targetBranch 미인지 → **이 기능의 사용 패턴에서 거짓 경고** | §3.6 ④ |
| B2-sec | §9에 **FR-C15 완료 기준 0건** — 가장 위험한 변경이 무검증 | §9-9,13 |
| B3-sec | §9-3(errorCode 판별자) ↔ EC9("반드시 구분") **충돌 → 오라클 부활** | §5-1 |
| B4-sec | 등록 API **secret 검증 부재** → GITLAB 빈 secret = 토큰만으로 우회 | §3.9 |

**CONCERN 12** — C1-sec(FR-C13 배치→`ActionExecutor` 내부) · C2-sec(FR-C13 과장 금지) ·
C3-sec(dedup 순서) · C4-sec(§9-1 화이트리스트) · C5-sec(§1.4 승인·프레이밍) · C6-sec(targetBranch 검증) ·
C7-sec(triggerEvent 스키마) · C1-be(단일 트랜잭션) · C2-be(정규식 단어 경계) · C3-be(webhookToken 불변) ·
C4-be(V309 COMMENT) · C5-be("13"→라인 16). 전건 반영.

**NIT 7** — 인용 stale 정정 3건(`SlackSignatureVerifier` `:64-66`→**`:72-74`** ·
`extractIssueKey` `:264-268`→**`:271-275`** · `.env` `:66-78`→**`:67-78`**) · `SecurityConfig.kt:44-52`
KDoc 동기화 · `TriggerConfigTest.kt:16,20` 문구 · `AutomationTestSecurityConfig` git 경로 ·
`SLACK_INBOUND_PATHS` 리네이밍.

> **★ 인용 stale의 교훈** (N1-sec). PR-A ADR `:82-83`이 *"plan·spec이 인용한 줄번호는 파일 이전 리비전
> 기준이라 어긋난다. 위 표가 현행 코드 기준 검증값이다"*라고 **각주까지 달았는데**, 그 표의 `:64-66`이
> #275로 **다시 stale**이 됐고 본 스펙 1회차가 **그걸 복사**했다. "인용만 믿지 마라"의 실례 —
> impl·리뷰는 인용 줄번호를 **재확인**한다.

### 검토가 확인한 "맞는 부분" (코드 대조 완료)

`MANAGE_AUTOMATION` 실재(fail-closed resolver) · G5의 "3곳"과 `:209` 순서 · `SchemaMigrationTest` 인용
전부 정확(plan G4의 `:559-563`이 마스터 C-h의 `:559-562`보다 정확) · `SecretEncryptor` 3빈 전부
`@Qualifier` 보유 → 4번째 안전 · G10 핵심 주장 사실(`:176` 조건 없는 룰 통과 + `:271-275` 무검증 신뢰) ·
`readBoundedBody` 선례 정확 · replay 불가 판단 정확 · §9-1 리플렉션 단언 **선례 실재**
(`SlackInboundBodyGuardTest.kt:76-88`) · 테이블 카운트 가드 부재 → 신규 테이블 안전 ·
`AtlasIssueUrlParser` 값 복제 패턴 타당 · **팬아웃 20은 실행 단계에선 충분**(억제창이 같은 20키 반복을
막음 — C-4의 "억제창 무력"은 20k 키 전제였고 상한이 그 전제를 깼다). **단 억제는 enqueue·archive 이후라
저장 폭발은 못 막음 → B1 유효.**

## Brainstorming Check

✅ **통과 (2회 iteration)**.

1회차 = 적대적 검토 2종 병렬(security + backend, 실제 코드 대조) → **BLOCKER 7 / CONCERN 12 / NIT 7**.
2회차 = 전건 반영 + Maxi 확정 3건(DEC-22~24).

**1회차가 잡아낸 가장 큰 것 3가지.**
1. **B2-be** — **내가 쓴 완료 기준(§9-6)이 죽은 코드를 만들라고 지시했다.** `q_automation_events`는
   issue-tracking 소유 큐라 Git 웹훅이 올라갈 수 없는데, 내 도메인 정리(G2)가 `TriggerMatcher` wire 맵
   누락을 "1순위 조용한 실패"로 단정했고 스펙이 그걸 물려받았다. **실측을 해놓고 해석을 틀렸다.**
2. **B3-sec** — **내가 마스터 스펙의 문맥을 잘라내 오라클을 부활시켰다.** 원문의 "구분" 대상은
   로그·메트릭인데 "반드시 구분"만 남겨 §9-3과 정면 충돌시켰다.
3. **B1** — **마스터의 해소책 3개 중 1개만 가져왔다.** 두 검토자가 독립적으로 같은 지적. 20키 상한만으론
   비율만 줄고 무한성은 그대로.

**메타 교훈**. 1회차의 §9-5는 *"'13'을 물려받지 말고 직접 재검증하라"*고 **스스로 경고했으면서** 정작
G2 표 자체가 불완전했다(라인 16 + 미기재 2). [[spec-stated-count-becomes-blindfold]]의 **세 번째 재현**.
