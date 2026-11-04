# DCRefresher-Mobile

디시인사이드 안드로이드 앱의 광고와 추적을 막고 편의 기능을 더하는 Vector(LSPosed 계열)용 [libxposed API](https://github.com/JingMatrix/libxposed-api) 모듈입니다.
대상 앱을 수정하지 않고 런타임에 훅을 겁니다. [GreenVector](https://github.com/green1052/GreenVector)의 디시인사이드 패치를 따로 뗀 저장소입니다.

- 패키지: `com.green1052.dcinside` (런처 아이콘 없음)
- 설정: Vector 관리자의 모듈 설정 화면에서 기능을 켜고 끕니다. 값은 `dcinside` 그룹에 저장됩니다. 설정을 바꾸면 실행 중인 앱의 모든 프로세스가 종료되고, 다음 실행부터 새 설정이 적용됩니다.
- 실패 표시: 적용에 실패한 기능은 앱 실행 시 토스트로 알리고, 오류 내용은 Vector 로그(`DCRefresher` 태그)에 남습니다.
- DexKit 캐시: 난독화된 코드는 DexKit으로 찾고, 결과(실패 포함)를 앱 데이터 폴더의 `dcrefresher_dcinside_dexkit.properties`에 저장합니다. 앱이나 모듈이 바뀌기 전까지는 DexKit을 다시 실행하지 않습니다.

## 대상 앱

| 앱 | 패키지 | 분석 버전 |
|---|---|---|
| 디시인사이드 | `com.dcinside.app.android` | 5.3.6, 5.3.8, 5.3.12, 5.3.20, 5.3.21 |

차단 갤러리와 유동 IP 정보는 [DCRefresher-Reborn](https://github.com/green1052/DCRefresher-Reborn/tree/data) DB에서 받아옵니다.

## 구조

```
app/src/main/kotlin/com/green1052/dcinside/
  MainHook.kt         진입점: 패키지 이름을 확인하고 그 앱의 패치를 적용
  Core.kt             패치 모델, 훅 DSL, 리플렉션 도우미, str()
  Dex.kt              DexKit 탐색과 APK별 캐시
  Hosts.kt            DNS, WebView 호스트 차단 (광고, 추적 서버 차단 패치가 공유)
  SettingsActivity.kt 설정 화면 (Jetpack Compose + Miuix: 분류, 검색)
  SettingsUi.kt       테마
  Ads.kt              광고 차단, 광고·추적 서버 차단, 추적 SDK 비활성화
  Identifier.kt       식별코드·글댓비·차단 갤러리 배지
  Lists.kt            운영자 공지, 페이지 구분 숨기기
  Media.kt            본문 움짤·동영상 제어, 업로드 파일 정리
  RefresherDb.kt      DCRefresher-Reborn DB (IP 정보, 차단 갤러리)
  Search.kt           계속검색 자동 진행, 식별코드·IP 검색
  Patches.kt          patchGroups (기능 분류)
```

패치 기능의 UI 문자열은 코드에 하드코딩되어 있고, 설정 화면 등 앱 자체 문자열만 `app/src/main/res/values/strings.xml`에 있습니다. 대상 앱 안에서는 호스트 Application이 생성된 뒤 `str()`(`Core.kt`)로 읽으므로, 훅 콜백 안에서만 쓸 수 있습니다.

## 빌드

```
./gradlew :app:assembleRelease
```

JDK 17+와 Android SDK(compileSdk 37)가 필요합니다. release 빌드는 디버그 키로 서명되므로, 배포하려면 서명 설정을 추가하세요.

## 실제 APK로 검증

`app/src/androidTest/.../VerifyPatches.kt`는 Xposed 없이 실제 APK에 모든 패치를 켜고 설치합니다. DexKit 탐색과 리플렉션은 실제로 수행하고, 훅은 기록만 합니다. DexKit으로 한 번, 저장된 캐시만으로 한 번 설치해 이후 실행 경로까지 확인합니다.
`cache/dcinside.apk`가 있을 때만 검사합니다. 앱이 업데이트되면 먼저 이것으로 깨진 패치를 찾으세요.

```
./gradlew :app:installDebug :app:installDebugAndroidTest
adb push base.apk /data/local/tmp/dcinside.apk      # XAPK 안의 base APK
adb shell run-as com.green1052.dcinside cp /data/local/tmp/dcinside.apk cache/dcinside.apk
adb shell am instrument -w -e class com.green1052.dcinside.VerifyPatches com.green1052.dcinside.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s DCVerify
```

`DcinsideMediaTest`는 업로드 메타데이터 제거, WebP 변환, 움짤 변환을 검사합니다. 훅이 의도대로 동작하는지는 기기에서 직접 확인해야 합니다.

마지막 검증(패치 탐색 성공): 디시인사이드 5.3.21 16/16.
