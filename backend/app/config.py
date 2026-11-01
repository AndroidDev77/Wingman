"""Runtime configuration loaded from environment variables (prefix WINGMAN_)."""

from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WINGMAN_", env_file=".env", extra="ignore")

    env: Literal["dev", "test", "prod"] = "dev"
    database_url: str = "postgresql+asyncpg://wingman:wingman@localhost:5432/wingman"
    redis_url: str = "redis://localhost:6379/0"
    # "mock" lets the whole backend run with no model credentials.
    llm_provider: Literal["mock", "openai", "anthropic"] = "mock"
    log_level: str = Field(default="INFO")


@lru_cache
def get_settings() -> Settings:
    return Settings()
