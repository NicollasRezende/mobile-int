#!/usr/bin/env python3
"""Simula o celular: manda dumps de exemplo pelo WebSocket, sem precisar de NFC.

    python tools/fake_scan.py                      # manda os 4 exemplos
    python tools/fake_scan.py --host 192.168.0.10  # outro servidor
    python tools/fake_scan.py --loop 3             # repete 3x
"""

import argparse
import asyncio
import json
import os
import uuid
from datetime import datetime, timezone

import websockets

EXAMPLES: list[dict] = [
    {
        "guess": "NTAG215",
        "uid": "04A91B22C75E80",
        "uid_len": 7,
        "tech_list": [
            "android.nfc.tech.NfcA",
            "android.nfc.tech.MifareUltralight",
            "android.nfc.tech.Ndef",
        ],
        "nfca": {"atqa": "0044", "sak": "00", "max_transceive": 253, "timeout": 618},
        "ndef": {
            "type": "org.nfcforum.ndef.type2",
            "max_size": 492,
            "writable": True,
            "can_make_read_only": True,
            "message_size": 21,
            "records": [
                {
                    "tnf": 1,
                    "tnf_name": "WELL_KNOWN",
                    "type": "54",
                    "type_text": "T (texto)",
                    "payload_hex": "02707442656d2d76696e646f204e69636f6c6c6173",
                    "decoded": "Bem-vindo Nicollas",
                }
            ],
        },
        "mifare_ultralight": {
            "type": "ULTRALIGHT",
            "version": "0004040201000f03",
            "pages": [
                {"index": 0, "data": "04a91b22"},
                {"index": 1, "data": "c75e8000"},
                {"index": 2, "data": "48480000"},
                {"index": 3, "data": "e1103e00"},
            ],
        },
        "errors": [],
        "read_ms": 187,
    },
    {
        "guess": "MIFARE Classic 1K",
        "uid": "A3B41C2D",
        "uid_len": 4,
        "tech_list": ["android.nfc.tech.NfcA", "android.nfc.tech.MifareClassic"],
        "nfca": {"atqa": "0400", "sak": "08", "max_transceive": 253, "timeout": 618},
        "mifare_classic": {
            "type": "CLASSIC",
            "size": 1024,
            "sector_count": 16,
            "block_count": 64,
            "sectors_read": 2,
            "sectors": [
                {
                    "index": 0,
                    "authenticated": True,
                    "key_type": "A",
                    "key_used": "FFFFFFFFFFFF",
                    "blocks": [
                        "a3b41c2d44080400" + "0000000000000000",
                        "00000000000000000000000000000000",
                        "00000000000000000000000000000000",
                        "000000000000ff078069ffffffffffff",
                    ],
                },
                {
                    "index": 1,
                    "authenticated": False,
                    "key_type": None,
                    "key_used": None,
                    "blocks": [],
                    "error": "nenhuma chave default funcionou",
                },
            ],
        },
        "errors": ["setor 1: autenticação falhou com as 12 chaves default"],
        "read_ms": 1420,
    },
    {
        "guess": "ISO 14443-4 Type A (smartcard)",
        "uid": "08A7F31C",
        "uid_len": 4,
        "tech_list": ["android.nfc.tech.NfcA", "android.nfc.tech.IsoDep"],
        "nfca": {"atqa": "0400", "sak": "20", "max_transceive": 253, "timeout": 618},
        "isodep": {
            "historical_bytes": "80318065b0850300ef12",
            "hi_layer_response": None,
            "max_transceive": 261,
            "extended_length_apdu_supported": False,
            "apdu_probes": [
                {
                    "name": "SELECT PPSE (2PAY.SYS.DDF01)",
                    "command": "00A404000E325041592E5359532E4444463031",
                    "response": "6f2c840e325041592e5359532e4444463031",
                    "sw": "9000",
                    "note": "cartão respondeu com FCI — é um cartão de pagamento",
                },
                {
                    "name": "GET UID (PC/SC)",
                    "command": "FFCA000000",
                    "response": "",
                    "sw": "6A81",
                    "note": "não suportado",
                },
            ],
        },
        "errors": [],
        "read_ms": 640,
    },
    {
        "guess": "ISO 15693 (NfcV)",
        "uid": "E0040150A1B2C3D4",
        "uid_len": 8,
        "tech_list": ["android.nfc.tech.NfcV"],
        "nfcv": {
            "dsf_id": "00",
            "response_flags": "00",
            "blocks": [
                {"index": 0, "data": "00000000"},
                {"index": 1, "data": "deadbeef"},
            ],
        },
        "errors": [],
        "read_ms": 310,
    },
]


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--token", default=os.getenv("DEVICE_TOKEN", "troque-este-token"))
    parser.add_argument("--device", default="celular-fake")
    parser.add_argument("--loop", type=int, default=1)
    parser.add_argument("--delay", type=float, default=0.6)
    args = parser.parse_args()

    url = f"ws://{args.host}:{args.port}/ws/device?token={args.token}&device={args.device}"
    async with websockets.connect(url) as ws:
        print("←", await ws.recv())
        for _ in range(args.loop):
            for dump in EXAMPLES:
                msg = {
                    "type": "scan",
                    "id": str(uuid.uuid4()),
                    "device": args.device,
                    "scanned_at": datetime.now(timezone.utc).isoformat(),
                    "dump": dump,
                }
                await ws.send(json.dumps(msg))
                reply = json.loads(await ws.recv())
                print(
                    f"→ {dump['uid']:<18} {reply['tag_type']:<32} "
                    f"conhecido={reply['known']} autorizado={reply['authorized']} "
                    f"scan_id={reply['scan_id']}"
                )
                await asyncio.sleep(args.delay)


if __name__ == "__main__":
    asyncio.run(main())
