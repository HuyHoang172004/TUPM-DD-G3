package com.adaptivenews.app.ui

import android.content.Context
import com.adaptivenews.app.core.contract.NewsRepository
import com.adaptivenews.app.core.contract.PolicyProvider
import com.adaptivenews.app.platform.ArticleRequestSignal
import com.adaptivenews.app.ui.fixture.FakeNewsRepository

/** Integration seam for Hoàn's repository; the fixture keeps UI development independent. */
object RepositoryBinding {
    var factory: (Context, PolicyProvider, ArticleRequestSignal) -> NewsRepository =
        { context, _, _ -> FakeNewsRepository(context) }
    var isFixture: Boolean = true
    var resetData: suspend (NewsRepository) -> Unit = { repository ->
        (repository as? FakeNewsRepository)?.reset()
    }
}
