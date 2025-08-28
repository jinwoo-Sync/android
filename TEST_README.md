# 테스트 가이드

##  테스트 구조

### 단위 테스트 (Unit Tests)
위치: `app/src/test/java/com/example/myapplication/`

- **SensorCollectorTest**: 센서 데이터 수집 로직 테스트
- **CircularQueueTest**: 순환 큐 데이터 구조 테스트
- **DataSynchronizerTest**: 데이터 동기화 로직 테스트
- **HomeRepositoryTest**: 리포지토리 패턴 구현 테스트

### 계측 테스트 (Instrumented Tests)
위치: `app/src/androidTest/java/com/example/myapplication/`

- **HomeFragmentTest**: UI 컴포넌트 동작 테스트
- **PerformanceInstrumentedTest**: 성능 벤치마크 및 메모리 누수 테스트

##  테스트 실행 방법

### 1. 전체 테스트 실행 (추천)
```bash
./run-tests.sh
```

### 2. 개별 테스트 실행

#### 단위 테스트만
```bash
./gradlew test
```

#### 계측 테스트만 (디바이스/에뮬레이터 필요)
```bash
./gradlew connectedAndroidTest
```

#### Lint 검사
```bash
./gradlew lint
```

#### 테스트 커버리지 리포트
```bash
./gradlew testDebugUnitTestCoverage
```

##  CI/CD 파이프라인

### GitHub Actions 워크플로우

1. **android-ci.yml**: 메인 CI/CD 파이프라인
   - 모든 push/PR에서 자동 실행
   - 단위 테스트, Lint, 빌드 수행
   - macOS 러너에서 계측 테스트 실행

2. **code-quality.yml**: 코드 품질 검사
   - PR에서만 실행
   - 하드코딩된 시크릿 검사
   - TODO/FIXME 카운트
   - 테스트 커버리지 분석

##  Pre-commit Hook 설정

```bash
# pre-commit 설치
pip install pre-commit

# hook 설치
pre-commit install

# 수동 실행
pre-commit run --all-files
```

##  테스트 리포트 위치

- **단위 테스트**: `app/build/reports/tests/`
- **Lint**: `app/build/reports/lint/`
- **커버리지**: `app/build/reports/coverage/`
- **계측 테스트**: `app/build/reports/androidTests/`

##  주의사항

1. **메모리 누수 검사**: 
   - Debug 빌드에서 LeakCanary가 자동 실행됨
   - 테스트 후 리포트 확인 필수

2. **성능 테스트**:
   - `PerformanceInstrumentedTest`는 실제 디바이스에서 실행 권장
   - 에뮬레이터에서는 정확한 성능 측정 불가

3. **하드코딩된 값**:
   - API 키, 비밀번호 등은 절대 소스코드에 포함 금지
   - 환경 변수나 빌드 설정 사용

##  테스트 체크리스트

### PR 전 필수 확인사항

- [ ] `./run-tests.sh` 실행 성공
- [ ] 모든 단위 테스트 통과
- [ ] Lint 경고 해결
- [ ] 메모리 누수 없음 (LeakCanary)
- [ ] 하드코딩된 시크릿 없음
- [ ] TODO/FIXME 검토

### 릴리즈 전 추가 확인사항

- [ ] 계측 테스트 통과
- [ ] 성능 벤치마크 기준 충족
- [ ] Release 빌드 성공
- [ ] ProGuard 규칙 검증

##  문제 해결

### 테스트 실패 시

1. 로그 확인: `app/build/reports/tests/`
2. 스택 트레이스 분석
3. 디바이스/에뮬레이터 상태 확인
4. Gradle 캐시 정리: `./gradlew clean`

### 메모리 문제

1. LeakCanary 리포트 확인
2. `BitmapPoolManager` 사용 검증
3. 리소스 해제 확인

### 성능 문제

1. 벤치마크 결과 분석
2. FPS 모니터링 확인
3. GPU 메모리 사용량 체크