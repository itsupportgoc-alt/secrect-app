from typing import Any

import httpx


class SupabaseStore:
    """Reads recipients and device tokens with the service role key.

    This key bypasses row level security, so it must only ever live on the
    server. All queries below are read-only.
    """

    def __init__(
        self,
        base_url: str,
        service_role_key: str,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._key = service_role_key
        self._client = client or httpx.AsyncClient(timeout=10.0)
        self._owns_client = client is None

    def _headers(self) -> dict[str, str]:
        return {
            "apikey": self._key,
            "Authorization": f"Bearer {self._key}",
        }

    async def _get(self, table: str, params: dict[str, str]) -> list[dict[str, Any]]:
        response = await self._client.get(
            f"{self._base_url}/rest/v1/{table}",
            params=params,
            headers=self._headers(),
        )
        response.raise_for_status()
        data = response.json()
        return data if isinstance(data, list) else []

    async def conversation_members(self, conversation_id: str) -> list[str]:
        rows = await self._get(
            "conversation_members",
            {"select": "user_id", "conversation_id": f"eq.{conversation_id}"},
        )
        return [row["user_id"] for row in rows]

    async def device_tokens(self, user_ids: list[str]) -> list[str]:
        if not user_ids:
            return []
        rows = await self._get(
            "device_tokens",
            {"select": "token", "user_id": f"in.({','.join(user_ids)})"},
        )
        return [row["token"] for row in rows]

    async def sender_name(self, user_id: str) -> str:
        rows = await self._get(
            "profiles",
            {"select": "username,display_name", "id": f"eq.{user_id}", "limit": "1"},
        )
        if not rows:
            return "New message"
        row = rows[0]
        return row.get("display_name") or f"@{row.get('username', 'someone')}"

    async def close(self) -> None:
        if self._owns_client:
            await self._client.aclose()
