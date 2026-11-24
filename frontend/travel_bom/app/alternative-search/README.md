# 대체 숙소 탐색 Python MVP

이 디렉터리는 Next.js 페이지와 같은 경로 아래에 있지만 **별도 Python 프로세스**로 실행한다. `pnpm install`은 Python 의존성을 설치하지 않는다. 현재 검색은 Google Maps와 Airbnb MCP를 사용하므로 모두 더미 데이터로 동작하는 통합 시연은 아니다.

## Windows에서 준비·실행

저장소의 `frontend/travel_bom` 폴더에서 실행한다. Python 3.13, Node.js와 `npx`, Google Maps API 키, 외부 네트워크가 필요하다. API 키는 로컬 환경변수로만 설정한다.

```powershell
py -3.13 -m venv app\alternative-search\.venv
.\app\alternative-search\.venv\Scripts\python.exe -m pip install -r app\alternative-search\requirements.txt
if ($LASTEXITCODE -ne 0) { throw 'Python 의존성 설치 실패' }

# 개인 키를 별도로 설정한 뒤 실행한다. 키를 Git이나 공유 로그에 남기지 않는다.
.\app\alternative-search\.venv\Scripts\python.exe -m uvicorn app.alternative-search.app:app --host 127.0.0.1 --port 8000
```

`uvicorn`은 문자열 모듈 경로를 `importlib`로 읽으므로 현재 `alternative-search` 디렉터리 이름을 그대로 사용한다. 실행 작업 디렉터리는 `frontend/travel_bom`이다. `http://127.0.0.1:8000/health`는 프로세스 상태를 확인하며, 외부 제공자 연결 성공을 뜻하지 않는다. 키가 없더라도 상태 조회는 가능하고 실제 검색은 명시적 오류로 실패한다.

현재 프런트 기본 검색 주소는 `http://127.0.0.1:8000`이다. 기본 CORS 허용 원점은 `http://localhost:3000`이며 기존 토큰 프록시를 사용하는 `127.0.0.1:3100` 상담 시연과 같은 환경이 아니다. 클라우드에서 예약 포트와 충돌하거나 8000을 사용 중이면 `--port 18025` 등 별도 포트를 명시하고 **프런트 빌드 전에** `NEXT_PUBLIC_AI_SEARCH_API_URL=http://127.0.0.1:18025`를 설정한다. 기존 프로세스를 임의 종료하지 않는다.

MCP 클라이언트는 별도 Node 프로세스로 `npx -y @openbnb/mcp-server-airbnb`를 실행한다. 이 npm 서버 버전과 실제 Google/Airbnb 응답의 재현성은 Python requirements의 고정과 별개다. 실행 환경에 따라 첫 실행 때 추가 패키지 다운로드가 필요하다. 실제 외부 검색·설치는 아래 오프라인 검사에서 수행하지 않는다.

## 보존하는 의미

- 요청한 원예약의 체크인·체크아웃과 숙박일수는 그대로 사용한다. 누락·과거·기간 불일치는 오류이며 오늘 이후의 다른 날짜로 자동 변경하지 않는다. 진행 중 숙박의 남은 날짜를 검색하는 별도 계약은 아직 구현하지 않았다.
- 호텔명은 정규화한 동일 이름과 명시적인 지역 힌트로 확인한다. 지역명만 맞거나 다른 지점이면 위치를 확정하지 않는다. 이름 표기가 모호하면 확인 실패가 정상이다.
- 확인할 수 없는 전체 KRW 금액, 주소·숙소 정체성, 인원·전체 기간 가용성은 미확인으로 남긴다. 검색 후보는 확정 견적·예약이 아니다.
- 제공자 오류는 검색 결과 0건과 구분한다. 오류 응답은 현재 MVP의 `detail` 구조를 유지한다. 팀 공통 problem+json·추적 ID 계약으로의 연결은 별도 API 합의 대상이다.
- Python은 업무 DB에 쓰지 않는다. 현재 MVP 검색 결과와 화면 내 후보 선택은 Spring의 견적 저장·제안 발송·고객 선택·예약 확정·입실·사건 종료를 실행하지 않는다.

Spring API-001의 실제 인증 주체 연결은 저장소 기준 후속 작업이다. 임의 `X-Demo-Subject` 헤더로 고객이나 상담사를 사칭하지 않는다. 따라서 공통 인증이 연결되지 않은 일반 Spring에서 예약 조회가 401이면 그 인증 경계를 보존한 결과다. 이 검색 프로세스를 켠 것만으로 기존 실제 사건에서 견적 DB까지 통합되었다고 설명하지 않는다.

## 외부 호출 없는 집중 검사

같은 `frontend/travel_bom` 폴더에서:

```powershell
.\app\alternative-search\.venv\Scripts\python.exe -m unittest discover -s app\alternative-search\tests -p "test_*.py"
```

검사는 가짜 제공자·MCP 응답과 FastAPI TestClient를 사용한다. 실제 Google/Airbnb 호출, 업무 DB 변경, 외부 숙소 예약은 수행하지 않는다. 고정 버전 목록은 직접 의존성 버전이다. 새 Linux Python 3.13 가상환경에서 설치와 22개 검사를 확인했으며, Windows 설치와 실제 외부 검색 성공은 별도 확인이 필요하다.

## 프런트 연결과 미구현 범위

- `/alternative-search/{caseId}/{bookingId}`는 URL의 두 ID를 사용한다. API-001이 돌려준 예약번호·활성 사건이 다르면 검색하지 않는다. 기본 예약을 조용히 대신 검색하지 않는다.
- 원예약 조회 기본 주소는 문서의 Spring 기본값인 `http://localhost:8080`이다. 다른 주소는 `NEXT_PUBLIC_API_BASE_URL`을 빌드 전에 지정한다. 임의 사용자 헤더는 제거했으며 실제 인증 주체는 기존 인증 계층이 제공해야 한다. 이 문서가 인증 구현 완료를 뜻하지 않는다.
- 로딩·조회 실패 중에는 검색을 차단한다. 검색 실패를 화면에 표시하고 이전 후보를 지운다. 결과의 예약·사건·기간·금액 형식이 일치해야 표시한다.
- 미확인 가격은 `금액 미확인`으로 표시한다. 검색 요약의 기간·반경·검토 기준 금액은 실제 응답을 사용한다. 화면에서 선택한 후보는 서버에 저장되거나 전달되지 않는다.
- 기존 요청 DTO는 정수 `nightlyPrice`를 받는다. 프런트가 원총액을 박수로 나누어 반올림하므로 나누어떨어지지 않는 금액은 재구성 총액에 수 원 차이가 생길 수 있다. 정확한 원총액 전달은 별도 DTO 합의 대상이며 현재 비교값을 정산·예약 확정 금액으로 사용하지 않는다.
- 기존 상담 사건 화면의 대체 숙소 탭 연결, 상담사 권한의 예약·원금액 조회, Spring 견적 저장·제안 발송은 후속이다. 별도 검색 페이지를 추가한 것만으로 API-025 및 전체 고객 구제 흐름이 완성된 것은 아니다.

## 선택적 브라우저 회귀 검사

빌드한 프런트를 별도 터미널에서 `127.0.0.1:18081`로 실행한다. Chrome이 설치돼 있거나 `CSRO_BROWSER_EXECUTABLE`에 사용할 브라우저 실행 파일을 지정해야 한다. 현재 작업 디렉터리는 `frontend/travel_bom`이다.

```powershell
pnpm.cmd exec next start --hostname 127.0.0.1 --port 18081
```

검사용 다른 터미널에서:

```powershell
.\app\alternative-search\.venv\Scripts\python.exe -m pip install -r tests\requirements-browser.txt
.\app\alternative-search\.venv\Scripts\python.exe tests\alternative-search-safety.py
```

이 검사는 가상의 예약·검색 HTTP 응답을 브라우저에서 가로챈다. 다른 예약의 ID, 전체 기간, 미확인 가격, 오류 표시와 기존 결과 제거, 사건 불일치 차단을 확인하며 실제 Spring·Google·MCP·DB 통합 검사로 해석하지 않는다. 테스트 후 직접 실행한 Next.js 서버는 해당 터미널에서 종료한다.
