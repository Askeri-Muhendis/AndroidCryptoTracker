package com.ibrahimethemsen.androidcryptotracker.network.websocket

import com.google.gson.Gson
import com.ibrahimethemsen.androidcryptotracker.model.Difference
import com.ibrahimethemsen.androidcryptotracker.model.WebSocketState
import com.ibrahimethemsen.androidcryptotracker.network.websocket.model.Channel
import com.ibrahimethemsen.androidcryptotracker.network.websocket.model.Subscription
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import javax.inject.Inject

class BtcWebSocket @Inject constructor(
    private val gson: Gson,
    private val okhttpClient : OkHttpClient,
    private val webSocketRequest : Request,
) {
    private lateinit var socket : WebSocket
    private var prevValue : Double = 0.0
    private var nextValue : Double = 0.0
    private var differenceRes = Difference.Stable
    val events : Flow<WebSocketState> = callbackFlow {
        trySendBlocking(WebSocketState.Connecting)
        val listener = websocketListener()
        socket = okhttpClient.newWebSocket(webSocketRequest,listener)
        awaitClose {
            unsubscribe()
            socket.cancel()
        }
    }

    private fun ProducerScope<WebSocketState>.websocketListener() : WebSocketListener{
        return object : WebSocketListener(){
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
                trySendBlocking(WebSocketState.Connected)
                subscribe()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                val channel = gson.fromJson(text, Channel::class.java)
                if (channel.isTrade()){
                    prevValue = nextValue
                    nextValue = channel.data.price
                    var exchangeValue = nextValue - prevValue
                    if (prevValue == 0.0){
                        differenceRes = Difference.Stable
                        exchangeValue = 0.0
                    }else{
                        differenceRes = if (exchangeValue > 0 ){
                            Difference.UP
                        } else if (exchangeValue<0){
                            Difference.Down
                        }else{
                            Difference.Stable
                        }
                    }

                    trySendBlocking(WebSocketState.Price(nextValue,differenceRes,exchangeValue))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                super.onMessage(webSocket, bytes)
                onMessage(webSocket,String(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                trySendBlocking(WebSocketState.Disconnecting)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                trySendBlocking(WebSocketState.Disconnected)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                super.onFailure(webSocket, t, response)
                trySendBlocking(WebSocketState.Error(t.message.toString()))
            }
        }
    }

    private fun subscribe(){
        socket.send(gson.toJson(SUBSCRIBE_MESSAGE))
    }

    private fun unsubscribe(){
        socket.send(gson.toJson(UNSUBSCRIBE_MESSAGE))
    }

    companion object{
        private val SUBSCRIBE_MESSAGE = Subscription(event = "bts:subscribe")
        private val UNSUBSCRIBE_MESSAGE = Subscription(event = "bts:unsubscribe")
    }
}

