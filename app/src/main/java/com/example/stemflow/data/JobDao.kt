package com.example.stemflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Query("SELECT * FROM stemflow_jobs ORDER BY createdAt DESC")
    fun getAllJobs(): Flow<List<JobEntity>>

    @Query("SELECT * FROM stemflow_jobs WHERE id = :id")
    suspend fun getJobById(id: Long): JobEntity?

    @Query("SELECT * FROM stemflow_jobs WHERE phase NOT IN ('COMPLETE', 'FAILED', 'CANCELLED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveJob(): JobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: JobEntity): Long

    @Update
    suspend fun updateJob(job: JobEntity)

    @Query("DELETE FROM stemflow_jobs WHERE id = :id")
    suspend fun deleteJobById(id: Long)

    @Query("UPDATE stemflow_jobs SET phase = 'CANCELLED', updatedAt = :time WHERE phase NOT IN ('COMPLETE', 'FAILED', 'CANCELLED')")
    suspend fun cancelActiveJobs(time: Long = System.currentTimeMillis())
}
