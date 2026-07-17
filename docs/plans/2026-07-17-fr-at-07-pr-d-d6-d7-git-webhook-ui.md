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

## Plan

> **11 TDD task / 7 wave.** 스펙(471줄) FR0~22 · EC1~21 · NFR1~6 · 완료기준 ★A~★F 전량 매핑.
> **★ task 11개 = 10 초과이나 Maxi 가 1안(현행 유지, 1 PR)으로 확정** — 분할하지 않는다(§Plan 메타 §5).

### ★ 이 절을 쓰기 전 실측 재대조한 것 (스펙 인용 전수 검증)

스펙이 못박은 file:line 을 **직접 열어 대조**했다. **반증 0건 — 스펙이 실측과 전부 일치한다.**

| 스펙 주장 | 실측 결과 |
|---|---|
| `AutomationRuleFormDialog.tsx` — `:375` `<input` · `:377` `type="text"` · `:379` `automation-rule-cron-input` · `:380` `autoComplete="off"` | **4곳 전부 정확** |
| 같은 파일 `:279` `function buildSharedSavePayload(` · `:291` `serializeTriggerConfig(effectiveTriggerType, { cron, fields }, baseConfigJson)` | **정확** |
| 같은 파일 `:151-154` `ParsedTriggerConfig {cron, fields}` · `:164`/`:171`/`:174` return 3곳 · `:459` initialConfig 폴백 | **정확** |
| `automation-rules.types.test.ts` — `it('PR_MERGED` 3건(`:349`·`:392`·`:401`) · 선행주석 `:386-391` · `:387` 에 `호출부(AutomationRuleFormDialog:291)` | **정확 (4건 아니라 3건)** |
| E2E `getByRole('heading', {name: '자동화 룰'})` **11 단언 / 6 스펙** | **정확** — rules `:131`·`:155`·`:182`·`:211`(4) / conditions `:94`·`:223`(2) / conflict-warning `:85`·`:115`(2) / actions `:102`(1) / yaml-gitops `:86`(1) / execution-history `:114`(1) = **11**. `automation-rules.spec.ts:226` 은 `labels.createTitle`(Dialog 제목)이라 **대상 아님** — 스펙의 제외가 옳다 |
| `automation.md` `grep -n "123"` **6곳** (`:38`·`:86`·`:100`·`:114`·`:116`·`:180`) | **정확 (5곳 아님)** |
| `automation.md:127-128` D6/D7 체크박스 · `:190` 「규칙 충돌 정적 분석」 `___` · `:84` 산문 `성능 스모크 100규칙 0.876s(NFR 1s)` · `:207-213` BC 완료 5조건 | **정확** |
| `CLAUDE.md:10` = `(128 FR, …)` · `README.md:110` = `| automation | … | 7 (AT 7) | (없음) | ☐ |` | **정확 (129 아님)** |
| 로컬 `setupServer` 합류 대상 2곳 — route test `:62`, FormDialog test `:42`(둘 다 `onUnhandledRequest:'error'`) | **정확.** `grep -rn "setupServer" apps/web/src` 전수 재실행 → `AutomationRuleList.test.tsx:31` 도 로컬 서버지만 **형제 구조라 비대상**(스펙 ★F 판단 옳음) |
| `AutomationYamlImportDialog.tsx:142` 에 `select-all` 있음 / `WebhookTokenModal.tsx:89` 에 **없음** | **정확** |
| route `:55-56` 조립 열거 · `:58` `상태 6종` · `:87-92` 실제 6개 · `:133` `<div className="p-8 space-y-6 max-w-2xl">` · `:136-138` h1 설명문 · `:162` 토큰 우선 가드 | **정확** |

**추가 실측 1건 (스펙 미기재, FR1 을 보강함).** route `:135` 의 **h1 텍스트는 `'자동화'`**(`'자동화 룰'` 아님)이다.
Playwright 의 `name` 은 **부분문자열 매칭**이라 `'자동화'` 는 `'자동화 룰'` 을 **포함하지 않으므로** 11 단언에 안 걸린다.
→ FR1 이 h1 을 **건드리지 않고 `<p>` 만 교체**하는 것이 옳다는 근거가 하나 더 생겼다(Task 7).

---

### Task 1. Git 웹훅 Zod 계약 신설 (`automation-git-webhooks.types.ts`)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-git-webhooks.types.ts`, `apps/web/src/api/automation-git-webhooks.types.test.ts`]
- depends-on: []

**RED**.
- 파일: `apps/web/src/api/automation-git-webhooks.types.test.ts`
- 테스트:
  ```ts
  // NFR4 판별자 — origin 없는 절대경로가 통과해야 한다. .url() 을 붙이면 red.
  it('webhookUrl 은 origin 없는 절대경로를 통과시킨다', () => {
    const parsed = createGitWebhookResponseSchema.parse({
      id: '11111111-1111-4111-8111-111111111111',
      provider: 'GITHUB',
      webhookUrl: '/api/v1/webhooks/git/abc123',
      token: 'abc123',
    })
    expect(parsed.webhookUrl).toBe('/api/v1/webhooks/git/abc123')
  })
  // FR3 판별자 — 정확히 2값. 소문자·BITBUCKET 거부(대문자 원문 전송 계약, GitProvider.kt:16-17)
  it('gitProviderSchema 는 GITHUB/GITLAB 만 받는다', () => {
    expect(gitProviderSchema.parse('GITHUB')).toBe('GITHUB')
    expect(gitProviderSchema.parse('GITLAB')).toBe('GITLAB')
    expect(() => gitProviderSchema.parse('github')).toThrow()
    expect(() => gitProviderSchema.parse('BITBUCKET')).toThrow()
  })
  // NFR5 — Instant = ISO 문자열(epoch 배열 아님)
  it('gitWebhookSummarySchema 는 4필드를 파싱한다', () => { /* id·provider·createdAt·createdBy */ })
  ```
- 실패 메시지 (예상). `Failed to resolve import "./automation-git-webhooks.types"` (모듈 부재)

**GREEN**.
- 파일: `apps/web/src/api/automation-git-webhooks.types.ts`
- 최소 구현. L1 한국어 주석 + `gitProviderSchema = z.enum(['GITHUB','GITLAB'])` · `createGitWebhookResponseSchema`(`id` uuid / `provider` / `webhookUrl` **`z.string()` — `.url()` 금지 NFR4** / `token`) · `gitWebhookSummarySchema`(`id` uuid / `provider` / `createdAt` `z.string().datetime()` / `createdBy` uuid) · `createGitWebhookInput = { provider, secret }` 타입. `z.infer` PascalCase 파생 타입 export.
- **없는 필드를 추가하지 않는다**(`GitWebhookDtos.kt:84-88` — `name`·`enabled`·`updatedAt`·`version`·`token`(summary) 전부 금지).

**REFACTOR**.
- KDoc 에 **NFR4 사유**(`.url()` 붙이면 백엔드 절대경로가 못 통과) 를 박아 후속 세션의 "친절한 강화"를 차단.

**검증**. `cd apps/web && node_modules/.bin/vitest run src/api/automation-git-webhooks.types.test.ts`

---

### Task 2. MSW git 웹훅 픽스처 + 핸들러 + **전역 등록**(★F-a)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/git-webhook-fixtures.ts`, `apps/web/src/mocks/git-webhook-handlers.ts`, `apps/web/src/mocks/git-webhook-handlers.test.ts`, `apps/web/src/mocks/handlers.ts`]
- depends-on: [1]

**RED**.
- 파일: `apps/web/src/mocks/git-webhook-handlers.test.ts`
- 테스트: `automation-rule-handlers.test.ts:26-36` 구조 복제 — 자체 `setupServer(...gitWebhookHandlers)` + `resetGitWebhookStore()` + SCENARIO_KEY 전 키 삭제.
  ```ts
  it('POST → 201 이 webhookUrl·token 을 담고, 그 뒤 GET 목록에 1건이 잡힌다', ...)   // EC19 stateful
  it('DELETE → 204 후 GET 목록에서 사라진다', ...)
  it('secret 15자 이하면 400 AUTOMATION_GIT_WEBHOOK_SECRET_INVALID + 서버 고정 detail', ...)
  it('SCENARIO_KEY.FORBIDDEN 플래그 on 이면 GET 이 403 AUTOMATION_ACCESS_DENIED', ...)  // FR12 error 분기 재현용
  ```
- 실패 메시지 (예상). `Failed to resolve import "./git-webhook-handlers"`

**GREEN**.
- 파일: `git-webhook-fixtures.ts`(store 소유 — **§제약 6**: `let` + 재할당식 `resetGitWebhookStore()`, `clear()` 아님. `import.meta.env.MODE !== 'test'` 일 때만 자동 시드) · `git-webhook-handlers.ts`(3 핸들러 + `problemDetail()` 헬퍼 **복제** — `automation-rule-handlers.ts:32-59`. **`message` 필드 절대 금지, `detail` 사용**) · `mocks/handlers.ts` 에 `gitWebhookHandlers` import + spread.
- **명명 = `gitWebhookHandlers`**(FR20) — `webhookHandlers` 는 `webhook-handlers.ts:266` 이 선점(search-export-import BC).
- 시나리오 플래그 `msw:automation-git-webhook:*` + `globalThis.localStorage?.` optional chaining 필수.
- 시드 UUID 는 **RFC4122 v4 형식**([[zod-v4-uuid-fixture-strictness]]).

**REFACTOR**.
- 경로 순서 함정 회피 — `DELETE .../git-webhooks/:id` 가 `GET .../git-webhooks` 를 가리지 않도록 `automation-rule-handlers.ts` 의 배치 관례(구체 경로 먼저)를 따른다.

**검증**. `cd apps/web && node_modules/.bin/vitest run src/mocks/git-webhook-handlers.test.ts src/mocks/handlers.test.ts src/mocks/__tests__/handlers.integration.test.ts`
(뒤 2개는 전역 배열 소비 회귀 — §제약 7-c. 둘 다 핸들러 **개수 단언 0건**이라 통과해야 정상)

**⚠️ 등록 race**. `handlers.ts` 는 공유 파일이다 — 이 task 만 건드린다(files 에 명시). implementer 는 **자기 files 만 `git add`**(`git add -A` 금지) 하고 **`git stash` 금지**.

---

### Task 3. API 함수 + 쿼리/뮤테이션 훅 (`GIT_WEBHOOKS_QUERY_KEY`)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-git-webhooks.ts`, `apps/web/src/api/automation-git-webhooks.test.ts`, `apps/web/src/api/useGitWebhooks.ts`, `apps/web/src/api/useGitWebhooks.test.tsx`]
- depends-on: [1, 2]

**RED**.
- 파일: `apps/web/src/api/automation-git-webhooks.test.ts`(`automation-rules.test.ts:29` 동형 — `setupServer(...gitWebhookHandlers)`) + `apps/web/src/api/useGitWebhooks.test.tsx`(`useAutomationRules.test.tsx:28` 동형)
- 테스트:
  ```ts
  // ★B2 1층 — API 함수가 secret 을 손대지 않는다(BLOCKER-0). trim 이 끼면 red.
  it('createGitWebhook 은 secret 원문을 바이트 그대로 싣는다', async () => {
    let body: unknown
    server.use(http.post('*/git-webhooks', async ({ request }) => { body = await request.json(); return HttpResponse.json(RESP, { status: 201 }) }))
    await createGitWebhook('ATLAS', { provider: 'GITHUB', secret: '  abcdefghijklmnop  ' })
    expect((body as { secret: string }).secret).toBe('  abcdefghijklmnop  ')
  })
  it('createGitWebhook 은 X-XSRF-TOKEN 헤더를 싣는다', ...)          // FR6
  it('deleteGitWebhook 은 204 를 파싱 없이 통과시킨다', ...)          // FR14 (automation-rules.ts:150-156 동형)
  it('403 이면 ApiError 를 던지고 extractAutomationRuleErrorCode 가 AUTOMATION_ACCESS_DENIED 를 뽑는다', ...)
  // FR21 — 함수 헬퍼 형태(useAutomationRules.ts:32-35 동형)
  it('GIT_WEBHOOKS_QUERY_KEY 는 ["automation-git-webhooks", projectKey] 를 낸다', ...)
  ```
- 실패 메시지 (예상). `Failed to resolve import "./automation-git-webhooks"` / `"./useGitWebhooks"`

**GREEN**.
- `automation-git-webhooks.ts` — `apiFetch` + `throwIfNotOk` + 수동 `.parse` 관례(`automation-rules.ts:44-49,100-107`). **`apiPost` 금지.** 타입만 배럴 재수출(`automation-rules.ts:18-26` 동형), **스키마(값)는 재수출 안 함**.
- `useGitWebhooks.ts` — `GIT_WEBHOOKS_QUERY_KEY(projectKey): [string, string]` + `useGitWebhooks` / `useCreateGitWebhook` / `useDeleteGitWebhook`. **onSuccess 는 invalidate 만**(NFR2 — `CreateGitWebhookResponse` 에 `createdAt`·`createdBy` 가 없어 `setQueryData` 는 Zod 계약 위반. `useAutomationRules.ts:52-56` 의 `invalidate…` 공용 헬퍼 형태 복제).
- **`extractAutomationRuleErrorCode` 재사용**(`automation-rules.ts:236`) — BC 공용, 신규 작성 금지.

**REFACTOR**.
- NFR2 사유(두 DTO 필드 집합 비교)를 KDoc 에 박는다 — `setQueryData` 유혹 차단.
- **NFR3** — `version` 필드가 없으므로 409 분기를 만들지 않는다(있으면 dead path).

**검증**. `cd apps/web && node_modules/.bin/vitest run src/api/automation-git-webhooks.test.ts src/api/useGitWebhooks.test.tsx`

---

### Task 4. `GitWebhookUrlModal` — URL 1회 노출 + origin prepend + 복사 폴백

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/GitWebhookUrlModal.tsx`, `apps/web/src/components/automation/GitWebhookUrlModal.test.tsx`]
- depends-on: []

> **왜 deps 가 비었나.** props 만 받는 순수 표시 컴포넌트다(`webhookUrl: string | null`, `onClose`). API·쿼리 의존 0 → **wave 1 후보**. mutation 소유는 Task 6(Section).

**RED**.
- 파일: `apps/web/src/components/automation/GitWebhookUrlModal.test.tsx`
- 테스트:
  ```ts
  // ★C EC8 판별자 — origin prepend 누락 시 red (FR9)
  it('표시 문자열이 window.location.origin 을 포함한 완전 URL 이다', () => {
    render(<GitWebhookUrlModal webhookUrl="/api/v1/webhooks/git/tok" onClose={vi.fn()} />)
    expect(screen.getByText(`${window.location.origin}/api/v1/webhooks/git/tok`)).toBeInTheDocument()
  })
  it('복사 버튼이 완전 URL 을 클립보드에 쓴다', ...)      // FR9 — bare token 아님
  it('clipboard reject 시 실패 문구를 role="alert" 로 낸다', ...)   // FR10-a · EC9
  it('URL <code> 에 select-all 클래스가 있다', ...)                  // ★ FR10-b — 복제 원본(WebhookTokenModal.tsx:89)엔 없다. 승계 금지
  it('webhookUrl 이 null 이면 렌더하지 않는다', ...)
  ```
- **★★ FR8 은 닫기 경로마다 `it` 를 분리한다 — 번들 금지**([[guard-handler-matrix-blindfold]]).
  **선례가 바로 그 함정이다** — `AutomationYamlImportDialog.test.tsx:334` `it('토큰 노출 중 ESC/오버레이/X 모두 2단계 확인을 거친다 (EC7)')` 가
  3경로를 **한 `it` 에 번들**했다. 번들하면 첫 경로에서 `expect` 가 터질 때 나머지 2경로는 **실행조차 안 되고**,
  구현이 1경로만 가로채도 나머지 2경로 무가드를 **테스트가 못 본다**. 개수가 아니라 **행렬**을 열거하라.
  ```ts
  // FR8 · EC6 — 경로별 독립 it. 각 it 는 ① 즉시 안 닫힘(onClose 미호출) ② 확인 프롬프트 등장 둘 다 단언한다.
  it('ESC 는 즉시 닫지 않고 2단계 확인을 띄운다', ...)        // → onEscapeKeyDown 가드
  it('오버레이 클릭은 즉시 닫지 않고 2단계 확인을 띄운다', ...) // → onPointerDownOutside 가드
  it('닫기 버튼(X)은 즉시 닫지 않고 2단계 확인을 띄운다', ...)  // → handleOpenChange 가드 (Close 는 위 둘을 안 거친다)
  it('2단계 확인에서 확인을 누르면 onClose 가 호출된다', ...)   // 수렴 — 양성 대조군
  it('2단계 확인에서 취소를 누르면 onClose 가 호출되지 않고 URL 이 계속 보인다', ...)
  ```
  - **★ 닫기 경로 전수 실측 = 3개다(4 아님).** Radix Dialog 의 닫기 통로를 직접 열거했다 —
    ① `DialogPrimitive.Close` 버튼(`WebhookTokenModal.tsx:105-110`) ② ESC ③ 오버레이 pointer-down.
    **`onOpenChange` 는 4번째 경로가 아니라 ①②③ 이 전부 도달하는 깔때기**다 — 오케스트레이터 지시와
    `AutomationYamlImportDialog.tsx:301` KDoc 이 `X·ESC·오버레이·onOpenChange` 를 **4경로**라 부르는 건
    사용자 경로(3)와 가로채기 지점(3)을 **섞어 센 것**이다. 가로채기 지점도 3개다 —
    `onEscapeKeyDown`(②) · `onPointerDownOutside`(③) · `handleOpenChange`(①. Close 버튼은 앞 둘을 **안 거치므로**
    깔때기에서 따로 잡아야 한다 — `AutomationYamlImportDialog.tsx:390` KDoc 이 `"X 버튼 포함"` 으로 명시).
    `onInteractOutside`/`onFocusOutside` 는 **리포 전수 grep 0건**이고 Root 이 modal 기본값(포커스 트랩)이라 비경로.
    → **3경로 × 3가로채기 = 하나라도 빠지면 그 경로만 1단계 즉시 닫힘 = URL 영구 분실**(FR8 · #273 EC7).
- 실패 메시지 (예상). `Failed to resolve import "./GitWebhookUrlModal"`

**GREEN**.
- `WebhookTokenModal.tsx` **구조 복제**(재사용 금지 — §제약 3, **34번째 복제가 정답**. `components/ui` 에 Dialog 래퍼 부재).
- **복제하되 승계할 검증된 세부 3종** — ① null narrowing 미전파 → `const rawUrl = webhookUrl` 별도 캡처(`:54-55`) ② `catch { setCopyError(labels.copyFailed); setCopied(false) }`(`:62-65`) ③ 문구 `'복사에 실패했습니다. 직접 선택해 복사해 주세요.'` 재사용(`:16`).
- **★ 승계하면 안 되는 결함 3종** (실측 정정 — 스펙 §복제 세부는 **1종만** 열거했고 오케스트레이터는 **2종**이라 했으나 **전수 grep 결과 3종**이다).
  | # | 결함 | 원본 실측 | 대신 따를 원본 |
  |---|---|---|---|
  | ① | `<code>` 에 `select-all` 부재 | `WebhookTokenModal.tsx:89` | `AutomationYamlImportDialog.tsx:142` |
  | ② | **2단계 확인 부재 — 3경로 전부 1단계 즉시 닫힘** | `WebhookTokenModal.tsx:68-70` `function handleOpenChange(open){ if(!open) onClose() }` · `:77` Content 에 `onEscapeKeyDown`·`onPointerDownOutside` **부재** | `AutomationYamlImportDialog.tsx:408,414` + `:390-397` |
  | ③ | **Overlay 에 `data-testid` 부재 → 오버레이 경로를 테스트할 수단이 없다** | `WebhookTokenModal.tsx:75` `<DialogPrimitive.Overlay className=... />` | `AutomationYamlImportDialog.tsx:400-403` `data-testid="automation-yaml-import-overlay"` |
  - **★ ②가 압도적으로 load-bearing 이다.** 스펙 FR8 이 `하나라도 빠지면 URL 영구 분실(#273 EC7 선례)` 이라 못박았는데
    **복제 원본에 그 기전이 통째로 없다.** 구조 복제만 하면 FR8 이 **자동으로 미구현**된다.
  - **③ 없이는 ②의 오버레이 경로가 테스트 불가**다 — jsdom 은 좌표 기반 바깥 클릭을 못 만든다.
    선례가 오버레이에 testid 를 박고 **직접 클릭**해서 우회한다(`AutomationYamlImportDialog.test.tsx:349`
    `await user.click(screen.getByTestId('automation-yaml-import-overlay'))`). → `git-webhook-url-overlay` testid 필수.
- **★ 2단계 확인 이식 방법 (복제 원본 verbatim — `AutomationYamlImportDialog.tsx`).**
  `WebhookTokenModal` 에는 이 코드가 **없으므로** 아래를 옮겨 심는다. 조건 `tokenAtRisk` 는 이 컴포넌트에선
  **`webhookUrl !== null` 자체**다(URL 이 떠 있는 동안이 곧 분실 위험 구간 — in-flight 개념 없음. 단순화가 맞다).
  ```tsx
  // ① Content 의 ESC·오버레이 가로채기 (원본 :408-420 verbatim, 조건만 치환)
  <DialogPrimitive.Content
    data-testid="git-webhook-url-dialog"
    onEscapeKeyDown={(event) => {
      event.preventDefault()          // 원본 :410 — preventDefault 가 Radix 의 기본 닫기를 취소한다
      setCloseConfirming(true)        // 원본 :411
    }}
    onPointerDownOutside={(event) => {
      event.preventDefault()          // 원본 :416
      setCloseConfirming(true)        // 원본 :417
    }}
  >
  // ② 깔때기 가로채기 — Close 버튼(X)은 위 둘을 안 거친다 (원본 :390-397 verbatim)
  /** Root의 onOpenChange — 분실 위험 구간의 닫기 시도(X 버튼 포함)를 가로채 2단계 확인을 요구한다. */
  function handleOpenChangeAttempt(next: boolean): void {
    if (!next) { setCloseConfirming(true); return }
  }
  // ③ 확인 프롬프트 — 원본 :424-429. state 만 세팅하고 UI 를 안 그리면 무음 실패한다(원본 :170-172 가 박제한 교훈)
  {closeConfirming && (
    <CloseConfirmPrompt onConfirm={onClose} onCancel={() => { setCloseConfirming(false) }} />
  )}
  ```
  - **`CloseConfirmPrompt` 는 file-local 로 복제**(원본 `:175-180` 구조. 재사용 import 금지 — §제약 3 동일 사유).
  - **원본 `:170-172` 의 교훈을 그대로 승계한다** — `closeConfirming` state 는 세팅되는데 **프롬프트 UI 를 안 그리면**
    닫기가 그냥 **무음으로 안 먹는다**(원본이 review-fix 로 겪은 사고). 위 ③ 렌더가 없으면 ①②는 **UX 를 망가뜨리기만 한다**.
- **testid 전면 분리**(FR20) — `git-webhook-url-copy-button` / `git-webhook-url-close-button`. `webhook-token-*`(`WebhookTokenModal.tsx:101`·`:107`)와 **같은 라우트 페이지에 동시 마운트**되므로 동일 testid 면 Playwright strict mode 즉사.
- NFR6 — `role="alert"` 는 **경고 영역을 묶어서**(`RuleConflictWarningModal.tsx:95-96` 방식), Content 에 `data-testid` 부여(`:88` 방식).
- **3경로 전부 2단계 확인을 거쳐 단일 `onClose`** 로 수렴한다 — 확인 프롬프트의 `onConfirm` 이 **유일한 `onClose` 호출 지점**이다.
  Task 6 이 그 `onClose` 에 `registerMutation.reset()` 을 건다(FR7).

**REFACTOR**.
- 테스트 clipboard 스텁은 **`userEvent.setup()` 이후**에 적용(`WebhookTokenModal.test.tsx:10-22` 박제된 순서 함정).

**검증**. `cd apps/web && node_modules/.bin/vitest run src/components/automation/GitWebhookUrlModal.test.tsx`

---

### Task 5. `GitWebhookRegisterDialog` — provider/secret 입력 + **BLOCKER-0 봉인**

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/GitWebhookRegisterDialog.tsx`, `apps/web/src/components/automation/GitWebhookRegisterDialog.test.tsx`]
- depends-on: [1]

> **왜 API deps 가 없나.** controlled 표시 컴포넌트다 — props `open`·`onOpenChange`·`onSubmit(input)`·`isPending`·`submitError`·`existingProviders: GitProvider[]`·`listUnavailable: boolean`. mutation 은 Task 6 이 소유(FR2 — Dialog open 상태도 `GitWebhookSection` 소유 → **route 상태 6종 불변**, FR22-a).

> ### ★ provider 입력 primitive **확정 = Radix Select (`@/components/ui/select`) + `vi.mock` 네이티브 shim**
>
> **실측 3건.**
> 1. `components/ui/select.tsx` **실재**(`Select`·`SelectTrigger`·`SelectValue`·`SelectContent`·`SelectItem` 5종 export, `:179`).
> 2. **jsdom 제약은 이 리포에서 실재하고 이미 박제돼 있다** — `grep -rn "hasPointerCapture" apps/web/src` →
>    `PatCreateForm.test.tsx:11` · `ResolutionPickerModal.test.tsx:11` · `admin.notification-policies.test.tsx:35`
>    **3건 전부** `jsdom에서 hasPointerCapture 제약으로 Radix Select 클릭 인터랙션이 불가` 라고 동일 진술.
> 3. **우회 선례도 3건 전부 동일하다** — `vi.mock('@/components/ui/select', ...)` 으로 **네이티브 `<select>` 로 치환**해
>    `userEvent.selectOptions` 를 활성화. 정본 템플릿 = **`PatCreateForm.test.tsx:15-83`**(`:82` 가 5종 전부 반환).
>    가장 가까운 선례다 — 같은 "폼 + aria-label 붙은 Select + 제출" 구조.
>
> **→ BLOCKER-0(secret trim) 판별자는 jsdom 벽에 막히지 않는다 (오케스트레이터 지시 반증).**
> 그 테스트는 **provider 를 고를 필요가 없기 때문**이다 — `provider` 는 **기본값 `'GITHUB'`** 이고(FR3 의 2값 중 첫째,
> `GitProvider.kt:16-17` 대문자 원문), EC2 는 secret 만 입력해 제출한다. 즉 Select 를 **한 번도 건드리지 않는다**.
> Select 를 여는 건 **FR3 옵션 열거 테스트뿐**이고, 그것도 아래 shim 으로 `<select>` 가 되므로 클릭이 아니라
> **옵션 배열 단언**으로 끝난다(`PatCreateForm.test.tsx:116-122` 템플릿 — `Array.from(select.options).map(o => o.textContent)`).
> **radio / native `<select>` 직접 사용은 기각** — 선례 0건이고 폼 전반의 shadcn Select 관례에서 이탈한다.

**RED**.
- 파일: `apps/web/src/components/automation/GitWebhookRegisterDialog.test.tsx`
- 테스트:
  ```ts
  // ★★ BLOCKER-0 1층 판별자 — 폼이 secret 을 손대지 않는다 (EC2)
  it('secret 앞뒤 공백을 그대로 onSubmit 에 넘긴다', async () => {
    const onSubmit = vi.fn()
    render(<GitWebhookRegisterDialog open onSubmit={onSubmit} ... />)
    await user.type(screen.getByLabelText('Secret'), '  abcdefghijklmnop  ')   // delay:null (vitest-usertype-long-string-timeout)
    await user.click(screen.getByTestId('git-webhook-register-submit'))
    expect(onSubmit).toHaveBeenCalledWith({ provider: 'GITHUB', secret: '  abcdefghijklmnop  ' })
  })
  // ★ EC3 — 비대칭 술어. 서버는 blank=trim 기준(:185) / 길이=원문 기준(:188). 클라가 trim().length 로 재면 red.
  it('공백 15자 + 문자 1자(원문 16자)는 클라 검증을 통과해 onSubmit 이 호출된다', ...)
  it('15자 이하는 요청 없이 인라인 에러 — 서버 고정 문구를 미러', ...)   // EC1 · S3
  it('4096자 초과는 원문 길이 기준으로 차단한다', ...)                    // EC4
  // ★ FR5 판별자
  it('secret 입력의 type 이 password 이고 autoComplete 가 off 다', ...)
  it('secret 입력에 <label> 이 연결돼 있다', ...)                          // NFR6
  // FR3 — shim 된 네이티브 <select> 의 옵션 배열을 단언한다 (PatCreateForm.test.tsx:116-122 템플릿).
  // 클릭으로 열지 않는다 — Radix 실물은 jsdom 에서 안 열린다(위 §primitive 확정).
  it('provider Select 는 GITHUB/GITLAB 2옵션뿐이다', () => {
    renderDialog()
    const select = screen.getByLabelText('Provider') as HTMLSelectElement
    expect(Array.from(select.options).map((o) => o.value)).toEqual(['GITHUB', 'GITLAB'])
  })
  it('provider 기본값이 GITHUB 이다', ...)   // ★ EC2(BLOCKER-0)가 Select 를 안 건드리고 제출할 수 있게 하는 전제
  // FR13 — 비차단 경고
  it('existingProviders 에 선택 provider 가 있으면 경고를 렌더하되 등록 버튼을 막지 않는다', ...)
  it('listUnavailable 이면 중복 경고를 내지 않는다', ...)                  // FR13 · EC21 동형 — 못 읽은 것 ≠ 0건
  it('isPending 이면 등록 버튼이 disabled 다', ...)                        // EC5
  ```
- 실패 메시지 (예상). `Failed to resolve import "./GitWebhookRegisterDialog"`

**GREEN**.
- **★★ `secret` 에 `trim()`·`normalize()`·공백제거 전부 금지**(BLOCKER-0 · FR4-a). 전송값 = 사용자 입력 **원문 그대로**. 사유 — provider HMAC 이 secret **바이트열 그대로**를 쓴다(`GitWebhookRegistrationService.kt:180-181` KDoc). trim 하면 **등록 201·목록 정상인데 모든 인바운드 서명 영구 실패**, MSW/E2E 미탐지, 수정 엔드포인트 0건이라 **복구 불가**.
- **검증 술어는 서버 비대칭을 정확히 복제**(FR4-b) — blank 판정 `secret.trim() === ''` / **길이 판정 `secret.length`**(untrimmed). **`secret.trim().length` 금지.**
- **(FR4-c)** 판정의 trim 은 **읽기 전용**이다 — 폼 state 나 요청 본문에 trim 결과를 **write-back 하면 (a) 위반**.
- secret 필드 — `type="password"`(선례 `WebhookForm.tsx:224`, 라벨 `:16` `'서명 Secret'`) + **`autoComplete="off"`**(선례 `AutomationRuleFormDialog.tsx:380`·`:407`·`:568`). autoComplete 없으면 **Chrome 이 BTS 로그인 비밀번호를 autofill** → 그 값이 GitHub 설정에도 평문으로 들어간다.
- 도움말 — `'provider 웹훅 설정에 입력할 값과 동일해야 합니다. 서명 검증에 쓰입니다. 앞뒤 공백도 값의 일부로 저장됩니다.'`
- **provider 필드** — `@/components/ui/select` 의 `Select`/`SelectTrigger`/`SelectValue`/`SelectContent`/`SelectItem`
  (`PatCreateForm.tsx:137-148` 구조 동형). **`SelectTrigger` 에 `id` + `aria-label='Provider'`**(`:138` 동형) —
  shim 이 `aria-label` 을 네이티브 `<select>` 로 넘겨 `getByLabelText('Provider')` 를 성립시킨다(NFR6 접근성도 겸함).
  **기본값 `'GITHUB'`** — `useState<GitProvider>('GITHUB')`.
- **테스트 파일 상단에 `vi.mock('@/components/ui/select', ...)` shim 필수** — `PatCreateForm.test.tsx:15-83` **복제**.
  없으면 `hasPointerCapture` 로 Select 인터랙션이 깨진다. **shim 은 테스트 전용이며 구현은 Radix 실물을 쓴다.**
- testid `git-webhook-*` 접두(FR20).

**REFACTOR**.
- **★ 판별력 검증(mutation)** — 구현에 `secret.trim()` 을 **일부러 끼워 넣어** EC2 테스트가 red 가 되는지 확인한 뒤 되돌린다(기준선 전체 초록 선확인). **MSW 도 E2E 도 이 사고를 못 잡으므로**(EC15) 이 단위 테스트가 **유일한 가드**다([[verify-logic-vs-verify-guard]]).

**검증**. `cd apps/web && node_modules/.bin/vitest run src/components/automation/GitWebhookRegisterDialog.test.tsx`

---

### Task 6. `GitWebhookSection` — 목록 4상태 + 등록 순차 + 삭제 + `mutation.reset()`

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/GitWebhookSection.tsx`, `apps/web/src/components/automation/GitWebhookSection.test.tsx`]
- depends-on: [3, 4, 5]

> **★ 이 task 가 가장 크다** — FR2·FR7·FR8(수렴)·FR11·FR12·FR14·FR21 + ★B2 전층 판별자. 3분할(목록/등록/삭제)하면 **같은 파일이라 자동 직렬화 → +2 wave** 라 묶었다. §Plan 메타 §5 의 PR 분할 문의 대상.

**RED**.
- 파일: `apps/web/src/components/automation/GitWebhookSection.test.tsx`
- 테스트:
  ```ts
  // ★★ BLOCKER-0 2층 — 전층(Dialog→Section→api→MSW) 요청 본문 바이트 단언 (EC2 · ★B2)
  it('등록 요청 본문의 secret 이 입력과 바이트 단위로 동일하다', async () => {
    let body: unknown
    server.use(http.post('*/git-webhooks', async ({ request }) => { body = await request.json(); ... }))
    // ... 등록 Dialog 열고 '  abcdefghijklmnop  ' 입력 후 제출
    expect((body as { secret: string }).secret).toBe('  abcdefghijklmnop  ')
  })
  // ★ FR12 판별자 — 4상태 전부 (AutomationRuleList.tsx:405-427 동형)
  it('loading — role="status" + aria-label 을 낸다', ...)                               // :405-409
  it('error 403 — accessDenied 문구를 렌더한다(백지 아님)', ...)                        // :411-417 ★ 이게 없으면 섹션이 백지 → "웹훅 0개" 오해
  it('error 그 외 — genericError 문구를 렌더한다', ...)
  it('empty — "등록된 Git 웹훅이 없습니다." + 등록 CTA', ...)                          // :419-421
  it('list — provider·createdAt 으로 식별해 렌더한다', ...)                             // S6
  it('createdBy UUID 를 화면에 표시하지 않는다', ...)                                    // ★ EC18 음성 단언 (RuleConflictWarningModal.tsx:42-43 선례)
  // FR2 — 모달 겹침 금지
  it('201 직후 등록 Dialog 가 먼저 닫히고 그 다음 URL 모달이 열린다', ...)
  // ★ FR7 판별자 (재설계 — 아래 §FR7 참조). reset() 이 닫기 기전 자체라 지우면 모달이 안 닫힌다.
  it('URL 모달에서 닫기→확인 하면 모달이 사라진다', ...)
  it('모달을 닫은 뒤 다시 등록하면 이전 URL 이 아니라 새 URL 이 뜬다', ...)  // 잔존 data 재사용 방지 (양성 대조군)
  // FR14 — 삭제
  it('삭제 확인 모달이 "연동이 끊기고 복구할 수 없습니다" + "provider 설정의 URL 도 함께 교체" 두 가지를 말한다', ...)
  it('204 후 목록을 invalidate 해 행이 사라진다', ...)                                   // EC19
  it('삭제 404 는 토스트 없이 invalidate 로 조용히 사라진다', ...)                       // EC10 멱등적 귀결
  it('삭제 403 은 에러 토스트 + 목록 유지(낙관적 제거 없음)', ...)                       // EC11
  it('isPending 이면 삭제 확인/취소가 disabled 다', ...)                                 // EC12
  // FR11 — 상시(에러 조건부 아님)
  it('재발급 부재 도움말이 목록 상태와 무관하게 항상 렌더된다', ...)
  ```
- 실패 메시지 (예상). `Failed to resolve import "./GitWebhookSection"`

**GREEN**.
- h2 **`'Git 웹훅'`**(FR1-b) — 소유자는 이 컴포넌트(룰 섹션이 자기 h2 를 소유하는 것과 대칭). **`'자동화 룰'` 과 부분문자열로도 겹치지 않는다** → §제약 11 의 11 단언 안전.
- 4상태는 `AutomationRuleList.tsx:405-427` **동형** — 403 분기 `extractAutomationRuleErrorCode(error) === 'AUTOMATION_ACCESS_DENIED' ? labels.accessDenied : labels.genericError`.
- 등록 Dialog **open 상태를 이 컴포넌트가 소유**(FR2) → **route 상태 6종 불변**.
- **★★ FR7 재설계 — URL 모달의 `webhookUrl` 을 별도 state 가 아니라 `registerMutation.data` 에서 파생시킨다.**
  ```tsx
  <GitWebhookUrlModal
    webhookUrl={registerMutation.data?.webhookUrl ?? null}
    onClose={() => { registerMutation.reset() }}   // ← reset() 이 유일한 닫기 기전
  />
  ```
  - **왜 바꾸나.** 원안(`urlPayload` state 별도 보유 + `handleClose` 에서 state null + `reset()` 동시 수행)의
    판별자 `expect(registerMutation.data).toBeUndefined()` 는 **컴포넌트 테스트에서 작성 자체가 불가능**하다 —
    `registerMutation` 은 `GitWebhookSection` **내부 지역변수**라 `render(<GitWebhookSection/>)` 밖에서 접근할 통로가 없다.
    (`grep -rn "result.current.data" apps/web/src` 히트 **전부 `renderHook` 훅 레벨**이다 — 컴포넌트 레벨 선례 **0건**.)
    → 구현자는 그 단언을 못 써서 **"모달이 사라진다"** 같은 약한 단언으로 조용히 대체하고, 원안에선 state null 만으로도
    그게 초록이라 **`reset()` 을 통째로 지워도 전 스위트가 초록**이다. 즉 원안 판별자는 vacuous 를 넘어 **미작성 가능**했다.
  - **파생시키면 `reset()` 이 구조적으로 load-bearing 해진다** — data 가 곧 렌더 조건이라 `reset()` 을 지우면
    **모달이 영원히 안 닫히고** 위 2단언이 즉시 red 다. 가드를 지우면 **기능이 죽는다** = 진짜 판별자([[verify-logic-vs-verify-guard]]).
  - **NFR1 도 이 쪽이 더 강하다** — `mutation.reset()` 은 `data`(URL·token) 와 `variables`(**secret 원문**) 를 **함께** 비운다.
    원안은 state 만 비우고 mutation 캐시에 secret 이 **gcTime 5분** 잔존해도 초록이었다.
  - **`urlPayload` state 를 만들지 않는다** → route 상태 6종 불변(FR22-a)에 더해 Section 상태도 1개 줄어든다.
- 순차 가드(FR2) — 201 시 `onSuccess` 에서 `setFormOpen(false)`. `registerMutation.data` 는 같은 커밋에 채워지므로
  **등록 Dialog 부재 + URL 모달 존재가 동시에 성립**한다. → **FR2 단언은 시간 순서가 아니라 "겹침 없음"을 본다**
  (`queryByTestId('git-webhook-register-dialog')` 부재 **와** URL 모달 존재를 **같은 시점에** 단언). 선례 `route:162`
  `conflicts={webhookToken === null ? conflicts : null}` 의 "토큰 우선" 패턴과 동형이다.
- 삭제 확인 모달 = file-local `DeleteConfirmDialog` **구조 복제**(`AutomationRuleList.tsx:109-166` — props 4개 · `if (x === null) return null` · 취소 `variant="outline"` / 확인 `variant="destructive"` · 둘 다 `disabled={isPending}`).
- FR13 배선 — 목록 쿼리 데이터를 Dialog 에 `existingProviders` / `listUnavailable={isLoading || isError}` 로 내린다(**신규 엔드포인트 0**).
- **NFR1** — `webhookUrl` 은 **경로에 원문 토큰이 박혀 있어 URL 자체가 비밀**이다. localStorage/sessionStorage/URL 쿼리/로그 기록 **금지**(§1.18).

**REFACTOR**.
- **★ FR7 판별력 검증 — mutation 필수.** 기준선 전체 초록을 **선확인**한 뒤 `onClose` 의 `registerMutation.reset()` 을
  **일부러 지워** 위 FR7 2단언이 red 가 되는지 확인하고 되돌린다. 초록이면 파생 배선이 안 된 것(= `urlPayload` state 가
  아직 남아 있다는 신호)이다([[verify-logic-vs-verify-guard]]).
- FR11 도움말 문구가 **왜 재발급이 없는지 + 대안(삭제 후 재등록 + provider 설정 갱신)** 을 말하는지 확인(S8 — #273 상시 도움말 승격 선례 동형).
- `<h2>` · `<p>` 문구 상수를 파일 상단 `labels` 로 모은다(`AutomationRuleList.tsx:23-` 관례).

**검증**. `cd apps/web && node_modules/.bin/vitest run src/components/automation/GitWebhookSection.test.tsx`

---

### Task 7. route 조립 — 형제 섹션 마운트 + h1 설명문 교체 + KDoc

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/routes/projects.$projectKey.settings.automation.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx`]
- depends-on: [6]

> **왜 한 task 인가.** FR0(마운트) · FR1(설명문) · FR22(KDoc)가 **같은 2파일**을 건드린다 → 쪼개도 자동 직렬화라 wave 만 늘고 이득 0.
>
> **★ ★F-b①(route test MSW 합류)는 이 task 에서 Task 9 로 이관했다.** 사유는 Task 9 §메타 참조 —
> **route test 를 깨는 건 이 task 가 아니라 Task 9** 다(`route:151` 이 `AutomationRuleFormDialog` 를 **조건 없이 마운트**하므로).
> 그 결과 이 task 가 dispatch 될 때(W5)엔 `:62` 에 `gitWebhookHandlers` 가 **이미 합류돼 있다**(Task 9 = W4).

**RED**.
- 파일: `apps/web/src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx`
- **★ 원안의 "2단계 RED"(합류 전 red 를 먼저 본다)는 삭제했다 — 판별자가 vacuous 였다.**
  Task 9(W4)가 이미 `GET .../git-webhooks` 를 발화시키고 `:62` 합류까지 마친 상태이므로, 이 task 의 `GitWebhookSection`
  마운트 **여부와 무관하게** 1단계는 **항상 초록**이다(원안은 "red 가 안 나면 FR0 미구현 신호"라고 선언했는데
  **역도 성립하지 않는다**). → **양성 단언으로 대체**한다. 아래 `heading 'Git 웹훅'` 단언이 FR0 의 유일·직접 판별자다.
  ```ts
  it('페이지가 자동화 룰 섹션과 Git 웹훅 섹션을 형제로 조립한다', () => {
    expect(screen.getByRole('heading', { name: '자동화 룰' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Git 웹훅' })).toBeInTheDocument()
  })
  // FR1 — 문구 교체 판별자
  it('h1 설명문이 룰 전용 문구가 아니라 두 섹션을 포괄한다', () => {
    expect(screen.queryByText(/트리거 규칙을 관리/)).not.toBeInTheDocument()
  })
  ```
- 실패 메시지 (예상). `Unable to find an accessible element with the role "heading" and name "Git 웹훅"`.
- **★ MSW 합류는 이 task 의 책임이 아니다** — `:62` 는 Task 9 가 이미 고쳐 놨다. **다시 건드리지 마라**(중복 spread 유발).
  단 REFACTOR 의 전수 재확인은 그대로 수행한다.

**GREEN**.
- **FR0** — `route:133` `<div className="p-8 space-y-6 max-w-2xl">` 안, `AutomationRuleList` 의 **형제**로 `<GitWebhookSection projectKey={projectKey} />` 추가. **신규 라우트 0 · `router.ts` 무변경**(Maxi D2 확정).
- **FR1 — 변경 대상은 `route:136-138` 의 `<p>` 한 덩어리뿐.** 교체 후 문자열(**확정 — 구현자가 발명하지 말 것**).
  `이슈 이벤트·예약 일정·PR 머지에 따라 자동으로 실행될 규칙과, 규칙을 발화시키는 웹훅 연동을 관리합니다.`
  - **`'Git 웹훅'`·`'자동화 룰'` 을 이 문구에 넣지 않는다** — `getByText` 중복 매치 표면을 애초에 안 만든다(`웹훅 연동` 으로 우회).
  - **판별자** — `grep -c '트리거 규칙을 관리' apps/web/src/routes/projects.$projectKey.settings.automation.tsx` = **0**.
- **★ (a) route 에 `'자동화 룰'` h2 를 절대 추가하지 않는다.** 룰 h2 는 `AutomationRuleList.tsx:380` 에 **이미 있다**. route 에 같은 텍스트 h2 를 도입하면 Playwright `getByRole('heading',{name})` 의 **부분문자열 매칭**이 2개를 잡아 **strict mode 위반 → E2E 11 단언 / 6 스펙 전부 red**(§제약 11). **이는 PR-D 가 만드는 회귀이지 기존 결함이 아니다.** `AutomationRuleList.tsx` 는 이 task 의 변경 대상이 **아니다**.
  - **실측 보강** — h1(`:135`)은 `'자동화'` 라 `'자동화 룰'` 을 부분문자열로 **포함하지 않는다** → h1 무변경이면 안전.
- **FR22-(b) 조립 열거 갱신 필수** — `route:55-56` 이 조립 컴포넌트를 **전수 열거**한다. `GitWebhookSection` · `GitWebhookUrlModal` 을 추가하지 않으면 그 열거가 **거짓**이 된다. 열거형 drift 는 `verify-master-plan.sh` 가 **안 본다**.
- **FR22-(a) `route:58` 의 `상태 6종` 숫자는 변경하지 않는다** — FR2 가 Dialog 소유를 Section 에 뒀으므로 route 상태(`:87-92`)는 6개 그대로다. **route 에 상태를 추가하게 되면 그건 FR2 위반** — 그때는 숫자도 함께 고친다.

**REFACTOR**.
- **★ 회귀 전수 재확인** — `grep -rn "setupServer" apps/web/src` 로 합류 누락이 없는지 재실측. `AutomationRuleList.test.tsx:31` 은 **비대상**(`GitWebhookSection` 은 형제라 단독 렌더 시 웹훅 쿼리 미발화).

**검증**. `cd apps/web && node_modules/.bin/vitest run 'src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx' src/components/automation/AutomationRuleList.test.tsx`

---

### Task 8. ★ **targetBranch 원자 4종** — parse ④ + serialize omit ② + 호출부 ③ + 입력 UI

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/api/automation-rules.types.ts`, `apps/web/src/api/automation-rules.types.test.ts`, `apps/web/src/components/automation/AutomationRuleFormDialog.tsx`, `apps/web/src/components/automation/AutomationRuleFormDialog.test.tsx`]
- depends-on: []

> **★★ 절대 쪼개지 마라 (스펙 ★A).** FR16(parse) 없이 FR17(omit)만 하면 **현상유지보다 나쁘다** — 지금은 `JSON.stringify(base)`(`automation-rules.types.ts:253`)로 통째 보존이라 유실이 **0** 인데, omit 만 넣으면 편집할 때마다 targetBranch 가 **유실**된다(브랜치 한정 룰이 **전 브랜치 발화로 조용히 승격**). **중간 상태가 버그를 새로 만든다.**

**RED**.
- 파일: `apps/web/src/api/automation-rules.types.test.ts` + `apps/web/src/components/automation/AutomationRuleFormDialog.test.tsx`
- **(1) ★ 기존 테스트 1개를 의도적으로 깬다 — 회귀가 아니라 계약 전환이다.**
  `automation-rules.types.test.ts:392-399` `it('PR_MERGED: 폼이 관리하지 않는 targetBranch 는 편집해도 유실되지 않는다')` 를 **재작성**한다.
  - 기대값 `{targetBranch:'develop', extraKey:'keep'}` → **`{extraKey:'keep'}`**(omit)
  - **제목 + 선행주석(`:386-391`) 도 함께 갱신** — `:387` 의 `호출부(AutomationRuleFormDialog:291)는 { cron, fields } 만 넘긴다` 가 이 task 로 **거짓이 된다**.
  - **판별자** — 이 테스트가 **갱신 없이 초록이면 ②(omit)가 누락된 것**이다.
  - `:349`·`:401` 2개는 base 가 없어 omit 이 무동작 → **원래 판별력 0(vacuous)**. 그대로 둔다.
- **(2) ★B 다이얼로그 레벨 라운드트립 5단언 신설** — `AutomationRuleFormDialog.test.tsx` 에 PR_MERGED describe 추가.
  **현재 이 파일에 `PR_MERGED` 는 0건이다 — ③④를 잡을 테스트가 코드베이스에 아예 없다.** serialize 단위 테스트는 호출부를 못 보므로 **원리적으로 못 잡는다.**

  | 단언 | 시나리오 | 기대 | 잡는 누락 |
  |---|---|---|---|
  | **A1** | `editingRule.triggerConfig = '{"targetBranch":"develop","futureKey":"x"}'` 로 렌더 | 입력에 `'develop'` **로드됨** | **FR16(④)** — parse 미구현이면 빈 값 → red |
  | **A2** | A1 에서 **이름만** 고쳐 저장 | `JSON.parse(capturedBody.triggerConfig)` = `{targetBranch:'develop', futureKey:'x'}` | **FR16+FR18(④+③)** — **핵심 회귀 가드** |
  | **A3** | 입력을 `'release/1.2'` 로 바꿔 저장 | `{targetBranch:'release/1.2', futureKey:'x'}` | **FR18(③) 양성 대조군** — A2 만 있으면 "base 통째 보존"으로도 초록이 난다 |
  | **A4** | 입력을 **비우고** 저장 | `{futureKey:'x'}` (**targetBranch 키 부재**) | **★ FR17(②) 단독 — 이 PR 의 핵심 가드.** 없으면 `omitManagedKeys` 를 통째로 지워도 전 스위트가 초록이다(A1~A3 는 값 있는 경로만 밟아 omit 을 **한 번도 실행하지 않는다** — [[guard-handler-matrix-blindfold]]) |
  | **A5** | `triggerType='ISSUE_CREATED'` 로 렌더 | targetBranch 입력 **부재**(음성 단언) | **FR19 과도발화** — `:121` 음성 단언 스타일 확장 |
- 실패 메시지 (예상). A1 — `expected '' to be 'develop'`. A4 — `expected { futureKey: 'x', targetBranch: 'develop' } to deeply equal { futureKey: 'x' }`. types.test — `expected { extraKey:'keep', targetBranch:'develop' } to deeply equal { extraKey: 'keep' }`.

**GREEN** — **4종을 한 커밋에**.
- **④ FR16 `parseTriggerConfig`**(`AutomationRuleFormDialog.tsx:151-176`). `ParsedTriggerConfig` 에 `targetBranch: string` 추가 + `obj['targetBranch']` 읽기 + **return 3곳 전부** — `:164` 폴백(비객체) · `:171` 성공 경로 · `:174` catch 폴백 — + `initialConfig` 폴백(`:459`) + 폼 `defaultValues`(`:482-486`).
- **② FR17 `serializeTriggerConfig`**(`automation-rules.types.ts`). 시그니처 `config` 에 `targetBranch?: string` 추가 + **PR_MERGED 를 fallthrough 그룹(`:249-253`)에서 떼어내 독립 case 로**. 선례 = **같은 함수의 `ISSUE_UPDATED` 분기**(`:243-248`) — `omitManagedKeys(base, ['targetBranch'])` 후 값 있으면 병합, 없으면 rest 만.
  - **여기서 `trim()` 은 targetBranch 에만 해당한다. ⚠️ secret 은 정반대이며 trim 을 절대 하지 않는다**(FR4 · BLOCKER-0). targetBranch 에 trim 이 필요한 이유 = 배열 `length > 0` 에 대응하는 문자열 술어가 `trim() !== ''` 이고, 공백만 든 입력을 실어보내면 백엔드 `asText().isBlank()` 가 **400** 을 낸다(`TriggerConfig.kt:104`).
  - **KDoc `:214-228` 재작성 필수** — 현재 `PR-D 에서 함께 도입한다` **미래형**이다. 안 고치면 **코드와 모순되는 주석**이 남는다.
- **③ FR18 호출부 3홉** — `onValid`(`:511-526`) → `buildSharedSavePayload`(`:279-291`) → `serializeTriggerConfig`(`:291`). 헬퍼가 이미 **7 위치인자**이고 `cron`·`targetBranch` 가 **둘 다 string** 이라 순서를 바꿔도 타입 에러가 안 난다(eslint `max-params` 룰 없음) → **`{cron, fields, targetBranch}` 객체 1개로 묶어 전달**(serialize `config` 인자와 동형, 위치 혼동 불가).
- **FR19 입력 UI** — `TriggerConfigFields`(`:348-436`)의 `return null`(`:435`) **앞에** `if (triggerType === 'PR_MERGED')` 분기 추가. 템플릿 = **cron 분기(`:369-392`)** — RHF `register` 기반 단일 텍스트 입력.
  - **에러 표시 블록만 제거**(refine 없음 → 에러 채널 불필요). `type="text"`(`:377`)·`autoComplete="off"`(`:380`)·`font-mono` **유지**. **targetBranch 는 비밀값이 아니므로 `text` 가 맞다**(⚠️ 이 템플릿을 secret 에 복제할 때만 `password` — Task 5).
  - 폼 스키마는 `targetBranch: z.string()` **한 줄만**(`formSchema` `:311-321`) — **`.refine` 금지**. 빈 값이 **정당한 의미(전 브랜치)** 를 가지므로 cron 필수 검증을 복제하면 **틀린다**.
  - testid `automation-rule-target-branch-input`(`:379` 동형). **`git-webhook-*` 접두는 여기 적용 안 됨** — targetBranch 는 automation-rule 폼 소속(FR20).
  - **설명문에 와일드카드를 암시하면 안 된다**(EC17) — 매칭은 **정확 문자열 비교**다(`GitWebhookService.kt:339-346`). `release/*` 를 넣으면 **조용히 0건 발화**. 문구는 `fieldsDescription`(`:53`) 문형을 따라
    `'지정한 브랜치로 병합될 때만 발화합니다. 비워두면 모든 브랜치에 반응합니다.'`

**REFACTOR**.
- **★ 판별력 검증 — mutation 필수.** 기준선 전체 초록을 **선확인**한 뒤 `omitManagedKeys(base, ['targetBranch'])` 호출을 **일부러 지워** A4 가 red 가 되는지 확인하고 되돌린다. **초록이면 A4 가 vacuous** 다([[verify-logic-vs-verify-guard]]).
- **EC14 — MSW 는 이 계약을 검증할 수 없다.** MSW 핸들러가 `triggerConfig` 를 검증 없이 통과시켜(`automation-rule-handlers.ts:214`) `{"targetBranch":""}` 를 보내도 **MSW 초록 / 실서버 400**([[date-input-iso-instant-query-param]] 동일 기전). → **단위 테스트가 유일한 가드**다.

**검증**. `cd apps/web && node_modules/.bin/vitest run src/api/automation-rules.types.test.ts src/components/automation/AutomationRuleFormDialog.test.tsx`

---

### Task 9. FR15 PR_MERGED 무음 실패 경고 + **MSW 합류 2곳**(★F-b② + ★F-b①)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/components/automation/AutomationRuleFormDialog.tsx`, `apps/web/src/components/automation/AutomationRuleFormDialog.test.tsx`, `apps/web/src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx`]
- depends-on: [2, 3]

> **★★ 이 task 가 `gitWebhookHandlers` 합류 2곳을 **모두** 소유한다 — blast radius 가 자기 파일 밖으로 나가기 때문이다.**
> **실측**. `route:151` 의 `<AutomationRuleFormDialog` 는 **조건 없이 마운트**된다(`open={dialogOpen}` 는 prop 일 뿐
> 마운트 게이트가 아니다). 따라서 이 task 가 폼에 `useQuery`(git-webhooks)를 심는 순간 **route test 의 모든 테스트**가
> 그 쿼리를 발화시킨다. 그런데 route test `:62` 는 `setupServer(...automationRuleHandlers, ...automationExecutionHandlers)`
> 로 **`gitWebhookHandlers` 가 없고**, `:64` 가 `server.listen({ onUnhandledRequest: 'error' })` 다 →
> **unhandled GET = 그 파일 전체 red**.
> - **원안(files 2개)이면 이 task 가 red 트리를 남기고 졸업한다** — 자기 검증은 `FormDialog.test.tsx` 만 돌리고
>   bts-impl verifier 는 **files 한정 diff** 만 본다 → **탐지 통로 0**. 그래서 route test 를 **files 에 편입**한다.
> - **`depends-on: [2, 3]`(CONCERN-2 정정)** — 원안 `[3]` 은 3←2 전이로 실해는 없었으나 **선언이 실제 의존을 말하지 않았다**.
>   이 task 는 `gitWebhookHandlers`(**Task 2 산출물**)를 직접 import 해 합류시킨다. **wave 수 불변**.
>
> **files 3개 전부가 형제 task 와 겹친다 → bts-impl 이 자동 직렬화한다.** Task 8(W1, 폼 2파일) · Task 7(W5, route test).
> 이 task 는 **W4** 라 양쪽 사이에 자연히 끼며 **추가 wave 비용 0**이다. Task 8 은 폼 로직, 이 task 는 폼 안 쿼리 구독 —
> 관심사가 갈린다. Task 7 은 이 task 가 합류시킨 `:62` 를 **그대로 물려받아** 단언만 얹는다.
> **★ Maxi D4 확정** — 웹훅 0건이면 PR_MERGED 룰이 **영원히 발화 안 하는데 이력도 비어 원인 도달 불가**. 두 요소를 한 화면에 모으는 유일한 PR 이다.

**RED**.
- 파일: `apps/web/src/components/automation/AutomationRuleFormDialog.test.tsx` + `apps/web/src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx`
- **★ 2단계 RED**(★F 판별자). 폼에 `useQuery` 구독분만 넣고 **두 파일을 함께** 실행 →
  ① `FormDialog.test.tsx:42` 로컬 `setupServer(...automationRuleHandlers, ...projectMemberHandlers)` 에 핸들러가 없어
  **unhandled → 파일 전체 red**(`:44` `onUnhandledRequest:'error'`) ②
  `route test:62` 도 **같은 이유로 파일 전체 red**(`:64` 동일 설정). **한쪽이라도 red 가 안 나면 쿼리가 실제로 마운트되지 않은 것**(FR15 미구현 신호).
- 그 뒤 **두 곳 모두** `...gitWebhookHandlers` 를 합류시킨다.
  - `FormDialog.test.tsx:42` → `setupServer(...automationRuleHandlers, ...projectMemberHandlers, ...gitWebhookHandlers)` (★F-b②)
  - `route test:62` → `setupServer(...automationRuleHandlers, ...automationExecutionHandlers, ...gitWebhookHandlers)` (★F-b① — **이관받음**)
- **★ route test 에는 단언을 추가하지 않는다** — 이 task 의 route test 변경은 **`:62` 한 줄(+import)뿐**이다.
  FR0/FR1 단언은 Task 7(W5) 몫이다. 그 이상 건드리면 W5 와 충돌한다.
- 테스트:
  ```ts
  // ★ FR15 판별자 3종 (양성 1 + 음성 2)
  it('웹훅 0건 + PR_MERGED 선택 → "이 프로젝트에 Git 웹훅이 없어 이 룰은 발화하지 않습니다." 를 렌더한다', ...)
  it('웹훅 1건 + PR_MERGED → 경고 부재', ...)                          // 음성 단언
  it('목록 isError(403) + PR_MERGED → 경고 부재', ...)                 // ★ EC21 — "못 읽음" ≠ "0건". 403 사용자에게 "웹훅이 없다"고 단정하면 거짓
  it('웹훅 0건이어도 저장은 막지 않는다(정보성)', ...)                 // FR15 — 차단 금지
  ```
- 실패 메시지 (예상). 1단계 — **두 파일 모두** `intercepted a request without a matching request handler: GET .../git-webhooks`. 2단계 — `Unable to find an element with the text: 이 프로젝트에 Git 웹훅이 없어…`.

**GREEN**.
- **신규 엔드포인트 0** — 같은 페이지에 이미 마운트된 `GIT_WEBHOOKS_QUERY_KEY(projectKey)` 를 `useQuery` 로 **구독만** 한다. 판정 = **`data?.length === 0`**.
- `isLoading` / `isError` 일 때는 **경고를 내지 않는다**(EC21).
- 경고 + **등록 섹션으로 유도하는 문구**. **저장 차단 금지.**
- **⚠️ "추가 API 호출 0" 이라고 쓰지 마라 (실측 반증).** ① `main.tsx:17-22` QueryClient = `{queries:{retry:false}, mutations:{retry:false}}` 뿐 → **`staleTime` 미설정 = 0** ② `grep -n "staleTime" apps/web/src/api/useAutomationRules.ts` → **0건**. **staleTime 0 + `refetchOnMount` 기본 true** 라 새 observer 마운트 시 **배경 refetch 가 1회 나간다**. 캐시 공유로 실제 얻는 것은 **① 중복 인플라이트 dedupe ② 캐시 즉시 표시**이지 '호출 0' 이 아니다. 렌더 블로킹·UX 영향은 없고 판정식은 그대로 유효하다. **KDoc 에 '호출 0' 이라고 적으면 스펙이 못 지킬 약속을 하는 것**(NFR1 의 `mutation.reset()` 과 같은 규율).

**REFACTOR**.
- 경고 문구 상수를 파일 상단 `labels`(`:23-`)에 합류.
- **★ blast radius 재확인** — `grep -rn "AutomationRuleFormDialog" apps/web/src` 로 **이 폼을 마운트하는 모든 호출부**를
  전수 열거하고, 각 호출부의 테스트가 `gitWebhookHandlers` 를 갖는지 확인한다. **개수를 믿지 말고 열거하라** —
  이 task 의 BLOCKER 자체가 "`route:151` 무조건 마운트를 아무도 안 셌다"에서 나왔다.

**검증**. **route test 를 반드시 함께 돌린다** — 이 task 가 깨는 파일이라 빠뜨리면 red 트리를 남기고 졸업한다.
```
cd apps/web && node_modules/.bin/vitest run src/components/automation/AutomationRuleFormDialog.test.tsx 'src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx' > /tmp/t9.txt 2>&1; echo "EXIT=$?"
```

---

### Task 10. D7 — MSW PR_MERGED 픽스처(EC16) + E2E 신규 스펙

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/src/mocks/automation-rule-fixtures.ts`, `apps/web/e2e/automation-git-webhook.spec.ts`]
- depends-on: [7, 8, 9]

**RED**.
- 파일: `apps/web/e2e/automation-git-webhook.spec.ts`
- **선행조건 = EC16 픽스처.** MSW 픽스처에 **PR_MERGED 가 0건**이다(`automation-rule-fixtures.ts:132,149` = ISSUE_CREATED · SCHEDULED 뿐). → **④의 핵심 경로를 화면으로 밟을 수 없어 버그가 D7 눈검사를 통과한다.** `triggerConfig: JSON.stringify({targetBranch:'release/1.2'})` 를 가진 PR_MERGED 룰을 `DEFAULT_AUTOMATION_RULES` 에 추가하고 `SEED_AUTOMATION_RULE_IDS`(`:72-76`)를 확장한다 — **RFC4122 v4 형식 필수**([[zod-v4-uuid-fixture-strictness]], `:72` 주석이 박제).
- **★★ 새 룰은 `DEFAULT_AUTOMATION_RULES` 배열 **맨 끝에 append** 한다. 앞/중간 삽입 금지.**
  **사유 — files 밖 4파일이 인덱스 위치를 계약으로 전제한다**(`grep -rn 'DEFAULT_AUTOMATION_RULES\[' apps/web/src` 전수).
  | 파일 | 전제 |
  |---|---|
  | `components/automation/AutomationRuleList.test.tsx:46-58` | `[0]`=ISSUE_CREATED(enabled) · `[1]`=SCHEDULED(nextFireAt 있음) — **헬퍼 2개** |
  | `api/automation-rules.test.ts:45-49` | `[0]` 가드 헬퍼 |
  | `api/useAutomationRules.test.tsx:43-47` | `[0]` 가드 헬퍼 |
  | `routes/__tests__/projects.$projectKey.settings.automation.test.tsx:79-82` | `[0]`=ISSUE_CREATED(enabled) |
  - **개수 단언은 전부 상대값이라 안전하다**(`toHaveLength(DEFAULT_AUTOMATION_RULES.length)` — `useAutomationRules.test.tsx:87`).
    **깨지는 건 개수가 아니라 위치 계약**이다. 앞에 끼우면 `[0]` 이 PR_MERGED 가 되어 `nextFireAt`·`enabled` 전제가
    무너지고 **4파일이 동시에 red** 인데 **그 4파일은 이 task 의 files 에 없다** → 원인 도달이 어렵다.
  - `automation-rule-handlers.test.ts` 도 `DEFAULT_AUTOMATION_RULES` 를 쓰지만 **인덱스 미사용**이라 비대상
    (참조 5파일 중 인덱스 전제는 **4파일**).
- 테스트: `automation-yaml-gitops.spec.ts:35-36,83-87` 관례 — **URL 직접 `goto`**(사이드바 자체가 없다, §제약 5).
  ```ts
  // S1 — 등록 + URL 1회 노출
  test('등록하면 origin 이 붙은 완전 URL 이 1회 노출되고 복사할 수 있다', ...)
  // S6 — 목록
  test('등록 후 목록에 provider·등록일시로 표시된다', ...)
  // S5 — 삭제
  test('삭제 확인 모달이 복구 불가를 명시하고, 확인하면 목록에서 사라진다', ...)
  // S12 — targetBranch 라운드트립 (④의 핵심 경로)
  test('PR_MERGED 룰을 편집해 이름만 고쳐 저장해도 targetBranch 가 유지된다', ...)
  ```
- 실패 메시지 (예상). `Error: expect(locator).toBeVisible() failed — locator resolved to 0 elements` (신규 섹션/픽스처 부재)

**GREEN**.
- 픽스처 추가 + E2E 스펙 작성. 시나리오 토글은 `addInitScript` + localStorage 플래그([[e2e-msw-scenario-toggle-localstorage-flag]]).

**REFACTOR**.
- **★ 픽스처 위치 계약 판별자** — append 를 지켰는지 **직접 확인**한다.
  ```
  grep -rn 'DEFAULT_AUTOMATION_RULES\[' apps/web/src > /tmp/idx.txt 2>&1; echo "EXIT=$?"
  cd apps/web && node_modules/.bin/vitest run src/components/automation/AutomationRuleList.test.tsx src/api/automation-rules.test.ts src/api/useAutomationRules.test.tsx 'src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx' > /tmp/idx-run.txt 2>&1; echo "EXIT=$?"
  ```
  **이 4파일은 이 task 의 files 가 아니다 — 그래서 더더욱 직접 돌려야 한다**(files 한정 diff 만 보는 verifier 는 못 잡는다).
- **★ 기존 automation E2E 6건 동시 통과 확인**(★D) — `automation-{actions,conditions,conflict-warning,execution-history,rules,yaml-gitops}.spec.ts`. **같은 페이지를 건드리므로 회귀 확인 필수**([[ui-pr-defer-e2e-regression-latent]]).
  - **이 실행이 §제약 11 의 판별자를 겸한다** — h2 를 잘못 도입했으면 Playwright 가 `strict mode violation: ... resolved to 2 elements` 로 **11 단언이 즉사**한다.
- **E2E 는 CI 에서 안 돈다**(`.github/workflows/frontend-ci.yml` 잡 3개 = lint `:45` · typecheck `:65` · test `:87`, `playwright` 문자열 **0건**). → **자동 게이트가 없다.** 통과 증거를 **보고서/PR 본문에 첨부**하는 규율에 전적으로 의존한다.
- **E2E 전 `lsof -ti tcp:5173`** 으로 orphan vite 확인([[e2e-orphan-vite-after-worktree-remove]]).

**검증**.
```
cd apps/web && node_modules/.bin/playwright test e2e/automation-git-webhook.spec.ts > /tmp/e2e-new.txt 2>&1; echo "EXIT=$?"
cd apps/web && node_modules/.bin/playwright test e2e/automation-actions.spec.ts e2e/automation-conditions.spec.ts e2e/automation-conflict-warning.spec.ts e2e/automation-execution-history.spec.ts e2e/automation-rules.spec.ts e2e/automation-yaml-gitops.spec.ts > /tmp/e2e-reg.txt 2>&1; echo "EXIT=$?"
```

---

### Task 11. 완료마킹 — `automation.md` D6/D7 + 전사갭 1행 + 대시보드 재생성 (★E)

**메타**.
- agent: `frontend-engineer`
- files: [`docs/plan/product/automation.md`, `docs/progress.html`]
- depends-on: [10]

> **왜 deps 가 [10] 인가.** 커밋 순서 의존이 아니라 **데이터 의존**이다 — 완료 블록이 인용할 **유닛 수 · E2E 결과 · PR 번호**가 Task 10 이 끝나야 존재한다. 선례 6블록 전부 실측치를 담고 있다.

**RED**.
- 파일: 없음(문서 task — 테스트 대신 **판별자 grep**).
- 판별자:
  ```
  grep -c '^- \[ \] D6\.' docs/plan/product/automation.md          → 0 이어야 함
  grep -c '^- \[ \] D7\.' docs/plan/product/automation.md          → 0
  grep -c '123' docs/plan/product/automation.md                     → 6 (기존 6곳 불변 — 새 블록에 7번째가 생기면 실패)
  grep -n '| 규칙 충돌 정적 분석 | 1s | ___' docs/plan/product/automation.md → 0건
  bash scripts/verify-master-plan.sh                                → EXIT=0
  ```

**GREEN**.
- **D6·D7 두 줄만** `[x]`(`:127-128`). D1~D5 는 #278 이 이미 `[x]`.
- **★ D6 정본 문구("페이지")는 고치지 않는다.** `:127` `D6. 프론트 UI — Webhook URL 생성 **페이지**` / `:143` `D6(Webhook URL 생성 페이지)`. 옵션 A 는 "섹션"이라 글자상 어긋나지만 **정본을 조용히 고치면 "왜 페이지가 아니게 됐는가"라는 결정 근거가 소실**된다 → **배치 사유를 완료 블록에 적어 해소**한다.
- **D6/D7 완료 블록 append — 선례는 3개가 아니라 6개다**(실측 `:38`·`:54`·`:70`·`:86`·`:100`·`:116`). **`:38` 만 `automation BC N/7` 접미가 없고**(`→ **FR-AT-01 전체 완료(D1~D7)**.` 로 종료) 그 접미는 **#260 부터 생긴 관례** → **#260 이후 형식**을 따른다.
  - 형식 — `> **D6/D7 완료 (2026-07-17, PR #N)**. ` … `→ **FR-AT-07 전체 완료(D1~D7)**, automation BC **7/7**.` (#273 수준 2000~2600자)
  - **반드시 포함 4종** — ① **배치 사유**(옵션 A 형제 섹션 · 라우터 변경 0 · D6 정본 "페이지"와 어긋나는 이유) ② **secret trim 금지**(BLOCKER-0) ③ 중복 가드 · 목록 식별자 **후속 등재** ④ **`BC 7/7 ≠ BC 완료` 분리**
- **★★ FR 총수는 `128 불변`(D-step).** `CLAUDE.md:10` **재실측 완료** — `(128 FR, …)`, HEAD `802b495ef` 기준(#279 는 아직 128→129 로 안 올렸다). **착수 시 재실측 필수.**
  - **선례 문구를 복붙하면 7번째 "123"이 박힌다.** `grep -n "123" automation.md` → **6곳** 전부 `123 불변`. `:180` 이 자백하듯 **#278 이 123 을 유지한 건 #277 동시 PR 카운트 충돌 회피용 의도적 미변경이지 현재값이 아니다** — #277 이 FR-PJ-01~04 + FR-PM-10 으로 **123→128** 을 올렸다.
  - **`verify-master-plan.sh` 는 `CLAUDE`/`fr-index`/`README`/product 헤더만 보므로 `automation.md` 산문의 123 은 자동 차단에 안 걸린다.**
- **★ §NFR 측정표 — 전사갭 1행만 채운다**(Maxi D1 확정 = 옵션 C). `:190` 「규칙 충돌 정적 분석」 실측 셀 `___` → **`0.876s`**.
  - **★ 새로 측정하는 게 아니라 옮겨 적는 것이다.** 출처 = **`:84` 산문**(#268 D1~D5 블록) `성능 스모크 100규칙 0.876s(NFR 1s)`. 이미 측정돼 산문에만 있는 **전사 누락**. 임계 1s 대비 통과.
  - **나머지 4행은 건드리지 않는다**(`:189` 트리거 지연 · `:191` 실행 이력 재실행 · `:192` YAML import · `:194` 권한 차단율) — 백엔드 측정이라 순수 프론트 PR 로 불가 → **후속 등재**.
- **★ `docs/plan/README.md:110` 의 `☐` 는 뒤집지 않는다.** `automation BC 7/7`(FR 카운트) ≠ `automation BC 완료`(게이트). BC 완료 5조건(`:207-213`) 중 PR-D 가 채우는 건 **`:209` 첫 줄뿐**이다 — `:210` §NFR 표는 1행 채워도 **4행이 `___` 로 남고**, `:211`~`:213`(CHANGELOG · README §7 · Maxi 선언)은 **Maxi 몫**. **선례 #273 은 6/7 이라 이 칸을 마주한 적이 없다** — automation 에서 PR-D 가 처음 마주친다. 두 표현을 섞어 쓰면 **"완료했다"는 거짓 보고**가 된다(§제약 10).
  - → `README.md` 는 **이 task 의 files 에 없다**(변경 0).
- **`docs/progress.html` 재생성** — `node scripts/build-dashboard.mjs`(`OUTPUT_PATH` 가 거기다). §제약 1 이 이 파일을 이 PR 의 변경 대상으로 **명시 열거**한다.

**REFACTOR**.
- **[[dashboard-regen-after-fr-marking]]** — post-merge 훅 **상시 고장**(#278 로 **6회 연속** — 재생성·stage 까지 하고 커밋에서 죽는다). **수동 `--no-verify` 가 정규 절차**다.

**검증**.
```
node scripts/build-dashboard.mjs > /tmp/dash.txt 2>&1; echo "EXIT=$?"
bash scripts/verify-master-plan.sh > /tmp/vmp.txt 2>&1; echo "EXIT=$?"
```

---

## Plan 메타

### 1. Task 목록 (11개)

| # | 제목 | agent |
|---|---|---|
| 1 | Git 웹훅 Zod 계약 신설 (`automation-git-webhooks.types.ts`) | frontend-engineer |
| 2 | MSW git 웹훅 픽스처 + 핸들러 + 전역 등록 (★F-a) | frontend-engineer |
| 3 | API 함수 + 쿼리/뮤테이션 훅 (`GIT_WEBHOOKS_QUERY_KEY`) | frontend-engineer |
| 4 | `GitWebhookUrlModal` — URL 1회 노출 + origin prepend + 복사 폴백 | frontend-engineer |
| 5 | `GitWebhookRegisterDialog` — provider/secret 입력 + BLOCKER-0 봉인 | frontend-engineer |
| 6 | `GitWebhookSection` — 목록 4상태 + 등록 순차 + 삭제 + `mutation.reset()` | frontend-engineer |
| 7 | route 조립 — 형제 섹션 + h1 설명문 교체 + KDoc | frontend-engineer |
| 8 | ★ targetBranch 원자 4종 (parse ④ + omit ② + 호출부 ③ + 입력 UI) | frontend-engineer |
| 9 | FR15 PR_MERGED 무음 실패 경고 + MSW 합류 2곳 (★F-b② + ★F-b①) | frontend-engineer |
| 10 | D7 — MSW PR_MERGED 픽스처(EC16) + E2E 신규 스펙 | frontend-engineer |
| 11 | 완료마킹 — `automation.md` D6/D7 + 전사갭 1행 + 대시보드 재생성 (★E) | frontend-engineer |

### 2. Wave 계산 (depends-on + files 교집합)

| wave | task | 근거 |
|---|---|---|
| **W1** | **1 · 4 · 8** | deps `[]` · files 교집합 0 (types / UrlModal / rules.types+FormDialog) |
| **W2** | **2 · 5** | 2 deps [1] ✓ · 5 deps [1] ✓ · 교집합 0 |
| **W3** | **3** | deps [1,2] ✓ |
| **W4** | **6 · 9** | 6 deps [3,4,5] ✓ · 9 deps **[2,3]** ✓ · 6↔9 교집합 0. 9 는 files 가 8(W1 **완료**)과 겹치므로 지금 안전 |
| **W5** | **7** | deps [6] ✓ · 7↔9 은 route test 교집합 → **9(W4) 가 앞서야 한다**. W4<W5 라 충족 |
| **W6** | **10** | deps [7,8,9] ✓ |
| **W7** | **11** | deps [10] ✓ |

**longest path** = 1 → 2 → 3 → 6 → 7 → 10 → 11 = **7 wave**.
**직렬화 쌍 2건** (BLOCKER-1 수정으로 1→2건).
| 쌍 | 교집합 files | 배치 | 비용 |
|---|---|---|---|
| 8 ↔ 9 | `AutomationRuleFormDialog.tsx` · `.test.tsx` (2개) | W1 / W4 | 0 |
| **9 ↔ 7** | `routes/__tests__/projects.$projectKey.settings.automation.test.tsx` (1개) | **W4 / W5** | **0** |

**★ 9↔7 순서는 단순 배타가 아니라 방향이 있다 — 9 가 반드시 먼저다.** 9 가 route test 를 깨는 주체(`route:151`
무조건 마운트)이고 7 은 그 위에 단언만 얹기 때문이다. W4<W5 라 **이미 충족**돼 wave 재배치가 필요 없다.
→ **wave 수 7 불변**(BLOCKER-1 을 옵션 ①로 고쳤음에도 +1 wave 가 안 든 이유 = 9 와 7 이 원래부터 다른 wave 였다).
**순환 0** — 확인함.

### 3. 줄수

plan 파일 총 **1097줄**(`wc -l` 실측 — BLOCKER 3 · CONCERN 2 · FR7 판별자 재설계 반영 후).
신설분 — `## Plan`(`:283`)~`## 리뷰 결과`(`:1097`) = **814줄**.

> **★ 줄수 이력 — 이 항목 자체가 4번 틀렸다.** 초안에 `674` 로 **짐작** → `wc -l` 로 **898** 로 정정 →
> 리뷰 수정 착수 시 오케스트레이터는 `898줄`이라 지시했으나 실측 **901** → 수정 후 `1077` 로 적었다가
> **그것도 스테일**이었다(이후 2개 편집을 안 세고 적었다). 최종 재실측 **1095**.
> [[spec-stated-count-becomes-blindfold]] 가 경고하는 형태가 **같은 문단에서 반복**됐다.
> **교훈 — 줄수는 "마지막 편집 이후"에 뽑아야 한다. 편집 중간에 뽑은 값은 즉시 썩는다.**

### 4. ★ 오케스트레이터 지시 중 실측과 달랐던 것

**이번엔 반증 0건이다.** 지시 6항목을 전부 실측 대조했고 **전부 맞았다**.

| 지시 | 실측 |
|---|---|
| "스펙 471줄" | **471줄 정확**(`wc -l`) |
| "PR-D = 순수 프론트, 백엔드 REST 3매핑은 PR-C 완비, 백엔드 변경 0" | **정확** |
| "D1~D4 Maxi 확정 4건" | plan §Maxi 확정 표와 일치 |
| "등록 폼 = Dialog, 소유는 route 가 아니라 GitWebhookSection" | **정확하고 load-bearing** — 이게 route 상태 **6종 불변**(FR22-a)을 성립시켜 `route:58` KDoc 숫자를 안 건드려도 되게 만든다 |
| "targetBranch 원자 4종을 하나의 task 로" | **정확** — Task 8 로 묶음 |
| "MSW 로컬 setupServer 2곳 합류(★F)도 task" | **정확**. `grep -rn "setupServer" apps/web/src` 전수 재실행 → 합류 대상은 **정확히 2곳**(route test `:62` · FormDialog test `:42`). `AutomationRuleList.test.tsx:31` 은 로컬 서버지만 **형제 구조라 비대상**(스펙 판단 옳음) |

#### 4-b. ★ 리뷰 수정 라운드 (BLOCKER 3 · CONCERN 2) 에서 **또** 실측과 갈린 것 — **4건**

> 위 표의 `이번엔 반증 0건` 은 **plan 작성 시점**의 얘기였다. 리뷰 지시를 실측 대조하니 **다시 4건이 갈렸다.**
> 패턴이 반복된다 — **지시가 개수를 말하면 그 개수가 틀린다.**

| 리뷰 지시 | 실측 |
|---|---|
| "닫기 **4경로**(X·ESC·오버레이·onOpenChange)" | **3경로**다. `onOpenChange` 는 경로가 아니라 ①②③ 이 **전부 도달하는 깔때기**다. 가로채기 지점도 3개(`onEscapeKeyDown`·`onPointerDownOutside`·`handleOpenChange`). `onInteractOutside`/`onFocusOutside` 는 **grep 0건** + Root modal 기본값이라 비경로. **지시가 "믿지 말고 직접 세라"고 했고, 세어보니 지시가 틀렸다** |
| "승계하면 안 되는 결함 **2종**" | **3종**이다. ①select-all 부재 ②2단계 확인 부재 **③Overlay `data-testid` 부재**(`WebhookTokenModal.tsx:75`). ③ 없이는 ②의 **오버레이 경로가 jsdom 에서 테스트 불가**라 ②를 봉인해도 1/3 이 무가드로 남는다 |
| "BLOCKER-0 판별자가 **provider 를 고를 수 없어 못 돈다**" | **막히지 않는다.** provider 기본값이 `'GITHUB'` 이라 EC2(secret trim)는 **Select 를 한 번도 건드리지 않는다**. jsdom 제약은 실재하나(`hasPointerCapture` grep **3건**) 그건 **FR3 옵션 열거 테스트에만** 걸리고, 그것도 `vi.mock` shim 선례 **3건**(정본 `PatCreateForm.test.tsx:15-83`)으로 이미 해결돼 있다. **"선례가 없으면 보고하라"고 했는데 선례가 3건 있었다** |
| "FR7 판별자는 **코드베이스 선례 0건**이라 vacuous" | **선례는 있다**(`ProfileForm.tsx:84,111,116,121` 4곳 · 훅 테스트 `use-create-user.test.tsx:117-140`). 진짜 문제는 선례 부재가 아니라 **관측 불가**다 — `registerMutation` 은 Section **내부 지역변수**라 컴포넌트 테스트에서 `mutation.data` 단언을 **작성할 방법 자체가 없다**(`result.current.data` 히트는 **전부 `renderHook`**). vacuous 를 넘어 **미작성 가능**이었다 → 파생 배선으로 재설계해 **구조적 load-bearing** 으로 전환 |

**★ 다만 "내 지시가 8번 틀렸다"는 자기평가 자체가 이번엔 과잉교정 위험이다.** 스펙(3차 개정)이 이미 그 8건을 **전부 흡수해 정정**했고, 나는 스펙 인용 **14항목을 실측 재대조해 반증 0건**을 확인했다(§실측 재대조 표). → **이제 의심 대상은 지시가 아니라 "스펙이 안 말한 것"이다.** 그래서 §실측 재대조 표에 **스펙 미기재 1건**(route `:135` h1 = `'자동화'` — `'자동화 룰'` 부분문자열 미포함이라 11 단언 안전)을 추가로 실측해 FR1 근거를 보강했다.

### 5. ~~task 11개 = 10 초과 → Maxi 에게 PR 분할 문의 필요~~ → **★ Maxi 확정 = 1안 (현행 유지, 1 PR / 11 task / 7 wave)**

> **✅ 종결됐다 — 아래 3안 비교는 그 결정의 근거 기록이며, 다시 묻지 마라.**
> Maxi 가 **1안(현행 유지, 분할 없음)** 으로 확정했다. task 11개 · wave 7 그대로 간다.
> (오케스트레이터 권고는 2안이었으나 **Maxi 판단이 우선**이다 — `automation BC 7/7` 을 한 번에 닫는 값을 택했다.)

**#273(automation 직전 PR)이 9 task 였고 이건 11 task / 7 wave 다.** 사유는 이 PR 이 **독립 덩어리 3개**를 한 PR 에 담고 있기 때문이다(plan §정찰 결과 3 — `UI 덩어리가 2개다`).

| 덩어리 | task | 독립성 |
|---|---|---|
| **A. D6 Git 웹훅 UI** | 1·2·3·4·5·6·7 | `git_webhooks` 리소스. 신규 파일 위주 |
| **B. targetBranch 원자 4종** | 8 (+9 는 A·B 교차) | `automation_rules.trigger_config` JSONB. **다른 화면·다른 테이블** |
| **C. D7 + 완료마킹** | 10·11 | A·B 둘 다 필요 |

**분할 가능성 판정(실측).**
- **B 는 A 없이 단독 머지 가능하다** — Task 8 의 deps 는 `[]` 이고 files 4개가 A 와 **교집합 0**이다. ★A 의 원자성은 **task 8 내부**에서 이미 보장되므로 분할이 그걸 깨지 않는다.
- **단 Task 9(FR15)는 A·B 를 교차한다** — 폼(B 영역 파일)이 웹훅 목록 쿼리(A 산출물)를 구독한다. → **B 를 먼저 빼면 9 는 A 쪽 PR 로 따라간다**(files 겹침은 순차 머지라 무해).
- **C 는 쪼갤 수 없다** — ★E 의 `automation BC 7/7` 마킹이 D6·D7 **둘 다** 완료를 전제한다.

**옵션 3안 (Maxi 판단 — 각 trade-off 1줄).**
1. **현행 유지 (1 PR / 11 task / 7 wave)** — 리뷰 폭이 크고 게이트 2 부하가 높지만 `automation BC 7/7` 을 **한 번에** 닫는다.
2. **2 PR 분할 — [B: targetBranch 4종] → [A+C: 웹훅 UI + D7 + 마킹]** — B 는 4파일/1 task 라 리뷰가 가볍고 ★A 원자성도 유지되지만, **PR 2개 = 게이트 2회**이고 B 단독 PR 은 사용자 가치가 안 보인다(입력 UI 만 생기고 발화 경로 확인 불가).
3. **2 PR 분할 — [A: 웹훅 UI(1~7)] → [B+C: targetBranch + D7 + 마킹]** — A 가 신규 파일 위주라 리뷰가 깔끔하고 D6 를 먼저 닫지만, **★F-b② FormDialog MSW 합류가 A 에 있고 FR15 는 B 에 있어** 두 PR 이 같은 파일을 순차로 건드린다(#278 [[migration-vnumber-concurrent-branch-collision]] 류 충돌은 아니나 rebase 필요).

> **오케스트레이터 권고 없음 — 이건 Maxi 결정 사항이다.** 다만 실측상 **2안이 기술적으로 가장 깨끗**하다(교집합 0 · deps [] · 원자성 보존).

### 6. 전 task 공통 규율 (implementer 프롬프트에 반드시 실을 것)

- **`pnpm install` 절대 금지 · `pnpm exec` / `pnpm --filter` 금지.** worktree 의 `node_modules` 는 main 심볼릭 링크다 — 여기서 install 하면 main `.modules.yaml` 을 **재오염**시킨다(이번 세션에 3일치 손상을 복구했다). 검증은 **`node_modules/.bin/*` 직접 호출**.
- **병렬 dispatch pre-commit race** — implementer 는 **자기 `files` 만 `git add`**. **`git add -A` 금지**([[parallel-dispatch-precommit-hook-race]] — files 교집합 0 이어도 **index 공유**로 발생, #273 에서 4회차 재발).
- **`git stash` 금지** — worktree 가 stash 를 **공유**해 형제 task 산출물을 삼킨다([[subagent-git-stash-worktree-shared-collision]]).
- **`eslint --fix` 도 자기 파일만.** `ktlintFormat` 은 **무관**(순수 프론트).
- **`rm`/`mv` 는 Bash deny** → 임시파일 정리는 `git clean -f <path>`.
- **파이프 금지** — `cmd | head; echo $?` 는 head 의 0 을 뱉는다. `> /tmp/x 2>&1; echo "EXIT=$?"`([[zsh-pipestatus-1-based-false-green]]).
- **[[parallel-review-mutation-contaminates-peers]]** — 동시 dispatch 중 남의 뮤테이션을 자기 결함으로 오탐하지 마라. `git show HEAD:<path>` 대조가 유일 탐지 수단.
- **Vitest `userEvent.type` 은 긴 문자열에서 timeout** → `{ delay: null }`([[vitest-usertype-long-string-timeout]]). Task 5·6 의 20자 secret 입력이 대상.
- **`getByRole` 은 Playwright/RTL 모두 strict** — 신규 testid 는 `git-webhook-*` 접두 강제(FR20).

### 7. 최종 검증 (전 wave 완료 후, 메인 루프)

```
cd apps/web && node_modules/.bin/eslint src > /tmp/lint.txt 2>&1; echo "EXIT=$?"
cd apps/web && node_modules/.bin/tsc -p tsconfig.app.json --noEmit > /tmp/tsc.txt 2>&1; echo "EXIT=$?"
cd apps/web && node_modules/.bin/vitest run > /tmp/unit.txt 2>&1; echo "EXIT=$?"
cd apps/web && node_modules/.bin/vite build > /tmp/build.txt 2>&1; echo "EXIT=$?"
bash scripts/verify-master-plan.sh > /tmp/vmp.txt 2>&1; echo "EXIT=$?"
```
- **★ 유닛 기준선은 착수 시 실측한다.** #273 spec 의 `6933`(#271 기준)도 `automation.md:116` 의 `유닛 6979`(#273 실적)도 **스테일**이다 — PR-C(#278)가 백엔드 PR 이면서 프론트 5파일을 함께 배포하고 **완료 블록에 프론트 유닛 수를 남기지 않았다**. **W1 dispatch 전에 `vitest run` 1회로 기준선을 찍어라.**
- **CI typecheck 는 `tsconfig.app.json`** — 로컬 기본과 다르다([[ci-typecheck-tsconfig-app-vs-local.md]]).

## 리뷰 결과 (← /bts-review-plan 채움)
