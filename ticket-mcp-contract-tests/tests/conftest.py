import os
from contextlib import asynccontextmanager

import pytest
from mcp import ClientSession
from mcp.client.sse import sse_client

BASE_URL = os.environ.get("TICKETS_MCP_URL", "http://localhost:8081").rstrip("/")
# The server's MCP_SERVICE_TOKEN (K5). Blank: the server runs without the token check.
SERVICE_TOKEN = os.environ.get("MCP_SERVICE_TOKEN", "").strip()
AUTH_HEADERS = {"Authorization": f"Bearer {SERVICE_TOKEN}"} if SERVICE_TOKEN else {}


@asynccontextmanager
async def _connect():
    async with (
        sse_client(f"{BASE_URL}/sse", headers=AUTH_HEADERS) as (read, write),
        ClientSession(read, write) as session,
    ):
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
