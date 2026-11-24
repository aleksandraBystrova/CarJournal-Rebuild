package com.carjournal.rebuild.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.carjournal.rebuild.data.*
import com.carjournal.rebuild.cloud.CloudHttpException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ru = Locale("ru", "RU")
private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", ru)
private fun dateText(day: Long): String = LocalDate.ofEpochDay(day).format(dateFormatter)
private fun money(value: Double): String = NumberFormat.getNumberInstance(ru).apply {
    minimumFractionDigits = 0
    maximumFractionDigits = 2
}.format(value)
private fun priceForInput(value: Double): String =
    if (value.isFinite() && value == value.toLong().toDouble()) value.toLong().toString()
    else java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private sealed interface Page {
    data object ListPage : Page
    data object StatisticsPage : Page
    data class VehiclePage(val vehicleId: Long) : Page
    data class EditVehiclePage(val vehicleId: Long?) : Page
    data class EditPhotosPage(val vehicleId: Long) : Page
    data class EditRepairPage(val vehicleId: Long, val repairId: Long?) : Page
}

@Composable
fun JournalApp(vm: JournalViewModel) {
    if (!vm.loggedIn) {
        CloudLoginScreen(vm)
        return
    }
    LaunchedEffect(vm.loggedIn) {
        while (true) {
            vm.syncNow()
            delay(30_000)
        }
    }
    val cloudScope = rememberCoroutineScope()
    val syncSnackbar = remember { SnackbarHostState() }
    var page: Page by remember { mutableStateOf(Page.ListPage) }
    val back: () -> Unit = {
        page = when (val p = page) {
            is Page.ListPage -> Page.ListPage
            is Page.StatisticsPage -> Page.ListPage
            is Page.VehiclePage -> Page.ListPage
            is Page.EditVehiclePage -> p.vehicleId?.let { Page.VehiclePage(it) } ?: Page.ListPage
            is Page.EditPhotosPage -> Page.VehiclePage(p.vehicleId)
            is Page.EditRepairPage -> Page.VehiclePage(p.vehicleId)
        }
    }
    BackHandler(enabled = page !is Page.ListPage) { back() }
    when (val p = page) {
        is Page.ListPage -> VehicleListScreen(vm, onOpen = { page = Page.VehiclePage(it) },
            onCreate = { page = Page.EditVehiclePage(null) },
            onStatistics = { page = Page.StatisticsPage },
            onSync = { cloudScope.launch {
                vm.syncNow()
                syncSnackbar.showSnackbar(vm.syncStatus)
            } },
            onLogout = { vm.signOut(); page = Page.ListPage },
            syncSnackbar = syncSnackbar)
        is Page.StatisticsPage -> StatisticsScreen(vm, onBack = back)
        is Page.VehiclePage -> VehicleDetailScreen(vm, p.vehicleId,
            onBack = back,
            onEditVehicle = { page = Page.EditVehiclePage(p.vehicleId) },
            onEditPhotos = { page = Page.EditPhotosPage(p.vehicleId) },
            onNewRepair = { page = Page.EditRepairPage(p.vehicleId, null) },
            onEditRepair = { page = Page.EditRepairPage(p.vehicleId, it) })
        is Page.EditVehiclePage -> VehicleEditorScreen(vm, p.vehicleId, onBack = back, onSaved = {
            page = if (p.vehicleId != null) Page.VehiclePage(p.vehicleId) else Page.VehiclePage(it)
        })
        is Page.EditPhotosPage -> VehiclePhotoEditorScreen(vm, p.vehicleId, onBack = back,
            onSaved = { page = Page.VehiclePage(p.vehicleId) })
        is Page.EditRepairPage -> RepairEditorScreen(vm, p.vehicleId, p.repairId,
            onBack = back, onSaved = { page = Page.VehiclePage(p.vehicleId) })
    }
}

@Composable
private fun CloudLoginScreen(vm: JournalViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loginDialog by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(54.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Журнал машин", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Общий аккаунт мастерской",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(email, { email = it; error = null }, label = { Text("Email мастерской") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it; error = null }, label = { Text("Пароль") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Spacer(Modifier.height(20.dp))
        Button(onClick = {
            busy = true
            error = null
            scope.launch {
                try { vm.signIn(email, password) }
                catch (e: Exception) {
                    when {
                        e is CloudHttpException && e.status in listOf(400, 401) ->
                            error = "Неверный email или пароль"
                        e is CloudHttpException && e.status == 429 ->
                            loginDialog = "Слишком много попыток входа. Попробуйте позже."
                        e is CloudHttpException ->
                            loginDialog = "Не удалось войти. Проверьте подключение и повторите попытку."
                        else -> loginDialog = "Не удалось подключиться. Проверьте интернет и повторите попытку."
                    }
                }
                finally { busy = false }
            }
        }, enabled = !busy && email.contains('@') && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (busy) "Входим..." else "Войти")
        }
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error ?: "", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (loginDialog != null) ErrorDialog(loginDialog!!) { loginDialog = null }
}

@Composable
private fun Header(title: String, onBack: (() -> Unit)? = null, extra: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .height(68.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Назад") }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        extra()
    }
}

@Composable
private fun SoftCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        content = { Column(Modifier.padding(16.dp), content = content) }
    )
}

@Composable
private fun VehicleListScreen(
    vm: JournalViewModel,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
    onStatistics: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    syncSnackbar: SnackbarHostState
) {
    val vehicles by vm.vehicles.collectAsState()
    val totals by vm.totals.collectAsState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(vehicles, query) {
        vehicles.filter { it.plateNumber.contains(query, ignoreCase = true) }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(syncSnackbar) },
        topBar = {
            Header("Машины") {
                TextButton(onClick = onStatistics) {
                    Icon(Icons.Outlined.BarChart, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Статистика")
                }
                IconButton(onClick = onSync) {
                    Icon(Icons.Outlined.Sync, contentDescription = "Синхронизировать с облаком")
                }
                IconButton(onClick = onLogout) {
                    Icon(Icons.Outlined.Logout, contentDescription = "Выйти из аккаунта")
                }
            }
        },
        floatingActionButton = { FloatingActionButton(onClick = onCreate) {
            Icon(Icons.Outlined.Add, "Добавить машину")
        } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Поиск по госномеру") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Всего ремонтов", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        Text(totals.repairsCount.toString(),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Общая стоимость", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Сомы", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(money(totals.totalSom), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text("сом", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Рубли", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(money(totals.totalRub), style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text("руб", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Text(
                    if (query.isEmpty()) "Нет машин. Нажмите +, чтобы добавить." else "Ничего не найдено",
                    modifier = Modifier.padding(vertical = 20.dp)
                ) }
            }
            items(filtered, key = { it.id }) { vehicle ->
                Card(
                    onClick = { onOpen(vehicle.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CarPicture(vehicle.photoPath, Modifier.size(74.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(vehicle.plateNumber, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium)
                            if (vehicle.model.isNotBlank()) {
                                Text(vehicle.model, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("Ремонтов: ${vehicle.repairCount}", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Outlined.ChevronRight, null)
                    }
                }
            }
        }
    }
}

/** Год берётся из даты ремонта, а рубли и сомы никогда не складываются между собой. */
@Composable
private fun StatisticsScreen(vm: JournalViewModel, onBack: () -> Unit) {
    val years by vm.yearlyStats.collectAsState()
    Scaffold(topBar = { Header("Статистика", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Ремонты по годам", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            Text("Суммы учитываются отдельно по каждой валюте.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (years.isEmpty()) {
                Text("Пока нет ремонтов для статистики.", modifier = Modifier.padding(top = 18.dp))
            } else {
                val tableScroll = rememberScrollState()
                Column(Modifier.fillMaxWidth().horizontalScroll(tableScroll)) {
                    Row(
                        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatsText("Год", 74.dp, true)
                        StatsText("Ремонтов", 96.dp, true)
                        Column(Modifier.width(260.dp)) {
                            Text("Стоимость", modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Row {
                                StatsText("Сом", 130.dp, true)
                                StatsText("Рубль", 130.dp, true)
                            }
                        }
                    }
                    years.forEach { item ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatsText(item.year.toString(), 74.dp, true)
                            StatsText(item.repairsCount.toString(), 96.dp)
                            StatsText(money(item.totalSom), 130.dp)
                            StatsText(money(item.totalRub), 130.dp)
                        }
                    }
                }
                Text("При необходимости прокрути таблицу вправо.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatsText(text: String, width: androidx.compose.ui.unit.Dp, bold: Boolean = false) {
    Text(text, modifier = Modifier.width(width).padding(horizontal = 8.dp),
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CarPicture(path: String?, modifier: Modifier = Modifier) {
    if (path.isNullOrBlank()) {
        Box(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center) { Icon(Icons.Outlined.DirectionsCar, "Автомобиль", Modifier.size(38.dp)) }
    } else {
        AsyncImage(model = File(path), contentDescription = "Фото автомобиля", contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp)))
    }
}

@Composable
private fun VehicleDetailScreen(
    vm: JournalViewModel, id: Long, onBack: () -> Unit,
    onEditVehicle: () -> Unit, onEditPhotos: () -> Unit,
    onNewRepair: () -> Unit, onEditRepair: (Long) -> Unit
) {
    val vehicle by remember(id) { vm.repo.dao.observeVehicle(id) }.collectAsState(initial = null)
    val repairs by remember(id) { vm.repo.dao.observeRepairs(id) }.collectAsState(initial = emptyList())
    val vehiclePhotos by remember(id) { vm.repo.dao.observePhotos(id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = vehicle
    Scaffold(
        topBar = {
            Header(current?.plateNumber ?: "Машина", onBack) {
                IconButton(onClick = onEditVehicle) { Icon(Icons.Outlined.Edit, "Редактировать машину") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Удалить машину") }
            }
        },
        floatingActionButton = { FloatingActionButton(onClick = onNewRepair) { Icon(Icons.Outlined.Add, "Добавить ремонт") } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (current != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CarPicture(current.photoPath, Modifier.size(92.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(current.plateNumber, style = MaterialTheme.typography.titleLarge)
                            if (current.model.isNotBlank()) {
                                Text(current.model, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            // Общая галерея автомобиля, включая снимки, добавленные при ремонтах.
            if (current != null) {
                item { VehiclePhotoGallery(vehiclePhotos, onEditPhotos) }
            }
            item {
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск по описанию") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true)
            }
            val shown = repairs.filter { it.description.contains(search, ignoreCase = true) }
            if (shown.isEmpty()) {
                item { Text("Ремонтов пока нет. Нажмите +, чтобы добавить.") }
            }
            items(shown, key = { it.id }) { repair ->
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dateText(repair.dateEpochDay), fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { onEditRepair(repair.id) }) {
                            Icon(Icons.Outlined.Edit, "Открыть ремонт")
                        }
                    }
                    Text("${money(repair.price)} ${if (repair.currencyCode == "RUB") "₽" else "сом"}",
                        style = MaterialTheme.typography.titleMedium)
                    Text(repair.description, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить машину?") },
            text = { Text("Удалятся все её ремонты и копии фотографий. Это действие нельзя отменить.") },
            confirmButton = { TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    try { vm.deleteVehicle(id); onBack() }
                    catch (e: Exception) { error = e.localizedMessage ?: "Не удалось удалить машину" }
                }
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
    }
    if (error != null) {
        AlertDialog(onDismissRequest = { error = null }, confirmButton = {
            TextButton(onClick = { error = null }) { Text("OK") }
        }, title = { Text("Ошибка") }, text = { Text(error ?: "") })
    }
}

/** Все снимки машины находятся в одном месте, независимо от того, при каком ремонте добавлены. */
@Composable
private fun VehiclePhotoGallery(photos: List<PhotoEntity>, onEditPhotos: () -> Unit) {
    var selectedPath by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Фотографии машины (${photos.size}/10)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = onEditPhotos) {
                Text(if (photos.isEmpty()) "Добавить фото" else "Изменить фото")
            }
        }
        if (photos.isEmpty()) {
            Text("Пока нет фотографий. Их можно добавить здесь или при новом ремонте.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(photos, key = { it.id }) { photo ->
                    AsyncImage(
                        model = File(photo.filePath),
                        contentDescription = "Открыть фотографию машины",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(104.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { selectedPath = photo.filePath }
                    )
                }
            }
        }
    }
    val openedPath = selectedPath
    if (openedPath != null) {
        Dialog(onDismissRequest = { selectedPath = null }) {
            Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Фото машины", modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { selectedPath = null }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Закрыть")
                        }
                    }
                    AsyncImage(
                        model = File(openedPath),
                        contentDescription = "Фотография машины",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 520.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VehicleEditorScreen(
    vm: JournalViewModel, vehicleId: Long?, onBack: () -> Unit, onSaved: (Long) -> Unit
) {
    val vehicleFlow = remember(vehicleId) {
        if (vehicleId == null) flowOf(null) else vm.repo.dao.observeVehicle(vehicleId)
    }
    val existing by vehicleFlow.collectAsState(initial = null)
    val photoFlow = remember(vehicleId) {
        if (vehicleId == null) flowOf(emptyList<PhotoEntity>()) else vm.repo.dao.observePhotos(vehicleId)
    }
    val dbPhotos by photoFlow.collectAsState(initial = null as List<PhotoEntity>?)
    var plate by remember(vehicleId) { mutableStateOf("") }
    var model by remember(vehicleId) { mutableStateOf("") }
    val photos = remember(vehicleId) { mutableStateListOf<PhotoChoice>() }
    var loaded by remember(vehicleId) { mutableStateOf(false) }
    LaunchedEffect(existing?.id, dbPhotos) {
        if (existing != null && dbPhotos != null && !loaded) {
            plate = existing!!.plateNumber
            model = existing!!.model
            photos.clear()
            photos.addAll(dbPhotos!!.map { PhotoChoice(it.id, File(it.filePath).toUri()) })
            loaded = true
        }
    }
    var date by remember(vehicleId) { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var currency by remember(vehicleId) { mutableStateOf("KGS") }
    var price by remember(vehicleId) { mutableStateOf("") }
    var description by remember(vehicleId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val isEditing = vehicleId != null
    Scaffold(topBar = { Header(if (isEditing) "Редактировать машину" else "Новая машина", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .imePadding().verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(15.dp)
        ) {
            OutlinedTextField(plate, { plate = it }, label = { Text("Госномер") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
            OutlinedTextField(model, { model = it }, label = { Text("Модель машины") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
            if (!isEditing) {
                Text("Фотографии машины", fontWeight = FontWeight.SemiBold)
                PhotoPicker(
                    photos = photos.toList(), maxItems = 10,
                    onAdd = { photos.appendPhotos(it, 10) },
                    onRemove = { photos.remove(it) }
                )
            }
            if (!isEditing) {
                HorizontalDivider()
                Text("Первый ремонт", fontWeight = FontWeight.SemiBold)
                Text("Можно оставить поля ремонта пустыми и добавить его позже.",
                    style = MaterialTheme.typography.bodySmall)
                RepairFields(date, { date = it }, currency, { currency = it },
                    price, { price = it }, description, { description = it })
            }
            Button(
                onClick = {
                    if (plate.isBlank()) {
                        error = "Укажите госномер"; return@Button
                    }
                    val hasRepair = description.isNotBlank() || price.isNotBlank()
                    val amount = price.replace(',', '.').toDoubleOrNull()
                    if (!isEditing && hasRepair && (description.isBlank() || amount == null || amount < 0)) {
                        error = "Для ремонта заполните описание и корректную стоимость"; return@Button
                    }
                    busy = true
                    focus.clearFocus()
                    scope.launch {
                        try {
                            val vehicle = VehicleEntity(
                                id = existing?.id ?: 0, plateNumber = plate.trim(),
                                brand = existing?.brand ?: "", model = model.trim(),
                                photoPath = existing?.photoPath,
                                createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis(),
                                cloudId = existing?.cloudId ?: "", syncDirty = true
                            )
                            if (isEditing) {
                                vm.editVehicleDetails(vehicle)
                                onSaved(vehicleId!!)
                            } else {
                                val newPhotoUris = photos.filter { it.existingId == null }.map { it.uri }
                                val repair = if (hasRepair) RepairDraft(date, amount!!, description.trim(), currency) else null
                                val newId = vm.addVehicle(vehicle, repair, newPhotoUris)
                                onSaved(newId)
                            }
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Не удалось сохранить машину"
                        } finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(30.dp)
            ) { Text(if (busy) "Сохраняем..." else if (isEditing) "Сохранить изменения" else "Сохранить машину") }
            Spacer(Modifier.height(20.dp))
        }
    }
    if (error != null) ErrorDialog(error!!) { error = null }
}

/** Photos are edited separately from plate/model, so changing the gallery cannot edit vehicle details. */
@Composable
private fun VehiclePhotoEditorScreen(
    vm: JournalViewModel, vehicleId: Long, onBack: () -> Unit, onSaved: () -> Unit
) {
    val vehicle by remember(vehicleId) { vm.repo.dao.observeVehicle(vehicleId) }
        .collectAsState(initial = null)
    val storedPhotos by remember(vehicleId) { vm.repo.dao.observePhotos(vehicleId) }
        .collectAsState(initial = null as List<PhotoEntity>?)
    val photos = remember(vehicleId) { mutableStateListOf<PhotoChoice>() }
    var loaded by remember(vehicleId) { mutableStateOf(false) }
    LaunchedEffect(vehicle?.id, storedPhotos) {
        if (vehicle != null && storedPhotos != null && !loaded) {
            photos.clear()
            photos.addAll(storedPhotos!!.map { PhotoChoice(it.id, File(it.filePath).toUri()) })
            loaded = true
        }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Scaffold(topBar = { Header("Фотографии машины", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!loaded) {
                CircularProgressIndicator()
            } else {
                Text("Фотографии (${photos.size}/10)",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                PhotoPicker(
                    photos = photos.toList(), maxItems = 10,
                    onAdd = { photos.appendPhotos(it, 10) },
                    onRemove = { photos.remove(it) }
                )
                Button(
                    onClick = {
                        val current = vehicle ?: return@Button
                        busy = true
                        scope.launch {
                            try {
                                vm.editVehicle(
                                    current,
                                    photos.mapNotNull { it.existingId }.toSet(),
                                    photos.filter { it.existingId == null }.map { it.uri }
                                )
                                onSaved()
                            } catch (e: Exception) {
                                error = e.localizedMessage ?: "Не удалось сохранить фотографии"
                            } finally { busy = false }
                        }
                    },
                    enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(30.dp)
                ) { Text(if (busy) "Сохраняем..." else "Сохранить фотографии") }
            }
        }
    }
    if (error != null) ErrorDialog(error!!) { error = null }
}

@Composable
private fun RepairEditorScreen(
    vm: JournalViewModel, vehicleId: Long, repairId: Long?,
    onBack: () -> Unit, onSaved: () -> Unit
) {
    val repairFlow = remember(repairId) { if (repairId == null) flowOf(null) else vm.repo.dao.observeRepair(repairId) }
    val existing by repairFlow.collectAsState(initial = null)
    val vehiclePhotos by remember(vehicleId) { vm.repo.dao.observePhotos(vehicleId) }
        .collectAsState(initial = emptyList())
    var loaded by remember(repairId) { mutableStateOf(false) }
    var date by remember(repairId) { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var currency by remember(repairId) { mutableStateOf("KGS") }
    var price by remember(repairId) { mutableStateOf("") }
    var description by remember(repairId) { mutableStateOf("") }
    val newVehiclePhotos = remember(repairId) { mutableStateListOf<PhotoChoice>() }
    LaunchedEffect(existing?.id) {
        if (repairId != null && existing != null && !loaded) {
            date = existing!!.dateEpochDay
            currency = existing!!.currencyCode
            price = priceForInput(existing!!.price)
            description = existing!!.description
            loaded = true
        }
    }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    Scaffold(topBar = {
        Header(if (repairId == null) "Новый ремонт" else "Ремонт", onBack) {
            if (repairId != null) IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Outlined.Delete, "Удалить ремонт")
            }
        }
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .imePadding().verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RepairFields(date, { date = it }, currency, { currency = it },
                price, { price = it }, description, { description = it })
            Text("Добавить фотографии машины", fontWeight = FontWeight.SemiBold)
            PhotoPicker(newVehiclePhotos.toList(), (10 - vehiclePhotos.size).coerceAtLeast(0),
                { newVehiclePhotos.appendPhotos(it, (10 - vehiclePhotos.size).coerceAtLeast(0)) },
                { removed -> newVehiclePhotos.remove(removed) })
            Button(onClick = {
                val amount = price.replace(',', '.').toDoubleOrNull()
                if (amount == null || amount < 0 || description.isBlank()) {
                    error = "Заполните описание и корректную стоимость"; return@Button
                }
                focus.clearFocus()
                busy = true
                scope.launch {
                    try {
                        vm.saveRepair(
                            RepairEntity(id = repairId ?: 0, vehicleId = vehicleId,
                                dateEpochDay = date, price = amount,
                                description = description.trim(), currencyCode = currency,
                                repairType = existing?.repairType ?: "Общий ремонт",
                                cloudId = existing?.cloudId ?: "", syncDirty = true),
                            newVehiclePhotos.map { it.uri }
                        )
                        onSaved()
                    } catch (e: Exception) { error = e.localizedMessage ?: "Не удалось сохранить ремонт" }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(30.dp)) {
                Text(if (busy) "Сохраняем..." else "Сохранить ремонт")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmDelete && repairId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить ремонт?") },
            text = { Text("Ремонт будет удалён. Фотографии машины сохранятся.") },
            confirmButton = { TextButton(onClick = {
                confirmDelete = false
                scope.launch {
                    try { vm.deleteRepair(repairId); onSaved() }
                    catch (e: Exception) { error = e.localizedMessage ?: "Ошибка удаления" }
                }
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }
    if (error != null) ErrorDialog(error!!) { error = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepairFields(
    date: Long, onDate: (Long) -> Unit,
    currency: String, onCurrency: (String) -> Unit,
    price: String, onPrice: (String) -> Unit,
    description: String, onDescription: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.DateRange, null)
        Spacer(Modifier.width(8.dp))
        Text("Дата: ${dateText(date)}")
    }
    Text("Валюта", fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = currency == "KGS", onClick = { onCurrency("KGS") }, label = { Text("Сом") })
        FilterChip(selected = currency == "RUB", onClick = { onCurrency("RUB") }, label = { Text("Рубль") })
    }
    OutlinedTextField(
        value = price, onValueChange = onPrice, label = { Text("Стоимость") },
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Next), singleLine = true
    )
    OutlinedTextField(
        value = description, onValueChange = onDescription,
        label = { Text("Описание ремонта") }, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
        minLines = 3, maxLines = 7,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
    )
    if (showPicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = {
                picker.selectedDateMillis?.let { onDate(Math.floorDiv(it, 86_400_000L)) }
                showPicker = false
            }) { Text("ОК") } },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Отмена") } }
        ) { DatePicker(state = picker) }
    }
}

@Composable
private fun ErrorDialog(message: String, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Не удалось выполнить действие") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = dismiss) { Text("ОК") } })
}

private fun SnapshotStateList<PhotoChoice>.appendPhotos(newOnes: List<PhotoChoice>, max: Int) {
    newOnes.forEach { choice ->
        if (size < max && none { it.uri == choice.uri }) add(choice)
    }
}
