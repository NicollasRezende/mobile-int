from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "mobile-int RFID Hub"
    database_url: str = "sqlite:///./data/rfid.db"
    device_token: str = "troque-este-token"
    auto_register_cards: bool = True
    action_authorized: str = "unlock"
    action_denied: str = "deny"
    log_level: str = "info"

    # Um dump completo de MIFARE Classic 4K passa de 100 KB em hex.
    max_payload_bytes: int = 1_048_576


@lru_cache
def get_settings() -> Settings:
    return Settings()
