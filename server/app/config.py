from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Configuration is read from environment variables or a local .env file."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    supabase_url: str = ""
    supabase_service_role_key: str = ""
    webhook_secret: str = "change-me"

    firebase_project_id: str = ""
    # Either a path to the service account JSON or the JSON itself.
    google_application_credentials: str | None = None
    fcm_service_account_json: str | None = None

    @property
    def push_configured(self) -> bool:
        return bool(
            self.firebase_project_id
            and (self.google_application_credentials or self.fcm_service_account_json)
        )
