package com.carjournal.rebuild.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Поля, представляющие старую схему APK; база этой сборки отдельная. */
@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val plateNumber: String,
    val brand: String = "",
    val model: String,
    val photoPath: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "repairs",
    foreignKeys = [ForeignKey(
        entity = VehicleEntity::class,
        parentColumns = ["id"], childColumns = ["vehicleId"],
        onDelete = ForeignKey.CASCADE
    )], indices = [Index("vehicleId")]
)
data class RepairEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val dateEpochDay: Long,
    val repairType: String = "Общий ремонт",
    val price: Double,
    val description: String,
    val currencyCode: String = "KGS"
)

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(
        entity = RepairEntity::class,
        parentColumns = ["id"], childColumns = ["repairId"],
        onDelete = ForeignKey.CASCADE
    )], indices = [Index("repairId")]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repairId: Long,
    val filePath: String,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class VehicleWithStats(
    val id: Long,
    val plateNumber: String,
    val brand: String,
    val model: String,
    val photoPath: String?,
    val createdAtEpochMs: Long,
    val repairCount: Int,
    val totalSom: Double,
    val totalRub: Double
)

data class JournalTotals(
    val totalVehicles: Int,
    val repairedVehicles: Int,
    val repairsCount: Int,
    val totalSom: Double,
    val totalRub: Double
)

@Dao
interface JournalDao {
    @Query("""
        SELECT v.id, v.plateNumber, v.brand, v.model, v.photoPath, v.createdAtEpochMs,
               CAST(COUNT(r.id) AS INTEGER) AS repairCount,
               COALESCE(SUM(CASE WHEN r.currencyCode='KGS' THEN r.price ELSE 0 END), 0) AS totalSom,
               COALESCE(SUM(CASE WHEN r.currencyCode='RUB' THEN r.price ELSE 0 END), 0) AS totalRub
        FROM vehicles v LEFT JOIN repairs r ON r.vehicleId = v.id
        GROUP BY v.id
        ORDER BY v.createdAtEpochMs DESC, v.id DESC
    """)
    fun observeVehicles(): Flow<List<VehicleWithStats>>

    @Query("SELECT * FROM vehicles WHERE id=:id LIMIT 1")
    fun observeVehicle(id: Long): Flow<VehicleEntity?>

    @Query("SELECT * FROM repairs WHERE vehicleId=:vehicleId ORDER BY dateEpochDay DESC, id DESC")
    fun observeRepairs(vehicleId: Long): Flow<List<RepairEntity>>

    @Query("SELECT * FROM repairs WHERE id=:id LIMIT 1")
    fun observeRepair(id: Long): Flow<RepairEntity?>

    @Query("SELECT * FROM photos WHERE repairId=:repairId ORDER BY id ASC")
    fun observePhotos(repairId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE repairId=:repairId ORDER BY id ASC")
    suspend fun photosNow(repairId: Long): List<PhotoEntity>

    @Query("SELECT p.filePath FROM photos p JOIN repairs r ON p.repairId=r.id WHERE r.vehicleId=:vehicleId")
    suspend fun vehiclePhotoPathsNow(vehicleId: Long): List<String>

    @Query("SELECT photoPath FROM vehicles WHERE id=:vehicleId LIMIT 1")
    suspend fun vehicleCoverPathNow(vehicleId: Long): String?

    @Query("""
        SELECT
        (SELECT COUNT(*) FROM vehicles) AS totalVehicles,
        (SELECT COUNT(DISTINCT vehicleId) FROM repairs) AS repairedVehicles,
        (SELECT COUNT(*) FROM repairs) AS repairsCount,
        (SELECT COALESCE(SUM(price), 0) FROM repairs WHERE currencyCode='KGS') AS totalSom,
        (SELECT COALESCE(SUM(price), 0) FROM repairs WHERE currencyCode='RUB') AS totalRub
    """)
    fun observeTotals(): Flow<JournalTotals>

    @Insert suspend fun insertVehicle(vehicle: VehicleEntity): Long
    @Update suspend fun updateVehicle(vehicle: VehicleEntity)
    @Query("DELETE FROM vehicles WHERE id=:id") suspend fun deleteVehicle(id: Long)
    @Insert suspend fun insertRepair(repair: RepairEntity): Long
    @Update suspend fun updateRepair(repair: RepairEntity)
    @Query("DELETE FROM repairs WHERE id=:id") suspend fun deleteRepair(id: Long)
    @Insert suspend fun insertPhoto(photo: PhotoEntity): Long
    @Query("DELETE FROM photos WHERE id IN (:ids)") suspend fun deletePhotos(ids: List<Long>)
}

@Database(
    entities = [VehicleEntity::class, RepairEntity::class, PhotoEntity::class],
    version = 1,
    exportSchema = false
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun dao(): JournalDao
}
