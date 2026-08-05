package dev.nicollas.nfcint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nicollas.nfcint.data.ServerConfig
import dev.nicollas.nfcint.net.ConnState
import dev.nicollas.nfcint.nfc.WriteJob

private enum class Modo(val rotulo: String) {
    NDEF("Texto / link (NDEF)"),
    SETOR("Texto num setor"),
    BLOCO("Bloco cru (hex)"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    leituras: List<Leitura>,
    conexao: ConnState,
    config: ServerConfig,
    nfcDisponivel: Boolean,
    gravacao: WriteJob?,
    onConfig: (ServerConfig) -> Unit,
    onLimpar: () -> Unit,
    onArmar: (WriteJob) -> Unit,
    onDesarmar: () -> Unit,
) {
    var mostrarConfig by remember { mutableStateOf(false) }
    var mostrarGravar by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Leitor NFC — dump bruto", fontSize = 17.sp)
                        Text(
                            statusTexto(conexao, config),
                            fontSize = 12.sp,
                            color = statusCor(conexao, config),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = {
                        mostrarGravar = !mostrarGravar
                        mostrarConfig = false
                    }) { Text("gravar") }
                    TextButton(onClick = {
                        mostrarConfig = !mostrarConfig
                        mostrarGravar = false
                    }) { Text("config") }
                    TextButton(onClick = onLimpar) { Text("limpar") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            if (gravacao != null) {
                BannerArmado(gravacao, onDesarmar)
            }

            if (mostrarConfig) {
                PainelConfig(config) {
                    onConfig(it)
                    mostrarConfig = false
                }
                HorizontalDivider()
            }

            if (mostrarGravar && gravacao == null) {
                PainelGravacao(
                    onArmar = {
                        onArmar(it)
                        mostrarGravar = false
                    },
                )
                HorizontalDivider()
            }

            if (!nfcDisponivel) {
                Aviso("NFC indisponível ou desligado neste aparelho. Ligue nas configurações do Android.")
            }

            if (leituras.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Encoste qualquer cartão", fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Cartão de ônibus, crachá, tag, cartão de banco…\nO app lê tudo que a tag responder.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(leituras, key = { it.id }) { CardLeitura(it) }
                }
            }
        }
    }
}

@Composable
private fun BannerArmado(job: WriteJob, onCancelar: () -> Unit) {
    Surface(color = Color(0xFF7A4B00), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("ENCOSTE A TAG PARA GRAVAR", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(3.dp))
                Text(descreverJob(job), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            OutlinedButton(onClick = onCancelar) { Text("cancelar") }
        }
    }
}

private fun descreverJob(job: WriteJob): String = when (job) {
    is WriteJob.NdefText ->
        (if (job.comoUri) "NDEF link → " else "NDEF texto → ") + job.conteudo
    is WriteJob.ClassicText -> "setor ${job.setor} → \"${job.conteudo}\""
    is WriteJob.ClassicBlock -> "bloco ${job.bloco} → ${job.hex}"
}

@Composable
private fun PainelGravacao(onArmar: (WriteJob) -> Unit) {
    var modo by remember { mutableStateOf(Modo.NDEF) }
    var texto by remember { mutableStateOf("") }
    var comoUri by remember { mutableStateOf(false) }
    var setor by remember { mutableStateOf("1") }
    var bloco by remember { mutableStateOf("4") }
    var hex by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Gravar na tag", fontWeight = FontWeight.SemiBold)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Modo.entries.forEach { m ->
                FilterChip(
                    selected = modo == m,
                    onClick = { modo = m },
                    label = { Text(m.rotulo, fontSize = 11.sp) },
                )
            }
        }

        when (modo) {
            Modo.NDEF -> {
                OutlinedTextField(
                    value = texto, onValueChange = { texto = it },
                    label = { Text(if (comoUri) "URL" else "texto") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = comoUri, onCheckedChange = { comoUri = it })
                    Spacer(Modifier.width(10.dp))
                    Text("gravar como link (abre sozinho ao encostar)", fontSize = 13.sp)
                }
                Text(
                    "Formato universal: qualquer celular lê sem app. Numa Classic 1K cabem " +
                        "cerca de 716 bytes — mas formatar troca as chaves dos setores.",
                    fontSize = 12.sp, color = dim(),
                )
            }

            Modo.SETOR -> {
                OutlinedTextField(
                    value = setor, onValueChange = { setor = it.filter(Char::isDigit).take(2) },
                    label = { Text("setor (0 a 15 numa 1K)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = texto, onValueChange = { texto = it },
                    label = { Text("texto") }, modifier = Modifier.fillMaxWidth(),
                )
                val usados = texto.toByteArray(Charsets.UTF_8).size
                val cabe = if (setor.toIntOrNull() == 0) 32 else 48
                Text(
                    "$usados de $cabe bytes. O setor 0 guarda menos porque o bloco 0 é o UID. " +
                        "O trailer de cada setor fica intocado.",
                    fontSize = 12.sp,
                    color = if (usados > cabe) MaterialTheme.colorScheme.error else dim(),
                )
            }

            Modo.BLOCO -> {
                OutlinedTextField(
                    value = bloco, onValueChange = { bloco = it.filter(Char::isDigit).take(3) },
                    label = { Text("bloco") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hex, onValueChange = { hex = it.filter { c -> c.isLetterOrDigit() }.uppercase() },
                    label = { Text("32 dígitos hex") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${hex.length} de 32 dígitos. Bloco 0 e trailers são recusados: " +
                        "estragá-los é permanente.",
                    fontSize = 12.sp,
                    color = if (hex.length != 32) MaterialTheme.colorScheme.error else dim(),
                )
            }
        }

        Button(
            onClick = {
                val job = when (modo) {
                    Modo.NDEF -> WriteJob.NdefText(texto.trim(), comoUri)
                    Modo.SETOR -> WriteJob.ClassicText(setor.toIntOrNull() ?: 1, texto)
                    Modo.BLOCO -> WriteJob.ClassicBlock(bloco.toIntOrNull() ?: 4, hex)
                }
                onArmar(job)
            },
            enabled = when (modo) {
                Modo.NDEF, Modo.SETOR -> texto.isNotBlank()
                Modo.BLOCO -> hex.length == 32
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("armar gravação") }
    }
}

@Composable
private fun Aviso(texto: String) {
    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
        Text(texto, Modifier.padding(12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CardLeitura(leitura: Leitura) {
    var expandido by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val d = leitura.dump

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.uid.ifBlank { "(sem UID)" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(leitura.hora, fontSize = 12.sp, color = dim())
            }

            Spacer(Modifier.height(4.dp))
            Text(d.guess, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "${d.techs.joinToString(" · ")}  •  ${d.readMs} ms",
                fontSize = 12.sp,
                color = dim(),
            )

            leitura.escrita?.let { r ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = if (r.ok) Color(0x333FB950) else Color(0x33F85149),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text(
                            (if (r.ok) "✔ gravado — " else "✘ não gravou — ") + r.mensagem,
                            fontSize = 13.sp,
                        )
                        r.detalhes.forEach {
                            Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = dim())
                        }
                    }
                }
            }

            leitura.mensagem?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = if (leitura.autorizado) Color(0x333FB950) else Color(0x33D29922),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        (if (leitura.autorizado) "✔ " else "• ") + msg,
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        fontSize = 13.sp,
                    )
                }
            }

            if (d.errors.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    d.errors.joinToString("\n"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Spacer(Modifier.height(6.dp))
            Row {
                TextButton(onClick = { expandido = !expandido }) {
                    Text(if (expandido) "esconder dump" else "ver dump completo")
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(d.pretty)) }) {
                    Text("copiar JSON")
                }
            }

            if (expandido) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        Text(
                            d.pretty,
                            Modifier.padding(10.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PainelConfig(atual: ServerConfig, onSalvar: (ServerConfig) -> Unit) {
    var host by remember { mutableStateOf(atual.host) }
    var porta by remember { mutableStateOf(atual.port.toString()) }
    var token by remember { mutableStateOf(atual.token) }
    var device by remember { mutableStateOf(atual.device) }
    var enviar by remember { mutableStateOf(atual.enviarParaServidor) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Servidor", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text("IP do PC") }, singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = porta, onValueChange = { porta = it.filter(Char::isDigit) },
                label = { Text("porta") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = token, onValueChange = { token = it },
            label = { Text("token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = device, onValueChange = { device = it },
            label = { Text("nome deste celular") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enviar, onCheckedChange = { enviar = it })
            Spacer(Modifier.width(10.dp))
            Text("Enviar leituras para o servidor", fontSize = 14.sp)
        }
        Text(
            "Com o switch desligado o app funciona offline: lê a tag e mostra o dump aqui mesmo.",
            fontSize = 12.sp, color = dim(),
        )
        Button(
            onClick = {
                onSalvar(
                    atual.copy(
                        host = host.trim(),
                        port = porta.toIntOrNull() ?: 8000,
                        token = token.trim(),
                        device = device.trim().ifBlank { atual.device },
                        enviarParaServidor = enviar,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("salvar e reconectar") }
    }
}

@Composable
private fun dim() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

private fun statusTexto(estado: ConnState, cfg: ServerConfig): String = when {
    !cfg.enviarParaServidor -> "modo offline — só mostra na tela"
    estado is ConnState.Connected -> "conectado em ${cfg.host}:${cfg.port}"
    estado is ConnState.Connecting -> "conectando em ${cfg.host}:${cfg.port}…"
    estado is ConnState.Failed -> "sem servidor (${estado.motivo}) — leituras na fila"
    else -> "desconectado"
}

@Composable
private fun statusCor(estado: ConnState, cfg: ServerConfig): Color = when {
    !cfg.enviarParaServidor -> dim()
    estado is ConnState.Connected -> MaterialTheme.colorScheme.secondary
    estado is ConnState.Failed -> MaterialTheme.colorScheme.error
    else -> dim()
}
