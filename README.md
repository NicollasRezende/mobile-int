# mobile-int — leitor NFC universal (Kotlin + FastAPI)

Celular Android lê **qualquer** tag NFC que encostar nele, extrai tudo que o chip
responder e manda o dump inteiro por WebSocket para um backend Python, que salva,
decide e devolve a resposta em menos de 100 ms. Tem painel web que mostra as
leituras chegando ao vivo.

O foco aqui é **exploração**: o app não filtra por tipo de cartão nem exige
cadastro prévio. Encostou, ele lê o máximo que consegue e mostra o que veio.

```
   tag NFC  ──▶  Android (Kotlin)  ──ws://──▶  FastAPI  ──▶  SQLite/Postgres
                 TagDumper                     decide          histórico
                      ◀── {authorized, label, action} ──┘
                                    │
                                    └──▶ painel web (tempo real)
```

## Do zero até rodando

### Pré-requisitos

| O quê | Versão | Para quê |
|---|---|---|
| Python | 3.11+ | backend |
| JDK | 17 | compilar o app (o Gradle é chato com versões acima) |
| Android SDK | platform 35 + build-tools 35 | compilar e instalar |
| Celular Android | 7.0+ (API 24) **com NFC** | não roda em emulador — emulador não tem antena |

Confira o Java antes de qualquer coisa, porque é onde mais gente trava:

```bash
java -version     # precisa dizer 17.x
```

Se você usa SDKMAN: `sdk use java 17.0.8-tem`.

### Android SDK sem Android Studio

Se você já tem o Android Studio, pule — o SDK dele serve. Para instalar só a
linha de comando (mais leve, funciona em servidor):

```bash
mkdir -p ~/Android/Sdk/cmdline-tools && cd /tmp
curl -sLO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-11076708_latest.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

export ANDROID_HOME=$HOME/Android/Sdk
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Vale colocar as duas linhas de `export` no seu `~/.bashrc` ou `~/.zshrc`.

### 1. Backend

```bash
cd backend
cp .env.example .env          # e troque o DEVICE_TOKEN por algo seu
./run.sh
```

O script cria a virtualenv na primeira execução, instala as dependências e sobe
o servidor em `0.0.0.0:8000`. Ele imprime os IPs da máquina — anote o da sua rede
local (algo como `192.168.x.x`), porque é o que você digita no celular.

Painel: `http://localhost:8000`. Documentação da API: `http://localhost:8000/docs`.

Se o celular não conectar, quase sempre é firewall. No Ubuntu:

```bash
sudo ufw allow from 192.168.0.0/24 to any port 8000 proto tcp
```

### 2. Compilar o app

```bash
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # só na primeira vez
./gradlew assembleDebug
```

O APK sai em `android/app/build/outputs/apk/debug/app-debug.apk` (~17 MB). A
primeira compilação baixa o Gradle e as dependências e leva alguns minutos; as
seguintes levam segundos.

Testes da lógica de parsing: `./gradlew testDebugUnitTest`.

### 3. Instalar no celular

**Com cabo** — precisa de *Opções do desenvolvedor* → **Depuração USB** ligada,
e o cabo em modo **Transferência de arquivos (MTP)**, não em "somente carga".
Autorize o diálogo de chave RSA que aparece na tela.

```bash
adb devices          # tem que listar o aparelho como "device"
./gradlew installDebug
```

**Sem cabo** — sirva o APK na rede e baixe pelo navegador do celular:

```bash
cd android/app/build/outputs/apk/debug
python3 -m http.server 8080
# no celular: http://SEU_IP:8080/app-debug.apk
```

Vai pedir permissão para instalar de fonte desconhecida. Autorize para o
navegador.

### 4. Apontar o app para o servidor

Abra o app, toque em **config** e preencha IP, porta e token (o mesmo do `.env`).
O padrão compilado é `192.168.0.106:8000`, que provavelmente não é o seu IP.

Se preferir mudar o padrão no código, é em
`android/.../data/AppSettings.kt`.

Com o switch *"enviar leituras para o servidor"* desligado o app funciona
sozinho: lê a tag e mostra o dump na tela, sem depender de rede.

### Build de release

```bash
cd android
./gradlew assembleRelease
```

Sai em `app/build/outputs/apk/release/app-release-unsigned.apk` — **sem
assinatura**, então não instala direto. Para gerar um APK instalável:

```bash
keytool -genkey -v -keystore chave.jks -keyalg RSA -keysize 2048 \
        -validity 10000 -alias nfcint

$ANDROID_HOME/build-tools/35.0.0/apksigner sign --ks chave.jks \
        --out app-release.apk \
        app/build/outputs/apk/release/app-release-unsigned.apk
```

Guarde o `.jks` fora do repositório — o `.gitignore` já bloqueia `*.keystore`,
mas confira antes de commitar.

### Testar sem celular nenhum

```bash
cd backend
.venv/bin/python tools/fake_scan.py     # manda 4 dumps de exemplo pelo WebSocket
```

Serve para mexer no backend e no painel sem precisar de hardware NFC.

### Quando não funciona

| Sintoma | Causa quase sempre |
|---|---|
| `adb devices` vazio | depuração USB desligada, ou cabo em "somente carga" — troque para MTP |
| App não conecta, diz "sem servidor" | firewall do PC, ou IP errado na config |
| `run-as: package not debuggable` | você está tentando inspecionar um build de release |
| Gradle reclama da versão do Java | está usando JDK acima do 17 |
| App instala mas não lê tag | NFC desligado no Android, ou tag na parte errada do aparelho (no Moto G34 a antena fica em cima, perto da câmera) |

## O que o app consegue ler de cada cartão

O `TagDumper` (`android/.../nfc/TagDumper.kt`) tenta todas as tecnologias em
sequência, da menos para a mais invasiva, com teto de 9 segundos. O que uma
etapa não conseguir vira uma linha em `errors` e o dump continua.

| Tecnologia | O que sai no dump |
|---|---|
| Sempre | UID (normal, invertido, em bytes), fabricante pelo primeiro byte, lista de tecnologias |
| NfcA | ATQA, SAK, tamanho máximo de transceive, timeout |
| NfcB | application data, protocol info |
| NfcF (FeliCa) | manufacturer, system code |
| NfcV (ISO 15693) | DSFID, response flags, leitura bloco a bloco |
| IsoDep | ATS/historical bytes, e sondas APDU de identificação (PPSE, PSE, NDEF app, GET UID, DESFire GetVersion, SELECT MF) |
| NDEF | tipo, tamanho, gravável, e cada registro decodificado (texto com idioma, URI com prefixo expandido, MIME, external) |
| MIFARE Ultralight / NTAG | GET_VERSION (identifica NTAG213/215/216 e Ultralight EV1), signature, contador, todas as páginas |
| MIFARE Classic | tipo, tamanho, e dump setor a setor tentando 13 chaves de fábrica |
| NfcBarcode | tipo e conteúdo (Kovio) |

## Gravar na tag

O botão **gravar** abre o painel, você escolhe o que gravar e toca em *armar
gravação*. A partir daí o app mostra uma faixa laranja e a **próxima** tag que
encostar recebe a gravação — uma vez só, e ele desarma sozinho. Fora desse
estado armado, encostar cartão nunca altera nada.

Três modos:

| Modo | O que faz | Cabe |
|---|---|---|
| Texto / link (NDEF) | Grava texto ou URL no formato universal, que qualquer celular lê sem app | ~716 bytes numa Classic 1K, 492 num NTAG215 |
| Texto num setor | Escreve texto cru nos blocos de dados de um setor do MIFARE Classic | 48 bytes por setor (32 no setor 0) |
| Bloco cru (hex) | 16 bytes exatos num bloco específico | 16 bytes |

Duas coisas o código recusa a fazer, porque o estrago não tem volta: escrever no
**bloco 0** (onde vive o UID) e escrever em qualquer **trailer** de setor (onde
ficam as chaves e os bits de permissão — um valor errado ali transforma o setor
em pedra). Toda gravação é relida e conferida byte a byte antes de reportar
sucesso.

### Gravando a partir do PC

O mesmo painel web tem o botão **gravar na tag**: você monta o comando lá e ele
viaja pelo WebSocket até o celular, que arma a gravação e mostra a faixa. O PC
não tem leitor NFC — quem encosta na tag continua sendo o celular, ele só passa
a ser controlado remotamente. O resultado volta pelo mesmo caminho e aparece na
seção *Gravação* do dump.

```bash
# armar
curl -X POST http://localhost:8000/api/write -H 'Content-Type: application/json' \
     -d '{"mode":"classic_text","conteudo":"NICOLLAS","setor":2}'

# cancelar
curl -X DELETE http://localhost:8000/api/write
```

`mode` aceita `ndef_text` (com `conteudo` e `como_uri`), `classic_text` (com
`conteudo` e `setor`) e `classic_block` (com `bloco` e `hex`).

### ⚠️ O que formatar como NDEF faz num MIFARE Classic

Isto não é teoria: aconteceu com o cartão `B377E64A` deste projeto, e os dois
dumps estão salvos em `cartoes/` para comparação. Gravar NDEF num Classic que
ainda não era NDEF dispara `NdefFormatable.format()`, e ele **reescreve o cartão
inteiro**, não só o texto:

| O que muda | Antes | Depois |
|---|---|---|
| Chave A dos setores de dados | `FFFFFFFFFFFF` | `D3F7D3F7D3F7` (pública de NDEF) |
| Chave B | `FFFFFFFFFFFF` | `FFFFFFFFFFFF` (não muda) |
| Access bits, setores 1-15 | `FF0780` | `7F0788` |
| Access bits, setor 0 | `FF0780` | `787788` |
| GPB | `69` | `40` nos dados, `C1` no setor 0 |
| Setor 0, blocos 1 e 2 | zerados | MAD (`140103E103E1…`) |

Se o cartão é usado num sistema de acesso que autentica com a chave A de
fábrica, **ele para de funcionar lá** — e o conserto exige reescrever os
trailers, que é a única operação deste projeto capaz de destruir um setor de
forma permanente. Antes de formatar, pergunte-se se o cartão serve para algo
além de teste.

Reverter é possível (os novos access bits permitem reescrever o trailer com a
chave B, que continua sendo a de fábrica), mas o app não faz isso: escrita em
trailer é bloqueada por decisão de projeto. Se um dia for necessário, faça setor
por setor, começando pelo menos importante, conferindo cada um antes do próximo.

### Backups de cartão

`cartoes/*.json` guarda dumps completos para referência — dá para comparar
estados e, se um dia precisar, reconstruir bloco a bloco a partir dali. O
histórico do banco (`GET /api/scans/{id}`) serve para o mesmo, mas o arquivo
sobrevive a um `DELETE /api/scans`.

### Quanto cabe numa MIFARE Classic 1K

São 1024 bytes no papel, mas o que sobra para você é **752 bytes**: o bloco 0
(16 bytes) é o UID e os 16 trailers (256 bytes) guardam as chaves. Em blocos
utilizáveis: 2 no setor 0 e 3 em cada um dos setores 1 a 15.

### Expectativa realista por tipo de cartão

- **Tag/crachá NTAG ou Ultralight** — leitura completa: todas as páginas, NDEF
  decodificado, modelo exato do chip.
- **MIFARE Classic virgem ou de kit** — dump completo dos 16 setores com a chave
  `FFFFFFFFFFFF`.
- **Cartão de ônibus / catraca** — quase sempre MIFARE Classic com chaves
  próprias. Você vai ver UID, ATQA, SAK, tamanho e a lista de setores marcados
  como não autenticados. Isso é esperado: a chave é o segredo do operador.
- **Cartão de banco por aproximação** — as sondas mostram que existe uma
  aplicação de pagamento e quais AIDs (bandeira) ela expõe. O dumper **não** lê
  dados de conta: não há GPO nem READ RECORD no código, de propósito.
- **Passaporte / documento com chip** — responde ao SELECT mas exige a chave
  derivada dos dados impressos (BAC/PACE), que não é implementada aqui.

## Backend

Camadas separadas em `backend/app/`: `routers/` (HTTP e WebSocket),
`services/` (regra de negócio, hub de conexões, autenticação),
`repositories/` (acesso a dados), `models.py` (SQLAlchemy), `schemas.py` (Pydantic).

O `dump` chega e é gravado **inteiro** como JSON, mesmo com campos que o backend
não conhece — o `ScanIn` é propositalmente permissivo. Cartão novo não é
rejeitado: com `AUTO_REGISTER_CARDS=true` ele entra na tabela `cards` como não
autorizado e você dá nome depois pelo painel.

| Rota | Para que serve |
|---|---|
| `ws://host:8000/ws/device?token=…&device=…` | canal do celular: manda scan, recebe decisão |
| `ws://host:8000/ws/dashboard` | painel: recebe cada leitura em tempo real |
| `POST /api/scans?token=…` | mesma ingestão via HTTP (curl, fallback) |
| `GET /api/scans` · `GET /api/scans/{id}` · `DELETE /api/scans` | histórico |
| `GET/POST /api/cards` · `PATCH/DELETE /api/cards/{uid}` | cadastro e autorização |
| `GET /api/health` | status, celulares conectados, IPs locais |
| `GET /docs` | OpenAPI |

Resposta devolvida ao celular:

```json
{"type":"scan_result","status":"ok","uid":"04A91B22C75E80","tag_type":"NTAG215",
 "known":true,"authorized":true,"label":"Nicollas","action":"unlock",
 "scan_count":7,"message":"Bem-vindo Nicollas"}
```

## Detalhes que costumam morder

- **Cleartext**: `ws://` sem TLS é bloqueado pelo Android desde a versão 9. Já
  vem liberado para faixas privadas em `res/xml/network_security_config.xml`.
- **Reader mode** em vez de intent filter: o app fica no controle da tag e
  consegue fazer várias leituras seguidas.
- **O presence check do Android atropela o dump.** Ele conversa com a tag por
  fora do nosso código para saber se ela ainda está no campo, e isso derruba a
  sessão autenticada do MIFARE Classic no meio da leitura — o sintoma é o
  `readBlock` devolver 1 byte em vez de 16. Está em 30 s (o dump tem teto
  próprio de 9 s), e todo bloco lido é validado contra `BLOCK_SIZE`: se vier
  torto, reautentica e tenta de novo, e só então marca como inválido.
- **Autenticação errada em MIFARE Classic derruba a sessão** — o dumper
  reconecta a cada chave que falha (capturando `Exception`, não só
  `IOException`: com a sessão morta o Android lança `IllegalStateException`),
  e Classic roda por último. A chave que autentica um setor vai para o topo da
  fila dos próximos, o que evita centenas de reconexões por cartão.
- **Fila offline**: sem servidor, o app guarda até 100 leituras e envia quando
  reconectar. A tela mostra o dump de qualquer jeito.
- Trocar para Postgres é só mudar `DATABASE_URL` no `.env` e descomentar
  `psycopg` no `requirements.txt`.

## Estado atual

Backend testado ponta a ponta (WebSocket, HTTP, autenticação, broadcast para o
painel, payload desconhecido). App compila e passa nos testes de parsing
(`./gradlew testDebugUnitTest`); a leitura NFC em si só dá para validar com
aparelho de verdade.

Próximos passos naturais: cadastro de cartão pelo próprio app, escrita em tags
NDEF, ESP32 na porta, e câmera associando foto à leitura.
