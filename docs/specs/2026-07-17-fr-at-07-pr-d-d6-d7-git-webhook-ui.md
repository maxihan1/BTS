<!-- FR-AT-07 PR-D D6/D7 Git 웹훅 UI 스펙 — 등록/1회노출/목록/삭제 + targetBranch 입력, 백엔드 #278(PR-C) 계약 위 순수 프론트 (3차 개정 — 배치 A·secret trim 금지 봉인 + h2 회귀/MSW 로컬 setupServer 2곳 BLOCKER 해소) -->

# FR-AT-07 PR-D D6/D7 — Git 웹훅 등록 UI 스펙

> 날짜. 2026-07-17 | BC. automation | type. ui | 선행. #278(PR-C 백엔드) | 배치 결정. **옵션 A — 기존 설정 페이지의 형제 섹션 (Maxi 확정)** | 개정. 3차

## 개요

Git 호스팅(GitHub/GitLab)이 PR 머지를 BTS에 통지할 인바운드 웹훅을 **등록·확인·삭제**하는 UI. 그리고 PR_MERGED 트리거 규칙이 특정 브랜치에만 발화하도록 하는 **`targetBranch` 입력 필드**. 백엔드는 #278(PR-C)로 완결됐고 PR-D는 그 3 엔드포인트를 소비만 한다.

**순수 프론트엔드.** 백엔드(엔드포인트·DTO·권한·에러코드·상한)는 #278로 완결. 신규 백엔드/스키마/마이그레이션/ADR 0.

---

### ★★ BLOCKER-0 — secret 에 `trim()` 을 절대 하지 않는다 (이 스펙에서 가장 중요한 한 줄)

백엔드 KDoc 이 못박았다(`GitWebhookRegistrationService.kt:180-181`, verbatim).

> `**trim 하지 않는다** — provider 의 HMAC 은 secret 바이트열 그대로를 쓰므로 서버가 값을 손대면`
> `서명이 영원히 불일치한다. 앞뒤 공백까지 포함해 사용자가 provider 에 붙여넣은 값 그대로 보관한다.`

**1차 초안 자신이 오염원이었다.** targetBranch 에 `trim()` 을 **5회** 못박아 놓고(:110·:198·:222·:223·:289 — 2차에서 FR17·§API 표·EC13·EC14 로 이동) secret 에는 침묵했다. 구현자가 두 필드를 나란히 읽으면 secret 에도 trim 하는 것이 가장 자연스러운 오답이다.

**그 오답이 만드는 사고의 모양.**
1. 등록은 **201 성공**한다. 목록도 **정상**이다. 화면상 아무 이상이 없다.
2. 그러나 서버가 보관한 secret 과 사용자가 provider 에 붙여넣은 secret 의 **바이트열이 다르다**(앞뒤 공백만큼).
3. → **모든 인바운드 서명 검증이 영구 실패**한다. 룰이 영원히 발화하지 않는다.
4. MSW 는 서명을 검증하지 않고 E2E 는 실서명을 태우지 않으므로 **prod 에서만** 드러난다.
5. secret 은 응답에 없고(`GitWebhookDtos.kt:42` — `[secret] 은 응답에 **포함하지 않는다**`) **수정 엔드포인트가 0건**이라 **진단도 복구도 불가능**하다. 삭제 후 재등록만이 방법이다.

→ **FR4 · FR5 · EC2 · §완료 기준**에 각각 봉인했다. targetBranch 의 trim 언급 **4곳 중 3곳**에 **"targetBranch 에만 해당 — secret 은 정반대"** 경고를 병기했다 — **FR17** · **§API targetBranch 수용표** · **EC13**. 나머지 1곳인 **EC14**는 미병기이나, 문언 자체가 `targetBranch 의 trim+omit 은` 으로 대상을 **한정**하고 바로 윗줄 EC13 이 경고를 달고 있어 오독 위험이 없다. (**"전부 병기했다"고 쓰면 거짓이다** — 개수·범위 표기는 실측대로.)

---

**★1 — 배치 확정 = 옵션 A (Maxi 확정).** 기존 `/projects/$projectKey/settings/automation` 페이지에 `AutomationRuleList` 의 **형제 섹션**으로 추가한다. **신규 라우트 0 / `router.ts` 무변경.** 선례 3건(#269·#271·#273)이 전부 `**라우터 변경 0**`(`automation.md:116`)을 성과로 기록한 것과 일치한다.

> **D6 정본 문구와의 글자상 어긋남은 문구를 몰래 바꿔서 해소하지 않는다.** `automation.md:127` 은 `- [ ] D6. 프론트 UI — Webhook URL 생성 **페이지**` 이고 `:143` 도 `D6(Webhook URL 생성 페이지)` 로 반복한다. 옵션 A 는 "페이지"가 아니라 "섹션"이다. → **정본 문구는 그대로 두고, D6/D7 완료 블록에 배치 사유를 명시**해 해소한다(§완료 기준). 이유는 두 가지다. ① 정본 문구를 조용히 고치면 "왜 페이지가 아니게 됐는가"라는 결정 근거가 소실된다 ② 옵션 B(신규 라우트)는 사이드바가 없어(설정 라우트 11개 전부 링크 0건) **URL 직접입력 외 도달 경로가 없는 페이지**가 되고, 진입점 문제는 FR-UX-06 관심사라 이 PR 에서 해결 불가하다.

**★2 — targetBranch 원자적 변경은 3종이 아니라 4종.** 착수 지시는 ①serialize 시그니처 ②omit 로직 ③호출부 전달을 원자적으로 하라 했으나, **④ `parseTriggerConfig` 역직렬화가 빠지면 ①②③은 사고를 막는 게 아니라 새로 만든다**. 현상유지(`JSON.stringify(base)` 통째 보존 → 유실 0)보다 **나쁜** 상태가 된다. **FR16 이 FR17 의 전제조건**이며, 4종은 **하나의 원자 단위**로 완료 기준에 묶인다(§완료 기준 ★A).

**★3 — 재발급 엔드포인트 0건.** `GitWebhookRegistrationController` HTTP 매핑을 직접 계수해 판정했다 — `@PostMapping`(:77) · `@GetMapping`(:100) · `@DeleteMapping("/{id}")`(:121) 정확히 3개. PUT·PATCH·rotate 0건. 토큰은 `sha256(rawToken)` 만 저장해 복원 불가(`GitWebhookDtos.kt:36-40`). **회전 = 삭제 후 재등록뿐**이며 이 사실을 UI 가 사용자에게 알려야 한다(FR11).

## 사용자 시나리오 (Given-When-Then)

### S1. Git 웹훅 등록 (성공 + URL 1회 노출)

- **Given**. `MANAGE_AUTOMATION` 권한이 있고 provider 설정 화면에 붙여넣을 URL 이 필요하다.
- **When**. "웹훅 등록" CTA → **등록 Dialog** → provider 선택(GitHub/GitLab) → secret 입력(마스킹) → "등록".
- **Then**. 201 직후 **등록 Dialog 를 먼저 닫고** URL 모달을 연다(모달 겹침 금지, FR2). 표시 대상은 bare token 이 아니라 **`${window.location.origin}${webhookUrl}` 로 조립한 완전한 URL** 이다(FR9). 복사 버튼은 이 완전 URL 을 복사한다. `role="alert"` 경고 — "이 URL 은 지금 한 번만 표시됩니다. 창을 닫으면 다시 확인할 수 없습니다."

> **★ 왜 token 이 아니라 URL 을 보여주는가.** 백엔드 KDoc 이 load-bearing 산출물을 못박았다 — `사용자가 이 응답의 [webhookUrl] 을 놓치면 웹훅을 지우고 다시 등록하는 것 말고는 방법이 없다`(`GitWebhookDtos.kt:40`). 사용자가 GitHub/GitLab 설정 화면에 붙여넣는 것은 완전 URL 이다. bare token 을 복사시키면 **사용자에게 쓸모없는 값을 복사시키는 것**이다. `token` 과 `webhookUrl` 은 **둘 다 원문 토큰을 품으므로**(후자는 경로 끝에) 노출 취급은 동일하게 엄격하다.

### S2. 등록 — secret 은 사용자 공급이며 **원문 그대로** 전송된다

- **Given**. 사용자는 GitHub/GitLab 웹훅 설정에 넣을 secret 을 **스스로 정해** 양쪽에 같은 값을 넣어야 한다.
- **When**. secret 칸에 붙여넣고 "등록".
- **Then**. 폼은 secret 을 **입력받는다**(생성 버튼 없음). 전송 페이로드는 **사용자 입력 원문 그대로** — `trim()`·정규화·인코딩 변환 **전부 금지**(BLOCKER-0). 도움말 — "provider 웹훅 설정에 입력할 값과 동일해야 합니다. 서명 검증에 쓰입니다. 앞뒤 공백도 값의 일부로 저장됩니다."
- **입력 칸은 마스킹**된다(`type="password"`, FR5). 응답에 secret 이 없으므로(`GitWebhookDtos.kt:42`) 등록 후 다시 확인할 수 없다.

### S3. 등록 — secret 클라이언트 검증 (16~4096자, **비대칭 술어**)

- **When**. secret 에 15자 이하 또는 공백만 입력하고 "등록".
- **Then**. 요청 없이 폼 인라인 에러. 서버 고정 문구를 미러 — `"secret 은 공백이 아닌 16자 이상 4096자 이하 문자열이어야 합니다."`(`GitWebhookRegistrationController.kt:219`).
- **★ 술어가 비대칭이다.** 서버는 **blank 판정은 trim 기준**(`secret.isBlank()` :185)이고 **길이 판정은 원문 기준**(`secret.length` :188 — untrimmed)이다. 클라도 정확히 같은 비대칭을 복제한다(FR4).

> **★ 클라 검증은 유일 방어선이 아니다.** automation 모듈에는 Bean Validation **provider 자체가 없어** `@field:NotBlank` 류가 런타임 무동작이다 — `붙이면 "검증이 있다"고 착각하게 만드는 가짜 가드가 된다`(`GitWebhookDtos.kt:14-20`). 검증은 서비스가 수행한다. 프론트 사전검증은 UX 용이며 **400 `AUTOMATION_GIT_WEBHOOK_SECRET_INVALID` 응답 경로를 반드시 함께 처리**한다(EC1).

### S4. 등록 — 같은 provider 가 이미 있으면 경고 (비차단)

- **Given**. 목록에 이미 `GITHUB` 웹훅이 1건 등록돼 있다.
- **When**. 등록 Dialog 에서 provider 를 `GITHUB` 로 선택한다.
- **Then**. **비차단 경고** — "이 프로젝트에 이미 GitHub 웹훅이 등록돼 있습니다. 추가로 등록하면 목록에서 두 건을 구분할 수 있는 정보가 provider 와 등록일시뿐입니다." 등록 버튼은 **막지 않는다**(FR13).

> **★ 왜 차단이 아니라 경고인가 (Maxi 확정 = 중복등록 B).** 백엔드에 `(project_key, provider)` 중복 가드가 **없다** — 중복 등록은 201 로 성공한다. 프론트가 임의로 차단하면 **서버가 허용하는 것을 클라가 막는 fail-closed 불일치**가 된다(이 코드베이스의 관례는 반대 방향 — 서버가 진짜 검증자, S3 ★). 그리고 목록 DTO 에 `name`·`label` 이 없어(S6) **중복 등록된 두 건은 사용자가 식별할 수 없다** — 삭제 시 어느 것을 지우는지 모른다. 이 구조적 결함을 경고로 알리되, 백엔드 가드는 **후속 등재**한다(§후속 등재).

### S5. 삭제 — 확인 모달이 **복구 불가**를 명시한다

- **When**. 목록 행의 "삭제" → 확인 모달 → "삭제".
- **Then**. 204 후 목록 invalidate. 확인 문구는 **"되돌릴 수 없습니다"로 부족**하다 — 재발급이 없으므로(★3) 삭제는 곧 **provider 설정 화면의 URL 교체 작업**을 수반한다. 문구는 두 가지를 반드시 말한다(FR14).
  1. **"이 URL 이 provider 에 붙어 있다면 연동이 끊기고 복구할 수 없습니다."**
  2. **"다시 쓰려면 새로 등록하고 provider 설정의 URL 도 함께 교체해야 합니다."**

> **★ 왜 이 문구가 load-bearing 인가 (Maxi 확정 = 중복등록 B 의 짝).** 목록 행이 provider·createdAt 으로만 식별되므로(S6), 중복 등록된 두 건 중 **살아 있는 쪽을 지울 위험**이 실재한다. 그리고 지우면 재발급이 없어 되돌릴 수 없다. 문구가 이 결과를 말하지 않으면 사용자는 "잘못 지워도 다시 만들면 되겠지"로 오해한다 — 다시 만들 수는 있지만 **URL 이 달라져 provider 설정을 손대야 한다**는 게 핵심이다.

### S6. 목록 조회

- **When**. 섹션 진입.
- **Then**. 등록된 웹훅이 **provider · createdAt** 으로 식별되어 표시된다. `name`·`label` 필드는 **DTO 에 없다**(`GitWebhookSummaryResponse` = `id`·`provider`·`createdAt`·`createdBy` 4필드뿐, `GitWebhookDtos.kt:95-100`). token·secret 은 목록에 영원히 없다.

### S7. 목록 — 4상태 전부 (loading / error / empty / list)

- **Then**. 형제 `AutomationRuleList.tsx:405-427` 과 **동형 4상태**. 특히 **error 의 403 분기**가 없으면 `MANAGE_AUTOMATION` 없는 사용자에게 **웹훅 섹션이 백지**가 된다 — 위쪽 룰 섹션은 "권한이 없습니다."를 보여주는데 아래는 아무것도 없어 **"웹훅 0개"로 오해**한다(FR12).
- 빈 상태는 정상이다 — "등록된 Git 웹훅이 없습니다." + 등록 CTA.

### S8. 재발급 없음 — 사용자 고지

- **Given**. 사용자가 URL 을 분실했거나 회전하려 한다.
- **Then**. 목록 섹션에 **상시 도움말** — "URL 을 분실했거나 새로 발급하려면 삭제 후 다시 등록하세요. 기존 URL 은 즉시 무효가 되므로 provider 설정도 함께 갱신해야 합니다."

> **★ 왜 에러 조건부가 아니라 상시인가.** 재발급 부재는 에러가 아니라 **구조적 사실**이라 사용자를 에러로 안내할 통로가 없다. 사용자가 "재발급 버튼이 어디 있지"를 찾는 시점은 URL 을 이미 잃은 뒤이고, 그때 화면에 아무 설명이 없으면 막다른 길이 된다. #273 의 S8(상시 도움말 승격) 선례 동형.

### S9. PR_MERGED 룰인데 프로젝트에 Git 웹훅이 0건 — 무음 실패 경고

- **Given**. 이 프로젝트에 Git 웹훅이 **하나도 등록돼 있지 않다**.
- **When**. 룰 폼에서 트리거를 **PR_MERGED** 로 선택한다.
- **Then**. 트리거 설정 영역에 안내 — **"이 프로젝트에 Git 웹훅이 없어 이 룰은 발화하지 않습니다."** + 등록 섹션으로 유도하는 문구. **저장은 막지 않는다**(FR15).

> **★ 근거 = 발화 경로 자체가 없다 (실측).** `GitWebhookService.kt:132` 가 PR_MERGED 룰 조회의 **유일한 진입점**이다.
> ```kotlin
> automationRuleRepository.findEnabledByProjectAndTriggerType(webhook.projectKey, TriggerType.PR_MERGED)
> ```
> 이 호출은 **인바운드 웹훅 수신 핸들러 안**에 있다 — `webhook` 이 있어야 도달한다. `TriggerType.PR_MERGED` 를 **`backend/` 의 main 소스 한정**(테스트 제외)으로 grep 하면 **정확히 5건**이고, 그 중 룰을 **조회**하는 곳은 `:132` 하나뿐이다(`:190` 은 같은 흐름의 하류 enqueue, `RuleConflictAnalyzer.kt:256` 은 발화가 아닌 정적 분석, `TriggerConfig.kt:18,49` 는 검증). **⚠️ "백엔드 전체 5건"은 거짓이다** — `grep -rn "TriggerType.PR_MERGED" backend` 는 테스트 포함 **39건**이다. 5 는 main 소스 한정 수치이며, 결론(조회 진입점이 `:132` 유일)은 그대로 유효하다. → **웹훅 0건 = PR_MERGED 룰이 발화할 경로가 물리적으로 없다.** 사용자는 룰을 저장하고 "동작하겠지"라고 믿는데 영원히 아무 일도 안 일어난다.
>
> **★ 신규 엔드포인트 0 · 폼 오픈 시 배경 refetch 1회.** 룰 폼과 웹훅 섹션은 **같은 페이지·같은 projectKey** 다. 웹훅 목록 쿼리(`GIT_WEBHOOKS_QUERY_KEY(projectKey)`)가 이미 마운트돼 있으므로 폼은 같은 queryKey 를 `useQuery` 로 구독하기만 하면 된다. 판정은 `data?.length === 0`.
>
> **⚠️ "추가 API 호출 0" 이라고 쓰면 거짓이다(실측 반증).** ① `main.tsx:17-22` 의 QueryClient 는 `{ queries: { retry: false }, mutations: { retry: false } }` 뿐 — **`staleTime` 미설정 = 기본값 0** ② `grep -n "staleTime" apps/web/src/api/useAutomationRules.ts` → **0건**(automation 계열은 어느 쿼리도 staleTime 을 걸지 않는다. 리포 전체로는 `useSessionsQuery.ts:21` `staleTime: 30_000` 등 실재한다). → **staleTime 0 + `refetchOnMount` 기본 `true`** 이므로 새 observer(폼의 `useQuery`)가 마운트되면 **배경 refetch 가 1회 발화**한다(TanStack Query v5 문서화 동작).
>
> **캐시 공유로 실제 얻는 것은 '호출 0' 이 아니다** — **① 중복 인플라이트 dedupe**(같은 queryKey 는 한 번만 나감) **② 캐시 데이터 즉시 표시**(refetch 완료를 기다리지 않고 판정 가능)다. 렌더 블로킹이 없어 **UX 영향은 없고** 판정식 `data?.length === 0` 도 **그대로 유효**하다. 다만 **스펙이 못 지킬 약속을 하면 안 된다**(NFR1 의 `mutation.reset()` 과 같은 규율).

### S10. targetBranch 설정 (PR_MERGED 규칙)

- **Given**. 규칙 폼에서 트리거를 PR_MERGED 로 선택했다.
- **When**. "대상 브랜치"에 `release/1.2` 입력 후 저장.
- **Then**. `triggerConfig` 에 `{"targetBranch":"release/1.2"}` 가 저장되고 해당 브랜치 머지에만 발화한다.

### S11. targetBranch 해제 (미입력 = 전 브랜치)

- **When**. 입력을 **비우고** 저장.
- **Then**. `targetBranch` 키가 **생략**된 채 저장된다(빈 문자열 전송 금지). 키 부재 = 전 브랜치 발화(`TriggerConfig.kt:102` `?: return`).

### S12. targetBranch 편집 라운드트립 (④의 핵심 경로)

- **Given**. `targetBranch: 'develop'` 인 PR_MERGED 규칙이 있다.
- **When**. 편집으로 열어 **이름만** 고쳐 저장.
- **Then**. 폼에 `develop` 이 **로드되어 있고**, 저장 후에도 `targetBranch: 'develop'` 이 유지된다. 미지 키(`futureKey`)도 함께 보존된다.

> **★ 이 시나리오가 없으면 ①②③이 사고를 만든다.** `parseTriggerConfig`(④)가 없으면 폼 입력이 빈 값으로 시작 → omit(②)이 base 에서 targetBranch 제거 → 빈 값이라 재삽입 안 됨 → **유실**. `automation-rules.types.ts:222-228` ★ 문단이 경고한 "브랜치 한정 룰이 전 브랜치 발화로 **조용히 승격**"이 바로 이것이다(SET_FIX_VERSIONS 규칙이면 릴리즈 브랜치만 겨냥한 의도가 전 PR 에 적용된다).

### S13. 등록/삭제 — 권한 없음 (403)

- **Then**. 에러 토스트 "권한이 없습니다."(`AUTOMATION_ACCESS_DENIED`). 기존 automation UI 의 403 fail-closed 관례 동일 — 버튼 사전 게이팅 없음(§제약 4).

### S14. 미인증 (401)

- **Then**. `apiFetch` 의 자동 refresh + 1회 retry 경로를 그대로 탄다(신규 처리 없음).

## 기능 요구사항 (FR)

> FR 번호는 **이 스펙 내부의 요구사항 번호**다. 제품 FR(`FR-AT-07`)과 다른 층위다.

| # | 요구사항 |
|---|---|
| FR0 | **배치 — 형제 섹션 (옵션 A, Maxi 확정)**. Git 웹훅은 규칙이 아니라 **별도 리소스**(자체 테이블·자체 컨트롤러·`git-webhooks` 경로)이므로 `AutomationRuleList` **안에 넣지 않는다** — AT-05/AT-06 이 진입점을 `onViewHistory`/`onImportYaml` prop 으로 단 것은 그것들이 **룰 스코프** 기능이기 때문이다. 신규 `GitWebhookSection` 을 route(`projects.$projectKey.settings.automation.tsx:133` `<div className="p-8 space-y-6 max-w-2xl">`) 안에 `AutomationRuleList` 의 **형제**로 추가한다. 이 페이지 최초의 **2섹션 레이아웃**이다. **신규 라우트 0 · `router.ts` 무변경.**<br>**⚠️ "최초의 2섹션화"를 "h2 도 최초"로 읽지 마라.** 룰 섹션 h2 는 **이미 존재한다**(`AutomationRuleList.tsx:380` `<h2 className="text-base font-semibold">{labels.heading}</h2>`, `:23` `heading: '자동화 룰'`). 최초인 것은 **route 가 두 개의 섹션 컴포넌트를 나란히 두는 구조**이지 h2 라는 요소가 아니다. **PR-D 가 신규로 만드는 h2 는 `'Git 웹훅'` 하나뿐**이다(FR1). |
| FR1 | **★ h1 설명문 교체 — h2 는 만들지 않는다**(FR0 의 파생). 변경 대상은 **`route:136-138` 의 h1 아래 설명문 한 덩어리뿐**이다. 현재 문구 `이슈 이벤트나 예약 일정에 따라 자동으로 실행될 트리거 규칙을 관리합니다.` 는 **룰 전용**이라 웹훅 섹션이 붙으면 거짓이 된다 → 두 섹션을 함께 포괄하는 문구로 교체한다.<br>**교체 후 문자열(확정 — 구현자가 발명하지 말 것)**. `이슈 이벤트·예약 일정·PR 머지에 따라 자동으로 실행될 규칙과, 규칙을 발화시키는 웹훅 연동을 관리합니다.`<br>· **안전성 실측** — 현재 문구(`자동으로 실행될` / `트리거 규칙을 관리`)에 의존하는 테스트는 **0건**(`grep -rn "자동으로 실행될\|트리거 규칙을 관리" apps/web/ --include=*.ts --include=*.tsx` → route 파일 자신만 hit, EXIT=0). 교체가 깨는 것은 없다.<br>· **`'Git 웹훅'` 을 이 문구에 넣지 않는다** — `<p>` 라 `getByRole('heading')` 은 애초에 안 잡지만, `getByText('Git 웹훅')` 이 신규 h2 와 중복 매치될 표면을 애초에 만들지 않는다(`웹훅 연동` 으로 우회). 같은 이유로 `'자동화 룰'` 도 넣지 않는다.<br>· **판별자** — 교체 후 `grep -c '트리거 규칙을 관리' apps/web/src/routes/projects.$projectKey.settings.automation.tsx` = **0**.<br>**(a) 룰 h2 는 이미 있고 무변경이다.** `AutomationRuleList.tsx:380` 의 `'자동화 룰'` h2 를 **route 에 새로 도입하지 않는다**(도입하면 아래 (c)로 회귀). `AutomationRuleList.tsx` 는 이 FR 의 변경 대상이 **아니다**.<br>**(b) 신규 h2 는 `'Git 웹훅'` 하나뿐**이며 소유자는 `GitWebhookSection` 이다(룰 섹션이 자기 h2 를 소유하는 것과 대칭). 텍스트는 `'자동화 룰'` 과 **부분문자열로도 겹치지 않는다** — `'Git 웹훅'` 은 조건 충족.<br>**(c) ★ route 에 `'자동화 룰'` h2 를 추가하면 E2E 11개 단언이 즉사한다.** Playwright 의 `getByRole('heading', { name })` 은 **기본 substring 매칭**이라 같은 텍스트 h2 가 2개면 **strict mode 위반**으로 진입 단언이 전부 red 가 된다. **이는 PR-D 가 만드는 회귀이지 기존 결함이 아니다** — 판별자·전수 열거는 §제약 11. |
| FR2 | **★ 등록 폼 = Dialog, 소유자는 `GitWebhookSection`**(인라인 아님). 같은 페이지 선례를 따른다 — `AutomationRuleList` → CTA 버튼 → `AutomationRuleFormDialog`. **단 Dialog open 상태를 route 가 아니라 `GitWebhookSection` 이 소유**한다 → **route 의 상태 개수는 6종 불변**(FR22 가 실행 가능해지는 전제). 등록 성공(201) 시 **등록 Dialog 를 먼저 닫고 그 다음 URL 모달을 연다** — 모달 겹침 금지. 순차 가드 선례 = `projects.$projectKey.settings.automation.tsx:162` 의 **"토큰 우선"** 패턴 — `conflicts={webhookToken === null ? conflicts : null}` (한쪽이 null 이 될 때까지 다른 쪽을 렌더하지 않는다). 동형으로 `open={urlModalPayload === null && formOpen}` 류 가드를 쓰거나, 201 핸들러에서 `setFormOpen(false)` → `setUrlPayload(...)` 순서를 보장한다. |
| FR3 | 등록 폼 — provider `<Select>` **2옵션 고정**(`GITHUB`/`GITLAB`). `GitProvider.kt:20-21` 이 정확히 2값이고 BITBUCKET 은 없다. **대문자 원문 그대로 전송**(소문자 변환 금지) — `이 enum 의 값 이름([GITHUB]/[GITLAB])이 그 상수 문자열과 다르면 \`enum.name\` 을 넘기는 호출자의 모든 서명 검증이 fail-closed(거부)로 무너진다`(`GitProvider.kt:16-17`). DB 제약도 동형(`ck_git_webhooks_provider CHECK (provider IN ('GITHUB','GITLAB'))`). |
| FR4 | **★★ secret 입력 — `trim()` 절대 금지 + 검증 술어 비대칭**(BLOCKER-0). <br>**(a) 전송값은 사용자 입력 원문 그대로다.** `trim()`·`normalize()`·공백 제거 **전부 금지**. 사유 — provider 의 HMAC 이 secret **바이트열 그대로**를 쓰므로 앞뒤 공백 한 칸이라도 손대면 **서명이 영원히 불일치**한다(`GitWebhookRegistrationService.kt:180-181` KDoc). 등록은 201 로 성공하고 목록도 정상이라 **prod 인바운드에서만** 드러나며, 수정 엔드포인트가 0건이라 **복구 불가**다.<br>**(b) 검증 술어는 서버와 정확히 같은 비대칭을 복제한다.** 서버는 `secret.isBlank()`(`:185` — **trim 기준** 판정)와 `secret.length`(`:188` — **untrimmed 원문 기준**)를 쓴다. → 클라도 **blank 판정은 `secret.trim() === ''` / 길이 판정은 `secret.length`**. **클라가 `secret.trim().length` 로 길이를 재면 안 된다** — `"               a"`(공백 15자 + `a`, 원문 16자)를 서버는 **통과**시키는데 클라는 **거부**해 불일치가 난다.<br>**(c) 판정에 trim 을 쓰는 것과 전송값에 trim 을 쓰는 것은 다르다.** `(b)` 의 blank 판정은 **읽기 전용 검사**이며 `state` 나 요청 본문을 바꾸지 않는다. 폼 상태에 `trim()` 결과를 되쓰기(write-back)하는 순간 `(a)` 위반이다. |
| FR5 | **★ secret 필드 마스킹 + autofill 차단**. <br>**(a) `type="password"`.** 근거 = **같은 성격 값의 in-repo 선례** — `apps/web/src/components/admin/WebhookForm.tsx:224` 가 아웃바운드 웹훅 **서명 Secret** 필드(라벨 `:16` `secret: '서명 Secret'`)에 `type="password"` 를 쓴다. 이 코드베이스는 **"웹훅 서명 secret 입력 = 마스킹"을 이미 결정**해 놨고 PR-D 만 승계 안 했다.<br>**(b) `autoComplete="off"` 필수.** `type="password"` 만 붙이고 autoComplete 를 비우면 **Chrome 이 사용자의 BTS 로그인 비밀번호를 secret 칸에 autofill** 할 수 있다. 눈치 못 채고 등록하면 **본인 계정 비밀번호가 웹훅 secret 으로 저장되고, 사용자가 같은 값을 provider 에 붙여넣어야 하므로 GitHub 설정에도 평문으로 들어간다.** 역방향으로는 브라우저 비밀번호 매니저가 이 secret 을 BTS 계정 비밀번호로 저장 제안한다. 선례 3건 — **같은 파일** `AutomationRuleFormDialog.tsx:380`·`:407`·`:568` 전부 `autoComplete="off"`.<br>**(c) ★ 입력 템플릿(FR19)의 cron 분기는 평문이다.** `AutomationRuleFormDialog.tsx:377` 이 `type="text"`(`:375` 는 `<input` 여는 줄) — **템플릿을 그대로 복제하면 공유 HMAC secret 이 화면에 평문으로 찍힌다.** cron 분기에서 가져올 것은 **레이아웃·`autoComplete="off"`(:380)·`font-mono`** 이고, `type` 은 **반드시 `password` 로 바꾼다**. (targetBranch 입력은 비밀값이 아니므로 `type="text"` 그대로 — FR19.) |
| FR6 | `POST .../git-webhooks` → 201. 헤더에 `'X-XSRF-TOKEN': readXsrfToken()` **필수** — 형제 mutation 3종(`automation-rules.ts:103,132,153`)과 동일. `apiPost` 가 아니라 `apiFetch` + `throwIfNotOk` + 수동 `.parse` 관례(`automation-rules.ts:44-49,100-107`). |
| FR7 | **★ `mutation.reset()` — NFR1 이 못 지키는 약속을 지우는 FR**. 등록을 `useMutation` 으로 하면 성공 후 **`mutation.data`(원문 토큰·webhookUrl)와 `mutation.variables`(secret)가 전역 MutationCache 에 남는다.** 호출부가 React state 만 `null` 로 비워도 이 둘은 그대로다 — 컴포넌트가 마운트된 동안 무기한, 언마운트 후에도 기본 `gcTime` 5분. → **URL 모달을 닫는 경로에서 `registerMutation.reset()` 을 반드시 호출**한다(state `null` 되돌리기와 **같은 핸들러 안에서**). 4경로(X·ESC·오버레이·`onOpenChange`) 전부 이 핸들러로 수렴하므로 호출 지점은 1곳이다(FR8). |
| FR8 | **URL 1회 노출 모달** — 신규 `GitWebhookUrlModal`. `WebhookTokenModal.tsx` 를 **재사용하지 않고 구조를 복제**한다(§제약 3). 표시·복사 페이로드는 **`${window.location.origin}${webhookUrl}`**(FR9). 닫기 **4경로 전부** 2단계 확인 — X · ESC(`onEscapeKeyDown` preventDefault) · 오버레이(`onPointerDownOutside` preventDefault) · `onOpenChange`. 하나라도 빠지면 URL **영구 분실**(#273 EC7 선례). 4경로는 **단일 `handleClose` 로 수렴**한다 — state null + `mutation.reset()`(FR7)을 한 곳에서 수행하기 위해. |
| FR9 | **origin prepend** — 백엔드가 `webhookUrl` 을 `/api/v1/webhooks/git/<token>` 형태의 **origin 없는 절대경로**로 내려준다(`GitWebhookDtos.kt:61,77`). KDoc 이 프론트에 명시 위임 — `호출하는 화면이 자신의 origin 을 앞에 붙여 완전한 URL 을 만든다`(`:66-68`). prepend 누락 시 사용자가 반쪽 URL 을 붙여넣고 웹훅이 조용히 동작하지 않는다(EC8). |
| FR10 | **복사 폴백 — `select-all` 필수 + 실패 문구**. <br>**(a)** `navigator.clipboard.writeText` reject(비보안 컨텍스트/권한 거부) 시 `'복사에 실패했습니다. 직접 선택해 복사해 주세요.'`(`WebhookTokenModal.tsx:16` 문구 재사용).<br>**(b) ★ URL `<code>` 블록에 `select-all` 클래스를 반드시 넣는다.** 없으면 폴백 안내가 **반쪽짜리**다 — 사용자가 `break-all` 로 3~4줄 wrap 된 긴 URL 을 드래그로 **정확히 전부** 선택해야 하고, 부분 선택 → 반쪽 URL → **무음 실패**(재발급이 없으니 삭제 후 재등록). **같은 BC 에 선례가 있다** — `AutomationYamlImportDialog.tsx:142` `<code className="block break-all rounded bg-muted px-2 py-1 text-xs font-mono select-all">{token.token}</code>`. **반면 복제 원본인 `WebhookTokenModal.tsx:89` 의 `<code className="mt-4 block break-all rounded bg-muted px-3 py-2 text-sm font-mono">` 에는 `select-all` 이 없다** — 원본을 그대로 복제하면 이 결함을 승계한다. `AutomationYamlImportDialog` 쪽을 따른다. |
| FR11 | **재발급 부재 고지**(S8) — 목록 섹션 상시 도움말. 재발급/수정 버튼을 만들지 않는 데서 그치지 않고, **왜 없는지와 대안(삭제 후 재등록 + provider 설정 갱신)을 명시**한다. |
| FR12 | **★ 목록 4상태 전부**(S7). 형제 `AutomationRuleList.tsx:405-427` 과 동형. <br>① **loading**(`:405-409`) — `role="status"` + `aria-label` <br>② **error**(`:411-417`) — **403 분기 필수**. `extractAutomationRuleErrorCode(error) === 'AUTOMATION_ACCESS_DENIED' ? labels.accessDenied : labels.genericError` <br>③ **empty**(`:419-421`) — `ruleList.length === 0` <br>④ **list**(`:423+`) <br>**②의 403 분기를 빠뜨리면 `MANAGE_AUTOMATION` 없는 사용자에게 섹션이 백지**가 되어 "웹훅 0개"로 오해한다 — 바로 위 룰 섹션은 "권한이 없습니다."를 보여주므로 화면이 자기모순에 빠진다. |
| FR13 | **중복 provider 경고 — 등록 폼 내 비차단 안내**(S4, Maxi 확정 = 중복등록 B). 목록 쿼리 데이터에 선택된 provider 가 **이미 있으면** 등록 Dialog 에 경고 문구를 렌더한다. **등록 버튼 disabled 금지**(서버가 허용하는 것을 클라가 막지 않는다). **신규 엔드포인트 0** — 같은 섹션의 목록 쿼리를 그대로 읽는다(폼이 별도 observer 를 만들면 FR15 와 동일하게 배경 refetch 1회 — S9 ★). **목록이 `isLoading`/`isError` 이면 중복 경고를 내지 않는다**(FR15·EC21 동형 — **못 읽은 것과 0건은 다르다**. 못 읽은 목록으로 "이미 있다/없다"를 단정하면 거짓이며, 403 사용자에겐 판단 근거 자체가 없다). 백엔드 `(project_key, provider)` 중복 가드와 목록 행 식별자는 **후속 등재**(§후속 등재). |
| FR14 | **삭제 + 확인 모달 문구 강화**(S5). `DELETE .../git-webhooks/{id}` → 204(파싱 없이 `throwIfNotOk` 만, `automation-rules.ts:150-156` 동형) + `X-XSRF-TOKEN`. 확인 모달은 같은 파일의 file-local `DeleteConfirmDialog`(`AutomationRuleList.tsx:122-166`) 구조 복제 — props 4개, `if (x === null) return null`, 취소 `variant="outline"` / 확인 `variant="destructive"`, 둘 다 `disabled={isPending}`. **문구는 `'삭제하면 되돌릴 수 없습니다.'` 로 부족**하다 → **"이 URL 이 provider 에 붙어 있다면 연동이 끊기고 복구할 수 없습니다. 다시 쓰려면 새로 등록하고 provider 설정의 URL 도 함께 교체해야 합니다."** |
| FR15 | **★ PR_MERGED 무음 실패 경고**(S9, Maxi 확정 = 무음실패 경고 A). 룰 폼에서 `triggerType === 'PR_MERGED'` 이고 이 프로젝트의 Git 웹훅이 **0건이면** 안내를 렌더한다 — "이 프로젝트에 Git 웹훅이 없어 이 룰은 발화하지 않습니다." + 등록 섹션 유도. **저장 차단 금지**(정보성). **신규 엔드포인트 0** — 같은 페이지에 이미 마운트된 `GIT_WEBHOOKS_QUERY_KEY(projectKey)` 쿼리를 `useQuery` 로 구독. 판정 = `data?.length === 0`. **⚠️ "추가 API 호출 0" 이 아니다** — staleTime 미설정(0) + `refetchOnMount` 기본 true 라 폼 오픈 시 **배경 refetch 1회**가 나간다(S9 ★ 실측). 캐시가 즉시 표시되므로 렌더 블로킹·UX 영향은 없다. **`isLoading`/`isError` 일 때는 경고를 내지 않는다** — 목록을 못 읽은 것과 0건인 것은 다르며, 403 사용자에게 "웹훅이 없다"고 단정하면 거짓이다. |
| FR16 | **★ `parseTriggerConfig` 역직렬화 — FR17 의 전제조건**(★2, ④). `ParsedTriggerConfig`(`AutomationRuleFormDialog.tsx:151-154`)에 `targetBranch: string` 추가 + `parseTriggerConfig`(`:160-176`)가 `obj['targetBranch']` 를 읽도록 확장 + **`parseTriggerConfig` 의 return 3곳** — `:164` **폴백**(`return { cron: '', fields: [] }` — 비객체) · `:171` **성공 경로**(`return { cron, fields }`) · `:174` **catch 폴백**(`return { cron: '', fields: [] }`) — + `initialConfig` 폴백(`:459` `{ cron: '', fields: [] }`) + 폼 `defaultValues`(`:482-486`). **이 FR 없이 FR17 을 하면 targetBranch 가 유실된다**(S12 ★). **FR16~FR19 는 하나의 원자 단위**다(§완료 기준 ★A). |
| FR17 | **serialize omit** — `serializeTriggerConfig` 시그니처(`automation-rules.types.ts:235-239`)의 `config` 에 `targetBranch?: string` 추가 + PR_MERGED 를 fallthrough 그룹(`:250-254`)에서 **떼어내** 독립 case 로. `ISSUE_UPDATED`(`:244-249`)가 정확한 선례 — `omitManagedKeys(base, ['targetBranch'])` 후 값 있으면 병합, 없으면 rest 만. **여기서 `trim()` 은 targetBranch 에만 해당한다 — ⚠️ secret 은 정반대이며 trim 을 절대 하지 않는다(FR4·BLOCKER-0).** targetBranch 에 trim 이 필요한 이유는 배열의 `length > 0` 에 대응하는 문자열 술어가 `trim() !== ''` 이고, 공백만 든 입력을 그대로 실어보내면 백엔드 `asText().isBlank()` 가 400 을 내기 때문이다(`TriggerConfig.kt:104-108`). KDoc `:214-228`(현재 "PR-D 에서 함께 도입한다" **미래형**)도 함께 재작성 — 안 고치면 코드와 모순되는 주석이 남는다. |
| FR18 | **호출부 전달 — 3홉**. `onValid`(`AutomationRuleFormDialog.tsx:511-526`) → `buildSharedSavePayload`(`:279-291`) → `serializeTriggerConfig`(`:291`). 헬퍼는 이미 **7 위치인자**이고 `cron` 과 `targetBranch` 가 **둘 다 string** 이라 순서를 바꿔 넣어도 타입 에러가 안 난다(eslint `max-params` 룰 없음 — grep 0건). → **`{cron, fields, targetBranch}` 객체 1개로 묶어 전달**한다(serialize 의 `config` 인자 형태와 동형, 위치 혼동 불가). `automation-rules.types.test.ts:387` 의 주석 `호출부(AutomationRuleFormDialog:291)` 도 갱신 대상. |
| FR19 | **targetBranch 입력 UI** — `TriggerConfigFields`(`AutomationRuleFormDialog.tsx:348-436`)의 `return null`(`:435`) 앞에 `if (triggerType === 'PR_MERGED')` 분기 추가. cron 분기(`:369-392`)가 템플릿(RHF `register` 기반 단일 텍스트 입력) — **에러 표시 블록만 제거**(refine 없음 → 에러 채널 불필요), `type="text"`·`autoComplete="off"`(`:380`)·`font-mono` 유지. **⚠️ 이 템플릿을 secret 필드(FR5)에 복제할 때만 `type` 을 `password` 로 바꾼다 — targetBranch 는 비밀값이 아니므로 `text` 가 맞다.** 폼 스키마는 `targetBranch: z.string()` **한 줄만**(`formSchema` `:311-322`) — **`.refine` 금지**. 빈 값이 정당한 의미(전 브랜치)를 가지므로 cron 식 필수 검증을 복제하면 **틀린다**. testid 는 `automation-rule-target-branch-input`(`:379` `automation-rule-cron-input` 동형) — **`gitWebhook*`/`git-webhook-*` 접두사는 여기 적용 안 됨**(FR20 참조, targetBranch 는 automation-rule 폼 소속). |
| FR20 | **네임스페이스 — `gitWebhook*` / `git-webhook-*` 강제**(§제약 2). 스키마·타입·훅·파일명·testid 전부. 특히 testid — `webhook-token-copy-button`(`WebhookTokenModal.tsx:101`)·`webhook-token-close-button`(`:107`)이 **같은 라우트 페이지에 마운트**되므로 동일 testid 면 Playwright strict mode 위반으로 즉시 깨진다 → `git-webhook-url-copy-button`·`git-webhook-url-close-button` 등 **전면 분리**. mocks export 명 `webhookHandlers`(`webhook-handlers.ts:266` 정의 / `handlers.ts:56` import / `:138` spread — search-export-import BC 선점)도 충돌 → **`gitWebhookHandlers`**. |
| FR21 | **invalidate-only** — `GIT_WEBHOOKS_QUERY_KEY = (projectKey: string): [string, string] => ['automation-git-webhooks', projectKey]` 함수 헬퍼(`useAutomationRules.ts:32-35` `AUTOMATION_RULES_QUERY_KEY` 동형). 등록/삭제 onSuccess 는 invalidate 만. **setQueryData 금지**(NFR2). |
| FR22 | **route KDoc — 상태 개수는 6종 불변, 조립 목록은 갱신 필수**. <br>**(a) 상태 6종 불변.** `route:58` `- 상태 6종을 이 컴포넌트가 보유한다.` 는 실측 6개(`:87-92` — `dialogOpen`·`editingRule`·`webhookToken`·`conflicts`·`historyRule`·`yamlImportOpen`)와 일치한다. **FR2 가 등록 Dialog 소유를 `GitWebhookSection` 에 두므로 route 상태는 6종 그대로다** → 이 숫자는 **변경하지 않는다**. 만약 구현 중 route 에 상태를 추가하게 되면 그것은 FR2 위반이며, 그때는 숫자도 함께 고친다.<br>**(b) ★ 조립 목록은 반드시 갱신한다.** 두 줄 위 `route:55-56` 이 조립 컴포넌트를 **전수 열거**한다 — `헤더 + AutomationRuleList + AutomationRuleFormDialog + WebhookTokenModal + RuleConflictWarningModal + RuleExecutionHistoryDialog + AutomationYamlImportDialog 조립.` **FR0 이 `GitWebhookSection` 을 붙이는 순간 이 열거가 거짓이 된다** → `GitWebhookSection` · `GitWebhookUrlModal` 을 열거에 추가한다. <br>**(c) 왜 (b)를 명시하는가.** 이 스펙은 개수·문구 drift 를 집요하게 막는다(§제약 10 · ★E 의 `123`→`128` · §명세 전수 동기화 관례). **`:58` 의 숫자만 봉인하고 `:55-56` 의 열거를 침묵하면 비대칭**이며, 열거형 drift 는 카운트형 drift 와 달리 `verify-master-plan.sh` 가 **보지 않는다** — 스펙이 못박는 것 외에 가드가 없다. |

## 비기능 요구사항 (NFR)

| # | 요구사항 |
|---|---|
| NFR1 | **URL·secret 비영속 — 단, 정직하게 쓴다**. 원문 토큰이 `webhookUrl` **경로 안에 박혀 있으므로 URL 자체가 비밀**이다. **localStorage/sessionStorage/URL 쿼리/로그 기록 금지**(§1.18). 보유 위치는 **React 상태(메모리)** 와 **TanStack MutationCache** 두 곳이다. 모달 `onClose` 시 호출부가 **①state 를 `null` 로 되돌리고 ②`mutation.reset()` 을 호출**한다(FR7·FR8). <br>**★ "모달 닫으면 소멸"이라고만 쓰면 스펙이 눈가리개가 된다** — `mutation.reset()` 없이는 `mutation.data`(URL·토큰)와 `mutation.variables`(secret)가 **마운트 동안 무기한, 언마운트 후에도 기본 `gcTime` 5분** 남는다. devtools 미설치라 노출면은 작지만, **스펙이 못 지킬 약속을 하면 안 된다.** 소멸 대상은 **token 이 아니라 webhookUrl 과 secret** 임에 주의. |
| NFR2 | **setQueryData 구조적 불가**. 두 DTO 의 필드 집합이 교집합만 공유한다 — `CreateGitWebhookResponse` = `{id, provider, webhookUrl, token}`(`GitWebhookDtos.kt:50-55`) vs `GitWebhookSummaryResponse` = `{id, provider, createdAt, createdBy}`(`:95-100`). 등록 응답에 `createdAt`·`createdBy` 가 **없으므로** 목록 캐시에 밀어넣으면 두 필드가 undefined 가 되어 Zod 계약 위반 + 렌더 깨짐. → invalidate 강제(FR21 · [[mutation-setquerydata-partial-response-flicker]]). |
| NFR3 | **OCC 없음**. `GitWebhookSummaryResponse` 에 `version` 필드가 없다(`:95-100`) → **409 처리 불필요**. 수정 엔드포인트가 0건이라 동시 수정 충돌 자체가 성립하지 않는다. |
| NFR4 | **`webhookUrl` 에 `z.string().url()` 금지**. origin 없는 절대경로라 URL 검증을 **통과하지 못한다**(`GitWebhookDtos.kt:61,77`). `z.string()` 을 쓴다. |
| NFR5 | **`Instant` = ISO 문자열**. 같은 모듈 `AutomationRuleResponse.createdAt: Instant`(`AutomationRuleResponses.kt:63`)의 프론트 미러가 `z.string().datetime()`(`automation-rules.types.ts:88`)로 이미 통과 중 → 배열 직렬화(epoch array) 함정 없음. `createdAt: z.string().datetime()` 그대로. |
| NFR6 | **접근성**. provider 는 색상 단독 금지 — 텍스트 라벨 병기. 모달 본문이 URL+경고+안내 여러 덩어리이므로 `role="alert"` 는 **경고 영역을 묶어서** 준다(`RuleConflictWarningModal.tsx:95-96` 방식 — `스크린리더가 중복 announce하지 않도록`). Content 에 `data-testid` 부여(`RuleConflictWarningModal.tsx:88` 방식, `WebhookTokenModal` 은 미부여). secret 필드는 `type="password"` 여도 `<label>` 연결 필수(FR5). |

## API 인터페이스 (REST) — #278 확정, 프론트는 소비만

```
POST   /api/v1/projects/{projectKey}/automation/git-webhooks
       X-XSRF-TOKEN: <readXsrfToken()>
       body: { provider, secret }      ← secret 은 사용자 입력 원문 그대로 (trim 금지, FR4)
       → 201 CreateGitWebhookResponse   ← 원문 토큰 1회 동봉

GET    /api/v1/projects/{projectKey}/automation/git-webhooks
       → 200 GitWebhookSummaryResponse[]   ← bare 배열, token·secret 미포함

DELETE /api/v1/projects/{projectKey}/automation/git-webhooks/{id}
       X-XSRF-TOKEN: <readXsrfToken()>
       → 204 (소프트 삭제)
```

> **재발급·수정 매핑 0건.** 컨트롤러 매핑 직접 계수 — `@PostMapping`(:77)·`@GetMapping`(:100)·`@DeleteMapping("/{id}")`(:121) 정확히 3개(`GitWebhookRegistrationController.kt`). PUT·PATCH·rotate 0. **주의** — 동명의 `GitWebhookController` 는 **인바운드 수신기**(`@RequestMapping("/api/v1/webhooks/git")` :95)로 PR-D 소비 대상이 **아니다**. 등록 API 는 별도 클래스다.

### DTO → Zod 계약 (신규 `api/automation-git-webhooks.types.ts`)

```ts
// CreateGitWebhookRequest — 2필드뿐. name·targetBranch·enabled 없음(invent 금지)
{ provider: GitProvider, secret: string }   // secret = 원문 그대로 (FR4)

// CreateGitWebhookResponse (201)
{
  id: z.string().uuid(),
  provider: gitProviderSchema,
  webhookUrl: z.string(),        // ★ .url() 금지 — origin 없는 절대경로 (NFR4)
  token: z.string(),
}

// GitWebhookSummaryResponse (200 배열 원소)
{
  id: z.string().uuid(),
  provider: gitProviderSchema,
  createdAt: z.string().datetime(),
  createdBy: z.string().uuid(),
}

// gitProviderSchema
z.enum(['GITHUB', 'GITLAB'])     // 정확히 2값 (GitProvider.kt:20-21)
```

- 목록에 `token`·`secret`·`name`·`enabled`·`updatedAt`·`version` **전부 없다**. DTO KDoc — `**token·secret 관련 필드를 하나도 두지 않는다** … 필드가 없으면 나중에 누가 실수로 [GitWebhook] 전체를 직렬화 경로에 태우는 일이 타입 단계에서 막힌다`(`GitWebhookDtos.kt:84-88`). 프론트 Zod 도 같은 규율 — **없는 필드를 추가하지 않는다**.
- 명명은 기존 관례 — camelCase + `Schema` 접미사, 타입은 `z.infer` PascalCase 파생. 타입만 배럴 재수출(`automation-rules.ts:18-26` 동형), 스키마(값)는 재수출 안 함.

### 에러 코드 → UI 문구 (**핸들러 전수 열거 — 7개**)

errorCode 추출은 **기존 `extractAutomationRuleErrorCode`(`automation-rules.ts:236`) 재사용**(BC 공용, 신규 작성 금지).

`GitWebhookRegistrationController.kt` 의 `@ExceptionHandler` 를 전수 계수했다 — **6개가 아니라 7개**이고, `AUTOMATION_MALFORMED_REQUEST` 는 **2곳에서 나오며 detail 문구가 2종**이다.

| # | 핸들러 (선언 줄) | 예외 | HTTP | errorCode | 서버 detail (고정) | UI |
|---|---|---|---|---|---|---|
| 1 | `:178` | `AutomationForbiddenException` | 403 | `AUTOMATION_ACCESS_DENIED` | `"이 작업을 수행할 권한이 없습니다."`(`:188`) | 토스트 "권한이 없습니다." — **automation-rules 와 같은 코드**라 기존 분기가 그대로 통함(S13) |
| 2 | `:193` | `GitWebhookNotFoundException` | 404 | `AUTOMATION_GIT_WEBHOOK_NOT_FOUND` | `"Git 웹훅을 찾을 수 없습니다."`(`:201`) | 삭제 대상 없음/타 프로젝트 소속. 목록 invalidate 후 조용히 사라짐(EC10) |
| 3 | `:211` | `GitWebhookSecretInvalidException` | 400 | `AUTOMATION_GIT_WEBHOOK_SECRET_INVALID` | `"secret 은 공백이 아닌 16자 이상 4096자 이하 문자열이어야 합니다."`(`:219`) | 폼 인라인 에러. 서버 detail 그대로(S3) |
| 4 | `:224` | `ResponseStatusException` | 전파(주로 401) | `AUTOMATION_UNAUTHENTICATED` | `"인증이 필요합니다. 세션이 만료되었을 수 있습니다."`(`:233`) | `apiFetch` 자동 refresh 경로(S14) |
| 5 | `:238` | `MethodArgumentTypeMismatchException`<br>(경로 변수 `id` 가 UUID 형식 아님) | 400 | `AUTOMATION_MALFORMED_REQUEST` | **`"요청 파라미터 값이 올바르지 않습니다."`**(`:246`) | 서버 detail 그대로. 프론트가 id 를 목록에서만 가져오므로 정상 흐름에선 도달 불가 |
| 6 | `:251` | `HttpMessageNotReadableException`<br>(JSON 파손 **또는 provider 화이트리스트 밖**) | 400 | `AUTOMATION_MALFORMED_REQUEST` | **`"요청 본문이 유효하지 않습니다."`**(`:262`) | 서버 detail 그대로. provider Select 가 2옵션 고정(FR3)이라 정상 흐름에선 도달 불가 |
| 7 | `:273` | `Exception`(catch-all) | 500 | `AUTOMATION_INTERNAL_ERROR` | 일반 메시지 | 서버 detail 없으면 fallback. **암호화 키 미설정이 부팅이 아니라 여기로 떨어진다**(`:266-271` — `배포 후 스모크 점검을 health 가 아니라 실제 등록 호출로 해야 하는 이유`, [[use-time-validated-env-passes-boot-fails-on-use]]) |

> **★ 5번과 6번은 errorCode 가 같고 detail 이 다르다.** → 프론트는 **errorCode 로 분기하고 detail 은 그대로 표시**한다. detail 문자열로 분기하면 [[crossbc-failure-classification-typed-not-name]] 위반이며 #273 이 같은 이유로 분기를 제거한 선례가 있다.
>
> MSW ProblemDetail 형태는 `automation-rule-handlers.ts:32-59` `problemDetail()` 헬퍼 복제. `:29` — **`message` 필드 절대 금지 — 백엔드는 `detail` 필드를 사용한다**.

### targetBranch 백엔드 수용 형태 (4갈래 — 실측 일치)

`TriggerConfig.kt:101-109` `validatePrMerged` 전문 기준.

| 입력 | 결과 |
|---|---|
| 키 부재 | 통과 = 전 브랜치(`?: return` `:102`) |
| `null` | 통과 = 전 브랜치(`isNull → return` `:103`) |
| 빈 문자열 / **공백만** | **400**(`asText().isBlank()` `:104`) → 프론트 `trim()` 필수 (**⚠️ targetBranch 에만 해당 — secret 은 정반대, FR4**) |
| 비문자열 | **400**(`!isTextual` `:104`) |

> **★ 매칭은 정확 문자열 비교 — glob/정규식/대소문자 무시 미지원.** `GitWebhookService.kt:339-346` — `[triggerConfig] 의 targetBranch 가 [actualBranch] 와 일치하면 true. 미지정이면 전 브랜치 발화(true)`. `release/1.2` 와 `Release/1.2` 는 **다른 브랜치**다. **폼 설명문에 와일드카드를 암시하면 안 된다** — 사용자가 `release/*` 를 넣고 조용히 0건 발화한다. 문구는 `fieldsDescription`(`AutomationRuleFormDialog.tsx:53`) 문형을 따라 `'지정한 브랜치로 병합될 때만 발화합니다. 비워두면 모든 브랜치에 반응합니다.'`.

## 데이터 모델 변경

**없음.** 순수 프론트. 신규 마이그레이션 0, 백엔드 변경 0, ADR 0.

## 엣지 케이스

| # | 케이스 | 처리 |
|---|---|---|
| EC1 | secret 15자 이하/공백만 | 클라 선제 차단(S3). **서버 400 경로도 병행 처리** — 클라 검증이 유일 방어선이 아니고(Bean Validation provider 부재) 서버가 진짜 검증자 |
| EC2 | **★ secret 앞뒤 공백 보존** | **테스트 대상**. `secret = '  abcdefghijklmnop  '`(앞뒤 공백 포함)을 입력하고 등록 → **요청 본문의 `secret` 이 입력과 바이트 단위로 동일**함을 단언한다. 어느 계층에서든 `trim()` 이 끼면 red(BLOCKER-0 · FR4 · §완료 기준 ★B) |
| EC3 | **★ 공백 15자 + 문자 1자**(원문 16자) | **서버는 통과**시킨다 — blank 판정은 trim 기준(`:185`)이나 길이 판정은 **원문 기준**(`:188`)이기 때문. **클라도 통과시켜야 한다.** 클라가 `trim().length` 로 재면 원문 16자를 1자로 세어 **서버가 받는 값을 클라가 거부**하는 불일치가 난다(FR4-b) |
| EC4 | secret 4096자 초과 | 클라 선제 차단 + 400 경로. **길이는 원문 기준**(`secret.length`) |
| EC5 | 등록 중 재클릭 | `isPending` 으로 버튼 disabled |
| EC6 | URL 모달 닫은 뒤 | 복구 불가. 재등록만이 방법 — 이것이 4경로 2단계 확인(FR8)과 상시 도움말(FR11)의 존재 이유 |
| EC7 | 모달 열린 채 새로고침 | URL 소멸(NFR1 비영속의 필연적 대가). 재등록 안내 |
| EC8 | **origin prepend 누락 → 반쪽 URL** | 사용자가 `/api/v1/webhooks/git/<token>` 을 그대로 붙여넣으면 provider 가 호출할 호스트를 몰라 **웹훅이 조용히 동작하지 않는다**(에러도 안 남). 단위 테스트로 `origin` 포함을 못 박는다(§완료 기준 ★C) |
| EC9 | **복사 실패**(`clipboard.writeText` reject — 비보안 컨텍스트) | 실패 문구(FR10-a) + **`select-all` 로 전체 선택 가능**(FR10-b). **HTTPS 아니면 clipboard API 가 없으므로 load-bearing** |
| EC10 | 삭제 중 실패(404) | 이미 삭제됨 — invalidate 후 목록에서 사라짐. 404 를 에러로 시끄럽게 알리지 않는다(멱등적 귀결) |
| EC11 | 삭제 중 실패(403/500) | 에러 토스트 + 목록 유지(낙관적 제거 금지 — invalidate-only 라 자연 성립) |
| EC12 | 삭제 중 재클릭 | `isPending` disabled |
| EC13 | **targetBranch 빈 문자열 vs 미입력** | 미입력·공백만 → **키 omit**(S11). 빈 문자열 전송 시 **400**(`TriggerConfig.kt:104`). `trim() !== ''` 술어로 구분(FR17). **⚠️ 이 trim 은 targetBranch 전용 — secret 에 복제 금지(FR4)** |
| EC14 | **MSW 가 400 을 재현하지 않음** | MSW 핸들러는 `triggerConfig` 를 검증 없이 통과시킨다(`automation-rule-handlers.ts:214`) → `{"targetBranch":""}` 를 보내도 **MSW 에선 초록, 실서버에선 400**([[date-input-iso-instant-query-param]] 동일 기전). → targetBranch 의 trim+omit 은 **단위 테스트로** 못 박는다(MSW 경유 검증 불가) |
| EC15 | **MSW 가 서명을 검증하지 않음** | secret trim 사고(BLOCKER-0)를 **MSW 도 E2E 도 잡지 못한다** — 등록은 201, 목록은 정상. → EC2 를 **요청 본문 단위 단언**으로 못 박는 것이 유일한 가드(§완료 기준 ★B) |
| EC16 | **PR_MERGED 편집 모드 진입 불가** | MSW 픽스처에 PR_MERGED **0건**(`automation-rule-fixtures.ts:132,149` = ISSUE_CREATED·SCHEDULED뿐) → **④의 핵심 경로를 화면으로 밟을 수 없어 버그가 D7 눈검사를 통과한다**. `triggerConfig: JSON.stringify({targetBranch:'release/1.2'})` 를 가진 PR_MERGED 픽스처 추가가 **D7 의 선행조건**. `SEED_AUTOMATION_RULE_IDS`(`:73-76`) 확장 시 **RFC4122 v4 형식 필수**([[zod-v4-uuid-fixture-strictness]], `:72` 주석) |
| EC17 | targetBranch 에 와일드카드 입력 | 정확 문자열 비교라 **0건 발화**. 방어는 문구뿐(§API ★) — 프론트 검증은 하지 않는다(유효한 브랜치명 배제 위험) |
| EC18 | `createdBy` UUID 원문 노출 | 이름 조회 API 가 없다. `RuleConflictWarningModal.tsx:42-43` 선례가 같은 문제에서 UUID 노출을 **거부** — `UUID 원문을 노출하지 않고 "관련 규칙 N개"로 축약한다 (… 이름 조회를 하지 않으므로 UUID 나열은 사용자에게 무의미하다)`. → **`createdBy` 를 화면에 표시하지 않는다**(provider·createdAt 으로 식별, S6) |
| EC19 | 등록 직후 목록 미반영 | invalidate 로 refetch(FR21). MSW mock 은 stateful store 로 재현(§제약 6) |
| EC20 | 같은 provider 중복 등록 | 서버가 **허용**한다(중복 가드 없음). 비차단 경고만(FR13). 등록되면 목록에 provider·createdAt 만 다른 2행이 생겨 **식별이 어렵다** → 삭제 모달 문구가 위험을 명시(FR14) + 백엔드 가드는 후속(§후속 등재) |
| EC21 | 웹훅 목록 403/loading 중 PR_MERGED 선택 | 무음 실패 경고를 **내지 않는다**(FR15). "못 읽음"과 "0건"은 다르며, 403 사용자에게 "웹훅이 없다"고 단정하면 거짓이다 |

## 제약 조건

1. **순수 프론트**. `apps/web/**` 만 변경(+ `docs/plan/product/automation.md` 마킹 + `docs/progress.html` **재생성** — `scripts/build-dashboard.mjs:17` 의 `OUTPUT_PATH` 가 거기다. §완료 기준 ★E 마지막 체크박스가 요구하므로 **이 PR 은 반드시 그 파일도 바꾼다**. 열거에서 빠뜨리면 FR22-(c) 가 스스로 지목한 **열거형 drift** 와 같은 유형이 되고 verify 는 이걸 안 본다). 백엔드/DB/마이그레이션/ADR 0. **공유 인프라 변경 0**(#273 의 `client.ts` 같은 변경 불요 — 전부 JSON body).
2. **★ webhook 네임스페이스 5중 충돌 — `gitWebhook*` 강제**(FR20). 'webhook' 이 코드베이스에서 **5개**를 가리킨다. ①search-export-import 아웃바운드(`/api/v1/webhooks`, `webhook-handlers.ts`·`useWebhooks.ts`·`admin.webhooks*`) ②notification 디스패처(REST 미노출) ③automation **WEBHOOK 트리거** 토큰(`WebhookTokenModal.tsx`) ④Git 인바운드(`/api/v1/webhooks/git/{token}`) ⑤Git 등록 API(PR-D 소비 대상). **①과 ④가 경로 접두사를 공유하는데 BC 가 다르다.** 단 **MSW 경로 충돌은 실측 결과 없다** — ①은 `/api/v1/webhooks*`(`webhook-handlers.ts:100,126,156,188,227`), PR-D 는 `/api/v1/projects/:projectKey/automation/git-webhooks*` 라 접두사가 갈리고 등록 순서(`handlers.ts:138` vs `:147-148`)도 무해하다. **진짜 충돌은 testid 와 export 명**(FR20).
3. **모달은 34번째 복제 — 공용 추출 금지**. `components/ui/` 에 **Dialog 래퍼가 없다**(실측 목록 — avatar/button/card/dropdown-menu/form/input/label/select/sonner). `AutomationRuleList.tsx:109` 가 박제 — `DeleteConfirmDialog — radix-ui 직접 사용(components/ui에 Dialog 래퍼 부재, WebhookTokenModal.tsx 동형)`. <br>**★ 실측 = 33개 파일이다(1차 초안의 "4벌"은 틀렸다).** `grep -rn "DialogPrimitive.Content" apps/web/src` → **66행 / 33파일**(비테스트 66행 = 열고 닫는 33쌍). 1차 초안의 4벌은 **자기 눈에 들어온 4벌**이었다 — [[spec-stated-count-becomes-blindfold]]. <br>**숫자를 33으로 고치면 결론이 더 강해진다** — 33벌을 공용 추출하는 것은 폭발반경이 automation 을 한참 벗어나는 **전사 리팩토링**이며 이 PR 의 범위 밖이다(4벌로 남기면 후속 세션이 "4벌쯤이면 추출해볼 만하다"고 오판한다). PR-D 는 **34번째 복제**가 정답이다.
4. **권한 사전 게이팅 범위 밖**. `projectPermissionsSchema` 에 `MANAGE_AUTOMATION` 키 부재 + automation UI 게이팅 선례 0건 → 기존 관례대로 **런타임 403 fail-closed**([[ui-permission-gating-needs-summary-api-exposure]]). #273 과 동일 판단. **단 403 을 화면에 렌더하는 것은 범위 안이다**(FR12 의 error 4상태 — 게이팅과 다른 문제). 후속 FR 후보로 기록.
5. **nav/사이드바 범위 밖**. **사이드바 자체가 없다** — 프로젝트 설정 라우트 11개 전부 링크 0건(URL 직접입력 전용). 진입점 문제는 **FR-UX-06 관심사**. E2E 는 선례대로 URL 직접 `goto`(`automation-yaml-gitops.spec.ts:35-36,83-87`).
6. **MSW stateful store** — store 는 handlers 가 아니라 **fixtures 파일이 소유**(단일 진실 출처, `automation-rule-fixtures.ts:167-193`). `let` + 재할당식 reset(`clear()` 아님), `import.meta.env.MODE !== 'test'` 일 때만 자동 시드(`:195-199`). **전역 setup 은 automation store 를 리셋하지 않으므로**(`src/test/setup.ts:21-27` 은 issue/favorite 2개만) 핸들러 테스트가 자체 `setupServer` + reset + SCENARIO_KEY 전 키 삭제를 세운다(`automation-rule-handlers.test.ts:26-36`). 시나리오는 localStorage 플래그(`msw:automation-git-webhook:*`) — `globalThis.localStorage?.` optional chaining 필수.
7. **★ MSW 핸들러 등록은 2층이다 — 전역 `handlers.ts` 만으로는 부족하다**(BLOCKER-2). `onUnhandledRequest: 'error'` 라 미등록 경로는 **즉시 실패**(조용한 통과 아님)인데, 등록 지점이 **전역 1곳 + 로컬 `setupServer` 2곳**으로 갈린다. **셋 다 해야 한다.**<br>**(a) 전역 — `src/mocks/handlers.ts` 에 `gitWebhookHandlers` spread 추가**(FR20 의 명명). 전역 서버(`src/test/server.ts:5`)와 전역 setup(`src/test/setup.ts:20`)이 이 배열을 쓰므로 **전역 배열을 소비하는 테스트는 자동으로 딸려 온다** — 아래 (c) 포함.<br>**(b) ★★ 로컬 `setupServer` 2곳에 합류 — 빠뜨리면 두 파일이 통째로 red.** 두 파일은 전역 `handlers` 를 **쓰지 않고** 자체 서버를 띄우므로 **(a) 의 효력이 전혀 미치지 않는다.**<br>  ① **`apps/web/src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx`** — `:62` `const server = setupServer(...automationRuleHandlers, ...automationExecutionHandlers)` / `:64` `server.listen({ onUnhandledRequest: 'error' })`. **FR0 이 이 route 에 `GitWebhookSection` 을 마운트**하므로 섹션의 목록 쿼리가 `GET .../git-webhooks` 를 쏜다 → 미합류 시 unhandled → **파일 전체 red**.<br>  ② **`apps/web/src/components/automation/AutomationRuleFormDialog.test.tsx`** — `:42` `const server = setupServer(...automationRuleHandlers, ...projectMemberHandlers)` / `:44` `onUnhandledRequest: 'error'`. **FR15 가 이 폼 안에 `useQuery`(웹훅 목록 구독)를 넣으므로** 동일하게 발화 → **파일 전체 red**. ★B 의 A1~A5 5단언이 전부 이 파일에 들어가므로 **이 합류 없이는 핵심 판별자가 실행조차 안 된다**.<br>  → 두 파일 모두 `setupServer(..., ...gitWebhookHandlers)` 로 **합류시키는 것이 명시적 작업**이다(§완료 기준 ★F).<br>**(c) 갱신 불요 2파일 — 단, 1차·2차 초안이 적은 사유는 거짓이었다.**<br>  · `src/mocks/handlers.test.ts` — 전역 `handlers` 배열을 소비(`:6` `setupServer(...handlers)`)하므로 **(a) 로 자동 충족**. 내용은 issue-tracking `/api/v1/issues` 전용이고 **핸들러 개수/배열 단언 0건**.<br>  · **★ `src/mocks/__tests__/handlers.integration.test.ts` 는 실재한다(76줄).** 1차·2차 초안이 `**존재하지 않는다**` 고 **두 번** 단언했으나 **거짓**이다 — `apps/web/src/mocks/__tests__/handlers.integration.test.ts`. 이 역시 `:6` `setupServer(...handlers)` 로 전역 배열을 소비하므로 **결론(갱신 불요)은 살아남지만 근거가 틀렸다**. 내용은 `/api/v1/issues`(`:17`) · `/api/v1/workflow-schemes`(`:30,38,45`) · `/api/v1/issue-types`(`:58,66`) 를 두드릴 뿐이고 **핸들러 배열 개수를 세는 단언은 0건**이다(`:63` `toHaveLength(5)` 는 **issue-type 5종**이지 핸들러 수가 아니다).<br>  · **★ 이 거짓 단언이 BLOCKER-2 를 숨겼다.** 오염원은 **디렉토리 맹점** — `src/mocks/*.test.ts` 만 보고 **`src/mocks/__tests__/` 를 보지 않았다**. 같은 맹점이 `routes/__tests__/` 의 (b)① 도 시야에서 지웠다. **`grep -rn "setupServer" apps/web/src` 전수**가 유일한 탐지 수단이다([[spec-stated-count-becomes-blindfold]] — 개수가 아니라 **행렬**을 세라).
8. **DESIGN.md 는 이 화면을 지배하지 않는다**. `grep -c amber DESIGN.md`(**리포 루트**) = **0** — 경고색 토큰이 없다. 오히려 `:57` `경고만 할 때는 muted-foreground를 우선 검토한다` · `:59` `**임의 색상 추가 금지** — 위 토큰 외 색이 필요할 경우 이 문서에 신규 토큰을 먼저 등록한 뒤 사용한다`. 그런데 기존 모달들의 `text-amber-800 dark:text-amber-200`(`WebhookTokenModal.tsx:84` 등)은 **§59 위반 상태로 코드에서만 자란 미등재 관례**다. DESIGN.md 는 스테일하다 — `:7` `본 문서는 **PR #11 (FR-AU-09 로그인 폼 UI)** 구현에 필요한 토큰만 우선 정의한다` · `:7` `라이트 모드가 기본값이며 본 PR에서 의도적으로 다크 모드를 미지원한다`(그러나 모달 전부 `dark:` 사용) · `:236` `모달 = shadow-lg`(그러나 실제 모달들은 `shadow-xl`). → **스타일 정본은 DESIGN.md 가 아니라 코드 선례**. amber 를 쓰려면 §59 대로 같은 PR 에서 토큰을 등재할지 여부가 **Maxi 판단**(갈림길, 본 스펙은 선례 추종 + 미등재 사실 명시를 전제).
9. **E2E 는 CI 에서 안 돈다**. `.github/workflows/frontend-ci.yml` 잡 3개뿐 — lint(`:45`)·typecheck(`:65`)·test(`:87`). `playwright`/`e2e` 문자열 **0건**. → D7 은 **로컬 규율**이며 자동 게이트가 없다. 통과 증거를 PR 본문에 첨부한다([[ui-pr-defer-e2e-regression-latent]]).
10. **`automation BC 7/7` ≠ `automation BC 완료`**. §측정 가능한 완료 기준의 ★E 참조. 두 표현을 섞어 쓰지 않는다.
11. **★ 룰 h2 무변경은 E2E 11개 단언의 전제다 — 전수 열거**(FR1-c). `AutomationRuleList.tsx:380` 의 `'자동화 룰'` h2 와 **같은 텍스트의 h2 를 하나라도 더 만들면** `getByRole('heading', { name })` 의 **substring 매칭**이 2개를 잡아 **strict mode 위반**이 난다. 아래 **11개 단언 / 6스펙 전부**가 여기에 의존한다(실측 — `grep -rn "getByRole('heading'" apps/web/e2e`).<br>
    | 스펙 파일 | 라벨 정의 | 진입 단언 (줄) | 건수 |
    |---|---|---|---|
    | `automation-rules.spec.ts` | `:34` `heading: '자동화 룰'` | `:131`·`:155`·`:182`·`:211` | **4** |
    | `automation-conditions.spec.ts` | `:46` | `:94`·`:223` | **2** |
    | `automation-conflict-warning.spec.ts` | `:37` | `:85`·`:115` | **2** |
    | `automation-actions.spec.ts` | `:40` | `:102` | **1** |
    | `automation-yaml-gitops.spec.ts` | `:44` | `:86` | **1** |
    | `automation-execution-history.spec.ts` | `:46` `pageHeading: '자동화 룰'` | `:114` | **1** |
    | | | **합계** | **11** |

    - **판별자** — 신규 h2 텍스트를 정한 뒤 `page.getByRole('heading', { name: '자동화 룰' })` 가 **여전히 1개만 매치**하는지 확인한다. Playwright 는 strict mode 위반 시 `strict mode violation: ... resolved to 2 elements` 로 실패하므로 **★D 의 기존 E2E 6건 통과**가 이 판별자를 겸한다.
    - `'Git 웹훅'` 은 `'자동화 룰'` 을 부분문자열로 포함하지 않으므로 **조건 충족**. 다른 텍스트를 고르면 이 표로 재검증한다.
    - **이 11개는 PR-D 가 h2 를 잘못 도입할 때만 깨진다** — 현재는 전부 초록이며 기존 결함이 아니다([[ui-pr-defer-e2e-regression-latent]] 와 성격이 다르다).

## 후속 등재 (이 PR 범위 밖 — 별도 이슈/FR 후보)

1. **`(project_key, provider)` 중복 등록 가드** — 백엔드. 현재 중복이 201 로 성공한다(EC20). PR-D 는 프론트 경고만(FR13, Maxi 확정 = 중복등록 B).
2. **목록 행 식별자** — `GitWebhookSummaryResponse` 에 `name`/`label` 또는 **토큰 prefix**(앞 6~8자) 추가. 현재 provider·createdAt 뿐이라 중복 등록 시 **어느 것을 지우는지 사용자가 알 수 없다**(S4 ★ · FR14). 토큰 prefix 는 `sha256` 저장이라 **원문 prefix 를 별도 컬럼으로 보관해야** 하므로 스키마 변경 동반 — 설계 필요.
3. **`MANAGE_AUTOMATION` 권한 요약 API 노출** — `projectPermissionsSchema` 에 키 추가 → UI 사전 게이팅(§제약 4, #273 도 동일하게 후속으로 남김).
4. **§NFR 측정표 5행** — 백엔드 측정이 필요해 순수 프론트 PR 로 채울 수 없다(★E).

## 측정 가능한 완료 기준

- [ ] `pnpm lint` · `pnpm typecheck`(CI 는 `tsconfig.app.json`) 0 에러
- [ ] `pnpm test` — 신규 단위 테스트 통과 + **기존 회귀 0**. **기준선은 착수 시 실측한다** — #273 spec 이 쓴 `6933`(#271 기준)도, `automation.md:116` 의 `유닛 6979`(#273 실적)도 **스테일**이다. PR-C(#278)가 백엔드 PR 이면서 프론트 5파일(PR_MERGED 트리거 UI + `automation-rules.types.test.ts` 3테스트)을 함께 배포했고 완료 블록에 프론트 유닛 수를 남기지 않았다.
- [ ] `pnpm build` 성공

### ★A — targetBranch 4종은 **하나의 원자 단위**다

FR16(parse ④) · FR17(serialize omit ②) · FR18(호출부 ③) · FR19(입력 UI)는 **부분 구현이 금지**된다. 특히 **FR16 없이 FR17 만 하면 현상유지보다 나쁘다** — 지금은 `JSON.stringify(base)`(`automation-rules.types.ts:253-254`)로 통째 보존이라 유실이 **0**인데, omit 만 넣으면 편집할 때마다 targetBranch 가 **유실**된다. 아래 판별자 5단언이 **전부 초록**이어야 이 단위가 완료된다.

### ★B — 작동하는 판별자 설계 (1차 지시의 판별자는 무효였다)

> **1차 지시가 준 판별자 — "기존 테스트 4개가 갱신 없이 초록이면 ③ 누락" — 는 작동하지 않는다.** 실측으로 4가지가 전부 틀렸다.
> - **(a) 테스트는 4개가 아니라 3개다.** `grep -n "it('PR_MERGED" apps/web/src/api/automation-rules.types.test.ts` → `:349`·`:392`·`:401` **3건**.
> - **(b) ③ 누락 시 `:392` 가 빨개져** '갱신 없이 초록'이라는 전제 자체가 성립하지 않는다.
> - **(c) 초록으로 남는 `:349`·`:401` 은 vacuous 다** — base 가 없어 omit 이 무동작이라 **원래 판별력이 0**이다.
> - **(d) 결정적으로, `AutomationRuleFormDialog.test.tsx` 에 PR_MERGED 가 0건이다**(`grep -c "PR_MERGED"` → **0**). **③④를 잡을 테스트가 코드베이스에 아예 없다.** serialize 단위 테스트는 호출부를 볼 수 없으므로 원리적으로 못 잡는다.

→ **다이얼로그 레벨 라운드트립 5단언을 신설**한다. `AutomationRuleFormDialog.test.tsx` 에 PR_MERGED describe 를 추가하고, **각 단언이 어느 누락을 잡는지 1:1 로 매핑**한다.

| 단언 | 시나리오 | 기대 | **이 단언이 잡는 누락** | 없으면 벌어지는 일 |
|---|---|---|---|---|
| **A1** | `editingRule.triggerConfig = {targetBranch:'develop', futureKey:'x'}` 로 렌더 | 입력에 `'develop'` **로드됨** | **FR16(④ parse) 누락** | parse 미구현이면 입력이 빈 값 → red |
| **A2** | A1 상태에서 **이름만** 고쳐 저장 | `JSON.parse(capturedBody.triggerConfig)` = `{targetBranch:'develop', futureKey:'x'}` | **FR16+FR18(④+③) 동시** | **핵심 회귀 가드.** parse 나 호출부 어느 쪽이 빠져도 targetBranch 유실 → red |
| **A3** | 입력을 `'release/1.2'` 로 바꿔 저장 | `{targetBranch:'release/1.2', futureKey:'x'}` | **FR18(③) 단독 — 양성 대조군** | A2 만 있으면 "그냥 base 통째 보존"으로도 초록이 난다. A3 가 **새 값이 실제로 흐르는지**를 증명 |
| **A4** | 입력을 **비우고** 저장 | `{futureKey:'x'}` (**targetBranch 키 부재**) | **FR17(② omit) 단독** | **★ 이 단언이 없으면 `omitManagedKeys` 를 통째로 지워도 전 스위트가 초록이다.** A1~A3 는 값이 있는 경로만 밟으므로 omit 을 **한 번도 실행하지 않는다** — [[guard-handler-matrix-blindfold]]. **이것이 이 PR 의 핵심 가드다** |
| **A5** | `triggerType = 'ISSUE_CREATED'` 로 렌더 | targetBranch 입력 **부재**(음성 단언) | **FR19 과도발화** | 분기 조건이 틀려 전 트리거에 입력이 뜨는 것을 차단(`:121` 음성 단언 스타일 확장) |

- [ ] **A1~A5 5단언 전부 통과**(위 표)
- [ ] **★ 판별력 검증 — mutation 으로 확인**. A4 를 믿으려면 **`omitManagedKeys(base, ['targetBranch'])` 호출을 일부러 지웠을 때 A4 가 red 가 되는지** 실제로 확인한다(기준선 전체 초록 선확인 후). 초록이면 A4 가 vacuous 다([[verify-logic-vs-verify-guard]] — load-bearing 불변식엔 mutation 필수).
- [ ] `automation-rules.types.test.ts:392-399` **재작성 완료** — 기대값이 `{extraKey:'keep'}` 로 바뀌고 제목·선행주석(`:386-391`)도 함께 갱신. **이 테스트가 갱신 없이 초록이면 ②(omit)가 누락된 것이다.** (단 이것만으로는 부족 — 호출부를 못 보므로 A1~A5 가 필수.)

### ★B2 — secret trim 판별자 (BLOCKER-0)

- [ ] **EC2 판별자 — 요청 본문 바이트 단위 단언**. `secret` 에 `'  abcdefghijklmnop  '`(앞뒤 공백 2칸씩, 원문 20자)를 입력하고 등록 → **캡처한 요청 본문의 `secret` 이 입력 문자열과 정확히 일치**(`toBe('  abcdefghijklmnop  ')`). 어느 계층에서든 `trim()` 이 끼면 red.
- [ ] **EC3 판별자 — 비대칭 술어**. `secret = ' '.repeat(15) + 'a'`(원문 16자, trim 시 1자) 입력 → **클라 검증 통과**(요청이 실제로 나감). 클라가 `trim().length` 로 재면 red.
- [ ] **★ 판별력 검증** — 구현에 `secret.trim()` 을 일부러 끼워 넣었을 때 EC2 판별자가 red 가 되는지 확인. **MSW 도 E2E 도 이 사고를 못 잡으므로**(EC15) 이 단위 테스트가 **유일한 가드**다.

### ★C — 그 밖의 판별자

- [ ] **EC8 판별자** — 모달 표시/복사 문자열이 `window.location.origin` 을 **포함**함을 단위 테스트로 단언(origin prepend 누락 시 red)
- [ ] **FR12 판별자** — 목록 4상태 각각 렌더 테스트. 특히 **403 → `accessDenied` 문구 렌더**(백지 아님)
- [ ] **FR15 판별자** — 웹훅 0건 + PR_MERGED 선택 → 경고 렌더 / 웹훅 1건 + PR_MERGED → 경고 **부재**(음성 단언) / 목록 `isError` + PR_MERGED → 경고 **부재**(EC21)
- [ ] **FR7 판별자** — URL 모달 닫기 후 `mutation.data` 가 `undefined` 임을 단언(`reset()` 호출 확인)
- [ ] **FR5 판별자** — secret 입력의 `type` 이 `'password'` 이고 `autoComplete` 가 `'off'` 임을 단언

### ★D — E2E (D7)

- [ ] E2E 신규 시나리오 통과 — S1(등록+URL 1회 노출) · S6(목록) · S5(삭제) · S12(targetBranch 라운드트립). **선행조건** — PR_MERGED 픽스처 추가(EC16)
- [ ] **기존 automation E2E 6건 동시 통과** — `automation-{actions,conditions,conflict-warning,execution-history,rules,yaml-gitops}.spec.ts`. 같은 페이지를 건드리므로 회귀 확인 필수([[ui-pr-defer-e2e-regression-latent]], 별도 실행 결과 첨부)

### ★E — 문서 마킹 (`docs/plan/product/automation.md`)

- [ ] **D6·D7 두 줄만** `[x]` 마킹(`:127-128` — D1~D5 는 #278 이 이미 `[x]`). **D6 정본 문구("페이지")는 고치지 않는다** — 배치 사유를 완료 블록에 적어 해소한다(★1).
- [ ] **D6/D7 완료 블록 추가 — 선례는 3개가 아니라 6개다**(실측 `grep -n "D6/D7 완료"` → `:38`(#254) · `:54`(#260) · `:70`(#265) · `:86`(#269) · `:100`(#271) · `:116`(#273)). **`:38` 만 `automation BC N/7` 접미가 없고**(`→ **FR-AT-01 전체 완료(D1~D7)**.` 로 끝남) 그 접미는 **#260 부터 생긴 관례**다. → PR-D 블록은 **#260 이후 형식**을 따른다.
  - 형식 — `> **D6/D7 완료 (2026-07-17, PR #N)**. ` … `→ **FR-AT-07 전체 완료(D1~D7)**, automation BC **7/7**.` (#273 수준 2000~2600자)
  - **반드시 포함** — ① **배치 사유**(옵션 A 형제 섹션·라우터 변경 0·D6 정본 문구 "페이지"와 글자상 어긋나는 이유) ② **secret trim 금지**(BLOCKER-0) ③ 중복 가드·목록 식별자 후속 등재 ④ `BC 7/7 ≠ BC 완료` 분리(아래)
- [ ] **★ FR 총수는 `128 불변`(D-step)** — `CLAUDE.md:10` **재실측 완료**(`(128 FR, …)`, HEAD=`802b495ef` 기준. #279 는 아직 128→129 로 올리지 않았다). <br>**선례 문구를 복붙하면 6번째 "123"이 박힌다.** `automation.md` 에서 `grep -n "123"` → **6곳**(`:38`·`:86`·`:100`·`:114`·`:116`·`:180`) 전부 `123 불변` 이다. `:180` 이 자백하듯 — `> FR 총수 **123 불변** — \`fr-index.md\`·\`README.md\`·\`CLAUDE.md\` 미변경(#277 동시 PR 의 카운트 충돌 회피).` — **#278 이 123 을 유지한 건 동시 PR 카운트 충돌 회피용 의도적 미변경이지 현재값이 아니다.** #277 이 FR-PJ-01~04 + FR-PM-10 으로 123→128 을 올렸다. <br>**`verify-master-plan.sh` 는 `CLAUDE`/`fr-index`/`README`/product 헤더만 보므로 `automation.md` 산문의 123 은 자동 차단에 안 걸린다** — **스펙이 직접 못박는 것 외에 가드가 없다.** 착수 시 `CLAUDE.md:10` 재실측 필수(#279 가 129 로 올렸을 수 있음).
- [ ] **★ §NFR 측정표 — 전사갭 1행만 채운다**(Maxi D1 확정 = 옵션 C). `automation.md:190` 「규칙 충돌 정적 분석」 행의 실측 셀 `___` → **`0.876s`**. <br>**★ 새로 측정하는 게 아니라 옮겨 적는 것이다.** 출처는 **`automation.md:84` 산문**(FR-AT-04 #268 D1~D5 완료 블록) — `성능 스모크 100규칙 0.876s(NFR 1s)`. 이미 측정돼 산문에만 있고 표에 반영이 안 된 **전사 누락**이다. 임계 1s 대비 통과. **나머지 4행(`:189` 트리거 지연 · `:191` 실행 이력 재실행 · `:192` YAML import · `:194` 권한 차단율)은 건드리지 않는다** — 백엔드 측정이라 순수 프론트 PR 로 불가하며 **후속 등재**한다(§후속 등재 4). 선례 = slack #267(선례 답습 + 전사갭만 수정).
- [ ] `docs/plan/README.md:110` — 실측 행 `| automation | [product/automation.md](product/automation.md) | 7 (AT 7) | (없음) | ☐ |`. FR 개수 열 `7 (AT 7)` 은 **불변**. **`☐` 열은 PR-D 가 뒤집지 않는다**(아래 ★).
- [ ] `node scripts/build-dashboard.mjs` 재생성([[dashboard-regen-after-fr-marking]] — post-merge 훅 상시고장, 수동 `--no-verify` 가 정규절차)

> **★ `automation BC 7/7`(FR 카운트)과 `automation BC 완료`(게이트)는 다른 것이다.** PR-D 는 **7/7 을 달성하지만 BC 완료 게이트는 채우지 못한다.** 두 표현을 섞어 쓰면 "완료했다"는 거짓 보고가 된다. `automation.md:207-213` `### BC 완료 조건` **5줄 중 PR-D 가 채우는 건 첫 줄뿐**이다(실측 전문).
> | `automation.md` | 조건 | PR-D |
> |---|---|---|
> | `:209` | `- [ ] §2 (FR-AT 7개) 모두 [x] 마킹` | **PR-D 가 완성** ✓ |
> | `:210` | `- [ ] §NFR 측정표 모든 항목 임계 통과` | **불가**. 6행 중 **5행이 `___`**(`:189-194`). PR-D 는 전사갭 1행(`:190` → `0.876s`)만 채운다 → **채워도 4행이 `___` 로 남는다**(`:193` Webhook 응답 `**148ms**` 는 #278 이 채움). 나머지는 백엔드 측정 |
> | `:211` | `- [ ] CHANGELOG.md 정리` | **Maxi 몫** |
> | `:212` | `- [ ] README.md §7 변경 이력에 "automation BC 완료 — YYYY-MM-DD" 추가` | **Maxi 몫** |
> | `:213` | `- [ ] Maxi 1인 선언 — "automation BC 완료"` | **Maxi 몫** |
>
> → **`docs/plan/README.md:110` 의 `☐` 는 PR-D 가 뒤집지 않는다.** 선례 #273 은 6/7 이라 이 칸을 마주한 적이 없어 **참고할 선례가 없는 지점**이다(automation 에서 PR-D 가 처음 마주친다). 이 분리를 명시하지 않으면 PR-D 가 **채울 수 없는 것을 완료 기준에 적게 된다**.

### ★F — 기존 테스트 2파일 MSW 합류 (BLOCKER-2 — 빠뜨리면 통째로 red)

**이 두 파일은 전역 `handlers.ts` 를 쓰지 않는다** — 자체 `setupServer` + `onUnhandledRequest: 'error'` 라 전역 등록의 효력이 **0** 이다(§제약 7-b). PR-D 가 반드시 건드리는 파일인데 **2차 개정본까지 이름조차 언급되지 않았다**(R6).

- [ ] **`apps/web/src/routes/__tests__/projects.$projectKey.settings.automation.test.tsx:62`** — `setupServer(...automationRuleHandlers, ...automationExecutionHandlers, ...gitWebhookHandlers)` 로 합류. **사유** = FR0 이 이 route 에 `GitWebhookSection` 을 마운트 → `GET .../git-webhooks` 발화.
- [ ] **`apps/web/src/components/automation/AutomationRuleFormDialog.test.tsx:42`** — `setupServer(...automationRuleHandlers, ...projectMemberHandlers, ...gitWebhookHandlers)` 로 합류. **사유** = FR15 가 폼 안에 웹훅 목록 `useQuery` 를 넣음 → 동일 발화. **★B 의 A1~A5 가 전부 이 파일 소속**이므로 합류 없이는 핵심 판별자가 실행조차 안 된다.
- [ ] **`apps/web/src/mocks/handlers.ts`** — `gitWebhookHandlers` import + spread(§제약 7-a). 이것이 전역 배열 소비 테스트(`handlers.test.ts` · `__tests__/handlers.integration.test.ts`)를 자동 충족시킨다.
- [ ] **★ 판별자 — 합류 전 red 를 실제로 본다.** 두 파일에 합류를 **넣기 전에** FR0/FR15 구현분으로 테스트를 돌려 `unhandled request` 로 red 가 나는지 확인한다. red 가 안 나면 섹션/쿼리가 실제로 마운트되지 않은 것이다(FR0·FR15 미구현 신호).
- [ ] **★ 회귀 전수 — `grep -rn "setupServer" apps/web/src` 로 재확인.** 위 2곳이 **전부**임을 실측으로 확정한다(현재 실측 기준). `AutomationRuleList.test.tsx:31` 은 **합류 대상이 아니다** — `GitWebhookSection` 은 `AutomationRuleList` 의 **형제**라 이 컴포넌트 단독 렌더에서는 웹훅 쿼리가 발화하지 않는다(FR0).

## Brainstorming Check

✅ **통과 — Maxi 결정 3건 + 오케스트레이터 결정 1건 반영 완료.** 1차 초안에서 미결이던 배치(★1)는 **옵션 A 확정**으로 닫혔다.

### ★ 3차에서 반증한 지시 4건 (BLOCKER 2건 + 거짓 사실 2건 — **전부 오케스트레이터 지시가 오염원**)

| # | 지시 전제 | 실측 | 해소 |
|---|---|---|---|
| **R5** | FR1 — "섹션별 h2 **도입**(`'자동화 룰'` / `'Git 웹훅'`)" **하되** "`'자동화 룰'` 과 반드시 구분" | **자기모순 + 거짓 전제.** `'자동화 룰'` h2 는 **이미 존재**한다(`AutomationRuleList.tsx:380`, 라벨 `:23`). 지시대로 route 에 h2 를 "도입"하면 같은 텍스트 h2 가 **2개** → `getByRole('heading', {name})` 의 **substring 매칭**이 둘 다 잡아 **strict mode 위반** → **automation E2E 11개 단언 / 6스펙 전부 red** | **PR-D 가 만드는 회귀**(기존 결함 아님). FR1 을 **h1 설명문 교체 한 가지로 축소**하고 신규 h2 는 `'Git 웹훅'` **하나뿐**임을 명시. FR0 에 "2섹션화 ≠ h2 최초" 오독 차단 문구. **§제약 11 에 11개 단언 전수 열거표 + 판별자** 신설 |
| **R6** | "`mocks/handlers.ts` 에 등록하면 E2E 가 붙는다" → §제약 7 이 **'handlers.ts 등록 필수'** 로 수용 | **그 지시는 두 파일에 효력이 0 이다.** PR-D 가 반드시 건드리는 테스트 2개가 전역 `handlers` 를 **안 쓰고** 자체 `setupServer` + `onUnhandledRequest:'error'` 를 쓴다 — **route 테스트**(`routes/__tests__/projects.$projectKey.settings.automation.test.tsx:62`·`:64`) · **FormDialog 테스트**(`components/automation/AutomationRuleFormDialog.test.tsx:42`·`:44`). FR0/FR15 가 `GET .../git-webhooks` 를 쏘는데 로컬 서버에 핸들러가 없어 unhandled → **두 파일 통째로 red** | §제약 7 을 **2층 구조(전역 + 로컬 2곳)로 재작성**. **§완료 기준 ★F 신설** — 두 파일 합류를 명시적 작업 + 체크박스로 승격 |
| **R7** | 1차·2차 초안. `handlers.integration.test.ts` 는 **존재하지 않는다**(§제약 7 · Brainstorming 에 **2회** 단언) | **실재한다.** `apps/web/src/mocks/__tests__/handlers.integration.test.ts`, **76줄**. `:6` `setupServer(...handlers)` 로 **전역 배열을 소비** | **결론(갱신 불요)은 살아남지만 근거가 거짓.** 정정 기재(§제약 7-c). ★ **바로 이 디렉토리 맹점**(`src/mocks/*.test.ts` 만 보고 `__tests__/` 를 안 봄)**이 R6 의 route 테스트도 시야에서 지웠다** — 거짓 부재 단언이 BLOCKER 를 숨긴 사례. `grep -rn "setupServer" apps/web/src` **전수**가 유일 탐지 수단 |
| **R8** | 오케스트레이터 요구 — "캐시 공유니까 **추가 API 호출 0** 이라고 써라" → spec S9 ★ · FR13 · FR15 가 `추가 API 호출 0` / `네트워크 요청이 새로 나가지 않는다` 로 수용 | **거짓.** ① `main.tsx:17-22` QueryClient = `{queries:{retry:false}, mutations:{retry:false}}` 뿐 → **`staleTime` 미설정 = 0** ② `grep -n "staleTime" apps/web/src/api/useAutomationRules.ts` → **EXIT=1(0건)**. **staleTime 0 + `refetchOnMount` 기본 true** = 새 observer 마운트 시 **배경 refetch 발화**(v5 문서화 동작). 리포 전체엔 staleTime 실재(`useSessionsQuery.ts:21` `staleTime: 30_000`) — **automation 계열만 0건** | 문구를 정직하게 — **`신규 엔드포인트 0` + `폼 오픈 시 배경 refetch 1회`**. 캐시 공유로 실제 얻는 것은 **중복 인플라이트 dedupe + 즉시 캐시 표시**이지 '호출 0' 이 아니다. **판정식 `data?.length === 0` 은 유지**(렌더 블로킹·UX 영향 없음). **★ 교훈** — "성능상 공짜"라는 직관을 스펙 문언으로 승격시키기 전에 `staleTime` 실측이 필요하다. NFR1 이 `mutation.reset()` 에서 배운 것과 같은 규율(**스펙이 못 지킬 약속을 하면 안 된다**)이 여기서 한 번 더 깨졌다 |

### ★ 2차에서 반증한 지시 4건 (전부 실측 근거)

| # | 지시 전제 | 실측 | 해소 |
|---|---|---|---|
| **R1** | `WebhookForm.tsx` 의 secret 라벨은 **`:15`** | **`:16`**. `grep -n "secret:"` → `16:  secret: '서명 Secret',`(`:15` 는 `events: '구독 이벤트'`) | B4 의 결론(마스킹 선례 실재)은 **그대로 유효**. `type="password"` 는 `:224` 로 **정확**. 인용만 `:16` 으로 정정(FR5) |
| **R2** | `automation.md` 의 `123 불변` 선례는 **5블록**(`:38,86,100,116,180`) | **6곳**. `grep -n "123"` → `:38`·`:86`·`:100`·**`:114`**·`:116`·`:180`. `:114`(#272 D1~D5 완료 블록)의 `FR 총수 123 불변(D-step)` 이 열거에서 빠져 있었다 | 결론(`128 불변` 을 써야 함)은 **더 강해진다** — 복붙 유혹 지점이 5곳이 아니라 **6곳**이다. §완료 기준 ★E 를 6곳으로 정정 |
| **R3** | cron 분기(`:369-392`)를 복제하면 autoComplete 가 비어 autofill 위험 | **cron 분기에는 `autoComplete="off"` 가 이미 있다**(`:380`). 템플릿을 그대로 복제하면 autoComplete 는 **딸려 온다** | C4 의 결론(**`autoComplete="off"` 필수**)은 유지 — 다만 위험의 모양이 다르다. 진짜 위험은 "복제하면 빠진다"가 아니라 **"복제하면 `type="text"`(`:375`)가 딸려 와 secret 이 평문이 된다"** 이다(B4-★). → FR5-(c) 로 정정해 **type 만 바꾸고 autoComplete 는 유지**하라고 명시 |
| **R4** | 1차 초안이 `useAutomationRules.ts` 를 `hooks/` 소속으로 읽힐 여지 | 실제 경로는 **`apps/web/src/api/useAutomationRules.ts`**(`hooks/` 아님). `:32-35` 내용은 **정확** — `export const AUTOMATION_RULES_QUERY_KEY = (projectKey: string): [string, string] => ['automation-rules', projectKey,]` | FR21 의 인용 유지. 착수 시 `api/` 경로임에 주의(파일 못 찾아 새로 만드는 사고 방지) |

### ★ 지시가 옳았음을 실측으로 확증한 것

1. **B1+B3 secret trim 금지** — `GitWebhookRegistrationService.kt:180-181` KDoc **verbatim 일치**. 검증 비대칭도 확증 — `:185` `if (secret.isBlank())` / `:188` `if (secret.length < MIN_SECRET_LENGTH || secret.length > MAX_SECRET_LENGTH)`. **untrimmed `secret.length` 확인.** → BLOCKER-0 · FR4 · EC2 · EC3 · EC15 · §완료 기준 ★B2.
2. **S1 핸들러 7개** — `grep -n "@ExceptionHandler"` → `:178`·`:193`·`:211`·`:224`·`:238`·`:251`·`:273` **정확히 7개**(6 아님). `AUTOMATION_MALFORMED_REQUEST` 가 `:238`·`:251` **2곳**이고 detail 이 `"요청 파라미터 값이 올바르지 않습니다."`(`:246`) / `"요청 본문이 유효하지 않습니다."`(`:262`) **2종**. → 에러표 **전수 열거로 재작성**.
3. **S3 모달 33개** — `grep -rn "DialogPrimitive.Content" apps/web/src` → **66행 / 33파일**, 비테스트 66행. 1차 초안의 "4벌"은 **자기 눈에 들어온 4벌**이었다([[spec-stated-count-becomes-blindfold]]). → §제약 3 을 33 으로 정정, 결론(복제가 정답)은 **강화**.
4. **S4 select-all 선례** — **같은 BC 에 실재**. `AutomationYamlImportDialog.tsx:142` `<code className="block break-all rounded bg-muted px-2 py-1 text-xs font-mono select-all">{token.token}</code>`. **반면 복제 원본 `WebhookTokenModal.tsx:89` 에는 `select-all` 이 없다** → 원본 복제 시 결함 승계. → FR10-(b).
5. **D4 무음실패 근거** — `GitWebhookService.kt:132` 가 PR_MERGED 룰 조회의 **유일한 진입점** 확증. `grep -rn "TriggerType.PR_MERGED"` 백엔드 5건 중 룰 **조회**는 `:132` 하나(`:190` 하류 enqueue / `RuleConflictAnalyzer.kt:256` 정적 분석 / `TriggerConfig.kt:18,49` 검증). **인바운드 핸들러 안에 있으므로 웹훅 0건 = 발화 경로 물리적 부재.** → FR15.
6. **오케스트레이터 결정 — 순차 가드 선례** — `projects.$projectKey.settings.automation.tsx:162` `conflicts={webhookToken === null ? conflicts : null}` **정확**. → FR2.
7. **①② 판별자 반증** — `grep -n "it('PR_MERGED" automation-rules.types.test.ts` → `:349`·`:392`·`:401` **3개**(4 아님). `grep -c "PR_MERGED" AutomationRuleFormDialog.test.tsx` → **0**. **③④를 잡을 테스트가 아예 없다** 확증. → ★B 5단언 + 1:1 매핑표 + mutation 판별력 검증.
8. **④ BC 완료 5줄** — `automation.md:207-213` 실측 일치. PR-D 가 채우는 건 `:209` 첫 줄뿐. `:190` 「규칙 충돌 정적 분석」 `___` 확증, 출처 `:84` `성능 스모크 100규칙 0.876s(NFR 1s)` 확증. → ★E.
9. **⑤ D6/D7 블록 6개** — `:38`·`:54`·`:70`·`:86`·`:100`·`:116` 확증(3 아님). `:38` 만 `automation BC N/7` 접미 부재 확증(`→ **FR-AT-01 전체 완료(D1~D7)**.` 로 종료). → ★E.
10. **③ CLAUDE.md = 128** — `CLAUDE.md:10` `(128 FR, …)` 실측 재확인(129 아님). `README.md:110` `| automation | … | 7 (AT 7) | (없음) | ☐ |` 실측. **DESIGN.md 는 리포 루트**(`apps/web/DESIGN.md` 아님), `grep -c amber` = **0** 확증.
11. **`webhookHandlers` 선점** — `webhook-handlers.ts:266` 정의 / `handlers.ts:56` import / `:138` spread 확증. → `gitWebhookHandlers`(FR20).
12. **route 상태 6종** — `:58` KDoc `- 상태 6종을 이 컴포넌트가 보유한다.` 와 실측 `:87-92` 6개 일치 확증. FR2 가 Dialog 소유를 섹션에 두므로 **6종 불변** → FR22 가 실행 가능.
13. **4상태 선례** — `AutomationRuleList.tsx:405-427` loading(`role="status"`+`aria-label`) / error(403 분기 `extractAutomationRuleErrorCode(error) === 'AUTOMATION_ACCESS_DENIED' ? labels.accessDenied : labels.genericError`) / empty / list **4상태 전부** 확증. → FR12.

### ★ 1차 초안이 반증한 지시 (2차에서 유지)

1. **targetBranch 4종**(3종 아님) — `parseTriggerConfig`(`AutomationRuleFormDialog.tsx:151-176`)에 targetBranch 부재 재확인. `ParsedTriggerConfig` = `{cron: string; fields: string[]}` 2필드뿐. → FR16, ★A 원자 단위.
2. **재발급 0건** — 매핑 3개 재확인.
3. **`webhookUrl` origin 없는 절대경로** — `GitWebhookDtos.kt:61,77` + KDoc `:66-68`.
4. **secret 사용자 공급 + Bean Validation provider 부재** — `GitWebhookDtos.kt:14-20`.
5. **백엔드 targetBranch 수용 4갈래** — `TriggerConfig.kt:101-109` 일치. 매칭은 정확 문자열 비교(`GitWebhookService.kt:339-346`) → 와일드카드 암시 금지(EC17).
6. **MSW 픽스처 PR_MERGED 0건** — `automation-rule-fixtures.ts:132,149`. → EC16, D7 선행조건.
7. **DESIGN.md amber 0건** — §제약 8.
8. ~~**`handlers.integration.test.ts` 부재**~~ — **3차에서 반증됨(R7). 실재한다.** §제약 7-c 참조.

### 복제 시 그대로 가져갈 검증된 세부 4종 (`WebhookTokenModal` 에서)

1. **null narrowing 우회** — `중첩 함수(handleCopy)에는 null narrowing이 전파되지 않으므로 token을 별도 const로 캡처한다`(`:54-55`). 복제 시 동일 함정.
2. **clipboard 실패 분기** — `catch { setCopyError(labels.copyFailed); setCopied(false) }`(`:62-65`). HTTPS 아니면 clipboard API 가 없어 load-bearing(EC9).
3. **테스트 clipboard 스텁 순서** — `userEvent.setup()이 navigator를 재구성하므로 스텁은 setup() 이후에 적용한다`(`WebhookTokenModal.test.tsx:10-22`). 순서 함정이 주석으로 박제돼 있음 — 복제 필수.
4. **소멸 계약** — `호출부는 이 시점에 token state를 null로 되돌린다`(`:29-31`). PR-D 는 **소멸 대상이 token 이 아니라 webhookUrl 과 secret** 이며, state null 만으로 부족하고 **`mutation.reset()` 이 함께 필요**하다(FR7·NFR1).

> **★ 복제하되 승계하면 안 되는 결함 1종.** `WebhookTokenModal.tsx:89` 의 `<code>` 에 **`select-all` 이 없다**. 이것만은 `AutomationYamlImportDialog.tsx:142` 쪽을 따른다(FR10-b).
