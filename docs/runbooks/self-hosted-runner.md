<!-- GitHub Actions self-hosted 러너 운영 절차 — 신원·증상·복구·되돌리기 -->

# self-hosted 러너 운영 (`maxi-mac-bts`)

> 도입 2026-07-30 (PR #324). 도입 사유는 §1, 되돌리는 법은 §5.

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

복구.

```bash
~/actions-runner-bts/svc.sh status
~/actions-runner-bts/svc.sh start
```

## 4. 알아 둘 것

- **직렬 실행.** 러너 1대라 동시 1잡이다. 전 워크플로우 합계 17잡이 순차 실행되므로 PR 피드백이
  기존 병렬 대비 느리다 (2026-07-30 PR #324 에서 D2=A 로 확정. 실측 후 대수 재검토).
- **`~/.gradle` · Docker 를 로컬 개발과 공유한다.** CI 실행 중 로컬 `./gradlew` 동시 실행은 피한다.
  워크스페이스 자체는 `actions/checkout@v4` 기본값 `clean: true`(`git clean -ffdx`)가 매 잡마다
  `build/`·`node_modules/`·테스트 XML 을 지우므로 잔재성 거짓 초록은 발생하지 않는다.
- **assembly 잡 DB 는 55433 이다.** 로컬 dev postgres(`bts-postgres-dev`)가 5433 을 상시 점유하므로
  서비스 컨테이너를 55433 에 띄우고 `BTS_DB_URL` 로 덮는다. **그 dev DB 를 재사용하면 안 된다** —
  볼륨이 영속이라 선재 행이 가짜 초록을 만든다.
- **판별식 워크플로우는 `workflow-scripts-ci.yml` 이다.** backend-ci 가 아니다.
  `paths` 가 워크플로우 레벨이라 backend-ci 에 두면 워크플로우 파일 한 줄 수정이 12잡을 끌고 온다.

## 5. 결제 복구 후 되돌리기

되돌리려면 **같은 PR 에서 세 가지를 함께** 바꾼다. 하나라도 빠지면 봉인이 어긋난다.

1. `.github/workflows/*.yml` 의 `runs-on: [self-hosted, bts-local]` → `ubuntu-latest` (**전수**)
2. `scripts/workflow/ci-runner-label-alignment.test.ts` 의 `REQUIRED_RUNNER_LABEL` 단언 —
   되돌린다면 이 판별식도 함께 되돌린다. 그냥 두면 되돌리는 PR 이 차단된다 (의도된 래칫이다)
3. `assembly` 잡의 `BTS_DB_URL` env 와 서비스 컨테이너 포트 — GitHub 호스팅 러너에는 로컬
   dev postgres 가 없으므로 5433 으로 되돌려도 무방하나, **55433 을 유지해도 정상 동작한다**
   (짝만 맞으면 된다). 굳이 바꾸지 않는 편이 안전하다

러너 제거.

```bash
~/actions-runner-bts/svc.sh stop
~/actions-runner-bts/svc.sh uninstall
TOKEN=$(gh api -X POST /repos/maxihan1/BTS/actions/runners/remove-token --jq .token)
cd ~/actions-runner-bts && ./config.sh remove --token "$TOKEN"
```

## 6. 근본 원인은 아직 안 풀렸다

이 러너는 **결제 차단을 우회할 뿐 해결하지 않는다.** GitHub 호스팅 러너가 필요한 상황
(러너 머신 부재 · 병렬 필요 · 외부 기여자)에서는 여전히 막힌다.
https://github.com/settings/billing 의 **Payment information** 과
**Spending limits → Actions** 를 확인해야 한다. BTS 는 private 저장소라 분 과금 대상이다.
