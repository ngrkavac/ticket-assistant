//! Service-to-service authentication for the MCP endpoints (contract K5).
//!
//! Every request except `/actuator/...` (probes) must carry `Authorization: Bearer
//! <MCP_SERVICE_TOKEN>`, otherwise it gets 401. A request with an `Origin` header that is not
//! listed in `MCP_ALLOWED_ORIGINS` (comma-separated, empty by default) gets 403, so a browser page
//! cannot call the server (DNS rebinding); server-to-server clients send no `Origin`.
//!
//! A blank token disables the token check (not the Origin check) and logs a warning. This is meant
//! for local development only.

use std::collections::HashSet;
use std::sync::Arc;

use axum::extract::{Request, State};
use axum::http::{header, HeaderMap, HeaderValue, StatusCode};
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use tracing::warn;

const BEARER: &str = "bearer ";

/// The token and the allowed origins; cheap to clone.
#[derive(Clone, Debug, Default)]
pub struct ServiceAuth {
    token: Option<Arc<[u8]>>,
    allowed_origins: Arc<HashSet<String>>,
}

impl ServiceAuth {
    pub fn new(token: &str, allowed_origins: &str) -> Self {
        let token = token.trim();
        if token.is_empty() {
            warn!("MCP_SERVICE_TOKEN is not set: MCP requests are accepted without a token");
        }
        ServiceAuth {
            token: (!token.is_empty()).then(|| Arc::from(token.as_bytes())),
            allowed_origins: Arc::new(parse_origins(allowed_origins)),
        }
    }

    /// Reads `MCP_SERVICE_TOKEN` and `MCP_ALLOWED_ORIGINS`.
    pub fn from_env() -> Self {
        let var = |key| std::env::var(key).unwrap_or_default();
        Self::new(&var("MCP_SERVICE_TOKEN"), &var("MCP_ALLOWED_ORIGINS"))
    }

    /// `None` lets the request through.
    fn check(&self, path: &str, headers: &HeaderMap) -> Option<Rejection> {
        if path == "/actuator" || path.starts_with("/actuator/") {
            return None;
        }
        if let Some(origin) = headers.get(header::ORIGIN) {
            let allowed = origin
                .to_str()
                .is_ok_and(|origin| self.allowed_origins.contains(origin));
            if !allowed {
                return Some(Rejection::Origin);
            }
        }
        match &self.token {
            Some(token) if !has_token(headers.get(header::AUTHORIZATION), token) => {
                Some(Rejection::Token)
            }
            _ => None,
        }
    }
}

enum Rejection {
    Origin,
    Token,
}

impl IntoResponse for Rejection {
    fn into_response(self) -> Response {
        match self {
            Rejection::Origin => (StatusCode::FORBIDDEN, "Origin not allowed").into_response(),
            Rejection::Token => (
                StatusCode::UNAUTHORIZED,
                [(header::WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"))],
                "Missing or invalid service token",
            )
                .into_response(),
        }
    }
}

/// Axum middleware: `axum::middleware::from_fn_with_state(auth, auth::require)`.
pub async fn require(State(auth): State<ServiceAuth>, request: Request, next: Next) -> Response {
    match auth.check(request.uri().path(), request.headers()) {
        None => next.run(request).await,
        Some(rejection) => rejection.into_response(),
    }
}

fn parse_origins(value: &str) -> HashSet<String> {
    value
        .split(',')
        .map(str::trim)
        .filter(|origin| !origin.is_empty())
        .map(String::from)
        .collect()
}

fn has_token(authorization: Option<&HeaderValue>, token: &[u8]) -> bool {
    let Some(value) = authorization.map(HeaderValue::as_bytes) else {
        return false;
    };
    if value.len() < BEARER.len() || !value[..BEARER.len()].eq_ignore_ascii_case(BEARER.as_bytes())
    {
        return false;
    }
    constant_time_eq(value[BEARER.len()..].trim_ascii(), token)
}

/// Compares without an early exit, so the response time does not reveal how much matched.
fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn origin_list_is_trimmed_and_ignores_empty_entries() {
        let origins = parse_origins(" https://a.example , ,http://b.example:8080,");
        assert_eq!(origins.len(), 2);
        assert!(origins.contains("https://a.example"));
        assert!(origins.contains("http://b.example:8080"));
        assert!(parse_origins("").is_empty());
    }

    #[test]
    fn token_comparison() {
        assert!(constant_time_eq(b"abc", b"abc"));
        assert!(!constant_time_eq(b"abc", b"abd"));
        assert!(!constant_time_eq(b"abc", b"abcd"));
        let token = b"s3cret";
        let header = |v: &'static str| Some(HeaderValue::from_static(v));
        assert!(has_token(header("Bearer s3cret").as_ref(), token));
        assert!(has_token(header("bearer  s3cret ").as_ref(), token));
        assert!(!has_token(header("Basic s3cret").as_ref(), token));
        assert!(!has_token(header("Bearer").as_ref(), token));
        assert!(!has_token(None, token));
    }
}
