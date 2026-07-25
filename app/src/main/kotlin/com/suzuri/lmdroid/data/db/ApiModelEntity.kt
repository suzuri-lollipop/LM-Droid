package com.suzuri.lmdroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One model a [ApiProfileEntity] offers, auto-populated from that provider's model list (see ApiProfileRepository.refreshModels). */
@Entity(
    tableName = "api_models",
    foreignKeys = [
        ForeignKey(
            entity = ApiProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class ApiModelEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileId: Long,
    val modelId: String,
)
