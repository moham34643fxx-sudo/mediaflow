package com.zdownloader.app.updates

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

// Retain class names so persisted pre-retirement jobs finish without network or Firebase initialization.
class ProductionEnrollmentWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork()=Result.failure(workDataOf("reason" to "control_integration_retired"))
}

class PushTokenWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork()=Result.failure(workDataOf("reason" to "control_integration_retired"))
}

class PushNoticeWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork()=Result.failure(workDataOf("reason" to "control_integration_retired"))
}
