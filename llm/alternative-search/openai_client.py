import asyncio
import json
import logging
import os
from time import monotonic
from typing import TypeVar

from openai import AsyncOpenAI, OpenAIError, RateLimitError
from pydantic import BaseModel, ValidationError

logger = logging.getLogger(__name__)
Result = TypeVar("Result", bound=BaseModel)


class StructuredOutputClient:
    def __init__(self, client: AsyncOpenAI | None = None) -> None:
        self.client = client
        self._slots = asyncio.Semaphore(2)
        self._retry_at = 0.0

    async def parse(
            self, schema: type[Result], instructions: str, data: dict
    ) -> Result | None:
        if self.client is None:
            api_key = os.getenv("OPENAI_API_KEY", "").strip()
            if not api_key:
                return None
            self.client = AsyncOpenAI(api_key=api_key, timeout=15, max_retries=0)

        async with self._slots:
            if monotonic() < self._retry_at:
                return None
            try:
                response = await self.client.responses.parse(
                    model=os.getenv("OPENAI_MODEL", "gpt-4.1-mini"),
                    instructions=instructions,
                    input=json.dumps(data, ensure_ascii=False),
                    text_format=schema,
                    store=False,
                )
                return response.output_parsed
            except RateLimitError as exc:
                now = monotonic()
                error_code = exc.code or exc.type or "unknown"
                default_delay = (
                    300 if error_code in {"insufficient_quota", "credit_balance_exhausted"} else 60
                )
                try:
                    retry_after = float(exc.response.headers.get("retry-after", "0"))
                except ValueError:
                    retry_after = 0
                if now >= self._retry_at:
                    logger.warning(
                        "OpenAI HTTP 429 (code=%s, request_id=%s). 대기 중에는 기본 검토 결과를 사용합니다.",
                        error_code, exc.request_id,
                    )
                self._retry_at = max(self._retry_at, now + max(default_delay, retry_after))
                return None
            except (OpenAIError, ValidationError) as exc:
                logger.warning("OpenAI %s 생성 실패 (%s)", schema.__name__, type(exc).__name__)
                return None
