# PR 개발 자동화 안내

## 현재 흐름

1. 개발 담당자가 기능 단위로 구현·검증하고 PR을 생성한다.
2. GitHub Actions CI가 변경된 영역의 핵심 테스트를 실행한다.
3. 기존 GitHub Codex 연동이 PR 코드를 자동 리뷰한다.
4. PR에 `codex-autofix` 라벨을 **리뷰 전에** 붙여 놓았고 실제 미해결 Codex 인라인 지적이 있다면, Action이 해당 지적을 한 차례 수정하고 기존 브랜치에 push한다.
5. 자동 수정 뒤에는 변경 범위 CI를 명시적으로 재실행한다. 실패나 추가 지적은 담당자가 확인한다.
6. 최종 병합은 범상의 명시적 승인으로만 진행한다. 병합 후 원격 브랜치 자동 삭제 설정을 사용하고, 로컬 worktree는 미커밋 내용을 확인해 안전하게 정리한다.

자동 수정은 OpenAI API 키가 있어야 작동하며 사용량에 따라 별도 API 비용이 발생한다. GitHub Codex 자동 리뷰와 코드 자동 수정은 서로 다른 기능이다. 자동 수정은 무한 반복하지 않는다.

## 사용자가 최초 한 번 확인할 설정

- [Actions Secrets](https://github.com/csro-travel/CSRO/settings/secrets/actions)에 `OPENAI_API_KEY` repository secret을 추가한다. **키를 저장소·PR·채팅·로그에 남기지 않는다.**
- [Labels](https://github.com/csro-travel/CSRO/labels)에 `codex-autofix` 라벨을 추가한다. 원하는 **동일 저장소의 PR에만** 라벨을 붙인다. 리뷰가 이미 끝난 뒤 붙인 경우에는 `@codex review`로 다시 요청할 수 있다.
- [Branch rules](https://github.com/csro-travel/CSRO/settings/rules)에서 `main`의 필수 검사로 `CI / ci-gate`를 선택한다. **최초 PR에서 해당 검사 성공을 확인한 뒤** 설정한다.
- 조직의 Actions 정책에서 `GITHUB_TOKEN`이 해당 작업에 필요한 `contents:write`, `issues:write`, `actions:write`를 허용하는지 확인한다. 막혀 있으면 관리자가 정책을 조정해야 한다. PAT를 코드에 넣지 않는다.

## 최소 CI

| 변경 경로 | 검증 |
| --- | --- |
| `backend/**` | Java 21, Mock API 026/027, PostgreSQL 18.7 **전용 격리 DB**, 예약 재확인·Evidence 핵심 테스트 |
| `frontend/travel_bom/**` | pnpm 잠금 의존성, TypeScript 검사, Next.js 빌드 |
| `ml/**` | 저장된 모델의 API-023/runtime 핵심 테스트. 모델 재학습·데이터 재생성 없음 |
| `llm/agent/**` | 호텔 회신 추출 HTTP 테스트 |
| `mock-server/**` | 가상 공급사·호텔 계약 테스트 |

`CI / ci-gate`는 변경 영역의 필수 작업 중 하나라도 실패하면 실패한다. **CI 스크립트 자체가 바뀌면** 백엔드와 프론트 핵심 검사도 실행한다.

Spring 쓰기 테스트는 `127.0.0.1:25433/yeohaengbom_reconfirmation_test` 전용 격리 DB만 사용하며, 기존 테스트 Loader가 Spring/Flyway 시작 전 대상 URL과 인증 구성을 확인한다. 외부 개인 DB나 공유 개발 DB에 쓰지 않는다.

단, CI 통과는 Chrome 브라우저 E2E, 운영 인증, 실제 공급사 연동을 검증한 것과 다르다. 해당 작업에서 꼭 필요한 브라우저 E2E는 기능 개발 PR에서 별도 검증한다.

## 자동 수정 안전 경계

- `pull_request_review`의 Codex 봇 인라인 리뷰만 처리한다.
- **PR이 열린 상태**, **같은 저장소 브랜치**, **`codex-autofix` 라벨 존재**, **미해결·최신 인라인 리뷰**인 경우만 실행한다.
- Codex 모델에는 GitHub 쓰기 권한을 주지 않고, 별도 job에서 허용된 소스/테스트 경로에 한해 패치를 적용한다.
- `.github/`, `AGENTS.md`, Flyway, 모델, 의존성, 공개 계약 파일, 비밀 정보는 수정하지 않는다.
- PR HEAD가 그 사이 바뀌면 적용하지 않는다.
- 자동 패치 적용 직전에 라벨을 제거해 같은 PR의 자동 수정 루프를 방지한다.
- `GITHUB_TOKEN`으로 push한 커밋은 GitHub의 일반 PR 워크플로를 자동 시작하지 않을 수 있으므로, `workflow_dispatch`로 현재 브랜치 CI를 재실행한다.
- 사람이 확인할 전제인 리뷰 스레드 해결과 main 병합은 자동 수행하지 않는다. 실패 시 무한 재시도하지 않는다.

**반영과 검증 구분:** 파일을 GitHub에 올렸다는 것만으로 자동 수정 성공을 의미하지 않는다. 최초 CI와 실제 Codex 인라인 리뷰 사례를 대상으로 한 번 사용해야 동작까지 검증할 수 있다.

## 새 기능 PR에 사용할 간단한 지시

> 확정 계약을 사용해 한 개 E2E 사용자 흐름을 완성하고 변경 영향 범위의 핵심 테스트만 실행해라. 기능 PR을 생성하고, Codex 자동 수정을 사용하기로 승인된 경우 PR 생성 시 `codex-autofix` 라벨을 붙여라. 리뷰·CI 이후 BLOCKER·MAJOR가 없는지 확인해 사용자에게 최종 병합 승인을 요청한다. 허가받은 병합 후 원격 브랜치가 자동 삭제됐는지 확인하고 깨끗한 로컬 worktree만 정리한다. 테스트 원시 로그는 저장소에 남기지 않는다.
