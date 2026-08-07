<!-- GitHub Actions self-hosted 러너 운영 절차 — 신원·증상·복구·되돌리기 -->

# self-hosted 러너 운영 (`maxi-mac-bts`)

> 도입 2026-07-30 (PR #324). 도입 사유는 §1, 무료 한도 정책은 §6.
>
> **★이것은 임시 조치가 아니라 영구 설계다.** 2026-07-30 Maxi 확정 —
> **유료 결제를 하지 않고 GitHub 무료 한도 안에서만 운영한다.** self-hosted 러너 잡은 분(minute)
> 과금 대상이 아니므로 무료 한도를 **1분도 쓰지 않는다.** 「결제가 복구되면 되돌린다」는 전제로
> 이 문서를 읽지 마라 — 되돌릴 계획이 없다. §5 는 조건이 바뀌었을 때를 위한 참고 절차일 뿐이다.

## 1. 왜 self-hosted 인가

**2026-07-29 17:32 KST 부터 GitHub Actions 결제 차단으로 `ubuntu-latest` 잡이 배정조차 되지 않았다.**

실측 서명 — 잡이 `steps=0` · `runner_name` 비어 있음 · 시작 2~12초 만에 종료. 어노테이션 원문.

```
The job was not started because recent account payments have failed
or your spending limit needs to be increased.
Please check the 'Billing & plans' section in your settings
```

차단 이후 실행 **12/12 전부** 같은 어노테이션이었고, 경계 이전에는 `failure` 가 **0건**이었다
(성공 아니면 cancelled). 그 사이 **#322 · #323 이 CI 0회로 머지**됐다.

**핵심 — 이 상태는 "테스트가 깨졌다" 와 구분이 안 된다.** 잡이 `failure` 로 표시되므로 로그를 열어
`steps=0` 을 확인하기 전까지는 원인을 오독한다. self-hosted 전환의 1차 목적은 속도가 아니라
**이 오독을 없애는 것**이다.

self-hosted 러너 잡은 분(minute) 과금 대상이 아니라 이 차단을 받지 않는다 —
확인 사격(run `30508738275`)에서 `steps=4` · `success` 로 실증했다.

## 2. 러너 신원

| 항목 | 값 |
|---|---|
| 이름 | `maxi-mac-bts` |
| 라벨 | `self-hosted`, `macOS`, `ARM64`, **`bts-local`** (워크플로우가 요구하는 것은 `bts-local`) |
| 설치 위치 | `~/actions-runner-bts` (저장소 밖) |
| 작업 디렉터리 | `~/actions-runner-bts/_work/BTS/BTS` — **Maxi 작업 트리와 별개** |
| 서비스 | launchd `~/Library/LaunchAgents/actions.runner.maxihan1-BTS.maxi-mac-bts.plist` |
| 로그 | `~/Library/Logs/actions.runner.maxihan1-BTS.maxi-mac-bts` |
| 버전 | 2.336.0 (osx-arm64), tarball sha256 `8e8839c4…b079` 검증 후 설치 |

상태 확인.

```bash
gh api /repos/maxihan1/BTS/actions/runners \
  --jq '.runners[] | "\(.name) \(.status) busy=\(.busy)"'
```

## 3. ★ 러너가 꺼지면 — 실패가 아니라 무한 대기다

**`timeout-minutes` 는 큐 대기 시간을 세지 않는다.** 러너가 오프라인이면 잡이 `failure` 로 끝나는 게
아니라 **`queued` 상태로 영원히 머문다.** PR 체크가 "대기 중" 으로 멈춰 있고 아무 알림도 오지 않는다.

증상 판별.

| 관측 | 원인 |
|---|---|
| 잡 `queued`, 러너 목록 비어 있거나 `offline` | 러너 정지 → §3 복구 |
| 잡 `queued`, 러너 `online busy=true` | 정상. 앞 잡이 끝나기를 기다리는 중 (러너 1대라 직렬) |
| 잡 `failure`, `steps=0`, 러너 이름 없음 | `ubuntu-latest` 로 되돌아간 잡. §5 판별식이 막지만 뚫렸다면 여기 |
| **잡 `failure`, 실패 스텝이 `Checkout`** | **러너 엔진(node·java) 부재** → §4 「러너는 켜져 있는데 엔진만 없을 수 있다」. 테스트는 한 줄도 안 돌았다 |
| run 자체가 **0건** (빨간불조차 없음) | squash 커밋 본문에 실린 `[skip ci]`. 머지 후 `gh api repos/maxihan1/BTS/commits/<sha>/check-runs --jq .total_count` 로 확인 |

복구.

```bash
~/actions-runner-bts/svc.sh status
~/actions-runner-bts/svc.sh start
```

## 4. 알아 둘 것

- **직렬 실행.** 러너 1대라 동시 1잡이다. 전 워크플로우 합계 17잡이 순차 실행된다.
  **실측 벽시계 45.1분** (잡 실행시간 합계 44.1분 = 거의 완전 직렬, 2026-07-30 PR #324).
  최장 잡은 `issue-tracking` 9.4분 · `identity-access` 7.0분 · `frontend test` 5.4분.
- **★러너를 증설하려면 assembly 잡을 함께 고쳐야 한다.** `CI_PG_CONTAINER` 이름과 55433 포트가
  고정이라, assembly 잡 두 개가 동시에 뜨면 뒤에 온 잡의 `docker rm -f` 가 **앞 잡의 DB 를 테스트
  도중에 죽인다.** 러너 1대일 때만 안전한 설계다.
  줄이려면 증설보다 `issue-tracking` 샤딩이 먼저다 — 그 하나가 벽시계의 21% 다.
- **`~/.gradle` · Docker 를 로컬 개발과 공유한다.** CI 실행 중 로컬 `./gradlew` 동시 실행은 피한다.
  워크스페이스 자체는 `actions/checkout@v4` 기본값 `clean: true`(`git clean -ffdx`)가 매 잡마다
  `build/`·`node_modules/`·테스트 XML 을 지우므로 잔재성 거짓 초록은 발생하지 않는다.
- **★`services:` 블록을 쓰지 마라 — macOS 러너는 지원하지 않는다.** 2026-07-30 실측
  (run `30514310932`)에서 `Initialize containers` 단계가 다음 에러로 죽었다.
  ```
  ##[error]Container operations are only supported on Linux runners
  ```
  이미지 아키텍처 문제가 아니다(pgmq 이미지는 arm64 매니페스트를 갖는다). **기능 자체가 없다.**
  스텝 안에서 `docker run` 으로 직접 띄우는 것은 정상 동작한다 — 같은 실행에서 infra-ci 의 nginx
  봉인 잡이 그렇게 돌고 통과했다(양성 대조군). `ci-runner-label-alignment.test.ts` 가 회귀를 차단한다.
- **assembly 잡 DB 는 55433 이다.** 로컬 dev postgres(`bts-postgres-dev`)가 5433 을 상시 점유하므로
  전용 컨테이너를 55433 에 띄우고 `BTS_DB_URL` 로 덮는다. **그 dev DB 를 재사용하면 안 된다** —
  볼륨이 영속이라 선재 행이 가짜 초록을 만든다.
- **컨테이너 정리는 우리 책임이다.** self-hosted 러너는 머신이 살아남으므로 `docker rm -f` 를
  `if: always()` 로 돌리지 않으면 다음 실행이 **포트 충돌로 죽는다**. GitHub 호스팅 러너에는
  없던 책임이다.
- **판별식 워크플로우는 `workflow-scripts-ci.yml` 이다.** backend-ci 가 아니다.
  `paths` 가 워크플로우 레벨이라 backend-ci 에 두면 워크플로우 파일 한 줄 수정이 12잡을 끌고 온다.
- **★러너는 켜져 있는데 엔진만 없을 수 있다 (2026-08-04 실측).** 홈 폴더 용량 정리로
  `~/actions-runner-bts` 아래 **큰 파일**이 지워지면 러너는 `online` 인데 모든 잡이
  `Checkout` 에서 죽는다. 실제로 `externals/node20`(86M) · `externals/node24`(115M) ·
  툴캐시 `node`(112M) · 툴캐시 JDK 의 `lib/modules`(~130M) 4개가 사라져 **8/4~8/7 사흘간
  CI 가 0회 실행**됐고, 그 사이 #342 · #343 · #344 가 검증 없이 머지됐다.
  기준은 「이름」이 아니라 **「홈 폴더 스코프 + 큰 파일」**이다 — 홈 밖의 `/usr/local/bin/node`
  는 **210M 인데도 살아남았고** 홈 안의 10M `ct.sym` 도 살아남았다.
  - **판별.** `gh run view <id> --log-failed | grep '##\[error\]'` 로 **어느 스텝에서
    죽었는지부터** 본다. `Checkout` 이면 코드가 아니라 러너다. §3 표의 마지막 행.
  - **점검.** `bash scripts/verify-runner-health.sh` (exit 0 이어야 정상).
    `/bts-start` **Step 0** 이 매 작업 시작 시 자동으로 돌리고, CI 는 모든 워크플로우가
    `runner-health.yml` 을 `needs:` 로 매달아 돌린다. 배선은
    `scripts/workflow/runner-healthcheck-wiring.test.ts` 가 강제한다.
  - **★파일 존재 확인으로는 못 잡는다.** `externals` 는 `corepack`·`npm`·`npx` 심볼릭이
    남고 `node` 만 없었고, 툴캐시는 `arm64.complete` 표식만 남아 `setup-*` 가 **캐시 히트로
    오판**해 시스템 node(v22.14.0)로 조용히 흘러내렸으며(그 결과 `.ts` 타입 스트리핑이
    없어 판별식 9파일이 `ERR_UNKNOWN_FILE_EXTENSION` 으로 죽었다), JDK 는 `bin` 의
    실행파일 30개가 전부 있는데 `lib/modules` 만 없어 `java -version` 만 실패했다.
    **세 경우 모두 `test -f` 는 통과한다.** 점검은 반드시 **실행(exit 0)** 을 본다.
  - **복구.** 툴캐시는 해당 버전 디렉터리의 `arm64.complete` **표식만 지우면** `setup-*` 가
    캐시 미스로 판정해 자가 재설치한다(바이너리를 손으로 갖다 놓지 말 것 — 버전이 어긋난다).
    - **★자가 재설치는 「고치는」 게 아니라 「새로 받는」 것이다.** 2026-08-07 실측 —
      깨진 `Java_…/21.0.11-10.0.LTS` 의 표식을 지우자 `setup-java` 가 그 디렉터리를 고치지 않고
      **최신 21.x(`21.0.12-8.0.LTS`)를 새 디렉터리에 받았다.** 옛 디렉터리는 **깨진 채 남는다.**
      헬스체크가 그것을 매번 ⚠️ 로 보고하므로(표식이 없어 차단은 안 한다) **재설치 확인 후
      옛 버전 디렉터리를 지운다** — 안 지우면 경고가 상시화되고, 상시 경고는 곧 무시된다.
      `rm -rf ~/actions-runner-bts/_work/_tool/Java_*/<옛 버전>`
    `externals` 는 러너 버전에 맞는 node 를 nodejs.org 에서 받아 `SHASUMS256.txt` 로
    sha256 대조 후 `bin/node` 만 복원하고 `chmod 755` + `xattr -d com.apple.quarantine`.
  - **⚠️ 예방은 습관뿐이다.** 홈 폴더를 용량 정리할 때 **`~/actions-runner-bts` 를 제외**한다.
    이 폴더는 다 합쳐 350MB 남짓이라 지워도 공간 이득이 거의 없는데 **CI 전체가 멈춘다.**
  - **⚠️ CI 층(`runner-health.yml`)의 전제는 미검증이다** — ①순수 shell `run:` 스텝이 node
    부재 시에도 실행되는가 ②`workflow_call` 해석이 node 를 안 쓰는가. 완전 실측은 러너를
    일부러 고장내야 해서 하지 않았다. 그래서 `scripts/verify-runner-health.sh` +
    `/bts-start` Step 0 이 **전제 무관 백스톱**으로 함께 있다. 실제 사고가 다시 나면 그때
    CI 층이 물었는지를 확인하고 이 문단을 갱신한다.

## 5. (참고) GitHub 호스팅 러너로 되돌리는 절차

**현재 계획에 없다** — §6 의 무료 한도 정책이 확정돼 있다. 조건이 바뀌었을 때만 쓴다.

되돌리려면 **같은 PR 에서 세 가지를 함께** 바꾼다. 하나라도 빠지면 봉인이 어긋난다.

1. `.github/workflows/*.yml` 의 `runs-on: [self-hosted, bts-local]` → `ubuntu-latest` (**전수**)
2. `scripts/workflow/ci-runner-label-alignment.test.ts` 의 `REQUIRED_RUNNER_LABEL` 단언 —
   되돌린다면 이 판별식도 함께 되돌린다. 그냥 두면 되돌리는 PR 이 차단된다 (의도된 래칫이다)
3. `assembly` 잡의 postgres 기동 방식 — Linux 러너로 돌아가면 `docker run` 스텝 3종을
   `services:` 블록으로 되돌릴 수 있다(그쪽이 헬스체크·정리를 GitHub 이 대신 해 준다).
   되돌린다면 §4 의 `services:` 금지 단언도 함께 되돌린다. 포트는 **55433 을 유지해도 정상
   동작한다** — `BTS_DB_URL` 과 짝만 맞으면 된다. 굳이 바꾸지 않는 편이 안전하다

러너 제거.

```bash
~/actions-runner-bts/svc.sh stop
~/actions-runner-bts/svc.sh uninstall
TOKEN=$(gh api -X POST /repos/maxihan1/BTS/actions/runners/remove-token --jq .token)
cd ~/actions-runner-bts && ./config.sh remove --token "$TOKEN"
```

## 6. 무료 한도 정책 (2026-07-30 Maxi 확정)

**유료 결제를 하지 않는다. GitHub 무료 한도 안에서만 운영한다.**
self-hosted 전환이 이 정책의 **해답**이다 — 분 과금이 아예 발생하지 않는다.

무료 한도 3종과 이 저장소의 소모 (2026-07-30 실측).

| 항목 | 소모 | 무료 한도 | 비고 |
|---|---|---|---|
| **실행 시간(분)** | **0** | 2,000분/월 | self-hosted 잡은 과금 대상이 아니다 |
| **아티팩트 스토리지** | 16 MB (73개) | 500 MB | `upload-artifact` 의 `retention-days: 7` 이 상한을 잡는다 |
| **캐시** | 4,157 MB (63개) | 10 GB/저장소 | **과금 대상 아님.** 초과 시 오래된 것부터 자동 축출 |

확인 명령.

```bash
gh api /repos/maxihan1/BTS/actions/cache/usage \
  --jq '"캐시 \(.active_caches_size_in_bytes/1048576|floor) MB / \(.active_caches_count) 개"'
gh api "/repos/maxihan1/BTS/actions/artifacts?per_page=100" \
  --jq '[.artifacts[] | select(.expired==false) | .size_in_bytes] | add // 0 | ./1048576 | floor'
```

**감시할 것은 분이 아니라 스토리지다.** 분은 이제 안 쓰지만 아티팩트는 계속 쌓인다.
500 MB 에 접근하면 `retention-days` 를 줄이거나 업로드 대상을 좁힌다
(현재는 테스트 결과 XML 뿐이라 여유가 크다).

**이 설계의 대가 — 맥이 꺼져 있으면 CI 가 안 돈다.** 게다가 실패가 아니라 `queued` 무한 대기다(§3).
무료 한도만 쓰기로 한 이상 이건 피할 수 없는 교환이며, 받아들인 것이다.

**현재 차단의 실체.** 무료 플랜 + 지출 한도 $0 + 이번 청구 주기 2,000분 소진으로 추정된다
(에러 문구 "recent account payments have failed **or** your spending limit needs to be increased"
는 두 경우를 한 문장으로 덮는 상용구다). 다음 주기에 자동으로 풀리지만, 모든 잡이 self-hosted 라
풀리든 말든 동작에 차이가 없다.
