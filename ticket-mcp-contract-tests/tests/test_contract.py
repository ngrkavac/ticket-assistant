"""Black-box contract every ticket-mcp-server implementation (Java, Python, Rust) must satisfy.

Run against a live server: TICKETS_MCP_URL=http://localhost:8081 uv run pytest
Expected values were captured from the original Java (Spring AI) implementation.
"""

import json

import httpx
import pytest
from mcp.shared.exceptions import McpError
from mcp.types import INVALID_PARAMS

pytestmark = pytest.mark.anyio

T1001 = {
    "id": "T-1001",
    "customer": "acme",
    "subject": "VPN drops every 10 minutes",
    "status": "OPEN",
    "priority": "HIGH",
}
T1003 = {
    "id": "T-1003",
    "customer": "globex",
    "subject": "Laptop not charging",
    "status": "IN_PROGRESS",
    "priority": "MEDIUM",
}
T1004 = {
    "id": "T-1004",
    "customer": "globex",
    "subject": "Printer offline on floor 3",
    "status": "OPEN",
    "priority": "LOW",
}


def only_text(result) -> str:
    assert len(result.content) == 1, result.content
    assert result.content[0].type == "text"
    return result.content[0].text


async def call_ok(mcp, tool, args):
    result = await mcp.call_tool(tool, args)
    assert not result.isError, result.content
    text = only_text(result)
    # Field order and compact separators must match Java's Jackson output.
    assert text == json.dumps(json.loads(text), separators=(",", ":")), text
    return json.loads(text)


async def call_err(mcp, tool, args):
    result = await mcp.call_tool(tool, args)
    assert result.isError, result.content
    return only_text(result)


async def test_server_info(init_result):
    assert init_result.serverInfo.name == "ticket-server"
    assert init_result.serverInfo.version == "1.0.0"
    assert init_result.capabilities.tools is not None


async def test_lists_exactly_the_two_tools(mcp):
    tools = {t.name: t for t in (await mcp.list_tools()).tools}
    assert set(tools) == {"getTicket", "listOpenTickets"}

    get = tools["getTicket"]
    assert get.description == (
        "Get a support ticket by its id, e.g. T-1001. Fails with a message if it does not exist."
    )
    assert get.inputSchema["required"] == ["id"]
    assert get.inputSchema["properties"]["id"]["type"] == "string"
    assert get.inputSchema["properties"]["id"]["description"] == "Ticket id such as T-1001"

    lst = tools["listOpenTickets"]
    assert lst.description == "List all open (status OPEN or IN_PROGRESS) tickets of a customer."
    assert lst.inputSchema["required"] == ["customer"]
    assert lst.inputSchema["properties"]["customer"]["type"] == "string"
    assert lst.inputSchema["properties"]["customer"]["description"] == "Customer name, e.g. acme"


@pytest.mark.parametrize("ticket_id", ["T-1001", " t-1001 "])
async def test_get_ticket(mcp, ticket_id):
    assert await call_ok(mcp, "getTicket", {"id": ticket_id}) == T1001
    result = await mcp.call_tool("getTicket", {"id": ticket_id})
    assert list(json.loads(only_text(result))) == list(T1001), "field order"


async def test_list_open_tickets_excludes_resolved(mcp):
    assert await call_ok(mcp, "listOpenTickets", {"customer": "acme"}) == [T1001]


async def test_list_open_tickets_is_case_insensitive_and_sorted(mcp):
    assert await call_ok(mcp, "listOpenTickets", {"customer": " GLOBEX "}) == [T1003, T1004]


async def test_list_open_tickets_unknown_customer_is_empty(mcp):
    assert await call_ok(mcp, "listOpenTickets", {"customer": "nobody"}) == []


async def test_customer_with_nbsp_is_not_trimmed(mcp):
    # Java trims only chars <= U+0020 and isBlank() does not treat NBSP as whitespace.
    assert await call_ok(mcp, "listOpenTickets", {"customer": "\u00a0acme"}) == []


async def test_unknown_ticket_is_an_error(mcp):
    assert await call_err(mcp, "getTicket", {"id": "T-9999"}) == "Ticket T-9999 not found"


@pytest.mark.parametrize(
    "args",
    [
        {"id": "1001; drop"},
        {"id": "T-123456789"},
        {"id": ""},
        {},
        {"id": "T-\u0661\u0660\u0660\u0661"},  # non-ASCII digits: Java's \\d is ASCII-only
        {"id": "\u00a0T-1001"},  # NBSP is not removed by Java's String.trim()
    ],
)
async def test_malformed_id_is_an_error(mcp, args):
    assert await call_err(mcp, "getTicket", args) == "Invalid ticket id, expected format T-1234"


@pytest.mark.parametrize("customer", ["", " ", "\t\n", "\u2003"])  # EM SPACE is Java whitespace
async def test_blank_customer_is_an_error(mcp, customer):
    error = await call_err(mcp, "listOpenTickets", {"customer": customer})
    assert error == "Customer name is required"


async def test_scalar_arguments_are_coerced_to_strings(mcp):
    # Spring AI converts JSON scalars to the String parameter: 5 -> "5", true -> "true".
    assert await call_ok(mcp, "listOpenTickets", {"customer": 5}) == []
    assert await call_ok(mcp, "listOpenTickets", {"customer": True}) == []
    error = await call_err(mcp, "getTicket", {"id": 1001})
    assert error == "Invalid ticket id, expected format T-1234"


async def test_extra_arguments_are_ignored(mcp):
    assert await call_ok(mcp, "listOpenTickets", {"customer": "acme", "extra": 1}) == [T1001]


async def test_unknown_tool_is_a_json_rpc_error(mcp):
    with pytest.raises(McpError) as raised:
        await mcp.call_tool("deleteTicket", {"id": "T-1001"})
    assert raised.value.error.code == INVALID_PARAMS
    assert raised.value.error.message == "Unknown tool: invalid_tool_name"
    assert raised.value.error.data == "Tool not found: deleteTicket"


async def test_raw_sse_transport_without_redirects(base_url):
    """Speaks the HTTP+SSE transport by hand with redirects disabled.

    The MCP Python client quietly follows redirects; Spring AI's Java client (used by the
    assistant) does not, so the message endpoint must accept the POST directly.
    """
    async with (
        httpx.AsyncClient(base_url=base_url, follow_redirects=False, timeout=10) as client,
        client.stream("GET", "/sse", headers={"Accept": "text/event-stream"}) as events,
    ):
        assert events.status_code == 200
        assert events.headers["content-type"].startswith("text/event-stream")
        lines = events.aiter_lines()

        async def next_event() -> tuple[str, str]:
            name = data = ""
            async for line in lines:
                if line.startswith("event:"):
                    name = line.removeprefix("event:").strip()
                elif line.startswith("data:"):
                    data = line.removeprefix("data:").strip()
                elif line == "" and data:
                    return name, data
            raise AssertionError("SSE stream ended")

        name, endpoint = await next_event()
        assert name == "endpoint"
        assert endpoint.startswith("/mcp/message?")

        # The first message Spring AI's client sends (it asks for 2024-11-05 too).
        initialize = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "contract-tests", "version": "1.0.0"},
            },
        }
        response = await client.post(endpoint, json=initialize)
        assert 200 <= response.status_code < 300, response.status_code

        name, data = await next_event()
        assert name == "message"
        message = json.loads(data)
        assert message["id"] == 1
        assert message["result"]["protocolVersion"] == "2024-11-05"
        assert message["result"]["serverInfo"]["name"] == "ticket-server"


async def test_readiness_endpoint(base_url):
    async with httpx.AsyncClient() as client:
        response = await client.get(f"{base_url}/actuator/health/readiness")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"
