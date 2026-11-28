import asyncio
import os
from hmac import compare_digest
from io import BytesIO
from typing import Annotated

from fastapi import Depends, FastAPI, Header, Response
from fastapi.responses import JSONResponse
from openai import OpenAIError
from pydantic import BaseModel, Field

from .agent import (
    FollowUpSuggestion,
    create_document_agent,
    create_draft_agent,
    generate_document,
    generate_draft,
    validate_follow_up_references,
)
from .docx_export import export_docx

DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)


class AgentProblem(Exception):
    def __init__(self, status: int, code: str):
        self.status = status
        self.code = code


@app.exception_handler(AgentProblem)
async def problem(_request, exc: AgentProblem):
    return JSONResponse(
        status_code=exc.status,
        media_type="application/problem+json",
        content={"status": exc.status, "code": exc.code},
    )


def authorize(authorization: Annotated[str | None, Header()] = None):
    expected = os.environ.get("POSTPROCESSING_SERVICE_TOKEN", "").strip()
    if not expected:
        raise AgentProblem(503, "POSTPROCESSING_NOT_CONFIGURED")
    if not compare_digest((authorization or "").encode(), f"Bearer {expected}".encode()):
        raise AgentProblem(401, "UNAUTHENTICATED_SERVICE")


def model_name() -> str:
    return os.environ.get("OPENAI_MODEL", "").strip() or "gpt-4.1-mini"


class DocumentRequest(BaseModel):
    case_input: dict
    reviewed_text: str = Field(min_length=1, max_length=30000)
    selected_follow_ups: list[FollowUpSuggestion] = Field(max_length=20)


async def run_agent(operation):
    try:
        return await asyncio.wait_for(operation, timeout=60)
    except (OpenAIError, TimeoutError) as exc:
        raise AgentProblem(503, "POSTPROCESSING_AGENT_UNAVAILABLE") from exc
    except (KeyError, ValueError) as exc:
        raise AgentProblem(422, "INVALID_POSTPROCESSING_DATA") from exc


@app.post("/internal/v1/case-postprocessing/drafts", dependencies=[Depends(authorize)])
async def draft(case_input: dict):
    return await run_agent(generate_draft(create_draft_agent(model_name()), case_input))


@app.post("/internal/v1/case-postprocessing/documents", dependencies=[Depends(authorize)])
async def document(request: DocumentRequest):
    try:
        validate_follow_up_references(request.selected_follow_ups, request.case_input)
    except (KeyError, ValueError) as exc:
        raise AgentProblem(422, "INVALID_POSTPROCESSING_DATA") from exc
    report = await run_agent(generate_document(
        create_document_agent(model_name()), request.reviewed_text, request.selected_follow_ups
    ))
    buffer = BytesIO()
    export_docx(report, request.case_input, buffer)
    return Response(buffer.getvalue(), media_type=DOCX_TYPE)
