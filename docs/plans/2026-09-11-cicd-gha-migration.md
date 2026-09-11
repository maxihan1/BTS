# 계획 — CI/CD 다이어트 (검증 GHA · 배포 젠킨스)

> 스펙 `docs/specs/2026-09-11-cicd-gha-migration.md`. 티어 **T2** (가드·CI 표면).

## 순서 원칙 — 검증 공백 0

젠킨스는 지금 **검증과 배포의 유일 경로**다. 먼저 끄면 백엔드·프론트·시각회귀·조립부팅·
인프라봉인이 동시에 0회가 된다. 그래서 **세우고 → 초록을 보고 → 내린다.**

## PR ① 선행 수정 — 참조 정합 구멍 (T2) — **완료**

- [x] 1-1 `discriminant-reference-integrity.test.ts` 의 `LIVE_ROOTS` 에 `Jenkinsfile.e2e` 추가
      → **red 를 먼저 본다.** 필터에 `f.startsWith('Jenkinsfile.')` 가 있는데 목록에
      `Jenkinsfile.e2e` 가 없어 **도달 불가 조건**이었다 (가짜 그린 양식)
- [x] 1-2 red 로 드러난 dangling 참조를 고친다
- [x] 게이트 — 판별식 전량 EXIT=0 (**811건** 으로 늘었다 · GHA 24초)

## PR ② 실측 — runner-smoke (T2) — **완료**

- [x] 2-1 `.github/workflows/runner-smoke.yml` 작성. 스펙 §6값을 재고 artifact 로 남긴다
- [x] 2-2 푸시 후 1회 실행 → 6값 수집
- [x] 게이트 — 6값 수집. timeout 은 백엔드 35분 · 프론트 40분 · 조립 20분으로 확정

## PR ③ 검증 워크플로우 (T2) — **완료**

- [x] 3-1 `verify.yml` — route 잡이 `select-test-scope.ts` 를 1회 실행해 outputs 로 낸다
      (**YAML 에 판정을 다시 적지 않는다** — Jenkinsfile:255 경고)
- [x] 3-2 discriminants 잡 — 조건 없이 무조건
- [x] 3-3 frontend · backend(matrix 9) · assemble 잡
- [x] 3-4 차집합 판별식 — 「YAML 이 실행하는 명령 ⊆ 계산기 출력」. **비-공허 짝 필수**
- [x] 게이트 — **run 34617888924 전 잡 성공**

## PR ④ E2E 전량 + 시각 회귀 (T2) — **완료**

- [x] 4-1 `e2e.yml` — 819건 샤딩. 샤드 수는 PR ② 실측으로 결정
- [x] 4-2 vite dev 서버 불안정 해소 (맥 7회 중 3회 red · `ERR_CONNECTION_REFUSED`)
- [x] 4-3 MSW 게이트를 **점 표기**로 (`import.meta.env.VITE_ENABLE_MSW`).
      대괄호 표기는 Vite 정적 치환이 안 돼 **운영 번들에 MSW 가 실린다** (스크래치 빌드 실측)
- [x] 4-4 시각 회귀 소유자를 `e2e.yml` **하나로** 일원화 (중복 실행 금지)
- [x] 게이트 — 전량 초록. flaky 원인(MSW 재기동 창)을 찾아 신호를 만들었다.
      ★**3회 연속은 아직 안 봤다** — 1회 초록이다. 머지 전에 재확인할 것

## PR ⑤ 게이트 배선 + 젠킨스 축소 (T2)

- [x] 5-1 `jenkins-build-status.ts` → 검증(GHA `gh pr checks`) + 배포(젠킨스) 2출처 합성.
      **「체크 0건」을 통과로 읽지 않는다** (`merge-skill-contract.test.ts` 계약)
- [x] 5-2 (route 잡이 `requires-full-build` 를 앞세워 공급) 배포 잡의 `RUN_DEEP`·`GIT_BRANCH_NAME` 공급원 확정 (4개 설계가 전부 미룬 자리)
- [ ] 5-3 젠킨스 검증 stage 철거 — **Maxi 확정 2026-09-11: 포함.** GHA 초록을 본 뒤 같은 작업에서
      지운다. 운영 VM 의 `mem_limit 10g` · `cpus 2.0` 회수가 목적이다
- [ ] 5-4 판별식 27개 재조준 (Jenkinsfile → `.github/workflows`)
- [ ] 게이트 — 배포 1회 성공

## PR ⑥ 문서 전수 동기화 (T1)

- [x] 6-1 `CLAUDE.md` · `DEVELOPMENT.md` · `behavior-rules.md` · `bts*/SKILL.md`
- [x] 6-2 `bts-impl/SKILL.md:115` 의 「34개 파일」 → 개수를 지운다
      (CLAUDE.md 가 금지한 「지시문에 개수」 그 자체. 실제는 792건)
- [ ] 6-3 런북 drift 2건 — 「시각 회귀 막는 기계 0개」·「배포 실행 0회」가 코드보다 낡았다
- [ ] 6-4 공개 저장소용 `README.md` · `SECURITY.md` · `CODEOWNERS`

## 결정 기록

| 결정 | 근거 |
|---|---|
| `paths:` 안 쓴다 | 실측 매칭 0건 6파일. 걸면 CI 부재 + required check 영구 pending |
| 판정 정본 = 계산기 | Jenkinsfile:255 가 「두 목록」을 미리 경고. YAML 재기술 금지 |
| E2E 소유자 = `e2e.yml` 하나 | 설계 2안이 6샤드+8샤드로 두 번 돌렸다 |
| 액션 버전 = 현재 메이저 | 설계가 핀한 8종이 전부 node20(구형). 현재는 node24 |
| 젠킨스 철거는 마지막 | 지금 검증·배포 유일 경로. 먼저 끄면 검증 0회 구간이 생긴다 |

## 미해결 — Maxi 확인

- ~~젠킨스 검증 stage 철거 범위~~ → **해소 2026-09-11. 포함한다** (GHA 초록 확인 후).
- ~~저장소 공개가 실측의 선행인가~~ → **해소 2026-09-11. 아니다.**
  push 34602622965 가 private 상태에서 **queued 로 들어갔다** — 3주 전 「결제 차단으로
  `ubuntu-latest` 가 steps=0 으로 죽었다」는 상태는 이미 풀려 있었고, 그 뒤로 아무도
  다시 시도하지 않아 몰랐다. 공개 전환은 **병렬 20잡 무료**를 위한 것이지
  「GHA 를 쓸 수 있느냐」의 조건이 아니다. 공개 시점과 이 작업의 순서를 분리할 수 있다.

## Jira 대조

대응 없음 — 빌드 파이프라인 인프라 작업이라 Jira Cloud 에 대조할 사용자 화면이 없다.
ADS 준용 대상도 아니다 (렌더되는 UI 를 만들지 않는다). 계약 §1 5단계 면제 경로.


---

## 남은 것 — 다음 작업으로

### ⓐ 젠킨스 검증 stage 철거 — **1·2단계 완료 · 3단계부터 미착수**

**완료한 것 (2026-09-11).**

- **1단계 선재 결함** — `.github/workflows/` 가 `surfaces.ts` GUARD_CI 와
  `select-backend-modules.ts` 두 정본에서 빠져 있었다. 실측으로 `detect-tier` **T1 UNMAPPED** ·
  `requiresFullBuild` **false** — CI 를 통째로 바꾸는 PR 이 한 모듈로만 검증될 수 있었다.
  두 곳 다 2026-09-09 P4b 가 지운 자리이고 주석이 그 위험을 미리 적어 두었다.
  되돌린 뒤 T2 · GUARD_CI · true 로 확인했고 **backend 매트릭스 9모듈**을 실행으로 봤다.
- **2단계 짝 없는 것 이관** — `verify-master-plan.sh` 와 `:modules:app:nonProdAssemblyTest` 를
  GHA 로 옮겼다. 후자는 `Jenkinsfile:525` 가 **저장소에서 유일한 실행 배선**이라
  젠킨스를 먼저 지웠으면 빨간불도 없이 0회가 됐을 자리다.
  `build-doc-index.mjs --check` 는 일부러 안 옮겼고 **그 경고문까지 함께 옮겼다.**

**3단계부터 안 한 이유 — 배포 실측과 한 세트다.**

조사가 밝힌 단일 실패점은 「배포 승인 stage 가 실제로 뜨는가」이고, 그 실패는
**빌드가 SUCCESS 인 채로 배포만 조용히 skip 되는** 형태다. `when` 이 unset env 를
비교하면 거짓이 되고 선언적 파이프라인은 그것을 실패가 아니라 skip 으로 처리한다.
즉 **머지 후 배포를 1회 돌려봐야만** 검출된다 — 코드만 바꾸고 확인을 못 하면
「배포 수단이 사라졌는데 초록」을 열어 둔 채 끝내는 것이다.

아래 순서와 검증 방법은 조사로 확정돼 있다.

Jenkinsfile 756줄 14 stage 중 **검증 계열 9개**가 GHA 와 이중으로 돈다.
정합 게이트 · 프론트 정적 · 빠른 게이트 · DB 마련 · 전량 · 시각 기준 생성 · 조립 부팅 등.

★**이번 세션에서 손대지 않았다.** 배포 경로를 건드리고 판별식 27개가 얽혀 있어,
  컨텍스트가 얕은 상태에서 하면 배포 수단이 사라질 수 있다. 순서는 정해져 있다.

  1. `전량 판정` stage 가 공급하는 `env.RUN_DEEP`·`env.GIT_BRANCH_NAME` 의 대체를 먼저 세운다
     (`:572`·`:574`·`:607`·`:608` 이 소비한다). 이것 없이 지우면 **배포가 빨간불 없이 skip** 된다.
  2. 검증 stage 제거 → `Jenkinsfile` 을 배포 전용으로 축소
  3. 판별식 27개 재조준 — 목록은 **파일명이 아니라 본문 grep** 으로 만든다
     (`node-ts-invocation`·`select-backend-modules`·`diff-base` 등 8개는 이름에 단서가 없다)
  4. `BTS_SKIP_DEPLOY_TEST` 차단이 Jenkinsfile 에만 있다 — 배포를 옮기면 이식이 필수다

### ⓑ E2E 안정성 — **완료**

전량 초록 **3회 연속** 확인(34617888924 · 34619280367 · rerun). 중간에 백엔드가 실패한
실행이 있었으나 E2E 는 세 번 다 초록이었다 — 그 백엔드 실패가 아래 부수 발견 둘로 이어졌다.

### ⓒ `board-reorder.spec.ts:329`

이번 실행에서는 통과했으나 이전에 한 번 실패했다(증거는 artifact 업로드 ETIMEDOUT 으로 유실).
MSW 신호 도입으로 해소됐을 수 있으나 **확증은 없다.** 재발하면 trace 로 드롭 대상을 읽는다.

### ⓔ 부수 발견 — CI 이관이 아니었으면 몰랐을 둘

- **MinIO 가 Docker Hub 에서 사라졌다.** `curl hub.docker.com/v2/repositories/minio/minio/`
  → `object not found`. 배포는 quay.io 로 계속된다. 9곳을 옮겼고 **버전은 그대로다.**
  ★젠킨스에서는 러너 캐시가 이미지를 붙잡고 있어 안 보였다 — 「깨끗한 머신에서 처음
  받을 때」만 드러나는 결함이고 GHA 는 매번 새 머신이다.
  `testcontainers-image-registry.test.ts` 로 재발을 막되 **네트워크 조회는 하지 않는다.**
- **ktlint 가 「바뀐 모듈만」 돌아 안 보였다.** 전량화하자 즉시 잡혔다 —
  다만 잡힌 것은 기존 부채가 아니라 **그 직전 커밋에서 내가 만든 들여쓰기**였다.

### ⓓ 프론트 벽시계

12분 1초로 백엔드 최장과 비슷하다. 캐시가 붙은 뒤라 초안의 「73% 가 준비 비용」은
더 이상 맞지 않는다 — `pool: 'threads'` 튜닝은 **실측을 다시 하고** 판단할 것.
