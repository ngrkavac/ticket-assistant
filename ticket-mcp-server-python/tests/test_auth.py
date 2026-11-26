"""Service token and Origin check on the MCP endpoints (K5)."""

import pytest
from starlette.testclient import TestClient

from ticket_server.auth import parse_origins
from ticket_server.server import build_app

TOKEN = "s3cret-token"
ENV = {
    "MCP_SERVICE_TOKEN": TOKEN,
    "MCP_ALLOWED_ORIGINS": "http://localhost:6274, https://inspector.example",
}


@pytest.fixture
def client():
    return TestClient(build_app(ENV))


def post(client, **headers):
    # No session id: the MCP transport itself rejects the message (400) once auth let it pass.
    return client.post("/mcp/message", json={}, headers=headers)


def test_requests_without_the_token_get_401(client):
    missing = client.get("/sse")
    assert missing.status_code == 401
    assert missing.headers["www-authenticate"] == "Bearer"
    assert post(client, authorization="Bearer wrong").status_code == 401
    assert post(client, authorization=f"Basic {TOKEN}").status_code == 401
    assert post(client, authorization=f"Bearer {TOKEN}-and-more").status_code == 401
    assert post(client, authorization="Bearer").status_code == 401


def test_requests_with_the_token_reach_the_mcp_endpoints(client):
    assert post(client, authorization=f"Bearer {TOKEN}").status_code == 400
    assert post(client, authorization=f"bearer {TOKEN}").status_code == 400


def test_browser_origins_are_rejected_even_with_the_token(client):
    evil = post(client, authorization=f"Bearer {TOKEN}", origin="https://evil.example")
    assert evil.status_code == 403
    assert post(client, origin="null").status_code == 403


def test_allowed_origins_still_need_the_token(client):
    assert post(client, origin="https://inspector.example").status_code == 401
    allowed = post(client, origin="http://localhost:6274", authorization=f"Bearer {TOKEN}")
    assert allowed.status_code == 400


def test_probes_need_no_token(client):
    assert client.get("/actuator/health/readiness").status_code == 200
    assert client.get("/actuator/health/liveness").status_code == 200


def test_blank_token_disables_only_the_token_check():
    client = TestClient(build_app({"MCP_SERVICE_TOKEN": " "}))
    assert post(client).status_code == 400
    assert post(client, origin="http://localhost:6274").status_code == 403


def test_origin_list_is_trimmed_and_ignores_empty_entries():
    assert parse_origins(" https://a.example , ,http://b.example:8080,") == {
        "https://a.example",
        "http://b.example:8080",
    }
    assert parse_origins("") == frozenset()
