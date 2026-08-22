package ani.dantotsu.parsers.novel
import android.content.Context
import app.cash.quickjs.QuickJs
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Headers.Companion.toHeaders
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object LnReaderJsEngine {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"

    suspend fun call(
        pluginJs: String,
        pluginId: String,
        method: String,
        argsJson: String = "[]",
    ): String = withContext(Dispatchers.IO) {
        val qjs = QuickJs.create()
        try {
            // Bridge
            val jsoupElements = mutableMapOf<Int, org.jsoup.nodes.Element>()
            var elementCounter = 0

            qjs.set("__dantotsuJsoup", JsoupBridge::class.java, object : JsoupBridge {
                override fun parse(html: String): Int {
                    val doc = org.jsoup.Jsoup.parse(html)
                    val id = ++elementCounter
                    jsoupElements[id] = doc
                    return id
                }
                override fun select(nodeIdsJson: String, selector: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    val resultIds = mutableListOf<Int>()
                    for (id in ids) {
                        val node = jsoupElements[id] ?: continue
                        try {
                            val elements = node.select(selector)
                            for (el in elements) {
                                val newId = ++elementCounter
                                jsoupElements[newId] = el
                                resultIds.add(newId)
                            }
                        } catch (e: Exception) {
                            Logger.log("Jsoup select error for '$selector': ${e.message}")
                        }
                    }
                    return Json.encodeToString(resultIds)
                }
                override fun text(nodeIdsJson: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    return ids.mapNotNull { jsoupElements[it]?.text() }.joinToString("")
                }
                override fun attr(nodeIdsJson: String, attr: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    return jsoupElements[ids.firstOrNull()]?.attr(attr) ?: ""
                }
                override fun html(nodeIdsJson: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    return ids.mapNotNull { jsoupElements[it]?.html() }.joinToString("\n")
                }
                override fun outerHtml(nodeIdsJson: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    return ids.mapNotNull { jsoupElements[it]?.outerHtml() }.joinToString("\n")
                }
                override fun remove(nodeIdsJson: String) {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    ids.forEach { jsoupElements[it]?.remove() }
                }
                override fun removeAttr(nodeIdsJson: String, attr: String) {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    ids.forEach { jsoupElements[it]?.removeAttr(attr) }
                }
                override fun setAttr(nodeIdsJson: String, attr: String, value: String) {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    ids.forEach { jsoupElements[it]?.attr(attr, value) }
                }
                override fun addClass(nodeIdsJson: String, className: String) {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    ids.forEach { jsoupElements[it]?.addClass(className) }
                }
                override fun removeClass(nodeIdsJson: String, className: String) {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    ids.forEach { jsoupElements[it]?.removeClass(className) }
                }
                override fun next(nodeIdsJson: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    val resultIds = mutableListOf<Int>()
                    for (id in ids) {
                        jsoupElements[id]?.nextElementSibling()?.let {
                            val newId = ++elementCounter
                            jsoupElements[newId] = it
                            resultIds.add(newId)
                        }
                    }
                    return Json.encodeToString(resultIds)
                }
                override fun prev(nodeIdsJson: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    val resultIds = mutableListOf<Int>()
                    for (id in ids) {
                        jsoupElements[id]?.previousElementSibling()?.let {
                            val newId = ++elementCounter
                            jsoupElements[newId] = it
                            resultIds.add(newId)
                        }
                    }
                    return Json.encodeToString(resultIds)
                }
                override fun parent(nodeIdsJson: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    val resultIds = mutableListOf<Int>()
                    for (id in ids) {
                        jsoupElements[id]?.parent()?.let {
                            val newId = ++elementCounter
                            jsoupElements[newId] = it
                            resultIds.add(newId)
                        }
                    }
                    return Json.encodeToString(resultIds)
                }
                override fun parents(nodeIdsJson: String, selector: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    val resultIds = mutableListOf<Int>()
                    for (id in ids) {
                        val node = jsoupElements[id] ?: continue
                        val pList = if (selector.isNotBlank()) node.parents().select(selector) else node.parents()
                        for (el in pList) {
                            val newId = ++elementCounter
                            jsoupElements[newId] = el
                            resultIds.add(newId)
                        }
                    }
                    return Json.encodeToString(resultIds)
                }
                override fun closest(nodeIdsJson: String, selector: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    val resultIds = mutableListOf<Int>()
                    for (id in ids) {
                        var curr = jsoupElements[id]
                        while (curr != null) {
                            if (selector.isBlank() || curr.`is`(selector)) {
                                val newId = ++elementCounter
                                jsoupElements[newId] = curr
                                resultIds.add(newId)
                                break
                            }
                            curr = curr.parent()
                        }
                    }
                    return Json.encodeToString(resultIds)
                }
                override fun children(nodeIdsJson: String, selector: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    val resultIds = mutableListOf<Int>()
                    for (id in ids) {
                        val node = jsoupElements[id] ?: continue
                        val ch = if (selector.isNotBlank()) node.children().select(selector) else node.children()
                        for (el in ch) {
                            val newId = ++elementCounter
                            jsoupElements[newId] = el
                            resultIds.add(newId)
                        }
                    }
                    return Json.encodeToString(resultIds)
                }
                override fun siblings(nodeIdsJson: String, selector: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    val resultIds = mutableListOf<Int>()
                    for (id in ids) {
                        val node = jsoupElements[id] ?: continue
                        val sibs = if (selector.isNotBlank()) node.siblingElements().select(selector) else node.siblingElements()
                        for (el in sibs) {
                            val newId = ++elementCounter
                            jsoupElements[newId] = el
                            resultIds.add(newId)
                        }
                    }
                    return Json.encodeToString(resultIds)
                }
                override fun hasClass(nodeIdsJson: String, className: String): Boolean {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    return ids.any { jsoupElements[it]?.hasClass(className) == true }
                }
                override fun attrs(nodeIdsJson: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    val element = jsoupElements[ids.firstOrNull()] ?: return "{}"
                    val map = element.attributes().associate { it.key to it.value }
                    return Json.encodeToString(map)
                }
                override fun tagName(nodeIdsJson: String): String {
                    val ids = runCatching { Json.decodeFromString<List<Int>>(nodeIdsJson) }.getOrDefault(emptyList())
                    return jsoupElements[ids.firstOrNull()]?.tagName() ?: ""
                }
            })

            qjs.set("__dantotsuFetch", FetchBridge::class.java, object : FetchBridge {
                override fun fetch(url: String, method: String, headersJson: String, body: String?): String {
                    return executeFetch(url, method, headersJson, body)
                }
            })

            qjs.set("__dantotsuLog", LogBridge::class.java, object : LogBridge {
                override fun log(msg: String) {
                    Logger.log("LnReaderJS[$pluginId]: $msg")
                }
            })

            qjs.set("__dantotsuStorage", StorageBridge::class.java, object : StorageBridge {
                private val prefs = ani.dantotsu.App.currentContext()?.getSharedPreferences("lnreader_plugin_storage", Context.MODE_PRIVATE)

                override fun get(key: String): String? {
                    return prefs?.getString(key, null)
                }

                override fun set(key: String, value: String) {
                    prefs?.edit()?.putString(key, value)?.apply()
                }

                override fun delete(key: String) {
                    prefs?.edit()?.remove(key)?.apply()
                }

                override fun getAllKeys(prefix: String): String {
                    val keys = prefs?.all?.keys?.filter { it.startsWith(prefix) } ?: emptyList()
                    return Json.encodeToString(keys)
                }
            })

            qjs.evaluate(SYNC_PROMISE_JS)
            qjs.evaluate(POLYFILL_JS)
            qjs.evaluate(STRING_HELPERS_JS)
            qjs.evaluate(HTMLPARSER_JS)
            qjs.evaluate(CHEERIO_JSOUP_BRIDGE_JS)
            qjs.evaluate(FETCH_BRIDGE_JS)
            qjs.evaluate(MODULE_BOOTSTRAP_JS)
            qjs.evaluate(REQUIRE_SHIM_JS)

            // Load plugin safely
            qjs.evaluate("""
                (function() {
                    var exports = {};
                    var module = { exports: exports };
                    (function(module, exports, require) {
                        $pluginJs
                    })(module, exports, require);
                    var pluginInstance = module.exports.default || module.exports;
                    if (typeof pluginInstance === 'function') {
                        try {
                            pluginInstance = new pluginInstance();
                        } catch(e) {}
                    }
                    globalThis['__plugin_$pluginId'] = pluginInstance;
                })();
            """.trimIndent())

            // Call method and drain async Promise execution
            val invokeScript = """
                globalThis.__callResult = null;
                globalThis.__callError = null;
                globalThis.__callDone = false;
                (function() {
                    try {
                        var target = globalThis['__plugin_$pluginId'];
                        if (!target) throw new Error("Plugin '$pluginId' not loaded");
                        var fn = target["$method"];
                        if (typeof fn !== "function") throw new Error("Method '$method' not found on plugin '$pluginId'");
                        var args = JSON.parse(${Json.encodeToString(argsJson)});
                        var p = Promise.resolve(fn.apply(target, args));
                        p.then(
                            function(r) {
                                globalThis.__callResult = JSON.stringify(r === undefined ? null : r);
                                globalThis.__callDone = true;
                            },
                            function(e) {
                                globalThis.__callError = String((e && e.message) || e);
                                globalThis.__callDone = true;
                            }
                        );
                    } catch(e) {
                        globalThis.__callError = String((e && e.message) || e);
                        globalThis.__callDone = true;
                    }
                })();
            """.trimIndent()
            qjs.evaluate(invokeScript)

            // Drain microtasks in QuickJS until Promise chain completes
            var isDone = false
            var iterations = 0
            while (!isDone && iterations < 500) {
                isDone = (qjs.evaluate("globalThis.__callDone === true") as? Boolean) == true
                iterations++
            }

            val error = qjs.evaluate("globalThis.__callError") as? String
            if (!error.isNullOrBlank()) {
                Logger.log("LnReaderJsEngine: error calling '$method' on '$pluginId': $error")
                throw Exception(error)
            }

            if (!isDone) {
                throw Exception("Plugin '$pluginId' method '$method' timed out (still pending after $iterations cycles)")
            }

            val resultStr = qjs.evaluate("globalThis.__callResult") as? String ?: "null"
            resultStr

        } catch (e: Exception) {
            Logger.log("LnReaderJsEngine.call error [$method]: ${e.message}")
            throw e
        } finally {
            qjs.close()
        }
    }

    private fun executeFetch(
        url: String,
        method: String,
        headersJson: String,
        body: String?,
    ): String {
        return try {
            val parsedHeaders = try {
                val obj = Json.parseToJsonElement(headersJson).jsonObject
                obj.entries.associate { it.key to it.value.jsonPrimitive.content }
            } catch (_: Exception) { emptyMap() }

            val reqBuilder = Request.Builder().url(url)

            val defaultHeaders = mapOf(
                "User-Agent" to DEFAULT_USER_AGENT,
                "Connection" to "keep-alive",
                "Accept" to "*/*",
                "Accept-Language" to "*",
                "Cache-Control" to "max-age=0",
            )
            // default first
            defaultHeaders.forEach { (k, v) ->
                if (!parsedHeaders.keys.any { it.equals(k, ignoreCase = true) }) {
                    reqBuilder.addHeader(k, v)
                }
            }
            parsedHeaders.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

            when (method.uppercase()) {
                "POST" -> {
                    val ct = parsedHeaders.entries
                        .firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
                        ?: "application/x-www-form-urlencoded"
                    val mediaType = ct.toMediaTypeOrNull()
                    reqBuilder.post((body ?: "").toRequestBody(mediaType))
                }
                "PUT" -> {
                    val ct = parsedHeaders.entries
                        .firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
                        ?: "application/json"
                    reqBuilder.put((body ?: "").toRequestBody(ct.toMediaTypeOrNull()))
                }
                "HEAD" -> reqBuilder.head()
                else   -> reqBuilder.get()
            }

            val builtReq = reqBuilder.build()
            val response = httpClient.newCall(builtReq).execute()
            val responseBody = response.body.string()
            val finalUrl = response.request.url.toString()
            val responseHeaders = response.headers.toMultimap()
                .entries.associate { it.key to it.value.firstOrNull().orEmpty() }

            Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("statusCode", response.code)
                    put("reasonPhrase", response.message)
                    put("body", responseBody)
                    put("url", finalUrl)
                    put("isRedirect", false)
                    put("headers", buildJsonObject {
                        responseHeaders.forEach { (k, v) -> put(k, v) }
                    })
                }
            )
        } catch (e: Exception) {
            Logger.log("LnReaderJsEngine fetch error ($url): ${e.message}")
            Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("statusCode", 0)
                    put("reasonPhrase", e.message ?: "Unknown error")
                    put("body", "")
                    put("url", url)
                    put("isRedirect", false)
                    put("headers", buildJsonObject {})
                }
            )
        }
    }

    //QuickJS

    interface FetchBridge {
        fun fetch(url: String, method: String, headersJson: String, body: String?): String
    }

    interface LogBridge {
        fun log(msg: String)
    }

    interface StorageBridge {
        fun get(key: String): String?
        fun set(key: String, value: String)
        fun delete(key: String)
        fun getAllKeys(prefix: String): String
    }

    interface JsoupBridge {
        fun parse(html: String): Int
        fun select(nodeIdsJson: String, selector: String): String
        fun text(nodeIdsJson: String): String
        fun attr(nodeIdsJson: String, attr: String): String
        fun html(nodeIdsJson: String): String
        fun outerHtml(nodeIdsJson: String): String
        fun remove(nodeIdsJson: String)
        fun removeAttr(nodeIdsJson: String, attr: String)
        fun setAttr(nodeIdsJson: String, attr: String, value: String)
        fun addClass(nodeIdsJson: String, className: String)
        fun removeClass(nodeIdsJson: String, className: String)
        fun next(nodeIdsJson: String): String
        fun prev(nodeIdsJson: String): String
        fun parent(nodeIdsJson: String): String
        fun parents(nodeIdsJson: String, selector: String): String
        fun closest(nodeIdsJson: String, selector: String): String
        fun children(nodeIdsJson: String, selector: String): String
        fun siblings(nodeIdsJson: String, selector: String): String
        fun hasClass(nodeIdsJson: String, className: String): Boolean
        fun attrs(nodeIdsJson: String): String
        fun tagName(nodeIdsJson: String): String
    }


    private val MODULE_BOOTSTRAP_JS = """
var module = {};
var exports = (function() { return this; })();
Object.defineProperties(module, {
    namespace: { set: function(a) { exports = a; } },
    exports: {
        set: function(a) { for (var b in a) { if (a.hasOwnProperty(b)) exports[b] = a[b]; } },
        get: function() { return exports; }
    }
});
""".trimIndent()

    private val REQUIRE_SHIM_JS = """
const dayjs = function(v) { return { format: function(f) { return String(v || new Date()); }, valueOf: function() { return +new Date(v); } }; };
dayjs.extend = function(){};

const NovelStatus = {
    "Unknown":"Unknown","Ongoing":"Ongoing","Completed":"Completed",
    "Licensed":"Licensed","PublishingFinished":"Publishing Finished",
    "Cancelled":"Cancelled","OnHiatus":"On Hiatus"
};

const FilterTypes = {
    "TextInput":"Text","Picker":"Picker","CheckboxGroup":"Checkbox",
    "Switch":"Switch","ExcludableCheckboxGroup":"XCheckbox"
};

const isPickerValue      = q => q && q.type === FilterTypes.Picker && typeof q.value === "string";
const isCheckboxValue    = q => q && q.type === FilterTypes.CheckboxGroup && Array.isArray(q.value);
const isSwitchValue      = q => q && q.type === FilterTypes.Switch && typeof q.value === "boolean";
const isTextValue        = q => q && q.type === FilterTypes.TextInput && typeof q.value === "string";
const isXCheckboxValue   = q => q && q.type === FilterTypes.ExcludableCheckboxGroup && typeof q.value === "object" && !Array.isArray(q.value);

const isUrlAbsolute = url => {
    if (!url) return false;
    if (url.indexOf("//") === 0) return true;
    if (url.indexOf("://") === -1) return false;
    if (url.indexOf(".") === -1) return false;
    if (url.indexOf("/") === -1) return false;
    if (url.indexOf(":") > url.indexOf("/")) return false;
    if (url.indexOf("://") < url.indexOf(".")) return true;
    return false;
};

const defaultCover = "https://placehold.co/300x400";
const gcm = function(key, nonce) {
    return {
        encrypt: function(plaintext) { return plaintext; },
        decrypt: function(ciphertext) { return ciphertext; }
    };
};

function PluginStorage(pluginId) { this.pluginId = pluginId || ''; }
PluginStorage.prototype.get = function(key) {
    var stored = __dantotsuStorage.get(this.pluginId + "_DB_" + key);
    if (!stored) return undefined;
    try {
        var item = JSON.parse(stored);
        if (item.expires && Date.now() > item.expires) {
            this.delete(key);
            return undefined;
        }
        return item.value !== undefined ? item.value : item;
    } catch(e) {
        return stored;
    }
};
PluginStorage.prototype.set = function(key, value, expires) {
    var exp = (expires instanceof Date) ? expires.getTime() : expires;
    var item = { created: Date.now(), value: value, expires: exp };
    __dantotsuStorage.set(this.pluginId + "_DB_" + key, JSON.stringify(item));
};
PluginStorage.prototype.delete = function(key) {
    __dantotsuStorage.delete(this.pluginId + "_DB_" + key);
};
PluginStorage.prototype.clearAll = function() {
    var keysStr = __dantotsuStorage.getAllKeys(this.pluginId + "_DB_");
    try {
        var keys = JSON.parse(keysStr);
        for (var i = 0; i < keys.length; i++) {
            __dantotsuStorage.delete(keys[i]);
        }
    } catch(e) {}
};
PluginStorage.prototype.getAllKeys = function() {
    var keysStr = __dantotsuStorage.getAllKeys(this.pluginId + "_DB_");
    try {
        var keys = JSON.parse(keysStr);
        var prefix = this.pluginId + "_DB_";
        return keys.map(function(k) { return k.replace(prefix, ''); });
    } catch(e) {
        return [];
    }
};

const require = (pkg) => {
    switch (pkg) {
        case "cheerio":           return { load: load };
        case "htmlparser2":       return { Parser: Parser };
        case "dayjs":             return dayjs;
        case "urlencode":         return { encode: encodeURIComponent, decode: decodeURIComponent };
        case "@libs/fetch":       return { fetchApi: fetchApi, fetchText: fetchText, fetchProto: fetchApi };
        case "@libs/novelStatus": return { NovelStatus: NovelStatus };
        case "@libs/isAbsoluteUrl": return { isUrlAbsolute: isUrlAbsolute };
        case "@libs/filterInputs": return { FilterTypes, isPickerValue, isCheckboxValue, isSwitchValue, isTextValue, isXCheckboxValue };
        case "@libs/defaultCover": return { defaultCover: defaultCover };
        case "@libs/aes":         return { gcm: gcm };
        case "@libs/utils":       return { utf8ToBytes: utf8ToBytes, bytesToUtf8: bytesToUtf8 };
        case "@/lib/utils":       return { utf8ToBytes: utf8ToBytes, bytesToUtf8: bytesToUtf8 };
        case "@libs/parseDate":   return { parseDate: function(d) { return dayjs(d).format('LL'); } };
        case "@libs/storage":     return {
            storage: new PluginStorage(''),
            localStorage: new PluginStorage('local'),
            sessionStorage: new PluginStorage('session')
        };
        case "lodash-es/reverse": return function(arr) { return arr ? arr.slice().reverse() : []; };
        case "lodash-es/uniqBy":  return function(arr, key) {
            if (!arr) return [];
            var seen = new Set();
            return arr.filter(function(item) {
                var k = typeof key === 'function' ? key(item) : item[key];
                if (seen.has(k)) return false;
                seen.add(k);
                return true;
            });
        };
        case "lodash-es/filter":  return function(arr, fn) { return arr ? arr.filter(fn) : []; };
        case "lodash-es/map":     return function(arr, fn) { return arr ? arr.map(fn) : []; };
        default:                  return {};
    }
};
""".trimIndent()

    private val SYNC_PROMISE_JS = """
(function() {
    function SyncPromise(executor) {
        this.state = 'pending';
        this.value = undefined;
        this.reason = undefined;
        this.handlers = [];
        
        var self = this;
        function resolve(val) {
            if (self.state !== 'pending') return;
            if (val && (typeof val === 'object' || typeof val === 'function') && typeof val.then === 'function') {
                try {
                    val.then.call(val, resolve, reject);
                } catch(e) {
                    reject(e);
                }
                return;
            }
            self.state = 'fulfilled';
            self.value = val;
            var h = self.handlers;
            self.handlers = [];
            for (var i = 0; i < h.length; i++) {
                try { h[i](); } catch(e) {}
            }
        }
        
        function reject(err) {
            if (self.state !== 'pending') return;
            self.state = 'rejected';
            self.reason = err;
            var h = self.handlers;
            self.handlers = [];
            for (var i = 0; i < h.length; i++) {
                try { h[i](); } catch(e) {}
            }
        }
        
        try {
            if (typeof executor === 'function') {
                executor(resolve, reject);
            }
        } catch(e) {
            reject(e);
        }
    }
    
    SyncPromise.prototype.then = function(onFulfilled, onRejected) {
        var self = this;
        return new SyncPromise(function(resolve, reject) {
            function execute() {
                if (self.state === 'fulfilled') {
                    if (typeof onFulfilled === 'function') {
                        try {
                            resolve(onFulfilled(self.value));
                        } catch(e) {
                            reject(e);
                        }
                    } else {
                        resolve(self.value);
                    }
                } else if (self.state === 'rejected') {
                    if (typeof onRejected === 'function') {
                        try {
                            resolve(onRejected(self.reason));
                        } catch(e) {
                            reject(e);
                        }
                    } else {
                        reject(self.reason);
                    }
                }
            }
            if (self.state === 'pending') {
                self.handlers.push(execute);
            } else {
                execute();
            }
        });
    };
    
    SyncPromise.prototype.catch = function(onRejected) {
        return this.then(null, onRejected);
    };
    
    SyncPromise.prototype.finally = function(onFinally) {
        return this.then(
            function(value) {
                return SyncPromise.resolve(typeof onFinally === 'function' ? onFinally() : undefined).then(function() { return value; });
            },
            function(reason) {
                return SyncPromise.resolve(typeof onFinally === 'function' ? onFinally() : undefined).then(function() { throw reason; });
            }
        );
    };
    
    SyncPromise.resolve = function(val) {
        if (val instanceof SyncPromise) return val;
        return new SyncPromise(function(resolve) { resolve(val); });
    };
    
    SyncPromise.reject = function(err) {
        return new SyncPromise(function(resolve, reject) { reject(err); });
    };
    
    SyncPromise.all = function(iterable) {
        return new SyncPromise(function(resolve, reject) {
            if (!iterable) return resolve([]);
            var arr = Array.from ? Array.from(iterable) : Array.prototype.slice.call(iterable);
            var results = new Array(arr.length);
            var remaining = arr.length;
            if (remaining === 0) return resolve(results);
            arr.forEach(function(item, idx) {
                SyncPromise.resolve(item).then(function(val) {
                    results[idx] = val;
                    remaining--;
                    if (remaining === 0) resolve(results);
                }, reject);
            });
        });
    };
    
    SyncPromise.allSettled = function(iterable) {
        return new SyncPromise(function(resolve) {
            if (!iterable) return resolve([]);
            var arr = Array.from ? Array.from(iterable) : Array.prototype.slice.call(iterable);
            var results = new Array(arr.length);
            var remaining = arr.length;
            if (remaining === 0) return resolve(results);
            arr.forEach(function(item, idx) {
                SyncPromise.resolve(item).then(function(val) {
                    results[idx] = { status: 'fulfilled', value: val };
                    remaining--;
                    if (remaining === 0) resolve(results);
                }, function(err) {
                    results[idx] = { status: 'rejected', reason: err };
                    remaining--;
                    if (remaining === 0) resolve(results);
                });
            });
        });
    };
    
    SyncPromise.race = function(iterable) {
        return new SyncPromise(function(resolve, reject) {
            if (!iterable) return;
            var arr = Array.from ? Array.from(iterable) : Array.prototype.slice.call(iterable);
            arr.forEach(function(item) {
                SyncPromise.resolve(item).then(resolve, reject);
            });
        });
    };
    
    globalThis.Promise = SyncPromise;
})();
""".trimIndent()

    private val POLYFILL_JS = """
var console = {
    log: function() { try { __dantotsuLog.log(Array.prototype.slice.call(arguments).map(function(a) { return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' ')); } catch(e){} },
    warn: function() { try { __dantotsuLog.log('WARN: ' + Array.prototype.slice.call(arguments).map(function(a) { return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' ')); } catch(e){} },
    error: function() { try { __dantotsuLog.log('ERROR: ' + Array.prototype.slice.call(arguments).map(function(a) { return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' ')); } catch(e){} }
};

var setTimeout = function(fn, ms) { try { if (typeof fn === 'function') fn(); } catch(e){} return 0; };
var clearTimeout = function() {};
var setInterval = function(fn, ms) { return 0; };
var clearInterval = function() {};

var btoa = function(str) {
    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
    var encoded = '';
    var c1, c2, c3, e1, e2, e3, e4;
    var i = 0;
    while (i < str.length) {
        c1 = str.charCodeAt(i++);
        c2 = str.charCodeAt(i++);
        c3 = str.charCodeAt(i++);
        e1 = c1 >> 2;
        e2 = ((c1 & 3) << 4) | (c2 >> 4);
        e3 = ((c2 & 15) << 2) | (c3 >> 6);
        e4 = c3 & 63;
        if (isNaN(c2)) e3 = e4 = 64;
        else if (isNaN(c3)) e4 = 64;
        encoded += chars.charAt(e1) + chars.charAt(e2) + chars.charAt(e3) + chars.charAt(e4);
    }
    return encoded;
};

var atob = function(input) {
    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
    var str = String(input).replace(/=+$/, '');
    var output = '';
    if (str.length % 4 === 1) return '';
    for (var bc = 0, bs, buffer, idx = 0; buffer = str.charAt(idx++); ~buffer && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {
        buffer = chars.indexOf(buffer);
    }
    return output;
};

function utf8ToBytes(str) {
    var bytes = [];
    for (var i = 0; i < str.length; i++) {
        var code = str.charCodeAt(i);
        if (code < 0x80) {
            bytes.push(code);
        } else if (code < 0x800) {
            bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
        } else if (code < 0xd800 || code >= 0xe000) {
            bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
        } else {
            i++;
            code = 0x10000 + (((code & 0x3ff) << 10) | (str.charCodeAt(i) & 0x3ff));
            bytes.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | (code & 0x3f));
        }
    }
    return new Uint8Array(bytes);
}

function bytesToUtf8(bytes) {
    var encoded = "";
    for (var i = 0; i < bytes.length; i++) {
        encoded += "%" + ("0" + bytes[i].toString(16)).slice(-2);
    }
    try {
        return decodeURIComponent(encoded);
    } catch (e) {
        return unescape(encoded);
    }
}

function TextEncoder() {}
TextEncoder.prototype.encode = function(str) {
    return utf8ToBytes(str || "");
};

function TextDecoder(encoding) {
    this.encoding = encoding || 'utf-8';
}
TextDecoder.prototype.decode = function(bytes) {
    if (!bytes) return "";
    return bytesToUtf8(bytes);
};

var crypto = {
    getRandomValues: function(arr) {
        if (!arr) return arr;
        for (var i = 0; i < arr.length; i++) {
            arr[i] = Math.floor(Math.random() * 256);
        }
        return arr;
    },
    randomUUID: function() {
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
            var r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }
};

if (typeof Object.assign !== 'function') {
    Object.assign = function(target) {
        if (target == null) throw new TypeError('Cannot convert undefined or null to object');
        var to = Object(target);
        for (var i = 1; i < arguments.length; i++) {
            var src = arguments[i];
            if (src != null) {
                for (var key in src) {
                    if (Object.prototype.hasOwnProperty.call(src, key)) {
                        to[key] = src[key];
                    }
                }
            }
        }
        return to;
    };
}

// URLSearchParams polyfill
function URLSearchParams(init) {
    this._params = {};
    if (typeof init === "string") {
        init.replace(/^\?/,"").split("&").forEach(function(p) {
            var kv = p.split("=");
            if (kv[0]) this._params[decodeURIComponent(kv[0])] = decodeURIComponent(kv[1] || "");
        }.bind(this));
    } else if (init && typeof init === "object") {
        for (var key in init) {
            if (init.hasOwnProperty(key) && init[key] !== undefined && init[key] !== null) {
                this._params[key] = String(init[key]);
            }
        }
    }
}
URLSearchParams.prototype.append = function(k,v){ this._params[k]=String(v); };
URLSearchParams.prototype.get = function(k){ return this._params.hasOwnProperty(k) ? this._params[k] : null; };
URLSearchParams.prototype.set = function(k,v){ this._params[k]=String(v); };
URLSearchParams.prototype.delete = function(k){ delete this._params[k]; };
URLSearchParams.prototype.has = function(k){ return this._params.hasOwnProperty(k); };
URLSearchParams.prototype.toString = function(){
    return Object.keys(this._params).map(function(k) {
        return encodeURIComponent(k)+"="+encodeURIComponent(this._params[k]);
    }.bind(this)).join("&");
};

// FormData polyfill with URL-encoded serialization
function FormData() { this._data = []; }
FormData.prototype.append = function(k,v){ this._data.push([k,v]); };
FormData.prototype.toString = function(){ 
    return this._data.map(function(kv) { return encodeURIComponent(kv[0]) + "=" + encodeURIComponent(kv[1]); }).join("&"); 
};

// URL minimal polyfill
function URL(href, base) {
    if (base && !href.match(/^https?:\/\//)) href = base.replace(/\/+$/,"") + "/" + href.replace(/^\//,"");
    var m = href.match(/^(https?:)\/\/([^/?#]*)(.*?)(\?[^#]*)?(#.*)?$/);
    if (!m) { this.href=href; this.pathname=""; this.search=""; this.hash=""; this.host=""; this.protocol=""; return; }
    this.protocol = m[1]; this.host = m[2]; this.pathname = m[3]||"/";
    this.search = m[4]||""; this.hash = m[5]||"";
    this.href = href;
    this.searchParams = new URLSearchParams(this.search);
}
""".trimIndent()

    private val STRING_HELPERS_JS = """
String.prototype.substringAfter = function(p){ var i=this.indexOf(p); return i===-1?this:this.substring(i+p.length); };
String.prototype.substringAfterLast = function(p){ return this.split(p).pop(); };
String.prototype.substringBefore = function(p){ var i=this.indexOf(p); return i===-1?this:this.substring(0,i); };
String.prototype.substringBeforeLast = function(p){ var i=this.lastIndexOf(p); return i===-1?this:this.substring(0,i); };
String.prototype.substringBetween = function(l,r){
    var i=this.indexOf(l); if(i===-1) return "";
    var li=i+l.length; var ri=this.indexOf(r,li); if(ri===-1) return "";
    return this.substring(li,ri);
};
""".trimIndent()

    private val FETCH_BRIDGE_JS = """
var fetchApi = function(url, init) {
    var method = (init && init.method) ? init.method.toUpperCase() : "GET";
    var headers = (init && init.headers) ? JSON.parse(JSON.stringify(init.headers)) : {};
    var body = null;
    
    if (init && init.body) {
        if (init.body instanceof FormData) {
            body = init.body.toString();
            var hasContentType = Object.keys(headers).some(function(k) { return k.toLowerCase() === 'content-type'; });
            if (!hasContentType) headers['content-type'] = 'application/x-www-form-urlencoded';
        } else if (typeof init.body === "string") {
            body = init.body;
        } else {
            body = JSON.stringify(init.body);
        }
    }
    
    if (init && init.referrer) {
        headers['Referer'] = init.referrer;
    }
    
    var headersJson = JSON.stringify(headers);
    var resultStr = __dantotsuFetch.fetch(url, method, headersJson, body);
    var result = JSON.parse(resultStr);
    var responseHeaders = result.headers || {};
    responseHeaders.get = function(key) {
        if (!key) return null;
        var lk = key.toLowerCase();
        for (var k in result.headers) {
            if (k.toLowerCase() === lk) return result.headers[k];
        }
        return null;
    };
    return Promise.resolve({
        status: result.statusCode,
        statusText: result.reasonPhrase,
        ok: result.statusCode >= 200 && result.statusCode < 300,
        url: result.url || url,
        headers: responseHeaders,
        text: function() { return Promise.resolve(result.body); },
        json: function() { 
            try {
                return Promise.resolve(JSON.parse(result.body));
            } catch(e) {
                return Promise.resolve({});
            }
        },
        body: result.body
    });
};

var fetchText = function(url, init) {
    return fetchApi(url, init).then(function(res) { return res.text(); });
};

var fetch = fetchApi;
globalThis.fetch = fetchApi;
""".trimIndent()

    private val HTMLPARSER_JS = """
var VOID_ELEMENTS = {area:1,base:1,br:1,col:1,embed:1,hr:1,img:1,input:1,link:1,meta:1,param:1,source:1,track:1,wbr:1};
var RAW_TAGS = {script:1,style:1};

function Parser(opts) {
    this.opts = opts || {};
    this._buf = "";
}
Parser.prototype.write = function(html) {
    this._buf += html;
};
Parser.prototype.isVoidElement = function(tag) {
    return !!VOID_ELEMENTS[tag];
};
Parser.prototype.end = function() {
    var buf = this._buf, i = 0, len = buf.length;
    while (i < len) {
        if (buf[i] === '<') {
            // Skip HTML comments
            if (buf[i+1] === '!' && buf[i+2] === '-' && buf[i+3] === '-') {
                var ce = buf.indexOf('-->', i + 4);
                i = ce === -1 ? len : ce + 3;
                continue;
            }
            // Skip DOCTYPE
            if (buf[i+1] === '!' || buf[i+1] === '?') {
                var de = buf.indexOf('>', i + 2);
                i = de === -1 ? len : de + 1;
                continue;
            }
            var closing = buf[i+1] === '/';
            if (closing) i++;
            var j = i+1;
            while (j < len && !/[\s>\/]/.test(buf[j])) j++;
            var tag = buf.slice(i+1, j).toLowerCase();
            i = j;
            var attrs = {};
            var selfClosing = false;
            while (i < len && buf[i] !== '>') {
                while (i < len && /\s/.test(buf[i])) i++;
                if (buf[i] === '>') break;
                if (buf[i] === '/') {
                    selfClosing = true;
                    i++;
                    while (i < len && /\s/.test(buf[i])) i++;
                    if (buf[i] === '>') break;
                }
                var ks = i;
                while (i < len && !/[\s=>\/]/.test(buf[i])) i++;
                var key = buf.slice(ks, i).toLowerCase();
                while (i < len && /\s/.test(buf[i])) i++;
                var val = null;
                if (buf[i] === '=') {
                    i++;
                    while (i < len && /\s/.test(buf[i])) i++;
                    if (buf[i] === '"' || buf[i] === "'") {
                        var q = buf[i++], vs = i;
                        while (i < len && buf[i] !== q) i++;
                        val = buf.slice(vs, i);
                        if (i < len) i++;
                    } else {
                        var vs2 = i;
                        while (i < len && !/[\s>\/]/.test(buf[i])) i++;
                        val = buf.slice(vs2, i);
                    }
                }
                if (key) attrs[key] = val !== null ? val : "";
            }
            if (buf[i] === '>') i++;
            if (closing) {
                if (this.opts.onclosetag) this.opts.onclosetag(tag);
            } else {
                if (this.opts.onopentagname) this.opts.onopentagname(tag);
                if (this.opts.onopentag) this.opts.onopentag(tag, attrs);
                if (selfClosing || VOID_ELEMENTS[tag]) {
                    if (this.opts.onclosetag) this.opts.onclosetag(tag);
                } else if (RAW_TAGS[tag]) {
                    var closeTag = '</' + tag;
                    var ri = buf.toLowerCase().indexOf(closeTag, i);
                    if (ri !== -1) {
                        var rawText = buf.slice(i, ri);
                        if (rawText && this.opts.ontext) this.opts.ontext(rawText);
                        i = ri;
                    }
                }
            }
        } else {
            var ts = i;
            while (i < len && buf[i] !== '<') i++;
            var text = buf.slice(ts, i);
            if (text && this.opts.ontext) this.opts.ontext(text);
        }
    }
    if (this.opts.onend) this.opts.onend();
};
""".trimIndent()

    private val CHEERIO_JSOUP_BRIDGE_JS = """
function load(html) {
    if (typeof html !== 'string') html = String(html || "");
    var rootId = __dantotsuJsoup.parse(html);
    
    function wrap(nodeIds) {
        var obj = {
            _nodes: nodeIds,
            length: nodeIds.length,
            nodeType: 1,
            text: function() { return __dantotsuJsoup.text(JSON.stringify(this._nodes)); },
            attr: function(a, v) { 
                if (this._nodes.length === 0) return undefined;
                if (v !== undefined) {
                    __dantotsuJsoup.setAttr(JSON.stringify(this._nodes), a, String(v));
                    return this;
                }
                return __dantotsuJsoup.attr(JSON.stringify(this._nodes), a) || undefined; 
            },
            removeAttr: function(a) {
                __dantotsuJsoup.removeAttr(JSON.stringify(this._nodes), a);
                return this;
            },
            addClass: function(className) {
                if (className) __dantotsuJsoup.addClass(JSON.stringify(this._nodes), className);
                return this;
            },
            removeClass: function(className) {
                if (className) __dantotsuJsoup.removeClass(JSON.stringify(this._nodes), className);
                return this;
            },
            prop: function(name) {
                if (name === 'outerHTML') return this.outerHtml();
                if (name === 'innerHTML') return this.html();
                if (name === 'tagName') return this.tagName;
                return this.attr(name);
            },
            val: function() {
                return this.attr('value') || this.text();
            },
            data: function(k) {
                return this.attr('data-' + k);
            },
            contents: function() {
                return this.children();
            },
            addBack: function() {
                return this;
            },
            filter: function(fn) {
                if (typeof fn === 'function') {
                    var res = [];
                    for(var i=0; i<this._nodes.length; i++) {
                        var el = wrap([this._nodes[i]]);
                        if (fn.call(el, i, el)) res.push(this._nodes[i]);
                    }
                    return wrap(res);
                }
                return this;
            },
            replaceWith: function(html) {
                return this;
            },
            is: function(sel) {
                if (typeof sel === 'string' && sel) {
                    if (sel.startsWith('.')) return this.hasClass(sel.slice(1));
                    return this.tagName.toLowerCase() === sel.toLowerCase();
                }
                return true;
            },
            not: function(sel) {
                return this;
            },
            clone: function() {
                return wrap(this._nodes.slice());
            },
            empty: function() {
                return this;
            },
            siblings: function(sel) {
                var resStr = __dantotsuJsoup.siblings(JSON.stringify(this._nodes), sel || "");
                return wrap(JSON.parse(resStr));
            },
            closest: function(sel) {
                var resStr = __dantotsuJsoup.closest(JSON.stringify(this._nodes), sel || "");
                return wrap(JSON.parse(resStr));
            },
            parents: function(sel) {
                var resStr = __dantotsuJsoup.parents(JSON.stringify(this._nodes), sel || "");
                return wrap(JSON.parse(resStr));
            },
            slice: function(start, end) {
                return wrap(this._nodes.slice(start, end));
            },
            wrap: function(html) {
                return this;
            },
            unwrap: function() {
                return this;
            },
            after: function(html) {
                return this;
            },
            before: function(html) {
                return this;
            },
            css: function(prop, val) {
                if (val !== undefined) return this.attr('style', (this.attr('style') || '') + ';' + prop + ':' + val);
                return '';
            },
            html: function() { return __dantotsuJsoup.html(JSON.stringify(this._nodes)); },
            outerHtml: function() { return __dantotsuJsoup.outerHtml(JSON.stringify(this._nodes)); },
            remove: function() { __dantotsuJsoup.remove(JSON.stringify(this._nodes)); return this; },
            find: function(sel) {
                var resStr = __dantotsuJsoup.select(JSON.stringify(this._nodes), sel);
                return wrap(JSON.parse(resStr));
            },
            children: function(sel) {
                var resStr = __dantotsuJsoup.children(JSON.stringify(this._nodes), sel || "");
                return wrap(JSON.parse(resStr));
            },
            parent: function() {
                var resStr = __dantotsuJsoup.parent(JSON.stringify(this._nodes));
                return wrap(JSON.parse(resStr));
            },
            hasClass: function(className) {
                return __dantotsuJsoup.hasClass(JSON.stringify(this._nodes), className);
            },
            each: function(fn) {
                for (var i = 0; i < this._nodes.length; i++) {
                    var el = wrap([this._nodes[i]]);
                    if (fn.call(el, i, el) === false) break;
                }
                return this;
            },
            first: function() { return wrap(this._nodes.length > 0 ? [this._nodes[0]] : []); },
            last: function() { return wrap(this._nodes.length > 0 ? [this._nodes[this._nodes.length - 1]] : []); },
            eq: function(i) { return wrap(i >= 0 && i < this._nodes.length ? [this._nodes[i]] : []); },
            get: function(i) {
                 if (i === undefined) return this._nodes.map(function(id) { return wrap([id]); });
                 return wrap([this._nodes[i]]);
            },
            toArray: function() {
                 return this._nodes.map(function(id) { return wrap([id]); });
            },
            map: function(fn) {
                 var res = [];
                 for(var i=0; i<this._nodes.length; i++) {
                     var el = wrap([this._nodes[i]]);
                     var v = fn.call(el, i, el);
                     if (v !== null && v !== undefined) res.push(v);
                 }
                 var wrapObj = wrap([]);
                 wrapObj.get = function() { return res; };
                 wrapObj.join = function(sep) { return res.join(sep); };
                 return wrapObj;
            },
            next: function() {
                 var resStr = __dantotsuJsoup.next(JSON.stringify(this._nodes));
                 return wrap(JSON.parse(resStr));
            },
            prev: function() {
                 var resStr = __dantotsuJsoup.prev(JSON.stringify(this._nodes));
                 return wrap(JSON.parse(resStr));
            },
            trim: function() { return this.text().trim(); },
            toString: function() { return this.text(); }
        };
        Object.defineProperty(obj, 'attribs', {
            get: function() {
                 var map = {};
                 if (nodeIds.length > 0) {
                     try {
                         var str = __dantotsuJsoup.attrs(JSON.stringify(nodeIds));
                         map = JSON.parse(str) || {};
                     } catch(e){}
                 }
                 if (!map.class) map.class = '';
                 return map;
            },
            set: function(v) {},
            enumerable: true,
            configurable: true
        });
        Object.defineProperty(obj, 'tagName', {
            get: function() {
                 if (nodeIds.length === 0) return "";
                 return __dantotsuJsoup.tagName(JSON.stringify(nodeIds));
            },
            enumerable: true
        });
        Object.defineProperty(obj, 'type', { value: 'tag', enumerable: true });
        for(var i=0; i<nodeIds.length; i++) {
            obj[i] = wrap([nodeIds[i]]);
        }
        return obj;
    }
    
    var $ = function(sel, context) {
        if (typeof sel === 'object' && sel !== null && sel._nodes) return sel;
        if (typeof sel === 'number') return wrap([sel]);
        if (Array.isArray(sel)) return wrap(sel);
        if (typeof sel !== 'string') return wrap([]);
        if (sel.trim().startsWith('<')) {
            return load(sel).root().children();
        }
        if (context) {
             if (typeof context === 'object' && context._nodes) {
                  var resStr = __dantotsuJsoup.select(JSON.stringify(context._nodes), sel);
                  return wrap(JSON.parse(resStr));
             }
        }
        var resStr = __dantotsuJsoup.select(JSON.stringify([rootId]), sel);
        return wrap(JSON.parse(resStr));
    };
    
    $.html = function() { return __dantotsuJsoup.outerHtml(JSON.stringify([rootId])); };
    $.root = function() { return wrap([rootId]); };
    return $;
}
""".trimIndent()
}
