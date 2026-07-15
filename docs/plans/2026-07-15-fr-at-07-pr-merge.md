# FR-AT-07 — PR 머지 연동 (Fix Version 자동 설정) · 백엔드 D1~D5

> slug: fr-at-07-pr-merge
> type: backend
> agent: backend-engineer
> primary_bc: automation
> 생성: 2026-07-15

## Brief

**사용자 원문**. `fr-at-07 진행해줘`

**작업 범위**. FR-AT-07(PR 머지 연동 — Fix Version 자동 설정)의 **D1~D5 백엔드**. UI(D6 Webhook URL 생성 페이지) + D7(E2E)은 **후속 PR로 분리**(Maxi 확정) — FR-AT-04·05·06 전부 이 방식으로 진행된 선례.

| 단계 | 내용 | 책임 |
|---|---|---|
| D1 | 도메인 — GitWebhookEvent | backend-engineer |
| D2 | 명세 — GitHub/GitLab Webhook 처리. 커밋 메시지에서 이슈 키 추출 | backend-engineer |
| D3 | 데이터 모델 — (활용. webhook secret 저장) | db-engineer |
| D4 | 백엔드 — `POST /api/v1/webhooks/git` + 서명 검증 | backend-engineer + security-engineer |
| D5 | 백엔드 테스트 — 가짜 페이로드 | backend-engineer |

**classify 결과**. type=backend / agent=backend-engineer / ~~primary_bc=issue-tracking~~ → **automation 정정**
 (classify가 "이슈키" 키워드로 issue-tracking 오판. product doc §2.7 소속은 automation.)

**우선순위**. 높음 | **선행**. §2.1 (WEBHOOK 트리거), §2.2 (액션) | **product doc Plan slug**. `automation/pr-merge`

**의의**. Phase 1의 **마지막 FR**. 완료 시 automation BC 6/7 → **7/7** (BC 완결), 전체 진척 122/123 → **123/123**.

## 열린 질문 (→ /bts-domain·/bts-spec에서 해소)

1. **cross-BC 경계**. Fix Version(`FR-VR`)은 issue-tracking 소유. automation이 이슈의 Fix Version을 설정 = BC 경계 통과. 기존 automation 액션이 이슈를 어떻게 건드리는지(포트 위임 / pgmq 이벤트) 관례 확인 필요.
2. **FR-AT-01 WEBHOOK 트리거와의 관계**. automation에 이미 WEBHOOK 트리거 + 토큰 체계 존재. Git webhook은 별도 엔드포인트인가, 기존 트리거의 특수 케이스인가.
3. **서명 검증 방식**. GitHub(`X-Hub-Signature-256`, HMAC-SHA256) vs GitLab(`X-Gitlab-Token`, 평문 비교) — 두 provider 모두 지원 범위인가.
4. **webhook secret 저장 위치**. D3이 "활용"이라 명시 — 신규 테이블 없이 기존 스키마 재사용 가능한지 확인.
5. **이슈 키 추출 규칙**. 커밋 메시지 / PR 제목 / PR 본문 중 어디까지, 다중 키 매칭 시 동작.
6. **엔드포인트 인증**. `/api/v1/webhooks/git`은 외부 Git 서버가 호출 → permitAll + 서명 검증. 중앙 등록 관례 확인(기존 부채 항목).

## 도메인 정리

> 조사 방식. Explore 에이전트 3종 병렬 (automation 모듈 / Fix Version·cross-BC 포트 / 서명검증·시크릿·permitAll). 아래는 **코드에서 확인된 사실**만. 결정은 §결정 사항에.

- **BC**. automation (`com.bts.automation`, 9번째 모듈, JdbcTemplate, Flyway V300~V399 — 현재 V305까지 사용)
- **classify 정정**. `primary_bc=issue-tracking` → **automation** ("이슈키" 키워드 오판)

### F1. 기존 WEBHOOK 트리거(FR-AT-01)의 실체

| 항목 | 사실 | 근거 |
|---|---|---|
| 엔드포인트 | `POST /api/v1/automation/webhooks/{token}` → 202 | `automation/adapter/web/AutomationWebhookController.kt:68,86` |
| 토큰 | `SecureRandom` 256bit → base64url 원문, **1회만 노출** | `AutomationRuleService.kt:773-777`, `:210` |
| 저장 | `automation_rules.webhook_token_hash VARCHAR(64)` — **SHA-256 해시(평문 미저장)** | `V300__automation_rules.sql:28,53-55` |
| 검증 | 수신 토큰을 해싱해 조회. 미존재/비활성/삭제 균일 404(존재 숨김) | `AutomationWebhookController.kt:95`, `AutomationRuleRepository.kt:248` |
| payload | `readTree` → `JsonNode` → pgmq `q_automation_execution` → `ActionExecutor` | `:119-124`, `AutomationExecutionEnqueuer.kt:55-62` |
| 상한 | 256KB 이중검사(Content-Length + `readNBytes`) | `:103-116`, `:206` |

**★ payload는 조건 평가에 직접 쓰이지 않는다.** payload에서 `issueKey`만 추출(`extractIssueKey` `:264-268` — 최상위 `issueKey` 또는 `issue.key`)해 `IssueSnapshotPort.fetch`로 **최신 스냅샷**을 받아 조건을 평가한다. payload는 템플릿 컨텍스트 전용(`buildContext` `:271-284`). issueKey 없으면 fail-safe `SKIPPED`.

### F2. ★ 인바운드 웹훅이 prod에서 전부 죽어 있다 (기존 부채, 문서화된 scope-out)

`SecurityConfig`(identity-access)의 permitAll 목록에 **automation·slack 인바운드 경로가 없다**. `anyRequest().authenticated()`(`SecurityConfig.kt:186`)에 걸려 **컨트롤러 도달 전 401**, POST는 CSRF 필터가 먼저 403.

- automation 웹훅 permitAll은 **테스트 전용** `AutomationTestSecurityConfig.kt:49`에만 존재
- slack 인바운드도 동일 — `SlackTestSecurityConfig.kt:64-67` (test 전용)
- **알려진 추적 항목**. `docs/plans/2026-07-11-automation-prod-assembly.md:53` — *"`AutomationWebhookController`가 조립되나 중앙 SecurityConfig 화이트리스트 미포함 → prod 401(FR-AT-01 WEBHOOK 트리거 **사문화**). slack `/slack/events`도 동일 미등록·후속 추적 중."* / `:143` D3=scope-out / `:165` *"인바운드 permitAll 중앙등록은 BTS의 알려진 BC별 배포-시점 후속 패턴"*
- **확장 포인트 없음**. 경로는 `SecurityConfig` companion object에 리터럴 하드코딩(`:209-260`). `PathContributor` 류 인터페이스·컬렉션 주입 grep 0건 → 이 구조가 부채의 원인
- 대조군. notification 익명 공유 GET은 **정상 등록**(`SecurityConfig.kt:180`, GET 고정 + 단일 세그먼트 매처 2겹 방어)

→ **FR-AT-07은 세 번째 인바운드 웹훅이다.** 중앙 등록 없이 머지하면 "Phase 1 마지막 FR"이 prod에서 동작하지 않는다. §결정 D2.

### F3. ★ Fix Version은 기존 포트로 설정할 수 없다

- **모델**. 컬럼이 아니라 **N:M 조인 테이블** `issue_fix_versions`(`V017__issue_version_links.sql`). 이슈는 fixVersion을 **복수**로 가짐(`Issue.fixVersionIds: List<UUID>` `Issue.kt:115`). 소프트삭제 없음(해제=행 DELETE)
- **설정 경로**. `IssueApplicationService.changeFixVersions(actor, key, AppChangeVersionsRequest{versionIds, expectedVersion})` (`:944-969`) — **전체 교체(replace) 시맨틱**(부분 add/remove 없음) + **OCC 필수**(`expectedVersion`, 불일치 시 409)
- **막는 지점**. `IssueMutationPort.setField`의 화이트리스트 6종에 fixVersions **없음** — `AutomationIssueMutationAdapter.kt:255-262` `SUPPORTED_FIELDS = {summary, description, priority, labels, environment, impact}`. `validateSupportedField`(`:202-204`)가 트랜잭션 전 거부
- **상수 추가만으론 불가(구조적)**. `setField`는 `updateIssue(UpdateIssueRequest)`로 위임하는데 fixVersions는 **다른 서비스 메서드**(`changeFixVersions`) 경로 → `buildUpdateRequest`의 `when`(`:218-240`)이 다른 메서드로 분기해야 함
- **조건으로도 참조 불가**. `Condition.FIELD_WHITELIST`(`Condition.kt:84-89`) 9종·`IssueSnapshot`(`:47-57`) 9필드 모두 fixVersions 미포함

### F4. cross-BC 포트 관례 (확립됨, ArchUnit 강제)

```
automation ──(port)──▶ shared-kernel ◀──(impl)── issue-tracking
```

| 항목 | 관례 |
|---|---|
| 인터페이스 위치 | **항상 shared-kernel** (`com.bts.shared.<도메인>`) |
| prod 어댑터 | **제공자 BC의 `adapter/outbound/<소비자명>/`** + `@Component @Profile("prod")` |
| 네이밍 | 인터페이스 `XxxPort` / 구현 `XxxAdapter` |
| 테스트 fake | **consumer-owns-stub** — 소비 BC의 `src/test`에 `StubXxxPort` |
| 쓰기 포트 | **fail-closed** — default 구현 없음 → 어댑터 부재 시 부팅 실패 |
| 계약 테스트 | `IssueMutationPortContractTest.kt:24` — 리플렉션으로 "default 없음" 검증 |
| shared-kernel 순수성 | Jackson 타입도 노출 안 함 → `SetFieldCommand.value: String?`(JSON 인코딩 문자열) |

automation→issue-tracking은 **이미 결선**. `ActionExecutor`가 `IssueMutationPort`(쓰기 3종) + `IssueSnapshotPort`(읽기) 주입(`:93,97`), 어댑터 2개 모두 `@Profile("prod")`.

`AutomationIssueMutationAdapter` 설계 포인트 — repository 직행 금지(유스케이스 위임으로 권한/OCC/이벤트 보존) · SecurityContext 안 읽고 `cmd.actorUserId` 신뢰(pgmq 워커 async) · `@Transactional` 대신 `TransactionTemplate`(OCC 재시도의 rollback-only 오염 회피) · dryRun=실행 후 `setRollbackOnly()` · OCC 충돌 시 1회 재시도.

### F5. 서명 검증 — 모범 사례는 있으나 slack 전용

`SlackSignatureVerifier.kt` — HmacSHA256, base string `v0:{ts}:{rawBody}`, **replay 방어 ±5분**(`:86` + `REPLAY_WINDOW_SECONDS=300L :113`), **상수시간 비교** `MessageDigest.isEqual`(`:78-81`), `Clock` 주입, fail-closed(secret 미설정도 `false`→401).

- **재사용 불가 형태**. `SlackProperties`(slack 전용 signing secret)에 직접 결합. shared-kernel에 인바운드 검증 유틸 **없음**
- **★ raw body 함정**. `@RequestBody String`만 사용. `@RequestParam`/`@ModelAttribute` 병용 시 Spring이 form을 먼저 파싱해 스트림 소비 → `@RequestBody`가 **빈 문자열** → 서명검증 조용히 무력화. 서명 통과 후 수동 form-decode (`SlackCommandsController.kt:24-33,102-115`)
- **DoS 가드**. 서명검증 **이전에** 크기 상한 → 413 (commands 16KB / interactions 64KB)
- 아웃바운드 `WebhookSigner.kt`(search-export-import)는 이미 `sha256=` 접두(GitHub 관례)를 쓰지만 **`verify()` 없음**(sign 전용)

### F6. ★ 시크릿 저장 — D3의 "활용" 전제가 깨진다

HMAC 서명 검증은 **평문 secret**이 있어야 서명을 재계산할 수 있다. 그런데 기존 `automation_rules.webhook_token_hash`는 **SHA-256 해시(비가역)** — HMAC에 쓸 수 없다. 따라서 **기존 컬럼 재사용 불가**, 가역 암호화 저장이 필요.

BTS 관례 — `SecretEncryptor`(shared-kernel, `shared/crypto/SecretEncryptor.kt`, **AES-256-GCM** `Encryptors.stronger`, 매 호출 random IV, hex 출력). BC마다 **다른 키/salt로 도메인 격리** + 빈 이름 명시 + `@Qualifier` by-name 주입(같은 타입 빈 4개 조립 → 이름 충돌 방지).

| 빈 이름 | 프로퍼티 | 사용처 |
|---|---|---|
| `slackSecretEncryptor` | `bts.slack-encryption.{key,salt}` | 봇 토큰 |
| `oidcSecretEncryptor` | (동형) | OIDC client secret |
| `webhookSecretEncryptor` | (동형) | 아웃바운드 웹훅 |
| `mfaSecretEncryptor` | — | TOTP secret |

- 키 미설정 시 **빈은 항상 등록**(`@ConditionalOnProperty` 금지 — 부팅 파괴), `encrypt/decrypt` **호출 시점**에 `check(configured)` → `IllegalStateException` (교훈 [[use-time-validated-env-passes-boot-fails-on-use]])
- 예외 메시지에 평문/키 미포함이 계약. salt는 **hex**

### F7. 이슈 키 추출

- **정본 정규식**. `IssueKey.REGEX = "^[A-Z][A-Z0-9]{1,9}-[1-9][0-9]*$"` (`issue/domain/IssueKey.kt:31`) — prefix 2~10자, number 1 이상(0 불가)
- **본문 텍스트에서 이슈 키를 스캔하는 기존 코드는 없다.** 멘션 파서는 `@username` 전용, 링크(FR-LK)는 명시적 이슈 간 링크로 텍스트 파싱 안 함
- 유일한 선례는 **URL 파싱** — `slack/unfurl/AtlasIssueUrlParser.kt:57`. BC 격리로 `IssueKey.REGEX`를 **값 복제**하고 그 사실을 주석에 명시(`:56`). 끝 `$` 앵커 없음(trailing slash/query 처리 목적)
- → automation도 같은 값 복제 패턴을 따르게 됨(BC 격리상 issue-tracking import 불가)
- 관련 교훈 [[flexmark-inline-extension-anchor-text-loss]] — `find()`는 미앵커 시 텍스트 유실

### F8. SDD 8.8 ↔ product doc §2.7 명세 충돌

| 출처 | 명세 |
|---|---|
| `docs/sdd/08-automation-engine.md:146-162` | 트리거 `type: webhook.received, source: github, event: pull_request.merged` — **기존 WEBHOOK 트리거의 특수화**. 액션 `set-field / field: fix_version_ids / value: ["{{ pr.target_branch_version }}"]` |
| `docs/plan/product/automation.md:122-126` | D1 "도메인 — **GitWebhookEvent**"(신규 개념) · D4 "**`POST /api/v1/webhooks/git`**"(신규 전용 엔드포인트) |

**SDD 8.8은 그 밖에도 현행과 어긋난다** — 조건을 JSONLogic/SpEL로 기술(`8.3`)하나 실제는 손수 조건 트리 + `FIELD_WHITELIST`. `conditions: PR 본문에 "Closes PROJ-N" 패턴 매칭`은 **현행 조건 모델로 표현 불가**(FIELD_WHITELIST가 `issue.*` 9종 전용) → 이슈 키 추출은 **조건이 아니라 웹훅 수신부**의 책임이어야 함.
`pr.target_branch_version`(대상 브랜치 → 버전 매핑)은 **어디에도 정의된 바 없음**.

### F9. 기존 결정 충돌 / 신규 용어

- **관련 ADR**. `2026-07-10-fr-at-01-automation-triggers.md` · `2026-07-11-fr-at-02-automation-actions.md` · `2026-07-11-automation-prod-assembly.md`(F2 부채 출처) · `2026-06-10-version-status-and-transitions`(Version 상태) · `2026-05-22-issue-key-prefix-policy`
- **신규 용어 후보**. `GitWebhookEvent`(product doc D1 표기) — glossary 미등재. 채택 여부는 §결정 D1에 종속
- **Obsidian `domain/automation.md`가 stale**. AQL 파서를 "ANTLR 4"로 기재하나 실제는 손수 파서. 본 PR 범위 밖(별건 정리 후보)

## 결정 사항 (2026-07-15 Maxi 확정)

### D1. 수신 형태 — **신규 `PR_MERGED` 트리거 + 전용 엔드포인트**

전용 엔드포인트가 서명검증·이슈키 추출을 담당하고, `TriggerType.PR_MERGED`를 추가해 **기존 pgmq → `ActionExecutor` 파이프라인을 그대로 재사용**한다.

- **근거**. SDD 8.2의 트리거 목록에 이미 `pr.merged`가 있음(`08-automation-engine.md:22`) → SDD 8.8의 `webhook.received/source:github` 표기보다 8.2가 정합. 룰 모델을 타므로 조건부 제어·실행 이력(FR-AT-05)·YAML GitOps(FR-AT-06)·충돌 분석(FR-AT-04) 혜택을 전부 승계
- **기각**. 기존 `WEBHOOK` 재사용 → 룰 편집 UI에서 Git 전용 룰 구분 불가 / 룰 우회 직결 → SDD 8.8의 룰 모델 이탈 + 위 4개 FR 혜택 상실
- **★ 파급**. enum 추가 → DB CHECK 제약 갱신(V306) + **타 모듈 카운트 가드까지 깨짐**([[enum-add-breaks-crossmodule-count-guard]]) → 전 모듈 `grep`으로 TriggerType 카운트 단언 전수 확인 필수
- **SDD 8.8 정정 필요**. `webhook.received/source:github` → `pr.merged` 트리거로. §전수 동기화 대상

### D2. permitAll 부채 — **3종 일괄 중앙 등록**

git 신규 + automation 웹훅(FR-AT-01) + slack 인바운드를 **중앙 `SecurityConfig`에 함께 등록**한다.

- **근거**. `automation-prod-assembly.md:165`가 제안한 *"slack+automation 인바운드 permitAll 통합 후속"* 과 정합. 이 등록 없이는 FR-AT-07이 prod에서 동작하지 않아 **E2E로도 검증 불가**, "automation BC 완결" 선언이 무색
- **★ BC 경계 예외**. identity-access(`SecurityConfig`)를 건드림. plan §리스크에 사유 명시 필수(선례 — PR #13 옵션 C 패턴). **security-engineer 공동 검토 필수**
- **방어 설계**. notification 익명 공유 GET 선례(`SecurityConfig.kt:175-180`)의 2겹 방어 차용 — **메서드 고정(POST)** + **경로 매처 최소 범위**. CSRF ignore도 같은 최소 범위로 동반 등록(POST라 필수)
- **회귀 가드**. permitAll 확장 포인트가 없어(F2) 이 부채가 재발하는 구조 → 등록 누락을 잡는 테스트를 조립 컨텍스트(`:modules:app`)에 추가 검토

### D3. Fix Version 통로 — **신규 포트 메서드 `setFixVersions`**

`IssueMutationPort`에 전용 메서드를 추가한다(`setField` 확장 아님).

> **⚠️ 2026-07-15 정정 — 아래 원래 근거의 후반부는 사실이 아니었다** (Phase B 적대적 검토가 코드로 반증, spec §B-2).
> `SetFieldCommand`/`AssignCommand`(`IssueMutationCommands.kt:34-60`)는 **애초에 OCC 파라미터가 없다**. 어댑터가 매 시도마다 자기 트랜잭션 안에서 `findByKey().version`을 재조회해 채우고(`AutomationIssueMutationAdapter.kt:100-105,118-124`) `runWithOccRetry`(`:162-172`)가 1회 재시도한다 — **호출자는 OCC를 알 필요가 없는 설계**.
> **결론(전용 포트 메서드)은 유지**하되 근거는 아래 "정정된 근거"로 대체. `SetFixVersionsCommand`에 `expectedVersion` 필드를 두지 않는다(항상 null인 죽은 분기 + 미래 TOCTOU 위험).

- **정정된 근거**. (1) **전체교체 시맨틱** + **복수** versionId를 타입으로 정직하게 드러냄 — `value: String?`(JSON 인코딩 문자열)에 리스트를 숨기면 "필드 하나에 값 하나"라는 `setField` 의미가 깨짐 (2) fixVersions는 `updateIssue`가 아닌 **별도 서비스 메서드**(`changeFixVersions`) 경로라는 구조적 사실(§F3)
- ~~**원래 근거(폐기)**. "…OCC 파라미터가 사라짐"~~ — 코드로 반증됨
- **파급**. shared-kernel 계약 변경 → `IssueMutationPortContractTest`("default 없음" 리플렉션 검증) 갱신 + `StubIssueMutationPort`(consumer-owns-stub) 갱신 + issue-tracking 어댑터 구현
- **fail-closed 유지**. 쓰기 포트라 default 구현 없음 → 어댑터 미결선 시 부팅 실패([[new-crossbc-dep-openapi-mockbean-regression]] — 신규 포트 소비는 full-boot `NoSuchBean` 유발, `@MockBean` 동반 확인)

### D4. 버전 결정 — **룰 액션에 versionId 명시 지정**

액션 config에 대상 `versionId`를 담는다. 브랜치→버전 매핑 테이블(신규 스키마+UI)도, 최신 UNRELEASED 자동 선택(암묵적 오설정 위험)도 채택하지 않음.

- **근거**. 기존 액션 모델과 동형 · 신규 스키마 0 · 명시적(암묵 동작 없음). 릴리스마다 룰 수정이 필요하지만 **FR-AT-06 YAML GitOps로 일괄 관리 가능**
- **SDD 8.8 정정 필요**. `value: ["{{ pr.target_branch_version }}"]`(미정의 개념) → 명시 versionId로. §전수 동기화 대상

### D5. (파생) 본 PR이 건드리는 모듈 — BC 격리 예외 명시

| 모듈 | 변경 | 사유 |
|---|---|---|
| **automation** | 주 대상 — 엔드포인트·서명검증·이슈키 추출·`PR_MERGED` 트리거·V306 | 본 FR 소속 BC |
| **shared-kernel** | `IssueMutationPort.setFixVersions` 추가 | cross-BC 포트 관례(F4) — 인터페이스는 항상 shared-kernel |
| **issue-tracking** | `AutomationIssueMutationAdapter`에 구현 | 포트 제공자 |
| **identity-access** | `SecurityConfig` permitAll·CSRF 3종 등록 | **D2 결정 — BC 격리 예외**. security-engineer 공동 검토 |

**cross-BC 포트 추가 PR의 필수 절차** ([[prod-assembly-boot-verification-required]]). 머지 전 `origin/main` rebase + **`:modules:app:test`(9 BC prod 조립) 재검증**.

### D6. (파생) 미해소 — /bts-spec에서 확정

1. **서명 검증 provider 범위**. GitHub(`X-Hub-Signature-256`, HMAC-SHA256, `sha256=` 접두) / GitLab(`X-Gitlab-Token`, 평문 비교) 둘 다인가
2. **secret 저장**. F6대로 해시 불가 → `SecretEncryptor` 가역 암호화. 신규 빈 `automationSecretEncryptor`(`bts.automation-encryption.{key,salt}`) + 저장 위치(신규 컬럼 vs 신규 테이블). D3 "활용" 전제 폐기 → **product doc D3 문구 정정 필요**
3. **이슈 키 추출 범위**. 커밋 메시지 / PR 제목 / PR 본문 중 어디까지. 다중 키 매칭 시 전부 처리인가 첫 건인가. `Closes/Fixes` 같은 키워드 요구 여부
4. **정규식 값 복제**. BC 격리로 `IssueKey.REGEX` 직접 import 불가 → `AtlasIssueUrlParser.kt:56` 선례대로 값 복제 + 주석 명시. 앵커 처리 주의([[flexmark-inline-extension-anchor-text-loss]])
5. **raw body 함정**. `@RequestBody String`만, `@RequestParam` 병용 금지(F5)
6. **DoS 가드**. 서명검증 **이전** 크기 상한 → 413 (slack 선례)
7. **replay 방어**. GitHub은 slack과 달리 timestamp 헤더가 없음 → `X-GitHub-Delivery`(UUID) 기반 dedup 검토
8. **트리거 이벤트 payload 형태**. `ActionExecutor.extractIssueKey`(`:264-268`)가 최상위 `issueKey` 또는 `issue.key`를 읽음 → PR_MERGED triggerEvent를 이 규약에 맞춰야 재사용 가능. PR이 이슈 **여러 개**를 참조하면 이슈당 1건씩 enqueue하는 구조 검토
9. **권한**. 룰 actor(`automation_rules.actor_user_id`)가 대상 이슈 UPDATE 권한 없으면 fail-closed(기존 `IssueMutationPermissionDeniedException` 경로 승계)

## 결정 사항 — 2차 (2026-07-15, Phase B 검토 후 Maxi 확정)

### DEC-11. **PR 3분할** — 이 PR = PR-A

Phase B 적대적 검토(BLOCKER 9 / CONCERN 11)로 범위가 BC 4개 + 프론트 + 마이그레이션 4개 + 신규 포트 + enum 2종(파급 6파일 13지점) + 보안설정으로 불어남 → 직렬 3분할(병렬 PR 충돌 이력 회피).

| PR | 범위 | 모듈 | 선행 |
|---|---|---|---|
| **PR-A (이 PR #274)** | 인바운드 permitAll 3종 + 암호화 키 4종 + prod 조립 HTTP 테스트 인프라 | identity-access · infra · app(test) | — |
| PR-B | `setFixVersions` 포트 + 어댑터 + `SET_FIX_VERSIONS` 액션 + 프론트 계약 | shared-kernel · issue-tracking · automation · apps/web | — |
| PR-C | `PR_MERGED` 트리거 + Git webhook + 서명검증 + 등록 API | automation · apps/web | A, B |

**이 PR의 성격 변경**. FR-AT-07 자체를 완료시키지 않는 **선행 부채 청산 PR**. **FR 카운트 불변 123**, automation BC **6/7 유지**.

### DEC-12. **targetBranch 필터 추가** (BLOCKER B8) — PR-C

`trigger_config`에 `targetBranch`. 기존 `ISSUE_UPDATED`의 `fields` 필터와 동형(JSONB, 구조 추가 0). 미지정 = 전 브랜치.

### DEC-13. **GitLab 유지 + 잔여위험 ADR 명시** (BLOCKER B3) — PR-C

GitLab 웹훅은 원래 `X-Gitlab-Token` 평문이고 GitLab이 HMAC 서명을 제공하지 않아 **우리가 더 강하게 만들 수 없다**. product doc D2 준수. 단 **GITHUB과 동급으로 서술하지 않는다** — 보안등급 차이를 ADR·KDoc 명시.

### DEC-14. **`.env.prod.example` 암호화 키 4종 전부** (BLOCKER B4) — PR-A

기존 3종(slack·MFA·OIDC)이 **전부 누락**돼 있고 이는 [[use-time-validated-env-passes-boot-fails-on-use]]의 **실사고 재발**. surgical changes 예외 — 문서 몇 줄이라 비용 ≈ 0 + PR-A 주제("인바운드가 prod에서 실제로 도는가")와 정합.

## 스펙

전체 스펙. [docs/specs/2026-07-15-fr-at-07-pr-merge.md](../specs/2026-07-15-fr-at-07-pr-merge.md) — **PR-A/B/C를 관통하는 마스터 스펙**. 이 PR은 **§A**만 구현.

**PR-A 핵심 3줄 요약.**
- 중앙 `SecurityConfig`에 인바운드 웹훅 5경로를 **메서드 고정 + 최소 매처**로 permitAll·CSRF-ignore **양쪽에** 등록 → FR-AT-01·FR-SL이 prod에서 되살아남
- `.env.prod.example`에 암호화 키 3종 선언(누락 = 실사고 재발) — automation 키는 PR-C에서 4번째로 추가
- **prod 조립 HTTP 테스트 인프라 신규 구축** — "401이 아님"을 검증할 수단이 현재 저장소에 없음(`BtsApplicationContextTest`는 MOCK 웹환경). PR-C가 재사용

## Brainstorming Check

✅ **통과 (2회 iteration)**. 1회차 = BLOCKER 9 / CONCERN 11 / NIT 7 (적대적 검토 2종 병렬, 실제 코드 대조). 2회차 = 전건 반영 + PR 3분할.

**1회차가 잡아낸 가장 큰 것 3가지.**
1. **B1** — 내가 쓴 `@RequestBody String`은 크기검사 **전에** 본문을 힙에 버퍼링. nginx 110MB 허용 → 미인증 힙 적재. 인용한 FR-AT-01 선례는 **정반대**(`readNBytes`)였는데 값만 가져오고 형태는 slack의 약한 쪽을 베낌. **내가 쓴 완료 기준이 이 결함을 통과시킴**(가짜 그린)
2. **B6** — **내 D3 근거가 코드로 반증**됨("OCC 파라미터가 사라진다" → 형제 커맨드엔 애초에 OCC 파라미터가 없음). 결론은 유지, 근거·필드는 폐기
3. **B4** — `.env.prod.example`에 기존 키 3종 부재 = **머지해도 prod 미동작 확정**. 과거 실사고와 동일 패턴

## Plan (PR-A — spec §A, DEC-15~17 반영)

> **★ 절대 규칙 §1.4 정식 예외 작업.** `DEVELOPMENT.md §1.1`의 **규칙 4 "인증 없는 엔드포인트 추가 금지. Spring Security 필터 우회 금지."** 를 정면으로 건드린다. 선례(FR-DB-03 익명 대시보드·FR-CA-02 iCal 피드)가 밟은 절차 = **ADR + 게이트1 승인 + KDoc 예외 사유 명시**. T1이 그 ADR.
> **표기**. 저장소 관례 `§1.<규칙번호>` 를 따라 **`§1.4`** 로 쓴다 (DEC-17 — 14곳과 일관. `§1.1 #4`는 세 번째 방언이라 금지).
>
> **정확한 프레이밍**. permitAll은 인증을 **없애는** 게 아니라 **검증 주체를 필터 → 컨트롤러(서명 검증)로 옮기는** 것이다. ADR·KDoc이 이 구분을 명시하고, T3가 **유효 서명으로 200을 받아 이를 실증**한다.
>
> **범위 (DEC-15)**. **slack 인바운드 4경로만**. automation 웹훅은 미방어 `issueKey`(`AutomationWebhookController:97`) 때문에 방어심층이 함께 들어오는 **PR-C**로.

### Task 1. ADR — slack 인바운드 permitAll 중앙등록 (§1.4 정식 예외)

**메타**.
- agent: `security-engineer`
- files: [`docs/decisions/2026-07-15-slack-inbound-permitall-central.md`]
- depends-on: []

**내용** (TDD 비대상 — 문서).
- **맥락**. 중앙 `SecurityConfig`가 `anyRequest().authenticated()`(`:186`)로 닫혀 있고 slack 인바운드 미등록 → **FR-SL이 prod에서 사문화**. `docs/plans/2026-07-11-automation-prod-assembly.md:53,165`의 명시적 scope-out + 후속 추적 항목
- **결정**. slack 4경로를 **공유 리스트**(DEC-16)로 permitAll·CSRF-ignore 양쪽 등록
- **§1.4 예외 정당화**. (a) 외부 시스템(Slack)이 호출하므로 BTS 자격증명을 가질 수 없다 (b) 인증은 **컨트롤러의 서명 검증**이 담당 — `SlackSignatureVerifier`(HMAC-SHA256 `v0:{ts}:{rawBody}` · replay 창 ±5분 `:86`(`REPLAY_WINDOW_SECONDS=300L :113`) · 상수시간 `MessageDigest.isEqual` `:78-81` · secret 미설정도 `false` fail-closed `:64-66`) (c) 필터가 막으면 **서명 검증 코드가 실행조차 안 됨** — 보안 강화가 아니라 기능 정지 (d) 폭발 반경은 메서드 고정 + 정확 경로로 봉인
- **★ automation 웹훅을 이번에 열지 않는 이유 명시**(DEC-15). `AutomationWebhookController:97`이 임의 `{"issueKey":"OTHER-1"}`을 무검증 enqueue → 토큰 보유자가 룰을 임의 이슈로 유도 가능(폭발반경 = 룰 actor 권한). 방어심층(FR-7)이 들어오는 PR-C와 함께 연다
- **잔여 위험 명시**. ① slack 경로 **rate limit 부재**(기존 부채, §후속) ② `/slack/install`은 열지 않음(admin 이중가드 유지)
- **대안 기각**. (i) 확장 포인트(`PathContributor`) 선도입 → 범위 폭증, 별도 후속 (ii) 계속 미룸 → FR-SL 사문화 지속
- **선례 링크**. `2026-07-02-fr-db-03-dashboard-share` · `2026-07-09-fr-ca-02-ical-export`
- **폴더**. `docs/decisions/` — 최근 ADR이 전부 여기([[bts-adr-dual-folder-convention]] — `docs/adr/`도 존재하나 신규는 `decisions/`)

**검증**. `bash scripts/verify-master-plan.sh` 통과. ★ **T1 자체 검증에 T3 산출물을 참조하지 않는다**(N-5 — T1 시점에 T3 미실행).

---

### Task 2. prod 조립 HTTP 테스트 베이스 + 기존 조립 테스트 상속 전환

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/ProdAssemblyHttpTestBase.kt`, `backend/modules/app/src/test/kotlin/com/bts/app/BtsApplicationContextTest.kt`]
- depends-on: []

> **TDD 비대상 — 테스트 인프라**(N-2 정정). "클래스 없음"은 컴파일 에러지 실패 테스트가 아니다. 이전 plan의 `TDD 강제. T2=yes`는 거짓이었다.
> **실현 가능성**(N-1 정정). `BtsApplicationContextTest`가 **prod 프로파일로 지금 통과 중**이고 `props`가 주입하는 건 issuer-uri + PEM 2개뿐(`:96-97`). **암호화 키 미설정으로 prod 부팅이 된다는 걸 이 테스트의 존재가 이미 증명**한다(그게 [[use-time-validated-env-passes-boot-fails-on-use]]의 요지 — 부팅 통과, 사용 시 500). MOCK→RANDOM_PORT는 **실 Tomcat 바인딩만 추가**. `/actuator/health`도 이미 permitAll(`:173`).

**내용**.
- `abstract class ProdAssemblyHttpTestBase` — `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ActiveProfiles("prod")` + `TestRestTemplate`
- **기존 `BtsApplicationContextTest`의 `@DynamicPropertySource props` 레시피를 베이스로 추출** (issuer URI + RSA 키 런타임 생성 — 실 시크릿 미커밋)
- **★ 여기에 `bts.slack.signing-secret`을 알려진 테스트 값으로 추가 주입** — T3의 양성 단언(B-2) 재료
- **★ `BtsApplicationContextTest`를 이 베이스 상속으로 전환** (C-5 해소). 안 하면 `webEnvironment` 차이로 `MergedContextConfiguration` 키가 달라져 **컨텍스트 캐시 미공유** → 같은 JVM에 9-BC prod 컨텍스트 **2벌** = 부팅 2회 + `@Scheduled` 워커 2벌이 동일 5433 pgmq 큐 동시 폴링
- **★ MockMvc 금지** — 서블릿 우회로 실 필터체인을 건너뛰어 **가짜 그린**([[multipart-default-limit-app-policy-false-green]]). `TestRestTemplate` = 실서블릿
- KDoc — **사전 조건 명시**(`docker compose -f infra/docker-compose.dev.yml up -d postgres`, 5433 — 기존 조립 테스트와 동일 전제, Testcontainers 미관리) + prod+RANDOM_PORT 셋업 이유([[identity-access-prod-randomport-boot-recipe]])

**검증**. `./gradlew :modules:app:test` — 기존 `BtsApplicationContextTest` 4건 **회귀 0**. ★ `--tests ProdAssemblyHttpTestBase*` **금지**(N-2 — abstract라 0 매칭 → "No tests found"로 빌드 실패). 베이스 자체는 테스트를 갖지 않는다.

---

### Task 3. SecurityConfig — 공유 리스트 + slack 4경로 permitAll/CSRF (T3+T4 병합)

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt`, `backend/modules/app/src/test/kotlin/com/bts/app/SlackInboundPermitAllTest.kt`]
- depends-on: [2]

> **N-3 반영 — 이전 T3+T4 병합.** 같은 파일·같은 에이전트·같은 테스트 클래스·연속 실행이었고, 이전 T4는 스스로 "RED 없음"이라 적어 `TDD 강제=yes`와 모순이었다(`/bts-impl`의 `test:`→`feat:` 순서 검증에서 **feat 없는 태스크** 판정이 불명).

**RED** — `SlackInboundPermitAllTest.kt` (T2 베이스 상속).

**★ 양성 단언 (B-2)**. "401이 아님"이라는 음성 단언에 갇히지 않는다. `SlackSignatureVerifier`는 결정론적 HMAC-SHA256이고 secret은 프로퍼티 주입이므로, 테스트가 **유효 서명을 계산**해 200을 받으면 필터 통과가 **양성으로 증명**된다.

```kotlin
@Test fun `유효 서명 url_verification 이 필터를 통과해 challenge 를 에코한다`() {
    val body = """{"type":"url_verification","challenge":"abc123"}"""
    val ts = clockNow().epochSecond.toString()
    val sig = "v0=" + hmacSha256Hex(TEST_SIGNING_SECRET, "v0:$ts:$body")   // 검증기와 동일 base string
    val res = rest.exchange("/slack/events", POST, entity(body, ts, sig), String::class.java)
    assertThat(res.statusCode).isEqualTo(HttpStatus.OK)          // ← 필터 통과 + 컨트롤러 도달 동시 증명
    assertThat(res.body).contains("abc123")                       // ← 서명 검증이 실제로 통과했음(S-A1)
}
@Test fun `무효 서명은 컨트롤러가 401 을 준다`() {                    // S-A2 — 검증 주체가 컨트롤러임을 실증
    // 서명만 틀린 동일 요청 → 401 (필터가 아니라 SlackEventsController:74-77 이 준 것)
}
```
- `SlackEventsController:88-91` — `url_verification` → `ResponseEntity.ok(mapOf("challenge" to challenge))` (확인 완료)
- 실패 메시지(예상). **401** (현재 부채 상태 — **이 RED가 부채의 존재 증명**)
- **음성 가드 동반** — EC-A1 `POST /slack/install` 익명 → **401 유지**(범위 누출 0). ★ 일부러 위반을 넣어 fail을 잡는지 확인([[archunit-vacuous-rule-silent-pass]] — 통과가 검증을 의미하지 않음)
- `/slack/commands` · `/slack/interactions` · `/slack/install/callback`(GET) **각각 개별 단언**(단수로 뭉뚱그리면 매처 오타를 못 잡음)

**GREEN** — `SecurityConfig.kt`.

**★ 공유 리스트 구조 (DEC-16)** — 한쪽만 등록이 컴파일 단위에서 불가능해진다.
```kotlin
// companion object
/** slack 인바운드 4경로 — DEVELOPMENT.md §1.4 정식 예외(ADR 2026-07-15-slack-inbound-permitall-central · 게이트1 승인).
 *  permitAll 과 CSRF-ignore 를 이 단일 목록에서 함께 구동한다 — 한쪽만 등록하는 실수를 구조적으로 차단(FR-MF-01 BLOCKER-1 재발 방지). */
val SLACK_INBOUND_PATHS = listOf(
    HttpMethod.POST to "/slack/events",
    HttpMethod.POST to "/slack/commands",
    HttpMethod.POST to "/slack/interactions",
    HttpMethod.GET  to "/slack/install/callback",
)
```
- csrf 블록(`:133-146`) · authorize 블록(`:151-184`) **둘 다 같은 리스트를 순회**
- **★ `/api/**` authenticated(`:185`)보다 위에** — 순서 의존 (리뷰 확인)
- **★ `/slack/install` 은 목록에 없다** (authenticated + admin 이중가드 유지)
- **★ `/slack/**` 금지** — 정확 경로만

**REFACTOR**. KDoc — **`§1.4 정식 예외(ADR … · 게이트1 승인)`** 표기(DEC-17, 기존 14곳과 일관). 선례 형식은 `PUBLIC_DASHBOARDS_PATH:248` KDoc.
- **★ 기존 `§1.4` 14곳은 건드리지 않는다**(surgical). 문서 절번호 충돌은 기존 부채 → §후속

**검증**. `./gradlew :modules:app:test --tests SlackInboundPermitAllTest` + `:modules:identity-access:test` + `:modules:slack-integration:test` 회귀 0

---

### Task 4. `.env.prod.example` — slack 동작 변수 + 암호화 키 3종

**메타**.
- agent: `security-engineer`
- files: [`infra/prod/.env.prod.example`]
- depends-on: []

> **★ B-1 반영 — 이게 없으면 이 PR의 주장이 거짓이 된다.** `grep -rn "SLACK" infra/` → `nginx.conf:40` **한 줄뿐**. permitAll만 열고 signing secret이 없으면 `SlackSignatureVerifier.kt:64-66`(`if (signingSecret.isBlank()) return false`)이 fail-closed로 막아 **"필터의 401"이 "컨트롤러의 401"로 바뀔 뿐 기능 변화 0**.

**내용** (TDD 비대상 — 배포 매니페스트).

| 변수 | 프로퍼티 | 없으면 |
|---|---|---|
| `BTS_SLACK_SIGNING_SECRET` | `bts.slack.signing-secret` | **서명 검증 전부 401** ← B-1 핵심 |
| `BTS_SLACK_CLIENT_ID` / `_SECRET` / `_REDIRECT_URI` | `bts.slack.{client-id,client-secret,redirect-uri}` | `/slack/install/callback` 동작 불가 |
| `BTS_SLACK_STATE_KEY` | `bts.slack.state-key` | OAuth state 서명 불가 (`SlackOAuthStateSigner.kt:58`) — **impl에서 실재 확인 후 반영** |
| `BTS_SLACK_ENCRYPTION_KEY` / `_SALT` | `bts.slack-encryption.{key,salt}` | 봇 토큰 복호화 500 (서명 통과 **후** 하류) |
| `BTS_MFA_ENCRYPTION_KEY` / `_SALT` | (`MfaEncryptionConfig`) | MFA 첫 호출 500 — **실사고 재발분**(DEC-14) |
| `BTS_OIDC_ENCRYPTION_KEY` / `_SALT` | (`OidcEncryptionConfig`) | OIDC 첫 호출 500 — 동일 |

- **salt는 hex** (`SecretEncryptor` 계약) — 생성법 주석 병기 (`openssl rand -hex 32`)
- automation 키(`BTS_AUTOMATION_ENCRYPTION_KEY`)는 **PR-C**에서 (빈 신설과 함께)
- ★ 전부 **조용히 fail-closed** — `@Value` 기본값 `""` + 사용 시점 검증. `BTS_AUTH_ISSUER_URI` 같은 fail-fast placeholder가 **아니다**
- **DEC-14 — surgical changes 예외**(MFA/OIDC 부채분). 같은 종류 사고가 이미 터졌고 문서 몇 줄이라 비용 ≈ 0 + PR-A 주제("인바운드가 prod에서 실제로 도는가")와 정합

**검증**. compose가 `env_file: ./prod/.env`로 전체 주입하므로 변수 나열이 곧 유효(리뷰 확인 — no-op 아님). 프로퍼티명 relaxed binding 정합 육안 확인.

## Plan 메타

- **task 수**. **4** (이전 5 → T3+T4 병합, N-3)
- **dispatch**. **직렬**
  - ~~사유 ①. pre-commit race~~ — **삭제**(N-4). `.lintstagedrc.json`은 `apps/web/**/*.{ts,tsx,js,jsx}`만 대상인데 **PR-A는 `apps/web` 0파일** → 매칭 0이면 lint-staged는 stash 없이 조기 종료 → race **구조적으로 발화 불가**. 작동할 수 없는 메커니즘을 근거로 인용한 것은 이 plan이 B6에서 스스로 규탄한 결함과 같은 종류
  - **사유. Gradle 모듈 컴파일 직렬화**([[bts-plan-wave-gradle-module-compile]]) — T2→T3에 유효. T1(`.md`)·T4(`.env.prod.example`)는 컴파일·lint 무관이라 병렬 가능하나, **4개 소규모 태스크에 병렬 이득 < 조율 비용** → 직렬 유지(무해)
  - 자연 의존. T3←[2]
- **예상 시간**. 약 15분
- **TDD 강제**. **T3만 yes**. T1·T4 = 문서·매니페스트, T2 = 테스트 인프라 (규칙 #14의 "새 기능" 아님) — N-2 정정
- **추가 검증**. `:modules:app:test`(9 BC 조립) · `:modules:identity-access:test` · `:modules:slack-integration:test` · ktlint · detekt · `bash scripts/verify-master-plan.sh`
- **FR 카운트**. **불변 123**. automation BC **6/7 유지** (부채 청산이라 FR 미완료)

## 리스크

| # | 리스크 | 대응 |
|---|---|---|
| R1 | **절대 규칙 §1.4 정면 위반** — "인증 없는 엔드포인트 추가 금지" | T1 ADR + **게이트1 승인**(선례 FR-DB-03·FR-CA-02와 동일 절차). 미승인 시 진행 불가 |
| R2 | **BC 격리 예외** — slack 사유로 identity-access 수정 | 선례 = PR #13 옵션 C 패턴. **security-engineer 공동 검토**(T1·T3·T4가 이미 그 에이전트) |
| R3 | ~~prod 신규 노출 (automation 미방어 issueKey)~~ | **DEC-15로 해소** — automation 웹훅을 PR-C(방어 동반)로 이관. **slack은 방어 온전**(서명+replay+상수시간) |
| R4 | ~~T2 불확실~~ → **하향**(N-1). `BtsApplicationContextTest`가 prod로 지금 통과 중이라 부팅은 검증됨 | 실제 리스크는 **C-5(컨텍스트 2벌)** → T2가 기존 테스트 상속 전환으로 해소. ★ **"경로 매처 단위 테스트로 축소" 대안은 삭제** — 필터체인 통과를 검증 못 해 PR이 자기 주장을 증명 못 한 채 경로를 열게 됨 |
| R5 | ~~필터 401 ↔ 컨트롤러 401 구분 곤란~~ | **B-2로 소멸** — 유효 서명 → 200 challenge 에코 **양성 단언**. 응답 포렌식(`WWW-Authenticate` — 저장소 선례 0건) 불요, 우리 코드에만 의존 |
| R6 | ~~기존 KDoc `§1.4` 오참조~~ | **C-3로 반증** — 오타가 아니라 9파일 14곳의 **관례**(`§1.<규칙번호>` 방언). DEC-17로 관례 준수 + 별건 등재 |
| R7 | 휴면 stash 오염 — `stash@{0}`에 타 세션 `WIP on feature/fr-pr-03-ooo` 존재 | **impl prompt에 `git stash` 금지 명시**([[subagent-git-stash-worktree-shared-collision]]) |
| R8 | slack 경로 rate limit 부재 | T1 ADR 잔여 위험 + §후속 등재. 기존 부채(신규 아님) |
| R9 | **T4의 `BTS_SLACK_STATE_KEY` 실재 미확인** | impl에서 `SlackOAuthStateSigner.kt` 확인 후 반영. **추측 기재 금지** |

## 후속 (PR-A 범위 밖)

- **automation 웹훅 permitAll** — PR-C에서 방어심층(FR-7)과 함께 (DEC-15)
- **permitAll 확장 포인트**(`PathContributor` 류) — 부재가 이 부채의 **구조적 원인**. DEC-16의 공유 리스트는 SecurityConfig **내부** 해결이라 BC별 등록은 여전히 수동
- **`§1.4` 표기 ↔ 문서 절번호 충돌** — 9파일 14곳이 `§1.<규칙번호>` 방언, `DEVELOPMENT.md §1.4`는 "외부 의존성". 별건 일괄 정리 (DEC-17)
- slack 경로 rate limit

### 구현 중 새로 발견 (2026-07-15, T4)

- **★ `bts.webhook-encryption.{key,salt}` 도 prod 미선언** (`WebhookEncryptionConfig.kt:51,54` — 아웃바운드 웹훅 `webhookSecretEncryptor`). DEC-14가 열거한 4종(slack·MFA·OIDC·automation) **밖의 5번째 조용한 실패**이며 당시엔 존재를 몰랐다. DEC-14의 논리(같은 사고 유형 + 비용≈0)가 그대로 적용됨 → **게이트2에서 Maxi 판단** (PR-A에 추가 / PR-C에서 automation 키와 함께 / 별건)
- **FR-SL-01 ADR의 프로퍼티 표기 오류** — `docs/decisions/2026-07-07-fr-sl-01-slack-bot-app.md` 가 `bts.slack.encryption.{key,salt}`(점)로 적었으나 **코드 정본은 `bts.slack-encryption.{key,salt}`(하이픈)** (`SlackEncryptionConfig.kt:54,57` `const val PROPERTY_KEY`). ADR 정정 필요
- **BC별 암호화 프로퍼티 표기 체계가 갈린다** — slack만 하이픈(`bts.slack-encryption.*`), MFA·OIDC는 점(`bts.mfa.encryption.*`·`bts.oidc.encryption.*`). 한 파일에 섞여 있으니 향후 편집 시 주의

## 구현 결과 (PR-A, 2026-07-15)

| Task | 상태 | 커밋 |
|---|---|---|
| T1 ADR | ✅ | `938b36742` |
| T2 prod 조립 HTTP 베이스 | ✅ | `9f0ff1868` |
| T3 SecurityConfig 공유 리스트 + slack 4경로 | ✅ TDD 준수 | `3e91f0ebc`(red) → `acb51ed15`(green) → `2c4a2eeb8`(refactor) |
| T4 `.env.prod.example` | ✅ | `eff22ffd9` |

### 구현이 plan을 정정한 것 (계획의 결함 3건)

1. **★ EC-A1이 vacuous 테스트가 될 뻔했다 (T3 발견).** plan/spec은 `/slack/install` 범위 누출 가드를 **상태코드 401 단언**으로 지정했다. 실제로 `/slack/install`을 `SLACK_INBOUND_PATHS`에 **일부러 넣어보니** — permitAll이 새어도 **상태는 여전히 401**이었다(컨트롤러의 `SlackActorExtractor`가 401을 던지므로). 즉 지정대로 짰으면 **누출이 있는데도 통과**했다. 판별자는 **응답 본문**(필터 401 vs 컨트롤러 ProblemDetail)뿐이라 body 단언으로 교체했다. [[archunit-vacuous-rule-silent-pass]]의 "일부러 위반 넣어 fail 확인"이 실제로 결함을 잡아낸 사례.
2. **EC-A1의 메서드가 틀렸다** — plan/spec은 `POST /slack/install`이라 적었으나 실제 매핑은 `@GetMapping`(`SlackInstallController.kt:60`). POST면 CSRF가 permitAll 여부와 무관하게 항상 거부해 **원리적으로 누출을 못 잡는다**(위 vacuous의 더 심한 버전). GET으로 정정.
3. **`SlackSignatureVerifier` 줄번호 stale** (T1 발견) — plan/spec 3곳이 이전 리비전 기준. 현행 115줄 기준 `:64`/`:78`/`:86`/`:113`으로 정정 완료(`18b48ce3d`).

### 구현 중 부딪힌 함정 (기록)

- **`TestRestTemplate`이 302를 자동 추종** — `:modules:app`에 httpclient5가 없어 `HttpURLConnection`으로 폴백. `/slack/install/callback`의 302를 따라가 `/admin/slack`(authenticated)에서 401을 받으면 **permitAll이 정상인데도 미등록과 똑같은 401**로 보인다. 해당 경로만 `instanceFollowRedirects=false` 클라이언트로 원 응답 관측(실 Tomcat·실 필터체인 유지 — MockMvc 우회 아님). **PR-C가 이 인프라를 재사용**.
- **KDoc에 슬래시-slack-와일드카드 리터럴 금지** — 중첩 블록 주석을 열어 KDoc의 종료 토큰을 삼키고 companion object 전체가 주석으로 사라진다(overload ambiguity 등 cascade). 기존 저장소 KDoc도 이 리터럴을 피하는 관례.
- **익명 요청의 CSRF 거부는 403이 아니라 401** — `ExceptionTranslationFilter`가 인증 진입점으로 넘긴다. 그래서 "CSRF 미등록"과 "permitAll 미등록"이 **증상으로 구분되지 않는다** → DEC-16 공유 리스트가 필요한 또 하나의 이유.
- **`grep -rn "SLACK" infra/` 는 0건**이었다(대문자). plan이 인용한 `nginx.conf:40`은 소문자 `slack` 프록시 location — 즉 환경변수 부재는 plan 전제보다 **더 확실**했다.
- **relaxed binding을 실측했다** — `application.yml`의 `bts:` 블록에 slack/mfa/oidc가 **하나도 없어** 이 저장소의 명시 placeholder 관례(`${BTS_MINIO_ACCESS_KEY:minioadmin}`)를 벗어난 선례 없는 경로였다. spring-core 6.1.14 `SystemEnvironmentPropertySource` 직접 실행으로 11/11 매핑 확인(하이픈 케이스 포함 — `checkPropertyName`이 점→`_`, 하이픈→`_`, 둘 다 치환 후보를 순차 시도).
- **`gradlew`는 `backend/gradlew`** — 워크트리 루트에 없다. 루트에서 `./gradlew`를 호출하면 "no such file"이 나는데 `| tail`을 붙이면 **파이프 종료코드가 0이라 가짜 그린**이 된다(controller가 실제로 한 번 당함). 검증 명령은 `cd backend` + `${PIPESTATUS[0]}` 확인.

## 리뷰 결과

### plan-eng-review + 아웃사이드 보이스 (2026-07-15)

> **스킬 deviation 기록**. gstack `plan-eng-review`는 시작 전 텔레메트리 동의·`CLAUDE.md` 라우팅 규칙 주입·cross-project learnings 설정 등 **Maxi가 요청하지 않은 부수 효과**와, 이슈마다 개별 AskUserQuestion + TODOS 등록 + `## GSTACK REVIEW REPORT` 삽입을 요구한다. BTS 워크플로우는 (a) 결과를 이 `## 리뷰 결과` 섹션에 쓰도록 정하고 (b) 바로 다음이 **게이트1**이라 결정을 한 번에 받는다. 사용자 지침 > 스킬이므로 **리뷰 본체 + 아웃사이드 보이스만 수행**하고 부수 효과·중복 게이트는 생략.

**BLOCKER 2 / CONCERN 5 / NIT 5.** 아웃사이드 보이스가 실제 코드를 읽고 확신도 표기 + 근거 인용으로 판정.

| # | 확신도 | 발견 | 처리 |
|---|---|---|---|
| **B-1** | **9** | **PR-A의 헤드라인 주장이 FR-SL에 대해 거짓.** `.env.prod.example`에 **`BTS_SLACK_SIGNING_SECRET`도 없다**(`grep -rn "SLACK" infra/` → `nginx.conf:40` 한 줄뿐). `SlackSignatureVerifier.kt:64-66`이 `if (signingSecret.isBlank()) return false` fail-closed → permitAll만 열면 **"필터의 401" → "컨트롤러의 401"** 로 바뀔 뿐 기능 0. slack **암호화** 키(봇 토큰)는 서명 통과 **후** 하류라 순서가 뒤집힘. ★ B4를 잡았다고 자평한 검토가 같은 결함을 한 층 위에서 놓침 | **T5에 signing secret + client id/secret/redirect-uri 추가**. spec §A-4 표 4행 → 확장 |
| **B-2** | **8** | **T3 검증 방법이 plan에서 미해결**(R5 "불가 시 판별 헬퍼")인데 **훨씬 단순한 경로를 통째로 놓침**. 응답 포렌식(`WWW-Authenticate` 유무 — 저장소 선례 **0건**) 불필요. `SlackSignatureVerifier`는 결정론적 HMAC-SHA256(`v0:{ts}:{rawBody}`)이고 secret은 프로퍼티 주입 → **T2 베이스가 `bts.slack.signing-secret`을 주입하고 테스트가 유효 서명을 계산해 `url_verification` POST → 200 + challenge 에코 단언**. (a) 필터 통과가 **양성**으로 증명 (b) S-A2 실증 (c) 판별 헬퍼 불요 (d) 우리 코드에만 의존(Spring Security 내부 미의존) | **채택. R5 삭제** |
| **C-1** | 7 | **전략 오조준** — 방어 없이 경로를 먼저 열고 방어는 2 PR 뒤(R3). 순 효과 = **FR-SL은 여전히 죽어 있고**(B-1) **FR-AT-01은 "안전하게 죽은 상태"→"알려진 미방어 3종을 달고 살아 있는 상태"**. PR-A 선행 근거(spec:17 (2)(3))는 **테스트 인프라만** 정당화하지 경로 개방을 정당화하지 않음 | **→ DEC-15 (Maxi 확정)** |
| **C-2** | **8** | **FR-A6 "향후 경로 추가 강제"는 테스트로 불가능**. 열거식 테스트는 자기가 아는 경로만 단언 — 미래에 한쪽만 등록된 경로는 **존재를 모르므로 영원히 못 잡음**. 게다가 `SecurityConfig.kt:209`가 `private companion object`라 `com.bts.app` 테스트가 상수 접근 불가 → **경로 리터럴을 복제**하게 되고 그 복제본이 드리프트 = 가드가 막으려는 결함을 가드가 재생산. ★ **구조로 풀면 더 단순·더 강함** — 5경로를 `List<Pair<HttpMethod,String>>` 하나로 두고 csrf·authorize를 **같은 리스트에서 구동**하면 한쪽만 등록이 **컴파일 단위에서 불가능** → 가드 테스트 자체 불요. plan은 `PathContributor` 대공사 ↔ 아무것도 안 함의 **거짓 이분법**에 갇혀 그 사이 20줄 리팩터링을 못 봄 | **→ DEC-16 (Maxi 확정)** |
| **C-3** | **9** | **R6이 사실과 다름 — 2곳이 아니라 9개 파일 14곳이고, 오타가 아니라 관례.** `SecurityConfig.kt`에만 6곳(`:59,:71,:176,:183,:246,:257`). `:117`이 `// DEVELOPMENT.md §1.5 — CSRF 비활성화 금지` → 저장소는 **`§1.<규칙번호>` 방언을 일관 사용**(§1.4=규칙4, §1.5=규칙5). 내 `§1.1 #4`는 **세 번째 방언** → 한 파일 안에 두 표기가 나란히 서게 됨. "surgical"이 여기선 **혼란 추가**만 낳음 | **→ DEC-17 (Maxi 확정)** |
| **C-4** | 6 | **test SecurityConfig `/**` ↔ 중앙 `/*` 의도적 divergence**. `AutomationTestSecurityConfig.kt:49`는 `/api/v1/automation/webhooks/**`. 머지 후 EC-A2(`/a/b`→401)가 `:modules:app:test`에선 통과하나 automation 자기 테스트에선 permitAll → **"test 초록불이 prod를 대변하지 않는다"(부채의 원인)가 새 형태로 하나 더**. ★ 두 test config를 **제거하면 안 됨**(`@TestConfiguration`이고 중앙 `SecurityConfig`는 identity-access 소속이라 automation/slack test 클래스패스에 부재) — plan의 결론(침묵)은 맞으나 divergence **명시 필요** | **plan에 명시 추가** |
| **C-5** | 6 | **prod 조립 컨텍스트 2벌**. `BtsApplicationContextTest:28-29`는 MOCK, T2는 RANDOM_PORT → `MergedContextConfiguration` 키가 달라 **캐시 미공유** → 같은 JVM에 9-BC prod 컨텍스트 2개 = 부팅 2회 + `@Scheduled` 워커 2벌이 동일 5433 pgmq 큐 동시 폴링. ★ 원인이 **files 화이트리스트라는 기계적 규칙**("추출만 하고 기존 테스트는 안 건드림")이 아키텍처 결과를 결정한 것. "예상 20분"에 2배 부팅 미반영 | **T2 files에 `BtsApplicationContextTest.kt` 포함 → 베이스 상속으로 컨텍스트 1개** |
| **N-1** | 8 | **T2는 실현 가능. R4("가장 불확실")가 오조준.** `BtsApplicationContextTest`가 prod로 **지금 통과 중**이고 `props`가 주입하는 건 issuer-uri + PEM 2개뿐(`:96-97`). **암호화 키 미설정으로 prod 부팅이 된다는 걸 이 테스트의 존재가 이미 증명**(그게 [[use-time-validated-env-passes-boot-fails-on-use]]의 요지). MOCK→RANDOM_PORT는 실 Tomcat 바인딩만 추가. `/actuator/health`도 이미 permitAll(`:173`). ★ **R4의 대안(경로 매처 단위 테스트로 축소)이 위험** — 필터체인 통과를 검증 못 함 = spec §A-5가 지적한 원점 회귀 = **PR이 자기 주장을 증명 못 한 채 prod 경로를 염**. 실제 T2 리스크는 부팅이 아니라 C-5 | **R4 하향 + 대안 삭제** |
| **N-2** | 7 | **T2의 RED가 RED가 아님** — "클래스 없음"은 컴파일 에러지 실패 테스트가 아님 → `TDD 강제. T2=yes`는 거짓. + 베이스가 `abstract`면 `--tests ProdAssemblyHttpTestBase*`가 0 매칭 → **"No tests found"로 빌드 실패**. 구체 클래스면 T3 상속 시 smoke 중복 실행. plan이 미결정 | **T2를 TDD 비대상(인프라)로 정정 + abstract 명시, 검증은 T3 경유** |
| **N-3** | 7 | **T4는 T3에 병합돼야 함** — 같은 파일·같은 에이전트·같은 테스트 클래스·바로 다음. T4 스스로 "RED 없음"이라 적어 `TDD 강제. T4=yes`와 모순. `/bts-impl`의 `test:`→`feat:` 순서 자동 검증에서 **feat 커밋 없는 태스크** 판정이 불명 | **T3+T4 병합 → 태스크 4개** |
| **N-4** | **9** | **직렬 사유 ①이 발화 불가.** `.lintstagedrc.json`은 `apps/web/**/*.{ts,tsx,js,jsx}`만 대상인데 **PR-A는 `apps/web` 0파일**(T1=docs, T2·T3=backend/modules/app, T5=infra). 매칭 0이면 lint-staged는 stash 없이 조기 종료 → race **구조적으로 발화 불가**. 사유 ②(Gradle 컴파일)는 T2→T3에 유효. ★ 결정(직렬)은 무해하나 **작동할 수 없는 메커니즘을 근거로 인용** = 이 plan이 B6에서 스스로 규탄한 결함과 같은 종류 | **근거 정정**(사유 ① 삭제, ②만 유지) |
| **N-5** | 6 | **T1 순서는 맞음** — 워크플로우가 `review-plan → 게이트1 → impl(T1~)`이라 **T1은 승인 후 실행**. 게이트1은 plan §리스크로 판단하지 ADR로 판단하지 않음. 단 **T1 검증이 미래를 참조**("ADR 링크가 T3 KDoc에서 참조됨" — T1 시점에 T3 미실행). + [[bts-adr-dual-folder-convention]] 관련 `docs/decisions/` 선택 근거 미명시 | **T1 검증 조건 정정 + 폴더 선택 근거 명시** |

**아웃사이드 보이스가 반증한 것(문제 없음 확인)**. nginx 프록시는 `/slack/*`·`/api/v1/automation/webhooks/*` 모두 백엔드로 전달([[nginx-spa-route-shadowed-by-backend-proxy-prefix]] 재발 아님) · `.env.prod.example` 추가는 no-op 아님(compose `env_file` 전체 주입) · `/api/v1/automation/webhooks/*` 단일 세그먼트가 맞음(`@RequestMapping` + `@PostMapping("/{token}")`) · T3의 등록 순서 지적(`:185` `/api/**` authenticated 위) 정확.

## 결정 사항 — 3차 (2026-07-15, 리뷰 후 Maxi 확정)

### DEC-15. **PR-A는 slack 인바운드만 연다** (C-1) — automation 웹훅은 PR-C로

**위험도로 가른다.**

| 경로 | 방어 상태 | PR |
|---|---|---|
| `/slack/{events,commands,interactions}` · `/slack/install/callback` | **온전** — HMAC-SHA256 서명 + replay 창 ±5분 + 상수시간 비교 + fail-closed(`SlackSignatureVerifier`) | **PR-A** |
| `/api/v1/automation/webhooks/*` | **미방어** — `AutomationWebhookController:97`이 임의 `{"issueKey":"OTHER-1"}`을 무검증 enqueue. 토큰 보유자가 룰을 임의 이슈로 유도 가능(폭발반경은 룰 actor 권한까지) | **PR-C** (방어심층 FR-7이 같은 PR에 들어옴) |

- **D2 "3종 일괄" 부분 철회**. 근거 = C-1. 방어 없이 여는 것이 부채 청산의 목적이 아님
- **★ C-4도 동시 해소.** automation을 빼면 test config `/**` ↔ 중앙 `/*` divergence가 발생하지 않는다. slack test config(`SlackTestSecurityConfig.kt:64-67`)는 이미 **정확 경로**라 중앙 등록과 일치
- **B-1 수정과 결합해 실효 발생**. signing secret이 들어가야 FR-SL이 실제로 살아남 (T4)

### DEC-16. **공유 리스트 구조로 이중등록 불가능화** (C-2)

경로 목록을 `List<Pair<HttpMethod, String>>` **하나**로 두고 `csrf.ignoringRequestMatchers(...)`와 `auth.requestMatchers(...)`를 **같은 리스트에서 구동**한다 → 한쪽만 등록하는 것이 **컴파일 단위에서 불가능** → FR-A6 가드 테스트 자체가 불요.

- **기각**. 열거식 가드 테스트 — 미래 경로를 원리적으로 못 잡고, `SecurityConfig.kt:209`가 `private companion object`라 테스트가 **경로 리터럴을 복제**해 drift(가드가 막으려는 결함을 가드가 재생산)
- **발상 선례**. fixture가 helper를 호출해 drift를 본질 차단한 패턴(learnings 2026-05-23 "fixture 옵션 B") — 회귀 가드보다 **사람 의존 0인 본질 차단**이 우선
- **거짓 이분법 해소**. `PathContributor` 대공사(§A-9 후속) ↔ 아무것도 안 함 사이의 **약 20줄 지역 리팩터링**

### DEC-17. **`§1.4` 지역 관례 따름** (C-3) + 별건 등재

신규 KDoc도 **`§1.4 정식 예외`** 로 써서 기존 14곳과 일관. 문서 절번호(`§1.4 외부 의존성`)와의 충돌은 **기존 부채**이며 본 PR이 만든 게 아니다 → **별건 후속**으로 등재(§후속).

- **기각**. `§1.1 #4` 신규 표기 — 한 파일 안에 두 표기가 나란히 서는 **세 번째 방언**
- **기각**. 14곳 일괄 정정 — 본 FR 무관 메모 정리로 보안 PR의 리뷰 초점을 흐림 (surgical changes)
