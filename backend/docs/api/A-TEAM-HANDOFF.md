# A팀 구현 현황과 B팀 인계

2026-11-26 기준이다. API-002까지 반영된 `main/6b3d386`을 기준으로, `1126cy-api024`는 기존 API-024 Spring 연결 4개 커밋만 다시 올린 A팀 작업 브랜치다. 11월 25일 API-024 구현 완료 시점(`db3b15f`)과 코드가 같다. 이 문서는 팀 전체 API나 서비스의 완료 선언이 아니다.

## 구현된 범위와 남은 일

| 범위 | 현재 구현 | 남은 범위 |
| --- | --- | --- |
| API-001 예약 상세 | `main`에 DB 조회·소유권/역할 판단·고객 DTO·오류 처리 코드 | 공통 Bearer 인증 연결, 최종 예약 ID 형식 검증, 일반 고객 예약 조회 화면. API-002의 격리 고객 신고 화면과 구분 |
| API-002 고객 신고 | `main`에 신고·첫 메시지·활성 사건·필수 업무·재확인 대기 원자 저장, 멱등 재전송, 정형 재확인 Worker와 별도 고객 시연 | 일반 로그인·운영 고객 화면·운영 재확인 실행 환경 |
| API-023 위험 점검 | `main`에 합성 관측 집계→ML 호출→평가/선별 Job 저장→재확인 및 복구 경로 | 실제 운영 관측 수집, 운영 임계값 승인·상시 실행·배포. 합성 모델 점수를 실제 호텔 장애 확률로 해석하지 않음 |
| API-026/027 재확인 | `main`에 Mock HTTP와 Spring 응답 검증·근거/숙박일/사건 반영 | 실제 공급사·호텔 연동. 202·404·장애를 객실 제공 불가로 바꾸지 않음 |
| API-024 자연어 분석 | 이번 A팀 브랜치에 저장 호텔 원문 선택→기존 B팀 HTTP 호출→응답 검증→분석 저장·필수 수동 확인 | 원문 자동 수집, 분석 큐, 상담 화면의 분석 표시, 사람의 검토 결과를 반영하는 업무 연결 |

상세 범위는 [API-001](API-001.md), [API-002](API-002.md), [ML 공통 경로](ML-RISK-COMMON-PATH.md), [API-024 Spring 연결](API-024-SPRING.md)을 따른다. 기존 `main`의 B팀 코드도 유지한다. 대체 숙소 탐색·후처리 자연어 Agent는 호텔 원문의 주장 추출인 API-024와 다른 기능이며 이번 차이에 포함하지 않는다.

## B팀이 연결할 API-024 경계

**기존 HTTP 계약은 변경하지 않았다.** B팀은 [기존 서버](../../../llm/agent/README.md)의 `POST /internal/v1/hotel-reply-extractions`를 제공하고 A팀 Spring이 호출한다. 기본 주소는 `http://127.0.0.1:8000`이다. Spring의 `HOTEL_ANALYSIS_AGENT_BASE_URL`과 `HOTEL_ANALYSIS_AGENT_SERVICE_TOKEN`을 설정하며, 토큰 값은 B팀 서버의 `AGENT_SERVICE_TOKEN`과 같아야 한다. 토큰을 브라우저나 Git에 전달하지 않는다.

- 요청 8필드: `evidenceId`, `bookingId`, `hotelId`, `stayDates`, `originalMessage`, `locale`, `messageOccurredAt`, `hotelTimeZone`.
- 응답 7필드: `evidenceId`, `analysisStatus`, `modelVersion`, `claims`, `unresolvedStayDates`, `requiresHumanVerification`, `warnings`. `claims`의 날짜·제공 여부·인용과 미확인 날짜를 Spring이 검증한다.
- 입력은 이미 저장된 `HOTEL_API`/`HOTEL_EVENT`/`MANUAL_HOTEL`의 자연어 `raw_text` 한 건이며 `raw_payload IS NULL`이어야 한다. 고객 신고·채팅·공급사 JSON과 API-027 전체 JSON은 분석 입력으로 사용하지 않는다. `occurred_at`이 없으면 null을 그대로 보낸다.
- 내부 진입점은 `HotelReplyAnalysisService.analyze(evidenceId)`다. 현재는 검증된 `local-chat-demo`에서 `--hotel.analysis.demo.evidence-id=EV-실제번호`로 선택한 한 건을 프로세스 시작 후 한 번 실행한다. 자동 수집·큐·새 공개 HTTP API는 없다. 재실행 시 같은 입력·모델·결과의 분석과 대기 확인 업무를 재사용한다.
- `EXTRACTED`와 `AMBIGUOUS` 모두 사람의 확인이 필요한 분석이다. 활성 사건에는 필수 `VERIFY_HOTEL` TODO/IN_PROGRESS를 유지한다. Agent 실패는 성공 분석으로 저장하지 않고 수동 확인 경로를 남긴다. 활성 사건이 없으면 사건을 새로 만들지 않고 별도 수동 인계 필요를 반환한다.
- Python은 업무 DB를 직접 수정하지 않는다. Spring이 기존 `agent_analyses`에 실제 입력·응답과 모델 버전을 저장한다. 분석만으로 출처 인증·숙박일 사실·공급사 예약·사건 상태·담당자를 바꾸지 않는다. HTTP 호출 중 DB 행 잠금을 유지하지 않는다.

원문 수집 채널·분석 큐·상담 화면 표시는 아직 연결되지 않았다. 후속 구현에서도 원문, Agent 주장, 검증된 사실을 구분하고 Spring의 업무 DB 소유권을 유지한다. C-002 시드 원문은 현재 B팀 서버에서 `AMBIGUOUS`가 정상이며, 결과를 맞추려고 원문이나 기존 검증 사실을 고치지 않는다.

## 검증과 인계 상태

11월 25일 Linux에서 API-024 단위 74개와 전용 PostgreSQL 통합 19개가 통과했다. 실제 B팀 서버와 Spring JAR로 C-002 원문 분석 저장, 같은 입력의 재시작 후 `REUSED`, 서버 중단 시 수동 확인 복구를 확인했다. 오늘은 해당 검증 소스와 코드 동일성을 확인했으며 테스트를 반복 실행하지 않았다.

이번 A팀 브랜치의 Windows 재현, 원격 CI, 전체 프로젝트 통합 검증은 미완료다. 위 기록은 API-024 분석 연결 범위이며 일반 인증·자동 원문 수집·화면 표시·실호텔 성능이나 탐색/후처리 Agent의 완료를 뜻하지 않는다.
