import argparse
import asyncio
import os
import json
from pathlib import Path

from openai import OpenAIError

from .agent import (
    FollowUpSuggestion,
    create_document_agent,
    create_draft_agent,
    generate_document,
    generate_draft,
    validate_follow_up_references,
)


EXAMPLE_CASE = Path(__file__).with_name("example_case.json")


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", type=Path, default=EXAMPLE_CASE)
    parser.add_argument("--reviewed", type=Path, help="상담사가 검토한 본문과 선택 업무 JSON")
    parser.add_argument("--docx", type=Path, help="최종 DOCX 출력 경로")
    options = parser.parse_args()
    if options.docx and not options.reviewed:
        parser.error("--docx에는 --reviewed가 필요합니다.")
    case_input = json.loads(options.case.read_text(encoding="utf-8-sig"))
    if not os.environ.get("OPENAI_API_KEY", "").strip():
        raise SystemExit("OPENAI_API_KEY가 현재 Python 프로세스에 설정되어 있지 않습니다.")
    model = os.environ.get("OPENAI_MODEL", "").strip() or "gpt-4.1-mini"
    try:
        if options.reviewed:
            review = json.loads(options.reviewed.read_text(encoding="utf-8-sig"))
            selected = [
                FollowUpSuggestion.model_validate(task) for task in review["selected_follow_ups"]
            ]
            if review["case_id"] != case_input["case_id"]:
                parser.error("상담사 수정본과 사건 입력의 사건 ID가 다릅니다.")
            validate_follow_up_references(selected, case_input)
            document = await asyncio.wait_for(
                generate_document(create_document_agent(model), review["reviewed_text"], selected),
                timeout=60,
            )
            if options.docx:
                from .docx_export import export_docx

                export_docx(document, case_input, options.docx)
            print(document.model_dump_json(indent=2))
            return
        draft = await asyncio.wait_for(
            generate_draft(create_draft_agent(model), case_input), timeout=60
        )
    except TimeoutError:
        raise SystemExit("OpenAI 호출이 60초 안에 완료되지 않았습니다.") from None
    except OpenAIError as exc:
        raise SystemExit(f"OpenAI 호출 실패 ({type(exc).__name__}).") from None
    except ValueError as exc:
        raise SystemExit(str(exc)) from None
    print(draft.model_dump_json(indent=2))


if __name__ == "__main__":
    asyncio.run(main())
