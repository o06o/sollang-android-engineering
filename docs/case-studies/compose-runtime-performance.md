# Compose 재구성·렌더링 성능 트러블슈팅

## 문제

재생 waveform, AI 채팅 streaming, 무한 animation처럼 자주 바뀌는 값이 큰 화면 상태에 포함되면
그 값을 사용하지 않는 UI까지 반복해서 무효화될 수 있습니다. Compose를 사용했다는 사실보다
**상태를 어디에서 읽고 composition·layout·draw 중 어느 단계를 갱신하는지**가 중요했습니다.

점검 당시 확인한 위험은 다음과 같았습니다.

- Visualizer callback이 waveform을 전체 playback state에 넣어 Home의 넓은 상태 경계를 갱신
- 미니 플레이어의 28개 bar animation 값을 composition에서 읽음
- 채팅 streaming 중 표시 목록을 반복 계산
- 하단 blur를 위해 같은 채팅 content를 두 graphics layer에 각각 기록
- lifecycle과 무관하게 Flow를 수집하는 화면

이 작업은 실제 프레임 저하 수치를 확보한 뒤 시작한 최적화가 아니라, Compose Compiler 보고서와
상태 소비 경로를 조사해 불필요한 invalidation과 중복 rendering 위험을 줄인 작업입니다.

## 원인과 변경

| 원인 | 변경 | 기대 효과 |
|---|---|---|
| waveform이 `PlayerState`에 포함됨 | 별도 `StateFlow`로 분리 | 재생 상태와 고빈도 시각화 상태의 갱신 범위 분리 |
| animation 값을 composable body에서 읽음 | `Canvas`, `graphicsLayer`, offset lambda에서 늦게 읽음 | animation frame마다 넓은 composition을 다시 실행할 위험 축소 |
| streaming 표시 목록을 매번 변환 | 메시지와 진행 중 chunk를 key로 `remember` | 입력이 같을 때 목록 변환과 allocation 반복 방지 |
| blur가 같은 content를 두 번 record | graphics layer를 한 번 기록해 원본과 blur가 재사용 | 중복 offscreen rendering 제거 |
| 화면이 보이지 않아도 Flow 수집 | `collectAsStateWithLifecycle` 적용 | 비활성 화면의 불필요한 상태 수집 방지 |

## 상태 경계를 먼저 줄인 이유

불안정한 모델에 안정성 annotation을 붙이는 방법은 선택하지 않았습니다. waveform은 실제로 자주
변하는 값이므로 annotation으로 변경 사실을 숨겨도 invalidation 원인은 사라지지 않습니다.

대신 playback 상태와 waveform을 분리하고, waveform을 사용하는 미니 플레이어의 `Canvas`까지
값 읽기를 내렸습니다. 화면 전체에 필요한 상태와 한 컴포넌트만 필요한 고빈도 상태를 같은 모델에
넣지 않는 방식입니다.

animation도 레이아웃 구조를 바꿀 필요가 없다면 composition에서 값을 꺼내지 않았습니다. 링 두께는
`drawBehind`, 이미지 크기는 `graphicsLayer`, 위치 변화는 offset lambda에서 읽어 필요한 단계만
무효화되도록 했습니다.

## 검증

- Release Compose Compiler 보고서에서 strong skipping 활성 여부를 확인했습니다.
- 화면 전용 상태와 player/favorite 표시 모델이 stable로 판정되는지 확인했습니다.
- Home, Chatting, Player와 designsystem 모듈의 compile·lint를 실행했습니다.
- 저장소 architecture/convention lint와 앱 assemble을 통과시켰습니다.

Compiler 보고서는 최적화 후보와 stability 계약을 확인하는 정적 근거입니다. 실제 사용자 기기의
jank가 사라졌다는 증거는 아니므로 측정하지 않은 성능 향상 수치나 XML 대비 우위를 주장하지 않습니다.

## 남은 한계

- 당시 저장소에는 Macrobenchmark 모듈과 이전/이후 `FrameTimingMetric` 자료가 없었습니다.
- 최종 성능 판단에는 release 빌드에서 Home 재생·확장과 Chat streaming 시나리오를 반복 측정해야 합니다.
- blur와 offscreen layer는 재구성과 별개로 GPU 비용이 있으므로 저사양 실기기 검증이 필요합니다.

이 사례의 핵심은 “Compose가 빠르다”가 아니라, **고빈도 상태의 소비 범위를 줄이고 확인 가능한
근거와 확인하지 못한 한계를 분리한 것**입니다.
