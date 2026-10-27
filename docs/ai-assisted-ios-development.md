# AI를 활용한 Android → iOS 개발

## 요약

기존 Sollang Android 앱의 제품 동작과 운영 정책을 기준으로 iOS 클라이언트를 구현하면서 AI를 코드 생성기가
아니라 **계약 탐색, 플랫폼 변환, 검증과 문서 동기화 도구**로 사용했습니다.

목표는 Kotlin 코드를 Swift로 번역하는 것이 아니었습니다. 같은 사용자 결과를 유지하되 SwiftUI,
Swift Concurrency, Observation, AVFoundation, Core Data와 Tuist에 맞는 구조를 선택하는 것이었습니다.

## 역할 구분

| 사람이 책임진 것 | AI가 가속한 것 |
|---|---|
| 제품 동작과 우선순위 결정 | Android 코드·문서·소비자 검색 |
| 공개 API와 모듈 경계 승인 | 플랫폼별 대응 후보와 영향 범위 정리 |
| 보안·개인정보·외부 공개 판단 | 반복적인 DTO·mapper·test fixture 초안 |
| Android와 다르게 구현할 지점 결정 | Android/iOS 동작 차이 목록화 |
| 실기기 결과와 최종 merge 판단 | 테스트 실패 원인 후보와 회귀 범위 탐색 |
| 확인되지 않은 사실을 `Unknown`으로 남기기 | 기준 문서·제품 스펙·코드의 불일치 탐지 |

AI가 기능을 자율적으로 결정하거나 검증 없이 코드를 배포한 것은 아닙니다.

## 작업 시스템

### 1. Android를 코드가 아닌 계약의 출발점으로 사용

Android의 화면, ViewModel, domain/data 경계와 신뢰성 문서에서 다음을 먼저 추출했습니다.

- 사용자에게 보여야 하는 결과
- 성공뿐 아니라 실패·취소·복구 경로
- 서버 API와 WebSocket 계약
- 플랫폼 독립적인 불변식
- Android 구현에만 해당하는 제약

### 2. iOS 저장소에 지속 가능한 문맥 구성

짧은 작업 지도와 목적별 기준 문서를 저장소에 함께 관리했습니다.

```text
AGENTS.md
├── ARCHITECTURE.md
├── docs/FRONTEND.md
├── docs/RELIABILITY.md
├── docs/SECURITY.md
├── docs/product-specs/
└── docs/exec-plans/
```

AI가 새 작업을 시작할 때 전체 저장소를 추측하는 대신, 변경 종류에 맞는 계약을 읽고 소비자와 테스트를 검색하도록
했습니다. 확인되지 않은 동작은 코드로 채우지 않고 `Unknown` 또는 실기기 검증 항목으로 남겼습니다.

### 3. 플랫폼 의미를 보존한 변환

| Android | iOS | 공유한 것 |
|---|---|---|
| Compose + StateFlow | SwiftUI + Observation | state/action 경계와 사용자 상태 |
| Coroutines | Swift Concurrency | 취소와 lifecycle 소유권 |
| Hilt composition | AppDependencies composition root | Feature가 구현체를 모르는 방향 |
| Media3 | AVFoundation | 재생 상태와 세션 종료 규칙 |
| Room | Core Data | 사용자 범위 데이터와 migration 정책 |
| AlarmManager timer | app-owned timer controller | timer identity와 중복 종료 방지 |
| SockJS/STOMP manager | actor 기반 connection manager | foreground/background와 재연결 정책 |
| Gradle convention lint | Python architecture lint | 금지된 모듈 의존의 자동 검사 |

플랫폼 API가 다르면 구현을 그대로 번역하지 않았습니다. 예를 들어 Android Data 계층이 앱 lifecycle을 직접 관찰하는
구조를 iOS에서 복제하지 않고, UIKit을 Data 모듈에 노출하지 않도록 앱이 lifecycle 신호를 전달하게 했습니다.

### 4. 작은 단위로 구현하고 같은 계약을 검증

각 작업은 다음 순서로 진행했습니다.

1. Android 변경과 관련 제품 스펙 확인
2. iOS에서 동일 문제가 존재하는지 검색
3. 의도적으로 달라야 하는 플랫폼 차이 기록
4. 작은 모듈 또는 사용자 흐름 단위 구현
5. Swift package test와 architecture lint 실행
6. 앱 조립에 영향이 있으면 Tuist generate와 Xcode build 수행
7. 자동화가 다루지 못하는 항목을 실기기 검증 목록에 이동

## 대표 사례

### 채팅 QA 수정의 양 플랫폼 전파

Android에서 발견한 `CONNECTED` 대기, 응답 timeout, 종료 frame 실패와 중복 응답 문제를 커밋과 테스트 단위로
분해했습니다. AI가 iOS 소비자와 상태 전환을 검색해 영향 표를 만들고, 사람은 Android와 동일한 사용자 결과가
필요한 항목과 iOS에서 다르게 처리할 항목을 결정했습니다.

그 결과 iOS에는 다음이 반영됐습니다.

- handshake부터 STOMP 연결 완료까지 제한 시간
- 연결이 없는 전송의 재연결과 1회 재시도
- 응답 시작 timeout 후 실패 상태 복구
- background 연결 중지와 foreground 재연결
- 종료 전송에 실패하면 화면을 닫지 않는 정책

### 아키텍처 수렴

초기 iOS 구현에는 Feature가 Repository를 직접 사용하거나 앱 target에 화면 코드가 남는 경계가 있었습니다.
Android 경계를 참고하되 Swift 프로젝트에 맞게 UseCase, Domain/Data, composition root와 선택적 TCA 구조로
정리했습니다. AI는 import와 target graph를 반복 검색하고 수정 후보를 만들었고, 최종 경계와 예외는 문서와 lint로
고정했습니다.

### 제품 분석 이벤트 일치

Android의 닫힌 analytics event 계약을 iOS에 옮기며 이벤트 이름, property, 발생 시점과 개인정보 제외 규칙을
대조했습니다. SDK 타입은 Feature에 노출하지 않고 앱 composition에서 Amplitude adapter를 연결했습니다.

## 현재 규모와 검증 증거

2026-10-27 로컬 저장소 기준:

- iOS Git history: 272 commits
- 주 작성자 명의 커밋: 256
- production Swift 파일: 374
- Swift test 파일: 73
- Markdown 기준 문서: 124
- SwiftUI, Swift Concurrency, Observation, 선택적 TCA
- Tuist 기반 Core/Domain/Data/Feature target graph
- module unit test, architecture lint, Xcode build 검증 경로

이 숫자는 AI가 모두 작성했다는 의미가 아닙니다. AI를 포함한 개발 시스템이 적용된 저장소의 현재 규모를 나타냅니다.

## 품질을 지키기 위한 제한

- AI가 제안한 외부 의존성은 표준 API나 기존 의존성으로 해결할 수 없는 경우에만 채택합니다.
- API, route, repository 계약을 바꾸기 전에 모든 소비자를 검색합니다.
- 자동 테스트 결과와 과거 문서의 통과 기록을 현재 빌드 통과로 간주하지 않습니다.
- 기기 권한, background, 소셜 로그인, WebSocket과 오디오는 실기기 검증을 별도로 남깁니다.
- 서버 계약이 불명확하면 임의 DTO를 만들지 않고 확인 항목으로 기록합니다.
- 비밀값, 사용자 데이터와 내부 endpoint를 AI 입력·공개 저장소에 포함하지 않습니다.

## 한계와 다음 개선

- AI 도입 전후의 개발 시간은 통제된 방식으로 측정하지 않아 절감률을 주장하지 않습니다.
- iOS 원본 저장소는 비공개이므로 이 문서는 의사결정과 검증 방식을 중심으로 공개합니다.
- 자동 screenshot·visual regression보다 기능·상태 테스트를 우선했으며, 시각 회귀 검증은 확대 여지가 있습니다.
- WebSocket 장애가 장시간 지속될 때 고정 재연결 간격이 만드는 부하는 실제 관측 후 backoff 도입 여부를 판단해야 합니다.

