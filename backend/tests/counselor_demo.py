"""Read-only HTTP smoke test after creating YB-001 in the isolated demo DB."""
import json
import os
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def fetch(path, token=None, status=200):
    port = int(os.environ.get("CSRO_DEMO_SPRING_PORT", "18080"))
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    request = Request(f"http://127.0.0.1:{port}/api/v1/cases{path}", headers=headers)
    try:
        response = urlopen(request, timeout=5)
    except HTTPError as error:
        response = error
    with response:
        assert response.status == status, (path, response.status)
        return json.load(response)


def main():
    token = os.environ["CSRO_DEMO_TOKEN"]
    assert fetch("", status=401)["code"] == "UNAUTHENTICATED"
    assert fetch("/C-999", token, status=404)["code"] == "CASE_NOT_FOUND"
    items = fetch("", token)["items"]
    case = next(item for item in items if item["caseId"] == "C-001")
    detail = fetch("/C-001", token)
    assert case["bookingId"] == detail["booking"]["bookingId"] == "YB-001"
    assert case["nextAction"] == detail["nextAction"]
    assert detail["nextAction"]["dueAt"] is None
    assert detail["customerReports"] == []
    assert [(n["fulfillmentStatus"], n["factStatus"]) for n in detail["nights"]] == [
        ("UNAVAILABLE", "VERIFIED"), ("UNKNOWN", "UNCONFIRMED"), ("UNKNOWN", "UNCONFIRMED")
    ]
    hotel = next(e for e in detail["evidence"] if e["sourceType"] == "HOTEL_API")
    assert hotel["sourceAuthenticated"] is False
    assert hotel["evidenceId"] == detail["nights"][0]["evidenceId"]
    assert json.loads(hotel["originalContent"])["supplierBookingId"] == "SUP-BOOK-001"
    assert [a["reviewStatus"] for a in hotel["assertions"]] == [
        "ACCEPTED", "PENDING_REVIEW", "PENDING_REVIEW"
    ]
    print("PASS API-003/004 YB-001")


if __name__ == "__main__":
    main()
