package com.purebrowse.vpn.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "auto_domains")
data class AutoDomain(
    @PrimaryKey
    val domain: String
)
