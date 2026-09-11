// 배포 경로가 성립하는지 · 운영 상태를 지우지 않는지 대조
//
// ## 무엇을 막는가 — 2026-09-11 첫 배포 사전 점검
//
// 배포가 **0회**인 상태에서 그 경로를 훑었더니 차단 요인이 아홉이었다. 코드는 전부
// 작성돼 있었고 판별식도 여럿 있었지만, **한 번도 실행된 적이 없어서** 아무도 몰랐다.
//
// ### 하드 스톱 넷 — 순서대로 배치돼 있었다
//
//     bts-deploy.sh:12   config.sh 가 gitignored + cleanWs → exit 1 (REMOTE_DIR 정의조차 안 됨)
//     bts-deploy.sh:22   젠킨스는 detached HEAD → "HEAD" != "main" → exit 1
//     bts-deploy.sh:182  젠킨스 이미지에 rsync 없음 → exit 127
//     bts-deploy.sh:243  docker compose 없음 — 이미지엔 docker 바이너리만
//
// 하나를 고치면 다음이 나오는 구조라 배포 시도를 넷 해야 알 수 있었다.
//
// ### 그리고 파괴적이었다
//
// 위를 다 메우면 `rsync --delete` 가 돈다. 제외 목록에 없던 것들이 대상에 실재했다.
//
//     backups/                   588K · **유일한 DB 덤프**
//     infra/jenkins/.env         젠킨스 관리자 비밀번호
//     infra/jenkins/.deploy-key  GitHub 배포키(개인키)
//
// 백업이 사라진 **직후에** 백업을 뜨는 순서라, 복구 수단이 먼저 증발한다.
//
// ### 그리고 침묵했다
//
// 배포 후 확인이 전부 `cmd && echo ✅ || echo ⚠️` 라 **종료 코드가 항상 0**이었다.
// 마이그레이션이 실패하든 컨테이너가 크래시 루프를 돌든 `✅ 배포 명령 완료` 가 찍힌다 —
// 롤백을 시작할 신호 자체가 안 뜬다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const DEPLOY = join(ROOT, 'infra/deploy/bts-deploy.sh');
const DOCKERFILE = join(ROOT, 'infra/jenkins/Dockerfile');
const COMPOSE = join(ROOT, 'infra/jenkins/docker-compose.jenkins.yml');
const CI = join(ROOT, 'Jenkinsfile');

/** 주석을 걷어낸 코드 줄. */
const codeOf = (p: string): string =>
  readFileSync(p, 'utf-8')
    .split('\n')
    .filter((l) => !/^\s*#/.test(l))
    .join('\n');

describe('① rsync --delete 가 운영 상태를 지우지 않는다', () => {
  /** 배포가 절대 지우면 안 되는 것 — 저장소가 모르는 운영 상태. */
  const MUST_PROTECT = [
    'backups',
    'infra/jenkins/.env',
    'infra/jenkins/.deploy-key',
    'infra/prod/.env',
    'infra/secrets',
  ];

  test('★★보호 목록이 실제로 선언돼 있다', () => {
    const code = codeOf(DEPLOY);
    assert.match(
      code,
      /PROTECTED=\(/,
      '보호 목록이 없다 — 제외 항목을 rsync 호출부에 직접 적으면 두 벌이 되고,\n' +
        '  한쪽만 고쳐지는 순간 지워질 것이 조용히 늘어난다.',
    );

    // ★★재조준 (2026-09-11). 종전은 `bts-deploy.sh` **소스에서** `'backups'` 같은 리터럴을
    //   찾았다. 그 목록이 `infra/deploy/protected-paths.txt` 로 빠지면서 — 읽는 쪽이
    //   `bootstrap.sh` 의 chown 까지 둘이 됐다 — 소스에서 사라져 red 가 났다.
    //
    //   표면이 옮겨졌으므로 **판별식도 따라간다.** 그리고 이왕 옮기는 김에 더 강하게 본다.
    //   소스에 그 문자열이 있는지가 아니라 읽기 스크립트를 **실제로 돌려** 무엇이 나오는지.
    //   「소스에 적혀 있다」와 「실행하면 그것이 나온다」는 다르다.
    const r = spawnSync('bash', [join(ROOT, 'infra/deploy/read-protected-paths.sh')], {
      encoding: 'utf-8',
    });
    assert.equal(r.status, 0, `read-protected-paths.sh 가 exit ${r.status} 로 죽었다\n${r.stderr}`);
    const actual = (r.stdout ?? '').split('\n').filter((l) => l.trim() !== '');
    const missing = MUST_PROTECT.filter((p) => !actual.includes(p));
    assert.deepEqual(
      missing,
      [],
      `보호 목록을 실제로 읽었더니 ${missing.join(', ')} 가 없다.\n` +
        '★2026-09-11 실측으로 이 경로들이 대상에 실재했다. 제외에 없으면 배포가 지운다.\n' +
        '  `backups/` 는 **유일한 DB 덤프**이고, 그것이 사라지면 되돌릴 수단이 없다.\n' +
        `  실제로 읽힌 목록: ${actual.join(', ') || '(비었다)'}`,
    );
  });

  test('★★rsync 가 그 목록을 실제로 쓴다 (선언만 하고 안 쓰면 공허)', () => {
    const code = codeOf(DEPLOY);
    assert.match(
      code,
      /RSYNC_EXCLUDES\+=\(--exclude=/,
      '보호 목록을 rsync 인자로 안 바꾼다 — 선언만 남고 동작이 없다.',
    );
    const rsyncCall = code.slice(code.indexOf('rsync -avz'), code.indexOf('rsync -avz') + 500);
    assert.match(
      rsyncCall,
      /\$\{RSYNC_EXCLUDES\[@\]\}/,
      'rsync 호출이 제외 배열을 안 받는다.',
    );
  });

  test('★비-공허 확인 — --delete 가 실재한다', () => {
    // ★`--delete` 가 없으면 위 보호가 지킬 대상이 없어 전부 공허해진다.
    assert.match(codeOf(DEPLOY), /rsync[^\n]*--delete/, 'rsync 에 --delete 가 없다.');
  });
});

describe('② 배포 경로가 성립한다', () => {
  test('★★브랜치 판정이 detached HEAD 를 넘는다', () => {
    const code = codeOf(DEPLOY);
    assert.doesNotMatch(
      code,
      /CURRENT_BRANCH=\$\(git rev-parse --abbrev-ref HEAD\)/,
      '브랜치를 **이름**으로 본다 — 젠킨스는 detached HEAD 라 "HEAD" 가 나온다.\n' +
        '  main 을 체크아웃했는데도 「main 이 아니다」로 중단된다.',
    );
    assert.match(
      code,
      /HEAD_SHA=\$\(git rev-parse HEAD\)/,
      '커밋으로 판정하지 않는다 — 물어야 할 것은 「이 커밋이 배포 브랜치인가」다.',
    );
    assert.match(code, /refs\/remotes\/origin\//, '원격 브랜치 ref 를 안 본다.');
  });

  test('★★config.sh 를 못 찾아도 죽지 않는 경로가 있다', () => {
    const code = codeOf(DEPLOY);
    // ★이름이 나오는 것만으로는 부족하다. 주석이나 echo 에도 나온다 —
    //   초안에서 `elif [ -n "${NOPE:-}" ]` 로 분기를 죽여도 통과했다(실측).
    //   **분기 조건으로 읽는지**를 본다.
    assert.match(
      code,
      /elif\s*\[\s*-n\s*"\$\{BTS_DEPLOY_CONFIG:-\}"/,
      'config.sh 를 스크립트 옆에서만 찾는다.\n' +
        '★그 파일은 `.gitignore` 라 저장소 체크아웃에 없고, 젠킨스는 매 빌드 `cleanWs` 로\n' +
        '  워크스페이스를 지운다 — 젠킨스 배포는 **구조적으로** 그 파일을 가질 수 없다.',
    );
    assert.match(
      codeOf(CI),
      /BTS_DEPLOY_CONFIG=/,
      'Jenkinsfile 이 설정 경로를 안 넘긴다 — 받는 쪽만 고쳐도 소용없다.',
    );
  });

  test('★★젠킨스 이미지에 rsync 와 docker compose 가 있다', () => {
    const df = readFileSync(DOCKERFILE, 'utf-8');
    assert.match(
      df,
      /install[^\n]*rsync|apt-get install[\s\S]{0,80}rsync/,
      '이미지에 rsync 를 안 넣는다 — 배포 3단계가 exit 127 로 죽는다.',
    );
    // ★설치 경로 문자열만 보면 안 된다 — 다른 이름으로 받아도 그 문자열은 남는다
    //   (초안에서 `cli-plugins/NOPE` 로 바꿔도 통과했다).
    //   **설치 위치**와 **설치 후 실행 확인**을 함께 요구한다.
    assert.match(
      df,
      /-o \/usr\/local\/lib\/docker\/cli-plugins\/docker-compose/,
      'compose plugin 을 그 이름으로 안 받는다 — docker 는 그 파일명만 서브커맨드로 인식한다.\n' +
        "★compose v2 는 `docker` 바이너리의 일부가 **아니다**. 별도 cli-plugin 이라\n" +
        "  정적 docker CLI 만 넣으면 `'compose' is not a docker command` 다.",
    );
    assert.match(
      df,
      /docker compose version/,
      '설치 후 실행 확인이 없다 — 파일만 놓고 안 돌려 보면 이름·권한 실수가 배포 때 드러난다.',
    );
  });

  test('★★배포 대상 디렉터리가 젠킨스에 마운트돼 있다', () => {
    const compose = readFileSync(COMPOSE, 'utf-8');
    assert.match(
      compose,
      /- \/opt\/bts:\/opt\/bts/,
      '`/opt/bts` 가 젠킨스에 안 물려 있다.\n' +
        '★그러면 `BTS_DEPLOY_LOCAL=1` 의 전제(「배포 대상 위에서 실행」)가 거짓이다 —\n' +
        '  배포가 **컨테이너 안에** 새 /opt/bts 를 만들고, `docker compose` 는 DooD 라\n' +
        '  호스트에서 돌아 옛 파일을 본다. 배포가 「성공」하고 아무것도 안 바뀐다.',
    );
  });
});

describe('③ 실패가 실패로 나타난다', () => {
  test('★★배포 후 확인이 종료 코드를 0 으로 뭉개지 않는다', () => {
    const code = codeOf(DEPLOY);
    const tail = code.slice(code.indexOf('compose'));
    const swallow = tail
      .split('\n')
      .filter((l) => /docker exec[^\n]*(bts-web|bts-backend)/.test(l))
      .filter((l) => /\|\|\s*echo/.test(l));
    assert.deepEqual(
      swallow.map((l) => l.trim()),
      [],
      '기동 확인이 `|| echo` 로 끝난다 — **종료 코드가 항상 0**이다.\n' +
        '★마이그레이션이 실패하든 컨테이너가 크래시 루프를 돌든 「✅ 배포 명령 완료」가 찍힌다.\n' +
        '  롤백을 시작할 신호 자체가 안 뜬다 — 「실패가 아니라 침묵」이다.',
    );
  });

  test('★★기동을 기다린다 (sleep 10 은 부팅보다 짧다)', () => {
    const code = codeOf(DEPLOY);
    assert.match(
      code,
      /wait_for\s*\(\)/,
      '기동 대기 함수가 없다 — 고정 sleep 은 정상 배포도 실패로 읽게 만든다.',
    );
    assert.match(code, /return 1/, '대기가 실패해도 안 죽는다.');
  });
});
