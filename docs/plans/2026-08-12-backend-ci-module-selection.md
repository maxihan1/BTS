# backend-ci 가 바뀐 모듈만 돌게 한다 — 의존 그래프는 Gradle 에서 도출

> slug: backend-ci-module-selection
> type: chore (fast-track — 게이트 1 생략, 게이트 2 만 정지)
> agent: backend-engineer
> 생성: 2026-08-12
> 부채. TODOS.md §인프라 — `backend-ci` 가 **바뀐 모듈을 고르지 않는다** (매핑 31)

## Brief

**무엇.** `backend-ci.yml` 은 트리거가 `backend/**` 이고 `matrix.module` 이 **9개 BC 고정 목록**
이다. `app(조립 부팅)` · `ktlint+detekt` · `runner-health` 를 더해 **잡 12개** — 백엔드 파일이
하나만 바뀌어도 전부 돈다.

**실측 (2026-08-12 · PR #367).** 백엔드 변경은 `IssueMoveController.kt` 의 **KDoc 한 덩어리,
실행 코드 0줄**. 그 한 파일이 12개 잡을 깨웠고 러너가 1대라 직렬로 **약 37분**이 나갔다.
**이번 세션의 PR #378 도 같은 비용을 다시 치렀다** — 워크플로우 파일만 고쳤는데 모듈 9개가 돌았다.

**★설계 주석의 전제가 거짓이다.** 「매트릭스로 병렬화하면 벽시계가 최장 모듈로 수렴」은
**러너가 여러 대일 때** 성립한다. 1대에서는 병렬이 아니라 직렬이라 **전 모듈 합계**가 된다.

### 채택 처방 — ① 변경 모듈만 돌린다 (Maxi 확정)

**의존 그래프를 손으로 적지 않는다.** `backend/modules/*/build.gradle.kts` 의
`project(":modules:X")` 참조에서 **런타임에 도출**한다 — 목록을 상수로 두면 그 목록과 실제
Gradle 설정이 서로를 안 보는 두 목록이 된다([[two-lists-never-check-each-other]]).

**착수 전 실측한 그래프.**

```
shared-kernel        ← (없음)          ⇒ 바뀌면 전 모듈이 영향
identity-access      ← shared-kernel
agile-planning       ← shared-kernel
automation           ← shared-kernel
notification         ← shared-kernel
search-export-import ← shared-kernel
slack-integration    ← shared-kernel
issue-tracking       ← project-workflow · shared-kernel
project-workflow     ← issue-tracking(testRuntimeOnly) · shared-kernel
app                  ← 전 모듈
```

★`issue-tracking ↔ project-workflow` 는 **순환처럼 보이지만 아니다** — 한쪽이
`testRuntimeOnly` 다. 다만 **역의존 폐포는 테스트 의존까지 포함**해야 한다(issue-tracking 이
바뀌면 project-workflow 의 테스트가 영향받는다). 폐포 계산은 방문 집합으로 순환 안전하게 한다.

### ⚠️ 장부가 못박은 비-공허 짝 (이것이 이 작업의 핵심 위험)

> 「필터가 모듈을 하나도 안 고르면 전부 돈다」와 「고른 목록이 실제 변경과 일치한다」를 함께
> 재지 않으면, 필터가 조용히 0개를 골라 **아무 잡도 안 돌면서 초록**이 되는 경로가 생긴다.

그래서 **fail-safe 방향을 넓은 쪽으로 고정**한다 — 판정을 못 하면(변경 파일 목록 획득 실패 ·
모듈 밖 백엔드 파일 변경 · 빌드 설정 변경) **전 모듈**이다. 좁히는 실수는 깨진 채 머지되지만
넓히는 실수는 시간만 든다. 두 오류의 비용이 대칭이 아니다.

## 도메인 정리 / 스펙 / Brainstorming

_fast-track (chore) — 생략._

## Plan

### 파일 구조

| 파일 | 책임 | 변경 |
|---|---|---|
| `scripts/workflow/select-backend-modules.ts` | 그래프 도출 + 역의존 폐포 + CLI | **신규** |
| `scripts/workflow/select-backend-modules.test.ts` | 계약 판별식 | **신규** |
| `.github/workflows/backend-ci.yml` | `select` 잡 신설 · 매트릭스를 동적으로 | 수정 |
| `TODOS.md` · `docs/plans/2026-08-12-debt24-master.md` | 정본 · 상태 마커 | 수정 |

### Task 1. 선별기 — 그래프 도출과 역의존 폐포

**메타**. agent `backend-engineer` · files [`select-backend-modules.test.ts`, `select-backend-modules.ts`] · depends-on []

**RED** → **GREEN** → **REFACTOR**. 판정 규칙.

1. 변경 파일이 `backend/modules/<m>/…` 이면 `<m>` 을 씨앗에 넣는다
2. `backend/` 아래인데 모듈 밖이면(`backend/build.gradle.kts` · `backend/db/**` ·
   `settings.gradle.kts` · 버전 카탈로그) **전 모듈**
3. `.github/workflows/backend-ci.yml` 변경도 **전 모듈** (워크플로우 자신이 바뀌면 전수 확인)
4. 씨앗에서 **역의존 폐포** — X 를 의존하는 모든 모듈을 전이적으로 더한다
5. 변경 파일 목록이 비었거나 획득 실패면 **전 모듈** (fail-safe)

### Task 2. 워크플로우 배선 — 매트릭스를 동적으로

**메타**. agent `backend-engineer` · files [`.github/workflows/backend-ci.yml`, `select-backend-modules.test.ts`] · depends-on [1]

`select` 잡이 변경 파일을 구해 선별기를 돌리고 JSON 배열을 출력 → `modules` 잡이
`fromJSON` 으로 매트릭스를 만든다. **빈 배열은 만들지 않는다**(GitHub 이 빈 매트릭스를 거부하고,
거부되면 잡이 조용히 스킵된다 — 정확히 장부가 경고한 경로).

### Task 3. 정본 동기화 + 뮤테이션

**메타**. depends-on [1, 2]

뮤테이션 최소 5종 — ①폐포 제거(직접 변경만) ②`shared-kernel` 특례 제거 ③fail-safe 를 좁은
쪽으로 뒤집기 ④테스트 의존 간선 제외 ⑤매트릭스를 다시 고정 목록으로.

## 뮤테이션 결과

기준선 **247 pass / 0 fail EXIT=0**. 각 뮤테이션을 하나씩 넣고 `git checkout --` 로 원복.

| 뮤테이션 | 결과 | 잡은 케이스 |
|---|---|---|
| M1. 역의존 폐포 제거 | **RED** (2) | shared-kernel 전파 · 전이·테스트 간선 |
| M2. 모듈 밖 백엔드 변경을 무시 | **RED** (1) | **섞인 입력** — 모듈+빌드설정 |
| M3. 빈 입력에서 공집합 반환 | **RED** (1) | 어떤 입력에도 0개를 안 준다 |
| M4. 테스트 의존 간선 제외 | **RED** (1) | 전이·테스트 간선 |
| M5. 매트릭스를 고정 목록으로 되돌림 | **RED** (2) | 배선 · 두 목록 차단 |
| M6. 알 수 없는 모듈 경로를 무시 | **RED** (1) | **섞인 입력** — 모듈+미지 모듈 |

### ★★1차에서 M2·M6 이 살아남았다 — 원인이 같다

**단일 파일 입력만 쟀다.** 빌드 설정 파일 **하나만** 넣으면 씨앗이 비어
「백엔드 변경을 못 찾았다」 fallback 이 대신 전 모듈로 넓혀 준다 — 그래서 넓히는 분기를
**통째로 지워도 통과**했다. M6 도 같은 모양이다.

실제 위험은 **섞인 입력**이다. 모듈 파일과 빌드 설정이 함께 바뀌면 씨앗이 비지 않으므로
fallback 이 발동하지 않고, 넓히는 분기가 없으면 **한 모듈로 좁혀진 채 머지된다.**

처방. `['backend/modules/notification/X.kt', 'backend/build.gradle.kts']` 와
`[..., 'backend/modules/no-such-module/Y.kt']` 두 케이스를 추가하니 둘 다 RED 가 됐다.

**교훈.** fallback 이 여러 겹인 코드에서는 **각 분기가 단독으로 발동하는 입력**을 만들어야 한다.
가장 바깥 fallback 이 넓게 잡아 주면 안쪽 분기의 부재가 보이지 않는다.
[[invariant-satisfied-by-helptext-not-logic]] 와 같은 계열 — **가드가 있는데 재는 대상이 아니다.**

## 독립 코드리뷰 반영 (6건 전부 채택)

`/code-review high 379` 이 6건을 올렸고 각각 실측 확인 후 전부 반영했다. **3건이 「좁아지는
방향」**, 즉 검증 안 된 코드가 초록으로 지나갈 수 있는 경로였다.

| # | 지적 | 처방 |
|---|---|---|
| 1 | 선별기 파일이 backend-ci `paths` 에 없어, **그 파일을 고치는 PR 에서 backend-ci 가 0회** 돈다 — 무엇을 돌릴지 정하는 코드가 백엔드 잡으로 한 번도 검증되지 않는다 | 글로브가 아닌 **파일 하나**를 paths 에 추가 + `WIDEN_PREFIXES` 등재. 금지 가드의 「`./gradlew` 만 실행한다」 전제가 이 PR 로 거짓이 됐으므로 그 근거 주석에 예외 명시 |
| 2 | Gradle 참조 파서가 `project(":modules:X")` 형태만 인식 — `project(path = …)` 등은 **간선이 조용히 사라진다** | 못 읽은 `:modules:` 를 세어 불일치면 **전 모듈**로 넓힘 |
| 3 | `app` 단독 변경이 전 모듈로 넓혀지는데 주석은 「예상 밖」이라 적혀 있었다 | 실제 동작에 맞춰 정정 + 왜 그 값을 택했는지(빈 매트릭스 금지) 명시 |
| 4 | 주석은 「app 간선을 남긴다」고 했는데 `moduleGraph()` 가 `app/build.gradle.kts` 를 **한 번도 안 읽었다** | 정의역에 `app` 포함 — 경유 폐포가 끊기면 좁아진다 |
| 5 | `git diff` 기본값이 비-ASCII 경로를 따옴표 이스케이프로 내보내 **귀속·확장 판정을 둘 다 빠져나가 무시**된다 | `-c core.quotePath=false` + `-z` + `xargs -0` |
| 6 | `select` 잡만 시스템 node 에 의존 — `runner-health` 는 그것을 검사하지 않는다 | `setup-node@v4` 추가 |

부수. 마스터 표 집계 `⬜ 22건 · ✅ 9건` 이 실측과 어긋나 있었다(그 줄 자신이 `TODOS.md` 의
`grep -c '^## ⬜'` 와 같아야 한다고 선언한다) → **⬜ 20 · ✅ 62** 로 정정.

## 뮤테이션 최종 — 9종 전부 RED

| 뮤테이션 | 잡은 케이스 |
|---|---|
| M1. 역의존 폐포 제거 | shared-kernel 전파 · 전이·테스트 간선 |
| M2. 모듈 밖 백엔드 변경 무시 | 섞인 입력 2종 |
| M3. 빈 입력에서 공집합 | 어떤 입력에도 0개를 안 준다 |
| M4. 테스트 의존 간선 제외 | 단일 모듈 선별 · 파싱 누락 감지 · 넓/좁 보고 |
| M5. 매트릭스를 고정 목록으로 | 배선 · 두 목록 차단 |
| M6. 알 수 없는 모듈 경로 무시 | 섞인 입력 |
| M7. 파싱 누락 감지 제거 | **이음매로 주입한** 비표준 참조 케이스 |
| M8. 선별기를 WIDEN 에서 제거 | 섞인 입력 |
| M9. backend-ci paths 에서 선별기 제거 | 트리거 2곳 배선 |

### ★★같은 양식이 **세 번** 나왔다 — 바깥 fallback 이 안쪽 분기를 가린다

M2 · M6 · M8 이 전부 같은 이유로 1차에서 살아남았다. **단일 파일 입력만 쟀기 때문**이다 —
그 파일 하나만 넣으면 씨앗이 비어 가장 바깥 fallback(「대상 모듈을 못 찾았다 → 전 모듈」)이
대신 넓혀 준다. 그래서 안쪽 분기를 **통째로 지워도** 통과했다.

실제 위험은 **섞인 입력**이다. 모듈 파일이 함께 바뀌면 씨앗이 비지 않아 fallback 이 발동하지
않고, 안쪽 분기가 없으면 **좁혀진 채 머지된다.**

**교훈.** fallback 이 여러 겹인 코드에서는 **각 분기가 단독으로 발동하는 입력**을 만들어야 한다.
가장 바깥 fallback 이 넓게 잡아 주면 안쪽 분기의 부재가 보이지 않는다.

### ★M7 은 이음매 없이는 원리적으로 못 쟀다

「Gradle 참조를 못 읽으면 넓힌다」는 방어는 **현재 저장소에 비표준 참조가 하나도 없어** 그
분기를 지워도 아무것도 안 깨졌다. 실제 파일을 비표준으로 바꿔 둘 수는 없으므로
`BTS_BACKEND_MODULES_ROOT` 이음매로 가짜 모듈 트리를 주입해 그 분기를 **실제로 밟게** 했다.
`BTS_RUNNER_ROOT`·`BTS_GH_BIN` 과 같은 관례다.

## 계획 대비 실제 (deviation)

| 계획 | 실제 | 사유 |
|---|---|---|
| 뮤테이션 5종 | **6종** | 「알 수 없는 모듈 경로」를 별도로 재야 했다 |
| — | **기존 가드 재조준** | `ci-module-coverage.test.ts` 가 고정 매트릭스 목록을 읽고 있었다. 지우지 않고 「선별기 전체 집합 ↔ Gradle include」로 다시 겨눴다 — 두 목록이 여전히 독립이라 계약의 실질이 유지된다 |
| — | `app` 제외의 근거를 판별식화 | 매트릭스에서 빼는 것 자체는 정당하나 **빠졌는데 아무도 안 도는** 상태가 위험하다. 제외 모듈은 backend-ci 안에 전용 잡이 있는지까지 확인한다 |

## 리뷰 결과

_fast-track (chore) — 생략. 게이트 2 의 /bts-codereview 는 그대로 실행한다._
