# PR 개발 자동화 안내

PR을 열거나 커밋을 추가하면 GitHub Actions가 변경 범위의 CI를 실행하고, 기존 Codex GitHub 연동이 코드를 자동 리뷰한다. 개발 담당자는 리뷰 지적을 확인하고 필요한 수정과 검증을 진행한다. 최종 병합에는 명시적 승인이 필요하다.

## PR 공통 안전 검사

모든 PR에서 `tools/ci/check_shared_contracts.py`를 실행한다. 다음은 명확한 사고로 판정해 CI를 실패시킨다.

- 이미 존재하는 Flyway 버전 마이그레이션(`V숫자__*.sql`) 수정·삭제
- Git 추적 대상에 `.env`, `.env.local`, `.env.production` 등 실제 환경파일 추가
- Compose PostgreSQL과 Spring local 설정의 포트·DB명·사용자·비밀번호 환경변수 참조 불일치
- local 프로필의 루트 `.env` import 해제, Hibernate DDL 자동 변경 활성화, DB의 루프백 바인딩 해제

GitHub Actions, 빌드 설정, API 구현 진입점, 환경설정, DB·Flyway, Mock 계약 등 공통 파일과 최상위 경로가 바뀌면 Actions Summary에 **리뷰 필요**로 표시한다. 변경 자체는 실패시키지 않으며 담당자가 계약·실행 절차·팀 영향 범위를 리뷰한다. 이 경고는 GitHub의 리뷰 승인을 자동으로 강제하지 않는다. `main` 보호 규칙과 담당자 리뷰를 별도 적용해야 한다.

서로 일치하는 정상적인 포트 변경은 자동 실패시키지 않는다. 다만 다른 팀원의 실행환경과 문서를 바꿀 수 있으므로 PR에서 명시적으로 검토한다. 환경 변수의 값·실제 비밀값은 로그에 출력하지 않는다. 이 스크립트는 모든 구조·API 변경의 의미를 완전히 판정하는 범용 정적 분석기가 아니다.

## 최소 CI

| 변경 경로 | 검증 |
| --- | --- |
| 백엔드 소스·Gradle 설정 | Java 21 컴파일과 `ciUnitTest` |
| Spring 예약·상담 사건·재확인 코드, Spring 설정, DB·Flyway, Gradle, 통합 테스트 또는 Mock 계약 | `ciPostgresTest`로 API-001과 예약 재확인·증빙 처리 검증 |
| `frontend/travel_bom/**` | 잠금 파일 기준 설치, Next.js 빌드, 별도 TypeScript 검사 |
| `ml/**` | 저장된 모델의 API-023·런타임 핵심 테스트 |
| `llm/agent/**` | 호텔 회신 추출 HTTP 테스트 |
| Mock 구현·의존성·계약·테스트 | 가상 공급사·호텔 계약 테스트. 구현·의존성·OpenAPI 변경은 Spring 연동도 검증 |

일반 백엔드 단위 테스트는 `ciUnitTest`가 자동으로 찾는다. DB나 외부 서비스가 필요한 테스트 클래스는 `*IntegrationTest`, `*IntegrationTests`, `*LiveTest` 이름으로 분류한다. CI의 PostgreSQL 작업에는 격리 DB 설정이 확인된 핵심 통합 테스트를 지정한다.

PostgreSQL은 해당 범위에서만 시작한다. Actions는 `127.0.0.1:25433`에서 재확인용 `yeohaengbom_reconfirmation_test`와 API 계약용 `yeohaengbom_api_contract_test`의 접속과 DB·사용자를 검증한 뒤 Gradle을 실행한다. 두 테스트에 별도 DB를 배정해 롤백 이후에도 전진하는 PostgreSQL 시퀀스의 영향을 막는다. Gradle은 두 주소를 다시 검사하며, 재확인 테스트의 기존 Loader와 API 테스트의 CI 설정이 각 DB를 Spring DataSource와 Flyway에 지정한다.

`CI / ci-gate`는 공통 검사와 실행 대상으로 선택된 작업을 모아 판정한다. 필요할 때 Actions에서 `workflow_dispatch`로 한 범위 또는 `all`을 명시할 수 있다. `main`의 필수 검사로 강제하려면 첫 성공 후 브랜치 규칙에서 `CI / ci-gate`를 지정한다.
