package com.edusing.chamcongtvbox

import android.app.Application
import com.edusing.chamcongtvbox.di.AppContainer
import com.edusing.chamcongtvbox.service.CrashRecoveryHandler

/** Lớp Application khởi tạo gốc phụ thuộc Clean Architecture ([AppContainer]) và bộ tự hồi sinh sau crash. */
class AttendanceTvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashRecoveryHandler.install(this)
        container = AppContainer(this)
    }
}
