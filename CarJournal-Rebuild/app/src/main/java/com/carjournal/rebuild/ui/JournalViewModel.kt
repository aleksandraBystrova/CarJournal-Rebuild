package com.carjournal.rebuild.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carjournal.rebuild.JournalApplication
import com.carjournal.rebuild.data.JournalTotals
import com.carjournal.rebuild.data.RepairDraft
import com.carjournal.rebuild.data.RepairEntity
import com.carjournal.rebuild.data.VehicleEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class JournalViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as JournalApplication).repository
    val vehicles = repo.dao.observeVehicles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totals = repo.dao.observeTotals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalTotals(0, 0, 0, 0.0, 0.0))

    suspend fun addVehicle(vehicle: VehicleEntity, cover: Uri?, repair: RepairDraft?, photos: List<Uri>) =
        repo.createVehicle(vehicle, cover, repair, photos)

    suspend fun editVehicle(vehicle: VehicleEntity, cover: Uri?, removeCover: Boolean) =
        repo.updateVehicle(vehicle, cover, removeCover)

    suspend fun saveRepair(repair: RepairEntity, keepIds: Set<Long>, newPhotos: List<Uri>) =
        repo.saveRepair(repair, keepIds, newPhotos)

    suspend fun deleteVehicle(id: Long) = repo.deleteVehicle(id)
    suspend fun deleteRepair(id: Long) = repo.deleteRepair(id)
}
