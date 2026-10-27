# 멀티모듈 경계를 빌드 규칙으로 바꾸기

## 문제

초기에는 모듈 책임을 문서로만 설명했습니다. 일정이 급할 때 Feature가 Room이나 Data 구현을 직접 참조하거나,
Feature끼리 연결되는 변경을 코드 리뷰가 놓칠 수 있었습니다. 이런 의존은 당장 동작하지만 교체·테스트·병렬 작업
비용을 뒤로 미룹니다.

## 구조

```text
app (composition root)
├── feature implementation
│   ├── feature API
│   ├── domain API
│   └── platform API
├── domain implementation
│   └── data API
└── data implementation
    └── network / database / datastore
```

주요 규칙은 다음과 같습니다.

- Feature는 Domain API를 사용하고 Data·Room 구현을 직접 알지 않습니다.
- Domain은 Android framework와 Feature를 알지 않습니다.
- API 모듈은 대응하는 구현 모듈에 의존하지 않습니다.
- Feature 간 이동은 route와 router 계약을 통합니다.
- 앱 모듈은 구체 구현을 조립하지만 비즈니스 로직을 소유하지 않습니다.

## 문서에서 기계적 검사로

Gradle convention plugin에 다음 검사를 추가했습니다.

- project dependency graph에서 금지된 방향 탐지
- production source import에서 Room·DataStore·Data 구현 누수 탐지
- Feature API에 무거운 Feature convention이 적용됐는지 검사
- 규칙 자체에 허용·금지 단위 테스트 제공

CI는 architecture lint와 unit test를 통과한 커밋만 배포 요청 단계로 보냅니다. Jenkins도 서명과 배포 전에
같은 검증을 다시 수행합니다.

## 89개 모듈을 어떻게 해석하는가

모듈 수는 성과 지표가 아닙니다. 실제 저장소는 API/implementation 분리 때문에 모듈 수가 많고, 다음 비용이 있습니다.

- Gradle configuration과 탐색 비용
- 새 기능 추가 시 파일·target 생성 비용
- 작은 계약 변경이 여러 모듈에 퍼지는 비용
- 처음 참여한 개발자가 경계를 이해하는 시간

그럼에도 인증, 플레이어, 실시간 채팅처럼 구현 교체와 테스트 격리가 중요한 경계는 분리 효과가 컸습니다.
반대로 소비자가 하나뿐이고 독립 배포·재사용 가능성이 없는 얇은 모듈은 합치는 편이 낫습니다.

면접에서 강조할 부분은 “많이 나눴다”가 아니라 다음입니다.

1. 어떤 변경 축을 격리하려 했는가
2. 어떤 잘못된 의존이 반복됐는가
3. 문서만으로 막히지 않아 어떤 검사를 추가했는가
4. 현재 구조에서 다시 합칠 수 있는 경계는 어디인가

## 결과와 한계

- 잘못된 계층 의존은 리뷰 전에 로컬과 CI에서 실패합니다.
- 테스트 fake가 Android framework 구현에 덜 의존합니다.
- 구조 원칙과 실제 Gradle graph의 불일치를 빠르게 발견합니다.
- 일반 Kotlin 품질은 compiler와 Android Lint를 사용하며, Detekt는 현재 빌드 조합과의 호환성을 확인한 뒤 도입할 후속 과제입니다.

