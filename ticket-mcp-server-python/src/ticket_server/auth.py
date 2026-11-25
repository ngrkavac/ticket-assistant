"""Service-to-service authentication for the MCP endpoints (contract K5).

Every request except ``/actuator/...`` (probes) must carry ``Authorization: Bearer
<MCP_SERVICE_TOKEN>``, otherwise it gets 401. A request with an ``Origin`` header that is not
listed in ``MCP_ALLOWED_ORIGINS`` (comma-separated, empty by default) gets 403, so a browser page
cannot call the server (DNS rebinding); server-to-server clients send no ``Origin``.

A blank token disables the token check (not the Origin check) and logs a warning. This is meant
for local development only.
"""

import hmac
import logging

from starlette.types import ASGIApp, Receive, Scope, Send

log = logging.getLogger(__name__)

_BEARER = b"bearer "


def parse_origins(value: str) -> frozenset[str]:
    return frozenset(origin.strip() for origin in value.split(",") if origin.strip())


class ServiceAuthMiddleware:
    """Pure ASGI middleware, so the SSE stream is passed through without buffering."""

    def __init__(self, app: ASGIApp, token: str, allowed_origins: frozenset[str]) -> None:
        self.app = app
        self.token = token.strip().encode() or None
        self.allowed_origins = allowed_origins
        if self.token is None:
            log.warning("MCP_SERVICE_TOKEN is not set: MCP requests are accepted without a token")

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        path = scope.get("path", "")
        if scope["type"] != "http" or path == "/actuator" or path.startswith("/actuator/"):
            await self.app(scope, receive, send)
            return
        headers = dict(scope.get("headers", []))
        origin = headers.get(b"origin")
        if origin is not None and origin.decode("latin-1") not in self.allowed_origins:
            await _reject(send, 403, b"Origin not allowed")
            return
        if self.token is not None and not self._has_token(headers.get(b"authorization")):
            await _reject(send, 401, b"Missing or invalid service token", bearer_challenge=True)
            return
        await self.app(scope, receive, send)

    def _has_token(self, authorization: bytes | None) -> bool:
        if authorization is None or authorization[: len(_BEARER)].lower() != _BEARER:
            return False
        # Constant-time comparison, so the response time does not reveal how much of it matched.
        return hmac.compare_digest(authorization[len(_BEARER) :].strip(), self.token)


async def _reject(send: Send, status: int, body: bytes, bearer_challenge: bool = False) -> None:
    headers = [
        (b"content-type", b"text/plain; charset=utf-8"),
        (b"content-length", str(len(body)).encode()),
    ]
    if bearer_challenge:
        headers.append((b"www-authenticate", b"Bearer"))
    await send({"type": "http.response.start", "status": status, "headers": headers})
    await send({"type": "http.response.body", "body": body})
