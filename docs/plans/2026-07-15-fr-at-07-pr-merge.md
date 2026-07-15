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

`SlackSignatureVerifier.kt` — HmacSHA256, base string `v0:{ts}:{rawBody}`, **replay 방어 ±5분**(`:110-112`), **상수시간 비교** `MessageDigest.isEqual`(`:104-107`), `Clock` 주입, fail-closed(secret 미설정도 `false`→401).

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

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
