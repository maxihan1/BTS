// BTS CI 파이프라인 — 2단(빠른 게이트 · 전량). GitHub Actions 4종의 승계 대상
//
// ## 왜 2단인가
//
// 이 컨트롤러는 운영과 **같은 2코어 머신**에 있다. 전 모듈 스위트는 맥(8코어)에서 약 55분이라
// 여기서는 그보다 느리다. 모든 푸시에 전량을 돌리면 사람이 기다리지 않게 되고, 그 순간
// 파이프라인은 검증 장치가 아니라 소음이 된다 — `push-backend-tests.ts` 가 훅에서
// 「빠를 것」을 1번 규율로 적은 것과 같은 이유다.
//
//   빠른 게이트  모든 푸시. `select-test-scope.ts` 가 계산한 범위
//   전량        main · FULL=true · 야간 크론. 전 모듈 + 조립 부팅 + 인프라 봉인
//
// ## ★★범위를 이 파일에서 계산하지 않는다 — 명령도 다시 적지 않는다
//
// `scripts/workflow/select-test-scope.ts` 는 「무엇을 돌릴지」가 아니라 **실행할 명령 블록
// 자체**를 출력한다(백엔드 모듈 폐포 · `vitest related` · E2E · 판별식 전량). 그 블록을
// 그대로 실행하는 것이 이 파일의 일이다.
//
// 명령을 여기 다시 적으면 사람이 그쪽을 복사해 쓰고 계산기는 장식이 된다 —
// `bts-impl` Step 4 에 같은 문장이 있고, 2026-09-04 진단이 「좁힘 장치가 세 겹 있었는데
// 그 자리가 셋 중 아무것도 안 불렀다」를 실측한 자리다. 초안에서 백엔드·프론트 스테이지를
// 손으로 쪼갰다가 되돌렸다. 스테이지 가독성은 그 위험을 살 만한 값이 아니다.
//
// ## 판정은 종료 코드로 한다
//
// `sh` 스텝은 비-0 에서 실패한다. 파이프(`| tail`)로 출력을 줄이면 **셸이 보고하는 종료 코드가
// tail 의 것으로 바뀐다** — 「Tests N passed」와 실패가 같은 실행에서 동시에 참일 수 있다.
//
// 운영 절차 docs/runbooks/jenkins.md

pipeline {
  agent any

  options {
    // 2코어다. 동시 빌드는 Testcontainers 컨테이너까지 겹쳐 스왑으로 민다.
    //
    // ★`abortPrevious: true` 가 핵심이다. 이것이 없으면 새 빌드가 **큐에서 기다린다** —
    //   executor 가 1개라 낡은 빌드가 도는 동안 현재 커밋의 검증이 시작조차 못 한다.
    //   GitHub Actions 시절 `concurrency.cancel-in-progress: true` 가 막던 그 상태이고,
    //   2026-08-07 실측에서 큐 8건 중 3건이 **이미 머지되고 브랜치까지 삭제된 PR** 의
    //   검증이었다. 그때 현재 main 을 검증하는 run 은 0건이었다.
    //   `disableConcurrentBuilds()` 만 쓰면 「동시 실행은 막았는데 낡은 것이 먼저 돈다」가
    //   되어 절반만 고친 상태가 된다 — 포팅하면서 실제로 그렇게 써 놓았다가 잡혔다.
    disableConcurrentBuilds(abortPrevious: true)
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '30', artifactNumToKeepStr: '10'))
    timeout(time: 3, unit: 'HOURS')
  }

  parameters {
    booleanParam(
      name: 'FULL',
      defaultValue: false,
      description: '전 모듈 + 조립 부팅 + 인프라 봉인. main 과 야간 크론은 이 값과 무관하게 전량이다.'
    )
    // ★기본값 거짓이다. 이 값이 참일 때만 배포 단계가 파이프라인에 나타나고, 나타나도
    //   `input` 승인 앞에서 멈춘다 — 두 겹이라 실수로 운영에 나가지 않는다.
    booleanParam(
      name: 'DEPLOY',
      defaultValue: false,
      description: '운영(bts.maxihan.com) 배포까지 간다. 전량 검증을 통과한 뒤 사람이 승인해야 실행된다.'
    )
    /*
     * ★시각 회귀 기준 이미지를 **이 러너에서** 만든다 (P3).
     *
     * 기준 PNG 는 OS·아키텍처·폰트 렌더가 같은 기계에서만 유효하다. 맥에서 만든 것을
     * 리눅스 러너가 비교하면 전량 diff 다. 그래서 생성도 여기서 해야 한다.
     *
     * ★**커밋하지 않는다.** 생성한 PNG 를 빌드 아티팩트로 남기고, 사람이 내려받아
     *   커밋한다. 파이프라인이 스스로 기준을 갱신하면 그것이 곧 「회귀를 승인하는
     *   재생성」이고, `snapshot-baseline-guard.test.ts` 가 막으려는 바로 그 고장이다.
     *
     * ★기본값 거짓이다. 체크해야만 도는 일회성 작업이다.
     */
    booleanParam(
      name: 'UPDATE_VISUAL_BASELINE',
      defaultValue: false,
      description: '시각 회귀 기준 이미지를 이 러너에서 생성해 아티팩트로 남긴다 (커밋은 사람이 한다).'
    )
  }

  triggers {
    // 야간 전량. 운영 트래픽이 가장 적은 시간대에 둔다.
    cron('H 3 * * *')

    // ★푸시 자동 감지. webhook 이 아니라 폴링인 이유가 있다 —
    //   젠킨스는 `127.0.0.1` 에만 붙어 있어 GitHub 이 부를 수 없다(P1 의 의도된 설계다).
    //   폴링은 `git ls-remote` 한 번이라 2코어 머신에도 부담이 없고 노출이 0 이다.
    //   대가는 최대 5분 지연이고, 그것이 `.husky/pre-push` 에서 백엔드 테스트를 걷어낼 때
    //   「푸시 전에 안다」가 「푸시 후 최대 5분 뒤에 안다」로 바뀌는 몫이다.
    //
    // ★`H/5` 의 `H` 는 해시 분산이다. `*/5` 로 적으면 모든 잡이 정각에 몰린다 —
    //   지금은 잡이 하나라 차이가 없지만, 늘어나는 순간 2코어에서 그 몰림이 그대로 비용이다.
    pollSCM('H/5 * * * *')
  }

  environment {
    // ★`GRADLE_OPTS` 를 **설정하지 않는다.** 성능 설정의 정본은 `backend/gradle.properties`
    //   하나이고, `gradle-perf-contract.test.ts` 가 그 값을 계약으로 강제한다.
    //
    // ★초안은 `-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx3g` 를 넣었다가 되돌렸다.
    //   `GRADLE_OPTS` 는 `gradle.properties` 보다 **우선**하므로 정본을 통째로 덮는다.
    //     · `daemon=true`(2026-08-25 실측으로 켠 것) → false 로 뒤집혀 매 호출마다 JVM 재기동
    //     · `jvmargs` 의 `-Xmx4096m` · `MaxMetaspaceSize=1024m` · 힙덤프 옵션이 전부 소실
    //     · `kotlin.daemon.jvmargs`(3G)도 함께 날아가 Kotlin 컴파일 데몬이 기본값으로 돈다
    //   판별식은 **파일만** 보므로 이 상태에서도 초록이었다 —
    //   「선언과 실행이 어긋난다」가 런타임 오버라이드로 발현한 형태다.
    //
    //   컨테이너가 살아 있으므로 데몬은 빌드 사이에도 재사용된다. 2코어에서 그 이득이 크다.

    // 조립 부팅 postgres 를 **실행별로 유일하게** 만든다. 이름이 고정이면 두 빌드가 겹칠 때
    // 뒤 빌드의 `docker rm -f` 가 앞 빌드 DB 를 테스트 도중에 죽인다 — `backend-ci.yml` 의
    // assembly 잡이 「러너를 늘리면 이 조건이 성립한다」고 경고한 바로 그 상태가
    // 젠킨스에서는 기본값이다.
    CI_PG_CONTAINER = "bts-ci-pg-${env.BUILD_NUMBER}"

    // ★E2E 는 공식 Playwright 컨테이너에서 돈다(DooD). 젠킨스 컨테이너는 uid 1000 이라
    //   `playwright install --with-deps` 가 apt 권한 없이 **종료 코드 0 으로 조용히** 넘어가고
    //   크롬이 `libglib-2.0.so.0` 부재로 뜨지 않는다(2026-09-10 실측).
    //
    //   `select-test-scope.ts` 가 이 두 값을 보고 실행 명령을 고른다 — 없으면 로컬 바이너리,
    //   있으면 컨테이너다. 그래서 맥의 개발 워크플로우는 아무것도 안 바뀐다.
    //
    // ★태그는 `Jenkinsfile.e2e` 의 `PW_IMAGE` 와 같아야 한다. 그쪽이 정본이고
    //   `playwright-image-pin.test.ts` 가 lockfile 버전과 대조한다.
    /*
     * ★비교 기준. `HEAD~1` 폴백은 **직전 한 커밋만** 덮으므로, 빌드가 중단되거나 건너뛴
     *   사이 머지가 여러 번 쌓이면 그 구간을 놓친다. 젠킨스는 직전 **성공** 빌드의 커밋을
     *   알고 있으니 그것을 넘겨 구간 전체를 덮는다. 첫 빌드에는 비어 있고, 그때는
     *   `diff-base.ts` 가 스스로 물러난다.
     */
    BTS_DIFF_BASE = "${env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: ''}"
    PW_IMAGE = 'mcr.microsoft.com/playwright:v1.60.0-noble'
    // DooD 라 `-v` 좌변은 호스트 경로다. 컨테이너 안 경로를 주면 빈 디렉터리가 마운트되고
    // "no tests found" 가 초록으로 보인다.
    HOST_WS = '/var/lib/docker/volumes/bts-jenkins-home/_data/workspace/bts-ci'
  }

  stages {

    stage('프리플라이트') {
      steps {
        sh '''
          set -eu
          echo "--- Docker 데몬 ---"
          # ★CLI 존재와 데몬 가동은 다른 것이다. `command -v docker` 는 통과하는데 데몬만
          #   꺼진 상태가 가장 헷갈린다 — 2026-08-12 에 그 상태가 「nginx 문법 오류」로
          #   오진돼 사람이 잡 12개 로그를 뒤졌다. 오진은 빨간불보다 나쁘다.
          docker info > /dev/null

          echo "--- node 버전 drift ---"
          # ★이미지는 한번 구우면 굳는다. `.nvmrc` 가 바뀌어도 따라오지 않으므로 여기서 잡는다.
          #   두 쪽 버전에 차이가 나면 타입 스트리핑 기본 활성 여부가 갈려 판별식이 조용히 0줄 실행된다
          #   (`node-ts-invocation.test.ts` 가 기록한 결함 — CI 초록 / 로컬 빨강이 구조적으로 고정).
          want="$(tr -d '[:space:]' < .nvmrc)"
          have="$(node -v | sed 's/^v//')"
          if [ "$want" != "$have" ]; then
            echo "node drift — .nvmrc=$want · 이미지=$have"
            echo "처방. 서버 infra/jenkins 에서 ./bootstrap.sh up (이미지 재빌드)"
            exit 1
          fi
          echo "node $have == .nvmrc ✅"

          corepack enable

          echo "--- 비교 기준(origin/main) ---"
          # ★셸의 `git` 은 자격증명이 없다. deploy key 는 젠킨스 자격증명 저장소에 있고
          #   SCM 체크아웃만 그것을 쓴다 — 여기서 `git fetch` 를 부르면
          #   `Permission denied (publickey)` 다(빌드 #1 실측).
          #   그래서 **잡의 refspec 이 모든 브랜치를 받아 오게** 하고 여기서는 확인만 한다.
          #
          # ★`|| true` 로 삼키지 않는다. 기준이 없으면 계산기가 전량으로 넓히므로 안전하지만,
          #   그러면 2단이 조용히 1단이 된다 — 「느려졌다」로만 보이고 원인이 안 보인다.
          # ★종전에는 `origin/main` 이 **있는지**만 봤다. 그 가드는 main 브랜치 빌드에서
          #   항상 초록이면서 아무것도 보장하지 않았다 — origin/main 이 곧 HEAD 라
          #   차집합이 공집합이 되는데도 「범위 계산 가능」이라고 찍었다(빌드 #31 실측).
          #
          #   이제 **계산기가 실제로 고른 기준**을 찍는다. 판정은 계산기가 하고
          #   여기서는 그 결과를 보이게만 한다 — 두 곳이 각자 판정하면 또 갈린다.
          if node --experimental-strip-types -e '
            import("./scripts/workflow/diff-base.ts").then((m) => {
              const b = m.resolveDiffBase();
              if (b === null) { console.log("⚠️ 비교 기준 없음 — 계산기가 전량으로 넓힌다"); }
              else { console.log(`비교 기준 ${b.slice(0, 9)} ✅`); }
            })
          '; then :; else
            echo "⚠️ 기준 조회 실패 — 계산기가 전량으로 넓힌다(안전하지만 느리다)."
          fi
        '''
      }
    }

    stage('의존성') {
      steps {
        sh 'pnpm install --frozen-lockfile'
      }
    }

    stage('정합 게이트') {
      steps {
        // 티어와 무관하게 항상 돈다. FR 카운트 drift 는 범위 계산의 대상이 아니고 초 단위다.
        //
        // ★`build-doc-index.mjs --check` 를 여기 두지 않는다. 그 생성기는 저장소 **밖**
        //   `~/.claude/projects/<프로젝트>/memory/MEMORY.md` 를 읽어서 CI 머신에서는
        //   `ENOENT` 로 죽는다(빌드 #1 실측). 그래서 GitHub Actions 4종 어디에도 없었고
        //   `.husky/pre-commit` 에만 걸려 있다 — 「저장소 밖 메모리의 유일한 봉인」이라고
        //   `behavior-rules.md §4` 가 적은 그 위치가 정답이다.
        //   초안에서 그것을 모르고 옮겨 왔다가 되돌렸다. 다시 넣지 말 것.
        sh '''
          set -eu
          bash scripts/verify-master-plan.sh
        '''
      }
    }

    stage('프론트 정적') {
      steps {
        // 타입체크와 문법검사는 범위를 나눌 이유가 없다(빠르다) — `bts-impl` Step 4-B 와 같은 판단.
        sh '''
          set -eu
          pnpm --filter @bts/web typecheck
          pnpm --filter @bts/web lint
        '''
      }
    }

    stage('전량 판정') {
      steps {
        script {
          def nightly = currentBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause')
          // ★브랜치 판정은 세 곳을 순서대로 본다. 하나만 믿으면 main 이 영원히 빠른 게이트만
          //   도는 상태가 **조용히** 고정된다.
          //     · `BRANCH_NAME`  멀티브랜치에서만 채워진다. 단일 파이프라인에서는 null
          //     · `GIT_BRANCH`   git 플러그인이 채운다. `origin/main` 형태라 접두사를 떼야 한다
          //     · `git rev-parse` 마지막 폴백. 젠킨스는 **detached HEAD** 로 체크아웃하므로
          //       이것만 쓰면 `HEAD` 가 나와 어떤 브랜치와도 안 맞는다 — 초안의 실제 결함이다
          def branch = (env.BRANCH_NAME ?: env.GIT_BRANCH
            ?: sh(returnStdout: true, script: 'git rev-parse --abbrev-ref HEAD').trim())
          branch = branch.replaceFirst(/^origin\//, '').trim()
          // 뒤 stage(배포 조건·승인 문구)가 쓴다. 여기서 한 번만 계산해 둔다 —
          // 각자 다시 구하면 세 곳이 서로 다른 답을 낼 수 있다.
          env.GIT_BRANCH_NAME = branch
          /*
           * ★CI 설정이 바뀌면 전량이다 (2026-09-10).
           *
           * 종전에는 브랜치·파라미터·야간만 봤다. 그래서 `Jenkinsfile` 을 고친 푸시가
           * 「빠른 게이트」로 갔는데, 그 안에서 `select-test-scope.ts` 가 스스로 전량으로
           * 넓혀 **32.7분**이 걸렸다(빌드 #21 실측). 이름과 실상이 갈렸다.
           *
           * 더 나쁜 것은 **전량 stage 에만 있는 검사를 건너뛴다**는 점이었다 —
           * 「조립 부팅」(`:modules:app` 조립)과 「인프라 봉인」(nginx 로그 마스킹 ·
           * springdoc 비노출). 「영향 범위를 모르니 전부 본다」면서 정작 그 둘을 안 보는
           * **반쪽 확대**였다.
           *
           * ★판정 목록을 여기 적지 않는다. `WIDEN_PREFIXES`(select-backend-modules.ts)가
           *   정본이고 `requires-full-build.ts` 가 그것을 읽는다 — Groovy 로 다시 적으면
           *   두 목록이 되고, 새 CI 파일이 생길 때 한쪽만 고쳐진다.
           */
          def ciChanged = sh(
            returnStdout: true,
            script: 'node --experimental-strip-types scripts/workflow/requires-full-build.ts',
          ).trim() == 'true'

          /*
           * ★두 판단을 나눈다 (2026-09-10).
           *
           *   RUN_FULL   테스트를 **전량**으로 돌 것인가
           *   RUN_DEEP   조립 부팅·인프라 봉인·배포 **stage 를 켤 것인가**
           *
           * 종전에는 `RUN_FULL` 하나가 둘을 겸했고, 그래서 `main` 머지가 무조건 37분이었다.
           *
           * ★main 을 전량에서 뺀 근거. 전량의 원래 목적은 「PR 여러 개가 각자 좁게 통과한 뒤
           *   main 에서 만나는 조합」을 보는 것이다. 그런데 이 저장소는 개발자 1명이라 PR 이
           *   순차로 들어오고, 그때 「합쳐진 상태」는 곧 「그 PR 상태」다 — 조합이 생기지 않는다.
           *   그 방어의 **전제가 성립하지 않는다.**
           *
           * ★조합 위험이 0 은 아니다. 마이그레이션 번호 대역처럼 모듈 그래프로 계산할 수 없는
           *   축이 남는다(`select-backend-modules.ts` 참조). 그 몫은 **야간 전량 크론**이 받는다
           *   (`cron('H 3 * * *')`). 매일 한 번은 조합된 main 을 전량으로 본다.
           *
           * ★계산기는 스스로 넓힐 줄 안다. 마이그레이션·설정·CI 변경이면 전량으로 간다 —
           *   `select-test-scope.ts` 가 그 판단을 갖고 있고 `ciChanged` 가 그 일부다.
           */
          env.RUN_FULL = (params.FULL || nightly || ciChanged) ? 'true' : 'false'
          // 배포 경로(조립 부팅·인프라 봉인·배포)는 main 과 전량 빌드에서 열린다.
          env.RUN_DEEP = (env.RUN_FULL == 'true' || branch == 'main') ? 'true' : 'false'
          echo "브랜치=${branch} · 야간=${nightly ? 'Y' : 'N'} · FULL=${params.FULL}" +
            " · CI설정변경=${ciChanged ? 'Y' : 'N'} → 전량=${env.RUN_FULL} · 심층=${env.RUN_DEEP}"
          if (ciChanged && !params.FULL && branch != 'main' && !nightly) {
            // 빌드 목록에서 왜 오래 걸리는지 바로 보이게 한다 — 32분을 기다린 뒤
            // 로그를 열어야 아는 상태를 만들지 않는다.
            currentBuild.displayName = "#${env.BUILD_NUMBER} · CI설정변경 → 전량"
          }

          /*
           * ★배포 후 E2E 가 쓸 「변경 도메인」을 여기서 계산한다.
           *
           * 매핑표를 만들지 않는다 — 표를 만들면 그것이 두 번째 목록이 되고, 새 화면이
           * 생길 때 표를 안 고치면 그 E2E 가 조용히 0회 실행된다. 대신 저장소가 이미 가진
           * 규칙을 쓴다. `apps/web/src/components/<도메인>/` 과 `apps/web/e2e/<도메인>-*.spec.ts`
           * 의 **이름이 같다** — 프론트 폴더명이 곧 E2E 파일 접두다.
           *
           * ★여기에 도메인 이름을 예시로도 적지 않는다. `select-backend-modules.test.ts` 가
           *   Jenkinsfile 의 BC 이름 하드코딩을 차단하는데, 주석의 예시도 그 대조에 걸린다 —
           *   그리고 그게 옳다. 예시로 적은 이름은 시간이 지나면 목록처럼 읽힌다.
           *
           * ★비어도 검증이 사라지지 않는다. `bts-e2e` 는 도메인이 비면 1단을 건너뛰고
           *   **전량**을 돈다 — 「못 찾았으니 생략」이 아니라 「전량이 어차피 전부 본다」다.
           *   안전한 쪽으로 실패한다.
           */
          /*
           * ★기준 계산을 여기서 하지 않는다(2026-09-10).
           *
           *   종전에는 `git rev-parse origin/main` 으로 기준을 잡았다. main 위에서 그것은
           *   **HEAD 자신**이라 차집합이 항상 공집합이었다 — 도메인이 늘 비었다.
           *   같은 결함이 TS 쪽에 5벌 더 있었고 여섯이 서로를 검사하지 않았다.
           *
           *   이제 `changed-files.ts` 한 창구만 쓴다. 종료 코드 3 은 「기준을 모른다」이고,
           *   그때는 도메인을 비워 `bts-e2e` 가 전량을 돌게 한다 — 안전한 쪽으로 실패한다.
           *   계약. scripts/workflow/diff-base.test.ts
           */
          def probe = sh(
            returnStatus: true,
            script: 'node --experimental-strip-types scripts/workflow/changed-files.ts > .ci-changed.txt',
          )
          def lines = probe == 0 ? readFile('.ci-changed.txt').trim().readLines() : []
          // 소스 쪽 — `apps/web/src/components/<도메인>/`
          def fromSrc = lines
            .findAll { it.startsWith('apps/web/src/components/') }
            .collect { it.split('/')[4] }
          // ★E2E 쪽 — `apps/web/e2e/<도메인>-*.spec.ts`
          //
          //   초안은 소스만 봤다. 그러면 **E2E 파일만 고쳤을 때 도메인이 비어** 그 시나리오가
          //   CI 에서 한 번도 안 돈다 — 「썼는데 실행 안 된 테스트」가 된다. 있다고 믿게 만드니
          //   없는 것보다 나쁘다. 기능과 E2E 는 함께 고치는 것이 규칙이므로 둘 다 입력이다.
          def fromE2e = lines
            .findAll { it.startsWith('apps/web/e2e/') && it.endsWith('.spec.ts') }
            .collect { it.substring('apps/web/e2e/'.length()).replaceFirst(/-.*$/, '') }
          def domains = (fromSrc + fromE2e).unique().sort()
          env.E2E_DOMAINS = domains.join(' ')
          echo "E2E 변경 도메인=${env.E2E_DOMAINS ?: '(없음 — 전량이 받는다)'}" +
            " [소스 ${fromSrc.unique().size()} · E2E ${fromE2e.unique().size()}]"
        }
      }
    }

    stage('빠른 게이트') {
      when { environment name: 'RUN_FULL', value: 'false' }
      steps {
        // 계산기가 내는 블록을 **그대로** 실행한다. 블록에는 판별식 전량이 조건 없이 들어 있다.
        // 넓힘의 방향은 한쪽뿐이다 — 비교 기준을 못 읽거나 설정·의존성·마이그레이션이 바뀌면
        // 계산기가 스스로 전량으로 넓힌다. 좁게 고르는 실수만이 치명적이다.
        sh '''
          set -eu
          node --experimental-strip-types scripts/workflow/select-test-scope.ts > .ci-scope.sh
          echo "───── 실행할 범위 ─────"
          cat .ci-scope.sh
          echo "──────────────────────"
          sh -e .ci-scope.sh
        '''
      }
    }

    /*
     * ★DB 마련이 전량보다 **앞**이다 — 2026-09-09 빌드 #17 이 그 순서를 강제했다.
     *
     * `./gradlew test`(전량)는 `:modules:app:test` 를 포함하고, 그 테스트의 베이스
     * `ProdAssemblyHttpTestBase` 는 **Testcontainers 를 관리하지 않는다**(그 파일 주석 34~35행).
     * `spring.datasource.url` 기본값 `jdbc:postgresql://localhost:5433/bts` 에 그냥 붙는다.
     *
     * 맥에서는 `docker-compose.dev.yml` 의 postgres 가 늘 떠 있어 초록이었다.
     * 깨끗한 리눅스로 옮기자 60건이 한꺼번에 죽었다 —
     *   IllegalStateException at DefaultCacheAwareContextLoaderDelegate
     *     → BeanCreationException → FlywaySqlException → PSQLException → ConnectException
     * **테스트가 환경에 몰래 의존하고 있었고 아무도 그 의존을 몰랐다.**
     * 맥이라는 「항상 뭔가 떠 있는 환경」을 벗어나야 보이는 종류다.
     *
     * 그래서 DB 를 전량보다 먼저 띄우고 `BTS_DB_URL` 을 파이프라인 env 에 올린다.
     * `application.yml` 이 이미 `${BTS_DB_URL:...}` 오버라이드 지점을 가져 Kotlin 변경은 0이다.
     */
    stage('DB 마련') {
      when { environment name: 'RUN_DEEP', value: 'true' }
      steps {
        sh '''
          set -eu
          # ★pgmq 확장 필수. 일반 postgres:16 은 마이그레이션에서 죽는다
          #   (ADR docs/adr/2026-05-22-pgmq-postgres-image.md).
          # ★포트를 고정하지 않는다. `-p 0:5432` 로 커널이 고르게 하고 실제 포트를 조회한다.
          #   고정 55433 은 러너 1대일 때만 안전한 설계였다.
          # ★dev postgres(5433)를 재사용하지 않는다. 볼륨이 영속이라 선재 행이 남아
          #   「마이그레이션이 안 넣음」과 「데이터 없음」이 구분되지 않는다 — 가짜 초록이다.
          docker rm -f "$CI_PG_CONTAINER" 2>/dev/null || true
          docker run -d --name "$CI_PG_CONTAINER" \
            -e POSTGRES_DB=bts -e POSTGRES_USER=bts -e POSTGRES_PASSWORD=bts \
            -p 0:5432 quay.io/tembo/pg16-pgmq:latest

          i=1
          while [ "$i" -le 30 ]; do
            if docker exec "$CI_PG_CONTAINER" pg_isready -U bts -d bts >/dev/null 2>&1; then
              echo "postgres ready (${i}회차)"; break
            fi
            if [ "$i" -eq 30 ]; then
              echo "postgres 가 60초 안에 안 떴다"; docker logs "$CI_PG_CONTAINER"; exit 1
            fi
            i=$((i + 1)); sleep 2
          done
        '''
        script {
          // 포트는 실행마다 다르다. 셸 변수는 stage 를 넘지 못하므로 파이프라인 env 에 올린다.
          def pgPort = sh(
            script: 'docker port "$CI_PG_CONTAINER" 5432/tcp | head -1 | sed "s/.*://"',
            returnStdout: true,
          ).trim()
          if (!pgPort) { error('postgres 포트 조회 실패 — 뒤 stage 가 조용히 5433 에 붙는다') }
          env.BTS_DB_URL = "jdbc:postgresql://localhost:${pgPort}/bts"
          echo "postgres → localhost:${pgPort} · BTS_DB_URL 설정됨"
        }
      }
    }

    /*
     * ★★명령을 여기 다시 적지 않는다 (2026-09-11 수정).
     *
     * 종전에는 이 stage 가 실행할 명령 목록을 **손으로** 갖고 있었다. 그래서
     * 「계산기가 내는 블록」과 「전량 stage 의 블록」이라는 두 목록이 생겼고,
     * **실제로 갈려 있었다** — 계산기 블록에는 E2E 줄이 있는데 여기에는 없었다.
     *
     * 결과. `RUN_FULL` 로 넓히는 순간 `빠른 게이트`(E2E 를 내는 유일한 자리)가 skip 되고,
     * 이 stage 에는 E2E 가 없어 **E2E 가 0회**가 됐다. 넓힐수록 검증이 줄었다.
     * 조합 위험을 받는다고 선언한 **야간 크론**도 그 경로다.
     *
     * 같은 파일 87~89행이 「영향 범위를 모르니 전부 본다면서 정작 그 둘을 안 보는
     * 반쪽 확대」라고 적어 조립 부팅·인프라 봉인은 고쳤는데, E2E 가 같은 자리에 남아 있었다.
     *
     * ★`BTS_FORCE_FULL=1` 이 계산기에게 「백엔드·프론트는 전량」을 시킨다.
     *   E2E 는 넓히지 않는다 — 전량 E2E(약 3시간)는 정책상 **배포 이후**의 몫이고,
     *   여기서는 2층의 약속대로 **변경 도메인**만 돈다.
     *   계약. scripts/workflow/full-stage-uses-calculator.test.ts
     */
    stage('전량') {
      when { environment name: 'RUN_FULL', value: 'true' }
      steps {
        sh '''
          set -eu
          # ★`BTS_DB_URL` 이 비면 여기서 죽인다. 비면 `application.yml` 기본값 localhost:5433 으로
          #   조용히 떨어지고, 그건 이 머신에 없다 — 실패가 컨텍스트 로드 오류 60건으로 나타나
          #   원인이 안 보인다(빌드 #17).
          [ -n "${BTS_DB_URL:-}" ] || { echo "BTS_DB_URL 이 비었다 — DB 마련 stage 를 확인하라"; exit 1; }
          BTS_FORCE_FULL=1 node --experimental-strip-types scripts/workflow/select-test-scope.ts > .ci-scope.sh
          echo "───── 실행할 범위 (전량) ─────"
          cat .ci-scope.sh
          echo "──────────────────────"
          sh -e .ci-scope.sh
        '''
      }
    }

    /*
     * ★★P3 시각 회귀 — 기준 이미지 생성 (일회성)
     *
     * ## 왜 별도 stage 인가
     *
     * 기준 PNG 는 **같은 기계·같은 컨테이너**에서 만들어야 유효하다. 맥에서 만든 것을
     * 리눅스 러너가 비교하면 폰트 렌더 차이로 전량 diff 다. 그래서 CI 가 쓰는 바로 그
     * Playwright 이미지 안에서 만든다.
     *
     * ## ★커밋하지 않는다
     *
     * 생성한 PNG 를 **아티팩트로만** 남긴다. 파이프라인이 스스로 기준을 갱신하면
     * 그것이 「회귀를 승인하는 재생성」이고, `snapshot-baseline-guard.test.ts` 가 막으려는
     * 고장 그 자체다. 사람이 내려받아 확인하고 커밋한다.
     *
     * ## ★MSW 가 필요하다
     *
     * 시각 스펙은 `loginAsAlice` 와 목 픽스처를 쓴다 — dev 서버에서만 동작한다.
     * `PLAYWRIGHT_BASE_URL` 을 주지 않으면 playwright 가 스스로 vite 를 띄운다.
     * 그래서 실서버가 아니라 **컨테이너 안 로컬**을 본다.
     */
    stage('시각 기준 이미지 생성') {
      when { expression { params.UPDATE_VISUAL_BASELINE } }
      steps {
        sh '''
          set -eu
          docker run --rm --network host \
            -v "$HOST_WS:/w" -w /w/apps/web \
            -e CI=1 \
            "$PW_IMAGE" \
            npx playwright test --project=visual --update-snapshots
        '''
      }
      post {
        always {
          // ★사람이 내려받아 확인하고 커밋하는 자리. 파이프라인은 여기까지만 한다.
          archiveArtifacts artifacts: 'apps/web/e2e/visual/__screenshots__/**',
                           allowEmptyArchive: true
        }
      }
    }

    stage('조립 부팅') {
      when { environment name: 'RUN_DEEP', value: 'true' }
      steps {
        sh '''
          set -eu
          # ★DB 기동은 「DB 마련」 stage 로 옮겼다(2026-09-09). 여기서 다시 띄우지 않는다.
          #   `:modules:app:test` 는 전량의 `./gradlew test` 에도 포함돼 있어서, 종전 구조는
          #   같은 테스트를 DB 없이 한 번(전량·실패) + DB 붙여 한 번(여기) 돌리고 있었다.
          [ -n "${BTS_DB_URL:-}" ] || { echo "BTS_DB_URL 이 비었다 — DB 마련 stage 를 확인하라"; exit 1; }
          cd backend
          # ★`:modules:app:test` 를 여기서 돈다 (2026-09-10 되살림).
          #
          #   종전에는 「전량이 같은 DB 를 보고 이미 돌렸다」고 여기서 뺐다. 그 전제가
          #   main 머지를 계산기 범위로 바꾸면서 깨졌다 — main 에서는 전량이 안 돈다.
          #
          #   그리고 `app` 은 `select-backend-modules.ts` 의 `NOT_IN_MATRIX` 라 계산기가
          #   **아예 안 고른다**(「app 변경은 조립 부팅 잡이 맡는다」는 그 파일의 설계).
          #   그래서 여기서 안 돌리면 **아무도 안 돈다.**
          #
          #   전량 빌드에서는 중복이 되지만, 그 비용(2코어에서 수십 초)보다
          #   「main 에서 조립 테스트가 0회」가 훨씬 나쁘다.
          # 별도 태스크 = 별도 JVM 이어야 한다. @ActiveProfiles 가 컨텍스트 캐시 키의 일부라
          # 같은 JVM 에 두면 9-BC 컨텍스트가 두 벌 뜨고 @Scheduled 워커가 같은 pgmq 큐를
          # 동시 폴링한다. 이 스텝을 지우면 태그로 분리된 가드가 0회 실행된다.
          ./gradlew :modules:app:test --console=plain
          ./gradlew :modules:app:nonProdAssemblyTest --console=plain
        '''
      }
    }

    stage('인프라 봉인') {
      when { environment name: 'RUN_DEEP', value: 'true' }
      steps {
        sh '''
          set -eu
          # ★DooD 에서 `docker run -v` 의 좌변은 **호스트 데몬이 보는 경로**다.
          #   젠킨스 컨테이너 안의 워크스페이스 경로를 그대로 주면 호스트에 없어서
          #   **빈 디렉터리가 마운트**되고, nginx 가 설정 없이 떠 문법 검증이 실패한다 —
          #   「이 설정으로 배포하면 프론트 전체가 뜨지 않는다」는 오진이 나온다.
          #   설정은 멀쩡했다(2026-09-10 빌드 #26 실측).
          BTS_HOST_WORKSPACE="$HOST_WS" bash scripts/verify/nginx-log-masking.sh
          bash scripts/verify/springdoc-not-exposed.sh
        '''
      }
    }

    // ── CD ────────────────────────────────────────────────────────────────
    //
    // ★배포는 **사람이 눌러야** 시작된다. 아래 `input` 이 그 자리다.
    //   게이트 2 가 사람 손에 남는 구조를 파이프라인이 깨지 않게 하는 장치이고,
    //   그래프 뷰에서는 이 지점이 클릭 가능한 승인 버튼으로 그려진다.
    //
    // ★`DEPLOY` 파라미터가 참일 때만 이 단계가 보인다. 기본값이 거짓이라
    //   평소 빌드는 여기까지 오지 않는다 — 실수로 배포창이 뜨는 일이 없다.
    /*
     * ★배포는 **main 에서만** 한다 (2026-09-10 추가).
     *
     * 종전 조건은 `DEPLOY && RUN_FULL` 뿐이라 **작업 브랜치를 운영에 배포할 수 있었다.**
     * 이 잡은 검증 편의로 작업 브랜치를 보도록 걸려 있었고, 그 임시 설정이 배포 경로까지
     * 열어 버렸다 — 검증용 설정이 운영 경로를 건드린 자리다.
     *
     * 「어차피 사람이 승인한다」로 넘길 수 없다. 종전 승인 화면은
     * 「운영에 배포한다. 계속할까?」만 보여줬다 — **어느 브랜치인지 안 보인다.**
     * 사람이 막을 수 없는 것을 사람에게 맡긴 셈이다.
     *
     * ★CI 는 머지하지 않는다. 머지는 GitHub 에서 사람이 한다(게이트 2). 무료 플랜이라
     *   브랜치 보호가 403 으로 거부되므로 젠킨스는 머지를 막지도 하지도 못한다
     *   (`docs/rules/behavior-rules.md` §4). 그래서 배포 대상은 **이미 머지된 main** 이다.
     */
    stage('배포 승인') {
      when {
        allOf {
          expression { params.DEPLOY }
          environment name: 'RUN_DEEP', value: 'true'
          expression { env.GIT_BRANCH_NAME == 'main' }
        }
      }
      steps {
        script {
          def sha = (env.GIT_COMMIT ?: 'unknown').take(9)
          def subject = sh(returnStdout: true, script: 'git log -1 --pretty=%s || true').trim()
          // ★무엇을 배포하는지 보여준다. 「계속할까?」만 묻는 승인은 판단할 정보가 없어
          //   반사적으로 눌리게 되고, 그때 승인 게이트는 이름만 남는다.
          //
          // 타임아웃을 둔다. 승인을 안 누르고 두면 executor 1개를 무한 점유해
          // 2코어 머신의 CI 가 통째로 멈춘다.
          timeout(time: 30, unit: 'MINUTES') {
            input(
              message: """운영(bts.maxihan.com)에 배포한다.

  브랜치  ${env.GIT_BRANCH_NAME}
  커밋    ${sha}  ${subject}

계속할까?""",
              ok: '배포',
            )
          }
        }
      }
    }

    stage('배포') {
      when {
        allOf {
          expression { params.DEPLOY }
          environment name: 'RUN_DEEP', value: 'true'
          // ★승인 stage 와 **같은 조건**이어야 한다. 한쪽만 브랜치를 보면 승인은 건너뛰고
          //   배포만 도는 경로가 생긴다 — 승인 없는 배포다.
          expression { env.GIT_BRANCH_NAME == 'main' }
        }
      }
      steps {
        // ★`bts-deploy.sh` 를 **호출한다. 다시 쓰지 않는다.**
        //   그 안에 배포 전 전량 게이트 · `require_web_module` · DB 덤프(실패 시 중단) ·
        //   pnpm 폴백 같은 사고 방어가 들어 있고, Jenkinsfile 에 옮겨 적으면 두 벌이 되어
        //   한쪽만 고쳐지는 자리가 된다.
        //
        // ★`BTS_DEPLOY_LOCAL=1` — 젠킨스는 **배포 대상 위에** 있다. 그 모드는 전송 수단만
        //   바꾸고(rsync 원격→로컬 · ssh→bash) 본문은 스크립트 한 벌 그대로 쓴다.
        //
        sh '''
          set -eu
          # ★★`BTS_SKIP_DEPLOY_TEST` 를 **코드로** 막는다 (2026-09-11).
          #
          #   종전에는 「넘기지 않는다」는 **주석만** 있었다. 젠킨스 전역 환경변수나
          #   노드 설정으로 그 값이 서면 `bts-deploy.sh:92` 가 전량 테스트를 건너뛰고
          #   배포한다 — 그 배포는 **전수 검증 0회**인데 파이프라인은 초록이다.
          #   주석은 그것을 막지 못한다. 실제로 같은 저장소가 「주석으로는 못 막는다」를
          #   잡 등록 함정에서 이미 실증했다(bootstrap.sh).
          if [ -n "${BTS_SKIP_DEPLOY_TEST:-}" ]; then
            echo "❌ BTS_SKIP_DEPLOY_TEST 가 설정돼 있다 — 젠킨스 배포 경로에서는 금지다." >&2
            echo "   그 값이 서면 이 배포는 전수 검증 0회다." >&2
            exit 1
          fi
          BTS_DEPLOY_LOCAL=1 bash infra/deploy/bts-deploy.sh
        '''
        /*
         * ★배포가 끝나면 `bts-e2e` 를 띄운다 — **기다리지 않는다**(`wait: false`).
         *
         * E2E 전량이 약 3시간이라 기다리면 이 잡의 executor 1개를 그동안 점유한다.
         * 2코어에 executor 가 1개뿐이라 그 사이 **모든 푸시 검증이 멈춘다.**
         *
         * 그리고 별도 잡이어야 하는 이유가 여기서 다시 성립한다 — 이 파이프라인은
         * `abortPrevious: true` 라, E2E 를 stage 로 넣으면 다음 푸시 하나가 도는 E2E 를
         * 죽이고 그 푸시는 `params.DEPLOY` 가 false 라 E2E 를 다시 돌리지도 않는다.
         * **배포된 것이 미검증으로 남는다.**
         *
         * `bts-e2e` 자신도 `abortPrevious` 라, 새 배포가 나면 낡은 E2E 는 취소되고
         * 새 배포의 E2E 가 이어 돈다 — 중단되고 끝나는 자리가 생기지 않는다.
         */
        build(
          job: 'bts-e2e',
          wait: false,
          parameters: [
            string(name: 'DEPLOYED_SHA', value: env.GIT_COMMIT ?: ''),
            string(name: 'CHANGED_DOMAINS', value: env.E2E_DOMAINS ?: ''),
            string(name: 'BASE_URL', value: 'https://bts.maxihan.com'),
          ],
        )
      }
    }
  }

  post {
    always {
      // ★postgres 정리를 파이프라인 post 로 올렸다(2026-09-09).
      //
      //   종전에는 「조립 부팅」 stage 의 post 에 있었다. 그런데 DB 를 띄우는 자리가
      //   「DB 마련」으로 옮겨가면서 **전량이 실패하면 조립 부팅이 skip 되고**,
      //   stage post 는 skip 된 stage 에서 돌지 않는다 — 컨테이너가 그대로 남는다.
      //   self-hosted 는 머신이 살아남으므로 그렇게 쌓인다.
      //   정리는 **DB 를 쓰는 모든 stage 를 덮는 자리**에 있어야 한다.
      // ★`-v` 로 익명 볼륨까지 지운다. 없으면 컨테이너만 사라지고 볼륨이 남아
      //   RUN_DEEP 빌드마다 디스크가 샌다 — self-hosted 는 머신이 살아남는다.
      sh 'docker rm -f -v "$CI_PG_CONTAINER" 2>/dev/null || true'
      junit allowEmptyResults: true, testResults: 'backend/modules/*/build/test-results/**/*.xml'
      /*
       * ★★E2E 결과도 남긴다 (2026-09-11 추가).
       *
       * 종전에는 백엔드 XML 하나만 수집하고 `cleanWs` 로 워크스페이스를 지웠다.
       * 그래서 빠른 게이트·전량이 돌린 Playwright 의 결과가 **아무 데도 안 남았다** —
       * 정책 §1 의 마지막 줄(「E2E 결과를 젠킨스에서 볼 수 있어야 한다」)이 `bts-e2e`
       * 에서만 지켜지고 `bts-ci` 에서는 지켜지지 않았다.
       *
       * 실패 원인을 로그에서 찾으려면 수만 줄을 뒤져야 하고, 트레이스·스크린샷은
       * `cleanWs` 가 지운 뒤였다. 「증거를 남기는 설정」(playwright.config.ts 의
       * `retain-on-failure`)이 있는데 **수집하는 쪽이 없어** 그 설정이 공허했다.
       *
       * ★`cleanWs` 보다 **앞**이어야 한다. 뒤면 지워진 것을 수집한다.
       */
      junit allowEmptyResults: true, testResults: 'apps/web/test-results/junit.xml'
      archiveArtifacts artifacts: 'apps/web/playwright-report/**', allowEmptyArchive: true
      archiveArtifacts artifacts: 'apps/web/test-results/**', allowEmptyArchive: true
      // 잔재성 거짓 초록을 막는다 — `actions/checkout@v4` 의 `clean: true` 가 하던 일이다.
      cleanWs(deleteDirs: true, notFailBuild: true)
    }
  }
}
