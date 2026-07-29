# vite preview 포트 ⟺ 백엔드 CORS 허용 오리진 정합

> slug: vite-preview-port-cors-align
> type: ui (fast-track — 빌드 설정 1줄 + 회귀 판별식)
> agent: frontend-engineer
> 생성: 2026-07-30

## Brief

**원문 (Maxi).** #319 손검증 경로 CORS 포트 불일치 수정 — `apps/web/vite.config.ts` 의
`preview.port` 를 4173 에서 5173 으로 고정한다. 백엔드 CORS 허용목록
(`application.yml` 기본값 `http://localhost:5173`)은 건드리지 않는다.
회귀 방지 장치로 preview 포트와 CORS 허용 오리진의 정합을 강제하는 테스트를 넣는다.

**Maxi 확정 (D1 = A).** 프론트 한 줄만 바꾼다. 백엔드 CORS 기본 허용목록을 넓히는 안(B)은
그 기본값이 운영 배포에 딸려가 `BTS_CORS_ALLOWED_ORIGINS` 미설정 시 `localhost:4173` 이
허용된 채로 뜨므로 기각. 절차만 문서화하는 안(C)은 이미 한 번 밟은 함정이라 기각.

**분류 결과.** `type=ui` / `agent=frontend-engineer` — 경로 실측(`apps/web/**`)과 일치.
`slug` 는 classify 자동 생성값(`319-cors-apps-web-vite-config-ts-preview-port-4173`) 대신
가독 가능한 이름으로 교체.

**fast-track 사유.** 설계 갈림길(D1)이 착수 전 Maxi 확정으로 닫혔고 변경 대상이 빌드 설정
1줄이라 `/bts-domain`(신규 도메인 용어 0건) · `/bts-spec`(사용자 시나리오 없음) ·
`/bts-review-plan` 을 생략한다. #319 자체도 `[chore]` 였다. plan · impl · codereview ·
게이트 2종은 그대로 밟는다.

## 결함 (실측)

| 항목 | 실측값 | 위치 |
|---|---|---|
| vite dev 서버 포트 | 5173 | `apps/web/vite.config.ts:33` |
| vite preview 포트 | **4173** | `apps/web/vite.config.ts:53` |
| 백엔드 CORS 허용 기본값 | `http://localhost:5173` | `backend/modules/app/src/main/resources/application.yml:85` |

**증상.** `pnpm build && pnpm preview` 로 실 백엔드 손검증을 하면 GET 은 통과하고 POST 만
`403 Invalid CORS request` 가 된다. 브라우저가 POST 에만 `Origin` 헤더를 보내고 vite 프록시가
그대로 전달하기 때문. 손검증의 첫 단계인 로그인부터 막혀 #319 가 연 경로 자체가 못 쓰인다.

**왜 이 저장소의 지배 결함 양식인가.** 「하드코딩 목록 두 개가 서로를 안 본다」의
9번째 사례다 (2026-07-27 감사에서 8건 확인, #1·#2가 같은 `CorsConfig`). vite 가 서빙하는
로컬 포트 집합과 백엔드가 허용하는 오리진 집합을 각각 검증하는 눈은 있으나 **갈라짐 자체를
보는 눈이 없다.**

## 도메인 정리 (← /bts-domain 채움)

_fast-track 생략. 신규 도메인 용어 0건, ADR 0건._

## 스펙 (← /bts-spec Phase A 채움)

_fast-track 생략. 사용자 시나리오 없는 빌드 설정 변경._

## Brainstorming Check (← /bts-spec Phase B 채움)

_fast-track 생략._

## 이 변경이 새로 만드는 위험 (착수 전 식별)

preview 를 dev 서버와 **같은 포트**로 옮기면 없던 충돌면이 생긴다. 전수 열거.

| # | 위험 | 처방 | 이 PR 범위 |
|---|---|---|---|
| R1 | 5173 이 이미 점유돼 있으면 vite 가 **조용히 5174 로 옮겨** 붙는다 → 같은 403 이 다른 원인으로 재발 | `strictPort: true` 를 `server`·`preview` 양쪽에 | **포함** — 이게 없으면 수정이 성립하지 않는다 |
| R2 | `pnpm dev` 와 `pnpm preview` 동시 기동 불가 | R1 처방으로 **즉시 명시적 실패**. MSW 때문에 애초에 배타적 용도 | 포함 (R1 과 동일 처방) |
| R3 | `playwright.config.ts:20` 이 `reuseExistingServer: !CI` — 로컬에 preview 가 떠 있으면 E2E 가 **그것을 재사용**한다. 프로덕션 빌드는 MSW 가 꺼져 있어 전 테스트가 엉뚱한 이유로 깨진다 | `reuseExistingServer: false` | **포함** — Maxi 확정 D3=A |

R3 보충. 거짓 초록은 아니다(MSW 부재로 API 호출이 전부 실패해 **시끄럽게** 깨진다). 손실은
「원인 찾는 시간」이다. **Maxi 확정 (2026-07-30, D3=A)** — 「이 PR 이 만든 위험은 이 PR 에서
닫는다」. 대가는 로컬 E2E 실행마다 dev 서버 기동 수 초. CI 는 원래 `!CI` 로 false 였으므로
CI 동작은 불변이다.

## Plan

### Task 1. 차집합 판별식 신설 (RED)

**메타**.
- agent: `frontend-engineer`
- files: [`scripts/workflow/preview-cors-origin-alignment.test.ts`]
- depends-on: []

**RED**.
- 파일. `scripts/workflow/preview-cors-origin-alignment.test.ts` (신규)
- 선례. `scripts/workflow/ci-module-coverage.test.ts` — `node:test` + `node:assert/strict` + `fs` 로
  두 소스 파일을 파싱해 차집합을 낸다. 스타일·주석 밀도·에러 메시지 형식을 그대로 따른다.
- 수집기 3종 (**하드코딩 복사본이 아니라 실제 소스에서 수집**).
  1. `viteLocalPorts()` — `apps/web/vite.config.ts` 의 `server` · `preview` 블록에서 `port` 를 뽑아
     `{ server: 5173, preview: 4173 }` 형태로 반환. `strictPort` 존재 여부도 함께 반환.
  2. `corsAllowedOrigins()` — `application.yml` 의
     `allowed-origins: ${BTS_CORS_ALLOWED_ORIGINS:<기본값>}` 에서 기본값을 뽑아 콤마 분리.
  3. `backendCiTriggerPaths()` — `backend-ci.yml` 의 `pull_request.paths` · `push.paths` 를
     **각각** 집합으로 반환 (한 덩어리로 합치면 한쪽만 배선돼도 통과한다).
  4. `playwrightConfig()` — `apps/web/playwright.config.ts` 원문 (E2E 재사용 단언용).
- 테스트 6건.
  - `판별식이 비어 있지 않다 (양성 대조군)` — 포트 ≥2건, 오리진 ≥1건, 트리거 블록 2개 각각 ≥3건.
    0 은 「없다」가 아니라 「파서가 틀렸다」이므로 하한을 먼저 세운다.
  - `vite 가 서빙하는 모든 로컬 포트가 CORS 허용목록에 있다` — 차집합 0.
    **현재 red** (`preview 4173` 이 허용목록 밖).
  - `포트 리터럴이 알려진 블록 밖에 없다` — 파일 전체 `port:` 리터럴 수 == 수집된 블록 수.
    새 포트가 늘면 침묵하지 않고 깨진다 (상한 단언).
  - `server·preview 가 strictPort 로 고정돼 있다` — **현재 red** (양쪽 다 부재).
  - `양쪽 트리거 블록이 판별식 입력 3종을 전부 건다` — `backend/**` ·
    `scripts/workflow/**` · `apps/web/vite.config.ts` 가 `pull_request` 와 `push` **양쪽**에.
    **현재 red** (`apps/web/vite.config.ts` 부재).
  - `E2E 가 기존 서버를 재사용하지 않는다` — preview 와 dev 가 같은 포트를 쓰게 되므로
    `reuseExistingServer` 가 **무조건 false** 여야 한다. **현재 red** (`!process.env['CI']`).
- 예상 실패. **4건 red / 2건 green.**

**GREEN**. 없음 — 이 task 는 결함을 드러내는 것이 목적이다.

**REFACTOR**. 파일 L1 한국어 주석 + 「왜 이 테스트가 있나」 헤더 (선례 형식).

**검증**. `node --test scripts/workflow/preview-cors-origin-alignment.test.ts` — red 3건을
**출력으로 실측**하고 실패 메시지가 원인을 지목하는지 확인.

**커밋**. `test:` 접두사 (TDD 순서 강제 — `feat:`/`fix:` 보다 먼저여야 한다).

---

### Task 2. preview 포트 5173 고정 + strictPort (GREEN-a)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/vite.config.ts`]
- depends-on: [1]

**RED**. Task 1 에서 이미 관측됨.

**GREEN**.
- `preview.port` `4173` → `5173`
- `server.strictPort: true` · `preview.strictPort: true` 추가 (R1/R2 처방)

**REFACTOR**.
- 파일 L1 주석의 `dev 서버 (5173 포트)` 표기를 preview 포함으로 갱신
- `preview` 블록 KDoc 에 **왜 dev 와 같은 포트인가**(CORS 허용목록이 단일 오리진) +
  **왜 strictPort 인가**(조용한 포트 이동이 같은 403 을 다른 원인으로 재발시킨다) 명시

**검증**. `node --test scripts/workflow/preview-cors-origin-alignment.test.ts` →
차집합·strictPort 단언 2건 red→green 전이 관측. 나머지 **2건(CI 배선 · E2E 재사용)은 여전히
red** 여야 한다 — 여기서 전부 초록이 되면 그 단언들이 공허하다는 뜻이다.

---

### Task 3. backend-ci 트리거 배선 (GREEN-b)

**메타**.
- agent: `frontend-engineer`
- files: [`.github/workflows/backend-ci.yml`]
- depends-on: [1]

**RED**. Task 1 에서 이미 관측됨.

**GREEN**.
- `on.pull_request.paths` 와 `on.push.paths` **양쪽**에 `apps/web/vite.config.ts` 추가.
  한쪽만 넣으면 봉인이 절반만 닫힌다 — 기존 `scripts/workflow/**` 주석과 같은 사유.

**왜 backend-ci 이고 frontend-ci 가 아닌가.**
판별식이 `pnpm test:workflow` 로 도는데 그 잡이 backend-ci 에 있다. 트리거 실측 —
backend-ci 는 `backend/**`(application.yml 쪽) · `scripts/workflow/**`(판별식 자신)을 이미 걸고
있어 **vite 쪽 한 축만 비어 있다**. 반대로 frontend-ci 에 두면 `application.yml` 만 바꾸는 PR 에서
판별식이 안 돈다 (frontend-ci 트리거에 `backend/**` 가 없다). `vite.config.ts` 는 변경 빈도가
극히 낮아 backend CI 추가 기동 비용이 사실상 0 이다.

**REFACTOR**. 추가한 경로 옆에 사유 주석 (기존 `scripts/workflow/**` 주석과 같은 형식).

**검증**. `node --test scripts/workflow/preview-cors-origin-alignment.test.ts` → 5/6 green,
E2E 재사용 단언만 red 로 남는다.

---

### Task 4. E2E 서버 재사용 차단 (GREEN-c)

**메타**.
- agent: `frontend-engineer`
- files: [`apps/web/playwright.config.ts`]
- depends-on: [1]

**RED**. Task 1 에서 이미 관측됨.

**GREEN**.
- `reuseExistingServer: !process.env['CI']` → `reuseExistingServer: false`

**왜 필요한가 (Maxi 확정 D3=A).**
T2 로 preview 가 dev 와 같은 5173 을 쓰게 되면, 손검증용 preview 를 켜 둔 채 E2E 를 돌릴 때
Playwright 가 **그 preview 를 재사용**한다. 프로덕션 빌드는 `import.meta.env.DEV` 가 false 라
MSW 가 꺼져 있어 전 테스트가 API 호출부터 실패한다 — 거짓 초록은 아니지만 원인이 전혀 안
보이는 실패다. `false` 로 두면 Playwright 가 항상 자기 dev 서버를 띄우고, 포트가 점유돼 있으면
`strictPort`(T2) 때문에 **즉시 명시적으로** 실패한다.

**CI 동작 불변.** 기존 값이 `!process.env['CI']` 라 CI 에서는 이미 false 였다. 바뀌는 것은
로컬뿐이고 대가는 실행마다 dev 서버 기동 수 초.

**REFACTOR**. `reuseExistingServer` 옆에 사유 1줄 주석 (preview 가 같은 포트를 쓴다는 사실이
이 값의 근거임을 남긴다 — 근거를 안 적으면 다음 사람이 속도 이유로 되돌린다).

**검증**. `node --test scripts/workflow/preview-cors-origin-alignment.test.ts` → **6/6 green.**

---

### Task 5. 뮤테이션 검증 + 전체 회귀

**메타**.
- agent: `frontend-engineer`
- files: []
- depends-on: [2, 3, 4]

**뮤테이션 7종** (커밋 후에만 — 미커밋 상태로 주입하면 원복이 불확실해진다).
각 주입마다 **어느 단언이 red 로 바뀌는지**까지 확인한다. red 가 안 뜨면 그 축은 무검증이다.
**생산 지점 전수 = 검증 범위** — 판별식이 읽는 파일 4종 각각에 최소 1발씩 넣는다.

| # | 주입 | 대상 파일 | 기대 red |
|---|---|---|---|
| M1 | `preview.port` → `4173` 되돌림 | vite.config.ts | 차집합 |
| M2 | `server.port` → `3000` | vite.config.ts | 차집합 (dev 축도 실제로 덮는지) |
| M3 | `preview.strictPort` 제거 | vite.config.ts | strictPort |
| M4 | `application.yml` 기본값에서 `5173` 제거 | application.yml | 차집합 (백엔드 쪽 축) |
| M5 | `backend-ci.yml` **push 블록에서만** 경로 제거 | backend-ci.yml | CI 배선 (절반 봉인 탐지) |
| M6 | `reuseExistingServer` → `true` | playwright.config.ts | E2E 재사용 |
| M7 | `viteLocalPorts()` 정규식을 고장내 0건 수집 | 판별식 자신 | 양성 대조군 (공허 통과 차단) |

**전체 회귀**.
- `pnpm test:workflow` 전량 (기준선 실측 후 대조 — 판별식 신설분만 증가해야)
- `apps/web/node_modules/.bin/eslint src` · `tsc -p tsconfig.app.json --noEmit` ·
  `vitest run` (기준선 대조. **XML/출력 신선도까지 확인** — 린트가 먼저 깨지면 테스트는
  안 돌고 직전 결과만 남는다)
- `bash scripts/verify-master-plan.sh` EXIT 0 (FR 카운트 불변 확인)

**손검증 (이 PR 의 존재 이유)**.
`pnpm build && pnpm preview` → 브라우저에서 **로그인 POST 가 200** 인지 눈으로 확인.
403 `Invalid CORS request` 가 사라지는 것이 이 작업의 성공 판정이다.
백엔드는 조립 앱 비-prod 부팅(#322 의 dev 시드로 계정 존재).

---

## Plan 메타

- task 수. 5
- 예상 시간. 직렬 기준 약 25분 (T5 손검증 포함), wave 적용 시 약 15분 (예상 wave 3 — T1 → T2‖T3‖T4 → T5)
- TDD 강제. yes (T1 `test:` 커밋이 T2/T3/T4 보다 선행)
- 병렬. T2·T3·T4 는 파일 교집합이 없어 같은 wave 가능
- 추가 검증. eslint · tsc · vitest · `pnpm test:workflow` · verify-master-plan · 브라우저 손검증
- FR 영향. **없음** (139 불변) · 마이그레이션 0 · 신규 의존성 0 · 백엔드 코드 0 (yml 트리거만)

## 리뷰 결과 (← /bts-review-plan 채움)

_fast-track 생략. `/bts-codereview` 는 정상 수행._
