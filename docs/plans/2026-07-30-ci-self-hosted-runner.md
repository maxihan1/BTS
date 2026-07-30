# GitHub Actions CI를 self-hosted 러너로 전환

> slug: ci-self-hosted-runner
> type: chore (인프라/CI 설정)
> agent: 컨트롤러 직접 수행 (sub-agent 호출 금지 지시)
> 생성: 2026-07-30

## Brief

### 왜 지금

2026-07-29 17:32 KST 부터 **GitHub Actions 결제 차단으로 CI 전체가 죽어 있다.**

- 실측 서명 — 잡이 `steps=0` · `runner_name` 없음 · 시작 2~12초 만에 종료.
- 어노테이션 원문 — `The job was not started because recent account payments have failed or your spending limit needs to be increased.`
- 차단 이후 실행 **12/12 전부** 같은 어노테이션. 경계 이전에는 `failure` 가 **0건**(성공 아니면 cancelled) — 양성·음성 대조가 깨끗하게 갈린다.

| 시각 (KST) | 실행 | 결과 |
|---|---|---|
| 07-29 17:15 | `fix/assembly-nonprod-bean-wiring` (#321) PR | ✅ 마지막 성공 |
| 07-29 **17:32** | main push (#321 머지) | 🔴 첫 차단 |
| 07-30 00:25 | main push (#322 머지) | 🔴 차단 |
| 07-30 07:25 | main push (#323 머지) | 🔴 차단 |

⇒ **#322 · #323 은 CI 0회로 머지됐다.** 로컬 검증 기록은 #323 에만 남아 있다.

### 이미 끝난 준비 (이 PR 범위 밖, 코드 변경 0)

- self-hosted 러너 `maxi-mac-bts` 등록 완료. 라벨 `self-hosted, macOS, ARM64, bts-local`.
  설치 위치 `~/actions-runner-bts`(저장소 밖), launchd 상시 서비스, 상태 `online`.
  tarball sha256 `8e8839c4…b079` 검증 통과.
- **R8 판별 완료** — 확인 사격(run `30508738275`, 폐기 브랜치)에서 `steps=4` · `success`.
  로그 실측 `RUNNER_OS=macOS ARCH=ARM64 NAME=maxi-mac-bts` · Docker `29.1.2` · `openjdk 21.0.11`.
  ⇒ **결제 차단은 GitHub 호스팅 러너에만 적용되고 self-hosted 는 무관하다**가 실증됨.
  폐기 브랜치·worktree 정리 완료.

### 이 PR 이 하는 일

`.github/workflows/` 3개 파일의 `runs-on` **9곳**을 self-hosted 로 옮기고, 옮기면서 **새로 생기는 충돌면**을 함께 봉합한다.

### 착수 전 신규 위험 전수 (#323 에서 유효했던 R표 방식)

「공유 자원(러너·포트·워크스페이스)을 옮기는 변경은 옮긴 뒤 무엇이 겹치는지 표로 셀 것」 — 메모리 `vite-preview-port-cors-align-done`.

| # | 충돌면 | 실측 근거 | 심각도 | 상태 |
|---|---|---|---|---|
| R1 | **5433 포트 선점** — `bts-postgres-dev` 컨테이너 상시 LISTEN | `lsof` PID 23698 · `docker ps` | 🔴 즉시 차단 | 설계 갈림길 |
| R2 | self-hosted 는 **워크스페이스를 재사용**한다 (ubuntu-latest 처럼 매번 새 VM 아님) | 러너 동작 | 🔴 이전 실행 `build/`·테스트 XML 잔재 → 거짓 초록 | 설계 갈림길 |
| R3 | 러너 1대 = 동시 1잡. 현재 **12잡 병렬** | 매트릭스 9 + 별도 3 | 🟡 벽시계 33분+ 회귀 | 설계 갈림길 |
| R4 | 러너 꺼지면 **queued 무한 대기** (실패로도 안 끝남) | 러너 동작 | 🟡 | 봉합 대상 |
| R5 | `runs-on` **9곳** (backend 4 · frontend 3 · infra 2) | grep 실측 | 🟡 일부만 옮기면 나머지 계속 차단 | 봉합 대상 |
| R6 | Testcontainers 가 로컬 Docker 공유 | `bts-postgres-dev` · `db`(mariadb) 상시 | 🟢 랜덤 포트라 충돌 낮음 | 관찰 |
| R7 | `setup-java`·`setup-node`·`setup-gradle`·`pnpm/action-setup` 의 macOS ARM64 지원 | Java 21.0.11 · Node 24.5.0 · pnpm 11.1.3 · arm64 실측 | 🟢 전부 지원 | 관찰 |
| R8 | 결제 차단이 self-hosted 까지 막는가 | 확인 사격 `steps=4 success` | ✅ **해소** | 완료 |

### 판별식 영향 실측

`scripts/workflow/ci-module-coverage.test.ts` 는 `backend-ci.yml` 을 읽지만 **`runs-on` 은 단언하지 않는다**
(매트릭스 모듈 집합 ↔ `settings.gradle.kts` 차집합 + `app` 별도 잡 존재만 확인).
⇒ **잡 구조를 유지하면 이 판별식은 안 깨진다.** 구조를 바꾸면 같은 PR 에서 판별식도 갱신해야 한다.

### 범위 밖 (별건으로 남긴다)

- `backend-ci` 트리거에 `docs/plan/product/**` 부재 — `bc-keyword-coverage.test.ts` 가 그 경로를 읽는데
  트리거에 없어 FR 문서만 바꾸는 PR 에서 판별식이 0회 실행된다. #320 에서 실증
  (`ui/fr-ux-07-active-project-key` 브랜치 실행 7건 전부 frontend-ci, backend-ci 0건 — CI 정상 구간).
  **결제 차단과 독립인 별개 결함.** 같은 파일 1줄이라 이 PR 에 합칠지는 게이트 1 판단 항목.
- GitHub 결제 자체 해결 (Maxi 계정 작업).

## 도메인 정리 (← /bts-domain)

**생략** — 도메인 용어·BC 코드 0. `classify` 의 `primary_bc=automation` 은 "Actions" 오매칭이며
BTS 의 automation BC(룰 자동화 도메인)와 무관하다.

## 스펙 (← /bts-spec)

**생략** — FR 없음, 사용자 노출 동작 변화 없음. FR 총수 불변 139.

## Brainstorming Check (← /bts-spec Phase B)

**생략** (위와 동일 사유)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
