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

## Plan (PR-A — spec §A만)

> **★ 절대 규칙 #4 정식 예외 작업.** `DEVELOPMENT.md §1.1` **#4 "인증 없는 엔드포인트 추가 금지. Spring Security 필터 우회 금지."** 를 정면으로 건드린다. 선례(FR-DB-03 익명 대시보드·FR-CA-02 iCal 피드)가 밟은 절차 = **ADR + 게이트1 승인 + KDoc 예외 사유 명시**. T1이 그 ADR.
>
> **정확한 프레이밍**. permitAll은 인증을 **없애는** 게 아니라 **검증 주체를 필터 → 컨트롤러(서명 검증)로 옮기는** 것이다. ADR과 KDoc이 이 구분을 명시해야 한다(S-A2가 이를 검증).

### Task 1. ADR — 인바운드 웹훅 permitAll 중앙등록 (절대규칙 #4 정식 예외)

**메타**.
- agent: `security-engineer`
- files: [`docs/decisions/2026-07-15-inbound-webhook-permitall-central.md`]
- depends-on: []

**내용** (TDD 비대상 — 문서).
- **맥락**. 중앙 `SecurityConfig`가 `anyRequest().authenticated()`(`:186`)로 닫혀 있고 인바운드 경로 미등록 → **FR-AT-01·FR-SL이 prod에서 사문화**. `docs/plans/2026-07-11-automation-prod-assembly.md:53,143,165`의 명시적 scope-out + 후속 추적 항목
- **결정**. 5경로를 메서드 고정 + 최소 매처로 permitAll·CSRF-ignore 양쪽 등록
- **#4 예외 정당화**. (a) 외부 시스템(GitHub/GitLab/Slack)이 호출하므로 BTS 자격증명을 가질 수 없다 (b) 인증은 **각 컨트롤러의 서명 검증**이 담당(`SlackSignatureVerifier` — HMAC+replay창+상수시간, fail-closed) (c) 필터가 막으면 **서명 검증 코드가 실행조차 안 됨** — 보안 강화가 아니라 기능 정지 (d) 폭발 반경은 메서드 고정 + 단일세그먼트/정확경로로 봉인
- **잔여 위험 명시**. ① rate limit 부재 ② `AutomationWebhookController:97`의 **무검증 `issueKey` enqueue**(spec §C-7 C-d) ③ actor 임의 지정(spec 부록 A C-e) — **prod에서 죽어 있어 문제가 안 되던 것들이 처음 열린다**(C-A2)
- **대안 기각**. (i) 확장 포인트(`PathContributor`) 선도입 → 범위 폭증, 별도 후속 (ii) 계속 미룸 → FR-AT-01·FR-SL 사문화 지속 + PR-C 불가
- **선례 링크**. `2026-07-02-fr-db-03-dashboard-share` · `2026-07-09-fr-ca-02-ical-export`

**검증**. `bash scripts/verify-master-plan.sh` 통과 + ADR 링크가 T3 KDoc에서 참조됨

---

### Task 2. prod 조립 HTTP 테스트 베이스 신규

**메타**.
- agent: `qa-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/ProdAssemblyHttpTestBase.kt`]
- depends-on: []

> **왜 신규인가**(spec §A-5 / 부록 A C-j). 유일한 9-BC 조립 테스트 `BtsApplicationContextTest`는 `@SpringBootTest` **기본(MOCK) 웹환경** → **실 HTTP 요청 불가**, MockMvc autowire 안 됨. "permitAll이 실제로 필터를 통과시키는가"를 **검증할 수단이 저장소에 없다**.

**RED**.
- 파일. `ProdAssemblyHttpTestBase.kt`
- 내용. `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ActiveProfiles("prod")` + `TestRestTemplate` 노출
- **기존 `BtsApplicationContextTest`의 `@DynamicPropertySource props` 레시피 재사용** — issuer URI + RSA 키 런타임 생성(`KeyPairGenerator`, 실 시크릿 미커밋). 그대로 복제 말고 **공용 베이스로 추출** 후 기존 테스트도 상속 검토(단, 기존 테스트 파일 수정은 T2 범위 — files에 없으면 BLOCKED이므로 **추출만 하고 기존 테스트는 건드리지 않음**)
- 실패 메시지(예상). 클래스 없음
- **★ MockMvc 금지** — 서블릿 우회로 실제 필터체인/서블릿 상한을 건너뛰어 **가짜 그린**([[multipart-default-limit-app-policy-false-green]]). `TestRestTemplate` = 실서블릿

**GREEN**. 베이스 클래스 + smoke 1건(`/actuator/health` 200 — 인프라 자체 동작 확인)

**REFACTOR**. KDoc — **사전 조건 명시**(`docker compose -f infra/docker-compose.dev.yml up -d postgres`, 5433 — 기존 조립 테스트와 동일 전제, Testcontainers 미관리) + prod+RANDOM_PORT 셋업 이유([[identity-access-prod-randomport-boot-recipe]])

**검증**. `./gradlew :modules:app:test --tests ProdAssemblyHttpTestBase*`

**★ 리스크**. prod 프로파일 부팅은 PEM 키·DataSource·암호화 키 등 비자명 셋업 의존. 이 태스크가 **가장 불확실** — 실패 시 Maxi 보고 후 대안(경로 매처 단위 테스트로 축소) 협의.

---

### Task 3. SecurityConfig — 인바운드 5경로 permitAll + CSRF ignore

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/identity-access/src/main/kotlin/com/atlas/bts/identity/config/SecurityConfig.kt`, `backend/modules/app/src/test/kotlin/com/bts/app/InboundWebhookPermitAllTest.kt`]
- depends-on: [2]

**RED**.
- 파일. `InboundWebhookPermitAllTest.kt` (T2 베이스 상속)
- 테스트. **5경로 각각 개별**(부록 A C-9 — 단수로 뭉뚱그리면 매처 오타를 못 잡음)
  ```kotlin
  @Test fun `POST automation webhook 은 필터에 막히지 않는다`() {
      val res = rest.postForEntity("/api/v1/automation/webhooks/nonexistent-token", "{}", String::class.java)
      assertThat(res.statusCode).isNotEqualTo(HttpStatus.UNAUTHORIZED)  // 필터 통과
      assertThat(res.statusCode).isNotEqualTo(HttpStatus.FORBIDDEN)     // CSRF 통과
      // 컨트롤러 도달 증거 = 404(토큰 미존재). 200 이 아니라 "필터가 막지 않음"을 단언
  }
  // /slack/events · /slack/commands · /slack/interactions (POST) — 서명 없음 → 401 이되
  //   ★ 필터의 401 과 컨트롤러의 401 을 구분해야 함 → 본문/헤더로 구분하거나
  //     WWW-Authenticate 부재 등으로 판별. 판별 불가 시 T2 베이스에 판별 헬퍼 추가
  // /slack/install/callback (GET)
  ```
- 실패 메시지(예상). 401/403 (현재 부채 상태 — **이 RED가 부채의 존재 증명**)

**GREEN**.
- 파일. `SecurityConfig.kt`
- **CSRF 블록**(`:133-146`)에 5경로 추가 — POST 4종만(GET은 CSRF 무관하나 일관성 위해 검토)
- **authorize 블록**(`:151-184`)에 메서드 고정 등록
  ```kotlin
  auth.requestMatchers(HttpMethod.POST, AUTOMATION_WEBHOOK_PATH).permitAll()      // "/api/v1/automation/webhooks/*"
  auth.requestMatchers(HttpMethod.POST, SLACK_EVENTS_PATH, SLACK_COMMANDS_PATH, SLACK_INTERACTIONS_PATH).permitAll()
  auth.requestMatchers(HttpMethod.GET, SLACK_INSTALL_CALLBACK_PATH).permitAll()
  ```
- **★ 반드시 `/api/**` authenticated(`:185`)보다 위에** — 순서 의존
- **★ `/**` 금지**. 테스트 원본(`AutomationTestSecurityConfig.kt:49`)은 `/api/v1/automation/webhooks/**` **하위 와일드카드**지만 중앙엔 **단일 세그먼트 `/*`** 로 좁힌다(notification 선례 `:175-180` 원칙)
- **★ `/slack/install` 은 열지 않는다** (admin 이중가드 유지)

**REFACTOR**.
- companion object에 경로 상수 5개 + KDoc — **선례 형식 준수**(`PUBLIC_DASHBOARDS_PATH:248` KDoc 형태)
- KDoc에 **"DEVELOPMENT.md §1.1 #4 정식 예외(ADR 2026-07-15-inbound-webhook-permitall-central · 게이트1 승인)"** 명시
- **★ 기존 KDoc 2곳이 이 예외를 `§1.4`로 잘못 참조** 중(§1.4 = 외부 의존성, 익명 경로는 §1.1 #4). **본 태스크는 신규 KDoc만 정확히 쓰고 기존은 건드리지 않는다**(surgical) — 불일치는 plan §리스크에 등재, Maxi 판단

**검증**. `./gradlew :modules:app:test --tests InboundWebhookPermitAllTest` + `:modules:identity-access:test` 회귀 0

---

### Task 4. 범위 누출 음성 가드 + CSRF 이중등록 가드

**메타**.
- agent: `security-engineer`
- files: [`backend/modules/app/src/test/kotlin/com/bts/app/InboundWebhookPermitAllTest.kt`]
- depends-on: [3]   # T3와 같은 테스트 파일 — files 교집합으로 자동 직렬

**RED → GREEN**. (T3 GREEN이 이미 존재하므로 이 태스크는 **가드 추가**가 본체 — 위반을 일부러 넣어 fail 확인 후 되돌리는 방식으로 vacuous PASS 차단 [[archunit-vacuous-rule-silent-pass]])

- **EC-A1**. `POST /slack/install` 익명 → **401 유지** (범위 누출 0)
- **EC-A2**. `POST /api/v1/automation/webhooks/a/b` (2세그먼트) → 401 (`/*` 단일 세그먼트 미매칭)
- **EC-A3**. `GET /api/v1/automation/webhooks/xxx` → 401 (메서드 고정)
- **EC-A4**. **permitAll ↔ CSRF ignore 이중등록 가드** — POST 경로가 403이 아님을 단언. ★ 두 등록이 **다른 블록**이라 한쪽만 하면 조용히 403(FR-MF-01 실제 BLOCKER 이력, KDoc `:86,139,228`)
- **FR-A6**. 회귀 가드 — 향후 경로 추가 시 양쪽 등록을 강제하는 형태로 작성

**REFACTOR**. KDoc — 각 가드가 **어떤 사고를 막는지** 1줄씩 (왜 이 룰이 있는지 컨텍스트, learnings #3 §예방 4)

**검증**. `./gradlew :modules:app:test --tests InboundWebhookPermitAllTest` — **가드 4건이 실제로 fail을 잡는지 일부러 위반 넣어 확인**

---

### Task 5. `.env.prod.example` 암호화 키 3종 선언

**메타**.
- agent: `security-engineer`
- files: [`infra/prod/.env.prod.example`]
- depends-on: []

**내용** (TDD 비대상 — 배포 매니페스트).
- `BTS_SLACK_ENCRYPTION_KEY` / `_SALT` · `BTS_MFA_ENCRYPTION_KEY` / `_SALT` · `BTS_OIDC_ENCRYPTION_KEY` / `_SALT` **전부 누락 상태** → 추가
- **salt는 hex** (`SecretEncryptor` 계약) — 생성법 주석 병기 (`openssl rand -hex 32`)
- automation 키(`BTS_AUTOMATION_ENCRYPTION_KEY`)는 **PR-C에서** (빈 신설과 함께)
- **왜 지금** (DEC-14). [[use-time-validated-env-passes-boot-fails-on-use]]의 **실사고 재발** — health는 통과하고 기능 첫 호출에서 500. PR-A 주제("인바운드가 prod에서 실제로 도는가")와 정합하고 문서 몇 줄이라 비용 ≈ 0. **surgical changes 예외**(Maxi 확정)
- ★ **미설정 시 조용히 fail-closed** — `@Value` 기본값 `""` + 사용 시점 `check(configured)`. `BTS_AUTH_ISSUER_URI` 처럼 fail-fast placeholder가 **아니다**

**검증**. `.env.prod.example`의 키 개수 + 기존 `bts.slack-encryption` 등 프로퍼티명과 relaxed binding 정합 육안 확인

---

## Plan 메타

- **task 수**. 5
- **dispatch**. **전 태스크 직렬** (병렬 없음)
  - 사유 ①. [[parallel-dispatch-precommit-hook-race]]가 **4회 재발**했고 FR-AT-06에서 *"files 교집합 0이어도 index 공유로 발생"* 확인 → 5개 소규모 태스크에 병렬 이득 < 복구 비용
  - 사유 ②. [[bts-plan-wave-gradle-module-compile]] — wave는 Gradle 모듈 컴파일도 직렬화
  - 자연 의존. T3←[2], T4←[3] (T4는 T3와 **files 교집합**으로 자동 직렬)
- **예상 시간**. 약 20분 (T2가 불확실 — prod 부팅 셋업)
- **TDD 강제**. T2·T3·T4 = yes / T1·T5 = 문서·매니페스트 (규칙 #14의 "새 기능" 아님)
- **추가 검증**. `:modules:app:test`(9 BC 조립) · ktlint · detekt · `bash scripts/verify-master-plan.sh`
- **FR 카운트**. **불변 123**. automation BC **6/7 유지** (부채 청산이라 FR 미완료)

## 리스크

| # | 리스크 | 대응 |
|---|---|---|
| R1 | **절대 규칙 #4 정면 위반** — "인증 없는 엔드포인트 추가 금지" | T1 ADR + **게이트1 승인**(선례 FR-DB-03·FR-CA-02와 동일 절차). 미승인 시 진행 불가 |
| R2 | **BC 격리 예외** — automation/slack 사유로 identity-access 수정 | 선례 = PR #13 옵션 C 패턴. **security-engineer 공동 검토 필수**(T1·T3·T4가 이미 그 에이전트) |
| R3 | **★ prod 신규 노출** — 이 PR은 부채 청산인 동시에 **미검증 경로 2개를 prod에 처음 여는 변경**. `AutomationWebhookController:97`의 무검증 `issueKey` enqueue + actor 임의 지정이 살아남 | T1 ADR에 **잔여 위험 명시**. 방어심층은 PR-C(spec §C-7 C-d). Maxi가 게이트1에서 수용 여부 판단 |
| R4 | **T2 불확실** — prod+RANDOM_PORT 부팅 셋업이 비자명(PEM·DataSource·수동 postgres) | 실패 시 Maxi 보고 → 대안(경로 매처 단위 테스트로 축소) 협의. **추측 구현 금지** |
| R5 | 필터의 401 ↔ 컨트롤러의 401 **구분 곤란** | T3 RED에서 판별 방법 확정. 불가 시 T2 베이스에 판별 헬퍼 |
| R6 | 기존 KDoc 2곳의 **`§1.4` 오참조**(정답 §1.1 #4) | **본 PR은 신규만 정확히 표기, 기존 미수정**(surgical). 불일치 잔존 → Maxi 판단 (별건 정리 후보) |
| R7 | 휴면 stash 오염 — `stash@{0}`에 타 세션 `WIP on feature/fr-pr-03-ooo` 존재 | **impl prompt에 `git stash` 금지 명시**([[subagent-git-stash-worktree-shared-collision]]) |
| R8 | rate limit 부재 경로를 3종 여는 것 | T1 ADR 잔여 위험 + spec §A-9 후속 등재 |

## 리뷰 결과

### plan-eng-review + 아웃사이드 보이스 (2026-07-15)

> **스킬 deviation 기록**. gstack `plan-eng-review`는 시작 전 텔레메트리 동의·`CLAUDE.md` 라우팅 규칙 주입·cross-project learnings 설정 등 **Maxi가 요청하지 않은 부수 효과**와, 이슈마다 개별 AskUserQuestion + TODOS 등록 + `## GSTACK REVIEW REPORT` 삽입을 요구한다. BTS 워크플로우는 (a) 결과를 이 `## 리뷰 결과` 섹션에 쓰도록 정하고 (b) 바로 다음이 **게이트1**이라 결정을 한 번에 받는다. 사용자 지침 > 스킬이므로 **리뷰 본체 + 아웃사이드 보이스만 수행**하고 부수 효과·중복 게이트는 생략.

**BLOCKER 2 / CONCERN 5 / NIT 5.** 아웃사이드 보이스가 실제 코드를 읽고 확신도 표기 + 근거 인용으로 판정.

| # | 확신도 | 발견 | 처리 |
|---|---|---|---|
| **B-1** | **9** | **PR-A의 헤드라인 주장이 FR-SL에 대해 거짓.** `.env.prod.example`에 **`BTS_SLACK_SIGNING_SECRET`도 없다**(`grep -rn "SLACK" infra/` → `nginx.conf:40` 한 줄뿐). `SlackSignatureVerifier.kt:78-80`이 `if (signingSecret.isBlank()) return false` fail-closed → permitAll만 열면 **"필터의 401" → "컨트롤러의 401"** 로 바뀔 뿐 기능 0. slack **암호화** 키(봇 토큰)는 서명 통과 **후** 하류라 순서가 뒤집힘. ★ B4를 잡았다고 자평한 검토가 같은 결함을 한 층 위에서 놓침 | **T5에 signing secret + client id/secret/redirect-uri 추가**. spec §A-4 표 4행 → 확장 |
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

## 결정 사항 — 3차 (← 게이트1 직전 Maxi 확정)
