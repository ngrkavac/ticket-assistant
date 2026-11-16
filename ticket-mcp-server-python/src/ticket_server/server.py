"""MCP server exposing the ticket tools over the SSE transport (GET /sse, POST /mcp/message).

Uses the low-level MCP server API rather than FastMCP so the wire contract matches the Java
implementation exactly: bare error messages, compact JSON text results, server version 1.0.0.
"""

import json
import logging
import os
from typing import Any

import uvicorn
from mcp import types
from mcp.server.lowlevel import Server
from mcp.server.sse import SseServerTransport
from mcp.shared.exceptions import McpError
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route
from starlette.types import ASGIApp, Receive, Scope, Send

from ticket_server.tickets import get_ticket, list_open_tickets

SERVER_NAME = "ticket-server"
SERVER_VERSION = "1.0.0"
MESSAGE_PATH = "/mcp/message"

TOOLS = [
    types.Tool(
        name="getTicket",
        description=(
            "Get a support ticket by its id, e.g. T-1001. "
            "Fails with a message if it does not exist."
        ),
        inputSchema={
            "type": "object",
            "properties": {"id": {"type": "string", "description": "Ticket id such as T-1001"}},
            "required": ["id"],
            "additionalProperties": False,
        },
    ),
    types.Tool(
        name="listOpenTickets",
        description="List all open (status OPEN or IN_PROGRESS) tickets of a customer.",
        inputSchema={
            "type": "object",
            "properties": {
                "customer": {"type": "string", "description": "Customer name, e.g. acme"}
            },
            "required": ["customer"],
            "additionalProperties": False,
        },
    ),
]


TOOL_NAMES = frozenset(tool.name for tool in TOOLS)


def _to_json(value: Any) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def _as_string(value: Any) -> str | None:
    """Coerce a JSON argument to a string the way Spring AI does for a String parameter."""
    if value is None or isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    return _to_json(value)


def call(name: str, arguments: dict[str, Any]) -> str:
    """Run a known tool and return its JSON text result; raises ValueError on bad input."""
    if name == "getTicket":
        return _to_json(get_ticket(_as_string(arguments.get("id"))).to_dict())
    if name == "listOpenTickets":
        tickets = list_open_tickets(_as_string(arguments.get("customer")))
        return _to_json([t.to_dict() for t in tickets])
    raise ValueError(f"Unknown tool: {name}")


def _text_result(text: str, is_error: bool) -> types.ServerResult:
    return types.ServerResult(
        types.CallToolResult(content=[types.TextContent(type="text", text=text)], isError=is_error)
    )


async def handle_call_tool(request: types.CallToolRequest) -> types.ServerResult:
    """tools/call: tool failures are isError results; an unknown tool is a JSON-RPC error."""
    name = request.params.name
    if name not in TOOL_NAMES:
        raise McpError(
            types.ErrorData(
                code=types.INVALID_PARAMS,
                message="Unknown tool: invalid_tool_name",
                data=f"Tool not found: {name}",
            )
        )
    try:
        return _text_result(call(name, request.params.arguments or {}), is_error=False)
    except ValueError as e:
        return _text_result(str(e), is_error=True)


def build_mcp_server() -> Server:
    server: Server = Server(SERVER_NAME, version=SERVER_VERSION)

    @server.list_tools()
    async def _list_tools() -> list[types.Tool]:
        return TOOLS

    # Registered directly instead of via @server.call_tool(): that decorator validates input
    # against the schema and turns every exception (even an unknown tool) into an isError
    # result, while the Java server lets the tools validate and answers unknown tools with
    # a JSON-RPC error.
    server.request_handlers[types.CallToolRequest] = handle_call_tool
    return server


class _AsgiEndpoint:
    """Makes Starlette's Route treat a function as a raw ASGI app, not a request handler."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        await self.app(scope, receive, send)


def build_app() -> Starlette:
    server = build_mcp_server()
    sse = SseServerTransport(MESSAGE_PATH)

    async def handle_sse(request: Request) -> Response:
        async with sse.connect_sse(request.scope, request.receive, request._send) as (read, write):
            await server.run(read, write, server.create_initialization_options())
        return Response()

    async def health(_: Request) -> JSONResponse:
        return JSONResponse({"status": "UP"})

    return Starlette(
        routes=[
            Route("/sse", handle_sse, methods=["GET"]),
            # An exact route, not Mount(): Mount redirects /mcp/message to /mcp/message/ (307),
            # which Spring AI's Java MCP client does not follow.
            Route(MESSAGE_PATH, _AsgiEndpoint(sse.handle_post_message), methods=["POST"]),
            Route("/actuator/health", health),
            Route("/actuator/health/liveness", health),
            Route("/actuator/health/readiness", health),
        ]
    )


app = build_app()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    uvicorn.run(
        app,
        host=os.environ.get("HOST", "0.0.0.0"),
        port=int(os.environ.get("PORT", "8081")),
        timeout_graceful_shutdown=10,
    )


if __name__ == "__main__":
    main()
