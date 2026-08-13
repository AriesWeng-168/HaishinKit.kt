package com.haishinkit.rtmp.message

import com.haishinkit.rtmp.RtmpConnection
import com.haishinkit.rtmp.RtmpObjectEncoding
import com.haishinkit.rtmp.amf.AmfTypeBuffer
import com.haishinkit.rtmp.event.Event
import java.nio.ByteBuffer

/**
 *  7.1.1. Command Message (20, 17)
 */
internal class RtmpCommandMessage(
    objectEncoding: RtmpObjectEncoding,
) : RtmpMessage(objectEncoding.commandType) {
    var commandName: String? = null
    var transactionID = 0
    var commandObject: Map<String, Any?>? = null
    var arguments: List<Any?> = mutableListOf()
    override var length: Int = CAPACITY

    override fun encode(buffer: ByteBuffer): RtmpMessage {
        val serializer = AmfTypeBuffer(buffer)
        serializer.putString(commandName)
        serializer.putNumber(transactionID.toDouble())
        serializer.putMap(commandObject)
        for (`object` in arguments) {
            serializer.putData(`object`)
        }
        return this
    }

    override fun decode(buffer: ByteBuffer): RtmpMessage {
        val position = buffer.position()
        val deserializer = AmfTypeBuffer(buffer)
        commandName = deserializer.string
        transactionID = deserializer.number.toInt()
        commandObject = deserializer.map
        val arguments = ArrayList<Any?>()
        while (buffer.position() - position != length) {
            arguments.add(deserializer.data)
        }
        this.arguments = arguments
        return this
    }

    override fun execute(connection: RtmpConnection): RtmpMessage {
        val responders = connection.responders

        if (responders.containsKey(transactionID)) {
            android.util.Log.i("RtmpCommandMessage", "matched: name=$commandName txn=$transactionID")
            val responder = responders[transactionID]
            when (commandName) {
                "_result" -> {
                    responder?.onResult(arguments)
                    // obslive.16：配對成功也要移除 —— 原版讓 responder 永遠留在表裡，
                    // 下面的 txn=0 寬鬆配對會撿到這些殭屍、把回應餵錯對象。
                    responders.remove(transactionID)
                    return this
                }

                "_error" -> {
                    responder?.onStatus(arguments)
                    responders.remove(transactionID)
                    return this
                }
            }
            responders.remove(transactionID)
            return this
        }

        // obslive.16：Cloudflare 的部分邊緣節點回覆 _result 時**不回顯 transaction id（一律 0）**，
        // 嚴格比對永遠配不到 —— createStream 的回應（stream id）掉進下面的 catch-all、被當成
        // 壞狀態，活連線被自己殺掉；因為是 anycast、各節點行為不一，表現為**間歇失敗**
        // （2026-08-13 真機實錄：送出 txn=2、responders=[2]，CF 回 _result txn=0 args=[1.0]）。
        //
        // 依 ffmpeg 的語意寬鬆配對：txn=0 且有待回應命令時，交給**最早送出的那一個**（FIFO）——
        // 伺服器按送出順序處理命令，按序配對即正確。只在 txn=0 時放寬：正常伺服器（mediamtx 等）
        // 回顯真實 txn，直接走上面的嚴格配對，行為不變。
        if (transactionID == 0 &&
            (commandName == "_result" || commandName == "_error") &&
            responders.isNotEmpty()
        ) {
            val entry = responders.entries.minByOrNull { it.key }
            if (entry != null) {
                android.util.Log.i(
                    "RtmpCommandMessage",
                    "loose-matched: name=$commandName txn=0 -> pending txn=${entry.key}",
                )
                when (commandName) {
                    "_result" -> entry.value.onResult(arguments)
                    else -> entry.value.onStatus(arguments)
                }
                responders.remove(entry.key)
                return this
            }
        }

        when (commandName) {
            "close" -> {
                connection.close()
            }

            "onStatus" -> {
                val stream = connection.streams[streamID]
                stream?.dispatchEventWith(
                    Event.RTMP_STATUS,
                    false,
                    if (arguments.isEmpty()) null else arguments[0],
                )
            }

            else -> {
                // obslive.15：這條路＝「沒有 responder 配對到的伺服器命令」。把身分記下來 ——
                // 2026-08-13 CF 直推失敗查了一整天，就是因為這裡什麼都不記，
                // 只看得到 arguments[0] 一個裸值（實測 1.0），連命令名都不知道。
                android.util.Log.w(
                    "RtmpCommandMessage",
                    "unhandled command: name=$commandName txn=$transactionID streamID=$streamID " +
                        "args=$arguments responders=${connection.responders.keys}",
                )
                connection.dispatchEventWith(
                    Event.RTMP_STATUS,
                    false,
                    if (arguments.isEmpty()) null else arguments[0],
                )
            }
        }

        return this
    }

    companion object {
        private const val CAPACITY = 1024
    }
}
