import json
import logging
from typing import Any, Protocol

import httpx
from google.auth.transport.requests import Request as GoogleAuthRequest
from google.oauth2 import service_account

from .config import Settings

logger = logging.getLogger(__name__)

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


class PushSender(Protocol):
    async def send(
        self,
        tokens: list[str],
        title: str,
        body: str,
        data: dict[str, str],
    ) -> int: ...

    async def close(self) -> None: ...


class FcmSender:
    """Sends one FCM HTTP v1 message per device token."""

    def __init__(
        self,
        project_id: str,
        service_account_info: dict[str, Any],
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._project_id = project_id
        self._credentials = service_account.Credentials.from_service_account_info(
            service_account_info,
            scopes=[FCM_SCOPE],
        )
        self._client = client or httpx.AsyncClient(timeout=10.0)
        self._owns_client = client is None

    def _access_token(self) -> str:
        if not self._credentials.valid or self._credentials.expired:
            self._credentials.refresh(GoogleAuthRequest())
        return self._credentials.token

    async def send(
        self,
        tokens: list[str],
        title: str,
        body: str,
        data: dict[str, str],
    ) -> int:
        if not tokens:
            return 0

        access_token = self._access_token()
        url = f"https://fcm.googleapis.com/v1/projects/{self._project_id}/messages:send"
        headers = {"Authorization": f"Bearer {access_token}"}
        sent = 0

        for device_token in tokens:
            payload = {
                "message": {
                    "token": device_token,
                    "notification": {"title": title, "body": body},
                    "data": data,
                    "android": {"priority": "high"},
                }
            }
            response = await self._client.post(url, headers=headers, json=payload)
            if response.status_code == 200:
                sent += 1
            else:
                logger.warning(
                    "FCM rejected token %s: %s %s",
                    device_token[:8],
                    response.status_code,
                    response.text[:200],
                )
        return sent

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()


def build_push_sender(settings: Settings) -> PushSender | None:
    """Returns None when Firebase is not configured; push is then disabled."""
    if not settings.push_configured:
        return None

    raw: str | None = None
    if settings.fcm_service_account_json:
        raw = settings.fcm_service_account_json
    elif settings.google_application_credentials:
        with open(settings.google_application_credentials, encoding="utf-8") as file:
            raw = file.read()

    if not raw:
        return None

    try:
        info = json.loads(raw)
    except json.JSONDecodeError:
        logger.exception("FCM service account JSON is invalid")
        return None

    return FcmSender(settings.firebase_project_id, info)
