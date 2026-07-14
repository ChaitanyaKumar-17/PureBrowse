package com.purebrowse.vpn.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_domains")
data class UserDomain(
    @PrimaryKey
    val domain: String,
    val addedAt: Long
)
