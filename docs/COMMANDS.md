# Coffee Order System Commands

이 문서는 로컬 설정, 실행, 포맷과 검증 명령의 정본이다. 모든 명령은 저장소 루트에서 실행하며 현재 환경에 맞는 표기를 사용한다.

- Windows PowerShell: `.\gradlew.bat <task>`
- POSIX 셸(Linux, macOS, WSL): `./gradlew <task>`

- JDK 21을 사용한다.
- 별도 Gradle 설치 대신 저장소의 Gradle Wrapper를 사용한다.
- 현재 초기 설정은 외부 DB 없이 기동된다. JPA, MySQL, Flyway와 Testcontainers는 해당 구현 단계에서 연결한다.

## 실행 전 preflight

MySQL Testcontainers 테스트 또는 로컬 MySQL 컨테이너를 사용하는 `bootRun`을 실행하기 전에 Docker Desktop이나 호환 Docker runtime을 시작하고 daemon 연결을 확인한다.

```console
docker version
```

출력에 Client 정보뿐 아니라 `Server` 섹션도 있어야 한다. `Server` 섹션이 없거나 daemon 연결 오류가 나오면 테스트나 애플리케이션을 실행하기 전에 Docker runtime을 먼저 정상화한다.

Step 01에서 MySQL patch 이미지 태그를 고정한 뒤, Testcontainers와 로컬 `bootRun`이 함께 사용할 정확한 컨테이너 시작·종료 명령과 datasource 환경 변수를 이 문서와 `README.md`에 기록한다. 태그와 명령이 실제로 확정되기 전에는 임의의 예시를 실행 계약으로 사용하지 않는다.

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
.\gradlew.bat bootRun
```

POSIX 셸(Linux, macOS, WSL):

```sh
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
- Phase Issue 자동 실행 스킬의 상태 전이와 review envelope schema parity를 Python 단위 테스트로 검증한다.
- 실패한 테스트 리포트는 7일 동안 workflow artifact로 보존한다.
- 문서도 Spotless 검사 대상이므로 문서만 변경한 pull request도 CI를 생략하지 않는다.

## 명령 관리 원칙

- 현재 `build.gradle`과 저장소에 실제로 존재하는 task와 스크립트만 사용한다.
- 현재 설정에 없는 린트, 포맷, Docker 또는 CI 명령을 추측해서 실행하지 않는다.
- 별도 통합 테스트 task나 실행 절차를 추가하면 이 문서를 같은 변경에서 갱신한다.
- 검증을 실행하지 못했다면 완료를 주장하지 않고 실행하지 못한 항목과 이유를 보고한다.
