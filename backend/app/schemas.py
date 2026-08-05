from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class ScanIn(BaseModel):
    """Payload que o celular manda. Propositalmente permissivo: o objetivo é
    receber qualquer coisa que a tag devolver, mesmo formatos que ainda não
    mapeamos. Só o UID é obrigatório (pode vir no topo ou dentro de `dump`)."""

    model_config = ConfigDict(extra="allow")

    type: str = "scan"
    id: str | None = None
    uid: str | None = None
    device: str | None = None
    scanned_at: datetime | None = None
    dump: dict[str, Any] = Field(default_factory=dict)


class ScanResult(BaseModel):
    """Resposta devolvida ao celular."""

    type: str = "scan_result"
    id: str | None = None
    status: str = "ok"
    scan_id: int | None = None
    uid: str = ""
    tag_type: str | None = None
    known: bool = False
    authorized: bool = False
    label: str | None = None
    action: str | None = None
    scan_count: int = 0
    message: str = ""


class ScanOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    uid: str
    device: str | None
    tag_type: str | None
    tech_list: str | None
    authorized: bool
    known: bool
    source: str
    read_ms: int | None
    scanned_at: datetime | None
    received_at: datetime
    dump: dict[str, Any]


class ScanSummary(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    uid: str
    device: str | None
    tag_type: str | None
    tech_list: str | None
    authorized: bool
    known: bool
    read_ms: int | None
    received_at: datetime


class WriteJobIn(BaseModel):
    """Comando de gravação enviado do painel para o celular."""

    mode: Literal["ndef_text", "classic_text", "classic_block"]
    conteudo: str | None = None
    como_uri: bool = False
    setor: int | None = None
    bloco: int | None = None
    hex: str | None = None


class CardIn(BaseModel):
    uid: str
    label: str | None = None
    authorized: bool = False
    action: str | None = None
    notes: str | None = None


class CardPatch(BaseModel):
    label: str | None = None
    authorized: bool | None = None
    action: str | None = None
    notes: str | None = None


class CardOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    uid: str
    label: str | None
    authorized: bool
    action: str | None
    notes: str | None
    tag_type: str | None
    scan_count: int
    created_at: datetime
    updated_at: datetime
    last_seen_at: datetime | None
