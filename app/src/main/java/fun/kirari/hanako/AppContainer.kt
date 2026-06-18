package `fun`.kirari.hanako

import android.content.Context
import `fun`.kirari.hanako.data.SettingsRepository
import `fun`.kirari.hanako.data.SettingsStore
import `fun`.kirari.hanako.localocr.LocalOcrManager
import `fun`.kirari.hanako.network.NetworkClientProvider
import `fun`.kirari.hanako.network.UnifiedLLMClient
import `fun`.kirari.hanako.network.search.LlmSearchJudge
import `fun`.kirari.hanako.network.search.SearchClient
import `fun`.kirari.hanako.network.search.SearchClientImpl
import `fun`.kirari.hanako.network.search.SearchJudge
import `fun`.kirari.hanako.network.search.SearchOrchestrator

internal class AppContainer(appContext: Context) {
    val networkClientProvider = NetworkClientProvider()
    val settingsStore = SettingsStore(appContext)
    val unifiedLLMClient = UnifiedLLMClient(clientProvider = networkClientProvider)
    val localOcrManager = LocalOcrManager(appContext)
    val settingsRepository = SettingsRepository(settingsStore)
    val searchClient: SearchClient = SearchClientImpl(clientProvider = networkClientProvider)
    val searchJudge: SearchJudge = LlmSearchJudge(unifiedLLMClient)
    val searchOrchestrator = SearchOrchestrator(searchClient, searchJudge)
}
