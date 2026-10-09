package com.alalay.app.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.alalay.app.agent.MemoryAccess
import com.alalay.app.agent.Router

/**
 * Memory schema per 04-§7. Hard rules: aliases/prefs/rolling context only,
 * never message bodies; writes happen only from code after user
 * confirmation — the model has no memory-write tool (07-§9).
 */

@Entity(tableName = "aliases")
data class Alias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val target_name: String,
    val target_kind: String,           // 'contact' | 'thread' | 'app'
    val created_at: Long,
)

@Entity(tableName = "prefs")
data class Pref(
    @PrimaryKey val key: String,       // 'speak_language' | 'speech_rate'
    val value: String,
)

@Entity(tableName = "rolling_context")
data class RollingContext(
    @PrimaryKey val key: String,       // 'last_contact' | 'last_task_kind'
    val value: String,
    val updated_at: Long,
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM aliases")
    suspend fun allAliases(): List<Alias>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlias(a: Alias): Long

    @Query("DELETE FROM aliases WHERE alias = :alias AND target_name = :target")
    suspend fun deleteAlias(alias: String, target: String)

    @Query("SELECT value FROM prefs WHERE key = :key")
    suspend fun getPref(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPref(p: Pref)

    @Query("SELECT value FROM rolling_context WHERE key = :key")
    suspend fun getRolling(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setRolling(r: RollingContext)
}

@Database(entities = [Alias::class, Pref::class, RollingContext::class], version = 1)
abstract class AlalayDatabase : RoomDatabase() {
    abstract fun dao(): MemoryDao

    companion object {
        @Volatile private var instance: AlalayDatabase? = null

        fun get(context: Context): AlalayDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AlalayDatabase::class.java,
                    "alalay.db",
                ).build().also { instance = it }
            }
    }
}

/**
 * Alias resolution logic — pure so the ambiguity path is JVM-testable.
 * Returns every saved target whose alias the name matches; the caller
 * treats ≥2 results as ambiguity material (FR-08, US-05).
 */
object AliasResolver {
    fun findAliasesMentioned(task: String, aliases: List<Alias>): List<Alias> {
        val t = Router.normalize(task)
        return aliases.filter { a ->
            val alias = Router.normalize(a.alias)
            " $t ".contains(" $alias ")
        }
    }

    fun resolve(name: String, aliases: List<Alias>): List<Alias> {
        val n = Router.normalize(name)
        return aliases.filter { a ->
            val alias = Router.normalize(a.alias)
            val target = Router.normalize(a.target_name)
            alias == n || alias.contains(n) || n.contains(alias) ||
                target == n || target.contains(n) || n.contains(target)
        }
    }
}

class MemoryStore(private val dao: MemoryDao) : MemoryAccess {

    constructor(context: Context) : this(AlalayDatabase.get(context).dao())

    override fun resolveAlias(name: String): List<String> {
        // DAO is suspend; a non-suspend path is needed inside hot routing
        // code, so this reads through a tiny in-memory cache kept by
        // refreshFromDb() at task boundaries.
        return cachedAliases.let { list -> AliasResolver.resolve(name, list) }
            .map { it.target_name }
            .distinct()
    }

    override fun aliasHintsFor(task: String): String =
        AliasResolver.findAliasesMentioned(task, cachedAliases)
            .joinToString(", ") { "${it.alias} → ${it.target_name}" }

    override fun memoryLine(): String {
        val contact = cachedRolling["last_contact"]
        val lang = cachedPrefs["speak_language"] ?: "en"
        return buildString {
            contact?.let { append("last_contact=").append(it) }
            if (isNotEmpty()) append(' ')
            append("lang=").append(lang)
        }
    }

    override fun language(): String = cachedPrefs["speak_language"] ?: "en"

    override suspend fun saveAlias(alias: String, target: String, kind: String) {
        dao.insertAlias(
            Alias(
                alias = alias,
                target_name = target,
                target_kind = kind,
                created_at = System.currentTimeMillis(),
            )
        )
        refreshFromDb()
    }

    suspend fun deleteAlias(alias: String, target: String) {
        dao.deleteAlias(alias, target)
        refreshFromDb()
    }

    override suspend fun setRolling(key: String, value: String) {
        dao.setRolling(
            RollingContext(key, value, System.currentTimeMillis())
        )
        cachedRolling[key] = value
    }

    suspend fun setPref(key: String, value: String) {
        dao.setPref(Pref(key, value))
        cachedPrefs[key] = value
    }

    suspend fun allAliases(): List<Alias> = dao.allAliases()

    /** Reload the read cache; call at app start and after every write. */
    suspend fun refreshFromDb() {
        cachedAliases = dao.allAliases()
        cachedRolling.clear()
        cachedPrefs.clear()
        dao.getRolling("last_contact")?.let { cachedRolling["last_contact"] = it }
        dao.getRolling("last_task_kind")?.let { cachedRolling["last_task_kind"] = it }
        dao.getPref("speak_language")?.let { cachedPrefs["speak_language"] = it }
        dao.getPref("speech_rate")?.let { cachedPrefs["speech_rate"] = it }
    }

    private var cachedAliases: List<Alias> = emptyList()
    private val cachedRolling = mutableMapOf<String, String>()
    private val cachedPrefs = mutableMapOf<String, String>()
}
