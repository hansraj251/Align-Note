package com.note.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notes")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val pinned: Boolean = false,
    val color: Int = 0,
    val reminderAt: Long? = null,
    val repeat: String = "NONE",
    val updatedAt: Long = System.currentTimeMillis()
)
