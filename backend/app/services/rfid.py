"""Normalização e processamento de uma leitura de tag.

A regra aqui é: nunca rejeitar uma leitura por não entender o formato. Cartão
desconhecido é registrado e devolvido como `known=false`, com o dump inteiro
salvo do jeito que chegou.
"""

from datetime import datetime, timezone
from typing import Any

from sqlalchemy.orm import Session

from app.config import get_settings
from app.models import Scan
from app.repositories import cards as cards_repo
from app.repositories import scans as scans_repo
from app.schemas import ScanIn, ScanResult

# Nomes curtos para as classes de tecnologia do Android.
TECH_SHORT = {
    "android.nfc.tech.NfcA": "NfcA",
    "android.nfc.tech.NfcB": "NfcB",
    "android.nfc.tech.NfcF": "NfcF",
    "android.nfc.tech.NfcV": "NfcV",
    "android.nfc.tech.IsoDep": "IsoDep",
    "android.nfc.tech.Ndef": "Ndef",
    "android.nfc.tech.NdefFormatable": "NdefFormatable",
    "android.nfc.tech.MifareClassic": "MifareClassic",
    "android.nfc.tech.MifareUltralight": "MifareUltralight",
    "android.nfc.tech.NfcBarcode": "NfcBarcode",
}


def short_techs(tech_list: list[str] | None) -> list[str]:
    return [TECH_SHORT.get(t, t.rsplit(".", 1)[-1]) for t in (tech_list or [])]


def normalize_uid(raw: str | None) -> str:
    if not raw:
        return ""
    return "".join(ch for ch in raw.upper() if ch in "0123456789ABCDEF")


def describe_tag(dump: dict[str, Any]) -> str:
    """Tipo legível da tag. O app manda o palpite dele em `guess`; se não vier,
    a gente deduz pelo que existe no dump."""
    guess = dump.get("guess")
    if isinstance(guess, str) and guess.strip():
        return guess.strip()

    techs = set(short_techs(dump.get("tech_list")))

    classic = dump.get("mifare_classic") or {}
    if classic:
        size = classic.get("size") or 0
        kb = f"{size // 1024}K" if size >= 1024 else f"{size}B"
        return f"MIFARE Classic {kb}"

    ultralight = dump.get("mifare_ultralight") or {}
    if ultralight:
        return ultralight.get("type") or "MIFARE Ultralight"

    if "NfcV" in techs:
        return "ISO 15693 (NfcV)"
    if "NfcF" in techs:
        return "FeliCa (NfcF)"
    if "NfcB" in techs:
        return "ISO 14443-4 Type B"
    if "IsoDep" in techs:
        return "ISO 14443-4 Type A (smartcard)"
    if "NfcA" in techs:
        return "ISO 14443-3 Type A"
    if "NfcBarcode" in techs:
        return "NFC Barcode (Kovio)"
    return "desconhecida"


def _as_utc(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def process_scan(
    session: Session,
    payload: ScanIn,
    *,
    device_fallback: str = "desconhecido",
    source: str = "ws",
) -> tuple[Scan, ScanResult]:
    settings = get_settings()
    dump = payload.dump or {}

    uid = normalize_uid(payload.uid or dump.get("uid"))
    if not uid:
        # Sem UID ainda é uma leitura válida para diagnóstico — carimbamos como
        # SEM-UID para não perder o registro.
        uid = "SEM-UID"

    device = payload.device or dump.get("device") or device_fallback
    tag_type = describe_tag(dump)
    techs = short_techs(dump.get("tech_list"))
    now = datetime.now(timezone.utc)
    scanned_at = _as_utc(payload.scanned_at) or now

    card = cards_repo.get_by_uid(session, uid)
    known = card is not None

    if card is None and settings.auto_register_cards and uid != "SEM-UID":
        card = cards_repo.create(session, uid, authorized=False, tag_type=tag_type)

    if card is not None:
        cards_repo.touch(session, card, scanned_at, tag_type)

    authorized = bool(card and card.authorized)
    action = (
        (card.action if card and card.action else settings.action_authorized)
        if authorized
        else settings.action_denied
    )

    read_ms = dump.get("read_ms")
    scan = Scan(
        uid=uid,
        device=str(device)[:120],
        tag_type=tag_type,
        tech_list=",".join(techs) or None,
        authorized=authorized,
        known=known,
        source=source,
        read_ms=read_ms if isinstance(read_ms, int) else None,
        scanned_at=scanned_at,
        received_at=now,
        dump=dump,
    )
    if card is not None:
        scan.card_id = card.id
    scans_repo.add(session, scan)
    session.commit()

    label = card.label if card else None
    if authorized:
        message = f"Bem-vindo {label}" if label else "Autorizado"
    elif known and label:
        message = f"{label} — não autorizado"
    elif known:
        message = "Cartão conhecido, não autorizado"
    else:
        message = "Cartão novo registrado"

    result = ScanResult(
        id=payload.id,
        status="ok",
        scan_id=scan.id,
        uid=uid,
        tag_type=tag_type,
        known=known,
        authorized=authorized,
        label=label,
        action=action,
        scan_count=card.scan_count if card else 0,
        message=message,
    )
    return scan, result


def scan_event(scan: Scan) -> dict[str, Any]:
    """Formato enviado aos dashboards em tempo real."""
    return {
        "type": "scan",
        "scan": {
            "id": scan.id,
            "uid": scan.uid,
            "device": scan.device,
            "tag_type": scan.tag_type,
            "tech_list": scan.tech_list,
            "authorized": scan.authorized,
            "known": scan.known,
            "read_ms": scan.read_ms,
            "received_at": scan.received_at.isoformat(),
            "dump": scan.dump,
        },
    }
