package com.carjournal.rebuild.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carjournal.rebuild.JournalApplication
import com.carjournal.rebuild.cloud.CloudClient
import com.carjournal.rebuild.cloud.CloudSynchronizer
import com.carjournal.rebuild.data.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class JournalViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as JournalApplication).repository
    private val cloud = CloudClient(application)
    private val synchronizer = CloudSynchronizer(application, repo, cloud)

    var loggedIn by mutableStateOf(cloud.signedIn)
        private set
    var syncStatus by mutableStateOf("Ожидает синхронизации")
        private set

    val vehicles = repo.dao.observeVehicles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totals = repo.dao.observeTotals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalTotals(0, 0.0, 0.0))
    val yearlyStats = repo.dao.observeYearlyStats().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<YearlyRepairStats>()
    )

    suspend fun signIn(email: String, password: String) {
        cloud.signIn(email, password)
        loggedIn = true
        syncStatus = "Вход выполнен. Загружаем данные..."
    }

    fun signOut() {
        cloud.signOut()
        loggedIn = false
        syncStatus = "Войдите в аккаунт мастерской"
    }

    suspend fun syncNow() {
        if (!loggedIn) return
        syncStatus = "Синхронизация..."
        try {
            synchronizer.sync()
            syncStatus = "Облако: все данные синхронизированы"
        } catch (e: Exception) {
            // Never mark local data as lost or clear it on network failures.
            syncStatus = "Не удалось синхронизировать. Данные сохранены на телефоне. Проверьте интернет."
        }
    }

    private fun triggerSync() {
        syncStatus = "Сохранено локально. Ожидает облако..."
        viewModelScope.launch { syncNow() }
    }

    suspend fun addVehicle(vehicle: VehicleEntity, repair: RepairDraft?, photos: List<Uri>): Long {
        val id = repo.createVehicle(vehicle, repair, photos)
        triggerSync()
        return id
    }

    suspend fun editVehicle(vehicle: VehicleEntity, keepPhotoIds: Set<Long>, newPhotos: List<Uri>) {
        repo.updateVehicle(vehicle, keepPhotoIds, newPhotos)
        triggerSync()
    }

    suspend fun editVehicleDetails(vehicle: VehicleEntity) {
        repo.updateVehicleDetails(vehicle)
        triggerSync()
    }

    suspend fun saveRepair(repair: RepairEntity, newVehiclePhotos: List<Uri>) {
        repo.saveRepair(repair, newVehiclePhotos)
        triggerSync()
    }

    suspend fun deleteVehicle(id: Long) {
        repo.deleteVehicle(id)
        triggerSync()
    }

    suspend fun deleteRepair(id: Long) {
        repo.deleteRepair(id)
        triggerSync()
    }
}
