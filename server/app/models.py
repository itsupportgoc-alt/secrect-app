from pydantic import BaseModel, ConfigDict, Field


class MessageRecord(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str
    conversation_id: str
    sender_id: str
    body: str
    created_at: str | None = None


class WebhookPayload(BaseModel):
    """Payload sent by a Supabase database webhook on INSERT."""

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    type: str
    table: str
    schema_name: str = Field(alias="schema")
    record: MessageRecord
