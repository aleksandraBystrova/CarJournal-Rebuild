package com.carjournal.rebuild.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class RepairDraft(
    val dateEpochDay: Long,
    val price: Double,
    val description: String,
    val currencyCode: String
)

class JournalRepository(
    private val context: Context,
    private val db: JournalDatabase
) {
    val dao = db.dao()
    private val photoFolder = File(context.filesDir, "journal_photos")

    private fun importPhoto(uri: Uri): String {
        photoFolder.mkdirs()
        val file = File(photoFolder, "${UUID.randomUUID()}.jpg")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Невозможно открыть фотографию" }
                file.outputStream().use { output -> input.copyTo(output) }
            }
            return file.absolutePath
        } catch (exception: Exception) {
            file.delete()
            throw exception
        }
    }

    private fun deleteOwnPhoto(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (file.parentFile?.canonicalFile == photoFolder.canonicalFile) file.delete()
    }

    suspend fun createVehicle(
        vehicle: VehicleEntity,
        firstRepair: RepairDraft?,
        photoUris: List<Uri>
    ): Long = withContext(Dispatchers.IO) {
        require(photoUris.size <= 10) { "Можно прикрепить до 10 фотографий" }
        val imported = mutableListOf<String>()
        try {
            photoUris.forEach { imported.add(importPhoto(it)) }
            db.withTransaction {
                val vehicleId = dao.insertVehicle(vehicle.copy(photoPath = imported.firstOrNull()))
                imported.forEach { dao.insertPhoto(PhotoEntity(vehicleId = vehicleId, filePath = it)) }
                if (firstRepair != null) {
                    dao.insertRepair(RepairEntity(
                        vehicleId = vehicleId,
                        dateEpochDay = firstRepair.dateEpochDay,
                        price = firstRepair.price,
                        description = firstRepair.description.trim(),
                        currencyCode = firstRepair.currencyCode
                    ))
                }
                vehicleId
            }
        } catch (exception: Exception) {
            imported.forEach(::deleteOwnPhoto)
            throw exception
        }
    }

    /** Все фотографии принадлежат машине, даже если их добавили на экране ремонта. */
    suspend fun updateVehicle(
        vehicle: VehicleEntity,
        keepPhotoIds: Set<Long>,
        photoUris: List<Uri>
    ) = withContext(Dispatchers.IO) {
        saveVehiclePhotos(vehicle.id, keepPhotoIds, photoUris, vehicle)
    }

    private suspend fun saveVehiclePhotos(
        vehicleId: Long,
        keepPhotoIds: Set<Long>,
        photoUris: List<Uri>,
        editedVehicle: VehicleEntity? = null
    ) {
        val current = dao.photosNow(vehicleId)
        val retained = current.filter { it.id in keepPhotoIds }
        require(retained.size + photoUris.size <= 10) { "Можно прикрепить до 10 фотографий" }
        val removed = current.filter { it.id !in keepPhotoIds }
        val imported = mutableListOf<String>()
        try {
            photoUris.forEach { imported.add(importPhoto(it)) }
            val coverPath = (retained.firstOrNull()?.filePath ?: imported.firstOrNull())
            db.withTransaction {
                if (editedVehicle != null) dao.updateVehicle(editedVehicle.copy(photoPath = coverPath))
                else dao.updateCoverPath(vehicleId, coverPath)
                if (removed.isNotEmpty()) dao.deletePhotos(removed.map { it.id })
                imported.forEach { dao.insertPhoto(PhotoEntity(vehicleId = vehicleId, filePath = it)) }
            }
            removed.forEach { deleteOwnPhoto(it.filePath) }
        } catch (exception: Exception) {
            imported.forEach(::deleteOwnPhoto)
            throw exception
        }
    }

    suspend fun saveRepair(
        repair: RepairEntity,
        newVehiclePhotos: List<Uri>
    ) = withContext(Dispatchers.IO) {
        val current = dao.photosNow(repair.vehicleId)
        require(current.size + newVehiclePhotos.size <= 10) { "В машине может быть не больше 10 фотографий" }
        val imported = mutableListOf<String>()
        try {
            newVehiclePhotos.forEach { imported.add(importPhoto(it)) }
            db.withTransaction {
                if (repair.id == 0L) dao.insertRepair(repair) else dao.updateRepair(repair)
                imported.forEach { dao.insertPhoto(PhotoEntity(vehicleId = repair.vehicleId, filePath = it)) }
                if (current.isEmpty() && imported.isNotEmpty()) dao.updateCoverPath(repair.vehicleId, imported.first())
            }
        } catch (exception: Exception) {
            imported.forEach(::deleteOwnPhoto)
            throw exception
        }
    }

    suspend fun deleteRepair(repairId: Long) = withContext(Dispatchers.IO) {
        // Удаление ремонта не трогает общую галерею автомобиля.
        db.withTransaction { dao.deleteRepair(repairId) }
    }

    suspend fun deleteVehicle(vehicleId: Long) = withContext(Dispatchers.IO) {
        val paths = dao.vehiclePhotoPathsNow(vehicleId) + listOfNotNull(dao.vehicleCoverPathNow(vehicleId))
        db.withTransaction { dao.deleteVehicle(vehicleId) }
        paths.distinct().forEach(::deleteOwnPhoto)
    }
}
