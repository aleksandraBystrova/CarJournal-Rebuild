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
import com.carjournal.rebuild.data.YearlyRepairStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class JournalViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as JournalApplication).repository
    val vehicles = repo.dao.observeVehicles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totals = repo.dao.observeTotals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JournalTotals(0, 0.0, 0.0))

    val yearlyStats = repo.dao.observeYearlyStats().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList<YearlyRepairStats>()
    )

    suspend fun addVehicle(vehicle: VehicleEntity, repair: RepairDraft?, photos: List<Uri>) =
        repo.createVehicle(vehicle, repair, photos)

    suspend fun editVehicle(vehicle: VehicleEntity, keepPhotoIds: Set<Long>, newPhotos: List<Uri>) =
        repo.updateVehicle(vehicle, keepPhotoIds, newPhotos)

    suspend fun saveRepair(repair: RepairEntity, newVehiclePhotos: List<Uri>) =
        repo.saveRepair(repair, newVehiclePhotos)

    suspend fun deleteVehicle(id: Long) = repo.deleteVehicle(id)
    suspend fun deleteRepair(id: Long) = repo.deleteRepair(id)
}
