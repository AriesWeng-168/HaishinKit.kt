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
                    return this
                }

                "_error" -> {
                    responder?.onStatus(arguments)
                    return this
                }
            }
            responders.remove(transactionID)
            return this
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
