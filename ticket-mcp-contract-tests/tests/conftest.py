import os
from contextlib import asynccontextmanager

import httpx
import pytest
from mcp import ClientSession
from mcp.client.sse import sse_client
from mcp.client.streamable_http import streamable_http_client

BASE_URL = os.environ.get("TICKETS_MCP_URL", "http://localhost:8081").rstrip("/")
# The server's MCP_SERVICE_TOKEN (K5). Blank: the server runs without the token check.
SERVICE_TOKEN = os.environ.get("MCP_SERVICE_TOKEN", "").strip()
AUTH_HEADERS = {"Authorization": f"Bearer {SERVICE_TOKEN}"} if SERVICE_TOKEN else {}
# The MCP transport (K6): "streamable" (Streamable HTTP at /mcp, Java and Python) or "sse"
# (legacy HTTP+SSE at /sse and /mcp/message, only the Rust reference server, D2).
TRANSPORTS = ("streamable", "sse")
TRANSPORT = os.environ.get("TICKETS_MCP_TRANSPORT", "streamable").strip().lower()
if TRANSPORT not in TRANSPORTS:
    raise pytest.UsageError(f"TICKETS_MCP_TRANSPORT must be one of {TRANSPORTS}, got {TRANSPORT!r}")


@asynccontextmanager
async def _streams():
    if TRANSPORT == "sse":
        async with sse_client(f"{BASE_URL}/sse", headers=AUTH_HEADERS) as (read, write):
            yield read, write
    else:
        async with (
            httpx.AsyncClient(headers=AUTH_HEADERS, timeout=httpx.Timeout(30, read=300)) as http,
            streamable_http_client(f"{BASE_URL}/mcp", http_client=http) as (read, write, _),
        ):
            yield read, write


@asynccontextmanager
async def _connect():
    async with _streams() as (read, write), ClientSession(read, write) as session:
        yield session, await session.initialize()


@pytest.fixture
async def mcp():
    async with _connect() as (session, _):
        yield session


@pytest.fixture
async def init_result():
    async with _connect() as (_, init):
        yield init


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.fixture
def base_url():
    return BASE_URL


@pytest.fixture
def auth_headers():
    return dict(AUTH_HEADERS)


@pytest.fixture
def service_token():
    return SERVICE_TOKEN


@pytest.fixture
def transport():
    return TRANSPORT


@pytest.fixture
def mcp_path():
    """Where a client POSTs its MCP messages on this transport."""
    return "/mcp" if TRANSPORT == "streamable" else "/mcp/message"
