"""Real Uvicorn/TCP checks, separate from the in-process contract tests."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

import httpx
from fastapi.testclient import TestClient

from app.main import create_app

ROOT = Path(__file__).resolve().parents[1]
TOKEN = 'tcp-test-token'


class MetadataTests(unittest.TestCase):
    def test_openapi_errors_use_problem_media_type_and_require_bearer(self):
        document = create_app(service_token=TOKEN).openapi()
        self.assertEqual(set(document['paths']['/mock-supplier/v1/bookings/{supplierBookingId}']['get']['responses']),
                         {'200', '401', '404', '503', '500'})
        for path, method in (('/mock-supplier/v1/bookings/{supplierBookingId}', 'get'),
                             ('/mock-hotel/v1/reconfirmations', 'post')):
            operation = document['paths'][path][method]
            self.assertEqual(operation['security'], [{'InternalServiceBearer': []}])
            for code in ('401', '404', '503', '500'):
                self.assertEqual(set(operation['responses'][code]['content']), {'application/problem+json'})

    def test_error_trace_is_returned_in_response_header(self):
        with TestClient(create_app(service_token=TOKEN)) as client:
            response = client.get('/mock-supplier/v1/bookings/SUP-BOOK-001')
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.json()['traceId'], response.headers['x-request-id'])
            self.assertEqual(response.headers['www-authenticate'], 'Bearer')

    def test_environment_token_is_used_and_empty_token_is_rejected(self):
        from unittest.mock import patch
        with patch.dict(os.environ, {'MOCK_SERVICE_TOKEN': 'environment-test-token'}):
            with TestClient(create_app()) as client:
                response = client.get('/mock-supplier/v1/bookings/SUP-BOOK-001',
                                      headers={'Authorization': 'Bearer environment-test-token'})
                self.assertEqual(response.status_code, 200)
                self.assertEqual(client.get('/mock-supplier/v1/bookings/SUP-BOOK-001',
                    headers={'Authorization': 'Bearer csro-local-mock-token'}).status_code, 401)
        with self.assertRaises(ValueError):
            create_app(service_token='')


class RealHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with socket.socket() as port_socket:
            port_socket.bind(('127.0.0.1', 0))
            port = port_socket.getsockname()[1]
        cls.log = tempfile.TemporaryFile()
        cls.process = subprocess.Popen([sys.executable, '-m', 'uvicorn', 'app.main:app',
            '--host', '127.0.0.1', '--port', str(port), '--log-level', 'warning'],
            cwd=ROOT, env={**os.environ, 'MOCK_SERVICE_TOKEN': TOKEN},
            stdout=cls.log, stderr=subprocess.STDOUT)
        cls.addClassCleanup(cls.cleanup)
        cls.client = httpx.Client(base_url=f'http://127.0.0.1:{port}', trust_env=False, timeout=2)
        cls.addClassCleanup(cls.client.close)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                if cls.client.get('/healthz').status_code == 200:
                    return
            except httpx.TransportError:
                pass
            if cls.process.poll() is not None:
                break
            time.sleep(.05)
        cls.log.seek(0)
        raise RuntimeError('Uvicorn did not start: ' + cls.log.read().decode(errors='replace'))

    @classmethod
    def cleanup(cls):
        if cls.process.poll() is None:
            cls.process.terminate()
            try:
                cls.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                cls.process.kill()
                cls.process.wait(timeout=5)
        cls.log.close()

    def test_live_supplier_and_hotel_contract(self):
        headers = {'Authorization': f'Bearer {TOKEN}'}
        supplier = self.client.get('/mock-supplier/v1/bookings/SUP-BOOK-001', headers=headers)
        self.assertEqual(supplier.status_code, 200)
        self.assertEqual(supplier.json()['contractStatus'], 'CONFIRMED')
        body = {'reconfirmationRequestId': 'RECHECK-001', 'supplierBookingId': 'SUP-BOOK-001',
                'propertyId': 'HOTEL-001', 'stayDates': ['2026-11-10', '2026-11-11', '2026-11-12']}
        hotel = self.client.post('/mock-hotel/v1/reconfirmations', json=body, headers=headers)
        self.assertEqual(hotel.status_code, 200)
        self.assertEqual([n['reportedProvisionStatus'] for n in hotel.json()['nights']],
                         ['UNAVAILABLE', 'UNKNOWN', 'UNKNOWN'])
        self.assertEqual(self.client.get('/mock-supplier/v1/bookings/SUP-BOOK-001').status_code, 401)

    def test_live_extension_statuses_and_pending_shape(self):
        headers = {'Authorization': f'Bearer {TOKEN}'}
        for number, expected in ((2, 200), (3, 404), (4, 202), (5, 503)):
            with self.subTest(booking=number):
                body = {'reconfirmationRequestId': f'RECHECK-{number:03d}',
                        'supplierBookingId': f'SUP-BOOK-{number:03d}', 'propertyId': f'HOTEL-{number:03d}',
                        'stayDates': ['2026-11-10', '2026-11-11', '2026-11-12']}
                response = self.client.post('/mock-hotel/v1/reconfirmations', json=body, headers=headers)
                self.assertEqual(response.status_code, expected)
                if expected == 202:
                    self.assertEqual(set(response.json()), {'reconfirmationRequestId', 'reconfirmationId', 'status', 'receivedAt'})
                if expected >= 400:
                    self.assertEqual(response.headers['content-type'], 'application/problem+json')
