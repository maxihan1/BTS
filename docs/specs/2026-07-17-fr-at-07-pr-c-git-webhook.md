# FR-AT-07 PR-C — Git Webhook 인바운드 + PR_MERGED 트리거 (백엔드)

> slug: fr-at-07-pr-c-git-webhook
> 마스터 스펙 `docs/specs/2026-07-15-fr-at-07-pr-merge.md` **§C의 상세화**. §C가 확정한 FR-C1~C12와
> BLOCKER 9건 해소책은 **재논의 대상이 아니다** — 본 문서는 그 위에 실측(plan §도메인 정리 G1~G14)과
> Maxi 확정 4건을 얹는다.
> **선행**. PR-A(#274/#275 permitAll 인프라·암호화 키·prod 조립 HTTP 테스트) · PR-B(#276 SET_FIX_VERSIONS).

## 0. 범위 (Maxi 확정 — DEC-18)

**PR-C = 백엔드만.** D6(웹훅 URL 발급 화면)·D7(E2E)은 **PR-D**로 분리.

- **근거**. automation BC 선례 **6/6 전부 백엔드→UI 분할** (#251→#254, #256→#260, #262→#265,
  #268→#269, #270→#271, #272→#273). 예외 없음. 본 PR 백엔드만으로도 20+ 태스크
- **프론트 Zod 계약 동기화(FR-C11)는 본 PR 포함** — PR-B 동형(백엔드 PR이 계약을 동기화, UI는 후속)
- **★ FR-AT-07 완료 마킹·automation BC 7/7은 PR-D 몫**. 본 PR은 D1/D2/D4/D5만 `[x]`, FR-AT-07 자체는
  **미완료 유지**. FR-AT-06 선례 동형(`product/automation.md:114` — D1~D5 완료가 BC 카운트를 올리지 않음)
- **FR 총수 123 불변** (D-step). → **카운트 파일 미변경** = 동시 PR #277(FR 5개 신설, 123→128)과 충돌 0

| D단계 | 내용 | 본 PR |
|---|---|---|
| D1 | 도메인 — PR_MERGED 트리거 | ✅ |
| D2 | 명세 — GitHub/GitLab Webhook 처리 + 이슈 키 추출 | ✅ |
| D3 | 데이터 모델 — webhook secret 저장 | ✅ (**"활용" 전제 폐기** — F6, 신규 테이블) |
| D4 | 백엔드 — `POST /api/v1/webhooks/git/{token}` + 서명 검증 | ✅ |
| D5 | 백엔드 테스트 — 가짜 페이로드 | ✅ |
| D6 | 프론트 UI — Webhook URL 생성 페이지 | ❌ PR-D |
| D7 | E2E | ❌ PR-D |

## 1. 신뢰 경계 (§C-1 승계 — 아래 모든 상한의 전제)

> **PR 제목·본문은 신뢰할 수 없는 외부 입력이다.** HMAC 서명이 증명하는 것은 **"GitHub이 보냈다"**
> 이지 **"내용이 믿을 만하다"** 가 아니다. PR은 BTS 계정이 없는 외부 기여자도 열 수 있고 제목·본문을
> 자유롭게 쓴다. **추출 결과는 전부 상한·검증 대상.**

여기에 실측 G10이 층을 하나 더 얹는다 — `ActionExecutor.kt:270-275`가 `triggerEvent.issueKey`를
**무검증 신뢰**하고 그 값이 `rule.actorUserId` 권한으로 실행된다. **폭발 반경 = 룰 actor 권한.**
따라서 웹훅 수신부의 프로젝트 스코프 필터(FR-C7)와 워커의 방어심층(FR-C13)이 **둘 다** 필요하다.

## 2. 사용자 시나리오 (Given-When-Then)

### S1. 정상 — PR 머지가 Fix Version을 자동 설정 (본 FR의 존재 이유)

```
Given 프로젝트 PROJ에 GITHUB git webhook이 등록돼 있고 (secret 설정 완료)
  And PR_MERGED 트리거 + SET_FIX_VERSIONS(versionIds=[v1.2.0]) 액션 룰이 활성이고
  And 룰의 targetBranch = "release/1.2"
 When GitHub이 base=release/1.2 로 머지된 PR(제목 "Fix login", 본문 "Closes PROJ-42")의
      pull_request(action=closed, merged=true) 이벤트를 유효 서명과 함께 전송하면
 Then 202를 즉시 반환하고 (동기 경로는 조회+enqueue만)
  And PROJ-42 의 fixVersions 가 [v1.2.0] 으로 전체교체된다 (룰 actor 권한으로)
```

### S2. 서명 위조 → 401 (검증 주체가 컨트롤러임의 실증)

```
Given 등록된 GITHUB webhook 토큰
 When 본문은 유효하나 X-Hub-Signature-256 이 틀린 요청이 오면
 Then 401 + ProblemDetail(errorCode=GIT_WEBHOOK_INVALID_SIGNATURE)
  And 어떤 룰도 enqueue 되지 않는다
```

### S3. 프로젝트 스코프 위반 → 무시 (혼동 공격 차단)

```
Given PROJ 에 등록된 webhook
 When 유효 서명으로 본문 "Closes OTHER-1" 이 오면
 Then 202 (수신은 정상) + 추출 결과 0건 → enqueue 0건
  And WARN 로그 (webhookId·projectKey 만, 본문 미포함)
```

### S4. 머지 아닌 PR 이벤트 → 무시

```
Given 등록된 webhook
 When action=opened / action=closed+merged=false / 다른 이벤트(push, issues) 가 오면
 Then 202 + enqueue 0건 (조용히 무시 — GitHub은 구독 이벤트를 UI에서 고르므로 정상 트래픽)
```

### S5. targetBranch 불일치 → 무발화

```
Given targetBranch="release/1.2" 룰과 targetBranch="release/2.0" 룰이 둘 다 활성
 When base=release/2.0 PR 머지 이벤트가 오면
 Then release/2.0 룰만 발화 (release/1.2 룰은 skip)
```

### S6. 정직한 재시도 → at-most-once (replay 방어 아님)

```
Given delivery=D1 이벤트가 이미 처리됨
 When GitHub이 타임아웃으로 delivery=D1 을 재전송하면
 Then 202 + enqueue 0건 (dedup)
```
> **★ 이것은 replay 방어가 아니다** (§C-6). GitHub HMAC은 본문만 서명하고 `X-GitHub-Delivery`는
> **서명 대상 밖**이라, 유효 (body, signature) 1쌍을 캡처한 공격자는 delivery UUID만 갈아끼워 무한
> 재전송할 수 있고 서명은 통과하며 dedup 키는 매번 신규다. **GITLAB은 평문 토큰이라 본문 무결성이 0**
> 이므로 rawBody SHA fallback도 무의미. → **잔여 위험으로 ADR 명시** (FR-C14).

### S7. 등록 — 토큰 1회 노출

```
Given MANAGE_AUTOMATION 권한 사용자
 When POST /api/v1/projects/PROJ/automation/git-webhooks {provider:GITHUB, secret:"..."} 하면
 Then 201 + { id, webhookUrl, token }  ← token 은 이 응답에서만. 이후 조회 불가
  And DB에는 token 의 SHA-256 해시 + secret 의 AES-256-GCM 암호문만 저장
```

## 3. 기능 요구사항

| ID | 요구사항 | 근거/함정 |
|---|---|---|
| **FR-C1** | `POST /api/v1/webhooks/git/{token}` — permitAll + CSRF-ignore + **bearer skip** | ★ G5 — **3곳** |
| **FR-C2** | 토큰 SHA-256 조회 → 등록행(provider·projectKey·secret_encrypted). 미존재/삭제 균일 **401** | §C-7 C-b |
| **FR-C3** | 서명 검증은 **등록행 `provider`로만 분기**. 헤더 추론·폴백 금지 | §C-7 C-a |
| **FR-C4** | 머지 이벤트만 처리 — GITHUB `pull_request`+`action=closed`+`merged=true` / GITLAB `Merge Request Hook`+`action=merge` | |
| **FR-C5** | **targetBranch 필터** — `trigger_config`, 정확 일치, 미지정=전 브랜치 | §C-5·DEC-12·G11 |
| **FR-C6** | 이슈 키 추출 — **PR 제목+본문**, `Closes\|Fixes\|Resolves` 계열 키워드 **필수**, 이슈키 대문자 고정 | |
| **FR-C7** | 프로젝트 스코프 필터 — prefix ≠ 등록 `project_key` 인 키 무시 | |
| **FR-C8** | **팬아웃 상한** — distinct 이슈키 20, 초과 시 **202+WARN, 처리 0건**(fail-closed) | §C-4 |
| **FR-C9** | 배달 dedup — **at-most-once, replay 방어 아님** | §C-6 |
| **FR-C10** | 등록 API `POST/GET/DELETE /api/v1/projects/{projectKey}/automation/git-webhooks`, 권한 `MANAGE_AUTOMATION` | |
| **FR-C11** | `TriggerType.PR_MERGED` + **미강제 13지점 전수** + 프론트 `triggerTypeSchema` 동기화 | ★ G2 |
| **FR-C12** | `automationSecretEncryptor` 빈 + `.env.prod.example` (**선택적 연동 배치**) | G8·G9·DEC-19 |
| **FR-C13** | **워커 방어심층** — 룰 projectKey ≠ 이슈키 prefix → SKIPPED. **조건 유무와 무관** | ★ G10 |
| **FR-C14** | ADR — §1.4 정식 예외 + GitHub replay 잔여위험 + **GitLab 보안등급 차이**(DEC-13) | |
| **FR-C15** | automation 웹훅(FR-AT-01) permitAll **중앙등록** — PR-A가 DEC-15로 본 PR에 미룬 것 | F2·G14 |

### FR-C6 상세 — 이슈 키 추출

- **대상**. PR 제목 + PR 본문 (커밋 메시지 **미포함** — 머지 커밋 목록은 별도 API 호출이 필요해 동기 경로
  NFR-1을 깨고, PR 제목/본문으로 충분)
- **키워드 필수**. `(Closes|Close|Closed|Fixes|Fix|Fixed|Resolves|Resolve|Resolved)\s+([A-Z][A-Z0-9]{1,9}-[1-9][0-9]*)`
  — 대소문자 무시(키워드만), **이슈키는 대문자 고정**(`PROJ-1` ≠ `proj-1`)
- **★ 정규식 값 복제**. BC 격리로 `IssueKey.REGEX`(`issue/domain/IssueKey.kt:31`) import 불가 →
  `AtlasIssueUrlParser.kt:56` 선례대로 **값 복제 + 주석에 복제 사실 명시**
- **★ 앵커**. `find()`는 미앵커라 텍스트 유실 위험([[flexmark-inline-extension-anchor-text-loss]]) —
  본 용도는 스캔이므로 `find()` 반복이 정상이나, 이슈키 부분에 **단어 경계** 필요
  (`PROJ-42x` / `PROJ-420` 이 `PROJ-42` 로 잘리면 안 됨)
- **distinct** — 같은 키 중복 언급은 1건

### FR-C9 상세 — dedup 설계

- **키**. GITHUB `X-GitHub-Delivery` / GITLAB `X-Gitlab-Event-UUID`
- **저장**. `git_webhook_deliveries(webhook_id, delivery_id)` **UNIQUE** → INSERT 성공=최초, 충돌=중복
- **헤더 부재 시**. dedup **불가** → **처리 진행**(fail-open). 근거 — dedup은 "정직한 재시도" 완화책일
  뿐 보안 통제가 아니므로(§C-6), 헤더 없다고 거부하면 기능이 죽는다. WARN 로그
- **★ 무한 증식 문제** (신규 발견 — §C에 없음). 이 테이블은 요청마다 1행 누적. 정리 없으면 무한 증식.
  → **보존 기한 7일 + 삭제 배치**. automation 모듈에 이미 `@Scheduled` 워커가 있으므로 동형 결선
  ([[module-first-scheduled-worker-detektmain-traps]] — 이미 결선돼 있어 `@EnableScheduling` 신규 불요, **확인 필요**)
- **순서**. dedup INSERT는 **enqueue 전**. 단 §C-7 C-f의 트레이드오프 — dedup 커밋 후 팬아웃 N건 중
  일부 enqueue 실패 시 **영구 유실**(재전송도 dedup에 막힘). EC10 명시

## 4. 비기능 요구사항

| ID | 요구사항 | 검증 (★ B9 — 1회차는 검증 항목 0건이었다) |
|---|---|---|
| NFR-1 | 동기 경로 < 200ms (p95) | **DB 왕복 상한 명시** — 토큰조회 1 + dedup INSERT 1 + 룰조회 1 + enqueue N. 완료 기준에 측정 항목 |
| NFR-2 | 미인증 힙 적재 방어 | **`@RequestBody` 미사용을 구조적으로 단언** (§C-3) |
| NFR-3 | secret·토큰 평문 미저장·미로그 | 로그에 webhookId·projectKey만 |
| NFR-4 | 서명 검증 실패율 100% 차단 | 양성+음성 단언 |

## 5. API 인터페이스

### 5-1. 인바운드 (permitAll)

```
POST /api/v1/webhooks/git/{token}
  consumes: application/json   ← ★ form-urlencoded 는 415 명시 거부 (§C-3)
  headers:  GITHUB → X-Hub-Signature-256: sha256=<hex>, X-GitHub-Event, X-GitHub-Delivery
            GITLAB → X-Gitlab-Token: <plain>, X-Gitlab-Event, X-Gitlab-Event-UUID
  → 202 (정상·무시 모두)  |  401 (토큰 미존재/서명 실패/복호화 실패)  |  413 (>256KB)  |  415  |  400 (JSON 파싱 실패)
```

### 5-2. 등록 API (인증 + `MANAGE_AUTOMATION`)

```
POST   /api/v1/projects/{projectKey}/automation/git-webhooks
       body { provider: GITHUB|GITLAB, secret: string }
       → 201 { id, provider, webhookUrl, token }        ← token 1회 노출
GET    /api/v1/projects/{projectKey}/automation/git-webhooks
       → 200 [{ id, provider, createdAt, createdBy }]   ← token·secret 절대 미포함
DELETE /api/v1/projects/{projectKey}/automation/git-webhooks/{id}
       → 204
```

## 6. 데이터 모델 (Maxi 확정 — DEC-20. **V307~V309 3분할**)

> **정정**. Maxi 확정 시점엔 "V307/V308 2분할"이었으나, dedup 테이블이 별도 필요함이 확정되어 **3개**가
> 된다. 결정의 정신("1파일=1스키마 변경 관례 준수, 롤백 단위 분리")을 그대로 적용한 결과이므로 재확인
> 불요 — 모듈 V300~V306이 예외 없이 1파일 1테이블이고 대역 여유 92개.

| 파일 | 내용 |
|---|---|
| **V307** | `git_webhooks` 신규 |
| **V308** | `git_webhook_deliveries` 신규 |
| **V309** | `ck_automation_rules_trigger_type` CHECK **5→6** (DROP→ADD, `V300:36-38` 원본 편집 금지) |

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
    deleted_at TIMESTAMPTZ,                  -- 소프트 삭제
    CONSTRAINT ck_git_webhooks_provider CHECK (provider IN ('GITHUB','GITLAB'))
);
CREATE UNIQUE INDEX uq_git_webhooks_token_hash ON git_webhooks(token_hash) WHERE deleted_at IS NULL;
```
- **★ `token_hash` 부분 UNIQUE** — `V300:53-55`(`uq_automation_rules_webhook_token_hash`) 선례 동형
- **★ PG NULL 함정** ([[pg-null-distinct-on-conflict-idempotency]]) — 본 UNIQUE는 NOT NULL 컬럼이라 무관

```sql
-- V308
CREATE TABLE git_webhook_deliveries (
    webhook_id UUID NOT NULL REFERENCES git_webhooks(id) ON DELETE CASCADE,  -- ★ 조인테이블 FK CASCADE
    delivery_id VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (webhook_id, delivery_id)
);
CREATE INDEX ix_git_webhook_deliveries_received_at ON git_webhook_deliveries(received_at);  -- 정리 배치용
```
- **FK CASCADE 필수** ([[join-table-fk-cascade-testcontainers-cleanup]])

**init_codegen 면제** — automation은 JdbcTemplate(jOOQ 4모듈에 automation 없음, §C-7 확인 완료).
**`:modules:app:test`는 5433 영속 DB** ([[app-test-persistent-db-migration-checksum-trap]]) — 적용 후 편집 금지.

## 7. 엣지 케이스

| ID | 상황 | 기대 |
|---|---|---|
| EC1 | 토큰 미존재/소프트삭제 | **401** (404 아님 — 서명 실패와 구분하면 존재 오라클, §C-7 C-b) |
| EC2 | GITHUB 등록인데 `X-Gitlab-Token` 만 옴 | **401**. 폴백 금지 |
| EC3 | GITLAB 등록인데 `X-Hub-Signature-256` 만 옴 | **401**. 폴백 금지 |
| EC4 | GitHub 레거시 `X-Hub-Signature`(SHA-1) 만 옴 | **401** (SHA-1 미지원) |
| EC5 | 본문 > 256KB | **413**, 서명 검증 **이전**, `readNBytes` |
| EC6 | `Content-Type: application/x-www-form-urlencoded` | **415** (§C-3 — 미지정 시 202 조용한 무시로 디버깅 지옥) |
| EC7 | JSON 파싱 실패 | **400** |
| EC8 | **등록 시** 암호화 키 미설정 | **500** (운영자 즉시 인지) |
| EC9 | **검증 시** 복호화 실패 | **401 + ERROR 로그**(등록 id·projectKey만) + 메트릭. **★ 서명 불일치와 반드시 구분** — 아니면 키 유실이 조용한 401로 은폐돼 운영자가 "GitHub 설정이 틀렸나"를 몇 시간 뒤진다 |
| EC10 | dedup 커밋 후 팬아웃 일부 enqueue 실패 | **영구 유실**(재전송도 dedup에 막힘). 트레이드오프 명시 (§C-7 C-f) |
| EC11 | delivery 헤더 부재 | dedup 불가 → **처리 진행** + WARN (FR-C9) |
| EC12 | 추출 distinct 키 > 20 | **202 + WARN, 처리 0건** (일부 처리 시 어느 게 처리됐는지 비결정적) |
| EC13 | 이슈키 추출 0건 | 202 + enqueue 0건 |
| EC14 | 룰 0건 (트리거는 맞으나 활성 룰 없음) | 202 |
| EC15 | secret 미설정 상태로 등록된 webhook에 요청 | **401** (fail-closed — `SlackSignatureVerifier:64-66` 선례) |

## 8. 제약

1. **`@RequestBody` 금지** — `HttpServletRequest.inputStream.readNBytes(MAX+1)`. HMAC은 **raw 바이트에
   직접**(String 왕복은 비-UTF8 바이트를 U+FFFD로 치환해 서명 파괴, G7)
2. **`@RequestParam`/`@ModelAttribute` 병용 절대 금지** — form 파싱이 스트림 소비
3. **★ bearer skip 필수** — G5. GitHub이 form-urlencoded를 보낼 수 있고, 누락 증상은 permitAll 미등록과
   **구분 불가능한 401**
4. **CSRF는 `antMatcher(method, path)`** — 문자열 오버로드는 메서드 고정이 조용히 사라지며 컴파일·테스트 통과
5. **`/api/**` authenticated(`SecurityConfig.kt:209`)보다 위**에 등록
6. **`/*` 단일 세그먼트 매처** — `/**` 금지. `AutomationTestSecurityConfig.kt:49`의 `/**`도 `/*`로 정합화 (G14)
7. **prod 조립 테스트는 `ProdAssemblyHttpTestBase` 상속만** — `@SpringBootTest` 재선언 시 컨텍스트 분열
   (9-BC 2회 부팅 + `@Scheduled` 워커 2벌 pgmq 동시 폴링). 신규 프로퍼티는 **베이스 `props()`에 추가** (G6)
8. **MockMvc 금지** — 실 필터체인 우회 = 가짜 그린 ([[multipart-default-limit-app-policy-false-green]])
9. **상수시간 비교** `MessageDigest.isEqual` — `==`/`equals` 금지 (타이밍 공격)
10. **fail-closed** — secret 미설정·헤더 누락·형식 오류 전부 거부. boolean 수렴 후 컨트롤러가 401 매핑
11. **cross-BC 조립 재검증** — 머지 전 `origin/main` rebase + `:modules:app:test`
    ([[prod-assembly-boot-verification-required]])
12. **`@param:Qualifier("automationSecretEncryptor")`** — SecretEncryptor 타입 빈 4개가 됨 (G8)

## 9. 측정 가능한 완료 기준

> ★ **1회차의 "413이 서명검증 이전(실서블릿)"은 `@RequestBody String`으로도 통과하는 가짜 그린이었다**
> (§C-3). 아래는 그 교훈을 반영해 **구조를 단언**한다.

1. **`GitWebhookController`가 `@RequestBody`를 쓰지 않음을 구조적으로 단언** (리플렉션 또는 소스 grep 테스트)
2. **양성 단언** — `ProdAssemblyHttpTestBase` 상속 테스트가 **유효 HMAC을 직접 재계산**해 202를 받음
   (= permitAll + CSRF-ignore + bearer skip 3곳 + 서명 검증 통과의 **동시 증명**, `SlackInboundPermitAllTest:186-215` 동형)
3. **음성 단언 — 판별자는 응답 본문** ([[negative-guard-needs-body-discriminator]]).
   서명만 틀린 동일 요청 → 401 **+ `ProblemDetail.errorCode == GIT_WEBHOOK_INVALID_SIGNATURE`**
   (필터의 401은 빈 본문 → 컨트롤러가 준 401임이 증명됨)
4. **★ 위반 주입으로 가드 실증** ([[archunit-vacuous-rule-silent-pass]]) — permitAll 목록에서 git 경로를
   일부러 빼고 테스트가 **fail** 하는지 확인 후 되돌린다. 통과가 검증을 의미하지 않는다
5. **★ 미강제 13지점 전수 grep 재검증** ([[spec-stated-count-becomes-blindfold]]) — "13"을 물려받지 말고
   `grep -rn "TriggerType\." backend/modules/automation/src/main` 으로 직접 재확인
6. **`TriggerMatcher` wire→enum 맵 회귀 가드** — `PR_MERGED` 미등록 시 fail 하는 테스트 (1순위 조용한 실패)
7. `TriggerConfigTest.kt:17` 5→6, `:15` describe, `:21-29` 집합 단언
8. `SchemaMigrationTest.kt:561` **프로브 값 교체** + `:553-557` 유효 6종 + `:551`·`:554` 문구
9. **FR-C13 방어심층** — 조건 **없는** 룰에도 projectKey 게이트가 걸림을 단언 (G10 — 기존
   `isConditionUnmet`은 조건 있는 룰만 방어)
10. **NFR-1 측정** — 동기 경로 DB 왕복 수 단언 + p95 실측 기록
11. `:modules:automation:test` + `:modules:app:test` + `:modules:identity-access:test` +
    `:modules:slack-integration:test` 회귀 0
12. `pnpm typecheck`(tsconfig.app.json) + `pnpm test` 회귀 0 — Zod enum 추가 파급
    ([[zod-schema-strengthen-inline-mock-fanout]] — 인라인 mock grep)
13. `ktlintCheck` + `detekt` 0 (SecurityConfig `@Suppress("LongMethod")` 임계 이미 1줄 초과 — G5)
14. `bash scripts/verify-master-plan.sh` 통과

## 10. 후속 (본 PR 범위 밖 — 명시)

- **D6/D7 UI + E2E** → **PR-D** (FR-AT-07 완료·BC 7/7 마킹은 거기서)
- **GitHub replay 방어** — 구조적 불가(서명에 timestamp 없음). 완화는 멱등 처리·팬아웃 상한·rate limit
- **rate limit** — 인바운드 3종(slack·automation·git) 공통 부재. PR-A ADR이 이미 잔여 위험으로 기록
- **`PathContributor` 확장점** — permitAll 하드코딩이 이 부채의 구조적 원인(F2). SecurityConfig 300줄 한계
- **`hasObservableSideEffect` exhaustive when 전환** (`pr-b-fix-version.md:766` 미해결)
- **`buildContext` 오염** (§C-7 C-k) — PR_MERGED + ADD_COMMENT 조합 시 `{{issue.title}}`이 **PR 제목**으로
  렌더(`ActionExecutor:278-291`이 중첩 `issue` 없으면 triggerEvent 전체를 issue로 취급). **한계로 명시**
- **`DATA.md:85-94` 나머지 6행 stale** (Maxi 확정 — automation 행만 갱신)
- **`fr-index.md:5` stale "122"** — #277이 자기 PR에 통합 선언 → 손대지 않음
- **`sha256Hex` private top-level 중복** (G14) — git/automation 두 컨트롤러가 각자 보유하게 됨

## 11. Maxi 확정 결정

| ID | 결정 |
|---|---|
| DEC-18 | **PR-C = 백엔드만.** D6/D7은 PR-D. FR-AT-07 완료·BC 7/7은 PR-D 몫 (선례 6/6) |
| DEC-19 | git webhook secret = **선택적 연동**. `.env.prod.example`에 **주석 처리 신규 §Git 웹훅 섹션** (slack 키 선례 동형, `:66-78`) |
| DEC-20 | 마이그레이션 **분리**. V307(git_webhooks) / V308(deliveries) / V309(CHECK 5→6) |
| DEC-21 | `DATA.md:90` **automation 행만** 갱신 (`(예정) —` → `V300~V309`). 나머지 6행 stale은 후속 |

## Brainstorming Check

(← Phase B 적대적 검토 후 채움)
