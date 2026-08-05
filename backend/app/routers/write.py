from fastapi import APIRouter, HTTPException

from app.schemas import WriteJobIn
from app.services.hub import hub

router = APIRouter(prefix="/api", tags=["write"])


@router.post("/write")
async def armar_gravacao(payload: WriteJobIn) -> dict:
    """Arma a gravação no celular. Nada é gravado aqui: o comando fica esperando
    a próxima tag que encostar no aparelho, e o app mostra o aviso na tela."""
    if not hub.devices:
        raise HTTPException(status_code=409, detail="nenhum celular conectado")

    if payload.mode == "classic_block":
        limpo = (payload.hex or "").replace(" ", "").upper()
        if len(limpo) != 32:
            raise HTTPException(status_code=422, detail="um bloco precisa de 32 dígitos hex")
        try:
            bytes.fromhex(limpo)
        except ValueError:
            raise HTTPException(status_code=422, detail="hex inválido")
        payload.hex = limpo
    elif not (payload.conteudo or "").strip():
        raise HTTPException(status_code=422, detail="conteúdo vazio")

    comando = payload.model_dump()
    comando["type"] = "write_job"
    entregues = await hub.send_to_devices(comando)
    if not entregues:
        raise HTTPException(status_code=409, detail="não deu para falar com o celular")

    await hub.broadcast_dashboards({"type": "write_armed", "job": comando, "devices": entregues})
    return {"status": "armado", "devices": entregues, "job": comando}


@router.delete("/write")
async def desarmar_gravacao() -> dict:
    entregues = await hub.send_to_devices({"type": "write_cancel"})
    await hub.broadcast_dashboards({"type": "write_canceled"})
    return {"status": "desarmado", "devices": entregues}
