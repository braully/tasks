package org.tasks.caldav

import android.content.Context
import androidx.annotation.WorkerThread
import at.bitfire.cert4android.CustomCertManager
import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.Response.HrefRelation
import at.bitfire.dav4jvm.XmlUtils.NS_APPLE_ICAL
import at.bitfire.dav4jvm.XmlUtils.NS_CALDAV
import at.bitfire.dav4jvm.XmlUtils.NS_CARDDAV
import at.bitfire.dav4jvm.XmlUtils.NS_WEBDAV
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.*
import at.bitfire.dav4jvm.property.ResourceType.Companion.CALENDAR
import com.todoroo.astrid.helper.UUIDHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import org.tasks.DebugNetworkInterceptor
import org.tasks.R
import org.tasks.Strings.isNullOrEmpty
import org.tasks.data.CaldavAccount
import org.tasks.data.CaldavCalendar
import org.tasks.preferences.Preferences
import org.tasks.security.KeyStoreEncryption
import org.tasks.ui.DisplayableException
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import org.xmlpull.v1.XmlSerializer
import timber.log.Timber
import java.io.IOException
import java.io.StringWriter
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLContext

class CaldavClient {
    private val encryption: KeyStoreEncryption
    private val preferences: Preferences
    private val interceptor: DebugNetworkInterceptor
    val httpClient: OkHttpClient?
    private val httpUrl: HttpUrl?
    private val context: Context
    private val basicDigestAuthHandler: BasicDigestAuthHandler?
    private var foreground = false

    @Inject
    internal constructor(
            @ApplicationContext context: Context,
            encryption: KeyStoreEncryption,
            preferences: Preferences,
            interceptor: DebugNetworkInterceptor) {
        this.context = context
        this.encryption = encryption
        this.preferences = preferences
        this.interceptor = interceptor
        httpClient = null
        httpUrl = null
        basicDigestAuthHandler = null
    }

    private constructor(
            context: Context,
            encryption: KeyStoreEncryption,
            preferences: Preferences,
            interceptor: DebugNetworkInterceptor,
            url: String?,
            username: String,
            password: String,
            foreground: Boolean) {
        this.context = context
        this.encryption = encryption
        this.preferences = preferences
        this.interceptor = interceptor
        val customCertManager = CustomCertManager(context)
        customCertManager.appInForeground = foreground
        val hostnameVerifier = customCertManager.hostnameVerifier(OkHostnameVerifier)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(customCertManager), null)
        basicDigestAuthHandler = BasicDigestAuthHandler(null, username, password)
        val builder = OkHttpClient()
                .newBuilder()
                .addNetworkInterceptor(basicDigestAuthHandler)
                .authenticator(basicDigestAuthHandler)
                .cookieJar(MemoryCookieStore())
                .followRedirects(false)
                .followSslRedirects(true)
                .sslSocketFactory(sslContext.socketFactory, customCertManager)
                .hostnameVerifier(hostnameVerifier)
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
        if (preferences.isFlipperEnabled) {
            interceptor.add(builder)
        }
        httpClient = builder.build()
        httpUrl = url?.toHttpUrlOrNull()
    }

    @Throws(NoSuchAlgorithmException::class, KeyManagementException::class)
    suspend fun forAccount(account: CaldavAccount): CaldavClient {
        return forUrl(account.url, account.username!!, account.getPassword(encryption))
    }

    @Throws(NoSuchAlgorithmException::class, KeyManagementException::class)
    suspend fun forCalendar(account: CaldavAccount, calendar: CaldavCalendar): CaldavClient {
        return forUrl(calendar.url, account.username!!, account.getPassword(encryption))
    }

    @Throws(KeyManagementException::class, NoSuchAlgorithmException::class)
    suspend fun forUrl(url: String?, username: String, password: String): CaldavClient = withContext(Dispatchers.IO) {
        CaldavClient(
                context, encryption, preferences, interceptor, url, username, password, foreground)
    }

    @WorkerThread
    @Throws(DavException::class, IOException::class)
    private fun tryFindPrincipal(link: String): String? {
        val url = httpUrl!!.resolve(link)
        Timber.d("Checking for principal: %s", url)
        val davResource = DavResource(httpClient!!, url!!)
        val responses = ArrayList<Response>()
        davResource.propfind(0, CurrentUserPrincipal.NAME) { response, _ ->
            responses.add(response)
        }
        if (responses.isNotEmpty()) {
            val response = responses[0]
            val currentUserPrincipal = response[CurrentUserPrincipal::class.java]
            if (currentUserPrincipal != null) {
                val href = currentUserPrincipal.href
                if (!isNullOrEmpty(href)) {
                    return href
                }
            }
        }
        return null
    }

    @WorkerThread
    @Throws(DavException::class, IOException::class)
    private fun findHomeset(): String {
        val davResource = DavResource(httpClient!!, httpUrl!!)
        val responses = ArrayList<Response>()
        davResource.propfind(0, CalendarHomeSet.NAME) { response, _ ->
            responses.add(response)
        }
        val response = responses[0]
        val calendarHomeSet = response[CalendarHomeSet::class.java]
                ?: throw DisplayableException(R.string.caldav_home_set_not_found)
        val hrefs: List<String> = calendarHomeSet.hrefs
        if (hrefs.size != 1) {
            throw DisplayableException(R.string.caldav_home_set_not_found)
        }
        val homeSet = hrefs[0]
        if (isNullOrEmpty(homeSet)) {
            throw DisplayableException(R.string.caldav_home_set_not_found)
        }
        return davResource.location.resolve(homeSet).toString()
    }

    @Throws(IOException::class, DavException::class, NoSuchAlgorithmException::class, KeyManagementException::class)
    suspend fun homeSet(): String = withContext(Dispatchers.IO) {
        var principal: String? = null
        try {
            principal = tryFindPrincipal("/.well-known/caldav")
        } catch (e: Exception) {
            if (e is HttpException && e.code == 401) {
                throw e
            }
            Timber.w(e)
        }
        if (principal == null) {
            principal = tryFindPrincipal("")
        }
        forUrl(
                (if (isNullOrEmpty(principal)) httpUrl else httpUrl!!.resolve(principal!!)).toString(),
                basicDigestAuthHandler!!.username,
                basicDigestAuthHandler.password)
                .findHomeset()
    }

    @Throws(IOException::class, DavException::class)
    suspend fun calendars(): List<Response> = withContext(Dispatchers.IO) {
        val davResource = DavResource(httpClient!!, httpUrl!!)
        val responses = ArrayList<Response>()
        davResource.propfind(
                1,
                ResourceType.NAME,
                DisplayName.NAME,
                SupportedCalendarComponentSet.NAME,
                GetCTag.NAME,
                CalendarColor.NAME,
                SyncToken.NAME) { response: Response, relation: HrefRelation ->
            if (relation == HrefRelation.MEMBER) {
                responses.add(response)
            }
        }
        val urls: MutableList<Response> = ArrayList()
        for (member in responses) {
            val resourceType = member[ResourceType::class.java]
            if (resourceType == null
                    || !resourceType.types.contains(CALENDAR)) {
                Timber.d("%s is not a calendar", member)
                continue
            }
            val supportedCalendarComponentSet = member.get(SupportedCalendarComponentSet::class.java)
            if (supportedCalendarComponentSet == null
                    || !supportedCalendarComponentSet.supportsTasks) {
                Timber.d("%s does not support tasks", member)
                continue
            }
            urls.add(member)
        }
        urls
    }

    @Throws(IOException::class, HttpException::class)
    suspend fun deleteCollection() = withContext(Dispatchers.IO) {
        DavResource(httpClient!!, httpUrl!!).delete(null) {}
    }

    @Throws(IOException::class, XmlPullParserException::class, HttpException::class)
    suspend fun makeCollection(displayName: String, color: Int): String = withContext(Dispatchers.IO) {
        val davResource = DavResource(httpClient!!, httpUrl!!.resolve(UUIDHelper.newUUID() + "/")!!)
        val mkcolString = getMkcolString(displayName, color)
        davResource.mkCol(mkcolString) {}
        davResource.location.toString()
    }

    @Throws(IOException::class, XmlPullParserException::class, HttpException::class)
    suspend fun updateCollection(displayName: String, color: Int): String = withContext(Dispatchers.IO) {
        val davResource = PatchableDavResource(httpClient!!, httpUrl!!)
        davResource.propPatch(getPropPatchString(displayName, color)) {}
        davResource.location.toString()
    }

    @Throws(IOException::class, XmlPullParserException::class)
    private fun getPropPatchString(displayName: String, color: Int): String {
        val xmlPullParserFactory = XmlPullParserFactory.newInstance()
        val xml = xmlPullParserFactory.newSerializer()
        val stringWriter = StringWriter()
        xml.setOutput(stringWriter)
        xml.startDocument("UTF-8", null)
        xml.setPrefix("", NS_WEBDAV)
        xml.setPrefix("CAL", NS_CALDAV)
        xml.setPrefix("CARD", NS_CARDDAV)
        xml.startTag(NS_WEBDAV, "propertyupdate")
        xml.startTag(NS_WEBDAV, "set")
        xml.startTag(NS_WEBDAV, "prop")
        setDisplayName(xml, displayName)
        if (color != 0) {
            setColor(xml, color)
        }
        xml.endTag(NS_WEBDAV, "prop")
        xml.endTag(NS_WEBDAV, "set")
        if (color == 0) {
            xml.startTag(NS_WEBDAV, "remove")
            xml.startTag(NS_WEBDAV, "prop")
            xml.startTag(NS_APPLE_ICAL, "calendar-color")
            xml.endTag(NS_APPLE_ICAL, "calendar-color")
            xml.endTag(NS_WEBDAV, "prop")
            xml.endTag(NS_WEBDAV, "remove")
        }
        xml.endTag(NS_WEBDAV, "propertyupdate")
        xml.endDocument()
        xml.flush()
        return stringWriter.toString()
    }

    @Throws(IOException::class, XmlPullParserException::class)
    private fun getMkcolString(displayName: String, color: Int): String {
        val xmlPullParserFactory = XmlPullParserFactory.newInstance()
        val xml = xmlPullParserFactory.newSerializer()
        val stringWriter = StringWriter()
        xml.setOutput(stringWriter)
        xml.startDocument("UTF-8", null)
        xml.setPrefix("", NS_WEBDAV)
        xml.setPrefix("CAL", NS_CALDAV)
        xml.setPrefix("CARD", NS_CARDDAV)
        xml.startTag(NS_WEBDAV, "mkcol")
        xml.startTag(NS_WEBDAV, "set")
        xml.startTag(NS_WEBDAV, "prop")
        xml.startTag(NS_WEBDAV, "resourcetype")
        xml.startTag(NS_WEBDAV, "collection")
        xml.endTag(NS_WEBDAV, "collection")
        xml.startTag(NS_CALDAV, "calendar")
        xml.endTag(NS_CALDAV, "calendar")
        xml.endTag(NS_WEBDAV, "resourcetype")
        setDisplayName(xml, displayName)
        if (color != 0) {
            setColor(xml, color)
        }
        xml.startTag(NS_CALDAV, "supported-calendar-component-set")
        xml.startTag(NS_CALDAV, "comp")
        xml.attribute(null, "name", "VTODO")
        xml.endTag(NS_CALDAV, "comp")
        xml.endTag(NS_CALDAV, "supported-calendar-component-set")
        xml.endTag(NS_WEBDAV, "prop")
        xml.endTag(NS_WEBDAV, "set")
        xml.endTag(NS_WEBDAV, "mkcol")
        xml.endDocument()
        xml.flush()
        return stringWriter.toString()
    }

    @Throws(IOException::class)
    private fun setDisplayName(xml: XmlSerializer, name: String) {
        xml.startTag(NS_WEBDAV, "displayname")
        xml.text(name)
        xml.endTag(NS_WEBDAV, "displayname")
    }

    @Throws(IOException::class)
    private fun setColor(xml: XmlSerializer, color: Int) {
        xml.startTag(NS_APPLE_ICAL, "calendar-color")
        xml.text(String.format("#%06X%02X", color and 0xFFFFFF, color ushr 24))
        xml.endTag(NS_APPLE_ICAL, "calendar-color")
    }

    fun setForeground(): CaldavClient {
        foreground = true
        return this
    }
}