#!/usr/bin/env bash
# bootJar 산출물에 FakeProvider 클래스가 포함되지 않았는지 검증하는 스크립트

set -euo pipefail

JAR_DIR="backend/modules/identity-access/build/libs"

if ! find "$JAR_DIR" -name "*.jar" | grep -q .; then
  echo "FAIL: bootJar 산출물을 찾을 수 없습니다. 먼저 ./gradlew :modules:identity-access:bootJar 를 실행하세요."
  exit 1
fi

if find "$JAR_DIR" -name "*.jar" -exec unzip -l {} \; | grep -q "Fake.*Provider"; then
  echo "FAIL: bootJar에 Fake Provider 클래스가 포함되어 있습니다."
  echo "      @Profile(\"test-spi\") 어노테이션이 올바르게 적용됐는지 확인하세요."
  exit 1
fi

echo "OK: bootJar에 Fake Provider 클래스가 포함되지 않았습니다."
