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
        entity = VehicleEntity::class,
        parentColumns = ["id"], childColumns = ["vehicleId"],
        onDelete = ForeignKey.CASCADE
    )], indices = [Index("vehicleId")]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
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
    @Query("UPDATE vehicles SET photoPath = :path WHERE id = :vehicleId")
    suspend fun updateCoverPath(vehicleId: Long, path: String?)
}

@Database(
    entities = [VehicleEntity::class, RepairEntity::class, PhotoEntity::class],
    version = 2,
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
