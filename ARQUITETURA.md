# Arquitetura — como uma leitura vira uma decisão

Documento técnico do caminho completo: da antena NFC do celular até o registro no
banco e a resposta de volta. Se você quer instalar e rodar, o `README.md` cobre
isso; aqui é **por que cada peça está onde está**.

> A mesma coisa em formato de apresentação, com diagramas e navegação:
> **[Anatomia do mobile-int](https://claude.ai/code/artifact/46d19ead-cb4b-47c7-b248-55191948d66e)**.

---

## O problema

Um celular Android lê uma tag NFC e precisa que um servidor decida, em tempo de
"encostar o cartão", se aquele UID está autorizado. Três exigências que moldaram
tudo:

1. **Latência baixa.** A pessoa está com o cartão na mão. Resposta em centenas de
   milissegundos, não em segundos.
2. **O servidor precisa falar primeiro.** O painel do PC arma uma gravação e o
   comando tem que chegar ao celular sem que ele pergunte. Isso mata "só HTTP".
3. **Nunca perder uma leitura.** Wi-Fi doméstico cai. O dump de uma tag que já
   saiu do campo não se recupera.

---

## Desenho

```
   tag NFC          Android (Kotlin)                    FastAPI (Python)
     │                                                        │
     │  ISO 14443   ┌──────────────┐                          │
     ├─────────────▶│  TagDumper   │                          │
     │              └──────┬───────┘                          │
     │                     │ TagDump (JSON)                   │
     │              ┌──────▼───────┐                          │
     │              │ ScanViewModel│                          │
     │              └──────┬───────┘                          │
     │                     │ {type, id, device, scanned_at, dump}
     │              ┌──────▼───────┐   ws://…/ws/device   ┌────▼─────┐
     │              │  ScanClient  │◀════════════════════▶│ routers/ │
     │              │ fila+backoff │      full-duplex     │  ws.py   │
     │              └──────────────┘                      └────┬─────┘
     │                                                         │
     │                                    ┌────────────────────▼─────────────┐
     │                                    │ services/rfid.py  (regra)        │
     │                                    │ repositories/     (dados)        │
     │                                    │ models.py         (SQLAlchemy)   │
     │                                    └────────────────────┬─────────────┘
     │                                                         │
     │              {authorized, label, action, message} ◀─────┤
     │                                                         │
                                          painel web  ◀════════┘
                                       ws://…/ws/dashboard  (broadcast)
```

Três atores, dois canais WebSocket, um `Hub` em memória que guarda quem está
conectado de cada lado.

---

## O caminho completo de uma leitura

### 1 — Antena → dump (Android)

`MainActivity` implementa `NfcAdapter.ReaderCallback` e liga o **reader mode**
com todas as flags de tecnologia (`FLAG_READER_NFC_A|B|F|V|BARCODE`).

Reader mode em vez de intent filter porque o app mantém o controle da sessão com
a tag e consegue leituras sucessivas — com intent filter cada tag reabre a
Activity.

`onTagDiscovered` roda numa **thread de binder**, fora da main thread, então o
I/O com a tag (que pode levar segundos) acontece ali mesmo sem travar a UI.
`TagDumper.dump()` percorre as tecnologias da menos para a mais invasiva com
teto de 9 s; cada etapa que falha vira uma linha em `errors` e o dump segue.
Só o resultado volta para a UI thread via `runOnUiThread`.

### 2 — Dump → payload (Android)

`ScanViewModel.registrar()` monta o envelope:

```json
{ "type": "scan",
  "id": "<UUID v4>",
  "device": "motorola moto g34 5g",
  "scanned_at": "2026-01-14T18:22:31.104Z",
  "dump": { … tudo que a tag respondeu … } }
```

O `id` é um **correlation id**. No HTTP a resposta vem amarrada à requisição pelo
próprio protocolo; num WebSocket o canal é assíncrono e as mensagens não têm par
natural. O app guarda esse UUID e, quando chega um `scan_result`, casa pelo `id`
e atualiza a leitura certa na lista (`ScanClient.onResult` → `ScanViewModel`).

`scanned_at` é gerado no celular em UTC. O servidor grava **os dois** tempos:
`scanned_at` (quando encostou) e `received_at` (quando chegou). Se a leitura
ficou 40 s na fila offline, a diferença aparece no banco em vez de sumir.

### 3 — Transporte (`ScanClient.kt`)

OkHttp WebSocket, com três comportamentos que não são default:

| Configuração | Valor | Por quê |
|---|---|---|
| `pingInterval` | 20 s | ping/pong do próprio protocolo (RFC 6455) — mantém a conexão viva através de NAT e roteador doméstico, que matam socket ocioso |
| `readTimeout` | 0 | ficar em silêncio é o estado normal de um socket que espera evento; timeout de leitura mataria a conexão |
| `connectTimeout` | 6 s | IP errado na config falha rápido e mostra o erro na tela |

**Fila offline** — `send()` tenta `socket.send()`; se o socket é nulo ou o buffer
recusa, o texto entra numa `ArrayDeque` de até 100 itens (descartando o mais
antigo, não o mais novo — leitura recente vale mais). Em `onOpen`, `drenarFila()`
esvazia na ordem, parando no primeiro que falhar.

**Backoff exponencial** — `min(1000 * 2^min(tentativas,4), 15_000)` ms:
1 s, 2 s, 4 s, 8 s, 16 s→15 s, e daí em diante 15 s. Zera em `onOpen`. Sem isso
um servidor fora do ar vira loop de reconexão a 100% de CPU e bateria.

### 4 — Handshake (a parte "HTTP" do WebSocket)

O WebSocket **começa como HTTP**. O que sai do celular é:

```
GET /ws/device?token=…&device=motorola%20moto%20g34%205g HTTP/1.1
Host: 192.168.0.106:8000
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: <16 bytes base64>
Sec-WebSocket-Version: 13
```

O servidor responde `101 Switching Protocols` com o `Sec-WebSocket-Accept`
derivado da chave, e a partir dali a mesma conexão TCP carrega frames
bidirecionais — sem handshake novo, sem headers repetidos a cada mensagem. É daí
que vem a latência baixa.

Duas decisões nesse ponto (`routers/ws.py:37-42`):

- **O token é validado ANTES do `accept()`.** Conexão não autenticada nunca chega
  a existir; não aloca objeto de sessão, não entra no `Hub`, não consome
  file descriptor além do handshake.
- **Fecha com código 4401.** A faixa 4000–4999 é reservada pela RFC 6455 para a
  aplicação. 4401 espelha o 401 do HTTP, e o cliente distingue "token recusado"
  de "servidor caiu" — `ScanClient` usa isso para mostrar o motivo certo em vez
  de tentar reconectar para sempre com credencial errada.

A comparação é `hmac.compare_digest` (`services/auth.py:11`), não `==`.
Comparação de string sai no primeiro byte diferente e o tempo de resposta vaza
quantos caracteres do token estão certos.

### 5 — Loop de mensagens (`routers/ws.py`)

Aceito o socket: entra no `Hub`, recebe um `hello` com a hora do servidor, e os
dashboards recebem `device_connected`.

O loop é defensivo de propósito — **nenhum erro de conteúdo derruba a conexão**:

| Situação | Resposta | Socket |
|---|---|---|
| JSON malformado | `{"type":"error","message":"JSON inválido"}` | segue |
| `type` desconhecido | `error` com o tipo recebido | segue |
| `type: "ping"` | `pong` com `server_time` | segue |
| Pydantic `ValidationError` | `error` com os 5 primeiros `exc.errors()` | segue |
| Cliente sumiu | — | `WebSocketDisconnect` → `finally: hub.leave_device` |

O `finally` é o que garante que o `Hub` não acumula socket morto: saia por
desconexão limpa ou por exceção, a remoção acontece.

### 6 — A linha mais importante do backend

```python
event, result = await run_in_threadpool(_handle_scan, payload, device)
```
`routers/ws.py:97`

SQLAlchemy aqui é **síncrono**. Chamar `session.commit()` direto dentro de uma
corrotina bloqueia o event loop do asyncio — e o event loop é único. Enquanto o
SQLite escreve, *todos* os outros WebSockets param: os pings não são respondidos,
os dashboards não recebem broadcast, outros celulares ficam pendurados.

`run_in_threadpool` joga a parte bloqueante num executor e devolve o controle ao
loop. E `_handle_scan` abre sua **própria** `SessionLocal()` com `try/finally`,
porque a sessão do `Depends(get_session)` pertence ao ciclo de vida de uma
requisição HTTP, que aqui não existe.

> A alternativa seria SQLAlchemy async (`asyncpg`/`aiosqlite`). É a evolução
> natural; o threadpool resolve com o driver síncrono que já estava no projeto.

### 7 — Regra de negócio (`services/rfid.py`)

Uma função, `process_scan`, na ordem:

1. **Normaliza o UID** — `[0-9A-F]` maiúsculo, descartando `:`, espaço, `-`.
   Vindo de lugares diferentes, o mesmo cartão precisa ser a mesma chave.
2. **UID vazio vira `"SEM-UID"`.** Uma leitura que falhou é dado de diagnóstico;
   descartar esconderia justamente o caso interessante.
3. **`describe_tag`** — usa o palpite do app se veio; senão deduz pelo que existe
   no dump (`mifare_classic` → "MIFARE Classic 1K", `NfcV` → "ISO 15693"…).
4. **Busca o `Card` pelo UID.** Não achou e `AUTO_REGISTER_CARDS=true`? Cria como
   **não autorizado**. Cartão desconhecido nunca abre porta, mas também nunca é
   silenciosamente ignorado — aparece no painel para receber nome depois.
5. **`touch`** — incrementa `scan_count`, atualiza `last_seen_at`, preenche
   `tag_type` se estava vazio.
6. **Decide** — `authorized = card.authorized`; `action` é a do cartão, senão o
   default de `.env` (`unlock` / `deny`).
7. **Insere o `Scan`** com o dump **inteiro** numa coluna JSON e faz commit.

O ponto de projeto: **nada é rejeitado por não ser entendido.** `ScanIn` tem
`extra="allow"` e só o `dump` é obrigatório na prática. Um chip que responde um
campo que o backend nunca viu tem esse campo gravado do mesmo jeito e continua
disponível para análise depois. Num leitor de campo, o formato que você não
previu é o que mais importa.

### 8 — Saída dupla

```python
await websocket.send_json(result.model_dump(mode="json"))   # ao celular
await hub.broadcast_dashboards(event)                        # aos painéis
```

Dois formatos diferentes de propósito: o celular recebe `ScanResult` (a
decisão — `authorized`, `label`, `action`, `message`); o painel recebe
`scan_event` (o registro completo com o dump, para inspeção).

`broadcast_dashboards` itera sobre `list(self.dashboards)` — cópia, porque o set
pode mudar durante o envio — coleta os sockets que estouraram e remove depois,
sob `asyncio.Lock`. Um navegador que fechou a aba não impede os outros de receber.

---

## O caminho HTTP

`POST /api/scans` existe fazendo **exatamente a mesma coisa** com outro
transporte, e por isso é a melhor porta para explicar o ciclo clássico:

```
1. TCP :8000 → Uvicorn (ASGI)
2. CORSMiddleware              ← main.py:56, roda antes de qualquer rota
3. Roteamento                  ← método + path contra as rotas registradas
4. Depends(require_device_token)  ← 401 aqui encerra, o handler nem é chamado
5. Depends(get_session)        ← generator: abre sessão, yield, close no finally
6. Pydantic valida o body      ← 422 automático se o JSON não bate com ScanIn
7. rfid.process_scan(..., source="http")
8. response_model=ScanResult   ← serializa e filtra o que sai
9. 200 + JSON
```

`require_device_token` aceita o token em três lugares — query `?token=`, header
`X-Device-Token`, ou `Authorization: Bearer` — porque o WebSocket **não permite**
headers customizados na API de browser, então o token tem que caber na URL; e o
mesmo helper serve os dois caminhos.

O campo `source` (`"ws"` ou `"http"`) fica gravado em cada `Scan`. Dá para
perguntar ao banco quantas leituras vieram pelo fallback — sinal direto de
instabilidade de rede.

### O caminho inverso: PC → celular

`POST /api/write` é o motivo de o WebSocket ser obrigatório e não conveniência:

```
painel → POST /api/write → valida por modo → hub.send_to_devices(comando)
                                                     ↓ frame no socket já aberto
                                          celular arma a gravação, faixa laranja
                                                     ↓ próxima tag encostada
                                          grava, relê, confere byte a byte
                                                     ↓ mesmo socket
                              resultado viaja junto com o dump da leitura
```

Sem canal aberto, isso viraria polling do celular ("tem comando pra mim?") a cada
N segundos: latência ruim, bateria pior. O `409` quando `hub.devices` está vazio
é a mensagem honesta — não adianta armar sem ninguém para executar.

---

## Camadas do backend

```
routers/       transporte apenas — HTTP e WS, validação de entrada, códigos
services/      regra de negócio (rfid), estado de conexões (hub), auth
repositories/  todo o acesso a dados; nenhum select fora daqui
models.py      SQLAlchemy — as tabelas
schemas.py     Pydantic — o contrato de entrada e saída
```

O teste que a separação passa: `process_scan` recebe uma `Session` e um `ScanIn`
e não sabe se veio de WebSocket ou de HTTP — só recebe `source` como rótulo. Foi
o que permitiu ter os dois canais sem duplicar uma linha de decisão.

`models.py` e `schemas.py` separados por escolha, não por acidente: o que o banco
guarda e o que a API expõe mudam por motivos diferentes. `Scan.dump` guarda tudo;
`ScanSummary` não devolve o dump na listagem, porque um dump de MIFARE Classic 4K
passa de 100 KB em hex e uma lista de 50 leituras viraria 5 MB de resposta.

---

## Modelo de dados

```
cards                                scans
─────                                ─────
id                                   id
uid          UNIQUE, INDEX  ◀──┐     uid           INDEX
label                          │     device
authorized   ← a decisão       │     tag_type / tech_list
action                         │     authorized  ← decisão congelada
tag_type                       │     known
scan_count                     │     source      ws | http
last_seen_at                   │     read_ms
                               │     scanned_at  ← hora do celular
                               │     received_at ← hora do servidor, INDEX
                               └──── card_id     FK nullable
                                     dump        JSON, o payload cru
```

`Scan.authorized` é redundante em relação a `Card.authorized` — **de propósito**.
`cards` é estado atual e muda; `scans` é log de evento e não pode mudar. Se você
revoga um cartão hoje, o histórico precisa continuar dizendo que ontem ele abriu.
Sem isso, auditoria não existe.

`card_id` é nullable porque a leitura `SEM-UID` não tem cartão associado.

---

## Segurança — o que está feito e o que não está

**Feito:**

- Token compartilhado validado com `hmac.compare_digest` (tempo constante).
- Validação **antes** do `accept()` do WebSocket.
- Cleartext liberado só para faixas RFC 1918 em `network_security_config.xml` —
  o Android 9+ bloqueia HTTP em claro por padrão e o app **não** desliga isso
  globalmente para a internet.
- Escrita em tag: bloco 0 e trailers de setor são recusados no código. São as
  duas únicas operações irreversíveis do projeto.
- O dumper não implementa GPO nem READ RECORD em cartão de pagamento. Ele mostra
  que existe uma aplicação EMV e quais AIDs, e para aí — decisão de escopo.

**Não está, e eu sei:**

| Lacuna | Por que passou | Como fecharia |
|---|---|---|
| `POST /api/write` e `/ws/dashboard` sem token | painel e servidor na mesma máquina, rede local | mesma `Depends(require_device_token)`; sessão no painel |
| Sem TLS | LAN, sem certificado válido para IP | proxy reverso com certificado interno, `wss://` |
| Token único, não por dispositivo | um celular no projeto | tabela `devices` com token próprio e revogação |
| `create_all()` em vez de migrations | schema ainda mudava toda semana | Alembic |
| Sem rate limit | rede fechada | limite por token no middleware |
| Fila offline pode duplicar | envio confirmado só pelo `send` do OkHttp | dedupe pelo `id` — a coluna já existe, faltou o índice único |
| `Hub` em memória | uma instância | Redis pub/sub para escalar horizontalmente |
| `allow_origins=["*"]` | painel servido pelo próprio backend | origem explícita |

---

## Limites conhecidos

- **SQLite com `check_same_thread=False`** aguenta o cenário atual (poucos
  devices, escrita esporádica), mas serializa escrita. `DATABASE_URL` no `.env`
  troca para Postgres sem tocar em código — é o único motivo de o acesso a dados
  estar isolado em `repositories/`.
- **`Hub` em memória** não sobrevive a restart nem a segunda instância.
- **Não há idempotência.** Cada leitura é um evento novo, de propósito: encostar
  o mesmo cartão duas vezes *são* dois eventos. O que falta é o dedupe de
  reenvio, que é outro problema (veja a tabela acima).
- **Emulador não serve** — não tem antena. A lógica de parsing tem teste unitário
  (`./gradlew testDebugUnitTest`); a leitura em si só valida com aparelho.

---

## Números

| | |
|---|---|
| Backend | ~1.100 linhas de Python |
| App | Kotlin + Jetpack Compose, `TagDumper` ~660 linhas |
| APK debug | ~17 MB |
| Teto do dump | 9 s (`BUDGET_MS`), presence check empurrado para 30 s |
| Fila offline | 100 leituras |
| Backoff | 1 s → 15 s, exponencial |
| Chaves MIFARE testadas | 13 de fábrica, com cache da que funcionou |
