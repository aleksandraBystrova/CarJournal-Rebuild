package com.carjournal.rebuild.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Поля, представляющие старую схему APK; база этой сборки отдельная. */
@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val plateNumber: String,
    val brand: String = "",
    val model: String,
    val photoPath: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val cloudId: String = "",
    @ColumnInfo(defaultValue = "1") val syncDirty: Boolean = true
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
    val currencyCode: String = "KGS",
    @ColumnInfo(defaultValue = "''") val cloudId: String = "",
    @ColumnInfo(defaultValue = "1") val syncDirty: Boolean = true
)

@Entity(
    tableName = "photos",
    foreignKeys = [ForeignKey(
        entity = VehicleEntity::class,
        parentColumns = ["id"], childColumns = ["vehicleId"],
        onDelete = ForeignKey.CASCADE
    )], indices = [Index("vehicleId")]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val filePath: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val cloudId: String = "",
    @ColumnInfo(defaultValue = "1") val syncDirty: Boolean = true,
    val storagePath: String? = null
)

@Entity(tableName = "pending_deletions")
data class PendingDeletion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val cloudId: String
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
    val repairsCount: Int,
    val totalSom: Double,
    val totalRub: Double
)

/** Сводка ремонтов по году, выбранному в поле даты ремонта. */
data class YearlyRepairStats(
    val year: Int,
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

    @Query("SELECT * FROM photos WHERE vehicleId=:vehicleId ORDER BY id ASC")
    fun observePhotos(vehicleId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE vehicleId=:vehicleId ORDER BY id ASC")
    suspend fun photosNow(vehicleId: Long): List<PhotoEntity>

    @Query("SELECT filePath FROM photos WHERE vehicleId=:vehicleId")
    suspend fun vehiclePhotoPathsNow(vehicleId: Long): List<String>

    @Query("SELECT photoPath FROM vehicles WHERE id=:vehicleId LIMIT 1")
    suspend fun vehicleCoverPathNow(vehicleId: Long): String?

    @Query("""
        SELECT
        (SELECT COUNT(*) FROM repairs) AS repairsCount,
        (SELECT COALESCE(SUM(price), 0) FROM repairs WHERE currencyCode='KGS') AS totalSom,
        (SELECT COALESCE(SUM(price), 0) FROM repairs WHERE currencyCode='RUB') AS totalRub
    """)
    fun observeTotals(): Flow<JournalTotals>

    @Query("""
        SELECT
            CAST(strftime('%Y', dateEpochDay * 86400, 'unixepoch') AS INTEGER) AS year,
            CAST(COUNT(*) AS INTEGER) AS repairsCount,
            COALESCE(SUM(CASE WHEN currencyCode = 'KGS' THEN price ELSE 0 END), 0) AS totalSom,
            COALESCE(SUM(CASE WHEN currencyCode = 'RUB' THEN price ELSE 0 END), 0) AS totalRub
        FROM repairs
        GROUP BY CAST(strftime('%Y', dateEpochDay * 86400, 'unixepoch') AS INTEGER)
        ORDER BY year DESC
    """)
    fun observeYearlyStats(): Flow<List<YearlyRepairStats>>

    // All cloud IDs are stable across retries. Rows without cloudId came from Room v2.
    @Query("SELECT * FROM vehicles ORDER BY id") suspend fun allVehicles(): List<VehicleEntity>
    @Query("SELECT * FROM repairs ORDER BY id") suspend fun allRepairs(): List<RepairEntity>
    @Query("SELECT * FROM photos ORDER BY id") suspend fun allPhotos(): List<PhotoEntity>
    @Query("SELECT * FROM vehicles WHERE id = :id LIMIT 1") suspend fun vehicleNow(id: Long): VehicleEntity?
    @Query("SELECT * FROM repairs WHERE id = :id LIMIT 1") suspend fun repairNow(id: Long): RepairEntity?
    @Query("SELECT * FROM vehicles WHERE cloudId = :uuid LIMIT 1") suspend fun vehicleByCloudId(uuid: String): VehicleEntity?
    @Query("SELECT * FROM repairs WHERE cloudId = :uuid LIMIT 1") suspend fun repairByCloudId(uuid: String): RepairEntity?
    @Query("SELECT * FROM photos WHERE cloudId = :uuid LIMIT 1") suspend fun photoByCloudId(uuid: String): PhotoEntity?
    @Insert suspend fun insertDeletion(deletion: PendingDeletion)
    @Query("SELECT * FROM pending_deletions ORDER BY id") suspend fun pendingDeletions(): List<PendingDeletion>
    @Query("DELETE FROM pending_deletions WHERE id = :id") suspend fun clearDeletion(id: Long)
    @Query("UPDATE vehicles SET cloudId = :uuid WHERE id = :id") suspend fun setVehicleCloudId(id: Long, uuid: String)
    @Query("UPDATE repairs SET cloudId = :uuid WHERE id = :id") suspend fun setRepairCloudId(id: Long, uuid: String)
    @Query("UPDATE photos SET cloudId = :uuid WHERE id = :id") suspend fun setPhotoCloudId(id: Long, uuid: String)
    @Query("UPDATE photos SET storagePath = :path WHERE id = :id") suspend fun setPhotoStoragePath(id: Long, path: String)
    @Query("UPDATE vehicles SET syncDirty = 0 WHERE id = :id AND cloudId = :uuid AND plateNumber = :plate AND model = :model AND brand = :brand")
    suspend fun markVehicleClean(id: Long, uuid: String, plate: String, model: String, brand: String)
    @Query("UPDATE repairs SET syncDirty = 0 WHERE id = :id AND cloudId = :uuid AND description = :description AND price = :price AND currencyCode = :currency AND dateEpochDay = :day")
    suspend fun markRepairClean(id: Long, uuid: String, description: String, price: Double, currency: String, day: Long)
    @Query("UPDATE photos SET syncDirty = 0 WHERE id = :id AND cloudId = :uuid AND storagePath = :path")
    suspend fun markPhotoClean(id: Long, uuid: String, path: String)

    @Insert suspend fun insertVehicle(vehicle: VehicleEntity): Long
    @Update suspend fun updateVehicle(vehicle: VehicleEntity)
    @Query("DELETE FROM vehicles WHERE id=:id") suspend fun deleteVehicle(id: Long)
    @Insert suspend fun insertRepair(repair: RepairEntity): Long
    @Update suspend fun updateRepair(repair: RepairEntity)
    @Query("DELETE FROM repairs WHERE id=:id") suspend fun deleteRepair(id: Long)
    @Insert suspend fun insertPhoto(photo: PhotoEntity): Long
    @Update suspend fun updatePhoto(photo: PhotoEntity)
    @Query("DELETE FROM photos WHERE id IN (:ids)") suspend fun deletePhotos(ids: List<Long>)
    @Query("UPDATE vehicles SET photoPath = :path WHERE id = :vehicleId")
    suspend fun updateCoverPath(vehicleId: Long, path: String?)
}

@Database(
    entities = [VehicleEntity::class, RepairEntity::class, PhotoEntity::class, PendingDeletion::class],
    version = 3,
    exportSchema = false
)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun dao(): JournalDao
}

/** Переносит старые фотографии ремонтов в общую галерею автомобиля.
 * Копии файлов остаются на месте; меняется только связь в Room.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE photos_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                vehicleId INTEGER NOT NULL,
                filePath TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                FOREIGN KEY(vehicleId) REFERENCES vehicles(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO photos_new (id, vehicleId, filePath, createdAtEpochMs)
            SELECT p.id, r.vehicleId, p.filePath, p.createdAtEpochMs
            FROM photos p INNER JOIN repairs r ON r.id = p.repairId
        """.trimIndent())
        // Старое фото обложки теперь тоже часть галереи машины.
        db.execSQL("""
            INSERT INTO photos_new (vehicleId, filePath, createdAtEpochMs)
            SELECT v.id, v.photoPath, v.createdAtEpochMs
            FROM vehicles v
            WHERE v.photoPath IS NOT NULL AND v.photoPath != ''
              AND NOT EXISTS (
                  SELECT 1 FROM photos_new p WHERE p.vehicleId = v.id AND p.filePath = v.photoPath
              )
        """.trimIndent())
        db.execSQL("DROP TABLE photos")
        db.execSQL("ALTER TABLE photos_new RENAME TO photos")
        db.execSQL("CREATE INDEX index_photos_vehicleId ON photos(vehicleId)")
    }
}

/** Room v2 -> v3: adds stable cloud identifiers and persistent deletion queue. No old data is removed. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf("vehicles", "repairs", "photos")) {
            db.execSQL("ALTER TABLE $table ADD COLUMN cloudId TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $table ADD COLUMN syncDirty INTEGER NOT NULL DEFAULT 1")
        }
        db.execSQL("ALTER TABLE photos ADD COLUMN storagePath TEXT")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_deletions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                entityType TEXT NOT NULL,
                cloudId TEXT NOT NULL
            )
        """.trimIndent())
    }
}
