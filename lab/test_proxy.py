import asyncio
import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
from fastapi.testclient import TestClient

from lab import proxy


class ProxyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, LAB_PROXY_TOKEN="test-token")
        self.env.start()
        self.raw = patch.object(proxy, "RAW", Path(self.temp.name))
        self.raw.start()
        proxy.state = proxy.Shaper()
        self.client = TestClient(proxy.app)
        self.client.__enter__()
        self.upstream = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(304 if request.headers.get("if-none-match") else 200, stream=httpx.ByteStream(b"" if request.headers.get("if-none-match") else b"image"), headers={"etag": '"fixed"', "cache-control": "public,max-age=3600"})))
        self.original = proxy.app.state.client
        proxy.app.state.client = self.upstream

    def tearDown(self):
        proxy.app.state.client = self.original
        self.client.__exit__(None, None, None)
        self.raw.stop(); self.env.stop(); self.temp.cleanup()

    def shape(self, **extra):
        return self.client.post('/_lab/configure', headers={"X-Lab-Token": "test-token"}, json=dict(run_id="Rtest", trial_id="T1", kbps=20000, delay_ms=0, **extra))

    def test_control_requires_token_and_safe_ids(self):
        self.assertEqual(self.client.post('/_lab/configure', json=dict(run_id='R',trial_id='T',kbps=1,delay_ms=0)).status_code, 403)
        self.assertEqual(self.client.post('/_lab/configure', headers={"X-Lab-Token":"test-token"}, json=dict(run_id='../escape',trial_id='T',kbps=1,delay_ms=0)).status_code, 422)

    def test_proxy_forwards_cache_headers_and_logs_actual_body(self):
        self.assertEqual(self.shape().status_code, 200)
        response = self.client.get('/v1/images/1?width=720')
        self.assertEqual(response.content, b'image')
        self.assertEqual(response.headers['etag'], '"fixed"')
        rows = (Path(self.temp.name)/'Rtest/T1/proxy.jsonl').read_text().splitlines()
        self.assertEqual(json.loads(rows[0])['bytes_body'], 5)
        response = self.client.get('/v1/articles/1', headers={'If-None-Match':'"fixed"'})
        self.assertEqual(response.status_code, 304)
        self.assertEqual(response.content, b'')
        self.assertEqual(proxy.state.active, 0)

    def test_offline_blocks_upstream(self):
        self.shape(blocked=True)
        self.assertEqual(self.client.get('/v1/articles/1').status_code, 503)

    def test_global_pacing_reserves_bandwidth_across_concurrent_calls(self):
        async def measure():
            shaper = proxy.Shaper()
            started = time.monotonic()
            await asyncio.gather(shaper.pace(100, 8), shaper.pace(100, 8))
            return time.monotonic()-started
        self.assertGreaterEqual(asyncio.run(measure()), .19)


if __name__ == '__main__':
    unittest.main()
