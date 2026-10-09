# 성경 퀴즈 (안드로이드 앱)

개역개정 성경 기준 퀴즈 HTML을 그대로 담은 안드로이드 앱이에요. 인터넷 없이 동작하고, 본문 읽기 문제의 성경 구절 링크만 휴대폰 브라우저로 열려요.

## 설치 파일 받기

`main`에 올라갈 때마다 GitHub Actions가 APK를 만들어 [Releases](../../releases)에 올려요. 휴대폰에서 가장 최근 릴리스의 `bible-quiz-…apk`를 눌러 받아 설치하면 돼요. 처음 설치할 때는 '출처를 알 수 없는 앱' 설치를 한 번 허용해야 해요.

## 구성

- `app/src/main/assets/index.html`: 퀴즈 본체 (글꼴은 `assets/fonts`에 포함, SIL Open Font License)
- `app/src/main/java/.../MainActivity.java`: 화면 전체 웹뷰, 뒤로 가기 두 번에 종료
- `keystore/`: 서명 키. 같은 키로 서명해야 업데이트 설치 때 기존 점수가 유지돼요.
