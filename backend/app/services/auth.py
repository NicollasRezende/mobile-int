import hmac

from fastapi import Header, HTTPException, Query, status

from app.config import get_settings


def token_is_valid(token: str | None) -> bool:
    if not token:
        return False
    return hmac.compare_digest(token, get_settings().device_token)


def extract_token(
    query_token: str | None = None,
    header_token: str | None = None,
    authorization: str | None = None,
) -> str | None:
    if query_token:
        return query_token
    if header_token:
        return header_token
    if authorization and authorization.lower().startswith("bearer "):
        return authorization[7:]
    return None


def require_device_token(
    token: str | None = Query(default=None),
    x_device_token: str | None = Header(default=None),
    authorization: str | None = Header(default=None),
) -> str:
    """Dependency para as rotas HTTP de ingestão."""
    candidate = extract_token(token, x_device_token, authorization)
    if not token_is_valid(candidate):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="token inválido"
        )
    return candidate  # type: ignore[return-value]
