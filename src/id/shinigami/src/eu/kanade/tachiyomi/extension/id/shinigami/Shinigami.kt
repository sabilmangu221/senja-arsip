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
import keiyoushi.utils.MarkdownDescription
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import kotlin.time.Instant

@Source
abstract class Shinigami :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()

    private val apiHeaders: Headers
        get() = headersBuilder()
            .set("Accept", "application/json")
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
                "mis. https://s1.shinigami.mx — dipakai untuk membuka judul/chapter di WebView."
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_API_DOMAIN
            title = "Domain API (override, lanjutan)"
            summary = "Kosongkan untuk otomatis. Isi kalau host API berubah, mis. https://api.shinigami.io"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    // ====================== Popular ======================

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = getApi { apiBase ->
            "$apiBase/v1/manga/list".toHttpUrl().newBuilder()
                .addQueryParameter("offset", ((page - 1) * 30).toString())
                .addQueryParameter("limit", "30")
                .addQueryParameter("sort", "popularity")
                .build()
        }
        return parseMangaList(response)
    }

    // ====================== Latest ======================

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = getApi { apiBase ->
            "$apiBase/v1/manga/list".toHttpUrl().newBuilder()
                .addQueryParameter("offset", ((page - 1) * 30).toString())
                .addQueryParameter("limit", "30")
                .addQueryParameter("sort", "latest")
                .build()
        }
        return parseMangaList(response)
    }

    // ====================== Search ======================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val response = getApi { apiBase ->
            val url = "$apiBase/v1/manga/list".toHttpUrl().newBuilder()
                .addQueryParameter("offset", ((page - 1) * 30).toString())
                .addQueryParameter("limit", "30")

            if (query.isNotEmpty()) {
                url.addQueryParameter("q", query)
            }

            filters.firstInstanceOrNull<SortFilter>()?.let {
                url.addQueryParameter("sort", it.sort)
                url.addQueryParameter("sort_order", it.order)
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
        val projectList = rootObject.data.map(::mangaFromObject)
        val hasNextPage = rootObject.meta.hasNext
        return MangasPage(projectList, hasNextPage)
    }

    private fun mangaFromObject(obj: ShinigamiBrowseDataDto): SManga = SManga.create().apply {
        title = obj.title
        thumbnail_url = obj.thumbnail
        url = obj.mangaId
    }

    // ====================== Manga Details & Chapters ======================

    override fun getMangaUrl(manga: SManga): String = "${webBase()}/series/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "${webBase()}/chapter/${chapter.url}"

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val siteDomain = webBase().toHttpUrl().host.substringAfter(".")
        if (
            url.host != siteDomain && !url.host.endsWith(".$siteDomain") &&
            url.host != "shinigami.asia" && !url.host.endsWith(".shinigami.asia")
        ) {
            return null
        }
        val firstSegment = url.pathSegments.firstOrNull()
        if (firstSegment != "series") return null
        val id = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null

        val manga = SManga.create().apply {
            this.url = id
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
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
            val format = mangaDetails.taxonomy["Format"]?.joinToString { it.name }
            description = MarkdownDescription.Builder()
                .description(mangaDetails.description)
                .rating(
                    mangaDetails.weightedScore
                        ?.takeIf { it > 0 }
                        ?.let { String.format(Locale.US, "%.1f", it).toDouble() },
                    votes = mangaDetails.ratingCount?.takeIf { it > 0 },
                )
                .field("Bookmark", mangaDetails.bookmarkCount?.let(::compactCount))
                .field("Views", mangaDetails.viewCount?.let(::compactCount))
                .info(format)
                .altTitles(
                    mangaDetails.altTitle?.split("|").orEmpty(),
                    label = "Alternative Title(s)",
                )
                .build()

            val genres = mangaDetails.taxonomy["Genre"]?.joinToString { it.name }.orEmpty()
            genre = listOf(genres, format.orEmpty()).filter { it.isNotBlank() }.joinToString()
        }
    }

    private fun Int.toStatus(): Int = when (this) {
        1 -> SManga.ONGOING
        2 -> SManga.COMPLETED
        3 -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    private suspend fun fetchChapterList(manga: SManga): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var cursor: String? = null

        while (true) {
            val currentCursor = cursor
            val response = getApi { apiBase ->
                "$apiBase/v1/chapter/${manga.url}/list".toHttpUrl().newBuilder()
                    .apply { currentCursor?.let { addQueryParameter("cursor", it) } }
                    .build()
            }
            val result = response.parseAs<ShinigamiChapterListDto>()
            chapters.addAll(result.chapterList.map(::chapterFromObject))

            cursor = result.meta.nextCursor?.takeIf { it.isNotBlank() }
            if (!result.meta.hasNext || cursor == null) break
        }

        return chapters
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

    // ====================== Filters ======================

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = coroutineScope {
        suspend fun fetchTaxonomy(type: String): TaxonomyListDto {
            val response = getApi { apiBase ->
                "$apiBase/v1/taxonomy/list?type=$type&limit=100&offset=0".toHttpUrl()
            }
            return response.parseAs<TaxonomyListDto>()
        }

        val (genres, themes) = listOf(
            async { fetchTaxonomy("genre") },
            async { fetchTaxonomy("theme") },
        ).awaitAll()

        FilterDataDto(
            genres = genres.data,
            themes = themes.data,
        ).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val filterData = data?.parseAs<FilterDataDto>() ?: FilterDataDto()
        fun terms(list: List<TaxonomyTermDto>): Array<Pair<String, String>> = list.map { it.name to it.slug }.toTypedArray()

        return FilterList(
            SortFilter(),
            FormatFilter(),
            TypeFilter(),
            StatusFilter(),
            GenreFilter(terms(filterData.genres)),
            ThemeFilter(terms(filterData.themes)),
            SubFormatFilter(),
            ChapterRangeFilter(),
        )
    }

    companion object {
        private const val DEFAULT_API_BASE = "https://api.shinigami.io"
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

private fun compactCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> String.format(Locale.US, "%.1f", value / 1e3) + "K"
    value < 1_000_000_000 -> String.format(Locale.US, "%.1f", value / 1e6) + "M"
    else -> String.format(Locale.US, "%.1f", value / 1e9) + "B"
}
