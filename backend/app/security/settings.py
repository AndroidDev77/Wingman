"""Auth settings (env prefix ``WINGMAN_AUTH_``).

Kept inside ``app.security`` so WING-004 does not edit ``app/config.py``. The runtime
environment (``dev``/``test``/``prod``) still comes from ``Settings.env``
(``WINGMAN_ENV``); it is passed in explicitly wherever it matters.
"""

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# Algorithms an OIDC provider may sign access tokens with. Symmetric (HS*) algorithms
# are deliberately absent: a shared secret cannot be published in a JWKS, and allowing
# them next to RSA/EC keys is the classic "alg confusion" hole.
ASYMMETRIC_ALGORITHMS = frozenset(
    {
        "RS256",
        "RS384",
        "RS512",
        "PS256",
        "PS384",
        "PS512",
        "ES256",
        "ES384",
        "ES512",
        "EdDSA",
    }
)
# Only the dev stub signs with a shared secret.
DEV_STUB_ALGORITHM = "HS256"
MIN_DEV_SECRET_LENGTH = 32


class AuthSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="WINGMAN_AUTH_", env_file=".env", extra="ignore")

    # ---- OIDC (real identity provider). All three are required to accept OIDC tokens.
    issuer: str | None = None
    audience: str | None = None
    jwks_url: str | None = None
    # Comma-separated allowlist, e.g. "RS256,ES256". Only asymmetric algorithms.
    algorithms: str = "RS256,ES256"
    # Clock-skew tolerance for exp / nbf / iat, in seconds.
    leeway_s: int = Field(default=30, ge=0, le=300)
    jwks_cache_ttl_s: int = Field(default=300, ge=10, le=86_400)
    # Minimum seconds between JWKS refetches triggered by an unknown ``kid``.
    jwks_min_refresh_s: int = Field(default=30, ge=0, le=3_600)
    jwks_timeout_s: float = Field(default=5.0, gt=0, le=60)

    # ---- Dev-only stub. Ignored unless WINGMAN_ENV is dev/test AND this is true.
    # With WINGMAN_ENV=prod, setting it to true makes the app refuse to start.
    dev_stub_enabled: bool = False
    dev_secret: SecretStr | None = None
    dev_issuer: str = "https://dev-auth.wingman.invalid"
    dev_audience: str = "wingman-dev"

    @field_validator("issuer", "audience", "jwks_url", "dev_issuer", "dev_audience")
    @classmethod
    def _strip_blank(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.strip()
        return v or None

    def algorithm_list(self) -> list[str]:
        """Parsed OIDC allowlist. Raises ``ValueError`` on anything not asymmetric."""
        algs = [a.strip() for a in self.algorithms.split(",") if a.strip()]
        if not algs:
            raise ValueError("WINGMAN_AUTH_ALGORITHMS must list at least one algorithm")
        bad = [a for a in algs if a not in ASYMMETRIC_ALGORITHMS]
        if bad:
            raise ValueError(
                f"WINGMAN_AUTH_ALGORITHMS contains disallowed algorithms {bad}; "
                f"allowed: {sorted(ASYMMETRIC_ALGORITHMS)}"
            )
        return algs

    @property
    def oidc_configured(self) -> bool:
        return bool(self.issuer and self.audience and self.jwks_url)
