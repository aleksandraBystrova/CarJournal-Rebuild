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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import com.carjournal.rebuild.data.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

private sealed interface Page {
    data object ListPage : Page
    data class VehiclePage(val vehicleId: Long) : Page
    data class EditVehiclePage(val vehicleId: Long?) : Page
    data class EditRepairPage(val vehicleId: Long, val repairId: Long?) : Page
}

@Composable
fun JournalApp(vm: JournalViewModel) {
    var page: Page by remember { mutableStateOf(Page.ListPage) }
    val back: () -> Unit = {
        page = when (val p = page) {
            is Page.ListPage -> Page.ListPage
            is Page.VehiclePage -> Page.ListPage
            is Page.EditVehiclePage -> p.vehicleId?.let { Page.VehiclePage(it) } ?: Page.ListPage
            is Page.EditRepairPage -> Page.VehiclePage(p.vehicleId)
        }
    }
    BackHandler(enabled = page !is Page.ListPage) { back() }
    when (val p = page) {
        is Page.ListPage -> VehicleListScreen(vm, onOpen = { page = Page.VehiclePage(it) },
            onCreate = { page = Page.EditVehiclePage(null) })
        is Page.VehiclePage -> VehicleDetailScreen(vm, p.vehicleId,
            onBack = back,
            onEditVehicle = { page = Page.EditVehiclePage(p.vehicleId) },
            onNewRepair = { page = Page.EditRepairPage(p.vehicleId, null) },
            onEditRepair = { page = Page.EditRepairPage(p.vehicleId, it) })
        is Page.EditVehiclePage -> VehicleEditorScreen(vm, p.vehicleId, onBack = back, onSaved = {
            page = if (p.vehicleId != null) Page.VehiclePage(p.vehicleId) else Page.VehiclePage(it)
        })
        is Page.EditRepairPage -> RepairEditorScreen(vm, p.vehicleId, p.repairId,
            onBack = back, onSaved = { page = Page.VehiclePage(p.vehicleId) })
    }
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
private fun VehicleListScreen(vm: JournalViewModel, onOpen: (Long) -> Unit, onCreate: () -> Unit) {
    val vehicles by vm.vehicles.collectAsState()
    val totals by vm.totals.collectAsState()
    var query by remember { mutableStateOf("") }
    val filtered = remember(vehicles, query) {
        vehicles.filter { it.plateNumber.contains(query, ignoreCase = true) ||
            it.model.contains(query, ignoreCase = true) || it.brand.contains(query, ignoreCase = true) }
    }
    Scaffold(
        topBar = { Header("Машины") },
        floatingActionButton = { LargeFloatingActionButton(onClick = onCreate) {
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
                    label = { Text("Поиск: номер, модель") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                SoftCard {
                    Text("Всего машин: ${totals.totalVehicles}", fontWeight = FontWeight.Medium)
                    Text("Машин с ремонтом: ${totals.repairedVehicles}")
                    Text("Ремонтов: ${totals.repairsCount}")
                    Text("Общая стоимость: ${money(totals.totalSom)} сом")
                    Text("В рублях: ${money(totals.totalRub)} ₽")
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
                            Text(vehicle.plateNumber.ifBlank { vehicle.model }, fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium)
                            Text(listOf(vehicle.brand, vehicle.model).filter { it.isNotBlank() }.joinToString(" "))
                            Text("Ремонтов: ${vehicle.repairCount}", style = MaterialTheme.typography.bodySmall)
                            if (vehicle.repairCount > 0) {
                                Text("${money(vehicle.totalSom)} сом · ${money(vehicle.totalRub)} ₽",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Icon(Icons.Outlined.ChevronRight, null)
                    }
                }
            }
        }
    }
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
    onEditVehicle: () -> Unit, onNewRepair: () -> Unit, onEditRepair: (Long) -> Unit
) {
    val vehicle by remember(id) { vm.repo.dao.observeVehicle(id) }.collectAsState(initial = null)
    val repairs by remember(id) { vm.repo.dao.observeRepairs(id) }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = vehicle
    Scaffold(
        topBar = {
            Header(current?.plateNumber?.ifBlank { current.model } ?: "Машина", onBack) {
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
                            Text(listOf(current.brand, current.model).filter(String::isNotBlank).joinToString(" "),
                                style = MaterialTheme.typography.titleLarge)
                            Text(current.plateNumber)
                        }
                    }
                }
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
                    RepairPhotoCount(vm, repair.id)
                    TextButton(onClick = { onEditRepair(repair.id) }) { Text("Подробнее / редактировать") }
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

@Composable
private fun RepairPhotoCount(vm: JournalViewModel, repairId: Long) {
    val photos by remember(repairId) { vm.repo.dao.observePhotos(repairId) }.collectAsState(initial = emptyList())
    Text("Фото: ${photos.size}", color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun VehicleEditorScreen(
    vm: JournalViewModel, vehicleId: Long?, onBack: () -> Unit, onSaved: (Long) -> Unit
) {
    val flow = remember(vehicleId) {
        if (vehicleId == null) flowOf(null) else vm.repo.dao.observeVehicle(vehicleId)
    }
    val existing by flow.collectAsState(initial = null)
    var plate by remember(vehicleId) { mutableStateOf("") }
    var brand by remember(vehicleId) { mutableStateOf("") }
    var model by remember(vehicleId) { mutableStateOf("") }
    var cover by remember(vehicleId) { mutableStateOf<PhotoChoice?>(null) }
    var removeCover by remember(vehicleId) { mutableStateOf(false) }
    var loaded by remember(vehicleId) { mutableStateOf(false) }
    LaunchedEffect(existing?.id) {
        if (existing != null && !loaded) {
            plate = existing!!.plateNumber
            brand = existing!!.brand
            model = existing!!.model
            cover = existing!!.photoPath?.let { PhotoChoice(existingId = -1L, uri = File(it).toUri()) }
            loaded = true
        }
    }
    var addFirstRepair by remember { mutableStateOf(true) }
    var date by remember { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var currency by remember { mutableStateOf("KGS") }
    var price by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val repairPhotos = remember { mutableStateListOf<PhotoChoice>() }
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            OutlinedTextField(brand, { brand = it }, label = { Text("Марка (необязательно)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            OutlinedTextField(model, { model = it }, label = { Text("Модель") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
            Text("Фото машины", fontWeight = FontWeight.SemiBold)
            PhotoPicker(
                photos = listOfNotNull(cover), maxItems = 1,
                onAdd = { chosen -> cover = chosen.firstOrNull(); removeCover = false },
                onRemove = { cover = null; removeCover = true }
            )
            if (!isEditing) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Добавить первый ремонт", fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Switch(checked = addFirstRepair, onCheckedChange = { addFirstRepair = it })
                }
                if (addFirstRepair) {
                    RepairFields(date, { date = it }, currency, { currency = it },
                        price, { price = it }, description, { description = it })
                    Text("Фотографии ремонта", fontWeight = FontWeight.SemiBold)
                    PhotoPicker(repairPhotos.toList(), 10, { repairPhotos.appendPhotos(it, 10) },
                        { removed -> repairPhotos.remove(removed) })
                }
            }
            Button(
                onClick = {
                    if (plate.isBlank() && model.isBlank()) {
                        error = "Укажите госномер или модель"; return@Button
                    }
                    val amount = if (!isEditing && addFirstRepair) price.replace(',', '.').toDoubleOrNull() else null
                    if (!isEditing && addFirstRepair && (description.isBlank() || amount == null || amount < 0)) {
                        error = "Для ремонта заполните описание и корректную стоимость"; return@Button
                    }
                    busy = true
                    focus.clearFocus()
                    scope.launch {
                        try {
                            val vehicle = VehicleEntity(
                                id = existing?.id ?: 0, plateNumber = plate.trim(),
                                brand = brand.trim(), model = model.trim(),
                                photoPath = existing?.photoPath,
                                createdAtEpochMs = existing?.createdAtEpochMs ?: System.currentTimeMillis()
                            )
                            if (isEditing) {
                                vm.editVehicle(vehicle,
                                    if (cover?.existingId == null) cover?.uri else null, removeCover)
                                onSaved(vehicleId!!)
                            } else {
                                val repair = if (addFirstRepair) RepairDraft(date, amount!!,
                                    description.trim(), currency) else null
                                val newId = vm.addVehicle(vehicle, cover?.uri, repair,
                                    if (addFirstRepair) repairPhotos.map { it.uri } else emptyList())
                                onSaved(newId)
                            }
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Не удалось сохранить машину"
                        } finally { busy = false }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(30.dp)
            ) { Text(if (busy) "Сохраняем..." else if (isEditing) "Сохранить изменения" else "Сохранить машину и ремонт") }
            Spacer(Modifier.height(20.dp))
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
    val photoFlow = remember(repairId) {
        if (repairId == null) flowOf(emptyList<PhotoEntity>()) else vm.repo.dao.observePhotos(repairId)
    }
    val photosFromDb by photoFlow.collectAsState(initial = null as List<PhotoEntity>?)
    var loaded by remember(repairId) { mutableStateOf(false) }
    var date by remember(repairId) { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var currency by remember(repairId) { mutableStateOf("KGS") }
    var price by remember(repairId) { mutableStateOf("") }
    var description by remember(repairId) { mutableStateOf("") }
    val photos = remember(repairId) { mutableStateListOf<PhotoChoice>() }
    LaunchedEffect(existing?.id, photosFromDb) {
        if (repairId != null && existing != null && photosFromDb != null && !loaded) {
            date = existing!!.dateEpochDay
            currency = existing!!.currencyCode
            price = existing!!.price.toString()
            description = existing!!.description
            photos.clear()
            photos.addAll(photosFromDb!!.map { PhotoChoice(it.id, File(it.filePath).toUri()) })
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
            Text("Фотографии", fontWeight = FontWeight.SemiBold)
            PhotoPicker(photos.toList(), 10, { photos.appendPhotos(it, 10) },
                { removed -> photos.remove(removed) })
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
                                repairType = existing?.repairType ?: "Общий ремонт"),
                            photos.mapNotNull { it.existingId }.toSet(),
                            photos.filter { it.existingId == null }.map { it.uri }
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
            text = { Text("Ремонт и его фотографии будут удалены без возможности восстановления.") },
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
