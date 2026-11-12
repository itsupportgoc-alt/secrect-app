import secrets
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException

from .config import Settings
from .models import WebhookPayload
from .push import PushSender, build_push_sender
from .supabase_store import SupabaseStore

PREVIEW_LIMIT = 140


def create_app(
    settings: Settings | None = None,
    store: SupabaseStore | None = None,
    push: PushSender | None = None,
) -> FastAPI:
    settings = settings or Settings()
    store = store or SupabaseStore(
        settings.supabase_url,
        settings.supabase_service_role_key,
    )
    push = push if push is not None else build_push_sender(settings)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        await store.close()
        if push is not None:
            await push.close()

    app = FastAPI(
        title="Secret Chat push service",
        version="1.0.0",
        lifespan=lifespan,
    )

    def verify_secret(x_webhook_secret: str | None = Header(default=None)) -> None:
        if not x_webhook_secret or not secrets.compare_digest(
            x_webhook_secret, settings.webhook_secret
        ):
            raise HTTPException(status_code=401, detail="invalid webhook secret")

    @app.get("/healthz")
    async def healthz() -> dict[str, object]:
        return {"status": "ok", "push_configured": push is not None}

    @app.post("/webhooks/new-message")
    async def new_message(
        payload: WebhookPayload,
        _: None = Depends(verify_secret),
    ) -> dict[str, object]:
        if payload.type != "INSERT" or payload.table != "messages":
            return {"skipped": True, "reason": "not a message insert"}

        record = payload.record
        members = await store.conversation_members(record.conversation_id)
        recipients = [user for user in members if user != record.sender_id]
        tokens = await store.device_tokens(recipients)

        if not tokens:
            return {"sent": 0, "tokens": 0}
        if push is None:
            raise HTTPException(status_code=503, detail="push is not configured")

        title = await store.sender_name(record.sender_id)
        preview = record.body
        if len(preview) > PREVIEW_LIMIT:
            preview = preview[: PREVIEW_LIMIT - 1] + "…"

        sent = await push.send(
            tokens,
            title,
            preview,
            {
                "conversation_id": record.conversation_id,
                "message_id": record.id,
            },
        )
        return {"sent": sent, "tokens": len(tokens)}

    return app


app = create_app()
