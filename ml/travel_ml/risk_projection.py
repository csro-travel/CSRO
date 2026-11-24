"""Offline synthetic observations -> the unchanged API-023 request.

This local projection file is an auditable handoff to Spring, not a new HTTP
schema. Only observed public events feed the existing feature builder. Final
booking tables, simulator truth and latent hotel fields are never model inputs.
No database, network, simulator, training or model-loading operation runs here.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import date, datetime
import json
import math
from pathlib import Path
import re
import sys
from typing import Any

from . import features as feature_builder
from .common import FEATURE_NAMES, json_hash, load_config, sha256
from .risk_runtime import RiskRequestError, validate_request

FORMAT = "risk-event-projection-1"
SOURCE_KIND = "SYNTHETIC_OBSERVATIONS"
_PUBLIC_TYPES = frozenset(("BOOKING_STATUS", "RECONFIRMATION_REQUEST", "HOTEL_REPLY",
                           "INVENTORY_PROBE", "OBSERVED_OUTCOME"))
_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}\Z")
_AWARE_ISO = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}"
    r"(?::[0-9]{2}(?:\.[0-9]{1,6})?)?"
    r"(?:Z|[+-](?:[01][0-9]|2[0-3]):[0-5][0-9])\Z"
)


class ProjectionError(ValueError):
    """Value-free diagnostics: source identifiers and payloads are never echoed."""


def _identifier(value: Any) -> str:
    if (type(value) is not str or not value or value.strip() != value
            or any(ord(char) < 32 or ord(char) == 127 for char in value)):
        raise ProjectionError("Source identifiers must be nonempty strings without control characters.")
    return value


def _day(value: Any) -> date:
    try:
        if type(value) is not str or not _DATE.fullmatch(value):
            raise ValueError
        return date.fromisoformat(value)
    except ValueError:
        raise ProjectionError("Source and target dates must use valid YYYY-MM-DD values.") from None


def _instant(value: Any) -> datetime:
    try:
        if type(value) is not str or not _AWARE_ISO.fullmatch(value):
            raise ValueError
        return datetime.fromisoformat(value)
    except (ValueError, OverflowError):
        raise ProjectionError("Observation timestamps must use timezone-aware ISO values.") from None


def _json_value(value: Any) -> None:
    """Reject Python coercions too; json.dumps alone accepts non-string keys."""
    if value is None or type(value) in (str, bool, int):
        return
    if type(value) is float and math.isfinite(value):
        return
    if type(value) is list:
        for item in value:
            _json_value(item)
        return
    if type(value) is dict and all(type(key) is str for key in value):
        for item in value.values():
            _json_value(item)
        return
    raise ProjectionError("The source and configuration must contain finite JSON values.")


def _payload(event: dict) -> None:
    """Check normalized observation types; feature formulas stay in features.py."""
    kind, payload = event["event_type"], event["payload"]
    booking = event.get("booking_id")
    if kind in {"BOOKING_STATUS", "RECONFIRMATION_REQUEST"}:
        _identifier(booking)
    if kind == "BOOKING_STATUS":
        if (payload.get("status") not in {"CONFIRMED", "CANCELLED"}
                or payload.get("channel") not in {"PLATFORM", "OTHER"}):
            raise ProjectionError("Booking observations require a supported status and channel.")
        if _day(payload.get("check_out_date")) <= _day(payload.get("check_in_date")):
            raise ProjectionError("Observation checkout dates must follow check-in dates.")
        for name in ("adults", "rooms"):
            if name in payload and (type(payload[name]) is not int or payload[name] < 1):
                raise ProjectionError("Booking observation quantities must be positive integers.")
    elif kind == "RECONFIRMATION_REQUEST":
        _identifier(payload.get("request_id"))
        _instant(payload.get("due_at"))
    elif kind == "OBSERVED_OUTCOME":
        label = payload.get("label")
        if label is not None and (type(label) is not int or label not in (0, 1)):
            raise ProjectionError("Observed outcomes require an integer 0/1 label or null.")
        if label is not None:
            _identifier(booking)
        _day(payload.get("check_in_date"))
    elif kind == "INVENTORY_PROBE":
        _identifier(payload.get("request_id"))
        _day(payload.get("target_check_in_date"))
        _day(payload.get("check_out_date"))
        for name in ("adults", "rooms"):
            if type(payload.get(name)) is not int:
                raise ProjectionError("Inventory observation quantities must be integers.")
        for name in ("room_type", "currency"):
            _identifier(payload.get(name))
        count = payload.get("available_rooms")
        if count is not None and type(count) is not int:
            raise ProjectionError("Observed inventory counts must be integers or null.")
    elif kind == "HOTEL_REPLY":
        _identifier(payload.get("request_id"))
        if "schema_valid" in payload and type(payload["schema_valid"]) is not bool:
            raise ProjectionError("Hotel observation validation flags must be booleans.")
        if "nights" in payload:
            if type(payload["nights"]) is not list:
                raise ProjectionError("Hotel observation nights must be a list.")
            for night in payload["nights"]:
                if type(night) is not dict:
                    raise ProjectionError("Hotel observation nights must contain objects.")
                _day(night.get("stay_date"))
                if night.get("status") not in {"AVAILABLE", "UNAVAILABLE", "UNKNOWN"}:
                    raise ProjectionError("Hotel observation nights require supported statuses.")
    if kind in {"HOTEL_REPLY", "INVENTORY_PROBE"}:
        status = payload.get("http_status")
        if status is not None and type(status) is not int:
            raise ProjectionError("Observation HTTP statuses must be integers or null.")


def _observations(source: Any, hotel_id: str) -> dict:
    if (type(source) is not dict or type(source.get("hotel")) is not dict
            or type(source.get("events")) is not list):
        raise ProjectionError("The source must be one hotel shard containing hotel and events.")
    if _identifier(source["hotel"].get("hotel_id")) != hotel_id:
        raise ProjectionError("The requested hotel must match the source hotel identity.")
    events = []
    for event in source["events"]:
        if type(event) is not dict or type(event.get("event_type")) is not str:
            raise ProjectionError("Source events must be objects with an event_type string.")
        # Match the established builder's public event allowlist. Private world
        # events need no timestamps/payload and are never passed to the builder.
        if event["event_type"] not in _PUBLIC_TYPES:
            continue
        if _identifier(event.get("hotel_id")) != hotel_id:
            raise ProjectionError("Public observations must belong to the requested hotel.")
        _identifier(event.get("event_id"))
        if event.get("booking_id") is not None:
            _identifier(event["booking_id"])
        occurred = _instant(event.get("occurred_at"))
        available = _instant(event.get("available_at"))
        if available < occurred:
            raise ProjectionError("Observations cannot be available before they occur.")
        if type(event.get("payload")) is not dict:
            raise ProjectionError("Public observation payloads must be objects.")
        _payload(event)
        events.append(event)
    # Do not pass final bookings, truth, private hotel fields or other audits.
    return {"hotel": {"hotel_id": hotel_id}, "events": events}


def prepare_projection(source: dict, hotel_id: str, target_date: str,
                       config: dict | None = None) -> dict:
    """Build one local handoff envelope, with no caller or frozen-config mutation.

    Optional config must equal the repository's frozen v0.2 configuration; it is
    not a way to tune feature definitions. Only a copied calendar panel changes.
    sourceSha256/configSha256 hash canonical JSON (sorted keys, compact UTF-8),
    not file formatting. The feature builder hash refers to exact source bytes.
    lineageDigest retains the existing builder's used-observation ID semantics.
    The caller attests this source is synthetic; the envelope never claims real
    operational evidence or measured real-world model performance.
    """
    try:
        _identifier(hotel_id)
        _day(target_date)
        _json_value(source)
        frozen = load_config()
        if config is not None:
            _json_value(config)
            if json_hash(config) != json_hash(frozen):
                raise ProjectionError("Feature projection requires the unchanged frozen v0.2 configuration.")
        effective = deepcopy(frozen)
        effective["calendar_panel"] = {"start": target_date, "end": target_date}
        observations = _observations(source, hotel_id)
        rows = feature_builder.build_features(observations, effective)
        if len(rows) != 1:
            raise ProjectionError("Feature projection must produce exactly one target row.")
        row = rows[0]
        cohort = row["cohortBookingIds"]
        if not cohort:
            raise ProjectionError("NOT_ELIGIBLE: no confirmed platform bookings were known at D-1.")
        request = validate_request({
            "hotelId": row["hotelId"], "targetCheckInDate": row["targetCheckInDate"],
            "asOf": row["asOf"], "featureSetVersion": row["featureSetVersion"],
            "features": {name: row[name] for name in FEATURE_NAMES},
        })
        return {
            "format": FORMAT, "sourceKind": SOURCE_KIND, "request": request,
            "cohortBookingCodes": list(cohort),
            "provenance": {
                "sourceSha256": json_hash(source),
                "featureBuilderSha256": sha256(Path(feature_builder.__file__)),
                "configSha256": json_hash(effective),
                "lineageDigest": row["lineageDigest"],
            },
        }
    except ProjectionError:
        raise
    except (KeyError, TypeError, ValueError, OverflowError, RecursionError,
            OSError, RiskRequestError):
        # Existing builder errors can include event IDs; never relay them.
        raise ProjectionError("The observed source cannot produce a valid frozen API-023 projection.") from None


def _unique_object(pairs: list[tuple[str, Any]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProjectionError("Source JSON must not contain duplicate object keys.")
        result[key] = value
    return result


def _nonfinite(_: str) -> None:
    raise ProjectionError("Source JSON must contain finite values.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--hotel-id", required=True)
    parser.add_argument("--target-check-in-date", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        try:
            source = json.loads(args.source.read_text(encoding="utf-8-sig"),
                                object_pairs_hook=_unique_object, parse_constant=_nonfinite)
        except (OSError, UnicodeError, json.JSONDecodeError, RecursionError, ValueError) as exc:
            if isinstance(exc, ProjectionError):
                raise
            raise ProjectionError("The source file must be readable, valid UTF-8 JSON.") from None
        result = prepare_projection(source, args.hotel_id, args.target_check_in_date)
        encoded = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
        args.output.parent.mkdir(parents=True, exist_ok=True)
        # Exclusive creation also rejects existing symlinks and concurrent writes.
        try:
            with args.output.open("x", encoding="utf-8", newline="\n") as stream:
                stream.write(encoded)
        except FileExistsError:
            raise ProjectionError("The output already exists; choose a new output file.") from None
        print(json.dumps({"status": "prepared", "format": FORMAT,
                          "sourceKind": SOURCE_KIND, "cohortCount": len(result["cohortBookingCodes"])}))
        return 0
    except (ProjectionError, OSError) as exc:
        detail = str(exc) if isinstance(exc, ProjectionError) else "The projection file could not be written."
        print("Error: " + detail, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
