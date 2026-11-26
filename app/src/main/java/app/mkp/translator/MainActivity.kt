package app.mkp.translator

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) { App(vm) }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.saveNow()
    }
}

@Composable
fun App(vm: MainViewModel) {
    var screen by rememberSaveable { mutableStateOf("list") }
    val ctx = LocalContext.current
    val view = LocalView.current
    val snack = remember { SnackbarHostState() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(ctx.contentResolver, uris)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.export(uri)
    }

    DisposableEffect(vm.busy) {
        view.keepScreenOn = vm.busy
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }

    val cur = vm.current
    BackHandler(enabled = screen != "list") {
        if (screen == "reader") vm.current = null
        screen = "list"
    }

    when {
        screen == "settings" -> SettingsScreen(vm, snack) { screen = "list" }
        screen == "names" -> NamesScreen(vm, snack) { screen = "list" }
        screen == "reader" && cur != null -> ReaderScreen(vm, cur, snack) { vm.current = null; screen = "list" }
        else -> ListScreen(
            vm, snack,
            onOpen = { vm.openScript(it); screen = "reader" },
            onSettings = { screen = "settings" },
            onNames = { screen = "names" },
            onImport = { importer.launch(arrayOf("*/*")) },
            onExport = { exporter.launch(null) }
        )
    }

    vm.error?.let {
        AlertDialog(
            onDismissRequest = { vm.error = null },
            confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
            title = { Text("Ошибка") },
            text = { Text(it) }
        )
    }
}

// ---------------------------------------------------------------- Список скриптов
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    vm: MainViewModel, snack: SnackbarHostState,
    onOpen: (ScriptData) -> Unit, onSettings: () -> Unit, onNames: () -> Unit,
    onImport: () -> Unit, onExport: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MKP Переводчик") },
                actions = {
                    TextButton(onClick = onNames) { Text("Имена") }
                    TextButton(onClick = onSettings) { Text("Настройки") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (vm.scripts.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Проект не открыт", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Выберите сразу два файла: .mkh и .mkp",
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onImport) { Text("Открыть .mkh + .mkp") }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (vm.busy) vm.cancel() else vm.translateAll() }, modifier = Modifier.weight(1f)) {
                        Text(if (vm.busy) "Стоп" else "Перевести всё")
                    }
                    OutlinedButton(onClick = onExport, enabled = !vm.busy, modifier = Modifier.weight(1f)) { Text("Экспорт") }
                    TextButton(onClick = onImport, enabled = !vm.busy) { Text("Другой") }
                }
                val total = vm.scripts.sumOf { it.segs.size }
                val done = vm.scripts.sumOf { vm.done(it) }
                Text(
                    "Переведено $done из $total" + if (vm.status.isNotEmpty()) " • ${vm.status}" else "",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(vm.scripts, key = { it.name }) { s ->
                        val d = vm.done(s)
                        Column(Modifier.fillMaxWidth().clickable { onOpen(s) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(s.name, fontWeight = FontWeight.Medium)
                                Text("$d / ${s.segs.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (s.segs.isNotEmpty()) {
                                LinearProgressIndicator(
                                    progress = { d.toFloat() / s.segs.size },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                )
                            }
                        }
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Чтение
private fun speakerColor(name: String): Color =
    Color.hsv((abs(name.hashCode()) % 360).toFloat(), 0.45f, 0.85f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(vm: MainViewModel, s: ScriptData, snack: SnackbarHostState, onBack: () -> Unit) {
    var edit by remember { mutableStateOf<Seg?>(null) }
    val fs = vm.settings.fontSize

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) } },
                title = {
                    Column {
                        Text(s.name, maxLines = 1)
                        Text("${vm.done(s)} / ${s.segs.size}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = { vm.settings.showOriginal = !vm.settings.showOriginal }) {
                        Text(if (vm.settings.showOriginal) "Без ориг." else "С ориг.")
                    }
                    TextButton(onClick = { if (vm.busy) vm.cancel() else vm.translateCurrent() }) {
                        Text(if (vm.busy) "Стоп" else "Перевод")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(vm.status, modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.segs, key = { it.start }) { seg ->
                    val tr = vm.translationOf(s, seg)
                    Column(Modifier.fillMaxWidth().clickable { edit = seg }.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (seg.speaker != null) {
                            Text(
                                vm.speakerLabel(seg.speaker) ?: "",
                                fontWeight = FontWeight.Bold,
                                color = speakerColor(seg.speaker),
                                fontSize = (fs - 2).sp
                            )
                        }
                        Text(
                            (if (seg.choice) "▸ " else "") + (tr ?: seg.original),
                            fontSize = fs.sp,
                            lineHeight = (fs * 1.4f).sp,
                            color = if (tr == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                        )
                        if (vm.settings.showOriginal && tr != null) {
                            Text(
                                seg.original,
                                fontSize = (fs - 3).sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    edit?.let { seg ->
        var text by remember(seg) { mutableStateOf(vm.translationOf(s, seg) ?: "") }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(seg.speaker?.let { vm.speakerLabel(it) } ?: "Строка") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(seg.original, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("Перевод") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.setTranslation(s, seg, text); edit = null }) { Text("Сохранить") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.retranslate(s, seg); edit = null }, enabled = !vm.busy) { Text("Заново (ИИ)") }
                    TextButton(onClick = { edit = null }) { Text("Отмена") }
                }
            }
        )
    }
}

// ---------------------------------------------------------------- Имена
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamesScreen(vm: MainViewModel, snack: SnackbarHostState, onBack: () -> Unit) {
    val names = remember(vm.scripts.size) {
        val c = HashMap<String, Int>()
        vm.scripts.forEach { sc -> sc.segs.forEach { sg -> sg.speaker?.let { n -> c[n] = (c[n] ?: 0) + 1 } } }
        c.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) } },
                title = { Text("Имена персонажей") },
                actions = {
                    TextButton(onClick = { vm.translateAllNames() }, enabled = !vm.busy) { Text("Перевести ИИ") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.padding(pad).imePadding().fillMaxSize()) {
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                items(names, key = { it.first }) { (n, count) ->
                    OutlinedTextField(
                        value = vm.glossary[n] ?: "",
                        onValueChange = { v ->
                            if (v.isBlank()) vm.glossary.remove(n) else vm.glossary[n] = v
                            vm.scheduleSave()
                        },
                        label = { Text("$n  ($count)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Настройки
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Dropdown(label: String, value: String, options: List<T>, optionLabel: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(optionLabel(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, snack: SnackbarHostState, onBack: () -> Unit) {
    val st = vm.settings
    val p = st.provider
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) } },
                title = { Text("Настройки") }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(
            Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("API перевода", style = MaterialTheme.typography.titleMedium)
            Dropdown("Тип API", p.title, Provider.values().toList(), { it.title }) { st.provider = it }
            OutlinedTextField(
                value = st.endpoint(p), onValueChange = { st.setEndpoint(p, it) },
                label = { Text("Адрес (endpoint)") }, singleLine = true,
                supportingText = {
                    Text(
                        when (p) {
                            Provider.GEMINI -> "Дописывается /models/МОДЕЛЬ:generateContent"
                            Provider.CLAUDE -> "Дописывается /messages"
                            else -> "Дописывается /chat/completions (можно указать и полный URL)"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = st.apiKey(p), onValueChange = { st.setApiKey(p, it) },
                label = { Text("API-ключ") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = st.model(p), onValueChange = { st.setModel(p, it) },
                label = { Text("Модель") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.testApi() }) { Text("Проверить API") }
                TextButton(onClick = { st.setEndpoint(p, p.defaultEndpoint); st.setModel(p, p.defaultModel) }) {
                    Text("Сбросить адрес и модель")
                }
            }

            HorizontalDivider()
            Text("Язык", style = MaterialTheme.typography.titleMedium)
            Dropdown("Язык оригинала", st.sourceLang, SOURCE_LANGS, { it }) { st.sourceLang = it }
            Dropdown("Переводить на", st.targetLang, TARGET_LANGS, { it }) { st.targetLang = it }
            OutlinedTextField(
                value = st.customTarget, onValueChange = { st.customTarget = it },
                label = { Text("Другой язык (если заполнено — используется он)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SwitchRow("Автоперевод при открытии главы", st.autoTranslate) { st.autoTranslate = it }
            SwitchRow("Переводить имена персонажей", st.translateNames) { st.translateNames = it }

            HorizontalDivider()
            Text("Перевод", style = MaterialTheme.typography.titleMedium)
            SliderRow("Строк в одном запросе", st.batchSize, 5..60) { st.batchSize = it }
            SliderRow("Параллельных запросов", st.parallel, 1..6) { st.parallel = it }
            OutlinedTextField(
                value = st.extraPrompt, onValueChange = { st.extraPrompt = it },
                label = { Text("Доп. инструкции (стиль, обращения, жаргон…)") },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()
            Text("Вид и экспорт", style = MaterialTheme.typography.titleMedium)
            SliderRow("Размер текста", st.fontSize, 12..28) { st.fontSize = it }
            Dropdown(
                "Кодировка при экспорте", st.exportEncoding, listOf("UTF-8", "Shift-JIS"), { it }
            ) { st.exportEncoding = it }
            Text(
                "Shift-JIS не содержит кириллицу — русские буквы станут «?». Для русского/английского перевода нужен движок, читающий UTF-8.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SliderRow("Ширина строки при экспорте (0 — без переноса)", st.wrapWidth, 0..90) { st.wrapWidth = it }
            Box(Modifier.padding(bottom = 24.dp))
        }
    }
}
