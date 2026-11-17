package com.carjournal.rebuild.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Создание ремонта, до 10 фото. Стоимость хранится в исходной валюте, без конвертации. */
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

    /** Удаляем только собственные копии файлов, не фотографии из галереи телефона. */
    private fun deleteOwnPhoto(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        if (file.parentFile?.canonicalFile == photoFolder.canonicalFile) file.delete()
    }

    suspend fun createVehicle(
        vehicle: VehicleEntity,
        coverUri: Uri?,
        firstRepair: RepairDraft?,
        repairPhotos: List<Uri>
    ): Long = withContext(Dispatchers.IO) {
        require(firstRepair != null || repairPhotos.isEmpty())
        require(repairPhotos.size <= 10) { "Можно прикрепить до 10 фотографий" }
        val imported = mutableListOf<String>()
        try {
            val coverPath = coverUri?.let { importPhoto(it).also(imported::add) }
            val photoPaths = repairPhotos.map { importPhoto(it).also(imported::add) }
            db.withTransaction {
                val vehicleId = dao.insertVehicle(vehicle.copy(photoPath = coverPath))
                if (firstRepair != null) {
                    val repairId = dao.insertRepair(
                        RepairEntity(
                            vehicleId = vehicleId,
                            dateEpochDay = firstRepair.dateEpochDay,
                            price = firstRepair.price,
                            description = firstRepair.description.trim(),
                            currencyCode = firstRepair.currencyCode
                        )
                    )
                    photoPaths.forEach { path -> dao.insertPhoto(PhotoEntity(repairId = repairId, filePath = path)) }
                }
                vehicleId
            }
        } catch (exception: Exception) {
            imported.forEach(::deleteOwnPhoto)
            throw exception
        }
    }

    suspend fun updateVehicle(
        vehicle: VehicleEntity,
        newCoverUri: Uri?,
        removeCover: Boolean
    ) = withContext(Dispatchers.IO) {
        var importedPath: String? = null
        try {
            importedPath = newCoverUri?.let(::importPhoto)
            val newPath = if (newCoverUri != null) importedPath else if (removeCover) null else vehicle.photoPath
            db.withTransaction { dao.updateVehicle(vehicle.copy(photoPath = newPath)) }
            if (newPath != vehicle.photoPath) deleteOwnPhoto(vehicle.photoPath)
        } catch (exception: Exception) {
            deleteOwnPhoto(importedPath)
            throw exception
        }
    }

    suspend fun saveRepair(
        repair: RepairEntity,
        keepPhotoIds: Set<Long>,
        newPhotoUris: List<Uri>
    ) = withContext(Dispatchers.IO) {
        require(keepPhotoIds.size + newPhotoUris.size <= 10)
        val imported = mutableListOf<String>()
        try {
            val current = if (repair.id == 0L) emptyList() else dao.photosNow(repair.id)
            val removed = current.filter { it.id !in keepPhotoIds }
            newPhotoUris.forEach { imported.add(importPhoto(it)) }
            db.withTransaction {
                val repairId = if (repair.id == 0L) dao.insertRepair(repair) else {
                    dao.updateRepair(repair)
                    repair.id
                }
                if (removed.isNotEmpty()) dao.deletePhotos(removed.map { it.id })
                imported.forEach { dao.insertPhoto(PhotoEntity(repairId = repairId, filePath = it)) }
            }
            removed.forEach { deleteOwnPhoto(it.filePath) }
        } catch (exception: Exception) {
            imported.forEach(::deleteOwnPhoto)
            throw exception
        }
    }

    suspend fun deleteRepair(repairId: Long) = withContext(Dispatchers.IO) {
        val paths = dao.photosNow(repairId).map { it.filePath }
        db.withTransaction { dao.deleteRepair(repairId) }
        paths.forEach(::deleteOwnPhoto)
    }

    suspend fun deleteVehicle(vehicleId: Long) = withContext(Dispatchers.IO) {
        val allPaths = dao.vehiclePhotoPathsNow(vehicleId) + listOfNotNull(dao.vehicleCoverPathNow(vehicleId))
        db.withTransaction { dao.deleteVehicle(vehicleId) }
        allPaths.forEach(::deleteOwnPhoto)
    }
}
