import re
from collections.abc import Iterator
from datetime import date
from zoneinfo import ZoneInfo

from .models import Claim, ExtractionRequest, ExtractionResponse


class ExtractionUnavailable(Exception):
    """The configured extraction engine is temporarily unavailable."""


MODEL_VERSION = "hotel-reply-extractor-0.1"
RELATIVE_DAYS = {"오늘": 0, "금일": 0, "내일": 1, "익일": 1, "모레": 2}
STAY_DAY_INDEX = {
    "첫날": 0,
    "첫째날": 0,
    "둘째날": 1,
    "두번째날": 1,
    "셋째날": 2,
    "세번째날": 2,
}

KOREAN_DATE = r"(?:(?:(?P<year>\d{4})\s*년\s*)?(?P<month>\d{1,2})\s*월\s*)?(?P<day>\d{1,2})\s*일"
KOREAN_DATE_PATTERN = re.compile(KOREAN_DATE)
DATE_TEXT = (
    r"(?:\d{4}-\d{2}-\d{2}|\d{1,2}/\d{1,2}|"
    r"(?:(?:\d{4}\s*년\s*)?\d{1,2}\s*월\s*)?\d{1,2}\s*일|"
    r"오늘|금일|내일|익일|모레|첫날|첫째\s*날|둘째\s*날|두\s*번째\s*날|"
    r"셋째\s*날|세\s*번째\s*날)"
)
DATE_TOKEN = re.compile(DATE_TEXT)
DATE_LIST = rf"{DATE_TEXT}(?:\s*(?:,|및|과|와)\s*{DATE_TEXT})*"

ROOM = r"객실(?:을|이|은|는)?\s*"
PROVISION = r"(?:제공|이용)(?:이|은|는)?\s*"
NOUN_ENDING = r"(?:합니다|해요|하고|하며|하지만|입니다|이고|함|하다)?"
VERB_ENDING = r"(?:습니다|어요|으며|지만|고|음|다)"
AVAILABLE = (
    rf"{ROOM}(?:{PROVISION}가능{NOUN_ENDING}|제공(?:해\s*드릴|할)\s*수\s*있{VERB_ENDING})"
)
UNAVAILABLE = (
    rf"{ROOM}(?:{PROVISION}(?:불가능{NOUN_ENDING}|불가{NOUN_ENDING}|어렵{VERB_ENDING}|"
    rf"안\s*됩니다|되지\s*않{VERB_ENDING})|제공(?:해\s*드릴|할)\s*수\s*없{VERB_ENDING})"
)
UNCERTAIN = (
    r"(?:객실\s*제공\s*여부(?:는|가)?\s*)?(?:아직\s*)?"
    r"(?:확인\s*중(?:입니다|이고|이며)?|미확인(?:입니다|이고|이며)?)"
)
STATEMENT = re.compile(
    rf"(?P<dates>{DATE_LIST})\s*(?:부터는|부터|에는|에|은|는|도)?\s*"
    rf"(?:(?P<available>{AVAILABLE})|(?P<unavailable>{UNAVAILABLE})|(?P<uncertain>{UNCERTAIN}))"
)
SENTENCE = re.compile(r"(?P<text>[^.!?。！？;\r\n]+)(?P<end>[.!?。！？;\r\n]+|$)")
CORRECTION_PREFIX = re.compile(r"정정(?:합니다)?(?:\s*[:：,，]\s*|\s+)")
CONDITION_PREFIX = re.compile(r"(?:단|다만)(?:\s*[,，:：]\s*|\s+)")
CLAUSE_COMMA = re.compile(r"[,，]\s*")
STAY_REQUIREMENT = re.compile(
    r"(?:경우|때)(?:에\s*한(?:합니다|해|하여)|에만|만|\s*가능)"
    r"|(?:취소|객실|공실|빈방|예약|숙박|투숙).*(?:해야|어야|아야|돼야|필요(?:합니다|해요|함|하다|$)|조건입니다)"
)
CONDITION_STAY_DATES = re.compile(rf"(?P<dates>{DATE_LIST})\s*(?:에는|은|는|만|(?=객실|숙박))")
CANCELLATION_DEADLINE = re.compile(r"\s*(?:까지|이전|이내)")
UNNAMED_SUBSET = re.compile(r"(?:일부|특정)\s*(?:숙박일|날짜|일자)")
UNCONFIRMED_REMAINDER = re.compile(rf"(?:나머지|그\s*외)(?:\s*(?:숙박일|날짜))?(?:는|가)?\s*{UNCERTAIN}")


def _split_condition(text: str) -> tuple[str, str]:
    named_dates = CONDITION_STAY_DATES.match(text)
    start = named_dates.end() if named_dates is not None else 0
    for comma in CLAUSE_COMMA.finditer(text, start):
        following = text[comma.end():]
        if STATEMENT.match(CORRECTION_PREFIX.sub("", following, count=1)):
            return text[:comma.start()].rstrip(), following
    return text, ""


def _clauses(message: str) -> Iterator[re.Match[str] | str | None]:
    for sentence in SENTENCE.finditer(message):
        if "?" in sentence["end"] or "？" in sentence["end"]:
            yield None
            continue

        remaining = sentence["text"].strip()
        while remaining:
            remaining = CORRECTION_PREFIX.sub("", remaining, count=1)
            prefix = CONDITION_PREFIX.match(remaining)
            if prefix is not None:
                condition, remaining = _split_condition(remaining[prefix.end():])
                yield condition if STAY_REQUIREMENT.search(condition) else None
                continue

            statement = STATEMENT.match(remaining)
            if statement is None:
                yield None
                break
            tail = remaining[statement.end():].lstrip()
            if not tail:
                yield statement
                break

            # Separate known clauses without treating an arbitrary suffix as a complete claim.
            if not (tail.startswith((",", "，")) or statement[0].endswith(("고", "며", "지만"))):
                yield None
                break
            remaining = tail[1:].lstrip() if tail.startswith((",", "，")) else tail
            if UNCONFIRMED_REMAINDER.fullmatch(remaining):
                yield statement
                yield None
                break
            if not (
                STATEMENT.match(remaining) or CONDITION_PREFIX.match(remaining) or CORRECTION_PREFIX.match(remaining)
            ):
                yield None
                break
            yield statement


def _calendar_parts(text: str) -> tuple[int | None, int | None, int]:
    korean_date = KOREAN_DATE_PATTERN.fullmatch(text)
    if korean_date is not None:
        year, month, day = korean_date.groups()
    elif "/" in text:
        year = None
        month, day = text.split("/")
    else:
        year, month, day = text.split("-")
    return int(year) if year else None, int(month) if month else None, int(day)


def _resolve_dates(text: str, stay_dates: list[date], local_day: date | None) -> Iterator[date]:
    context_year = None
    context_month = None
    for date_text in DATE_TOKEN.findall(text):
        if date_text in RELATIVE_DAYS:
            if local_day is not None:
                target = local_day.toordinal() + RELATIVE_DAYS[date_text]
                yield from (day for day in stay_dates if day.toordinal() == target)
            continue

        ordinal = STAY_DAY_INDEX.get("".join(date_text.split()))
        if ordinal is not None:
            if ordinal < len(stay_dates):
                yield sorted(stay_dates)[ordinal]
            continue

        year, month, day = _calendar_parts(date_text)
        if month is not None:
            # A following bare day inherits the explicit month in the same list.
            context_year, context_month = year, month
        matches = [
            stay_date
            for stay_date in stay_dates
            if stay_date.day == day
            and (context_month is None or stay_date.month == context_month)
            and (context_year is None or stay_date.year == context_year)
        ]
        if len(matches) == 1:
            yield matches[0]


def _condition_dates(
    text: str, previous_claim_dates: tuple[date, ...], stay_dates: list[date], local_day: date | None
) -> Iterator[date]:
    named_dates = CONDITION_STAY_DATES.match(text)
    if named_dates is not None:
        yield from _resolve_dates(named_dates["dates"], stay_dates, local_day)
        return

    # A deadline describes when the condition must be met, not which night it qualifies.
    if not UNNAMED_SUBSET.search(text) and all(
        CANCELLATION_DEADLINE.match(text, reference.end()) for reference in DATE_TOKEN.finditer(text)
    ):
        yield from previous_claim_dates


def _message_local_date(request: ExtractionRequest) -> date | None:
    if request.messageOccurredAt is None:
        return None
    try:
        return request.messageOccurredAt.astimezone(ZoneInfo(request.hotelTimeZone)).date()
    except OverflowError:
        return None


def _warnings(claims: list[Claim], unresolved_dates: list[date]) -> list[str]:
    if not claims:
        return ["원문만으로 제공 가능 여부와 적용 날짜를 특정할 수 없습니다."]
    if not unresolved_dates:
        return []

    same_month = all(
        (day.year, day.month) == (unresolved_dates[0].year, unresolved_dates[0].month)
        for day in unresolved_dates
    )
    labels = [
        f"{day.month}월 {day.day}일" if index == 0 or not same_month else f"{day.day}일"
        for index, day in enumerate(unresolved_dates)
    ]
    date_list = labels[0] if len(labels) == 1 else f"{', '.join(labels[:-1])}과 {labels[-1]}"
    return [f"{date_list}의 객실 제공 가능 여부는 확인되지 않았습니다."]


def extract_claims(request: ExtractionRequest) -> ExtractionResponse:
    claims_by_date: dict[date, Claim] = {}
    uncertain_dates: set[date] = set()
    if request.locale.casefold() == "ko-kr":
        local_day = _message_local_date(request)
        previous_claim_dates: tuple[date, ...] = ()
        for clause in _clauses(request.originalMessage):
            if clause is None:
                previous_claim_dates = ()
                continue
            if isinstance(clause, str):
                uncertain_dates.update(_condition_dates(
                    clause, previous_claim_dates, request.stayDates, local_day,
                ))
                previous_claim_dates = ()
                continue

            dates = tuple(_resolve_dates(clause["dates"], request.stayDates, local_day))
            if clause["uncertain"] is not None:
                uncertain_dates.update(dates)
                previous_claim_dates = ()
                continue

            # Attach undated conditions only to the immediately preceding, fully resolved statement.
            previous_claim_dates = dates if len(dates) == len(DATE_TOKEN.findall(clause["dates"])) else ()
            status = "AVAILABLE" if clause["available"] is not None else "UNAVAILABLE"
            for stay_date in dates:
                previous = claims_by_date.get(stay_date)
                if previous is None:
                    claims_by_date[stay_date] = Claim(
                        stayDate=stay_date,
                        reportedProvisionStatus=status,
                        supportingText=clause[0],
                    )
                elif previous.reportedProvisionStatus != status:
                    uncertain_dates.add(stay_date)

    claims = [
        claims_by_date[day]
        for day in request.stayDates
        if day in claims_by_date and day not in uncertain_dates
    ]
    claimed_dates = {claim.stayDate for claim in claims}
    unresolved_dates = [day for day in request.stayDates if day not in claimed_dates]
    return ExtractionResponse(
        evidenceId=request.evidenceId,
        analysisStatus="EXTRACTED" if claims else "AMBIGUOUS",
        modelVersion=MODEL_VERSION,
        claims=claims,
        unresolvedStayDates=unresolved_dates,
        requiresHumanVerification=True,
        warnings=_warnings(claims, unresolved_dates),
    )
