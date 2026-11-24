"""Check an already-running Mock server. No database or Spring connection."""
import argparse
from copy import deepcopy
import json
import os
from pathlib import Path
import sys

import httpx


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8001')
    args = parser.parse_args()
    token = os.environ.get('MOCK_SERVICE_TOKEN', 'csro-local-mock-token')
    try:
        with httpx.Client(base_url=args.base_url, headers={'Authorization': f'Bearer {token}'},
                          timeout=5, trust_env=False) as client:
            for number, hotel_status in ((1, 200), (2, 200), (3, 404), (4, 202), (5, 503)):
                supplier = client.get(f'/mock-supplier/v1/bookings/SUP-BOOK-{number:03d}')
                assert supplier.status_code == (503 if number == 5 else 200), f'YB-{number:03d} supplier HTTP {supplier.status_code}'
                body = {'reconfirmationRequestId': f'RECHECK-{number:03d}',
                        'supplierBookingId': f'SUP-BOOK-{number:03d}', 'propertyId': f'HOTEL-{number:03d}',
                        'stayDates': ['2026-11-10', '2026-11-11', '2026-11-12']}
                hotel = client.post('/mock-hotel/v1/reconfirmations', json=body)
                assert hotel.status_code == hotel_status, f'YB-{number:03d} hotel HTTP {hotel.status_code}'
                if number in (1, 2):
                    expected = ['UNAVAILABLE', 'UNKNOWN', 'UNKNOWN'] if number == 1 else ['AVAILABLE'] * 3
                    assert [n['reportedProvisionStatus'] for n in hotel.json()['nights']] == expected
                elif number == 4:
                    assert hotel.json()['status'] == 'PENDING' and 'nights' not in hotel.json()
                else:
                    assert hotel.json()['status'] == hotel_status and hotel.headers['content-type'] == 'application/problem+json'
                print(f'YB-{number:03d}: API-026={supplier.status_code}, API-027={hotel.status_code} PASS')
            search_body = json.loads((Path(__file__).resolve().parents[1] /
                                      'examples/availability-search.json').read_text(encoding='utf-8'))
            search = client.post('/mock-supplier/v1/availability-searches', json=search_body)
            assert search.status_code == 200, f'API-028 HTTP {search.status_code}'
            result = search.json()
            assert result['stay'] == search_body['stay']
            assert [q['price']['totalAmount'] for q in result['quotes']] == [480000, 510000, 465000]
            assert all(q['source'] == 'SIMULATED_SUPPLIER' for q in result['quotes'])
            narrow_body = deepcopy(search_body)
            narrow_body['filters']['radiusMeters'] = 700
            empty = client.post('/mock-supplier/v1/availability-searches', json=narrow_body)
            assert empty.status_code == 200 and empty.json()['quotes'] == []
            repeated = client.post('/mock-supplier/v1/availability-searches', json=search_body)
            assert repeated.status_code == 200 and repeated.json() == result
            print('API-028: fixed three-night quotes, empty result, repeated search PASS')
        print('Running server checks: passed')
        return 0
    except (httpx.HTTPError, AssertionError, KeyError, ValueError) as exc:
        print(f'Check failed: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
