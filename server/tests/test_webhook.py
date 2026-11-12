from typing import Any

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app

SECRET = "test-webhook-secret"


class FakeStore:
    def __init__(
        self,
        members: list[str] | None = None,
        tokens: list[str] | None = None,
        sender_name: str = "Alice",
    ) -> None:
        self.members = members or []
        self.tokens = tokens or []
        self.name = sender_name
        self.requested_tokens_for: list[str] | None = None
        self.closed = False

    async def conversation_members(self, conversation_id: str) -> list[str]:
        return self.members

    async def device_tokens(self, user_ids: list[str]) -> list[str]:
        self.requested_tokens_for = user_ids
        return self.tokens

    async def sender_name(self, user_id: str) -> str:
        return self.name

    async def close(self) -> None:
        self.closed = True


class FakePush:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []
        self.closed = False

    async def send(
        self,
        tokens: list[str],
        title: str,
        body: str,
        data: dict[str, str],
    ) -> int:
        self.calls.append({"tokens": tokens, "title": title, "body": body, "data": data})
        return len(tokens)

    async def close(self) -> None:
        self.closed = True


def make_client(store: FakeStore, push: FakePush) -> TestClient:
    settings = Settings(
        supabase_url="https://example.supabase.co",
        supabase_service_role_key="service-role-key",
        webhook_secret=SECRET,
        firebase_project_id="demo-project",
        fcm_service_account_json='{"type": "service_account"}',
    )
    return TestClient(create_app(settings=settings, store=store, push=push))


def payload(
    *,
    event_type: str = "INSERT",
    table: str = "messages",
    sender: str = "sender-1",
    body: str = "Hello there",
) -> dict[str, Any]:
    return {
        "type": event_type,
        "table": table,
        "schema": "public",
        "record": {
            "id": "message-1",
            "conversation_id": "conversation-1",
            "sender_id": sender,
            "body": body,
        },
    }


def test_health_reports_push_configuration() -> None:
    response = make_client(FakeStore(), FakePush()).get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok", "push_configured": True}


def test_rejects_missing_or_wrong_secret() -> None:
    client = make_client(FakeStore(), FakePush())
    assert client.post("/webhooks/new-message", json=payload()).status_code == 401
    assert (
        client.post(
            "/webhooks/new-message",
            json=payload(),
            headers={"x-webhook-secret": "wrong"},
        ).status_code
        == 401
    )


def test_skips_non_insert_events() -> None:
    push = FakePush()
    response = make_client(FakeStore(), push).post(
        "/webhooks/new-message",
        json=payload(event_type="UPDATE"),
        headers={"x-webhook-secret": SECRET},
    )
    assert response.status_code == 200
    assert response.json()["skipped"] is True
    assert push.calls == []


def test_notifies_every_member_except_sender() -> None:
    store = FakeStore(members=["sender-1", "other-1"], tokens=["token-1"])
    push = FakePush()
    response = make_client(store, push).post(
        "/webhooks/new-message",
        json=payload(),
        headers={"x-webhook-secret": SECRET},
    )

    assert response.status_code == 200
    assert response.json() == {"sent": 1, "tokens": 1}
    assert store.requested_tokens_for == ["other-1"]
    assert push.calls == [
        {
            "tokens": ["token-1"],
            "title": "Alice",
            "body": "Hello there",
            "data": {
                "conversation_id": "conversation-1",
                "message_id": "message-1",
            },
        }
    ]


def test_preview_is_truncated() -> None:
    push = FakePush()
    response = make_client(
        FakeStore(members=["sender-1", "other-1"], tokens=["token-1"]),
        push,
    ).post(
        "/webhooks/new-message",
        json=payload(body="x" * 200),
        headers={"x-webhook-secret": SECRET},
    )

    assert response.status_code == 200
    assert len(push.calls[0]["body"]) == 140
    assert push.calls[0]["body"].endswith("…")


def test_does_nothing_without_tokens() -> None:
    push = FakePush()
    response = make_client(
        FakeStore(members=["sender-1", "other-1"], tokens=[]),
        push,
    ).post(
        "/webhooks/new-message",
        json=payload(),
        headers={"x-webhook-secret": SECRET},
    )

    assert response.status_code == 200
    assert response.json() == {"sent": 0, "tokens": 0}
    assert push.calls == []


def test_rejects_invalid_payload() -> None:
    response = make_client(FakeStore(), FakePush()).post(
        "/webhooks/new-message",
        json={"type": "INSERT", "table": "messages", "schema": "public"},
        headers={"x-webhook-secret": SECRET},
    )
    assert response.status_code == 422
