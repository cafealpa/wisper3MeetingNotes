# 고정된 화자 분리 의존성

앱에 모델을 번들로 포함한다. 첫 실행 시 모델 다운로드나 추가 계정은 필요하지 않다.
`app:verifyDiarizationArtifacts`가 매 빌드 시 아래 SHA-256을 검사한다.

| 구성 | 원본 URL | SHA-256 |
| --- | --- | --- |
| sherpa-onnx 1.13.8 AAR | https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar | 633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96 |
| pyannote segmentation 3.0 INT8 | https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 내부 model.int8.onnx | d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d |
| TitaNet Small ONNX | https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx | ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e |

sherpa-onnx: Apache-2.0 (`sherpa-onnx-LICENSE`). Pyannote 모델: 동봉 MIT (`pyannote-LICENSE`).
TitaNet-S: [NVIDIA 모델 카드](https://catalog.ngc.nvidia.com/orgs/nvidia/nemo/models/titanet_small)는 NeMo Toolkit 라이선스를 지정한다. NeMo v1.19.0 Apache-2.0 라이선스를 `NeMo-LICENSE`에 포함했다.

계측 테스트 WAV 출처: https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/0-four-speakers-zh.wav
공식 4인 중국어 예제이며, `androidTest/assets`의 M4A(44.1kHz stereo AAC), MP3(48kHz stereo)는 이 WAV를 ffmpeg로 변환했다. 테스트 자산은 일반 앱 APK에 포함되지 않는다. 이 테스트는 한국어 화자 분리 품질 평가를 대신하지 않는다.

`long-meeting.m4a`는 동일 WAV를 12번 이어 붙인 약 11분 22초의 16kHz mono AAC(32kbps)다. 각 반복의 같은 발화가 여러 분석 창을 거쳐도 동일한 전역 화자로 연결되는지 자동/4명 모드에서 검사한다. 합성 반복 테스트이므로 자연스러운 장시간 회의의 품질을 보장하지 않는다.

공식 v1.13.8 Kotlin/JNI API 및 `offline-speaker-diarization-pyannote-impl.h`를 확인했다. `processWithCallback` 반환값은 현재 native 구현에서 사용하지 않으므로 즉시 취소를 보장하지 않는다. 분석 완료 전 native 객체를 해제하지 않는다.

JNI callback은 `invoke(IIJ)Ljava/lang/Integer;`라는 구체적인 시그니처를 요구한다. Kotlin indy lambda는 이를 제공하지 않아 Android CheckJNI에서 abort할 수 있으므로 `DiarizationProgressCallback` 명시적 클래스를 사용한다. 단위 테스트와 실제 Android native 추론으로 회귀 검증한다.

`two-speaker-telephone.m4a`는 위 공식 WAV의 각 반복에서 앞 11.8초(첫 두 화자)를 추출하고 300–3400Hz 필터, 8kHz mono AAC 24kbps로 변환한 약 367초 반복 자료다. 생성 명령: `ffmpeg -stream_loop 30 -i four-speakers.wav -af "aselect=''lt(mod(t,56.86),11.8)'',asetpts=N/SR/TB,highpass=f=300,lowpass=f=3400" -ar 8000 -ac 1 -c:a aac -b:a 24k two-speaker-telephone.m4a`. 실제 한국어 통화 자료나 독립적인 정확도 벤치마크는 아니다.
