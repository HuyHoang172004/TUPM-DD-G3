package com.adaptivenews.app.ui

import android.app.Application
import androidx.work.Configuration
import com.adaptivenews.app.data.NewsSyncWorkerFactory

class NewsApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(NewsSyncWorkerFactory { RepositoryBinding.data(this).repository })
            .build()
    }
}
