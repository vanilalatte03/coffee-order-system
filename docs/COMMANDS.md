# Coffee Order System Commands

이 문서는 로컬 설정, 실행, 포맷과 검증 명령의 정본이다. 모든 명령은 저장소 루트에서 실행하며 현재 환경에 맞는 표기를 사용한다.

- Windows PowerShell: `.\gradlew.bat <task>`
- POSIX 셸(Linux, macOS, WSL): `./gradlew <task>`

- JDK 21을 사용한다.
- 별도 Gradle 설치 대신 저장소의 Gradle Wrapper를 사용한다.
- 로컬 실행과 Testcontainers 통합 테스트는 실제 pull·기동을 확인한 `mysql:8.0.42` 이미지를 사용한다.

## 실행 전 preflight

MySQL Testcontainers 테스트 또는 로컬 MySQL 컨테이너를 사용하는 `bootRun`을 실행하기 전에 Docker Desktop이나 호환 Docker runtime을 시작하고 daemon 연결을 확인한다.

```console
docker version
```

출력에 Client 정보뿐 아니라 `Server` 섹션도 있어야 한다. `Server` 섹션이 없거나 daemon 연결 오류가 나오면 테스트나 애플리케이션을 실행하기 전에 Docker runtime을 먼저 정상화한다.

## 로컬 MySQL 시작과 종료

MySQL 컨테이너는 `coffee_order` 데이터베이스와 로컬 개발 계정을 생성하고 호스트의 전용 `3307` 포트를 사용한다. 이미 같은 이름의 컨테이너나 같은 포트를 사용하는 프로세스가 있으면 먼저 종료한다.

Windows PowerShell:

```powershell
docker run --name coffee-order-mysql --detach --publish 3307:3306 --env MYSQL_DATABASE=coffee_order --env MYSQL_USER=coffee --env MYSQL_PASSWORD=coffee --env MYSQL_ROOT_PASSWORD=root mysql:8.0.42
do { docker exec coffee-order-mysql mysqladmin ping -h 127.0.0.1 -ucoffee -pcoffee --silent; if ($LASTEXITCODE -ne 0) { Start-Sleep -Seconds 1 } } while ($LASTEXITCODE -ne 0)
```

POSIX 셸(Linux, macOS, WSL):

```sh
docker run --name coffee-order-mysql --detach --publish 3307:3306 --env MYSQL_DATABASE=coffee_order --env MYSQL_USER=coffee --env MYSQL_PASSWORD=coffee --env MYSQL_ROOT_PASSWORD=root mysql:8.0.42
until docker exec coffee-order-mysql mysqladmin ping -h 127.0.0.1 -ucoffee -pcoffee --silent; do sleep 1; done
```

`mysqld is alive`가 출력되면 준비가 끝난 것이다. 애플리케이션 종료 후 컨테이너를 중지하고 제거한다.

Windows PowerShell:

```powershell
docker stop coffee-order-mysql
docker rm coffee-order-mysql
```

POSIX 셸(Linux, macOS, WSL):

```sh
docker stop coffee-order-mysql
docker rm coffee-order-mysql
```

## Git hook 설정

저장소를 clone하거나 Git 저장소로 초기화한 뒤 clone마다 한 번 공유 pre-commit hook을 활성화한다.

Windows PowerShell:

```powershell
.\scripts\install-git-hooks.ps1
```

POSIX 셸(Linux, macOS, WSL):

```sh
git config --local core.hooksPath .githooks
git config --local --get core.hooksPath
```

두 번째 명령은 `.githooks`를 출력해야 한다.

pre-commit hook은 staged 파일의 공백 오류와 전체 코드 포맷을 검사한다. 파일을 자동 수정하거나 stage하지 않는다.

## 포맷

코드와 문서 포맷을 검사한다.

Windows PowerShell:

```powershell
.\gradlew.bat spotlessCheck
```

POSIX 셸(Linux, macOS, WSL):

```sh
./gradlew spotlessCheck
```

포맷 위반을 자동 수정한다.

Windows PowerShell:

```powershell
.\gradlew.bat spotlessApply
```

POSIX 셸(Linux, macOS, WSL):

```sh
./gradlew spotlessApply
```

자동 수정 후 diff를 확인하고 `spotlessCheck`를 다시 실행한다.

## 애플리케이션 실행

Windows PowerShell:

```powershell
$env:DB_URL = 'jdbc:mysql://localhost:3307/coffee_order?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME = 'coffee'
$env:DB_PASSWORD = 'coffee'
.\gradlew.bat bootRun
```

POSIX 셸(Linux, macOS, WSL):

```sh
export DB_URL='jdbc:mysql://localhost:3307/coffee_order?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
export DB_USERNAME='coffee'
export DB_PASSWORD='coffee'
./gradlew bootRun
```

다른 터미널에서 Health endpoint가 `UP`인지 확인한다.

Windows PowerShell:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health
```

POSIX 셸(Linux, macOS, WSL):

```sh
curl --fail --silent --show-error http://localhost:8080/actuator/health
```

실행 경로나 런타임 설정을 변경했다면 테스트뿐 아니라 애플리케이션 기동과 Health endpoint도 확인한다.

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 생략하면 위 로컬 개발값을 기본값으로 사용한다. Flyway가 migration을 적용하고 Hibernate `ddl-auto=validate`가 스키마를 검증한다. JDBC 연결은 `connectionTimeZone=UTC`와 `forceConnectionTimeZoneToSession=true`로 세션 타임존을 UTC에 고정한다.

## 테스트와 전체 검증

개발 중 빠른 테스트를 실행한다.

Windows PowerShell:

```powershell
.\gradlew.bat test
```

POSIX 셸(Linux, macOS, WSL):

```sh
./gradlew test
```

Phase Issue 자동 실행 스킬의 Python 회귀 테스트를 실행하기 전에 테스트 의존성을 설치한다.

Windows PowerShell:

```powershell
python -m pip install --disable-pip-version-check --no-input --requirement .agents/skills/phase-issue-autopilot/requirements-test.txt
python -m unittest discover -s .agents/skills/phase-issue-autopilot/tests -p "test_*.py"
```

POSIX 셸(Linux, macOS, WSL):

```sh
python3 -m pip install --disable-pip-version-check --no-input --requirement .agents/skills/phase-issue-autopilot/requirements-test.txt
python3 -m unittest discover -s .agents/skills/phase-issue-autopilot/tests -p "test_*.py"
```

fix push 뒤 fresh review가 게시되면 이전 autopilot inline review thread의 정리 계획을
dry-run으로 확인한다. `<previous-review.json>`과 `<fresh-review.json>`은 각각 게시에 사용한
schema version 2 envelope다.

Windows PowerShell:

```powershell
python .agents/skills/phase-issue-autopilot/scripts/resolve_review_threads.py `
  --previous-input <previous-review.json> `
  --current-input <fresh-review.json> `
  --repo <owner/repo> `
  --pr <number> `
  --expected-base develop
```

POSIX 셸(Linux, macOS, WSL):

```sh
python3 .agents/skills/phase-issue-autopilot/scripts/resolve_review_threads.py \
  --previous-input <previous-review.json> \
  --current-input <fresh-review.json> \
  --repo <owner/repo> \
  --pr <number> \
  --expected-base develop
```

dry-run이 의도한 이전 autopilot thread만 `candidates`로 출력한 경우에만 같은 명령에
`--apply`를 추가한다. 사람이 만든 thread, 현재 review에 남은 finding, 소유권이나 snapshot을
검증하지 못한 thread는 resolve하지 않는다.

작업 완료 전 foreground로 실행한 `bootRun` 또는 `java -jar` 터미널이 있다면 해당 터미널에서 `Ctrl+C`로 정상 종료한다. Windows에서는 실행 중인 프로세스가 JAR 파일을 잠가 `clean build`가 실패할 수 있다.

실행 프로세스가 종료된 것을 확인한 뒤 포맷을 검사하고 전체 빌드를 실행한다.

Windows PowerShell:

```powershell
.\gradlew.bat spotlessCheck
.\gradlew.bat clean build
```

POSIX 셸(Linux, macOS, WSL):

```sh
./gradlew spotlessCheck
./gradlew clean build
```

DB 락·제약·트랜잭션 동작을 검증하는 테스트가 추가되면 H2로 대체하지 않고 MySQL Testcontainers로 실행한다.

## CI

GitHub Actions의 [CI workflow](../.github/workflows/ci.yml)는 `develop`·`main` 대상 pull request, 두 branch의 push와 수동 실행에서 `verify` job을 수행한다.

- JDK 21과 저장소의 Gradle Wrapper를 사용한다.
- pull request에서는 변경 범위의 공백 오류를 검사한다.
- `clean build`로 포맷, 테스트, 컴파일과 패키징을 검증한다.
- Phase Issue 자동 실행 스킬의 상태 전이, review 게시·thread resolve 상태 머신과 review
  envelope schema parity를 Python 단위 테스트로 검증한다.
- 실패한 테스트 리포트는 7일 동안 workflow artifact로 보존한다.
- 문서도 Spotless 검사 대상이므로 문서만 변경한 pull request도 CI를 생략하지 않는다.

## 명령 관리 원칙

- 현재 `build.gradle`과 저장소에 실제로 존재하는 task와 스크립트만 사용한다.
- 현재 설정에 없는 린트, 포맷, Docker 또는 CI 명령을 추측해서 실행하지 않는다.
- 별도 통합 테스트 task나 실행 절차를 추가하면 이 문서를 같은 변경에서 갱신한다.
- 검증을 실행하지 못했다면 완료를 주장하지 않고 실행하지 못한 항목과 이유를 보고한다.
