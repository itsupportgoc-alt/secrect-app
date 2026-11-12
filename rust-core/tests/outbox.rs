use secretchat_core::{Outbox, MAX_BODY_CHARS};
use tempfile::tempdir;

#[test]
fn enqueue_persists_and_reloads() {
    let dir = tempdir().unwrap();
    let path = dir.path().join("outbox.json");

    let mut outbox = Outbox::open(&path).unwrap();
    let client_id = outbox.enqueue_at("conversation-1", "  hello there  ", 1_000).unwrap();
    assert_eq!(outbox.pending_count(), 1);

    let reopened = Outbox::open(&path).unwrap();
    let pending = reopened.pending();
    assert_eq!(pending.len(), 1);
    assert_eq!(pending[0].client_id, client_id);
    assert_eq!(pending[0].conversation_id, "conversation-1");
    assert_eq!(pending[0].body, "hello there");
    assert_eq!(pending[0].created_at_ms, 1_000);
    assert_eq!(pending[0].attempts, 0);
}

#[test]
fn due_is_ordered_and_respects_retry_time() {
    let dir = tempdir().unwrap();
    let mut outbox = Outbox::open(dir.path().join("outbox.json")).unwrap();
    let first = outbox.enqueue_at("conversation-1", "first", 2_000).unwrap();
    let second = outbox.enqueue_at("conversation-1", "second", 1_000).unwrap();

    let due = outbox.due(10_000);
    assert_eq!(due.len(), 2);
    assert_eq!(due[0].client_id, second);
    assert_eq!(due[1].client_id, first);

    outbox.mark_failed(&first, 10_000).unwrap();
    let due = outbox.due(10_500);
    assert_eq!(due.len(), 1, "failed message waits for its backoff");
    assert_eq!(due[0].client_id, second);
}

#[test]
fn backoff_grows_and_is_capped() {
    let dir = tempdir().unwrap();
    let mut outbox = Outbox::open(dir.path().join("outbox.json")).unwrap();
    let client_id = outbox.enqueue_at("conversation-1", "retry me", 0).unwrap();

    outbox.mark_failed(&client_id, 10_000).unwrap();
    let pending = &outbox.pending()[0];
    assert_eq!(pending.attempts, 1);
    assert_eq!(pending.next_attempt_at_ms, 11_000);

    outbox.mark_failed(&client_id, 20_000).unwrap();
    assert_eq!(outbox.pending()[0].next_attempt_at_ms, 22_000);

    for _ in 0..10 {
        outbox.mark_failed(&client_id, 30_000).unwrap();
    }
    assert_eq!(outbox.pending()[0].next_attempt_at_ms, 90_000);
}

#[test]
fn mark_sent_removes_message() {
    let dir = tempdir().unwrap();
    let mut outbox = Outbox::open(dir.path().join("outbox.json")).unwrap();
    let client_id = outbox.enqueue_at("conversation-1", "done", 0).unwrap();

    assert!(outbox.mark_sent(&client_id).unwrap());
    assert!(!outbox.mark_sent(&client_id).unwrap());
    assert_eq!(outbox.pending_count(), 0);
    assert_eq!(Outbox::open(dir.path().join("outbox.json")).unwrap().pending_count(), 0);
}

#[test]
fn rejects_invalid_messages_without_writing() {
    let dir = tempdir().unwrap();
    let path = dir.path().join("outbox.json");
    let mut outbox = Outbox::open(&path).unwrap();

    assert!(outbox.enqueue_at("conversation-1", "   ", 0).is_err());
    assert!(outbox.enqueue_at("", "hi", 0).is_err());
    let too_long = "a".repeat(MAX_BODY_CHARS + 1);
    assert!(outbox.enqueue_at("conversation-1", &too_long, 0).is_err());
    assert_eq!(outbox.pending_count(), 0);
}

#[test]
fn reopens_corrupt_file_as_error() {
    let dir = tempdir().unwrap();
    let path = dir.path().join("outbox.json");
    std::fs::write(&path, b"{not json").unwrap();
    assert!(Outbox::open(path).is_err());
}
