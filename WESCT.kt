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

