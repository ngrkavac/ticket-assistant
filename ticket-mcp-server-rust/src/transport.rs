//! The MCP HTTP+SSE transport (protocol revision 2024-11-05), as served by Spring AI's WebMVC
//! server: `GET /sse` opens an event stream whose first `endpoint` event names the URL to POST
//! JSON-RPC messages to; responses are delivered as `message` events on that stream.
//!
//! rmcp (the official Rust SDK) no longer ships a server for this transport, so it is
//! implemented here directly. It is small: one channel per session.

use std::collections::HashMap;
use std::convert::Infallible;
use std::sync::{Arc, Mutex};

use axum::body::Bytes;
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use futures_util::stream::{self, Stream, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;
use tokio_util::sync::CancellationToken;
use tracing::{debug, warn};

use crate::protocol;
use crate::tickets::TicketStore;

pub const SSE_PATH: &str = "/sse";
pub const MESSAGE_PATH: &str = "/mcp/message";

type Sessions = Arc<Mutex<HashMap<String, mpsc::Sender<String>>>>;

#[derive(Clone)]
struct AppState {
    sessions: Sessions,
    shutdown: CancellationToken,
    store: Arc<TicketStore>,
}

/// Builds the HTTP app. Cancelling `shutdown` ends all open SSE streams, so a graceful
/// server shutdown is not held up by connected clients.
pub fn router(shutdown: CancellationToken) -> Router {
    let state = AppState {
        sessions: Arc::default(),
        shutdown,
        store: Arc::new(TicketStore::new()),
    };
    Router::new()
        .route(SSE_PATH, get(open_session))
        .route(MESSAGE_PATH, post(receive_message))
        .route("/actuator/health", get(health))
        .route("/actuator/health/liveness", get(health))
        .route("/actuator/health/readiness", get(health))
        .with_state(state)
}

async fn health() -> Json<Value> {
    Json(json!({"status": "UP"}))
}

/// Removes the session when its SSE stream is dropped (client disconnected or shutdown).
struct SessionGuard {
    id: String,
    sessions: Sessions,
}

impl Drop for SessionGuard {
    fn drop(&mut self) {
        self.sessions.lock().unwrap().remove(&self.id);
        debug!(session = %self.id, "SSE session closed");
    }
}

async fn open_session(
    State(state): State<AppState>,
) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let id = uuid::Uuid::new_v4().to_string();
    let (tx, rx) = mpsc::channel::<String>(32);
    state.sessions.lock().unwrap().insert(id.clone(), tx);
    debug!(session = %id, "SSE session opened");

    let endpoint = Event::default()
        .event("endpoint")
        .data(format!("{MESSAGE_PATH}?sessionId={id}"));
    let guard = SessionGuard {
        id,
        sessions: state.sessions.clone(),
    };
    let messages = ReceiverStream::new(rx).map(move |json| {
        let _keep_session_until_stream_drops = &guard;
        Ok(Event::default().event("message").data(json))
    });
    let stream = stream::once(async { Ok(endpoint) })
        .chain(messages)
        .take_until(state.shutdown.cancelled_owned());
    Sse::new(stream).keep_alive(KeepAlive::default())
}

#[derive(Deserialize)]
struct SessionQuery {
    #[serde(rename = "sessionId")]
    session_id: Option<String>,
}

async fn receive_message(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
    body: Bytes,
) -> Response {
    let Some(session_id) = query.session_id else {
        return (
            StatusCode::BAD_REQUEST,
            "Session ID missing in message endpoint",
        )
            .into_response();
    };
    let sender = state.sessions.lock().unwrap().get(&session_id).cloned();
    let Some(sender) = sender else {
        return (StatusCode::NOT_FOUND, "Session not found").into_response();
    };

    let (response, status) = match serde_json::from_slice::<Value>(&body) {
        Ok(message) => (
            protocol::handle(&state.store, &message),
            StatusCode::ACCEPTED,
        ),
        Err(e) => {
            warn!(session = %session_id, error = %e, "invalid JSON-RPC message");
            (Some(protocol::parse_error()), StatusCode::BAD_REQUEST)
        }
    };
    if let Some(response) = response {
        if sender.send(response.to_string()).await.is_err() {
            return (StatusCode::NOT_FOUND, "Session closed").into_response();
        }
    }
    status.into_response()
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::body::Body;
    use axum::http::Request;
    use http_body_util::BodyExt;
    use tower::ServiceExt;

    async fn send(request: Request<Body>) -> (StatusCode, String) {
        let response = router(CancellationToken::new())
            .oneshot(request)
            .await
            .unwrap();
        let status = response.status();
        let body = response.into_body().collect().await.unwrap().to_bytes();
        (status, String::from_utf8(body.to_vec()).unwrap())
    }

    #[tokio::test]
    async fn readiness_is_up() {
        let request = Request::get("/actuator/health/readiness")
            .body(Body::empty())
            .unwrap();
        assert_eq!(
            send(request).await,
            (StatusCode::OK, r#"{"status":"UP"}"#.to_string())
        );
    }

    #[tokio::test]
    async fn message_for_unknown_session_is_rejected() {
        let request = Request::post("/mcp/message?sessionId=nope")
            .body(Body::from(r#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#))
            .unwrap();
        assert_eq!(send(request).await.0, StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn message_without_session_is_a_bad_request() {
        let request = Request::post("/mcp/message").body(Body::empty()).unwrap();
        assert_eq!(send(request).await.0, StatusCode::BAD_REQUEST);
    }
}
