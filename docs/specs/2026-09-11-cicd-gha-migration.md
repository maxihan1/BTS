# CI/CD 다이어트 — 검증을 GitHub Actions 로, 배포는 젠킨스에

> Maxi 지시 2026-09-11. 계획 `docs/plans/2026-09-11-cicd-gha-migration.md`.

## 왜

2026-09-10 PR #481 이 GitHub Actions 4종 736줄을 폐기하고 젠킨스로 옮겼다. 그 동기는
`docs/runbooks/self-hosted-runner.md` 에 기록돼 있다 — 「결제 차단으로 `ubuntu-latest` 가
steps=0 으로 죽었다. 유료 결제 없이 GitHub 무료 한도 안에서만 운영한다」(2026-07-30 Maxi 확정).

**저장소 공개 전환이 그 제약을 없앤다.** 공개 저장소의 GitHub-hosted 러너는 병렬 20잡까지 무료다.
옛 파이프라인의 최대 낭비가 정확히 여기 있었다 — backend-ci 최악 벽시계 135.0분 중
**잡 실행 합계는 43.9분이고 나머지 67% 가 러너 1대를 기다린 큐 대기**였다(run 32307915897 실측).

동시에 젠킨스 설정은 1,999줄로 구 Actions 736줄의 **2.72배**다. 「복잡해졌다」는 체감은 실측과 맞다.

## 무엇을

| 층 | 전 | 후 |
|---|---|---|
| 검증 (백엔드·프론트·판별식·E2E·시각회귀) | 젠킨스 | **GitHub Actions** |
| 배포 (운영 VM 접근) | 젠킨스 | **젠킨스 유지** |
| 저장소 밖 의존 (`build-doc-index --check`) | 로컬 훅 | 로컬 훅 유지 |

E2E 는 **MSW 목킹을 유지**하고(실 백엔드 기동 안 함), 지금 「바뀐 스펙만」 도는 것을
**전량 CI 편입**으로 바꾼다 — 169 파일 819건이 파이프라인 어디에서도 안 도는 상태를 끝낸다.

## 티어별 최소 — `paths:` 는 쓰지 않는다

「티어별 최소 CI」의 순진한 구현은 `on.push.paths:` 필터다. **이 저장소에서는 틀린 답이다.**

실측 — `surfaces.ts` 의 60 글로브로 `paths:` 를 생성하면 다음이 **매칭 0건**이다.

    apps/web/playwright.config.ts   apps/web/vite.config.ts   apps/web/vitest.config.ts
    apps/web/tsconfig.app.json      backend/modules/<bc>/build.gradle.kts
    infra/docker-compose.dev.yml

`playwright.config.ts` 의 `testIgnore` 한 줄이 E2E 819건을 끈다. 그 파일을 고치면 워크플로우가
**아예 안 뜬다** — 빨간불이 아니라 부재다. `paths:` 로 걸러진 워크플로우를 브랜치 보호의
필수 체크로 걸면 PR 이 영구 pending 이 된다.

근본 원인은 범주 오류다. `surfaces.ts` 는 **「어떤 절차를 밟을까」의 분류기**이지
**「무엇이 바뀌면 무엇을 돌릴까」의 목록**이 아니다. 후자의 정본은 이미 따로 있다 —
`scripts/workflow/select-test-scope.ts` 와 `select-backend-modules.ts` 의 `WIDEN_PREFIXES`.

**처방.** 워크플로우는 조건 없이 항상 뜬다. 무엇을 돌릴지는 `route` 잡이 **기존 계산기를 1회 실행**해
`$GITHUB_OUTPUT` 으로 내고, 나머지 잡이 `needs.route.outputs.*` 를 `if:` 로 읽는다.

이것이 Jenkinsfile:255 가 미리 적어 둔 경고를 지키는 유일한 방법이다.

> 판정 목록을 여기 적지 않는다. `WIDEN_PREFIXES`(select-backend-modules.ts)가 정본이다 —
> Groovy 로 다시 적으면 두 목록이 되고 **새 CI 파일이 생길 때 한쪽만 고쳐진다**.

새 CI 파일이 생기는 지금이 그 경고가 겨냥한 순간이다. YAML 에 판정을 다시 적으면
이 저장소의 지배 결함 양식(「두 목록이 서로를 안 본다」)을 스스로 재생산한다.

## 워크플로우 토폴로지 — 정본 1장

    .github/workflows/verify.yml        검증 정본. PR·push 공통. paths 필터 없음.
      route          계산기 1회 → outputs: full, deep, modules[], frontend, e2e
      discriminants  판별식 792건. **조건 없이 무조건** (실측 29.2초)
      frontend       typecheck · lint · vitest        if: route.outputs.frontend
      backend        matrix 9 BC                      if: route.outputs.modules != '[]'
      assemble       :modules:app (시드된 DB 필요)     if: route.outputs.deep

    .github/workflows/e2e.yml           E2E 전량 + 시각 회귀. 샤딩. 소유자는 **여기 하나**.

    .github/workflows/runner-smoke.yml  일회성 실측. 아래 6값을 재고 폐기한다.

배포는 `Jenkinsfile` 이 계속 소유한다. 검증 stage 철거는 **GHA 초록을 본 뒤**에 한다.

## 착수 전 실측 필수 — 6값

설계 벽시계 추정이 실측과 어긋난다. 맥 8코어 웜 상태에서 `:modules:issue-tracking:test` 가
**9분 13초**다(테스트 331파일 · Testcontainers 75클래스 · Flyway 39건). 4 vCPU 리눅스 콜드면
이 한 모듈이 20~30분일 수 있고 그 위에 세운 timeout 은 동전던지기다.

`runner-smoke.yml` 로 다음을 재기 전에는 본 구현에 착수하지 않는다.

1. 최장 모듈(`issue-tracking`) 벽시계 — 콜드 캐시
2. `generateJooq` 콜드 비용 (jOOQ 산출물은 `.gitignore:21` 로 미추적 → 매트릭스 잡마다 재생성)
3. **시드 없는 순정 postgres 에서 `:modules:app:test` 가 통과하는가**
   (`ProdAssemblyHttpTestBase.kt:33` 이 손수 시드된 dev DB 를 전제로 적는다)
4. 판별식 792건이 GHA 에서 몇 건 실패하는가
5. 시각 회귀 기준 PNG 4장이 GHA 러너와 픽셀 일치하는가
6. `-Xmx4096m` + kotlin daemon 3GB(합 7GB) 선언이 러너 사양에서 성립하는가

## 비-범위

- 저장소 공개 전환 자체 — **Maxi 가 직접 한다.**
- LICENSE 선택 — Maxi 판단.
- 실 백엔드 기동 E2E — MSW 유지로 확정.

## Jira 대조

대응 없음 — 빌드 파이프라인 인프라 작업이라 Jira Cloud 에 대조할 사용자 화면이 없다.
ADS 준용 대상도 아니다 (렌더되는 UI 를 만들지 않는다). 계약 §1 5단계 면제 경로.
