package com.tradelens.local.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "analyses")
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val ticker: String,
    val price: String,
    val aiSummary: String,
    val risk: Int,
    val rawScreenText: String,
    val inferenceMs: Long
)
