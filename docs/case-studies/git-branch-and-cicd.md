# Git·브랜치·CI/CD 운영 정책

## 목표

배포 자동화의 목표를 “push하면 바로 배포”로 두지 않았습니다. 개발 속도와 운영 안전성을 함께
확보하기 위해 다음 세 가지를 분리했습니다.

- GitHub Actions는 저장소에 들어온 변경을 검증합니다.
- Slack 버튼은 사람이 배포 시점을 승인하는 게이트입니다.
- Jenkins는 승인된 **정확한 커밋**을 다시 검증하고 서명·배포합니다.

이 구조는 브랜치가 승인 뒤 이동하더라도 다른 커밋이 배포되는 문제를 막고, 테스트 실패 상태에서는
배포 진입점 자체를 노출하지 않습니다.

## 브랜치 전략

| 브랜치 | 역할 | 시작점·병합 대상 |
|---|---|---|
| `main` | 배포 가능한 안정 상태 | 직접 커밋하지 않고 release·hotfix PR만 병합 |
| `dev` | 다음 배포의 통합 브랜치 | 일반 작업의 시작점이자 topic PR 대상 |
| `feature/*`, `fix/*` 등 | 한 가지 기능·수정·리팩터링 | 최신 `dev`에서 시작해 `dev`로 PR |
| `release/<version>` | 운영 배포 준비 | `dev`에서 분기해 검증 후 `main`으로 병합 |
| `hotfix/*` | 운영 긴급 수정 | `main`에서 시작해 `main`과 `dev`에 모두 반영 |

topic 브랜치는 `feature`, `fix`, `refactor`, `test`, `docs`, `chore`, `build`, `ci`, `perf`로
작업 성격을 드러냅니다. PR은 squash merge를 기본으로 하고, 공유 브랜치에는 force push하지 않습니다.

release가 열린 뒤에도 수정 브랜치는 `dev`를 기준으로 만듭니다. 같은 수정이 release에도 필요하면
하나의 topic 브랜치를 `dev`와 `release/<version>`에 각각 merge합니다. cherry-pick으로 서로 다른
커밋을 만들지 않아 변경의 출처와 포함 여부를 추적할 수 있게 했습니다.

## 배포 흐름

```mermaid
flowchart LR
    Topic[topic branch] --> PR[PR to dev]
    PR --> Dev[dev]
    Dev -->|push| GHA[GitHub Actions\nlint + unit test]
    Dev --> Release[release/version]
    Release -->|push| GHA
    GHA -->|실패| Block[배포 버튼 없음]
    GHA -->|성공| Slack[Slack 배포 승인]
    Slack -->|branch@sha| Jenkins[Jenkins]
    Jenkins --> Gate[version + release notes\nflavor test gate]
    Gate --> Build[서명된 release build]
    Build --> FAD[Firebase App Distribution]
```

GitHub Actions가 감시하는 브랜치와 배포 대상은 다음과 같습니다.

| push 브랜치 | 승인 대상 | Jenkins 빌드·업로드 |
|---|---|---|
| `dev` | 개발 테스터용 DEV | `assembleDevRelease` → `appDistributionUploadDevRelease` |
| `release/**` | 운영 설정의 PROD | `assembleProdRelease` → `appDistributionUploadProdRelease` |

`main`, topic, hotfix 브랜치는 현재 배포 요청 workflow의 직접 감시 대상이 아닙니다. 일반 변경은 `dev`,
운영 후보는 `release/**`에 반영됐을 때만 배포 카드가 만들어집니다.

## 이중 검증 게이트

첫 번째 게이트는 `dev` 또는 `release/**` push에서 실행됩니다.

```bash
./gradlew sollangLint testDebugUnitTest
```

실패 보고서는 CI artifact로 남고 Slack에는 실패 링크만 전송됩니다. 배포 버튼은 만들지 않습니다.

두 번째 게이트는 Slack에서 배포를 승인한 뒤 Jenkins가 실행합니다. Jenkins는 payload의
`<branch>@<sha>`를 해석해 승인 당시 커밋을 checkout하고, flavor를 포함한 검증을 다시 수행합니다.

```bash
# DEV
./gradlew sollangLint testDevDebugUnitTest testDebugUnitTest

# PROD
./gradlew sollangLint testProdDebugUnitTest testDebugUnitTest
```

CI와 Jenkins의 중복 검증은 의도적입니다. CI 이후의 환경 차이와 잘못된 수동 실행을 배포 직전에
한 번 더 차단하며, 하나라도 실패하면 서명과 업로드 단계로 진행하지 않습니다.

## Jenkins 단계

| 단계 | 책임 |
|---|---|
| Resolve Deploy Target | Slack payload에서 flavor·branch·commit SHA 결정 |
| Notify Start | 배포 시작 상태 공유 |
| Checkout | branch의 최신 HEAD가 아니라 전달받은 commit checkout |
| Verify Version | PROD에서 `release/<version>`과 앱 버전 일치 확인 |
| Resolve Release Notes | `CHANGELOG.md`의 대상 섹션을 읽고 빈 내용 차단 |
| Prepare Firebase Config | flavor별 Firebase 설정을 CI credential로 주입 |
| Lint & Test | 서명 전 최종 검증 게이트 수행 |
| Build & Distribute | release 서명 후 Firebase App Distribution 업로드 |

버전 이름·코드는 저장소에서 사람이 관리하고 CI는 값을 임의로 올리지 않습니다. PROD는
`release/<version>`, 앱의 version name, `CHANGELOG.md` 버전 섹션이 서로 맞아야 합니다.
배포 내용은 마지막 커밋 메시지가 아니라 여러 변경을 사용자·테스터 관점으로 모은 changelog에서
가져옵니다.

## 보안과 운영 판단

- release keystore, 비밀번호, Firebase service account와 환경별 설정 파일은 저장소에 두지 않고
  Jenkins credential로 주입합니다.
- DEV와 PROD의 application ID, Firebase 프로젝트, 서명 정보를 분리합니다.
- payload 없는 Jenkins 실행은 배포 대상을 증명할 수 없으므로 실패시킵니다.
- release build에는 R8 code/resource shrinking을 적용하고 난독화 stack trace 복원 정보는 보존합니다.

이 설계는 완전 자동 배포보다 승인 단계가 하나 더 필요합니다. 대신 배포 의도, 검증 결과, 대상 SHA,
버전과 release note가 한 흐름에 남아 소규모 팀에서도 “무엇을 왜 배포했는지”를 추적하기 쉽습니다.

## 확인된 한계

- GitHub Actions의 첫 게이트는 환경 파일을 두지 않기 위해 flavor가 없는 모듈 테스트를 실행합니다.
  `app` 또는 flavor 모듈 테스트는 Jenkins 게이트가 담당합니다.
- 따라서 앱 모듈 테스트가 늘어나면 CI runner의 환경 설정과 첫 게이트 범위를 함께 확장해야 합니다.
- hotfix의 Git 병합 정책은 정의돼 있지만 현재 workflow에서 hotfix branch 자체를 직접 배포하지는 않습니다.

