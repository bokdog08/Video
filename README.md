# MultiView Studio

[![Build Android APK](https://github.com/bokdog08/Video/actions/workflows/build-apk.yml/badge.svg)](https://github.com/bokdog08/Video/actions/workflows/build-apk.yml)

최신 설치용 디버그 APK: [MultiViewStudio-debug.apk](https://github.com/bokdog08/Video/releases/latest/download/MultiViewStudio-debug.apk)

가로 전체화면에서 최대 12개의 로컬 영상을 자유롭게 배치·재생하고, 영상별 하이라이트 시퀀스를 편집한 뒤 현재 구성을 MP4로 합성하는 Android 앱입니다.

## 개발 환경

- Android Studio / Kotlin / Jetpack Compose
- minSdk 29, targetSdk 36, compileSdk 36
- AndroidX Media3 1.11.0 (`ExoPlayer`, `Transformer`, `Composition`)
- Room 프로젝트 저장소, WorkManager 백그라운드 Export
- JDK 17, Gradle 8.11.1, Android Gradle Plugin 8.10.1

Android Studio에서 이 폴더를 열고 SDK 36을 설치한 뒤 Gradle Sync를 실행합니다. 디버그 빌드는 다음 명령과 같습니다.

```bash
./gradlew testDebugUnitTest assembleDebug
```

생성 APK: `app/build/outputs/apk/debug/app-debug.apk`

## 사용 흐름

1. 빈 Tile의 `+`를 누르면 영상 하나, 길게 누르면 여러 영상을 선택합니다.
2. 상단의 Grid 아이콘에서 1/2/4/6/9/12분할을 선택하거나 이동 아이콘으로 자유배치 모드를 켭니다.
3. Tile을 탭해 재생·±10초·Seek·볼륨을 조작합니다. 컨트롤은 마지막 조작 4초 후 사라집니다.
4. `⋮`에서 배속, 음소거, Audio Pan, 반복, FIT/FILL/CROP, Zoom 초기화, 레이어, 잠금, 교체를 설정합니다.
5. 하이라이트 편집에서 시작/끝 또는 가위 두 점으로 구간을 만들고, 경계를 끌거나 ±1초·±0.1초·1프레임 단위로 보정합니다. 구간을 길게 끌어 순서를 바꿉니다.
6. 다운로드 아이콘에서 결과 길이, 짧은 영상 처리, 해상도, FPS, 코덱, 오디오 Mix를 선택하고 미리보기 또는 제작을 시작합니다.
7. 결과는 `Movies/MultiViewStudio/MultiView_yyyy-MM-dd_HHmmss.mp4`로 저장됩니다.

## 구현 범위

- Immersive landscape, 영상 중심의 반투명 UI
- Tile별 ExoPlayer, 속도·볼륨·Mute·Stereo Pan·소스 모드 독립 제어
- 길게 누르는 동안 임시 2배속, 더블 탭 전체화면, Pinch/Drag 영상 확대·이동
- 자유 이동, 네 변/우하단 크기 조절, 겹침, Z-index, 잠금
- SAF와 persistable URI 권한, `ACTION_SEND`/`ACTION_SEND_MULTIPLE`, Android Drag & Drop
- Room 기반 자동 저장/복원과 여러 프로젝트 관리
- NONE/FULL/A–B/HIGHLIGHT 반복, 순차/구간별 반복/결정적 랜덤 하이라이트 재생
- 하나의 `TimelineEngine`이 미리보기와 Export의 소스 구간, 배속, 반복, 결과 길이를 계산
- Export 시작 시 불변 Snapshot 저장
- Media3 Composition 다중 영상 합성, Tile 변환·Crop/Zoom·레이어 순서 적용
- Media3 AudioProcessor로 Preview/Export에 동일한 Volume·Mute·Pan 적용
- 가장 긴 가상 영상 또는 직접 지정 길이, 자동 반복/마지막 프레임/검은 화면
- H.264 또는 HEVC, 1080p/1440p/4K/사용자 지정, 원본 FPS/30/60
- WorkManager Foreground Export, 진행률·취소·오류 안내, MediaStore 저장/재생/공유
- 기기 하드웨어 디코더 개수 감지와 Player Pool 축소 운용. 한도를 넘는 Tile은 정지 Placeholder로 남아 탭 시 LRU 방식으로 활성화됩니다.

## 구조

- `model/ProjectModels.kt`: 영속 프로젝트, Tile, 하이라이트, Export Snapshot 모델
- `timeline/TimelineEngine.kt`: Preview/Export 공통 시간 매핑
- `playback/PlayerManager.kt`: Player Pool과 Tile별 실제 재생 제어
- `audio/PanningAudioProcessor.kt`: Stereo Pan과 Gain 처리
- `ui/`: 멀티뷰, Tile 설정, 하이라이트, Export, 프로젝트 화면
- `data/`: Room 데이터베이스와 저장소
- `export/`: Composition 생성, Snapshot 조정, Foreground Worker, MediaStore 게시

## 기기별 주의점

동시에 사용할 수 있는 하드웨어 디코더와 4K/HEVC 인코더 수는 Galaxy 모델과 원본 코덱에 따라 다릅니다. 앱은 감지된 Preview Decoder 예산을 넘기지 않고, 실패 시 재시도 가능한 Placeholder와 원인 메시지를 표시합니다. 12개 고해상도 소스의 4K 합성은 발열·메모리·저장 공간 영향을 크게 받으므로 실제 목표 기기에서 긴 Export 전 짧은 미리보기와 1080p Export를 먼저 확인하는 것이 좋습니다.
