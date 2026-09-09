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
    disableConcurrentBuilds()
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
  }

  triggers {
    // 야간 전량. 운영 트래픽이 가장 적은 시간대에 둔다.
    cron('H 3 * * *')
  }

  environment {
    GRADLE_OPTS = '-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs=-Xmx3g'
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
          # 계산기가 `origin/main` 을 비교 기준으로 쓴다(FALLBACK_BASE). 없으면 판정 불가로
          # 읽고 전량으로 넓히므로 안전하지만, 매번 전량이면 2단이 무의미해진다.
          git fetch --no-tags origin main:refs/remotes/origin/main || true
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
        // 티어와 무관하게 항상 돈다. FR 카운트 drift 와 문서 인덱스 부패는 범위 계산의
        // 대상이 아니고, 둘 다 초 단위다.
        sh '''
          set -eu
          bash scripts/verify-master-plan.sh
          node scripts/build-doc-index.mjs --check
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

    stage('전량') {
      when { environment name: 'RUN_FULL', value: 'true' }
      steps {
        sh '''
          set -eu
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
          # ★pgmq 확장 필수. 일반 postgres:16 은 마이그레이션에서 죽는다
          #   (ADR docs/adr/2026-05-22-pgmq-postgres-image.md).
          # ★포트를 고정하지 않는다. `-p 0:5432` 로 커널이 고르게 하고 실제 포트를 조회한다.
          #   고정 55433 은 러너 1대일 때만 안전한 설계였다.
          # ★dev postgres(5433)를 재사용하지 않는다. 볼륨이 영속이라 선재 행이 남아
          #   「마이그레이션이 안 넣음」과 「데이터 없음」이 구분되지 않는다 — 가짜 초록이다.
          docker rm -f "$CI_PG_CONTAINER" 2>/dev/null || true
          docker run -d --name "$CI_PG_CONTAINER" \\
            -e POSTGRES_DB=bts -e POSTGRES_USER=bts -e POSTGRES_PASSWORD=bts \\
            -p 0:5432 quay.io/tembo/pg16-pgmq:latest

          PG_PORT="$(docker port "$CI_PG_CONTAINER" 5432/tcp | head -1 | sed 's/.*://')"
          [ -n "$PG_PORT" ] || { echo "postgres 포트 조회 실패"; exit 1; }
          echo "postgres → localhost:$PG_PORT"

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

          # application.yml 이 이미 `${BTS_DB_URL:...}` 오버라이드 지점을 갖는다 — Kotlin 변경 0.
          BTS_DB_URL="jdbc:postgresql://localhost:${PG_PORT}/bts"; export BTS_DB_URL
          cd backend
          ./gradlew :modules:app:test --console=plain
          # 별도 태스크 = 별도 JVM 이어야 한다. @ActiveProfiles 가 컨텍스트 캐시 키의 일부라
          # 같은 JVM 에 두면 9-BC 컨텍스트가 두 벌 뜨고 @Scheduled 워커가 같은 pgmq 큐를
          # 동시 폴링한다. 이 스텝을 지우면 태그로 분리된 가드가 0회 실행된다.
          ./gradlew :modules:app:nonProdAssemblyTest --console=plain
        '''
      }
      post {
        // self-hosted 는 머신이 살아남는다. 지우지 않으면 컨테이너가 쌓인다 —
        // GitHub 호스팅 러너에는 없던 책임이다. 실패해도 돌아야 하므로 always.
        always { sh 'docker rm -f "$CI_PG_CONTAINER" 2>/dev/null || true' }
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
  }

  post {
    always {
      junit allowEmptyResults: true, testResults: 'backend/modules/*/build/test-results/**/*.xml'
      // 잔재성 거짓 초록을 막는다 — `actions/checkout@v4` 의 `clean: true` 가 하던 일이다.
      cleanWs(deleteDirs: true, notFailBuild: true)
    }
  }
}
