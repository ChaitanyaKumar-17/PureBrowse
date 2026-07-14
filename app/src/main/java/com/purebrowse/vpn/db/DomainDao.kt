package com.purebrowse.vpn.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DomainDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserDomain(userDomain: UserDomain)

    @Query("DELETE FROM user_domains WHERE domain = :domain")
    suspend fun deleteUserDomain(domain: String)

    @Query("SELECT * FROM user_domains")
    suspend fun getAllUserDomains(): List<UserDomain>

    @Query("SELECT COUNT(*) FROM auto_domains WHERE domain = :domain LIMIT 1")
    suspend fun isAutoBlocked(domain: String): Int

    @Query("SELECT COUNT(*) FROM user_domains WHERE domain = :domain LIMIT 1")
    suspend fun isUserBlocked(domain: String): Int

    // Used for the WorkManager update process
    @Query("DELETE FROM auto_domains")
    suspend fun clearAutoDomains()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAutoDomains(domains: List<AutoDomain>)

    @Transaction
    suspend fun replaceAllAutoDomains(domains: List<AutoDomain>) {
        clearAutoDomains()
        insertAutoDomains(domains)
    }
}
