#!/usr/bin/env bash
# Sobe o backend na rede local (0.0.0.0 para o celular enxergar).
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d .venv ]; then
    echo "criando venv…"
    python3 -m venv .venv
    .venv/bin/pip install -q -r requirements.txt
fi

[ -f .env ] || cp .env.example .env

echo
echo "Painel:  http://localhost:8000"
ip -4 addr show scope global 2>/dev/null | awk '/inet /{split($2,a,"/"); print "         http://" a[1] ":8000  ← use este IP no celular"}'
echo

exec .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
