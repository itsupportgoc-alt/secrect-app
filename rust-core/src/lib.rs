//! Offline outbox shared by the Android client.
//!
//! Messages are written to disk before they are sent to Supabase. Each message
//! carries a client generated UUID that is used as an idempotency key by the
//! `send_message` RPC, so a retry can never duplicate a message.

use serde::{Deserialize, Serialize};
use std::fmt;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};
use uuid::Uuid;

pub mod jni_bridge;

/// Keep in sync with the `messages.body` check constraint.
pub const MAX_BODY_CHARS: usize = 8000;
const FILE_VERSION: u32 = 1;
const BASE_BACKOFF_MS: i64 = 1_000;
const MAX_BACKOFF_MS: i64 = 60_000;

#[derive(Debug)]
pub enum OutboxError {
    Io(io::Error),
    Json(serde_json::Error),
    Invalid(String),
}

impl fmt::Display for OutboxError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            OutboxError::Io(err) => write!(f, "outbox io error: {err}"),
            OutboxError::Json(err) => write!(f, "outbox format error: {err}"),
            OutboxError::Invalid(message) => write!(f, "invalid message: {message}"),
        }
    }
}

impl std::error::Error for OutboxError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            OutboxError::Io(err) => Some(err),
            OutboxError::Json(err) => Some(err),
            OutboxError::Invalid(_) => None,
        }
    }
}

impl From<io::Error> for OutboxError {
    fn from(err: io::Error) -> Self {
        OutboxError::Io(err)
    }
}

impl From<serde_json::Error> for OutboxError {
    fn from(err: serde_json::Error) -> Self {
        OutboxError::Json(err)
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PendingMessage {
    pub client_id: String,
    pub conversation_id: String,
    pub body: String,
    pub created_at_ms: i64,
    pub attempts: u32,
    pub next_attempt_at_ms: i64,
}

#[derive(Serialize, Deserialize)]
struct OutboxFile {
    version: u32,
    messages: Vec<PendingMessage>,
}

pub struct Outbox {
    path: PathBuf,
    messages: Vec<PendingMessage>,
}

impl Outbox {
    /// Opens the outbox at `path`, creating an empty one if the file is absent.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, OutboxError> {
        let path = path.as_ref().to_path_buf();
        let messages = if path.exists() {
            let raw = fs::read(&path)?;
            if raw.is_empty() {
                Vec::new()
            } else {
                let file: OutboxFile = serde_json::from_slice(&raw)?;
                if file.version > FILE_VERSION {
                    return Err(OutboxError::Invalid(format!(
                        "outbox version {} is newer than supported version {FILE_VERSION}",
                        file.version
                    )));
                }
                file.messages
            }
        } else {
            Vec::new()
        };
        Ok(Self { path, messages })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Queues a message and returns its client id (idempotency key).
    pub fn enqueue(
        &mut self,
        conversation_id: &str,
        body: &str,
    ) -> Result<String, OutboxError> {
        self.enqueue_at(conversation_id, body, now_ms())
    }

    /// Same as [`Outbox::enqueue`] with an explicit clock, for tests.
    pub fn enqueue_at(
        &mut self,
        conversation_id: &str,
        body: &str,
        now_ms: i64,
    ) -> Result<String, OutboxError> {
        let conversation_id = conversation_id.trim();
        if conversation_id.is_empty() {
            return Err(OutboxError::Invalid("conversation id is empty".into()));
        }
        let body = body.trim();
        if body.is_empty() {
            return Err(OutboxError::Invalid("body is empty".into()));
        }
        if body.chars().count() > MAX_BODY_CHARS {
            return Err(OutboxError::Invalid(format!(
                "body is longer than {MAX_BODY_CHARS} characters"
            )));
        }

        let client_id = Uuid::new_v4().to_string();
        self.messages.push(PendingMessage {
            client_id: client_id.clone(),
            conversation_id: conversation_id.to_string(),
            body: body.to_string(),
            created_at_ms: now_ms,
            attempts: 0,
            next_attempt_at_ms: 0,
        });
        self.persist()?;
        Ok(client_id)
    }

    /// Messages whose retry time has arrived, oldest first.
    pub fn due(&self, now_ms: i64) -> Vec<PendingMessage> {
        let mut due: Vec<_> = self
            .messages
            .iter()
            .filter(|message| message.next_attempt_at_ms <= now_ms)
            .cloned()
            .collect();
        due.sort_by(|a, b| {
            a.created_at_ms
                .cmp(&b.created_at_ms)
                .then_with(|| a.client_id.cmp(&b.client_id))
        });
        due
    }

    /// Everything still queued, oldest first.
    pub fn pending(&self) -> Vec<PendingMessage> {
        let mut pending = self.messages.clone();
        pending.sort_by(|a, b| {
            a.created_at_ms
                .cmp(&b.created_at_ms)
                .then_with(|| a.client_id.cmp(&b.client_id))
        });
        pending
    }

    pub fn pending_count(&self) -> usize {
        self.messages.len()
    }

    /// Removes a delivered message. Returns `false` if it was already gone.
    pub fn mark_sent(&mut self, client_id: &str) -> Result<bool, OutboxError> {
        let before = self.messages.len();
        self.messages.retain(|message| message.client_id != client_id);
        let removed = self.messages.len() != before;
        if removed {
            self.persist()?;
        }
        Ok(removed)
    }

    /// Records a failed delivery and schedules the next attempt with capped
    /// exponential backoff (1s, 2s, 4s, ... 60s).
    pub fn mark_failed(&mut self, client_id: &str, now_ms: i64) -> Result<(), OutboxError> {
        let Some(message) = self
            .messages
            .iter_mut()
            .find(|message| message.client_id == client_id)
        else {
            return Ok(());
        };
        message.attempts = message.attempts.saturating_add(1);
        let exponent = message.attempts.saturating_sub(1).min(6);
        let delay = BASE_BACKOFF_MS
            .saturating_mul(1_i64 << exponent)
            .min(MAX_BACKOFF_MS);
        message.next_attempt_at_ms = now_ms.saturating_add(delay);
        self.persist()
    }

    fn persist(&self) -> Result<(), OutboxError> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let payload = OutboxFile {
            version: FILE_VERSION,
            messages: self.messages.clone(),
        };
        let json = serde_json::to_vec_pretty(&payload)?;
        let tmp = self.path.with_extension("json.tmp");
        fs::write(&tmp, json)?;
        // Windows cannot rename over an existing file, Android can.
        #[cfg(windows)]
        if self.path.exists() {
            fs::remove_file(&self.path)?;
        }
        fs::rename(&tmp, &self.path)?;
        Ok(())
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as i64)
        .unwrap_or(0)
}
