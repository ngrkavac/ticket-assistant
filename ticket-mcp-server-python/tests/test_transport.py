"""The MCP transport (K6): stateless Streamable HTTP at POST /mcp, no legacy SSE endpoints."""

import pytest
from starlette.testclient import TestClient

from ticket_server.server import build_app

TOKEN = "s3cret-token"
HEADERS = {"authorization": f"Bearer {TOKEN}", "accept": "application/json, text/event-stream"}


@pytest.fixture
def client():
    with TestClient(build_app({"MCP_SERVICE_TOKEN": TOKEN})) as client:
        yield client


def rpc(client, method, params=None, id_=1):
    body = {"jsonrpc": "2.0", "method": method}
    if id_ is not None:
        body["id"] = id_
    if params is not None:
        body["params"] = params
    return client.post("/mcp", json=body, headers=HEADERS)


def test_initialize_answers_with_json_and_no_session(client):
    response = rpc(
        client,
        "initialize",
        {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "test", "version": "1.0.0"},
        },
    )
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/json")
    assert "mcp-session-id" not in response.headers
    result = response.json()["result"]
    assert result["serverInfo"] == {"name": "ticket-server", "version": "1.0.0"}
    assert rpc(client, "notifications/initialized", id_=None).status_code == 202


def test_every_request_stands_on_its_own(client):
    # Stateless: no initialize and no session needed, so any replica can answer.
    tools = rpc(client, "tools/list", id_=2).json()["result"]["tools"]
    assert [t["name"] for t in tools] == [
        "getTicket",
        "listOpenTickets",
        "listTickets",
        "listCustomers",
        "createTicket",
    ]
    call = rpc(client, "tools/call", {"name": "listCustomers", "arguments": {}}, 3)
    content = call.json()["result"]["content"]
    assert content == [{"type": "text", "text": '["acme","globex","initech"]'}]


@pytest.mark.parametrize("method", ["GET", "DELETE"])
def test_only_post_is_served(client, method):
    # No server-to-client stream and no session to end.
    assert client.request(method, "/mcp", headers=HEADERS).status_code == 405


@pytest.mark.parametrize(("method", "path"), [("GET", "/sse"), ("POST", "/mcp/message")])
def test_the_legacy_sse_endpoints_are_gone(client, method, path):
    assert client.request(method, path, headers=HEADERS).status_code == 404
