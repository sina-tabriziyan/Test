interface OnWebsocketEventOccurred {
    fun isTyping(socketResponse: SocketResponse)
    fun removeIsTyping(socketResponse: SocketResponse)
    fun newMessage(socketResponse: SocketResponse)
    fun changeTopic(socketResponse: SocketResponse)
    fun seenMessage(socketResponse: SocketResponse)
}
//
class WebsocketListener(private val coroutineScope: CoroutineScope) : WebSocketListener() {

    companion object {
        val chatEventChannel: Channel<SocketResponse> = Channel()
        val notifyEventChannel: Channel<SocketResponse> = Channel()
        val pingSocketChannel: Channel<String> = Channel(1)
        lateinit var webSocketObj: WebSocket
    }

    fun closeChatEventChannel() = chatEventChannel.close()
    fun closeNotifyEventChannel() = notifyEventChannel.close()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocketObj = webSocket
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        coroutineScope.launch {
            val socketResponse = convertStrToSocketResponse(text)
            try {
                socketResponse?.let {
                    if (NotificationHelper.socketEvent(socketResponse) == SocketEvents.NEW_CHET_EVENT)
                        notifyEventChannel.send(socketResponse)

                    when (NotificationHelper.socketEvent(socketResponse)) {
                        SocketEvents.NEW_CHET_EVENT -> {
                            chatEventChannel.send(socketResponse)
                            yield()
                        }

                        SocketEvents.WEB_SOCKET_EVENT_REMOVE_IS_TYPING -> {
                            chatEventChannel.send(socketResponse)
                            yield()
                        }

                        SocketEvents.WEB_SOCKET_EVENT_IS_TYPING -> {
                            chatEventChannel.send(socketResponse)
                            yield()
                        }

                        SocketEvents.SEEN_MESSAGE, SocketEvents.SEEN_MESSAGE_GROUP -> {
                            chatEventChannel.send(socketResponse)
                            yield()
                        }

                        SocketEvents.WEB_SOCKET_EVENT_IS_DELETE -> {
                            chatEventChannel.send(socketResponse)
                            yield()
                        }

                        SocketEvents.WEB_SOCKET_EVENT_IS_EDIT -> {
                            chatEventChannel.send(socketResponse)
                            yield()
                        }

                        SocketEvents.WEB_SOCKET_EVENT_IS_CHANGE_TOPIC -> {
                            chatEventChannel.send(socketResponse)
                            yield()
                        }
                        else -> {
                            if (socketResponse.detail.toString() != "PONG" &&
                                NotificationHelper.socketEvent(socketResponse) != null
                            ) {
                                notifyEventChannel.send(socketResponse)
                                yield()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        coroutineScope.launch {
            closeChatEventChannel()
            closeNotifyEventChannel()
        }
        webSocket.close(WebSProvider.NORMAL_CLOSURE_STATUS, null)
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        t.message
        coroutineScope.launch {
            closeChatEventChannel()
            closeNotifyEventChannel()
        }
    }

    fun convertStrToSocketResponse(jsonStr: String): SocketResponse? {
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val type = Types.newParameterizedType(SocketResponse::class.java, String::class.java)
        val jsonAdapter = moshi.adapter<SocketResponse?>(type)
        return jsonAdapter.fromJson(jsonStr)
    }
}
//
data class WebsocketInfoModel(
    val domain: String,
    val sid: String
)
//
object WebsocketMiddleware  {
   private lateinit var onWebsocketEventOccurred: OnWebsocketEventOccurred

    fun initial(onWebsocketEventOccurred: OnWebsocketEventOccurred) {
        this.onWebsocketEventOccurred = onWebsocketEventOccurred
        middleware()
    }

    private fun middleware(){
        GlobalScope.launch(Dispatchers.IO){
            WebsocketListener.chatEventChannel.receiveAsFlow().collect {socketResponse->
                when(NotificationHelper.socketEvent(socketResponse)){
                    SocketEvents.WEB_SOCKET_EVENT_IS_TYPING->{
                        onWebsocketEventOccurred.isTyping(socketResponse)
                    }
                    SocketEvents.WEB_SOCKET_EVENT_REMOVE_IS_TYPING->{
                        onWebsocketEventOccurred.removeIsTyping(socketResponse)
                    }
                    SocketEvents.WEB_SOCKET_EVENT_IS_CHANGE_TOPIC->{
                        onWebsocketEventOccurred.changeTopic(socketResponse)
                    }
                    SocketEvents.NEW_CHET_EVENT->{
                        onWebsocketEventOccurred.newMessage(socketResponse)
                    }
                    SocketEvents.SEEN_MESSAGE , SocketEvents.SEEN_MESSAGE_GROUP->{
                        onWebsocketEventOccurred.seenMessage(socketResponse)
                    }
                    SocketEvents.WEB_SOCKET_EVENT_IS_EDIT->{
                        if (onWebsocketEventOccurred is ChatSocketEvent)
                            (onWebsocketEventOccurred as ChatSocketEvent).editMessage()
                    }
                    SocketEvents.WEB_SOCKET_EVENT_IS_DELETE->{
                        if (onWebsocketEventOccurred is ChatSocketEvent)
                            (onWebsocketEventOccurred as ChatSocketEvent).deleteMessage()
                    }
                    else->{

                    }
                }
            }
        }
    }
}
//
class WebSProvider(
    private val websocketInfoModel: WebsocketInfoModel, ) {
    var webSocket: WebSocket? = null

    private val socketOkHttpClient = OkHttpClient.Builder().getUnsafeOkHttpClient()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(39, TimeUnit.SECONDS)
        .hostnameVerifier { _, _ -> true }
        .build()

    private var webSocketListener: WebsocketListener? = null

    fun startSocket() {
        val coroutineScope = CoroutineScope(Dispatchers.Default)
        val listener = WebsocketListener(coroutineScope)
        startSocket(listener)
    }

    private fun startSocket(webSocketListener: WebsocketListener) {
        this.webSocketListener = webSocketListener

        val request = Request.Builder()
            .url(websocketInfoModel.domain)
            .addHeader("Cookie", websocketInfoModel.sid)
            .build()

        webSocket = socketOkHttpClient.newWebSocket(request, webSocketListener)
        webSocket?.send("PING")
        socketOkHttpClient.dispatcher.executorService.shutdown()
    }

    fun stopSocket() {
        try {
            webSocket?.close(NORMAL_CLOSURE_STATUS, null)
            webSocket = null
            webSocketListener?.closeChatEventChannel()
            webSocketListener?.closeNotifyEventChannel()
            webSocketListener = null
        } catch (ex: Exception) {
            throw ex
        }
    }

    companion object {
        const val NORMAL_CLOSURE_STATUS = 1000
    }

    fun OkHttpClient.Builder.getUnsafeOkHttpClient(): OkHttpClient.Builder {
        try {
            val trustAllCerts = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
            object : X509TrustManager {
                override fun checkClientTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun checkServerTrusted(
                    chain: Array<out X509Certificate>?,
                    authType: String?
                ) {
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            val delegate = sslContext.socketFactory

            sslSocketFactory(delegate, trustAllCerts[0] as X509TrustManager)
            hostnameVerifier { _, _ -> true }

            return this
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}

//
class WebsocketService : Service() {
    @Inject
    lateinit var sharePref: SharePref

    @Inject
    lateinit var socketRepository: SocketRepository

    @Inject
    lateinit var baseVm: BaseVm

    lateinit var webSProvider: WebSProvider

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        (application as TeamyarApplication).applicationGraph.inject(this)
        webSProvider = initialWebSProvider()
        webSProvider.startSocket()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationCompat = NotificationCompat.Builder(this, TeamyarApplication.CHANNEL_ID).setSmallIcon(R.drawable.logo)
            .setContentTitle(getString(R.string.app_name)).build()

        setupPing()

        GlobalScope.launch {
            WebsocketListener.notifyEventChannel.consumeEach { socketResponse ->
                    socketResponse.content?.let {
                        val content: NotifyContent? = socketResponse.toContent()
                       var notificationsId= content?.let { it1 -> notificationId(it1) }
                        var showNotification=true
                        if (notificationsId == 2) {
                            if (!baseVm.notifyType(SharePrefConstant.NOTIFY_SPECIAL)) {
                                showNotification = false
                            }
                        }

                        if (notificationsId == 3) {
                            if (!baseVm.notifyType(SharePrefConstant.NOTIFY_NORMAL)) {
                                showNotification = false
                            }
                        }

                        if (notificationsId == 4) {
                            if (!baseVm.notifyType(SharePrefConstant.NOTIFY_UNIMPORTANT)) {
                                showNotification = false
                            }
                        }

                        if (showNotification) {
                            val builder = NotificationCompat.Builder(this@WebsocketService,
                                TeamyarApplication.CHANNEL_ID_UNIMPORTANT)
                                .setSmallIcon(R.drawable.logo)
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setContent(
                                    remoteViews(content)
                                ).build()

                            synchronized(builder) {
                                PermissionUtils.requestPermission(
                                    this@WebsocketService,
                                    permission = arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                    deniedMsg = resources.getString(R.string.deny_permission_message),
                                    onPermissionDenied = {

                                    },
                                    onPermissionGranted = {
                                        (application as TeamyarApplication).notificationManager.notify(11, builder)
                                    }
                                )
                            }
                        }
                    }
            }
        }

        startForeground(1235, notificationCompat)
        return START_STICKY
    }

    private fun setupPing() {
        val timer = Timer()

        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    WebsocketListener.webSocketObj.send("PING")
                } catch (_: Exception) {

                }
            }
        }, 3000, 10000)
    }

    private fun initialWebSProvider() = WebSProvider(
        WebsocketInfoModel(
            "wss://${sharePref.getString(SharePrefConstant.DOMAIN)}/events/get/",
            sharePref.getString(SharePrefConstant.SID)
        )
    )

    private fun remoteViews(content: NotifyContent?): RemoteViews? {
        return content?.statusMap(resources)?.get("${content.status}")?.let { status ->
            NotificationHelper.notificationCustomView(
                content.title!!,
                convertFiletimeToDate(content.tid!!),
                status,
                1,
                notificationId(content),
                getStringResourceByName("icon_" + "module" + content.moduleId),
                this
            )
        }
    }

    private fun notificationId(notifyContent: NotifyContent): Int {
        val type = checkNotifyType(notifyContent, socketRepository.notifyColor)

        when (type) {
            "Special" -> return 2
            "Normal" -> return 3
            "Unimportant" -> return 4
            else -> return 4
        }
    }

    private fun convertFiletimeToDate(filetime: Long): String {
        val arrDate: ArrayList<String>
        val fileTimeConvertor = FileTimeConvertor()
        arrDate = fileTimeConvertor.Picker(
            filetime,
            baseVm.timeZone()!!.toLong(),
            baseVm.userPrefModel()?.daylightRange,
            Integer.valueOf(baseVm.userPrefModel()?.calendarType!!)
        )
        return arrDate[0] + "/" + arrDate[1] + "/" + arrDate[2] + "   " + arrDate[3] + ":" + arrDate[4]
    }

    private fun getStringResourceByName(aString: String): String {
        val packageName: String = this.packageName
        val resId: Int = this.resources.getIdentifier(aString, "string", packageName)
        return this.getString(resId)
    }
}

