# Architecture

## State boundaries

- `UiState`: 선택, Overlay, 편집 Dialog, Preview/Export 진행 상태만 보관합니다.
- `ProjectState`: 재시작 후 복원되어야 하는 모든 Tile 설정을 불변 데이터로 보관합니다.
- `PlayerManager`: 디코더와 Surface에 연결되는 일시적 런타임 상태입니다.
- `ExportSnapshot`: Export 버튼을 누른 순간의 `ProjectState`와 설정 및 확정 결과 길이를 복사합니다.

## Shared timeline contract

`TimelineEngine.orderedSourceSlices`는 FULL, A–B, 하이라이트 순서, 구간별 반복, 랜덤 순서를 원본 시간 구간의 목록으로 정규화합니다. `cycleDurationMs`는 배속을 적용한 가상 영상 길이를 계산합니다. `slicesToFill`은 Export 결과 길이에 도달하도록 이 목록을 반복하고 마지막 구간을 정확히 자릅니다.

Preview의 각 ExoPlayer playlist와 Export의 각 `EditedMediaItemSequence`가 모두 이 정규화 결과를 사용합니다. RANDOM은 Tile ID를 seed로 사용해 Preview와 Export에서 같은 순서를 만듭니다.

## Export composition

1. 전체 결과 길이의 검은 이미지 Sequence가 바닥 영상과 출력 FPS 기준을 만듭니다.
2. Z-index 오름차순으로 각 Tile의 video-only Sequence를 추가합니다.
3. Crop/Zoom을 먼저 적용한 다음 출력 Canvas로 정규화하고 Tile matrix로 위치와 크기를 반영합니다.
4. 각 활성 오디오를 별도 audio-only Sequence로 추가하고 Gain/Pan Processor를 적용합니다.
5. Transformer가 H.264 또는 HEVC 영상과 AAC 오디오를 MP4로 Encoding합니다.
6. 임시 결과를 MediaStore의 `Movies/MultiViewStudio`에 게시합니다.

## Failure policy

- URI 검사 실패는 해당 Tile을 유지하면서 교체 안내를 표시합니다.
- Decoder 예산 초과 Tile은 Player를 만들지 않으며 탭 시 가장 오래된 Player를 교체합니다.
- Playback codec 오류는 앱을 종료하지 않고 Tile 내부에서 재시도를 제공합니다.
- Export는 Foreground WorkManager에서 실행하고 취소, 공간 부족, 권한, codec 오류를 사용자 메시지로 변환합니다.
