"""Black-box contract every ticket-mcp-server implementation (Java, Python, Rust) must satisfy.

Run against a live server: TICKETS_MCP_URL=http://localhost:8081 uv run pytest
Set MCP_SERVICE_TOKEN to the server's token if it has one (K5).
Expected values were captured from the original Java (Spring AI) implementation.
"""

import json

import httpx
import pytest
from mcp.shared.exceptions import McpError
from mcp.types import INVALID_PARAMS

pytestmark = pytest.mark.anyio


def ticket(id_, customer, subject, status, priority):
    return {
        "id": id_,
        "customer": customer,
        "subject": subject,
        "status": status,
        "priority": priority,
    }


T1001 = ticket("T-1001", "acme", "VPN drops every 10 minutes", "OPEN", "HIGH")
T1002 = ticket("T-1002", "acme", "Cannot reset password", "RESOLVED", "LOW")
T1003 = ticket("T-1003", "globex", "Laptop not charging", "IN_PROGRESS", "MEDIUM")
T1004 = ticket("T-1004", "globex", "Printer offline on floor 3", "OPEN", "LOW")
T1005 = ticket("T-1005", "acme", "Outlook keeps asking for credentials", "OPEN", "MEDIUM")
T1006 = ticket("T-1006", "globex", "Monitor flickering", "RESOLVED", "LOW")
T1010 = ticket("T-1010", "acme", "MFA codes not arriving by SMS", "IN_PROGRESS", "HIGH")

ACME_ALL = [T1001, T1002, T1005, T1010]
ACME_OPEN = [T1001, T1005, T1010]
GLOBEX_ALL = [T1003, T1004, T1006]
SEED_MAX_ID = 1010

INVALID_ID = "Invalid ticket id, expected format T-1234"
CUSTOMER_REQUIRED = "Customer name is required"
BLANK = ["", " ", "\t\n", "\u2003"]  # EM SPACE is Java whitespace


def only_text(result) -> str:
    assert len(result.content) == 1, result.content
    assert result.content[0].type == "text"
    return result.content[0].text


async def call_ok(mcp, tool, args):
    result = await mcp.call_tool(tool, args)
    assert not result.isError, result.content
    text = only_text(result)
    # Field order, compact separators and raw (unescaped) UTF-8 must match Java's Jackson output.
    assert text == json.dumps(json.loads(text), separators=(",", ":"), ensure_ascii=False), text
    return json.loads(text)


async def call_err(mcp, tool, args):
    result = await mcp.call_tool(tool, args)
    assert result.isError, result.content
    return only_text(result)


async def test_server_info(init_result):
    assert init_result.serverInfo.name == "ticket-server"
    assert init_result.serverInfo.version == "1.0.0"
    assert init_result.capabilities.tools is not None


def assert_params(tool, expected: dict[str, str], required: list[str]):
    """Property names (in order), types and descriptions, plus the required list."""
    props = tool.inputSchema.get("properties", {})
    assert list(props) == list(expected), tool.name
    for name, description in expected.items():
        assert props[name]["type"] == "string", (tool.name, name)
        assert props[name]["description"] == description, (tool.name, name)
    assert tool.inputSchema.get("required", []) == required, tool.name


async def test_lists_exactly_the_five_tools(mcp):
    tools = {t.name: t for t in (await mcp.list_tools()).tools}
    assert set(tools) == {
        "getTicket",
        "listOpenTickets",
        "listTickets",
        "listCustomers",
        "createTicket",
    }
    customer = {"customer": "Customer name, e.g. acme"}

    get = tools["getTicket"]
    assert get.description == (
        "Get a support ticket of a customer by its id, e.g. T-1001. "
        "Fails with a message if the customer has no such ticket."
    )
    assert_params(get, {**customer, "id": "Ticket id such as T-1001"}, ["customer", "id"])

    open_ = tools["listOpenTickets"]
    assert open_.description == "List all open (status OPEN or IN_PROGRESS) tickets of a customer."
    assert_params(open_, customer, ["customer"])

    lst = tools["listTickets"]
    assert lst.description == "List all tickets of a customer, optionally filtered by status."
    status = {"status": "Optional status filter: OPEN, IN_PROGRESS or RESOLVED"}
    assert_params(lst, {**customer, **status}, ["customer"])

    customers = tools["listCustomers"]
    assert customers.description == "List the names of all known customers."
    assert_params(customers, {}, [])

    create = tools["createTicket"]
    assert create.description == (
        "Create a new OPEN support ticket for a known customer and return it. "
        "The ticket id is assigned by the server."
    )
    assert_params(
        create,
        {
            **customer,
            "subject": "Short description of the problem",
            "priority": "Priority: LOW, MEDIUM or HIGH",
        },
        ["customer", "subject", "priority"],
    )


# --- getTicket ---------------------------------------------------------------------------


@pytest.mark.parametrize(("customer", "ticket_id"), [("acme", "T-1001"), (" ACME ", " t-1001 ")])
async def test_get_ticket(mcp, customer, ticket_id):
    args = {"customer": customer, "id": ticket_id}
    assert await call_ok(mcp, "getTicket", args) == T1001
    result = await mcp.call_tool("getTicket", args)
    assert list(json.loads(only_text(result))) == list(T1001), "field order"


async def test_unknown_ticket_is_an_error(mcp):
    error = await call_err(mcp, "getTicket", {"customer": "acme", "id": "T-9999"})
    assert error == "Ticket T-9999 not found"


async def test_ticket_of_another_customer_is_not_found(mcp):
    # Same message as an unknown id: one customer cannot learn about another's tickets.
    error = await call_err(mcp, "getTicket", {"customer": "acme", "id": " t-1003 "})
    assert error == "Ticket t-1003 not found"
    error = await call_err(mcp, "getTicket", {"customer": "nobody", "id": "T-1001"})
    assert error == "Ticket T-1001 not found"


@pytest.mark.parametrize(
    "args",
    [
        {"id": "1001; drop"},
        {"id": "T-123456789"},
        {"id": ""},
        {},
        {"id": "T-١٠٠١"},  # non-ASCII digits: Java's \\d is ASCII-only
        {"id": " T-1001"},  # NBSP is not removed by Java's String.trim()
    ],
)
async def test_malformed_id_is_an_error(mcp, args):
    assert await call_err(mcp, "getTicket", {"customer": "acme", **args}) == INVALID_ID


async def test_get_ticket_checks_customer_before_id(mcp):
    assert await call_err(mcp, "getTicket", {"id": "bad"}) == CUSTOMER_REQUIRED
    assert await call_err(mcp, "getTicket", {"customer": " ", "id": "bad"}) == CUSTOMER_REQUIRED


# --- listOpenTickets / listTickets --------------------------------------------------------


async def test_list_open_tickets_excludes_resolved(mcp):
    assert await call_ok(mcp, "listOpenTickets", {"customer": "acme"}) == ACME_OPEN


async def test_list_open_tickets_is_case_insensitive_and_sorted(mcp):
    assert await call_ok(mcp, "listOpenTickets", {"customer": " GLOBEX "}) == [T1003, T1004]


@pytest.mark.parametrize("tool", ["listOpenTickets", "listTickets"])
async def test_unknown_customer_lists_nothing(mcp, tool):
    assert await call_ok(mcp, tool, {"customer": "nobody"}) == []


@pytest.mark.parametrize("tool", ["listOpenTickets", "listTickets"])
async def test_customer_with_nbsp_is_not_trimmed(mcp, tool):
    # Java trims only chars <= U+0020 and isBlank() does not treat NBSP as whitespace.
    assert await call_ok(mcp, tool, {"customer": " acme"}) == []


@pytest.mark.parametrize("tool", ["getTicket", "listOpenTickets", "listTickets", "createTicket"])
@pytest.mark.parametrize("customer", BLANK)
async def test_blank_customer_is_an_error(mcp, tool, customer):
    args = {"customer": customer, "id": "T-1001", "subject": "x", "priority": "LOW"}
    assert await call_err(mcp, tool, args) == CUSTOMER_REQUIRED


@pytest.mark.parametrize("status", [None, "", " "])
async def test_list_tickets_without_status_lists_all(mcp, status):
    args = {"customer": "Globex"} if status is None else {"customer": "Globex", "status": status}
    assert await call_ok(mcp, "listTickets", args) == GLOBEX_ALL


@pytest.mark.parametrize(
    ("status", "expected"),
    [
        ("RESOLVED", [T1002]),
        (" open ", [T1001, T1005]),
        ("In_Progress", [T1010]),
    ],
)
async def test_list_tickets_filters_by_status(mcp, status, expected):
    assert await call_ok(mcp, "listTickets", {"customer": "acme", "status": status}) == expected


@pytest.mark.parametrize("status", ["CLOSED", "IN PROGRESS", " OPEN", 1])
async def test_invalid_status_is_an_error(mcp, status):
    error = await call_err(mcp, "listTickets", {"customer": "acme", "status": status})
    assert error == "Invalid status, expected one of OPEN, IN_PROGRESS, RESOLVED"


async def test_list_tickets_includes_resolved(mcp):
    assert await call_ok(mcp, "listTickets", {"customer": "acme"}) == ACME_ALL


# --- listCustomers -----------------------------------------------------------------------


async def test_list_customers(mcp):
    assert await call_ok(mcp, "listCustomers", {}) == ["acme", "globex", "initech"]


# --- createTicket ------------------------------------------------------------------------
# The server keeps created tickets for its lifetime, so these tests only create tickets for
# initech, never assert initech's full ticket list, and never assume an exact new id.


def ticket_number(ticket_id: str) -> int:
    prefix, _, number = ticket_id.partition("-")
    assert prefix == "T" and number.isascii() and number.isdigit(), ticket_id
    return int(number)


async def test_create_ticket(mcp):
    args = {"customer": " InitECH ", "subject": "  Badge reader broken  ", "priority": " high "}
    created = await call_ok(mcp, "createTicket", args)
    assert list(created) == list(T1001), "field order"
    assert ticket_number(created["id"]) > SEED_MAX_ID
    assert created == ticket(created["id"], "initech", "Badge reader broken", "OPEN", "HIGH")

    fetched = await call_ok(mcp, "getTicket", {"customer": "initech", "id": created["id"]})
    assert fetched == created
    open_ids = [t["id"] for t in await call_ok(mcp, "listOpenTickets", {"customer": "initech"})]
    assert created["id"] in open_ids
    error = await call_err(mcp, "getTicket", {"customer": "acme", "id": created["id"]})
    assert error == f"Ticket {created['id']} not found"
    # Other customers' lists are untouched.
    assert await call_ok(mcp, "listTickets", {"customer": "acme"}) == ACME_ALL


async def test_created_ids_increase(mcp):
    args = {"customer": "initech", "subject": "Keyboard missing keys", "priority": "LOW"}
    first = await call_ok(mcp, "createTicket", args)
    second = await call_ok(mcp, "createTicket", args)
    assert ticket_number(second["id"]) == ticket_number(first["id"]) + 1


async def test_subject_of_200_code_points_is_accepted(mcp):
    subject = "\U0001f600" * 200  # 200 code points, 400 UTF-16 units
    args = {"customer": "initech", "subject": subject, "priority": "MEDIUM"}
    assert (await call_ok(mcp, "createTicket", args))["subject"] == subject


@pytest.mark.parametrize(
    ("args", "error"),
    [
        ({"customer": "nobody"}, "Unknown customer nobody"),
        ({"customer": " Nobody  "}, "Unknown customer Nobody"),
        ({"customer": "nobody", "subject": "", "priority": "x"}, "Unknown customer nobody"),
        ({"subject": None}, "Subject is required"),
        ({"subject": " \t"}, "Subject is required"),
        ({"subject": "", "priority": "x"}, "Subject is required"),
        ({"subject": "x" * 201}, "Subject must be at most 200 characters"),
        ({"priority": None}, "Invalid priority, expected one of LOW, MEDIUM, HIGH"),
        ({"priority": "URGENT"}, "Invalid priority, expected one of LOW, MEDIUM, HIGH"),
        ({"priority": 1}, "Invalid priority, expected one of LOW, MEDIUM, HIGH"),
    ],
)
async def test_create_ticket_errors(mcp, args, error):
    full = {"customer": "initech", "subject": "Mouse broken", "priority": "LOW", **args}
    full = {k: v for k, v in full.items() if v is not None}
    assert await call_err(mcp, "createTicket", full) == error


# --- argument handling -------------------------------------------------------------------


async def test_scalar_arguments_are_coerced_to_strings(mcp):
    # Spring AI converts JSON scalars to the String parameter: 5 -> "5", true -> "true".
    assert await call_ok(mcp, "listOpenTickets", {"customer": 5}) == []
    assert await call_ok(mcp, "listOpenTickets", {"customer": True}) == []
    assert await call_err(mcp, "getTicket", {"customer": "acme", "id": 1001}) == INVALID_ID


async def test_extra_arguments_are_ignored(mcp):
    assert await call_ok(mcp, "listOpenTickets", {"customer": "acme", "extra": 1}) == ACME_OPEN
    assert await call_ok(mcp, "listCustomers", {"extra": 1}) == ["acme", "globex", "initech"]


async def test_unknown_tool_is_a_json_rpc_error(mcp):
    with pytest.raises(McpError) as raised:
        await mcp.call_tool("deleteTicket", {"customer": "acme", "id": "T-1001"})
    assert raised.value.error.code == INVALID_PARAMS
    assert raised.value.error.message == "Unknown tool: invalid_tool_name"
    assert raised.value.error.data == "Tool not found: deleteTicket"


async def test_raw_sse_transport_without_redirects(base_url, auth_headers):
    """Speaks the HTTP+SSE transport by hand with redirects disabled.

    The MCP Python client quietly follows redirects; Spring AI's Java client (used by the
    assistant) does not, so the message endpoint must accept the POST directly.
    """
    async with (
        httpx.AsyncClient(
            base_url=base_url, follow_redirects=False, timeout=10, headers=auth_headers
        ) as client,
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


# --- service auth (K5) -----------------------------------------------------------------------


async def test_browser_origin_is_rejected(base_url, auth_headers):
    """MCP_ALLOWED_ORIGINS is empty by default, so any Origin header gets 403, token or not."""
    async with httpx.AsyncClient(base_url=base_url, timeout=10) as client:
        response = await client.post(
            "/mcp/message",
            json={},
            headers={**auth_headers, "Origin": "https://evil.example"},
        )
    assert response.status_code == 403


async def test_missing_or_wrong_token_is_rejected(base_url, service_token):
    if not service_token:
        pytest.skip("MCP_SERVICE_TOKEN is not set: the server under test runs without a token")
    async with httpx.AsyncClient(base_url=base_url, timeout=10) as client:
        missing = await client.get("/sse")
        wrong = await client.post(
            "/mcp/message", json={}, headers={"Authorization": f"Bearer {service_token}x"}
        )
    assert missing.status_code == 401
    assert missing.headers["www-authenticate"] == "Bearer"
    assert wrong.status_code == 401


async def test_probes_need_no_token(base_url):
    async with httpx.AsyncClient(base_url=base_url, timeout=10) as client:
        response = await client.get("/actuator/health/liveness")
    assert response.status_code == 200
