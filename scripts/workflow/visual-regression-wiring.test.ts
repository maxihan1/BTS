// 시각 회귀(P3)가 고아가 아닌지 · 기준 이미지가 생기면 자동으로 켜지는지 대조
//
// ## 무엇을 막는가 — 2026-09-11 실측
//
// `apps/web/e2e/visual/visual-regression.spec.ts` 에 스펙 4건(2화면 × 2테마)이 작성돼
// 있는데, 그것을 **돌리는 곳이 저장소 어디에도 없었다.**
//
//     chromium 프로젝트   testIgnore: '**​/e2e/visual/**'   ← 이 스펙을 제외한다
//     파이프라인 전량      `--project=chromium` 하드코딩     ← visual 을 안 부른다
//     `--project=visual`  문서·주석에만 3건, 배선 0건
//
// 그리고 `.claude/skills/bts/SKILL.md` 는 「4화면은 `visual` 잡이 면제」라며 **존재하지
// 않는 잡**을 눈확인 면제 근거로 세우고 있었다. 눈확인도 기계 검증도 없는 화면 4개다.
// (그 문구는 같은 날 제거했다.)
//
// ## 왜 조건부인가
//
// 기준 이미지가 0장인 동안 돌리면 비교 대상이 없어 전부 실패한다. 그 빨간불은 회귀가
// 아니라 「아직 도입 안 됨」이라 **거짓 빨강**이고, 거짓 빨강은 사람에게 빨간불을 끄는
// 법을 가르친다. 그래서 기준이 실재할 때만 돈다.
//
// ★조건의 입력이 「기준 이미지 디렉터리」 하나뿐이라 두 목록이 생기지 않는다.
//   기준이 커밋되는 순간 자동으로 켜진다 — 배선을 따로 고칠 필요가 없다.
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { hasVisualBaseline, visualDecision, visualLines } from './select-test-scope.ts';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const JENKINSFILE = join(ROOT, 'Jenkinsfile');
const PW_CONFIG = join(ROOT, 'apps/web/playwright.config.ts');
const BASELINE_DIR = join(ROOT, 'apps/web/e2e/visual/__screenshots__');

const baselineCount = (): number =>
  existsSync(BASELINE_DIR) ? readdirSync(BASELINE_DIR).filter((f) => f.endsWith('.png')).length : 0;

describe('시각 회귀 배선', () => {
  test('★비-공허 확인 — visual 프로젝트와 스펙이 실재한다', () => {
    assert.match(
      readFileSync(PW_CONFIG, 'utf-8'),
      /name:\s*'visual'/,
      "playwright.config.ts 에 'visual' 프로젝트가 없다 — 아래 대조가 통째로 공허해진다.",
    );
    assert.ok(
      existsSync(join(ROOT, 'apps/web/e2e/visual/visual-regression.spec.ts')),
      '시각 스펙 파일이 없다 — 지킬 대상이 없다.',
    );
  });

  /*
   * ★★판정을 **순수 함수**로 잰다 (2026-09-11).
   *
   * 초안은 `renderCommands` 의 출력을 봤다. 그런데 기준 이미지가 0장인 지금은
   * 「돈다」 갈래를 디스크로 만들 수 없어서, 그 분기를 통째로 지운 뮤테이션도
   * **통과했다**(실측 — ①③이 5/0 그대로였다).
   *
   * 도입 전 기능의 배선은 이렇게 구조적으로 공허해지기 쉽다. 판정을 IO 에서 떼면
   * 세 갈래를 지금 전부 잴 수 있다.
   */
  test('★★기준이 있고 프론트가 바뀌면 돈다', () => {
    assert.equal(
      visualDecision(true, 'all'),
      'run',
      '기준이 있는데 시각 회귀를 안 돌린다 — 기준을 커밋해도 켜지지 않는다.',
    );
    assert.equal(visualDecision(true, 'related'), 'run', '부분 프론트 변경에서 안 돈다.');
    assert.match(
      visualLines(true, 'all').join('\n'),
      /--project=visual/,
      '「돈다」 판정인데 실행 줄에 `--project=visual` 이 없다.\n' +
        '★`chromium` 은 `e2e/visual/**` 를 testIgnore 하므로 그 프로젝트로는 절대 안 돈다.',
    );
  });

  test('★★기준이 0장이면 안 돌되 그 사실과 켜는 방법을 알린다', () => {
    assert.equal(visualDecision(false, 'all'), 'no-baseline');
    const block = visualLines(false, 'all').join('\n');
    assert.match(
      block,
      /기준 이미지 0장/,
      '미도입 사실이 실행 블록에 안 보인다 — 「생략」이 조용하면 화면 4개가 검증 0회인 줄 모른다.',
    );
    assert.match(
      block,
      /UPDATE_VISUAL_BASELINE/,
      '켜는 방법이 안 적혀 있다 — 「생략」만 보이면 영영 도입되지 않는다.',
    );
    assert.doesNotMatch(block, /--project=visual\b(?!.*update)/, '기준이 없는데 돌리려 든다.');
  });

  test('★프론트가 안 바뀌면 안 돈다 (비-공허 짝)', () => {
    assert.equal(
      visualDecision(true, 'skip'),
      'no-frontend',
      '백엔드만 바뀌었는데 시각 회귀가 돈다 — 화면 픽셀은 프론트 소스에서만 바뀐다.',
    );
  });

  test('★기준 이미지 판정이 디스크를 실제로 본다', () => {
    // ★`hasVisualBaseline` 이 상수가 되면 위 세 갈래가 전부 공허해진다.
    assert.equal(hasVisualBaseline(join(ROOT, 'scripts')), false, 'PNG 없는 곳을 있다고 한다.');
    assert.equal(
      hasVisualBaseline(join(ROOT, '__does_not_exist__')),
      false,
      '없는 디렉터리를 있다고 한다.',
    );
  });

  test('★★기준 이미지를 만드는 경로가 파이프라인에 있다', () => {
    const jf = readFileSync(JENKINSFILE, 'utf-8');
    assert.match(
      jf,
      /UPDATE_VISUAL_BASELINE/,
      '기준 이미지를 이 러너에서 만드는 경로가 없다.\n' +
        '★기준 PNG 는 OS·아키텍처·폰트 렌더가 같은 기계에서만 유효하다.\n' +
        '  맥에서 만든 것을 리눅스 러너가 비교하면 전량 diff 다.',
    );
    assert.match(
      jf,
      /--project=visual --update-snapshots/,
      '기준 생성 명령이 없다.',
    );
  });

  test('★★파이프라인이 기준 이미지를 스스로 커밋하지 않는다', () => {
    const jf = readFileSync(JENKINSFILE, 'utf-8');
    const at = jf.indexOf("stage('시각 기준 이미지 생성')");
    assert.ok(at >= 0, '기준 생성 stage 를 못 찾았다.');
    const body = jf.slice(at, jf.indexOf("stage('", at + 10));
    assert.doesNotMatch(
      body,
      /git (commit|push|add)/,
      '파이프라인이 기준 이미지를 스스로 커밋한다.\n' +
        '★그것이 곧 「회귀를 승인하는 재생성」이고, `snapshot-baseline-guard.test.ts` 가\n' +
        '  막으려는 고장 그 자체다. 아티팩트로만 남기고 사람이 확인해 커밋한다.',
    );
    assert.match(body, /archiveArtifacts/, '생성한 기준을 아티팩트로 안 남긴다 — 받을 방법이 없다.');
  });
});
