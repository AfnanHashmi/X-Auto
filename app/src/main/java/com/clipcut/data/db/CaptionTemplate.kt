package com.clipcut.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A pre-written caption the user can pick from on the Caption screen. */
@Entity(tableName = "caption_templates")
data class CaptionTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
)
