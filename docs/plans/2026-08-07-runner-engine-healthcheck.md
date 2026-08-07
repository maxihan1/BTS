# self-hosted 러너 엔진 헬스체크 + 런북 실패 모드 문서화

> slug: runner-engine-healthcheck
> type: chore (classify 오분류 `feature` 정정)
> agent: qa-engineer (classify 오분류 `backend-engineer` 정정)
> primary_bc: null (BC 무관)
> 생성: 2026-08-07

## Brief

2026-08-04 18:18 에 홈 폴더 대용량 파일 정리로 self-hosted 러너의 **실행 엔진 4개가 삭제**되어
**8/4~8/7 사흘간 모든 CI 가 0회 실행**됐다. 빨간불이 뜨긴 했으나 「테스트 실패」와 구분되지
않아 아무도 러너를 의심하지 않았고, 그 사이 #342·#343·#344 가 검증 없이 머지됐다.

같은 일이 또 나도 **사흘이 아니라 첫 실행에서** 잡히도록 조기 발견 장치를 넣고, 재발 원인
(사람의 용량 정리)을 런북에 명시한다.

배경 정본 — 메모리 `self-hosted-runner-node-binaries-swept`.

## 착수 전 실측 (2026-08-07)

### 무엇이 지워졌나 — 기준은 「이름」이 아니라 「홈 폴더 안 + 큰 파일」

| 파일 | 크기 | 위치 | 결과 |
|---|---|---|---|
| `externals/node24/bin/node` | 115M | 홈 안 | ❌ |
| `_tool/node/22.23.2/arm64/bin/node` | 112M | 홈 안 | ❌ |
| `externals/node20/bin/node` | 86M | 홈 안 | ❌ |
| `_tool/Java_Temurin-Hotspot_jdk/21.0.11-10.0.LTS/arm64/…/lib/modules` | ~130M | 홈 안 | ❌ |
| `_tool/Java_…/lib/ct.sym` | 10M | 홈 안 | ✅ 생존 |
| `/usr/local/bin/node` | **210M** | 홈 **밖** | ✅ 생존 |

210M 이 살고 86M 이 죽었으므로 **크기 단독 기준이 아니라 「홈 폴더 스코프 + 큰 파일」**이다.
4곳의 `bin/`·`lib/` 디렉터리 mtime 이 **전부 `Aug 4 18:18`** 로 동일 = 단일 시점 작업.
같은 10분 창에 `~/.cursor`·`~/.docker`·`~/.gradle` 등 홈 최상위 수십 개의 mtime 이 바뀌고
`.DS_Store` 가 생성됐다 = **GUI 도구가 홈 전체를 훑은 서명**. `/Applications/Disk Inventory.app`
설치돼 있고 데이터 볼륨 **83% 사용** 중이었다.

배제 — 러너 self-update(`_diag` 흔적 0건, 게다가 update 는 `_tool/` 을 안 건드린다) ·
저장소 스크립트(`actions-runner` 참조 0건) · Claude 세션(8/4 17~20시 세션 전수 확인, 파괴적
명령은 worktree 임시파일과 `.bts-cache` 뿐).

### ★ 이 결함은 「파일 존재 확인」으로 못 잡는다

| 대상 | 파일 상태 | 실행 |
|---|---|---|
| `externals/node24/bin/` | `corepack`·`npm`·`npx` 심볼릭 **존재** | `node` 자체가 없음 |
| `_tool/node/22.23.2/arm64` | `arm64.complete` 표식 **존재** → setup-node 가 **캐시 히트로 오판** | 시스템 node v22.14.0 으로 조용히 흘러내림 |
| `_tool/Java_…/bin/` | `java` 포함 30개 실행파일 **전부 존재** | `Failed setting boot class path` (`lib/modules` 부재) |

**세 경우 모두 `test -f` 는 통과한다.** 점검은 반드시 `node -v` / `java -version` 의
**실행 성공(exit 0)** 을 봐야 한다.

### ★★ 설계 핵심 전제 — 아직 실측 미완

「`actions/checkout` 앞의 순수 shell `run:` 스텝은 node 부재 상황에서도 실행된다」

**현재 근거는 정황이고 직접 실측이 아니다.**
- 사고 기간 실패 run 전수에서 **첫 스텝이 `Checkout`(JS 액션)이라** `run:` 스텝의 거동을 관측한
  사례가 **0건**이다. 어느 워크플로우도 checkout 앞에 `run:` 을 두지 않는다.
- 간접 근거 — 실패 run 에서 **`Set up job` 단계는 완료됐고** 에러가 `Checkout` 스텝에 귀속됐다.
  즉 node 해석이 **job 초기화 시점이 아니라 스텝 실행 시점**에 일어난다.
  `_diag/Worker_20260806-234940-utc.log` 에 `NodeScriptActionHandler` 가 **2회**만 등장한다
  (Checkout + Post Checkout) — `run:` 스텝용 핸들러는 별개다.
- **plan 단계에서 이 전제를 실측할 방법을 정한다.** 전제가 깨지면 CI 스텝 안은 이 결함을
  구조적으로 못 잡으므로 **설계 자체가 바뀐다**(아래 대안 B 로 이동).

### 현재 러너 상태 (복구 진행분)

| 대상 | 상태 |
|---|---|
| `externals/node20` | ✅ v20.20.2 (2026-08-07 복원, sha256 대조) |
| `externals/node24` | ✅ v24.18.0 (〃) |
| `_tool/node/22.23.2` | ✅ v22.23.2 (표식 삭제 → setup-node 자가 재설치) |
| `_tool/Java_…21` | ❌ **`Failed setting boot class path` — 미복구.** 표식 `arm64.complete` 잔존 |

Java 는 backend-ci 3개 잡이 `actions/setup-java@v4` 로 쓰므로 **다음 백엔드 PR 이 첫 희생자**다.

## 설계 후보 — plan 단계에서 확정

| | 안 | 장점 | 약점 |
|---|---|---|---|
| **A** | 각 워크플로우 checkout **앞**에 shell `run:` 헬스체크 | CI 가 스스로 잡음. 첫 실행에서 발견 | **전제 미검증**. 워크플로우 4개 전부 배선해야 하고 누락 시 공허 |
| **B** | `scripts/verify-runner-health.sh` + `/bts-start` 에서 호출 | 전제 불필요(Maxi 머신에서 실행). 작업 시작 시점에 발견 | Maxi 가 작업을 시작해야만 돈다. CI 단독 실행은 못 잡음 |
| **C** | A + B 병행 | 두 지점 모두 커버 | 비용 최대. 과한지 판단 필요 |

**판별식(모든 워크플로우에 배선됐는지 강제)을 추가할지도 plan 에서 비용 대비 효과로 판단한다** —
`two-lists-never-check-each-other` 양식이라 배선 누락이 곧 공허 가드가 된다.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
