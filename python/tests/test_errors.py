import json

import httpx
import pytest

import verbex_speech
from verbex_speech import APIError

from .conftest import KEY, fixture

CASES = fixture("errors.json")["cases"]


@pytest.mark.parametrize("case", CASES, ids=[f'{c["status"]}-{c["code"]}' for c in CASES])
def test_error_responses_map_to_their_type(make_client, case):
    def handler(request):
        if "body" in case:
            return httpx.Response(case["status"], headers=case["headers"], content=json.dumps(case["body"]).encode(),
                                  )
        return httpx.Response(case["status"], headers=case["headers"], content=case["body_text"].encode())

    client, _ = make_client(handler, max_retries=0)
    with pytest.raises(APIError) as raised:
        client.listen.get("9f1c2f7a6b5e4d0f8a3c1b2d4e5f6a7b")
    error = raised.value
    assert type(error).__name__ == case["type"]
    assert isinstance(error, getattr(verbex_speech, case["type"]))
    assert error.status == case["status"]
    assert error.code == case["code"]
    assert error.request_id == case["request_id"]
    assert [{"name": f.name, "reason": f.reason} for f in error.fields] == case["fields"]
    assert error.retry_after == case["retry_after"]
    assert KEY not in str(error) and KEY not in repr(error)
