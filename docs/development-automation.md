# PR 개발 자동화 안내

PR을 열거나 커밋을 추가하면 GitHub Actions가 변경 범위의 CI를 실행하고, 기존 Codex GitHub 연동이 코드를 자동 리뷰한다. 개발 담당자는 리뷰 지적을 확인하고 필요한 수정과 검증을 진행한다. 최종 병합에는 명시적 승인이 필요하다.

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

PostgreSQL은 해당 범위에서만 시작한다. Actions가 `127.0.0.1:25433/yeohaengbom_reconfirmation_test` 접속과 DB·사용자를 확인한 후 Gradle을 실행한다. Gradle은 유효 범위를 다시 확인하고 Spring DataSource와 Flyway에 동일한 격리 주소를 전달한다. 재확인 통합 테스트는 기존 Loader의 시작 전 검증도 수행한다.

`CI / ci-gate`는 실행 대상으로 선택된 작업을 모아 판정한다. 필요할 때 Actions에서 `workflow_dispatch`로 한 범위 또는 `all`을 명시할 수 있다. `main`의 필수 검사로 강제하려면 첫 성공 후 브랜치 규칙에서 `CI / ci-gate`를 지정한다.
