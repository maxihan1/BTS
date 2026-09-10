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
          if git rev-parse --verify --quiet refs/remotes/origin/main > /dev/null; then
            echo "origin/main 있음 ✅ — 범위 계산 가능"
          else
            echo "⚠️ origin/main 없음 — 계산기가 전량으로 넓힌다(안전하지만 느리다)."
            echo "   처방. 잡 설정의 refspec 이 +refs/heads/*:refs/remotes/origin/* 인지 확인."
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
          env.RUN_FULL = (params.FULL || branch == 'main' || nightly) ? 'true' : 'false'
          echo "브랜치=${branch} · 야간=${nightly ? 'Y' : 'N'} · FULL=${params.FULL} → 전량=${env.RUN_FULL}"

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
          def diffBase = sh(
            returnStdout: true,
            script: 'git rev-parse origin/main 2>/dev/null || git rev-parse HEAD~1',
          ).trim()
          def changed = sh(
            returnStdout: true,
            script: "git diff --name-only --no-renames ${diffBase}...HEAD || true",
          ).trim()
          def domains = changed.readLines()
            .findAll { it.startsWith('apps/web/src/components/') }
            .collect { it.split('/')[4] }
            .unique()
            .sort()
          env.E2E_DOMAINS = domains.join(' ')
          echo "E2E 변경 도메인=${env.E2E_DOMAINS ?: '(없음 — 전량이 받는다)'}"
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
      when { environment name: 'RUN_FULL', value: 'true' }
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

    stage('전량') {
      when { environment name: 'RUN_FULL', value: 'true' }
      steps {
        sh '''
          set -eu
          # ★`BTS_DB_URL` 이 비면 여기서 죽인다. 비면 `application.yml` 기본값 localhost:5433 으로
          #   조용히 떨어지고, 그건 이 머신에 없다 — 실패가 컨텍스트 로드 오류 60건으로 나타나
          #   원인이 안 보인다(빌드 #17).
          [ -n "${BTS_DB_URL:-}" ] || { echo "BTS_DB_URL 이 비었다 — DB 마련 stage 를 확인하라"; exit 1; }
          node --experimental-strip-types --test 'scripts/**/*.test.ts' 'scripts/**/*.test.mjs'
          pnpm --filter @bts/web test
          cd backend
          ./gradlew test --console=plain
          # `--rerun-tasks` 로 캐시 거짓 초록을 막는다.
          ./gradlew ktlintCheck detekt --rerun-tasks --console=plain
        '''
      }
    }

    stage('조립 부팅') {
      when { environment name: 'RUN_FULL', value: 'true' }
      steps {
        sh '''
          set -eu
          # ★DB 기동은 「DB 마련」 stage 로 옮겼다(2026-09-09). 여기서 다시 띄우지 않는다.
          #   `:modules:app:test` 는 전량의 `./gradlew test` 에도 포함돼 있어서, 종전 구조는
          #   같은 테스트를 DB 없이 한 번(전량·실패) + DB 붙여 한 번(여기) 돌리고 있었다.
          [ -n "${BTS_DB_URL:-}" ] || { echo "BTS_DB_URL 이 비었다 — DB 마련 stage 를 확인하라"; exit 1; }
          cd backend
          # ★`:modules:app:test` 를 여기서 다시 돌리지 않는다. 전량이 같은 DB 를 보고 이미 돌렸다.
          # 별도 태스크 = 별도 JVM 이어야 한다. @ActiveProfiles 가 컨텍스트 캐시 키의 일부라
          # 같은 JVM 에 두면 9-BC 컨텍스트가 두 벌 뜨고 @Scheduled 워커가 같은 pgmq 큐를
          # 동시 폴링한다. 이 스텝을 지우면 태그로 분리된 가드가 0회 실행된다.
          ./gradlew :modules:app:nonProdAssemblyTest --console=plain
        '''
      }
    }

    stage('인프라 봉인') {
      when { environment name: 'RUN_FULL', value: 'true' }
      steps {
        sh '''
          set -eu
          bash scripts/verify/nginx-log-masking.sh
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
    stage('배포 승인') {
      when {
        allOf {
          expression { params.DEPLOY }
          environment name: 'RUN_FULL', value: 'true'
        }
      }
      steps {
        // 타임아웃을 둔다. 승인을 안 누르고 두면 executor 1개를 무한 점유해
        // 2코어 머신의 CI 가 통째로 멈춘다.
        timeout(time: 30, unit: 'MINUTES') {
          input message: '운영(bts.maxihan.com)에 배포한다. 계속할까?', ok: '배포'
        }
      }
    }

    stage('배포') {
      when {
        allOf {
          expression { params.DEPLOY }
          environment name: 'RUN_FULL', value: 'true'
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
        // ★`BTS_SKIP_DEPLOY_TEST` 를 넘기지 않는다. 그 값이 서면 이 배포는 전수 검증 0회다.
        sh '''
          set -eu
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
      sh 'docker rm -f "$CI_PG_CONTAINER" 2>/dev/null || true'
      junit allowEmptyResults: true, testResults: 'backend/modules/*/build/test-results/**/*.xml'
      // 잔재성 거짓 초록을 막는다 — `actions/checkout@v4` 의 `clean: true` 가 하던 일이다.
      cleanWs(deleteDirs: true, notFailBuild: true)
    }
  }
}
