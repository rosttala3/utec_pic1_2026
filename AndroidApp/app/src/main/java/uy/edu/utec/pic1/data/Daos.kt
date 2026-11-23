package uy.edu.utec.pic1.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EnsayoDao {
    @Query("SELECT * FROM ensayo ORDER BY inicioMs DESC")
    fun observeAll(): Flow<List<Ensayo>>

    @Query("SELECT * FROM ensayo WHERE id = :id")
    fun observe(id: Long): Flow<Ensayo?>

    @Query("SELECT * FROM ensayo WHERE id = :id")
    suspend fun get(id: Long): Ensayo?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: Ensayo): Long

    @Update
    suspend fun update(e: Ensayo)

    @Query("DELETE FROM ensayo WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE ensayo SET estado = 'abortado', motivo = :motivo, finMs = :finMs WHERE estado = 'en_curso'")
    suspend fun abortRunning(motivo: String, finMs: Long)

    @Query("UPDATE ensayo SET inicioMs = :inicioMs WHERE id = :id")
    suspend fun setInicio(id: Long, inicioMs: Long)

    @Query("UPDATE ensayo SET estado = :estado, motivo = :motivo, finMs = :finMs WHERE id = :id")
    suspend fun close(id: Long, estado: String, motivo: String?, finMs: Long)
}

@Dao
interface MuestraDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(m: List<Muestra>)

    @Query("SELECT * FROM muestra WHERE ensayoId = :id ORDER BY seq")
    fun observe(id: Long): Flow<List<Muestra>>

    @Query("SELECT * FROM muestra WHERE ensayoId = :id ORDER BY seq")
    suspend fun all(id: Long): List<Muestra>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM muestra")
    suspend fun maxSeq(): Long

    @Query("SELECT COUNT(*) FROM muestra WHERE ensayoId = :id")
    fun count(id: Long): Flow<Int>

    @Query("DELETE FROM muestra WHERE ensayoId = :id")
    suspend fun deleteFor(id: Long)
}

@Database(entities = [Ensayo::class, Muestra::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ensayos(): EnsayoDao
    abstract fun muestras(): MuestraDao
}
