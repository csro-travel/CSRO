#!/usr/bin/env python3
"""Small PR guard for shared local configuration and immutable migrations."""

import os
import re
import subprocess
import sys
from pathlib import Path

MIGRATIONS = "backend/src/main/resources/db/migration"
LOCAL_CONFIG = Path("backend/src/main/resources/application-local.properties")


def git(*args):
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout.strip()


def properties(path):
    result = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        if "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def check_shared_config():
    errors = []
    try:
        config = properties(LOCAL_CONFIG)
        compose = Path("compose.yaml").read_text(encoding="utf-8")
        example = properties(Path(".env.example"))
    except (OSError, UnicodeError) as exc:
        return [f"공통 개발 설정 파일을 읽을 수 없음: {exc.filename}"]

    def require(condition, message):
        if not condition:
            errors.append(message)

    require("file:../.env[.properties]" in config.get("spring.config.import", "").split(","),
            "Spring local 프로필의 루트 .env 로드 설정이 변경됨")
    require(config.get("spring.jpa.hibernate.ddl-auto") == "none",
            "Spring local 프로필이 Hibernate DDL 자동 변경을 허용함")

    # Match the existing local-development contract, not an arbitrary fixed host port.
    url = re.fullmatch(r"jdbc:postgresql://(?:127\.0\.0\.1|localhost):(\d+)/([a-zA-Z0-9_]+)",
                       config.get("spring.datasource.url", ""))
    service = re.search(r"(?ms)^  postgres:\s*\n(.*?)(?=^  [\w.-]+:\s*$|\Z)", compose)
    body = service.group(1) if service else ""
    port = re.search(r'''(?m)^\s*-\s*["']?127\.0\.0\.1:(\d+):(\d+)["']?\s*$''', body)
    db = re.search(r"(?m)^\s+POSTGRES_DB:\s*([\w-]+)\s*$", body)
    user = re.search(r"(?m)^\s+POSTGRES_USER:\s*([\w-]+)\s*$", body)
    password = re.search(r"(?m)^\s+POSTGRES_PASSWORD:\s*[\"']?(\$\{[A-Z][A-Z0-9_]*:\?[^}]+\})[\"']?\s*$", body)
    spring_password = re.fullmatch(r"\$\{([A-Z][A-Z0-9_]*)\}",
                                   config.get("spring.datasource.password", ""))

    require(bool(url), "Spring local JDBC 주소 형식 또는 루프백 설정이 변경됨")
    require(bool(port), "Compose postgres 포트의 127.0.0.1 바인딩/형식이 변경됨")
    require(bool(db), "Compose postgres DB 이름을 확인할 수 없음")
    require(bool(user), "Compose postgres 사용자 이름을 확인할 수 없음")
    require(bool(password), "Compose postgres의 필수 비밀번호 환경변수 참조가 없음")
    require(bool(spring_password), "Spring local DB 비밀번호의 필수 환경변수 참조가 없음")
    if url and port:
        require(url.group(1) == port.group(1) and port.group(2) == "5432",
                "Spring local JDBC 포트와 Compose postgres 공개 포트가 불일치함")
    if url and db:
        require(url.group(2) == db.group(1), "Spring local JDBC DB 이름과 Compose POSTGRES_DB가 다름")
    if user:
        require(config.get("spring.datasource.username") == user.group(1),
                "Spring local DB 사용자와 Compose POSTGRES_USER가 다름")
    if password and spring_password:
        name = re.match(r"\$\{([A-Z][A-Z0-9_]*)", password.group(1)).group(1)
        require(name == spring_password.group(1),
                "Compose와 Spring local의 DB 비밀번호 환경변수 이름이 다름")
        require(name in example, ".env.example에 공통 DB 비밀번호 환경변수가 누락됨")
    return errors


def review_paths(changed):
    patterns = (
        r"^\.github/", r"^tools/ci/", r"^\.git(ignore|attributes)$", r"^AGENTS(\.override)?\.md$",
        r"^docs/(api|db|business|local-development)\.md$",
        r"^compose\.ya?ml$", r"^\.env\.example$", r"^backend/(build\.gradle|settings\.gradle|gradlew(\.bat)?)$",
        r"^backend/src/main/resources/application[^/]*\.(properties|ya?ml)$",
        r"^backend/src/main/resources/db/", r"^backend/gradle/wrapper/",
        r"^backend/src/main/java/.*/(?:\w+Controller|\w+Application)\.java$",
        r"^frontend/travel_bom/(?:next\.config\.[^/]+|package\.json|pnpm-lock\.yaml|tsconfig\.json)$",
        r"^mock-server/(?:app/|docs/openapi\.json$|requirements\.txt$)",
        r"^ml/requirements[^/]*\.txt$", r"^llm/agent/requirements[^/]*\.txt$",
    )
    return sorted(path for path in changed if any(re.search(pattern, path) for pattern in patterns))


def main():
    if len(sys.argv) != 3:
        sys.exit("usage: check_shared_contracts.py BASE_REF PR_HEAD_REF")
    base, pr_head = sys.argv[1:]
    errors = []
    changed = set()
    for line in git("diff", "--name-status", "-M", f"{base}...{pr_head}").splitlines():
        fields = line.split("\t")
        changed.update(fields[1:])  # For renames, cover both old and new paths.

    tracked = git("ls-files", "-z").split("\0")
    for path in tracked:
        name = Path(path).name
        if (name == ".env" or name.startswith(".env.")) and not name.endswith((".example", ".sample")):
            errors.append(f"Git에서 추적 중인 비공개 환경파일: {path}")

    for path in git("ls-tree", "-r", "--name-only", base, "--", MIGRATIONS).splitlines():
        if not re.fullmatch(r"V\d+__[^/]+\.sql", Path(path).name):
            continue
        original = git("rev-parse", f"{base}:{path}")
        now = subprocess.run(["git", "rev-parse", "--verify", f"HEAD:{path}"],
                             capture_output=True, text=True)
        if now.returncode or now.stdout.strip() != original:
            errors.append(f"기존 Flyway 버전 마이그레이션 수정/삭제 금지: {path}")

    errors.extend(check_shared_config())

    watch = review_paths(changed)
    base_roots = set(git("ls-tree", "--name-only", base).splitlines())
    current_roots = set(git("ls-tree", "--name-only", "HEAD").splitlines())
    root_changes = sorted(base_roots ^ current_roots)
    if root_changes:
        print("::warning title=최상위 구조 변경::추가/삭제된 최상위 경로: " + ", ".join(root_changes))
    if watch:
        print("::warning title=공통 변경 리뷰 필요::다음 공통 파일의 의도와 영향 범위를 PR에서 확인하세요")
        for path in watch:
            print(f"  REVIEW: {path}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("### 공통 변경 보호\n")
            if root_changes:
                out.write("최상위 경로 추가/삭제: " + ", ".join(f"\`{p}\`" for p in root_changes) + "\n")
            if watch:
                out.write("리뷰 필요 변경 (변경 자체를 오류로 처리하지 않음):\n")
                out.writelines(f"- \`{path}\`\n" for path in watch)
            else:
                out.write("리뷰 대상 공통 파일 변경 없음.\n")
            out.write(f"\n차단 규칙 결과: {'실패' if errors else '통과'}\n")

    for message in errors:
        print(f"::error::{message}")
    if errors:
        sys.exit(1)
    print("Shared configuration, tracked env files and existing Flyway migrations: OK")


if __name__ == "__main__":
    main()
