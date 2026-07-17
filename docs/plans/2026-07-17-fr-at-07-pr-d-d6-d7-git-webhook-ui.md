# FR-AT-07 PR-D D6/D7 — Git Webhook 등록 UI + targetBranch 입력 + E2E

> slug: fr-at-07-pr-d-d6-d7-git-webhook-ui
> type: ui
> agent: frontend-engineer
> primary BC: automation (9번째 모듈 `com.bts.automation`)
> 생성: 2026-07-17
> 선행: PR-A(#274·#275) → PR-B(#276) → PR-C(#278) → **PR-D(이 PR, 마지막)**

## Brief

**사용자 원문 (Maxi).** "fr-at-07 끝까지 마무리 해줘!!"
→ FR-AT-07 PR-D. D6/D7 UI + FR-AT-07 완료마킹으로 automation BC 7/7 완결.
→ Maxi 지시. "프론트에서 `targetBranch` 입력 UI와 omit 로직을 **함께** 도입할 것 (둘 중 하나만 넣으면 PR-C에서 고친 유실 버그 재발)."

**classify 결과 (수동 정정).** `type=ui` / `agent=frontend-engineer`
- classify-task.ts 원출력은 `type=qa` / `qa-engineer` — **오분류**. 제목의 "E2E" 키워드가 지배함.
- qa-engineer는 정의상 구현코드(`apps/web/src/`) 수정 금지 → 이대로면 UI를 못 만듦.
- **선례 실측** — D6/D7 UI+E2E 7건 중 **6건이 `ui`/frontend-engineer** (#260·#265·#269·#271·#273·#267), 1건만 `auth`/security-engineer (FR-MF-05, 인증 UI라서).
- 토큰 1회 표시 UI는 **security-engineer 코드리뷰 대상** (PR-C가 평문 토큰 누출 2통로를 겪음).

---

## ★ 착수 전 실측 정찰 결과 (2026-07-17, 6렌즈 × 적대적 검증 42 에이전트)

> **이 절은 재도출 금지.** 전부 file:line 인용으로 실측됨. 오케스트레이터 전제 23건이 반증됐고 적대적 검증에서 14건이 PARTIAL로 정정됨.

### 반증된 전제 (중요도순)

1. **"PR-C = 인바운드 수신만"은 틀렸다.** PR-C(Task 11)가 **관리 CRUD API도 함께** 실었다.
   `GitWebhookRegistrationController.kt:38-41` verbatim.
   - `POST   /api/v1/projects/{projectKey}/automation/git-webhooks` — 등록(201, **원문 토큰 1회 동봉**)
   - `GET    /api/v1/projects/{projectKey}/automation/git-webhooks` — 목록(**token·secret 미포함**)
   - `DELETE /api/v1/projects/{projectKey}/automation/git-webhooks/{id}` — 소프트 삭제(204)
   → **PR-D는 순수 프론트**(automation D6/D7 선례 #260·#265·#269·#271·#273 전부 "백엔드 변경 0"과 동형).

2. **재발급·수정 엔드포인트는 0건.** `PATCH`/`PUT` 0개. `regenerate|rotate|재발급|reissue` grep → automation main 히트는
   `RuleConflictAnalyzer.rotateToMinimum`(사이클 정규화, 무관)뿐. 토큰은 **SHA-256 해시 저장, 복원 불가**
   (`V307__git_webhooks.sql:54` COMMENT `'인바운드 URL 토큰 SHA-256 해시(평문 미저장) — 발급 시 1회 노출'`).
   → **UI에 "재발급" 버튼 설계 불가.** 회전 = **삭제 후 재등록**(새 토큰 = 새 URL = provider 재설정 필요) 뿐.

3. **UI 덩어리가 2개다.** `targetBranch`는 `git_webhooks`가 **아니라** `automation_rules.trigger_config` JSONB 소속
   (V307에 `target_branch` 컬럼 없음, 마이그레이션 전체 grep 0건). → **AutomationRuleFormDialog**에 들어감.
   D6 웹훅 등록 화면과 **다른 화면**.

4. **Maxi 지시는 절반만 정확.** "둘 중 하나만 넣으면 유실 재발" —
   - `omit`만 → **유실 재현** (맞음)
   - `input`만 → 유실이 아니라 **거울상 버그**: 브랜치 한정 **해제 불가** / 빈 입력 시 **400**
   → 결론(둘 다 함께)은 유지. 단 **테스트가 2종 필요** — "유실 방지" + "해제 가능".

5. **PR_MERGED 트리거 UI는 이미 배포됨.** PR-C(#278)가 프론트 5파일 변경
   (`git show --stat 626c7695f -- apps/web`): automation-rules.types.ts(+14) / .test.ts(+30) /
   AutomationRuleFormDialog.tsx(5) / AutomationRuleList.tsx(3) / RuleExecutionTraceRow.tsx(1).
   → PR-D가 다시 만들 필요 **없음**. 단 **MSW 픽스처엔 PR_MERGED가 0건**(D7에서 메울 갭).

6. **E2E는 CI에서 안 돈다.** `.github/workflows/` 전체가 `frontend-ci.yml` 1개, 잡은 lint/typecheck/test 3개,
   playwright 문자열 **0건**. → D7은 머지 게이트가 아니라 **로컬 규율**. 보고서에 결과 첨부 필수.

7. **사이드바가 존재하지 않는다.** `components/layout/` 디렉토리 없음. 전역 nav는 `Header.tsx`의
   `메인 메뉴`(:91)·`관리 메뉴`(:107) 2개뿐이고 automation은 어디에도 없음.
   프로젝트 설정 라우트 **11개 전부** 링크 0건(URL 직접입력 전용) — automation만의 예외가 아니라 status quo.
   → **nav 작업은 PR-D 범위 밖** (FR-UX-06 사이드바 개편의 관심사).

### PR-D 프론트가 반드시 처리해야 할 백엔드 계약 (실측)

| # | 계약 | 근거 | 프론트 책임 |
|---|---|---|---|
| C1 | `webhookUrl`은 **origin 없는 절대경로** (`/api/v1/webhooks/git/<token>`) | `GitWebhookDtos.kt:66-69` KDoc이 "호출하는 화면이 자신의 origin을 앞에 붙여 완전한 URL을 만든다"고 **명시 위임** | UI가 origin prepend 안 하면 GitHub에 붙여넣을 수 없는 반쪽 URL |
| C2 | `secret`은 **사용자 공급** (서버가 생성 안 함). 16~4096자·비공백 | `GitWebhookDtos.kt:30-33` `CreateGitWebhookRequest(provider, secret)` / 검증은 `GitWebhookRegistrationService.kt:184-193` | secret 입력 필드 + 길이 안내 + **클라 검증 직접 구현** |
| C3 | automation 모듈에 **Bean Validation provider 자체가 없음** → `@field:NotBlank`는 런타임 무동작 | `GitWebhookDtos.kt:16-19` "붙이면 '검증이 있다'고 착각하게 만드는 **가짜 가드**" | 서버 400과 별개로 클라 검증 필수 |
| C4 | 목록 DTO에 **name/label 필드 없음** | `GitWebhookSummaryResponse` | 목록 식별은 provider + createdAt 로 |
| C5 | 원문 토큰은 **201 응답에서 단 1회**. 이후 어떤 조회로도 못 봄 | `V307:54` + `mintToken()` SecureRandom 256bit base64url | 1회 표시 모달 + "다시 못 봅니다" 경고 + 복사 버튼 |
| C6 | 권한 어노테이션 0건 — 가드는 **3층** (SecurityConfig `/api/**` authenticated + 서비스 resolver + permitAll 2경로 컨트롤러 자체검증) | `GitWebhookRegistrationService.kt:172` `hasManageAutomation` | 403 처리 |

### targetBranch 계약 (실측 — Maxi 지시보다 정밀)

`apps/web/src/api/automation-rules.types.ts:222-228` KDoc verbatim:
> `## ★ PR_MERGED의 targetBranch는 managed key가 아니다 (FR-AT-07 PR-C 리뷰)`
> managed key로 제거하려면 폼이 그 값을 **다시 채워 넣어야** 성립하는데, `targetBranch` 입력 UI는
> PR-D 몫이라 호출부([AutomationRuleFormDialog])는 `{ cron, fields }`만 넘긴다. 그 상태에서 제거만 하면
> 폼에서 이름만 고쳐 저장해도 `targetBranch`가 유실돼, **브랜치 한정 룰이 전 브랜치 발화로 조용히 승격**된다.
> 따라서 입력 UI가 생기는 PR-D에서 omit 로직과 `targetBranch` 인자를 **함께** 도입한다.

**backend 수용 형태 (TriggerConfig.kt:102-107 실측 — 적대적 검증이 인용 오류 1건 정정)**
- 키 **부재**(omit) → 통과, **전 브랜치 발화**
- `{"targetBranch": null}` → **통과**, 전 브랜치 발화 ← `:103 if (targetBranchNode.isNull) return`
- `{"targetBranch": ""}` / 공백 → **400**
- 비문자열(숫자·배열·객체) → **400**
→ **omit은 백엔드 제약이 아니라 프론트 선택지.** "omit 또는 명시적 null 중 하나"면 되고 **빈 문자열만 금지**.

**PR-D가 원자적으로 함께 해야 할 3가지**
1. `serializeTriggerConfig` 시그니처에 `targetBranch` 인자 추가
2. `omitManagedKeys(base, ['targetBranch'])` 적용
3. `AutomationRuleFormDialog` 호출부(:291 현재 `{ cron, fields }`만 전달)가 **실제로 targetBranch 전달**

**★ 기존 테스트 4개가 의도적으로 깨져야 함** — `automation-rules.types.test.ts:349-402`이 현재
`serializeTriggerConfig('PR_MERGED')`의 `'{}'`/base 보존을 단언. **빨개지는 건 회귀가 아니라 계약 전환**이며,
**갱신 없이 초록이면 위 ③이 누락된 것**이다(= 판별자).

**복제 대상은 DatePatch 3-state가 아님** — 기전이 다름(DatePatch는 PATCH 본문 JsonNullable, triggerConfig는
문자열 통째 교체라 '유지' 상태 자체가 없음). 복제 대상은 **같은 함수의 ISSUE_UPDATED `fields` 분기(:244-249)**.

### 'webhook' 네임스페이스 충돌 (최소 5개를 가리킴 — 오인 주의)

| # | 무엇 | BC | 경로 |
|---|---|---|---|
| ① | 아웃바운드 Webhook 구독/발송이력 + admin UI | **search-export-import** (notification 아님) | `/api/v1/webhooks` |
| ② | 아웃바운드 **디스패처** (REST 미노출) | notification | — |
| ③ | automation **WEBHOOK 트리거** 토큰 | automation | `/api/v1/automation/webhooks/{token}` |
| ④ | PR-C Git **인바운드 수신** | automation | `/api/v1/webhooks/git/{token}` |
| ⑤ | PR-C Git **등록 API** ← PR-D가 소비 | automation | `/api/v1/projects/{projectKey}/automation/git-webhooks` |

**★ ①과 ④가 `/api/v1/webhooks` 접두사를 공유하는데 서로 다른 BC다.** 프론트 파일명·핸들러 재사용 금지.
기존 선점 — `webhook-handlers.ts`·`webhook.spec.ts`·`admin.webhooks*`는 전부 ①(search) 소속.
`WebhookTokenModal.tsx`는 ③ 소속.

### 완료마킹 요건 (선례 #273·#267 실측 + Maxi 확정)

**Maxi D1 확정 = 옵션 C** — 선례 답습 + 전사갭 수정 + 후속 등재.

- **PR 내부**. `docs/plan/product/automation.md` **1개** (선례 #273·#267이 이 파일만 고침. fr-index / plan/README /
  CLAUDE.md / docs/sdd 는 **0건** — FR 총수 128 불변이라 미해당) + 이 plan/spec 산출물
  - D6/D7 체크박스 `[ ]`→`[x]` (:127-128)
  - `> **D6/D7 완료 (2026-07-17, PR #N)**. ...` 블록 append (#273 형태)
  - **§NFR 표 전사갭 수정** — 「규칙 충돌 정적 분석」 `___` → `0.876s` (#268에서 이미 측정, `automation.md:84` 본문에 기록됨)
  - **후속 등재** — 남은 NFR 4종(트리거→액션 지연·replay·YAML import·권한 차단율) + 죽은 BC 완료 게이트
- **PR 외부(머지 후 별도 커밋)**. `docs/progress.html` — `node scripts/build-dashboard.mjs` → `[chore] dashboard regen [skip ci]`
- **BC 완료 게이트(automation.md:207-213) 5조건은 미집행 유지** — slack #267 동형.
  실측 근거: **9개 BC 중 이 게이트 통과한 BC는 0개**. slack 6/6 "완결" 선언에도 게이트 6항목 전부 미체크.
  README §1 진척 컬럼도 9 BC 전부 ☐. → "BC 7/7" = **FR 체크박스 7개 [x]** 의미로 읽음(선례 정합).
- `verify-master-plan.sh`는 완료마킹에 **사실상 무감각** (FR ID 집합만 봄, 체크박스 [x]/[ ] 미판독).
  유일 접점은 마커 형식 룰(`- [X]` 대문자 금지, 종료 2).

### 환경 함정 (이 세션 실측)

- **★ main node_modules 손상 발견·해소(2026-07-17)** — #269 worktree가 main `virtualStoreDir`를 유령 경로로
  박제해 3일·8머지 내내 `pnpm exec` 전멸. `CI=true pnpm install --frozen-lockfile --config.confirmModulesPurge=false`
  1회로 해소(25초, reused 795, lockfile 불변). **post-merge 훅 6회 연속 고장의 root cause였음.**
- **worktree에서 `pnpm install` 절대 금지** — main `.modules.yaml`을 재오염시킴. node_modules는 main에서 심볼릭 링크.
- **검증은 `node_modules/.bin/*` 직접 호출** — `pnpm exec`/`pnpm --filter`는 deps 검사를 거쳐 재오염 위험.
  정본: `node_modules/.bin/vitest run` / `node_modules/.bin/tsc -p tsconfig.app.json --noEmit` /
  `node_modules/.bin/eslint src` / `node_modules/.bin/playwright test`
- **파이프 금지** — `cmd | head; echo $?`는 head의 0을 뱉음. `> /tmp/x 2>&1; echo "EXIT=$?"`.
- **`rm`/`mv` Bash deny** (`.claude/settings.json` 6종) → 임시파일 정리는 `git clean -f <path>`.
- **E2E 전 5173 orphan vite 확인** — `lsof -ti tcp:5173`.
- **MSW 등록 구조** — 전역 `setupServer`는 `src/test/handlers.ts`(refresh 1개)만. `src/mocks/handlers.ts`(spread 69그룹)는
  `setupWorker`(dev/E2E) + **로컬 setupServer를 띄우는 vitest들**이 소비. gitWebhookHandlers를 `mocks/handlers.ts`에
  등록하면 `handlers.test.ts`·`handlers.integration.test.ts`(집합배열 회귀 테스트)에도 **동시 편입**됨.

---

## 도메인 정리

**BC.** `automation` (9번째 모듈 `com.bts.automation`, JdbcTemplate, V300~V310)

**영향 엔티티 (전부 기존 — 신규 0).**
| 엔티티 | 소유 | PR-D 관계 |
|---|---|---|
| `GitWebhook` | automation (PR-C 도입, `V307`) | D6 화면이 등록/목록/삭제 |
| `GitProvider` | automation (PR-C 도입) | 등록 폼의 provider 선택 (`GITHUB`/`GITLAB` — `V307` CHECK) |
| `TriggerConfig` | automation (기존) | `targetBranch` 키가 사는 곳 (`automation_rules.trigger_config` JSONB) |
| `AutomationRule` | automation (기존) | `AutomationRuleFormDialog`가 편집 |

**신규 엔티티 / 관계.** 없음. **순수 프론트** — PR-C가 만든 REST 3매핑을 소비할 뿐 도메인 모델 무변경.

**기존 결정 충돌.** 없음. PR-C의 결정을 그대로 따름.

**관련 ADR.**
- `docs/decisions/2026-07-17-git-webhook-inbound-permitall.md` (PR-C, 20KB) — 이 PR의 직계 선행
- `docs/decisions/2026-07-16-fr-at-07-pr-b-fix-version-port.md` (PR-B)
- automation ADR 7건 전체가 배경

**신규 ADR.** 불요 (신규 결정 없음 — 순수 프론트, 선례 답습). Maxi D1 확정(옵션 C)은 plan에 기록.

### ★ grill-with-docs 대체 사유 (명시)

`/bts-domain` §Step 2는 `grill-with-docs` 호출을 지시하나, 이 PR은 **신규 엔티티·관계·결정이 0건**(PR-C가 도메인을
확정했고 PR-D는 그 REST를 소비만 함)이라 grill 대상이 없다. 대신 그 단계의 **목적**("새 용어/엔티티/관계가 필요한지,
glossary·domain 노트와 일치하는지 확인")을 **인터뷰 대신 실측**으로 수행했고, 그 결과 **실제 drift 2건을 발견**했다(아래).

### ★ 발견 1 — `Maxi_wiki/BTS/domain/automation.md` 가 3건 틀렸다 (Maxi 확인 필요)

노트 최종수정 **2026-05-19** — automation BC 전 작업(#254~#278) 내내 미갱신. BC 분할 **이전의 구상**에 멈춰 있다.

| 노트 주장 | 실측 반증 |
|---|---|
| "AQL 파싱 → PostgreSQL 쿼리 변환" + "ANTLR 4로 AQL 파서" | AQL 파일 = **search-export-import 62** · shared-kernel 12 · **automation 0**. ANTLR 의존성 **0건**(실제는 손수 파서) |
| "Export/Import (CSV, JSON, Jira XML)" | **search-export-import** BC 소유 (`.../export`, `.../import`) |
| 핵심 엔티티 = `AutomationRule` 하나 | 실제 domain/ 13개 — `GitWebhook`·`GitProvider`·`RuleConflict`·`ConflictType`·`ConflictSeverity`·`TriggerConfig`·`ActionConfig` 등 누락 |

BC 노트는 **수동 영역**(`/bts-domain` §Step 3 — "자동 갱신 안 함 → Maxi에게 확인")이라 이 PR에서 임의 수정하지 않음.
→ **게이트 1 안건.**

### ★ 발견 2 — glossary 에 '웹훅' 용어가 0건인데 코드에선 5가지를 가리킨다 (Maxi 확인 필요)

`glossary.md` 114줄에 **웹훅 항목 없음**(`트리거`·`액션`만 있음). 그런데 실측상 'webhook'이 **최소 5개**를 가리킨다.

| # | 무엇 | BC | 경로 |
|---|---|---|---|
| ① | 아웃바운드 Webhook 구독/발송이력 + admin UI | **search-export-import** | `/api/v1/webhooks` |
| ② | 아웃바운드 **디스패처** (REST 미노출) | notification | — |
| ③ | automation **WEBHOOK 트리거** 토큰 | automation | `/api/v1/automation/webhooks/{token}` |
| ④ | Git **인바운드 수신** | automation | `/api/v1/webhooks/git/{token}` |
| ⑤ | Git **등록 API** ← PR-D가 소비 | automation | `/api/v1/projects/{key}/automation/git-webhooks` |

**①과 ④가 `/api/v1/webhooks` 접두사를 공유하는데 서로 다른 BC다.** 이건 DDD 유비쿼터스 언어의 교과서적 실패 —
같은 낱말이 5개 개념을 가리키고 glossary가 침묵. 프론트에서도 이미 `webhook-handlers.ts`·`webhook.spec.ts`·
`admin.webhooks*`(전부 ① 소속) / `WebhookTokenModal.tsx`(③ 소속)로 네임스페이스가 선점돼 있어, PR-D가
`gitWebhook*` 접두사를 **엄격히** 지키지 않으면 오인·충돌한다.

**용어 후보 (Maxi 승인 시 glossary 등재).**
- **인바운드 웹훅** — 외부(GitHub/GitLab)가 BTS를 호출. 인증은 서명 검증. 예 ③④
- **아웃바운드 웹훅** — BTS가 외부를 호출. 예 ①②
- **Git 웹훅** — ④⑤의 짝. 프로젝트 단위로 등록(`git_webhooks`), 토큰은 URL 경로 세그먼트, secret은 HMAC 서명용
- **웹훅 시크릿** — provider가 HMAC 서명에 쓰는 사용자 공급 공유비밀. BTS는 AES-256-GCM 암호화 저장(복호 조회 API 없음)
- **웹훅 토큰** — 인바운드 URL의 식별자. SHA-256 해시로만 저장 → **발급 시 1회 노출, 이후 복원 불가**

→ **게이트 1 안건.** 이 PR에서 glossary를 고칠지, 별도 문서 PR로 뺄지 Maxi 결정.

## 스펙

전체 스펙. [docs/specs/2026-07-17-fr-at-07-pr-d-d6-d7-git-webhook-ui.md](../specs/2026-07-17-fr-at-07-pr-d-d6-d7-git-webhook-ui.md) — **471줄, 3차 개정, 적대적 검증 통과**

**office-hours 대체.** `/bts-spec` §A-3 는 `office-hours` 호출을 지시하나 **스킵**했다 — 메모리
[[bts-spec-office-hours-mismatch]] 에 근거가 있고, **2026-05-29 Maxi 가 이미 "직접 기술 스펙 작성"으로 결정**했다.
office-hours 는 YC 아이디어 검증 도구라 "이미 D1~D7 로 정의된 FR"엔 프레임이 안 맞는다. 선례 spec 형식으로 직접 작성.
`design-consultation` 도 스킵(DESIGN.md 실재). `design-shotgun` 도 스킵 — 토큰 1회 표시 모달의 선례
(`WebhookTokenModal`)가 이미 있어 변형 4종을 새로 뽑는 건 확립된 패턴에서 이탈한다.

### 핵심 3줄 요약

- **D6.** `/projects/$key/settings/automation` 에 `GitWebhookSection` 을 `AutomationRuleList` 의 **형제 섹션**으로 추가 —
  등록(provider+secret, Dialog) → **원문 토큰 1회 표시**(origin prepend한 완전 URL·복사·"다시 못 봅니다") → 목록 → 삭제.
  **재발급 버튼 없음**(백엔드 엔드포인트 0건, 토큰 SHA-256 해시라 복원 불가) — 회전 = 삭제 후 재등록.
- **별건.** `AutomationRuleFormDialog` 에 `targetBranch` 입력 UI + omit 로직을 **원자 4종**으로 함께 도입.
- **D7.** E2E + FR-AT-07 완료마킹(automation BC 7/7).

### 스펙이 봉인한 것 (적대적 검증 3라운드)

| # | 항목 | 왜 load-bearing 인가 |
|---|---|---|
| **BLOCKER-0** | **`secret` 에 `trim()` 절대 금지** | 백엔드 KDoc(`GitWebhookRegistrationService.kt:180-181`)이 명시 금지 — HMAC 이 바이트열 원본을 쓴다. trim 하면 등록 201 성공·목록 정상인데 **모든 인바운드 서명 영구 실패**, MSW 미검증·E2E 실서명 미태움이라 **prod 에서만** 드러나고 secret 이 응답에 없어 **진단·복구 불가**. **1차 초안 자신이 오염원**이었다(targetBranch 에 trim 5회, secret 엔 침묵) |
| **B1** | FR1 h2 중복 회귀 | 룰 h2 가 이미 있는데 "도입"을 지시 → Playwright substring 매칭 → **strict mode 위반 → E2E 11단언 즉사** |
| **B2** | MSW 로컬 `setupServer` 2곳 | 그 2파일은 전역 handlers 를 안 쓰고 `onUnhandledRequest:'error'` → GET unhandled → **통째로 red** |
| **거짓사실** | "추가 API 호출 0" | `staleTime` 미설정(0)+`refetchOnMount` → **배경 refetch 1회 나감**. 정직하게 정정 |
| **원자 4종** | `parseTriggerConfig` 포함 | parse 빠지면 나머지 3종이 사고를 **막는 게 아니라 새로 만든다** — 현상유지보다 나쁨 |

### Maxi 확정 (게이트 1 이전)

| ID | 결정 | 근거 |
|---|---|---|
| **D1** | BC 게이트 = **선례 답습 + 전사갭 1건만 수정** (옵션 C) | 9개 BC 중 이 게이트 통과한 BC **0개**. slack 6/6 도 미집행 |
| **D2** | 배치 = **형제 섹션** (신규 라우트 0) | D6 정본은 "페이지"라 하나 선례 3건 전부 in-page + "라우터 변경 0"이 성과. 사이드바 부재라 신규 페이지는 **도달 경로 0** |
| **D3** | 중복 등록 = **프론트 경고 + 백엔드 가드 후속** | 순수 프론트 원칙 유지하며 최악(살아있는 연동 오삭제, 복구 불가) 차단 |
| **D4** | PR_MERGED 무음실패 = **경고 표시** | 웹훅 0건이면 룰이 영원히 발화 안 하는데 이력도 비어 원인 도달 불가. 두 요소를 한 화면에 모으는 유일한 PR |

## Brainstorming Check

✅ **통과 (3회 iteration — /bts-spec §B-1 상한 내)**

- **1차** — 초안 298줄 → 적대적 3렌즈(contract-fidelity / completeness / security-ux) → **BLOCKER 4 · CONCERN 5 · SUGGESTION 4**.
  최중요(secret trim)는 **두 렌즈가 독립 발견**.
- **2차** — 434줄 → 재검증 3렌즈 → 10항목 SEALED, 그러나 **새 BLOCKER 2 + 거짓사실 1**(전부 **오케스트레이터 지시가 원인**).
- **3차** — 471줄 → 봉인 확인 2렌즈 → **회귀 0 · 범위 침범 0 · READY**. 잔여 4건은 메인 루프에서 직접 수정.

### ★ 이 단계의 단일 교훈 — 오케스트레이터 지시가 8번 틀렸고 전부 에이전트가 반증했다

| 내 지시 | 실측 |
|---|---|
| "targetBranch 3종 원자적으로" | **4종**. 3종만 하면 버그를 **새로 만듦** |
| "테스트 4개가 초록이면 ③ 누락" | 테스트는 3개, ③ 누락 시 T2 가 빨개짐 → **판별자 자체가 무효** |
| "handlers.ts 에 등록하면 됨" | 그 지시가 **아무 효력 없는** 테스트 2파일 존재 → 통째로 red |
| "추가 API 호출 없이 판정" **요구** | `staleTime` 0 이라 **거짓**. 내 요구를 충실히 따른 결과로 **거짓이 스펙에 박힘** |
| "DESIGN.md 의 amber 토큰" | amber **0건**. DESIGN.md 는 반대를 지시하고 **stale**(PR #11 로그인 폼 범위·다크모드 미지원 선언인데 실제 모달은 `dark:` 사용) |
| "선례 5블록이 123" | **6곳**. **스펙이 나보다 정확했다** |
| "모달 복제 4벌" | **33벌**. "자기 눈에 들어온 4벌"이었다 |
| "PR-C = 인바운드 수신" | 관리 CRUD API 도 함께 실었음 |

**패턴 — 내가 개수를 말할 때마다 틀렸다.** 메모리 [[spec-stated-count-becomes-blindfold]] 가 5연속 재발로 적혀 있는데
그걸 읽고도 6번째를 만들었다. **유일하게 작동한 방어는 에이전트 프롬프트의 "개수를 믿지 말고 전수 열거하라"** 였다.
→ 후속 세션 처방. 지시에 숫자를 쓰지 말고 **"전수 열거하라"만** 쓸 것.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
