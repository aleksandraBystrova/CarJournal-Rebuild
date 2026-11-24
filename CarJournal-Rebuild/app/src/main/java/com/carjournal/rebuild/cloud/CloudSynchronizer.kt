package com.carjournal.rebuild.cloud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.carjournal.rebuild.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Offline-first synchronization:
 * - Room is the UI cache and the durable queue of local writes/deletions.
 * - Each row gets a stable UUID, so retrying an upload never duplicates it.
 * - Deleted rows are tombstoned on the server; missing network never drops pending changes.
 * - Remote rows are pulled into Room on sign-in and periodically while the app is open.
 * - Concurrent edits to the same field are last-upload-wins; cloud deletions win over stale edits.
 * No Room table is ever truncated as part of synchronization.
 */
class CloudSynchronizer(
    private val context: Context,
    private val repo: JournalRepository,
    private val client: CloudClient
) {
    private val dao = repo.dao
    private val db = repo.db
    private val mutex = Mutex()
    private val photoFolder = File(context.filesDir, "journal_photos")

    suspend fun sync(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            check(client.signedIn) { "Сначала войдите в аккаунт мастерской" }
            // Remote deletion wins even over a stale pending update from another device.
            val before = client.list("vehicles")
            for (row in before.filter { !it.isNull("deleted_at") }) {
                dao.vehicleByCloudId(row.getString("id"))?.let { removeLocalVehicle(it.id) }
            }
            for (row in client.list("repairs").filter { !it.isNull("deleted_at") }) {
                dao.repairByCloudId(row.getString("id"))?.let { dao.deleteRepair(it.id) }
            }
            for (row in client.list("photos").filter { !it.isNull("deleted_at") }) {
                dao.photoByCloudId(row.getString("id"))?.let { removeLocalPhoto(it) }
            }

            // Persisted outbox is cleared ONLY after the cloud accepted the deletion.
            dao.pendingDeletions().forEach { deletion ->
                client.softDelete(deletion.entityType, deletion.cloudId)
                dao.clearDeletion(deletion.id)
            }

            dao.allVehicles().filter { it.syncDirty }.forEach { local ->
                val cloudId = local.cloudId.ifBlank { UUID.randomUUID().toString().also { dao.setVehicleCloudId(local.id, it) } }
                client.upsert("vehicles", JSONObject()
                    .put("id", cloudId)
                    .put("plate_number", local.plateNumber)
                    .put("created_at", Instant.ofEpochMilli(local.createdAtEpochMs).toString())
                    .put("updated_at", Instant.now().toString()))
                dao.markVehicleClean(local.id, cloudId, local.plateNumber)
            }

            dao.allRepairs().filter { it.syncDirty }.forEach { local ->
                val parent = dao.vehicleNow(local.vehicleId) ?: return@forEach
                val parentCloudId = parent.cloudId
                if (parentCloudId.isBlank()) return@forEach
                val cloudId = local.cloudId.ifBlank { UUID.randomUUID().toString().also { dao.setRepairCloudId(local.id, it) } }
                client.upsert("repairs", JSONObject()
                    .put("id", cloudId)
                    .put("vehicle_id", parentCloudId)
                    .put("repair_date", LocalDate.ofEpochDay(local.dateEpochDay).toString())
                    .put("description", local.description)
                    .put("price", local.price)
                    .put("currency_code", local.currencyCode)
                    .put("updated_at", Instant.now().toString()))
                dao.markRepairClean(local.id, cloudId, local.description, local.price, local.currencyCode, local.dateEpochDay)
            }

            dao.allPhotos().filter { it.syncDirty }.forEach { photo ->
                val vehicle = dao.vehicleNow(photo.vehicleId) ?: return@forEach
                if (vehicle.cloudId.isBlank()) return@forEach
                val cloudId = photo.cloudId.ifBlank { UUID.randomUUID().toString().also { dao.setPhotoCloudId(photo.id, it) } }
                val storagePath = photo.storagePath ?: "${client.userId}/${vehicle.cloudId}/$cloudId.jpg"
                if (photo.storagePath == null) {
                    val file = File(photo.filePath)
                    require(file.isFile) { "Фотография на телефоне не найдена: ${file.name}" }
                    client.uploadPhoto(storagePath, compressImage(file))
                    dao.setPhotoStoragePath(photo.id, storagePath)
                }
                client.upsert("photos", JSONObject()
                    .put("id", cloudId)
                    .put("vehicle_id", vehicle.cloudId)
                    .put("storage_path", storagePath)
                    .put("sort_order", photo.id))
                dao.markPhotoClean(photo.id, cloudId, storagePath)
            }

            // Pull everything, including remote tombstones. Never wipe the local database.
            val remoteVehicles = client.list("vehicles")
            for (remote in remoteVehicles) {
                val cloudId = remote.getString("id")
                val existing = dao.vehicleByCloudId(cloudId)
                if (!remote.isNull("deleted_at")) {
                    existing?.let { removeLocalVehicle(it.id) }
                    continue
                }
                val plate = remote.getString("plate_number")
                val created = Instant.parse(remote.getString("created_at")).toEpochMilli()
                if (existing == null) {
                    dao.insertVehicle(VehicleEntity(plateNumber = plate, model = "", createdAtEpochMs = created,
                        cloudId = cloudId, syncDirty = false))
                } else if (!existing.syncDirty) {
                    dao.updateVehicle(existing.copy(plateNumber = plate, createdAtEpochMs = created, syncDirty = false))
                }
            }

            for (remote in client.list("repairs")) {
                val cloudId = remote.getString("id")
                val existing = dao.repairByCloudId(cloudId)
                if (!remote.isNull("deleted_at")) {
                    existing?.let { dao.deleteRepair(it.id) }
                    continue
                }
                val vehicle = dao.vehicleByCloudId(remote.getString("vehicle_id")) ?: continue
                val date = LocalDate.parse(remote.getString("repair_date")).toEpochDay()
                val fields = RepairEntity(vehicleId = vehicle.id, dateEpochDay = date,
                    price = remote.getDouble("price"), description = remote.getString("description"),
                    currencyCode = remote.getString("currency_code"), cloudId = cloudId, syncDirty = false)
                if (existing == null) dao.insertRepair(fields)
                else if (!existing.syncDirty) dao.updateRepair(fields.copy(id = existing.id, repairType = existing.repairType))
            }

            for (remote in client.list("photos")) {
                val cloudId = remote.getString("id")
                val existing = dao.photoByCloudId(cloudId)
                if (!remote.isNull("deleted_at")) {
                    existing?.let { removeLocalPhoto(it) }
                    continue
                }
                val vehicle = dao.vehicleByCloudId(remote.getString("vehicle_id")) ?: continue
                if (existing?.syncDirty == true) continue
                val path = remote.getString("storage_path")
                val localFile = existing?.filePath?.let(::File)?.takeIf { it.isFile }
                    ?: downloadToLocal(path, cloudId)
                val photo = PhotoEntity(id = existing?.id ?: 0, vehicleId = vehicle.id,
                    filePath = localFile.absolutePath, createdAtEpochMs = existing?.createdAtEpochMs
                        ?: Instant.parse(remote.getString("created_at")).toEpochMilli(),
                    cloudId = cloudId, syncDirty = false, storagePath = path)
                if (existing == null) dao.insertPhoto(photo) else dao.updatePhoto(photo)
            }

            // Cover image is always the first photo in the shared machine gallery.
            dao.allVehicles().forEach { vehicle ->
                val first = dao.photosNow(vehicle.id).firstOrNull()?.filePath
                if (first != vehicle.photoPath) dao.updateCoverPath(vehicle.id, first)
            }
        }
    }

    private suspend fun removeLocalVehicle(id: Long) {
        val files = dao.vehiclePhotoPathsNow(id) + listOfNotNull(dao.vehicleCoverPathNow(id))
        db.withTransaction { dao.deleteVehicle(id) }
        files.forEach(::deletePrivateFile)
    }

    private suspend fun removeLocalPhoto(photo: PhotoEntity) {
        dao.deletePhotos(listOf(photo.id))
        deletePrivateFile(photo.filePath)
    }

    private fun deletePrivateFile(path: String) {
        val file = File(path)
        if (file.parentFile?.canonicalFile == photoFolder.canonicalFile) file.delete()
    }

    private suspend fun downloadToLocal(path: String, cloudId: String): File {
        val bytes = client.downloadPhoto(path)
        photoFolder.mkdirs()
        val file = File(photoFolder, "cloud_$cloudId.jpg")
        val temp = File(photoFolder, "cloud_$cloudId.part")
        temp.outputStream().use { it.write(bytes) }
        check(temp.renameTo(file)) { "Не удалось сохранить полученную фотографию" }
        return file
    }

    /** Resize uploads before sending. Original private image on the phone is untouched. */
    private fun compressImage(file: File): ByteArray {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > 2200 || opts.outHeight / sample > 2200) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Не удалось прочитать фотографию ${file.name}")
        return try {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
