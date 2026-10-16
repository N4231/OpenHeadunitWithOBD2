package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.proto.Sensors
import com.google.protobuf.Message

class DeadReckoningEvent(speedKmh: Int)
    : SensorEvent(Sensors.SensorType.DEAD_RECONING_VALUE, makeProto(speedKmh)) {

    companion object {
        private fun makeProto(speedKmh: Int): Message {
            return Sensors.SensorBatch.newBuilder().also {
                it.addDeadReckoning(Sensors.SensorBatch.DeadReckoningData.newBuilder().apply {
                    // wheel_speed uses e3 (mm/s) or similar? Android Auto typically expects mm/s here as well.
                    val speedMmPerSec = (speedKmh * 2500 / 9)
                    wheelSpeed = speedMmPerSec
                }.build())
            }.build()
        }
    }
}
