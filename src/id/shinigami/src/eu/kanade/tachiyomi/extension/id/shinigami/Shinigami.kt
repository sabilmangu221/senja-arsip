package eu.kanade.tachiyomi.extension.id.shinigami

import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.time.Instant

@Source
abstract class Shinigami :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val apiHeaders: Headers
        get() = headersBuilder()
            .add("Accept", "application/json")
            .add("DNT", "1")
            .add("Sec-GPC", "1")
            .build()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(3)

    // ====================== Domain resolution ======================
    //
    // Endpoints are resolved at runtime:
    //   1. A domain typed by the user in this source's settings (highest priority).
    //   2. A remote config (config.json) on the `repo` branch, fetched at most
    //      once per process.
    //   3. The last API host that worked in this process.
    //   4. The built-in default below.

    private fun normalizeBase(raw: String?): String? {
        val trimmed = raw?.trim()?.trimEnd('/') ?: return null
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun apiCandidates(): List<String> {
        val candidates = linkedSetOf<String>()
        normalizeBase(preferences.getString(PREF_API_DOMAIN, ""))?.let { candidates.add(it) }
        workingApiBase?.let { candidates.add(it) }
        candidates.addAll(remoteApiBases)
        candidates.add(DEFAULT_API_BASE)
        return candidates.toList()
    }

    private suspend fun refreshRemoteConfig() {
        if (remoteConfigLoaded) return
        remoteConfigLoaded = true
        try {
            val config = client.get(CONFIG_URL, apiHeaders, Fetch.XHR)
                .parseAs<ShinigamiRemoteConfigDto>()
            remoteApiBases = config.api.mapNotNull(::normalizeBase)
            remoteWebBases = config.web.mapNotNull(::normalizeBase)
            remoteWebBases.firstOrNull()?.let { resolved ->
                if (preferences.getString(PREF_WEB_DOMAIN_RESOLVED, "") != resolved) {
                    preferences.edit().putString(PREF_WEB_DOMAIN_RESOLVED, resolved).apply()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Remote config is best-effort; built-in defaults still apply.
        }
    }

    private suspend fun getApi(buildUrl: (String) -> HttpUrl): Response {
        refreshRemoteConfig()
        var lastError: Exception? = null
        for (apiBase in apiCandidates()) {
            try {
                val response = client.get(buildUrl(apiBase), apiHeaders, Fetch.XHR)
                workingApiBase = apiBase
                return response
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Tidak ada endpoint API Shinigami yang bisa dihubungi")
    }

    private fun webBase(): String {
        normalizeBase(preferences.getString(PREF_WEB_DOMAIN, ""))?.let { return it }
        normalizeBase(preferences.getString(PREF_WEB_DOMAIN_RESOLVED, ""))?.let { return it }
        remoteWebBases.firstOrNull()?.let { return it }
        return baseUrl
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_WEB_DOMAIN
            title = "Domain web (override)"
            summary = "Kosongkan untuk otomatis. Isi kalau Shinigami pindah domain, " +
                "mis. https://12.shinigami.asia — dipakai untuk membuka judul/chapter di WebView."
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_API_DOMAIN
            title = "Domain API (override, lanjutan)"
            summary = "Kosongkan untuk otomatis. Isi kalau host API berubah, mis. https://api.shngm.io"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    // ====================== Popular ======================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = getApi { apiBase ->
            "$apiBase/v1/manga/list".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("page_size", "30")
                .addQueryParameter("sort", "popularity")
                .build()
        }
        return parseMangaList(response)
    }

    // ====================== Latest ======================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = getApi { apiBase ->
            "$apiBase/v1/manga/list".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("page_size", "30")
                .addQueryParameter("sort", "latest")
                .build()
        }
        return parseMangaList(response)
    }

    // ====================== Search ======================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val response = getApi { apiBase ->
            val url = "$apiBase/v1/manga/list".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("page_size", "30")

            if (query.isNotEmpty()) {
                url.addQueryParameter("q", query)
            }

            filters.filterIsInstance<UriFilter>().forEach {
                it.addToUri(url)
            }

            url.build()
        }
        return parseMangaList(response)
    }

    private fun parseMangaList(response: Response): MangasPage {
        val rootObject = response.parseAs<ShinigamiBrowseDto>()
        val projectList = rootObject.data.map(::popularMangaFromObject)
        val hasNextPage = rootObject.meta.totalPage?.let { rootObject.meta.page < it } ?: false
        return MangasPage(projectList, hasNextPage)
    }

    private fun popularMangaFromObject(obj: ShinigamiBrowseDataDto): SManga = SManga.create().apply {
        title = obj.title.orEmpty()
        thumbnail_url = obj.thumbnail
        url = obj.mangaId.orEmpty()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        SortOrderFilter(),
        StatusFilter(),
        FormatFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    // ====================== Manga Details & Chapters ======================

    override fun getMangaUrl(manga: SManga): String = "${webBase()}/series/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "${webBase()}/chapter/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != "shinigami.asia" && !url.host.endsWith(".shinigami.asia")) return null
        val firstSegment = url.pathSegments.firstOrNull()
        if (firstSegment != "series") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null

        val manga = SManga.create().apply {
            this.url = id
        }
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        if (manga.url.startsWith("/series/")) {
            throw Exception("Migrate dari $name ke $name (ekstensi yang sama)")
        }

        val detailsDeferred = if (fetchDetails) {
            async { fetchMangaDetails(manga) }
        } else {
            null
        }

        val chaptersDeferred = if (fetchChapters) {
            async { fetchChapterList(manga) }
        } else {
            null
        }

        val updatedManga = detailsDeferred?.await() ?: manga
        val updatedChapters = chaptersDeferred?.await() ?: chapters

        SMangaUpdate(updatedManga, updatedChapters)
    }

    private suspend fun fetchMangaDetails(manga: SManga): SManga {
        val response = getApi { apiBase -> "$apiBase/v1/manga/detail/${manga.url}".toHttpUrl() }
        val mangaDetailsResponse = response.parseAs<ShinigamiMangaDetailDto>()
        val mangaDetails = mangaDetailsResponse.data

        return manga.apply {
            mangaDetails.title?.takeIf { it.isNotBlank() }?.let { title = it }
            mangaDetails.thumbnail?.let { thumbnail_url = it }
            author = mangaDetails.taxonomy["Author"]?.joinToString { it.name }.orEmpty()
            artist = mangaDetails.taxonomy["Artist"]?.joinToString { it.name }.orEmpty()
            status = mangaDetails.status.toStatus()
            description = buildString {
                append(mangaDetails.description)
                mangaDetails.altTitle?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative Title: ").append(it)
                }
            }

            val genres = mangaDetails.taxonomy["Genre"]?.joinToString { it.name }.orEmpty()
            val type = mangaDetails.taxonomy["Format"]?.joinToString { it.name }.orEmpty()
            genre = listOf(genres, type).filter { it.isNotBlank() }.joinToString()
        }
    }

    private fun Int.toStatus(): Int = when (this) {
        1 -> SManga.ONGOING
        2 -> SManga.COMPLETED
        3 -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val response = getApi { apiBase -> "$apiBase/v1/chapter/${manga.url}/list?page_size=3000".toHttpUrl() }
        val result = response.parseAs<ShinigamiChapterListDto>()
        return result.chapterList.map(::chapterFromObject)
    }

    private fun chapterFromObject(obj: ShinigamiChapterListDataDto): SChapter = SChapter.create().apply {
        date_upload = Instant.tryParse(obj.date)
        name = "Chapter ${obj.name.toString().removeSuffix(".0")} ${obj.title}".trim()
        chapter_number = obj.name.toFloat()
        url = obj.chapterId
    }

    // ====================== Page List ======================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.url.startsWith("/series/")) {
            throw Exception("Migrate dari $name ke $name (ekstensi yang sama)")
        }

        val response = getApi { apiBase -> "$apiBase/v1/chapter/detail/${chapter.url}".toHttpUrl() }
        val result = response.parseAs<ShinigamiPageListDto>()

        return result.pageList.chapterPage.pages.mapIndexed { index, imageName ->
            Page(index, imageUrl = "${result.pageList.baseUrl}${result.pageList.chapterPage.path}$imageName")
        }
    }

    override fun getImageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .add("DNT", "1")
            .add("Sec-GPC", "1")
            .build()

        return Request.Builder()
            .url(page.imageUrl!!)
            .headers(imageHeaders)
            .get()
            .build()
    }

    companion object {
        private const val DEFAULT_API_BASE = "https://api.shngm.io"
        private const val CONFIG_URL =
            "https://raw.githubusercontent.com/sabilmangu221/senja-arsip/repo/config.json"

        private const val PREF_WEB_DOMAIN = "web_domain"
        private const val PREF_API_DOMAIN = "api_domain"
        private const val PREF_WEB_DOMAIN_RESOLVED = "web_domain_resolved"

        @Volatile
        private var remoteConfigLoaded: Boolean = false

        @Volatile
        private var remoteApiBases: List<String> = emptyList()

        @Volatile
        private var remoteWebBases: List<String> = emptyList()

        @Volatile
        private var workingApiBase: String? = null
    }
}
