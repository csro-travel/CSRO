# 사건 후처리 Agent

OpenAI Agents SDK로 사건 초안을 생성하고, 상담사가 검토한 본문과 업무 목록을
최종 문서로 구성한다. 사례 정보는 사건별 JSON으로 받고, DOCX의 금액·거리·후보·
예약 및 입실 상태 표에는 입력 데이터를 사용한다. 차액은 같은 숙박 범위의 원예약
금액 `comparable_original_krw`이 있을 때 계산한다.

저장소 루트에서 실행한다. `OPENAI_API_KEY`는 실행 환경에서 주입하며
`OPENAI_MODEL`을 생략하면 `gpt-4.1-mini`를 사용한다.

```powershell
.\.venv\Scripts\python.exe -m pip install -r llm/postprocessing/requirements.txt
.\.venv\Scripts\python.exe -X utf8 -m llm.postprocessing
.\.venv\Scripts\python.exe -X utf8 -m llm.postprocessing --reviewed llm/postprocessing/example_review.json --docx postprocessing-report.docx
```

- 기본 실행은 합성 사건 `example_case.json`을 사용하며 `--case`로 다른 입력을 지정할 수 있다.
- 사건 자료에는 고객에게 실제 전달한 후보를 `offered_stays`에 담고, 선택·예약·입실은
  `replacement`에 담는다. 승인된 규정 MD에서 해당 조항을 발췌해 `policies`에
  문서 ID·버전·조항과 함께 공급한다. 예제 정책은 프로젝트 테스트용이다.
- 첫 호출은 `draft_text`와 근거가 연결된 `follow_up_suggestions`를 반환한다.
  상담사 확인본은 같은 `case_id`, `reviewed_text`, `selected_follow_ups`로 제출한다.
  두 번째 호출에는 상담사 검토 본문만 전달한다. Agent가 정한 제목·섹션에
  검토 본문을 원래 순서로 넣고, 선택한 업무를 문서에 그대로 옮긴다.
- Spring 연동은 아래 내부 서버를 사용한다. Spring과 Python에 동일한
  `POSTPROCESSING_SERVICE_TOKEN`을 환경 변수로 설정한다. Spring의
  `POSTPROCESSING_AGENT_URL` 기본값은 `http://127.0.0.1:8002`이다.

```powershell
.\.venv\Scripts\python.exe -m uvicorn llm.postprocessing.api:app --host 127.0.0.1 --port 8002
```

로컬 상담 시연 프록시(3100)에서 `/cases?case=C-001`처럼 사건을 열고
후속 업무 카드에서 초안을 생성한다. 상담사가 선택한 신규 업무만
Spring이 문서와 같은 트랜잭션에서 `case_tasks`에 기록한다.
프로젝트 테스트용 정책으로 검증하며 실제 고객 자료 연동 전에는 별도 보안·정책 검토가 필요하다.

발송 당시 전체 숙박 비교 금액이 저장된 제안에서만 실제 문서의 차액을 계산한다.
Airbnb 시연 데이터의 가상 선택은 실제 고객 동의와 구분한다. 입실 확인 업무는
기존 자동 완료 방식에 맞춰 필수로 등록하거나 같은 미완료 업무를 재사용한다.
